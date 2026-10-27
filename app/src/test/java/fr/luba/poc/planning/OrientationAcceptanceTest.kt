package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Acceptance of the direction of the passes on the reference map with every zone in one task (the per-zone runs are in
 * [OrientationReferenceTest]): manual angle on all the zones at once, four "Change every session" sessions with the
 * memory written by [ZoneProgress] from the previous plan, Optimized ignoring the setting, and the attribution of the
 * cutting metres of a real multi-zone plan.
 */
class OrientationAcceptanceTest {
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
    private val allZones = zones.map { it.id }

    private fun request(ids: List<Long> = allZones, orientation: OrientationMode = OrientationMode.AUTOMATIC, angle: Int = 0,
                        memory: Map<Long, ZoneOrientation> = emptyMap()) =
        PlanningRequest(map, ids, base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
                orientation = orientation, manualAngleDeg = angle),
            navigation = PlanningNavigation(emptyList(), base), orientationMemory = memory)

    /** Directions (0..179) of the straight passes longer than 0.5 m. */
    private fun passDirections(preview: Preview) = preview.passes.filter { it.size >= 2 && it.first().distance(it.last()) > 0.5 }
        .map { foldAngle(Math.round(heading(it.first(), it.last())).toInt()) }.toSet()

    @Test fun manualAngleHoldsOnEveryZoneOfAMultiZoneTask() {
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) for (angle in listOf(37, 143)) {
            val selected = RoutePlanning.calculate(request(orientation = OrientationMode.MANUAL, angle = angle), mode).selected
            val expected = foldAngle(angle, mode.orientationPeriod)
            selected.metrics?.let { assertEquals("$mode $angle°", 0, it.geometricViolations) }
            if (!selected.valid) assertTrue("$mode $angle°: ${selected.diagnostics}",
                selected.diagnostics.any { "$angle°" in it && "Automatique" in it })
            val preview = selected.preview ?: continue
            assertEquals("$mode $angle°: zones", allZones.toSet(), preview.zoneIds.toSet())
            assertEquals("$mode $angle°: angle replaced on a zone", List(preview.zoneIds.size) { expected }, preview.zoneAngles)
            assertEquals("$mode $angle°", List(preview.zoneIds.size) { mode }, preview.zoneModes)
            assertEquals("$mode $angle°", List(preview.zoneIds.size) { 0.5 }, preview.zonePhases)
            val families = if (mode == CoverageMode.CHECKERBOARD) listOf(expected, expected + 90) else listOf(expected)
            val used = passDirections(preview)
            assertTrue("$mode $angle°: passes at $used", used.isNotEmpty() &&
                used.all { d -> families.any { OrientationRule.distance(d, it) <= 1 } })
            println("ORIENTATION acceptance manual ${mode.name} $angle° all zones: ${selected.status} ${preview.zoneAngles}")
        }
    }

    @Test fun changeEverySessionOnAMultiZoneTaskMovesEveryZone() {
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) {
            val automatic = RoutePlanning.calculate(request(), mode).selected
            assertTrue("$mode automatic: ${automatic.diagnostics}", automatic.valid)
            val am = automatic.metrics!!
            // No memory yet: exactly the automatic plan.
            assertEquals("$mode: without memory", automatic.planHash,
                RoutePlanning.calculate(request(orientation = OrientationMode.EACH_SESSION), mode).selected.planHash)
            var memory = ZoneProgress.update(emptyMap(), automatic.preview!!, map, Double.MAX_VALUE, 0L)
            assertEquals("$mode: every zone remembered after a complete mowing", allZones.toSet(), memory.keys)
            val pairs = automatic.preview!!.zoneIds.indices.associate { i ->
                automatic.preview!!.zoneIds[i] to (automatic.preview!!.zoneAngles[i] to OrientationRule.phaseIndex(automatic.preview!!.zonePhases[i])) }.toMutableMap()
            for (session in 1..4) {
                val previous = memory
                val selected = RoutePlanning.calculate(request(orientation = OrientationMode.EACH_SESSION, memory = memory), mode).selected
                assertTrue("$mode session $session: ${selected.diagnostics}", selected.valid)
                val m = selected.metrics!!
                assertEquals("$mode session $session", 0, m.geometricViolations)
                assertTrue("$mode session $session: coverage %.4f against %.4f".format(m.coverage, am.coverage),
                    m.coveredAreaM2 >= am.coveredAreaM2 - 0.002 * am.targetAreaM2)
                val p = selected.preview!!
                assertEquals(p.zoneIds.size, p.zoneAngles.size)
                assertEquals(p.zoneIds.size, p.zonePhases.size)
                p.zoneIds.forEachIndexed { i, id ->
                    val last = previous.getValue(id)
                    val angle = p.zoneAngles[i]; val phase = OrientationRule.phaseIndex(p.zonePhases[i])
                    val separation = OrientationRule(last.directions, 0.5).separation(angle, p.zoneModes[i].orientationPeriod)
                    assertTrue("$mode session $session zone $id: $angle° phase $phase after ${last.directions} phase ${last.phaseIndex}",
                        separation >= 45 || phase != last.phaseIndex)
                    assertNotEquals("$mode session $session zone $id: same direction and phase twice in a row", pairs[id], angle to phase)
                    pairs[id] = angle to phase
                }
                println("ORIENTATION acceptance session ${mode.name} #$session: ${p.zoneAngles} ${p.zonePhases} " +
                    "%.0f s (auto %.0f s, %+.1f %%), coverage %.2f %% (auto %.2f %%)".format(m.durationSeconds, am.durationSeconds,
                        (m.durationSeconds / am.durationSeconds - 1) * 100, m.coverage * 100, am.coverage * 100))
                memory = ZoneProgress.update(memory, p, map, Double.MAX_VALUE, session.toLong())
            }
        }
    }

    @Test fun optimizedIgnoresManualAndEverySessionOnTheReferenceMap() {
        val ids = zones.take(2).map { it.id }
        val automatic = RoutePlanning.calculate(request(ids), CoverageMode.OPTIMIZED).selected
        val memory = ids.associateWith { id ->
            ZoneOrientation(listOf(foldAngle((automatic.preview?.zoneAngles?.firstOrNull() ?: 0) + 10)), 0,
                ZoneOrientation.shapeKey(zones.first { it.id == id }.points), 1L)
        }
        assertEquals(automatic.planHash, RoutePlanning.calculate(request(ids, OrientationMode.MANUAL, 37), CoverageMode.OPTIMIZED).selected.planHash)
        assertEquals(automatic.planHash,
            RoutePlanning.calculate(request(ids, OrientationMode.EACH_SESSION, memory = memory), CoverageMode.OPTIMIZED).selected.planHash)
        // An Optimized mowing records nothing.
        assertTrue(ZoneProgress.update(emptyMap(), automatic.preview!!, map, Double.MAX_VALUE, 1L).isEmpty())
    }

    @Test fun cuttingMetresOfARealMultiZonePlanGoToTheirZoneInMowingOrder() {
        val preview = RoutePlanning.calculate(request(), CoverageMode.LINES).selected.preview!!
        val lengths = preview.cuttingPaths.filter { it.size >= 2 }.map { p -> p.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
        val order = preview.zoneIds
        // Walk the plan path by path: one zone at a time, each reaches 100 % before the next one of the order starts.
        var done = 0.0
        val finishedAt = linkedMapOf<Long, Double>()
        for (length in lengths) {
            done += length
            val shares = ZoneProgress.shares(preview, map, done)
            shares.filterValues { it >= 1.0 - 1e-9 }.keys.forEach { finishedAt.putIfAbsent(it, done) }
            val inProgress = shares.filterValues { it > 1e-9 && it < 1.0 - 1e-9 }.keys
            assertTrue("several zones in progress at %.1f m: $inProgress".format(done), inProgress.size <= 1)
        }
        assertEquals(order.toSet(), finishedAt.keys)
        assertEquals("zones finish in mowing order", order, finishedAt.keys.toList())
        // Interrupted after the first zone and 40 % of the second one: only the first one is remembered.
        val first = finishedAt.getValue(order[0])
        val second = finishedAt.getValue(order[1]) - first
        assertEquals(setOf(order[0]), ZoneProgress.update(emptyMap(), preview, map, first + 0.4 * second, 1L).keys)
        // Resumed and finished: every zone.
        assertEquals(order.toSet(), ZoneProgress.update(emptyMap(), preview, map, lengths.sum(), 2L).keys)
    }
}

