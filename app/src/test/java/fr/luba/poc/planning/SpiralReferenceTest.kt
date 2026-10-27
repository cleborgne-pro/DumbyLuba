package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Spiral ([CoverageMode.SPIRAL]) on the seven zones of the reference map through the production entry point
 * ([RoutePlanning.calculate]), recorded boundaries, 25 cm, against Zigzag. Printed per zone: spiral kept or zigzag used
 * (with the reason), coverage, mission duration, degrees pivoted in place and wear ([TurnOptimizer.drive]), cutting length,
 * U-turns, computation time in the same JVM. The bounds are the values measured on 2026-10-27
 * (docs/path-planning/ENGINE.md, "Spiral") with a small margin; where they miss the first targets of
 * docs/path-planning/PATTERNS.md (section 5.4) the comment says why.
 */
class SpiralReferenceTest {
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

    private fun request(zones: List<MapShape>) = PlanningRequest(map, zones.map { it.id }, base.point,
        config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
        navigation = PlanningNavigation(emptyList(), base))
    private fun <T> timed(block: () -> T): Pair<T, Double> { val start = System.nanoTime(); val r = block(); return r to (System.nanoTime() - start) / 1e9 }

    @Test(timeout = 1_800_000) fun spiralOnTheReferenceMapAgainstZigzag() {
        RoutePlanning.calculate(request(zones.take(1)), CoverageMode.LINES)
        RoutePlanning.calculate(request(zones.take(1)), CoverageMode.SPIRAL)
        val failures = mutableListOf<String>()
        var kept = 0
        var zigzagSeconds = 0.0; var spiralSeconds = 0.0
        val decisions = java.util.Collections.synchronizedList(mutableListOf<SpiralSweep.Decision>())
        for (zone in zones) {
            val name = zone.name.trim()
            fun best(mode: CoverageMode): Pair<CandidateResult, Double> {
                val (a, ta) = timed { RoutePlanning.calculate(request(listOf(zone)), mode).selected }
                decisions.clear()
                SpiralSweep.observer = { decisions += it }
                val (b, tb) = try { timed { RoutePlanning.calculate(request(listOf(zone)), mode).selected } } finally { SpiralSweep.observer = null }
                assertEquals("$name $mode: deterministic", a.planHash, b.planHash)
                return b to minOf(ta, tb)
            }
            val (zigzag, tz) = best(CoverageMode.LINES)
            val (spiral, ts) = best(CoverageMode.SPIRAL)
            val decision = decisions.singleOrNull()
            zigzagSeconds += tz; spiralSeconds += ts
            for ((label, x) in listOf("zigzag" to zigzag, "spiral" to spiral)) {
                assertTrue("$name $label: ${x.diagnostics}", x.valid)
                assertEquals("$name $label", 0, x.metrics!!.geometricViolations)
            }
            val p = spiral.preview!!; val sm = spiral.metrics!!; val zm = zigzag.metrics!!
            assertEquals(CoverageMode.SPIRAL, p.mode)
            val applied = p.zoneModes.single()
            val reason = when {
                applied == CoverageMode.SPIRAL -> "spiral kept"
                decision == null -> "zigzag: no decision"
                decision.result == null -> "zigzag: spiral not buildable"
                !decision.kept -> "zigzag: spiral sweeps %.2f m² against %.2f m² (limit %.2f m²)".format(java.util.Locale.ROOT,
                    decision.spiralCovered, decision.zigzagCovered, 0.002 * decision.target)
                else -> "zigzag: refused by the mission evaluation (${spiral.name})"
            }
            if (applied == CoverageMode.SPIRAL) {
                kept++
                val result = decision!!.result!!
                assertTrue("$name: kept", decision.kept)
                if (sm.coveredAreaM2 < zm.coveredAreaM2 - 0.002 * zm.targetAreaM2) failures += "$name: coverage %.4f against %.4f".format(sm.coverage, zm.coverage)
                assertTrue(name, result.chains.isNotEmpty())
            } else {
                // Fallback: the zigzag of the zone, as the Zigzag mode computes it, and the preview warns.
                assertEquals(name, CoverageMode.LINES, applied)
                assertEquals("$name: fallback = Zigzag", zigzag.planHash, spiral.planHash)
                assertEquals(listOf("Spirale impossible sur $name : zigzag utilisé"), zoneModeWarnings(map, p))
            }
            fun drives(x: CandidateResult) = x.preview!!.runs.map { TurnOptimizer.drive(it, 0.3) }
            fun cutting(x: CandidateResult) = x.preview!!.runs.sumOf { r -> r.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
            val dz = drives(zigzag); val ds = drives(spiral)
            println(("SPIRAL $name: $reason${decision?.result?.let { r -> " (${r.chains.size} chains, ${r.rings} rings, ${r.splits} splits, ${r.pockets.size} pockets %.1f m²)".format(java.util.Locale.ROOT, r.pockets.sumOf { it.area }) } ?: ""} | " +
                "coverage %.2f %% / %.2f %% | mission %.0f s / %.0f s (%+.1f %%) | pivoted %.0f° / %.0f° | wear %.0f / %.0f | passes %.0f s / %.0f s | " +
                "cutting %.0f m / %.0f m (×%.3f) | U-turns ${zm.uTurns} / ${sm.uTurns} | computation %.2f s / %.2f s (×%.2f)").format(java.util.Locale.ROOT,
                zm.coverage * 100, sm.coverage * 100, zm.durationSeconds, sm.durationSeconds, (sm.durationSeconds / zm.durationSeconds - 1) * 100,
                dz.sumOf { it.pivotDegrees }, ds.sumOf { it.pivotDegrees }, dz.sumOf { it.wear }, ds.sumOf { it.wear },
                dz.sumOf { it.durationS }, ds.sumOf { it.durationS }, cutting(zigzag), cutting(spiral), cutting(spiral) / cutting(zigzag), tz, ts, ts / tz))
        }
        println("SPIRAL total: spiral kept on $kept of ${zones.size} zones, computation %.1f s → %.1f s (×%.2f)".format(java.util.Locale.ROOT,
            zigzagSeconds, spiralSeconds, spiralSeconds / zigzagSeconds))
        // Target: kept on at least 4 of the 7 zones (measured: 6, Figuier in zigzag).
        if (kept < 4) failures += "spiral kept on $kept zones"
        // Target ×1.0 of the zigzag: the spiral is computed with the zigzag it is compared with (reference and fallback), then
        // ordered and evaluated with longer runs; measured ×1.1 to ×1.4 on the map. Bound with margin for a loaded machine.
        if (spiralSeconds > 1.8 * zigzagSeconds) failures += "computation %.1f s against %.1f s".format(spiralSeconds, zigzagSeconds)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test(timeout = 900_000) fun severalZonesKeepTheirOwnPatternAndSpiralZonesAreNotRemembered() {
        val chosen = zones.filter { it.name.trim() in listOf("Potager", "Figuier", "Jardin d’Eden") }
        val result = RoutePlanning.calculate(request(chosen), CoverageMode.SPIRAL).selected
        assertTrue(result.diagnostics.toString(), result.valid)
        val p = result.preview!!
        assertEquals(chosen.size, p.zoneModes.size)
        println("SPIRAL three zones: ${p.zoneIds.map { id -> chosen.single { it.id == id }.name.trim() }.zip(p.zoneModes)}")
        assertTrue(p.zoneModes.contains(CoverageMode.SPIRAL))
        // "Change every session": spiral zones have no direction to remember; zones mowed in zigzag do.
        val memory = ZoneProgress.update(emptyMap(), p, map, Double.MAX_VALUE, 0L)
        assertEquals(p.zoneIds.filterIndexed { i, _ -> p.zoneModes[i] == CoverageMode.LINES }.toSet(), memory.keys)
    }
}
