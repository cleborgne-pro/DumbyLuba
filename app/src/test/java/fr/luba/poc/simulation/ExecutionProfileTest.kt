package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in bench (-PexecutionProfile=true): modelled execution ([RouteExecutionModel]) of the route actually
 * prepared for each zone of the reference map (computation, navigation and preparation of [MapScreen],
 * saved base), with the actual mowing tracking loop. Measures the estimated physical duration, the
 * precision and the time lost in pivots, stops and slow advance, for several robots (delay, gains).
 * Options: `executionProfileZones` (names separated by |), `executionProfileSpeeds` (e.g. 50|100), `executionProfileWarm`,
 * `executionProfileRobots` (`nominal` or all by default), `executionProfileSeeds` (draws per robot),
 * `executionProfileVerbose`. See docs/TRACKING_EXECUTION.md.
 */
class ExecutionProfileTest {
    private fun referenceMap() = ReferenceRoutes.referenceMap()
    private fun routeEdges(map: RobotMap, base: MapBase, zone: MapShape) = ReferenceRoutes.routeEdges(map, base, zone)

    /** Trace of one case (-PtraceZone, -PtraceSpeed, -PtraceDelay, -PtraceDegs, -PtraceSeed, -PtraceHeadingError, -PtraceFrom, -PtraceEdges). */
    @Test fun traceExecution() {
        val zoneName = System.getProperty("traceZone")
        assumeTrue(System.getProperty("executionProfile") == "true" && zoneName != null)
        val (map, base) = referenceMap()
        val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == zoneName }
        val (edges, approach) = routeEdges(map, base, zone)
        val from = System.getProperty("traceFrom")?.toInt() ?: 0
        val count = System.getProperty("traceEdges")?.toInt() ?: 10
        edges.withIndex().drop(maxOf(0, from - 3)).take(count + 3).forEach { (i, e) ->
            println("TRACE edge %d (%.3f;%.3f)→(%.3f;%.3f) %.3f m heading %.1f° turn %.1f° blades %s".format(i, e.a.x, e.a.y, e.b.x, e.b.y, e.meters,
                PathFollower.headingOf(e), if (i > 0) PathFollower.turnDegrees(edges[i - 1], e) else 0.0, e.bladesOn)) }
        val robot = RouteExecutionModel.Robot(delayMs = System.getProperty("traceDelay")?.toLong() ?: 600,
            degPerUnit = System.getProperty("traceDegs")?.toDouble() ?: 0.11, seed = System.getProperty("traceSeed")?.toLong() ?: 1,
            headingError = System.getProperty("traceHeadingError")?.toDouble() ?: 0.0)
        val run = RouteExecutionModel.drive(edges, robot, System.getProperty("traceSpeed")?.toInt() ?: 50, approach,
            traceEdges = (from - 2)..(from + count), trace = { println("TRACE $it") })
        println("TRACE bilan : $run")
    }

    @Test fun profileExecution() {
        assumeTrue(System.getProperty("executionProfile") == "true")
        val (map, base) = referenceMap()
        val filter = System.getProperty("executionProfileZones")?.split('|')
        val speeds = System.getProperty("executionProfileSpeeds")?.split('|')?.map { it.toInt() } ?: listOf(30, 50, 100)
        val robots = if (System.getProperty("executionProfileRobots") == "nominal") listOf(RouteExecutionModel.Robot())
        else listOf(RouteExecutionModel.Robot()) + (1..(System.getProperty("executionProfileSeeds")?.toInt() ?: 1)).flatMap { seed ->
            listOf(400L, 600L, 800L).flatMap { delay -> listOf(0.09, 0.13).map { degs ->
                RouteExecutionModel.Robot(delayMs = delay, degPerUnit = degs, headingError = if (delay == 600L) 20.0 else 0.0,
                    seed = delay + (degs * 100).toLong() + 1000L * (seed - 1)) } } }
        val verbose = System.getProperty("executionProfileVerbose") == "true"
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && (filter == null || it.name.trim() in filter) }
        data class Row(val zone: String, val speed: Int, val robot: RouteExecutionModel.Robot, val run: RouteExecutionModel.Run)
        val rows = mutableListOf<Row>()
        for (zone in zones) {
            val (edges, approach) = routeEdges(map, base, zone)
            val sharp = edges.zipWithNext().count { (a, b) -> PathFollower.turnDegrees(a, b) > PathFollower.CORNER_DEGREES }
            val stops = PathFollower.stopVertices(edges, 0, edges.size).count { it }
            println("EXEC ${zone.name.trim()}: ${edges.size} edges, $sharp corners > ${PathFollower.CORNER_DEGREES.toInt()}°, $stops stops (tight rounded turns included), %.1f m"
                .format(edges.sumOf { it.meters }))
            // Option: calibration kept from a previous mission (same zone, another noise draw).
            val warm = System.getProperty("executionProfileWarm") == "true"
            rows += speeds.flatMap { speed -> robots.map { robot -> speed to robot } }.parallelStream().map { (speed, robot) ->
                val calibration = if (!warm) null else
                    RouteExecutionModel.drive(edges, robot.copy(seed = robot.seed + 7919), speed, approach).calibration
                Row(zone.name.trim(), speed, robot, RouteExecutionModel.drive(edges, robot, speed, approach, calibration = calibration))
            }.toList()
        }
        for (row in rows) if (verbose || row.robot == robots.first())
            println("EXEC ${row.zone} ${row.speed} % [${row.robot}] : ${row.run}")
        // Summary per speed: nominal robot (duration) and worst case over all robots (precision, stops).
        for (speed in speeds) {
            val nominal = rows.filter { it.speed == speed && it.robot == robots.first() }
            val all = rows.filter { it.speed == speed }
            val seconds = nominal.sumOf { it.run.seconds }; val meters = nominal.sumOf { it.run.meters }
            println(("EXEC SUMMARY " + speed + " %% : nominal %.0f s (%.3f m/s ; pivot %.0f s, heading not measured %.0f s, slow %.0f s, stop %.0f s, %d pivots) · " +
                "all robots %.0f s · %d/%d missions interrupted (> 30 cm), mean of worst deviations %.3f m, worst deviation %.3f m, %d blockages, %d pivots in stripe, max RMS %.3f m, max ends %.2f m, %d resumes")
                .format(seconds, meters / seconds, nominal.sumOf { it.run.pivotSeconds }, nominal.sumOf { it.run.unconfirmedSeconds },
                    nominal.sumOf { it.run.slowSeconds }, nominal.sumOf { it.run.stoppedSeconds }, nominal.sumOf { it.run.pivots },
                    all.sumOf { it.run.seconds }, all.count { it.run.violations > 0 }, all.size, all.map { it.run.worst }.average(),
                    all.maxOf { it.run.worst }, all.sumOf { it.run.blocked },
                    all.sumOf { it.run.midPivots }, all.maxOf { it.run.rmsStraight }, all.maxOf { it.run.endErrors.max() }, all.sumOf { it.run.approaches }))
            all.maxBy { it.run.worst }.let { println("EXEC PIRE " + speed + " % : ${it.zone} [${it.robot}] ${it.run.where}") }
        }
    }
}
