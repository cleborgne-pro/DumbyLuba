package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.atan2

/**
 * QA of the Zamboni mode (step 2) on the reference map and on synthetic zones, through the production entry points:
 * validity, coverage against Zigzag, an independent chassis-envelope check of every driven path, front width, entry and
 * end of the mission, edge cases (holes, obstacles, small cells, fine spacing, manual angle, change every session, height
 * passes, cancellation) and persistence (checkpoint store, resume in the middle of an arc).
 */
abstract class ZamboniQaFixture {
    @get:Rule val folder = TemporaryFolder()

    protected val shapes: List<MapShape> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    protected val map = RobotMap(shapes, true, "reference-station")
    protected val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    protected val zones = shapes.filter { it.kind == ShapeKind.ZONE }
    protected val radius = TurnOptimizer.FOLLOW_RADIUS_M

    protected fun request(ids: List<Long>, spacing: Double = 0.25, style: TurnStyle = TurnStyle.FAST, start: Point = base.point,
                        obstacles: List<PlanningObstacle> = emptyList(), bordersFirst: Boolean = true) =
        PlanningRequest(map, ids, start,
            config = PlanningConfig(spacingM = spacing, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
                turnStyle = style, bordersFirst = bordersFirst),
            obstacles = obstacles, navigation = PlanningNavigation(emptyList(), base), end = if (start == base.point) null else base.point)

    protected fun <T> timed(block: () -> T): Pair<T, Double> { val s = System.nanoTime(); val r = block(); return r to (System.nanoTime() - s) / 1e9 }

    /** Driven paths that the planner's own chassis envelope refuses, checked one by one on a fresh terrain. */
    protected fun envelopeFailures(req: PlanningRequest, p: Preview): List<String> {
        val terrain = PlanningTerrain(req)
        return p.cuttingPaths.withIndex().filter { it.value.size >= 2 }.mapNotNull { (i, path) ->
            terrain.envelope.pathCollision(path)?.let { "path $i (${path.size} points): $it" }
        }
    }

    /** Failures of one Zamboni plan against the zigzag of the same request; empty when every check holds. */
    protected fun check(label: String, req: PlanningRequest, zigzag: CandidateResult, a: CandidateResult, b: CandidateResult,
                      spacing: Double, singleZone: Boolean): List<String> {
        val failures = mutableListOf<String>()
        // Zigzag refused as well: problem of the request, not of Zamboni (reported, not counted).
        if (!a.valid && !zigzag.valid) { println("QA2 NOTE $label: Zamboni ${a.status} ${a.diagnostics}, zigzag ${zigzag.status} ${zigzag.diagnostics}"); return failures }
        if (!a.valid) failures += "$label: not valid ${a.status} ${a.diagnostics}"
        if (a.planHash != b.planHash) failures += "$label: not deterministic"
        val p = a.preview
        val zm = a.metrics
        if (p == null || zm == null) return failures + "$label: Zamboni ${a.status} ${a.diagnostics} (zigzag ${zigzag.status} ${zigzag.diagnostics})"
        if (zm.geometricViolations != 0) failures += "$label: ${zm.geometricViolations} geometric violations"
        val gm = zigzag.metrics
        if (gm == null) println("QA2 NOTE $label: zigzag ${zigzag.status} ${zigzag.diagnostics}, Zamboni ${a.status}")
        else if (zm.coveredAreaM2 < gm.coveredAreaM2 - 0.002 * gm.targetAreaM2) failures += "$label: coverage %.4f against %.4f".format(zm.coverage, gm.coverage)
        if (p.mode != CoverageMode.ZAMBONI || p.zoneModes.any { it != CoverageMode.ZAMBONI }) failures += "$label: modes ${p.mode} ${p.zoneModes}"
        failures += envelopeFailures(req, p).map { "$label: envelope $it" }
        // Control: the same check holds on the zigzag (otherwise the check itself would be wrong).
        zigzag.preview?.let { zp -> envelopeFailures(req, zp).firstOrNull()?.let { failures += "$label: CONTROL zigzag envelope $it" } }
        // Mission from the start to the base.
        if (a.mission.first().path.first().distance(req.start) > 1e-6) failures += "$label: mission starts at ${a.mission.first().path.first()}"
        if (a.mission.last().path.last().distance(req.finish) > 1e-6) failures += "$label: mission ends at ${a.mission.last().path.last()}"
        // Borders first: the entry (first cutting path) is the one of the zigzag, the same border loops rotated nearest.
        if (req.config.bordersFirst && zigzag.preview != null && p.cuttingPaths.first() != zigzag.preview!!.cuttingPaths.first())
            failures += "$label: first cutting path differs from the zigzag"
        if (singleZone) {
            val m = TurnOrder.step(spacing, radius); val w = ZamboniOrder.window(spacing, radius)
            val order = PassOrderMetrics.order(p, p.zoneAngles.single(), spacing, 2 * m + 2)
            if (order.frontWidth > w) failures += "$label: front ${order.frontWidth} > W $w"
        }
        return failures
    }