/** Manual angle that cannot be mowed on a zone: refused with the angle and the advice, never swapped for another one. */
class ManualOrientationThinStripTest {
    @Test fun thinStripAcrossTheManualAngleIsFlaggedWithTheMessage() {
        val strip = rectangle(0.0, 0.0, 24.0, 1.9)
        val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65, orientation = OrientationMode.MANUAL, manualAngleDeg = 90)
        val along = RoutePlanning.calculate(request(strip, config = config.copy(manualAngleDeg = 0)), CoverageMode.LINES).selected
        val across = RoutePlanning.calculate(request(strip, config = config), CoverageMode.LINES).selected
        println("ORIENTATION thin strip: along ${along.status} ${along.preview?.zoneAngles}, across ${across.status} " +
            "${across.preview?.zoneAngles} ${across.diagnostics}")
        across.preview?.let { assertEquals("angle replaced", listOf(90), it.zoneAngles) }
        if (!across.valid) assertTrue(across.diagnostics.toString(), across.diagnostics.any { "90°" in it && "Automatique" in it })
        // Production facade: a plan at 90°, or no usable plan (local-only preview, or a refusal carrying the message).
        runCatching { RoutePlanning.coverage(request(strip, config = config), CoverageMode.LINES) }
            .onSuccess { assertTrue(it.preview.localOnly || it.preview.zoneAngles == listOf(90)) }
            .onFailure { assertTrue(it.message, "90°" in it.message.orEmpty() && "Automatique" in it.message.orEmpty()) }
    }
}
