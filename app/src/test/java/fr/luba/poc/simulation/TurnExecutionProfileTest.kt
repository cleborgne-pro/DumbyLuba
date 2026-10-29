package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.planning.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in bench of the turns (-PexecutionProfile=true, step C0 of docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): per
 * manoeuvre metrics ([RouteManoeuvres], [RouteExecutionModel.TurnStats]) of the rolling U-turns and other curves driven by
 * the tracking loop of the actual mowing, for the robots of [RouteExecutionRegressionTest] (same seeds):
 * one isolated rolling U of the Turn Optimizer (passes 0.75 m apart, 3 m long), Lawn friendly routes of Potager and
 * Aire de jeux, Zamboni routes of Potager and Jardin d'Eden. Options: `executionProfileSpeeds` (default 30|63|100),
 * `executionProfileZones`. See docs/TRACKING_EXECUTION.md.
 */
class TurnExecutionProfileTest {
    private val robots = TurnBench.robots

    @Test fun profileTurns() {
        assumeTrue(System.getProperty("executionProfile") == "true")
        val speeds = System.getProperty("executionProfileSpeeds")?.split('|')?.map { it.toInt() } ?: listOf(30, 63, 100)
        val filter = System.getProperty("executionProfileZones")?.split('|')
        val cases = mutableListOf<Pair<String, Pair<List<SimEdge>, Point?>>>()
        if (filter == null || "U" in filter) {
            cases += "isolated U" to (TurnBench.isolatedU() to null)
            cases += "isolated U warm" to (TurnBench.isolatedU() to null)
        }
        val (map, base) = ReferenceRoutes.referenceMap()
        for (name in listOf("Potager", "Aire de jeux")) if (filter == null || name in filter) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            cases += "$name lawn" to ReferenceRoutes.routeEdges(map, base, zone, TurnStyle.LAWN)
        }
        for (name in listOf("Potager", "Jardin d’Eden")) if (filter == null || "$name zamboni" in filter || "zamboni" in filter) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
                config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE, turnStyle = TurnStyle.LAWN),
                navigation = PlanningNavigation(emptyList(), base)), CoverageMode.ZAMBONI)
            cases += "$name zamboni" to ReferenceRoutes.routeEdges(map, base, zone, plan)
        }
        for ((label, route) in cases) {
            val (edges, approach) = route
            val windows = RouteManoeuvres.find(edges)
            println("TURNS $label: ${edges.size} edges, ${windows.count { it.kind == RouteManoeuvres.Kind.U_TURN }} rolling U, " +
                "${windows.count { it.kind == RouteManoeuvres.Kind.LOOP }} loops, ${windows.count { it.kind == RouteManoeuvres.Kind.CURVE }} curves")
            val runs = speeds.flatMap { speed -> robots.map { speed to it } }.parallelStream()
                .map { (speed, robot) -> Triple(speed, robot, TurnBench.drive(edges, robot, speed, approach, windows, warm = label.endsWith("warm"))) }.toList()
            for (speed in speeds) {
                val atSpeed = runs.filter { it.first == speed }
                for ((_, robot, run) in atSpeed) println("TURNS $label $speed % [$robot] ${TurnBench.summary(run)}")
                val all = atSpeed.map { it.third }
                println("TURNS SUMMARY $label $speed % nominal ${TurnBench.summary(all.first())} | all robots ${TurnBench.summary(all)}")
            }
        }
    }
}

/** Shared by the turn bench and the acceptance test of the rolling U-turns. */
object TurnBench {
    val robots = listOf(RouteExecutionModel.Robot(),
        RouteExecutionModel.Robot(delayMs = 800, degPerUnit = 0.13, seed = 813),
        RouteExecutionModel.Robot(delayMs = 400, degPerUnit = 0.09, seed = 409),
        RouteExecutionModel.Robot(delayMs = 600, degPerUnit = 0.09, headingError = 20.0, seed = 609))

    /** [warm]: rotation and speed gains already calibrated (a U in the middle of a mission), the robot's true gains. */
    fun drive(edges: List<SimEdge>, robot: RouteExecutionModel.Robot, speed: Int, approach: Point?, windows: List<RouteManoeuvres.Manoeuvre>,
              warm: Boolean = false) = RouteExecutionModel.drive(edges, robot, speed, approach, windows = windows,
        calibration = if (warm) DeadReckoning.Calibration(robot.metersPerUnit, robot.degPerUnit) else null)

    /** One rolling U of the Turn Optimizer between two 3 m passes 0.75 m apart (three 25 cm spacings, Lawn friendly). */
    fun isolatedU(lateral: Double = 0.75, passM: Double = 3.0): List<SimEdge> {
        val aFrom = Point(0.0, 0.0); val aEnd = Point(passM, 0.0)
        val bStart = Point(passM, lateral); val bTo = Point(0.0, lateral)
        val plan = TurnOptimizer.candidates(aFrom, aEnd, bStart, bTo, TurnOptimizer.TurnContext(0.25, 0.3, drivable = { true }))
            .single { it.kind == TurnKind.U_ARC }
        check(plan.possible) { plan.refusal ?: "" }
        val points = listOf(aFrom) + plan.points + listOf(bTo)
        return points.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-9 }.map { (a, b) -> SimEdge(a, b, true) }
    }

    data class Totals(val uCount: Int, val uSeconds: Double, val uAfterSeconds: Double, val uSpeed: Double, val uMedian: Double,
                      val uP90: Double, val uMax: Double, val uAfterMax: Double, val uPivots: Int,
                      val curveCount: Int, val curveP90: Double, val curveMax: Double, val curvePivots: Int)

    fun totals(runs: List<RouteExecutionModel.Run>): Totals {
        val us = runs.flatMap { r -> r.turns.filter { it.window.turning } }
        val curves = runs.flatMap { r -> r.turns.filter { it.window.kind == RouteManoeuvres.Kind.CURVE } }
        return Totals(us.size, us.map { it.seconds }.average().orZero(), us.map { it.afterSeconds }.average().orZero(),
            TurnBench.quantile(us.map { it.medianSpeed }, 0.5), TurnBench.quantile(us.flatMap { it.deviations }, 0.5),
            TurnBench.quantile(us.flatMap { it.deviations }, 0.9), us.maxOfOrNull { it.trueMax } ?: 0.0,
            us.maxOfOrNull { it.afterMax } ?: 0.0, us.sumOf { it.pivots },
            curves.size, TurnBench.quantile(curves.flatMap { it.deviations }, 0.9), curves.maxOfOrNull { it.trueMax } ?: 0.0,
            curves.sumOf { it.pivots })
    }

    fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    fun summary(run: RouteExecutionModel.Run) = summary(listOf(run)) + " | mission %.0f s, worst %.3f m, %d in stripe, %d blocked"
        .format(run.seconds, run.worst, run.midPivots, run.blocked)

    fun summary(runs: List<RouteExecutionModel.Run>): String = totals(runs).let { t ->
        ("U/loops %d: %.1f s (+%.1f s for the next metre), %.3f m/s, deviation median %.3f p90 %.3f max %.3f m, after %.3f m, %d pivots in arc · " +
            "curves %d: p90 %.3f max %.3f m, %d pivots").format(t.uCount, t.uSeconds, t.uAfterSeconds, t.uSpeed, t.uMedian, t.uP90,
            t.uMax, t.uAfterMax, t.uPivots, t.curveCount, t.curveP90, t.curveMax, t.curvePivots)
    }
}
