package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA reproduction: Zamboni with a manual angle on the reference map, against Zigzag with the same angle. A manual angle
 * that Zigzag mows must not be refused by Zamboni (its passes are the zigzag ones). Diagnosis of a refusal: the
 * unscheduled Zamboni geometry is scheduled as in [AutomaticPlanner] once as Zamboni (chained runs) and once relabelled
 * Zigzag (no chain), and its runs are checked in both directions by the chassis envelope.
 */
class ZamboniManualAngleReproTest {
    private val shapes: List<MapShape> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val zones = shapes.filter { it.kind == ShapeKind.ZONE }
    private fun request(id: Long, angle: Int, spacing: Double = 0.25) = PlanningRequest(map, listOf(id), base.point,
        config = PlanningConfig(spacingM = spacing, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
            orientation = OrientationMode.MANUAL, manualAngleDeg = angle),
        navigation = PlanningNavigation(emptyList(), base))

    /** Every manual angle (5° steps) on Aire de jeux, every 15° on the other zones: Zamboni valid wherever Zigzag is. */
    @Test(timeout = 3_600_000) fun zamboniAcceptsEveryManualAngleThatZigzagMows() {
        val failures = mutableListOf<String>()
        for (zone in zones) {
            val step = if (zone.name.trim() == "Aire de jeux") 5 else 15
            for (angle in 0 until 180 step step) {
                val req = request(zone.id, angle)
                fun status(mode: CoverageMode) = try { RoutePlanning.calculate(req, mode).selected.let { "${it.status} ${it.diagnostics}" to it.valid } }
                    catch (e: IllegalArgumentException) { "refused ${e.message}" to false }
                val (zs, zv) = status(CoverageMode.LINES); val (as_, av) = status(CoverageMode.ZAMBONI)
                if (zv && !av) failures += "${zone.name.trim()} manual $angle°: Zamboni $as_ (zigzag $zs)"
                if (zv != av) println("QA2 MANUAL ${zone.name.trim()} $angle°: zigzag $zs | Zamboni $as_")
            }
        }
        println("QA2 MANUAL failures: ${failures.size}\n" + failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Aire de jeux, manual 0°: where the scheduling of the Zamboni geometry fails. */
    @Test(timeout = 600_000) fun diagnosisAireDeJeuxManualZero() {
        val zone = zones.single { it.name.trim() == "Aire de jeux" }
        val req = request(zone.id, 0).snapshot()
        val t = PlanningTerrain(req); val evaluator = MissionEvaluator(t, TransitionPlanner(t)); val c = req.config
        val leads = NoGoLoopLeads(t.holes, req.robot, c.outerOffsetM)
        fun geometry(mode: CoverageMode) = CoveragePlanner.plan(zone.points, t.holes, c.spacingM, c.marginM, mode, c.borders,
            c.borderPasses, c.noGoPasses, t.obstacleDiscs, req.robot, true, req.start, sweepOptions = SweepOptions(listOf(0)),
            continuous = true, outerOffsetM = c.outerOffsetM, turnStyle = c.turnStyle, speedMps = c.speedMps, cutWidthM = c.cutWidthM)
        fun schedule(p: Preview): String = try {
            val s = LocalMowingOrder.schedule(p, req.start, c.bordersFirst, c.nearbyNoGo,
                { before, after -> evaluator.linkCost(before, after) }, { t.envelope.pathCollision(it) == null }, {},
                entryDistance = { evaluator.linkDistance(null, it) },
                loopSafe = { path -> t.envelope.pathCollision(path, finalHeading = path.zipWithNext().firstOrNull { (a, b) -> a.distance(b) > 1e-9 }
                    ?.let { (a, b) -> Math.toDegrees(kotlin.math.atan2(b.y - a.y, b.x - a.x)) }) == null },
                noGoLoopLead = leads::lead)
            "scheduled, ${s.cuttingPaths.size} paths"
        } catch (e: IllegalArgumentException) { "FAILS: ${e.message}" }
        val zigzag = geometry(CoverageMode.LINES); val zamboni = geometry(CoverageMode.ZAMBONI)
        val zigzagScheduled = schedule(zigzag)
        val zamboniScheduled = schedule(zamboni)
        val zamboniAsZigzag = schedule(zamboni.copy(mode = CoverageMode.LINES))
        val oneWay = zamboni.runs.indices.filter { i ->
            (t.envelope.pathCollision(zamboni.runs[i]) == null) != (t.envelope.pathCollision(zamboni.runs[i].reversed()) == null) }
        val unsafe = zamboni.runs.indices.filter { t.envelope.pathCollision(zamboni.runs[it]) != null }
        val chains = zamboni.runCellIds.groupBy { it }.filterValues { it.size > 1 }.mapValues { it.value.size }
        println("QA2 DIAG Aire de jeux 0°: zigzag ${zigzag.runs.size} runs → $zigzagScheduled | Zamboni ${zamboni.runs.size} runs, " +
            "cells with several runs $chains → as Zamboni: $zamboniScheduled; relabelled Zigzag (no chain): $zamboniAsZigzag | " +
            "runs refused forwards $unsafe, runs drivable one way only $oneWay")
    }
}