    protected fun inside(zone: MapShape, clearance: Double): Point {
        val f = GeometryFactory()
        fun poly(p: List<Point>) = f.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        var free: org.locationtech.jts.geom.Geometry = poly(zone.points).buffer(-clearance)
        shapes.filter { it.excludes }.forEach { free = free.difference(poly(it.points).buffer(clearance)) }
        val c = free.interiorPoint.coordinate
        return Point(c.x, c.y)
    }
}

/** Every zone of the reference map at each spacing, and the seven zones in one task: a class of its own, so that the two
 * test processes share the Zamboni sweeps. */
class ZamboniQaSpacingTest : ZamboniQaFixture() {
    private fun everyZoneAt(spacing: Double) {
        val failures = mutableListOf<String>()
        for (zone in zones) {
            val name = zone.name.trim(); val req = request(listOf(zone.id), spacing)
            val (zigzag, tz) = timed { RoutePlanning.calculate(req, CoverageMode.LINES).selected }
            val (a, ta) = timed { RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected }
            // Determinism (second computation) checked at 25 cm only: the order search does not depend on the spacing.
            val b = if (spacing == 0.25) RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected else a
            val f = check("$name $spacing", req, zigzag, a, b, spacing, true)
            failures += f
            println("QA2 ZAMBONI $name $spacing m: ${a.status}, coverage %.3f %% (zigzag %.3f %%), runs ${a.preview?.runs?.size} (zigzag ${zigzag.preview?.runs?.size}), mission %.0f s (zigzag %.0f s), computation %.1f s (zigzag %.1f s)%s".format(
                100 * (a.metrics?.coverage ?: 0.0), 100 * (zigzag.metrics?.coverage ?: 0.0), a.metrics?.durationSeconds ?: 0.0,
                zigzag.metrics?.durationSeconds ?: 0.0, ta, tz, if (f.isEmpty()) "" else " FAIL $f"))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test(timeout = 3_600_000) fun everyZoneAt5Cm() = everyZoneAt(0.05)
    @Test(timeout = 3_600_000) fun everyZoneAt10Cm() = everyZoneAt(0.10)
    @Test(timeout = 3_600_000) fun everyZoneAt25Cm() = everyZoneAt(0.25)
    @Test(timeout = 3_600_000) fun everyZoneAt35Cm() = everyZoneAt(0.35)

    @Test(timeout = 3_600_000) fun sevenZonesInOneTask() {
        val req = request(zones.map { it.id })
        val zigzag = RoutePlanning.calculate(req, CoverageMode.LINES).selected
        val (a, ta) = timed { RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected }
        val b = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
        val f = check("ALL", req, zigzag, a, b, 0.25, false)
        println("QA2 ZAMBONI ALL: ${a.status}, coverage %.3f %% (zigzag %.3f %%), computation %.1f s ${f}".format(
            100 * (a.metrics?.coverage ?: 0.0), 100 * (zigzag.metrics?.coverage ?: 0.0), ta))
        assertTrue(f.joinToString("\n"), f.isEmpty())
    }

    @Test(timeout = 3_600_000) fun sevenZonesAt35CmAndFromInsideAZone() {
        val failures = mutableListOf<String>()
        val piscine = zones.single { it.name.trim() == "Piscine" }
        for ((label, req) in listOf("ALL 0.35" to request(zones.map { it.id }, 0.35),
                "ALL robot in Piscine" to request(zones.map { it.id }, start = inside(piscine, 1.0), bordersFirst = false))) {
            val zigzag = RoutePlanning.calculate(req, CoverageMode.LINES).selected
            val a = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
            val b = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
            val f = check(label, req, zigzag, a, b, req.config.spacingM, false)
            failures += f
            val entry = { r: CandidateResult -> r.preview?.cuttingPaths?.first()?.first()?.distance(req.start) ?: Double.NaN }
            val zone = { r: CandidateResult -> r.preview?.pathZoneIds?.firstOrNull() }
            println("QA2 ZAMBONI $label: ${a.status}, entry %.2f m from the start in zone ${zone(a)} (zigzag %.2f m in zone ${zone(zigzag)}), mission %.0f s (zigzag %.0f s) $f".format(
                entry(a), entry(zigzag), a.metrics?.durationSeconds ?: 0.0, zigzag.metrics?.durationSeconds ?: 0.0))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

/** Edge cases, orientation, height passes, cancellation and persistence of the Zamboni mode. */
class ZamboniQaTest : ZamboniQaFixture() {

    /** The app always sends Lawn friendly with Zamboni (the toggle is disabled): the toggle must not change the plan. */
    @Test(timeout = 1_800_000) fun lawnFriendlyToggleDoesNotChangeAZamboniPlan() {
        for (zone in zones.filter { it.name.trim() in listOf("Potager", "Figuier", "Jardin d’Eden") }) {
            val fast = RoutePlanning.calculate(request(listOf(zone.id)), CoverageMode.ZAMBONI).selected
            val lawn = RoutePlanning.calculate(request(listOf(zone.id), style = TurnStyle.LAWN), CoverageMode.ZAMBONI).selected
            assertEquals(zone.name, fast.planHash, lawn.planHash)
        }
    }

    /** A point inside [zone], away from its holes. */

    @Test(timeout = 1_800_000) fun holesObstaclesAndARobotStartInsideTheZone() {
        val failures = mutableListOf<String>()
        for (zone in zones.filter { it.name.trim() in listOf("Potager", "Piscine", "Fosse") }) {
            val obstacle = PlanningObstacle(inside(zone, 2.0), 0.4)
            val start = inside(zone, 1.0).let { Point(it.x + 0.0, it.y) }
            for (bordersFirst in listOf(true, false)) {
                val req = request(listOf(zone.id), obstacles = listOf(obstacle), start = start, bordersFirst = bordersFirst)
                val zigzag = RoutePlanning.calculate(req, CoverageMode.LINES).selected
                val a = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
                val b = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
                val label = "${zone.name.trim()} obstacle, robot start, bordersFirst=$bordersFirst"
                val f = check(label, req, zigzag, a, b, 0.25, true)
                failures += f
                // The passes keep clear of the obstacle (centre distance) like the zigzag.
                fun clearance(p: Preview?) = p?.runs?.flatMap { it.zipWithNext() }?.minOfOrNull { (u, v) -> distanceToSegment(obstacle.center, u, v) } ?: Double.NaN
                val cz = clearance(zigzag.preview); val ca = clearance(a.preview)
                if (ca < cz - 0.05) failures += "$label: obstacle clearance %.2f m against %.2f m".format(ca, cz)
                val entry = { r: CandidateResult -> r.preview?.cuttingPaths?.first()?.first()?.distance(start) ?: Double.NaN }
                println("QA2 ZAMBONI $label: ${a.status}, entry %.2f m from the robot (zigzag %.2f m), obstacle clearance %.2f m (zigzag %.2f m) $f".format(
                    entry(a), entry(zigzag), ca, cz))
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y; val l = dx * dx + dy * dy
        val t = if (l < 1e-12) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0.0, 1.0)
        return Point(a.x + t * dx, a.y + t * dy).distance(p)
    }

    private fun rectangle(w: Double, h: Double) = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
    private fun synthetic(zone: List<Point>, spacing: Double, mode: CoverageMode, holes: List<List<Point>> = emptyList(),
                          obstacles: List<ObstacleDisc> = emptyList(), checkpoint: () -> Unit = {}) =
        CoveragePlanner.plan(zone, holes, spacing, 0.6, mode, true, obstacles = obstacles, recordedBoundary = true,
            sweepOptions = SweepOptions(listOf(0)), continuous = true, cutWidthM = 0.4, checkpoint = checkpoint)
    private fun envelope(zone: List<Point>, holes: List<List<Point>> = emptyList()): TrajectoryEnvelope {
        var physical = RecordedBoundary.physicalArea(zone, RobotEnvelope.LUBA2)
        holes.forEach { physical = physical.difference(RecordedBoundary.forbiddenArea(it, RobotEnvelope.LUBA2)) }
        return TrajectoryEnvelope(physical, RobotEnvelope.LUBA2)
    }

    /** One cell of fewer than 2 m + 2 passes: Zamboni is the zigzag; from 2 m + 2 passes it reorders. */
    @Test fun cellsUnderTwoMPlusTwoPassesKeepTheZigzag() {
        val m = TurnOrder.step(0.25, radius)
        var seenSmall = false; var seenLarge = false
        for (h in (25..60).map { it / 10.0 }) {
            val zone = rectangle(10.0, h)
            val zigzag = synthetic(zone, 0.25, CoverageMode.LINES); val zamboni = synthetic(zone, 0.25, CoverageMode.ZAMBONI)
            val n = zigzag.passes.size
            zamboni.runs.forEach { assertNull("h $h", envelope(zone).pathCollision(it)) }
            if (n < 2 * m + 2) { seenSmall = true; assertEquals("h $h, $n passes", zigzag.runs, zamboni.runs) }
            else if (n >= 2 * m + 4) { seenLarge = true; assertNotEquals("h $h, $n passes: reordered", zigzag.runs, zamboni.runs) }
        }
        assertTrue(seenSmall && seenLarge)
    }

    /** Fine spacings: 7 cm still runs the search (window 12 = m + 2), 6.5 cm and below keep the zigzag. */
    @Test(timeout = 1_800_000) fun fineSpacings() {
        val zone = rectangle(12.0, 9.0); val env = envelope(zone)
        for (spacing in listOf(0.05, 0.06, 0.065, 0.07, 0.08)) {
            val m = TurnOrder.step(spacing, radius); val w = ZamboniOrder.window(spacing, radius)
            val zigzag = synthetic(zone, spacing, CoverageMode.LINES)
            val (zamboni, t) = timed { synthetic(zone, spacing, CoverageMode.ZAMBONI) }
            zamboni.runs.forEachIndexed { i, r -> assertNull("$spacing run $i", env.pathCollision(r)) }
            val order = PassOrderMetrics.order(zamboni, 0, spacing, 2 * m + 2)
            assertTrue("$spacing: front ${order.frontWidth} > $w", order.frontWidth <= w)
            assertEquals("$spacing: same passes", zigzag.passes.map { setOf(it.first(), it.last()) }.toSet(),
                zamboni.passes.map { setOf(it.first(), it.last()) }.toSet())
            if (w < m + ZamboniOrder.STEPS_ABOVE_M) assertEquals("$spacing: zigzag kept", zigzag.runs, zamboni.runs)
            println("QA2 ZAMBONI fine $spacing m (m=$m, W=$w): runs ${zigzag.runs.size} → ${zamboni.runs.size}, ${order.rolling} of ${order.turns} rolling, front ${order.frontWidth}, %.1f s, reordered ${zigzag.runs != zamboni.runs}".format(t))
        }
    }

    @Test fun syntheticZoneWithHolesAndObstacles() {
        val zone = rectangle(14.0, 10.0)
        val holes = listOf(listOf(Point(4.0, 3.0), Point(6.0, 3.0), Point(6.0, 6.0), Point(4.0, 6.0)),
            listOf(Point(9.0, 6.0), Point(11.0, 6.5), Point(10.0, 8.0)))
        val obstacles = listOf(ObstacleDisc(8.0, 2.5, 0.4 + RobotEnvelope.LUBA2.turnClearanceM))
        val zigzag = synthetic(zone, 0.25, CoverageMode.LINES, holes, obstacles)
        val zamboni = synthetic(zone, 0.25, CoverageMode.ZAMBONI, holes, obstacles)
        val env = envelope(zone, holes)
        zamboni.runs.forEachIndexed { i, r -> assertNull("run $i", env.pathCollision(r)) }
        assertEquals(zigzag.passes.map { setOf(it.first(), it.last()) }.toSet(), zamboni.passes.map { setOf(it.first(), it.last()) }.toSet())
        assertTrue(zamboni.runs.size <= zigzag.runs.size)
        val minDist = zamboni.runs.flatMap { it.zipWithNext() }.minOf { (u, v) -> distanceToSegment(Point(8.0, 2.5), u, v) }
        val zigDist = zigzag.runs.flatMap { it.zipWithNext() }.minOf { (u, v) -> distanceToSegment(Point(8.0, 2.5), u, v) }
        assertTrue("obstacle %.2f against %.2f".format(minDist, zigDist), minDist >= zigDist - 0.05)
        val m = TurnOrder.step(0.25, radius)
        val order = PassOrderMetrics.order(zamboni, 0, 0.25, 2 * m + 2)
        assertTrue(order.frontWidth <= ZamboniOrder.window(0.25, radius))
        println("QA2 ZAMBONI holes+obstacle: runs ${zigzag.runs.size} → ${zamboni.runs.size}, ${order.regularCells} regular cells, ${order.rolling} of ${order.turns} rolling, front ${order.frontWidth}, obstacle %.2f m (zigzag %.2f m)".format(minDist, zigDist))
    }

    @Test(timeout = 1_800_000) fun manualAngleAndChangeEverySessionOnEveryZone() {
        val failures = mutableListOf<String>()
        for (zone in zones) {
            val name = zone.name.trim()
            // An axis-aligned and an oblique angle: ZamboniManualAngleReproTest covers every angle in 15° steps.
            for (angle in listOf(0, 151)) {
                val req = request(listOf(zone.id)).let { it.copy(config = it.config.copy(orientation = OrientationMode.MANUAL, manualAngleDeg = angle)) }
                val z = runCatching { RoutePlanning.calculate(req, CoverageMode.LINES).selected }.getOrNull()
                val r = try { RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected } catch (e: IllegalArgumentException) {
                    println("QA2 ZAMBONI $name manual $angle°: refused ${e.message} (zigzag ${z?.status})")
                    if (z?.valid == true) failures += "$name manual $angle°: Zamboni refused (${e.message}), zigzag valid"; continue }
                println("QA2 ZAMBONI $name manual $angle°: Zamboni ${r.status} ${r.diagnostics}, zigzag ${z?.status} ${z?.diagnostics}")
                if (r.preview != null && r.preview?.zoneAngles != listOf(angle)) failures += "$name manual $angle°: ${r.preview?.zoneAngles}"
                if (!r.valid || r.metrics?.geometricViolations != 0) { if (z?.valid == true) failures += "$name manual $angle°: Zamboni ${r.status} ${r.diagnostics}, zigzag valid" }
                else failures += envelopeFailures(req, r.preview!!).map { "$name manual $angle°: $it" }
            }
            val first = RoutePlanning.calculate(request(listOf(zone.id)), CoverageMode.ZAMBONI).selected
            var memory = ZoneProgress.update(emptyMap(), first.preview!!, map, Double.MAX_VALUE, 0L)
            var previous = first.preview!!
            for (session in 1..3) {
                val req = request(listOf(zone.id)).let { it.copy(config = it.config.copy(orientation = OrientationMode.EACH_SESSION), orientationMemory = memory) }
                val next = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
                val again = if (session == 1) RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected else next
                val p = next.preview!!
                if (!next.valid || next.metrics?.geometricViolations != 0) failures += "$name session $session: ${next.status}"
                if (next.planHash != again.planHash) failures += "$name session $session: not deterministic"
                if (p.zoneModes != listOf(CoverageMode.ZAMBONI)) failures += "$name session $session: ${p.zoneModes}"
                failures += envelopeFailures(req, p).map { "$name session $session: $it" }
                val sep = OrientationRule(memory.getValue(zone.id).directions, 0.5).separation(p.zoneAngles.single(), 180)
                if (sep < 45 && p.zonePhases.single() == previous.zonePhases.singleOrNull()) failures += "$name session $session: ${previous.zoneAngles} → ${p.zoneAngles}, same phase"
                println("QA2 ZAMBONI $name session $session: ${previous.zoneAngles}/${previous.zonePhases} → ${p.zoneAngles}/${p.zonePhases}")
                memory = ZoneProgress.update(memory, p, map, Double.MAX_VALUE, session * 1000L); previous = p
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun zamboniPlan(name: String): Pair<MapShape, CoveragePlan> {
        val zone = zones.single { it.name.trim() == name }
        return zone to RoutePlanning.coverage(request(listOf(zone.id), style = TurnStyle.LAWN), CoverageMode.ZAMBONI)
    }

    @Test(timeout = 1_800_000) fun heightPassesRepeatAZamboniRouteThatStaysInsideTheEnvelope() {
        val (zone, plan) = zamboniPlan("Potager")
        assertFalse(plan.preview.localOnly)
        val expanded = CutPasses.expand(plan.preview, listOf(70, 55, 40))
        assertEquals(CoverageMode.ZAMBONI, expanded.mode)
        assertEquals(3 * plan.preview.cuttingPaths.count { it.size >= 2 }, expanded.cuttingPaths.size)
        val failures = envelopeFailures(request(listOf(zone.id)), expanded)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        val (edges, _) = ReferenceRoutes.routeEdges(map, base, zone, plan.copy(preview = expanded))
        assertTrue(edges.any { it.cutting })
        val decoded = PreviewJson.decode(JSONObject(PreviewJson.encode(expanded).toString()))
        assertEquals(expanded, decoded)
        val memory = ZoneProgress.update(emptyMap(), expanded, map, Double.MAX_VALUE, 0L)
        assertEquals(setOf(zone.id), memory.keys)
        // The other passes are driven backwards: still the same Zamboni geometry in reverse.
        val n = plan.preview.cuttingPaths.count { it.size >= 2 }
        assertEquals(expanded.cuttingPaths.subList(0, n).asReversed().map { it.asReversed() }, expanded.cuttingPaths.subList(n, 2 * n))
    }

    /** Cancellation thrown from inside the order search: it stops the computation, nothing swallows it, nothing stays cached wrong. */
    @Test(timeout = 1_800_000) fun cancellationDuringTheOrderSearchStopsTheComputation() {
        val zone = zones.single { it.name.trim() == "Piscine" }
        val req = request(listOf(zone.id))
        val reference = RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected
        for (entry in listOf<(PlanningRequest, () -> Unit) -> Any>(
            { r, c -> RoutePlanning.calculate(r, CoverageMode.ZAMBONI, c) },
            { r, c -> RoutePlanning.coverage(r, CoverageMode.ZAMBONI, c) })) {
            var inSearch = 0; var after = 0; var cancelledAt = 0L
            val error = try {
                entry(req) {
                    if (cancelledAt > 0) after++
                    else if (Thread.currentThread().stackTrace.any { it.className.contains("ZamboniOrder") } && ++inSearch == 3) {
                        cancelledAt = System.nanoTime(); throw CancellationException("cancelled")
                    }
                }
                null
            } catch (e: CancellationException) { e }
            val delay = if (cancelledAt > 0) (System.nanoTime() - cancelledAt) / 1e9 else -1.0
            println("QA2 ZAMBONI cancellation: ${error?.javaClass?.simpleName}, %.2f s after the throw, $after checkpoints after it".format(delay))
            assertTrue("the order search called the checkpoint", inSearch >= 3)
            assertNotNull("cancellation propagated", error)
            assertTrue("%.2f s after the cancellation".format(delay), delay < 5.0)
        }
        // Synthetic plan: the cancellation inside CoveragePlanner.plan is not swallowed either.
        var calls = 0
        assertThrows(CancellationException::class.java) {
            synthetic(rectangle(12.0, 9.0), 0.25, CoverageMode.ZAMBONI, checkpoint = { if (++calls > 10) throw CancellationException("cancelled") })
        }
        assertEquals(reference.planHash, RoutePlanning.calculate(req, CoverageMode.ZAMBONI).selected.planHash)
    }

    @Test(timeout = 1_800_000) fun checkpointStoreKeepsAZamboniPreviewAndResumesInTheMiddleOfAnArc() {
        val (zone, plan) = zamboniPlan("Potager")
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        fun heading(s: SimEdge) = Math.toDegrees(atan2(s.b.y - s.a.y, s.b.x - s.a.x))
        fun turn(a: SimEdge, b: SimEdge) = abs(((heading(b) - heading(a) + 540) % 360) - 180)
        val e = engine.edges
        val arcs = (1 until e.size - 1).filter { i -> e[i].cutting && e[i].meters < 0.05 && e[i - 1].cutting && e[i + 1].cutting &&
            turn(e[i - 1], e[i]) in 2.0..8.0 && turn(e[i], e[i + 1]) in 2.0..8.0 }
        assertTrue("no rolling arc in the Potager Zamboni route", arcs.isNotEmpty())
        val k = arcs[arcs.size / 2]
        val point = Point((e[k].a.x + e[k].b.x) / 2, (e[k].a.y + e[k].b.y) / 2)
        val cp = MissionCheckpoint("AA:BB", MissionProgress.mapKey(map), MissionProgress.fingerprint(e), e.size, engine.preview, true, 45, 20, 90,
            k, point, MissionProgress.cuttingMetersBefore(e, k, point), MissionProgress.totalCuttingMeters(e), 1000L)
        val store = MissionCheckpointStore(folder.root)
        store.save(cp)
        val read = MissionCheckpointStore(folder.root).read("AA:BB")
        assertNotNull(read); read!!
        assertEquals(cp.preview, read.preview)
        assertEquals(CoverageMode.ZAMBONI, read.preview.mode)
        assertEquals(listOf(CoverageMode.ZAMBONI), read.preview.zoneModes)
        assertEquals(cp.edge, read.edge); assertEquals(cp.point, read.point); assertEquals(cp.doneMeters, read.doneMeters, 1e-9)
        // The rebuilt route is the same, so the checkpoint matches it.
        val rebuilt = MowingSimulation(routes, base.point, read.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        assertEquals(read.planFingerprint, MissionProgress.fingerprint(rebuilt.edges))
        val resumed = RobotRoute.missionEdges(rebuilt, inside(zone, 1.0), 0.0, resume = MissionResume(read.edge, read.point))
        assertEquals(MissionProgress.remaining(rebuilt.edges, k, point), resumed.edges.subList(resumed.mowingFrom, resumed.mowingTo))
        assertEquals(e[k].a, resumed.edges[resumed.mowingFrom].a)
        resumed.edges.zipWithNext().forEach { (a, b) -> assertEquals(a.b, b.a) }
        println("QA2 ZAMBONI checkpoint: arc edge $k of ${e.size} (${arcs.size} arc edges), resume from ${resumed.edges[resumed.mowingFrom].a}")
    }

    /** The seven zones in one task at 35 cm, and at 25 cm with the robot inside Piscine: start nearest, end at the base. */
}
