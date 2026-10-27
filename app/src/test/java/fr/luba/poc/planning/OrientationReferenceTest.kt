package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Direction of the passes on the seven zones of the reference map, through the production entry point
 * ([RoutePlanning.calculate]), recorded boundaries, 25 cm. Printed per zone: angle, validity, duration, coverage and
 * computation time against Automatic in the same JVM.
 */
class OrientationReferenceTest {
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
    private fun request(zone: MapShape, orientation: OrientationMode = OrientationMode.AUTOMATIC, angle: Int = 0,
                        memory: Map<Long, ZoneOrientation> = emptyMap()) =
        PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
                orientation = orientation, manualAngleDeg = angle),
            navigation = PlanningNavigation(emptyList(), base), orientationMemory = memory)
    private fun <T> timed(block: () -> T): Pair<T, Double> { val start = System.nanoTime(); val r = block(); return r to (System.nanoTime() - start) / 1e9 }

    @Test fun manualAngleIsKeptOnEveryZoneOrFlagged() {
        RoutePlanning.calculate(request(zones.first()), CoverageMode.LINES)   // warm-up
        var autoTime = 0.0; var manualTime = 0.0; var manualRuns = 0
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) for (zone in zones) {
            val (auto, tAuto) = timed { RoutePlanning.calculate(request(zone), mode) }
            autoTime += tAuto
            for (angle in listOf(0, 37, 90, 143)) {
                val (result, t) = timed { RoutePlanning.calculate(request(zone, OrientationMode.MANUAL, angle), mode) }
                manualTime += t; manualRuns++
                val s = result.selected
                val expected = foldAngle(angle, mode.orientationPeriod)
                s.metrics?.let { assertEquals("${zone.name} $mode $angle°", 0, it.geometricViolations) }
                if (s.preview != null) assertEquals("${zone.name} $mode $angle°: angle replaced", listOf(expected), s.preview!!.zoneAngles)
                if (!s.valid) assertTrue("${zone.name} $mode $angle°: ${s.diagnostics}", s.diagnostics.any { "Automatique" in it })
                println("ORIENTATION manual ${mode.name} ${zone.name.trim()} $angle° → ${s.preview?.zoneAngles} ${s.status} " +
                    "%.0f s (auto %.0f s at ${auto.selected.preview?.zoneAngles}), coverage %.2f %% (auto %.2f %%), %.2f s (auto %.2f s)".format(
                        s.metrics?.durationSeconds ?: Double.NaN, auto.selected.metrics?.durationSeconds ?: Double.NaN,
                        (s.metrics?.coverage ?: Double.NaN) * 100, (auto.selected.metrics?.coverage ?: Double.NaN) * 100, t, tAuto))
            }
        }
        val ratio = manualTime / manualRuns / (autoTime / (2 * zones.size))
        println("ORIENTATION manual computation: mean %.2f s per plan against %.2f s automatic (×%.2f)".format(
            manualTime / manualRuns, autoTime / (2 * zones.size), ratio))
        // Measured ×0.46 to ×0.53 (one candidate instead of about 18): the bound only catches a regression, with room for a
        // loaded machine (wall-clock time of two forks in parallel).
        assertTrue("manual computation ×%.2f of automatic".format(ratio), ratio < 1.0)
    }

    @Test fun changeEverySessionMovesAwayFromTheLastMowingOrShiftsTheRows() {
        RoutePlanning.calculate(request(zones.first()), CoverageMode.LINES)   // warm-up
        var autoTime = 0.0; var sessionTime = 0.0; var sessions = 0; var separated = 0; var shifted = 0
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) for (zone in zones) {
            val (auto, tAuto) = timed { RoutePlanning.calculate(request(zone), mode) }
            val a = auto.selected; val am = a.metrics!!
            assertTrue("${zone.name} $mode automatic: ${a.diagnostics}", a.valid)
            // First session: the automatic plan was mowed.
            var memory = ZoneProgress.update(emptyMap(), a.preview!!, map, Double.MAX_VALUE, 0L)
            val pairs = mutableListOf(a.preview!!.zoneAngles.single() to OrientationRule.phaseIndex(a.preview!!.zonePhases.single()))
            for (session in 1..4) {
                val previous = memory.getValue(zone.id)
                val (result, t) = timed { RoutePlanning.calculate(request(zone, OrientationMode.EACH_SESSION, memory = memory), mode) }
                autoTime += tAuto; sessionTime += t; sessions++
                val s = result.selected; val m = s.metrics!!
                assertTrue("${zone.name} $mode session $session: ${s.diagnostics}", s.valid)
                assertEquals(0, m.geometricViolations)
                val p = s.preview!!
                val angle = p.zoneAngles.single(); val phase = OrientationRule.phaseIndex(p.zonePhases.single())
                val separation = OrientationRule(previous.directions, 0.5).separation(angle, mode.orientationPeriod)
                val pair = angle to phase
                println("ORIENTATION session ${mode.name} ${zone.name.trim()} #$session: ${previous.directions}/${OrientationRule.PHASES[previous.phaseIndex]} → " +
                    "$angle° phase ${OrientationRule.PHASES[phase]} (separation $separation°), %.0f s (auto %.0f s at ${a.preview!!.zoneAngles}, %+.1f %%), coverage %.2f %% (auto %.2f %%), %.2f s (auto %.2f s)".format(
                        m.durationSeconds, am.durationSeconds, (m.durationSeconds / am.durationSeconds - 1) * 100, m.coverage * 100, am.coverage * 100, t, tAuto))
                if (separation >= 45) {
                    separated++
                    // The 5 % cap applies to the ranking cost; on the evaluated mission it reaches +6.2 % (Jardin d'Eden
                    // zigzag, Piscine checkerboard), hence this bound.
                    assertTrue("${zone.name} $mode session $session: %.0f s against %.0f s".format(m.durationSeconds, am.durationSeconds),
                        m.durationSeconds <= 1.07 * am.durationSeconds)
                    assertTrue("${zone.name} $mode session $session: coverage %.4f against %.4f".format(m.coverage, am.coverage),
                        m.coveredAreaM2 >= am.coveredAreaM2 - 0.002 * am.targetAreaM2)
                } else {
                    shifted++
                    assertNotEquals("${zone.name} $mode session $session: same rows as the last mowing", previous.phaseIndex, phase)
                }
                assertNotEquals("${zone.name} $mode session $session: same direction and phase twice in a row", pairs.last(), pair)
                pairs += pair
                memory = ZoneProgress.update(memory, p, map, Double.MAX_VALUE, session.toLong())
            }
        }
        val ratio = sessionTime / autoTime
        println("ORIENTATION sessions: $separated separated, $shifted shifted rows; computation %.2f s per plan against %.2f s automatic (×%.2f)".format(
            sessionTime / sessions, autoTime / sessions, ratio))
        // Measured ×0.96 to ×1.10; wide bound for the wall-clock time under load.
        assertTrue("computation ×%.2f of automatic".format(ratio), ratio < 3.0)
    }
}
