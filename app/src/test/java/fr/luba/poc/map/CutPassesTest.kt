package fr.luba.poc.map

import fr.luba.poc.protocol.CutHeightRange
import fr.luba.poc.simulation.*
import org.junit.Assert.*
import org.junit.Test

class CutPassesTest {
    @Test fun heightsAreEvenlySpreadDownToTheCuttingHeight() {
        assertEquals(listOf(70, 55, 40), CutPasses.heights(40, 70, 3))
        assertEquals(listOf(60, 45), CutPasses.heights(45, 60, 2))
        assertEquals(listOf(40), CutPasses.heights(40, 70, 1))
        // First pass too low: raised to keep 5 mm between two passes.
        assertEquals(listOf(50, 45, 40), CutPasses.heights(40, 42, 3))
        // Final height too high for three distinct passes.
        assertEquals(2, CutPasses.maxPasses(65))
        assertEquals(listOf(70, 65), CutPasses.heights(65, 70, 3))
        assertEquals(listOf(70), CutPasses.heights(70, 70, 3))
        assertEquals(listOf(70, 48, 25), CutPasses.heights(10, 90, 9))
        assertEquals("70 → 55 → 40 mm", CutPasses.label(CutPasses.heights(40, 70, 3)))
        assertEquals("Passe 2/3 · 55 mm", CutPasses.progress(listOf(70, 55, 40), 55))
        assertEquals("55 mm", CutPasses.progress(emptyList(), 55))
    }

    @Test fun settingsKeepConsistentPasses() {
        val chosen = MowingTaskSettings(heightMm = 40f, heightPasses = 3, firstPassHeightMm = 70f).sanitized()
        assertEquals(listOf(70, 55, 40), chosen.passHeights())
        // Saved settings accept every model (H versions up to 100 mm); the passes follow the robot's own range.
        val wild = MowingTaskSettings(heightMm = 68f, heightPasses = 9, firstPassHeightMm = 10f).sanitized()
        assertEquals(3, wild.heightPasses)
        assertEquals(listOf(68), wild.passHeights())
        assertEquals(listOf(78, 73, 68), wild.passHeights(CutHeightRange.HIGH))
        assertEquals(100f, MowingTaskSettings(heightMm = 140f).sanitized().heightMm)
        assertEquals(20f, MowingTaskSettings(heightMm = 3f).sanitized().heightMm)
        assertEquals(listOf(60), MowingTaskSettings().passHeights())
    }

    @Test fun highCutVersionsGetPassesUpTo100Millimetres() {
        val high = CutHeightRange.HIGH
        assertEquals(listOf(100, 85, 70), CutPasses.heights(70, 100, 3, high))
        assertEquals(3, CutPasses.maxPasses(90, high))
        assertEquals(1, CutPasses.maxPasses(70))
        assertEquals(80..100, CutPasses.firstRange(70, 3, high))
        // Below the H minimum: the final height is raised to it.
        assertEquals(listOf(high.minMm), CutPasses.heights(30, 30, 1, high))
    }

    private val map = RobotMap(listOf(MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 12.0), Point(0.0, 12.0)))), true)
    private val single = Geometry.plan(map.shapes.first().points, emptyList(), borders = true)
    private fun engine(preview: Preview) = MowingSimulation(TrajectoryPlanner(map, 0.6, emptyList()), Point(2.0, 2.0), preview, map,
        SimConfig(instantCharging = true))

    @Test fun expandedPlanRepeatsTheRouteAlternatingItsDirection() {
        val expanded = CutPasses.expand(single, listOf(70, 55, 40))
        val paths = single.cuttingPaths
        assertEquals(paths.size * 3, expanded.cuttingPaths.size)
        assertEquals(paths, expanded.cuttingPaths.take(paths.size))
        assertEquals(paths.asReversed().map { it.asReversed() }, expanded.cuttingPaths.subList(paths.size, 2 * paths.size))
        assertEquals(paths, expanded.cuttingPaths.takeLast(paths.size))
        // The next pass starts where the previous one ended: no transit between two passes.
        assertEquals(expanded.cuttingPaths[paths.size - 1].last(), expanded.cuttingPaths[paths.size].first())
        assertEquals(List(paths.size) { 70 } + List(paths.size) { 55 } + List(paths.size) { 40 }, expanded.pathHeights)
        assertEquals(single.meters * 3, expanded.meters, 1e-9)
        // A single pass, a survey or an already expanded preview: unchanged.
        assertSame(single, CutPasses.expand(single, listOf(40)))
        assertSame(expanded, CutPasses.expand(expanded, listOf(70, 55, 40)))
        val survey = single.copy(mode = CoverageMode.TOUR)
        assertSame(survey, CutPasses.expand(survey, listOf(70, 55, 40)))
    }

    @Test fun preparedRouteCarriesEachPassHeightAndCompletesInSimulation() {
        val one = engine(single)
        val three = engine(CutPasses.expand(single, listOf(70, 55, 40)))
        assertTrue(one.edges.all { it.heightMm == null })
        assertEquals(one.total * 3, three.total, 1e-6)
        // Heights in pass order, never raised again; every cutting edge has one.
        val heights = three.edges.mapNotNull { it.heightMm }
        assertEquals(three.edges.size, heights.size)
        assertEquals(listOf(70, 55, 40), heights.distinct())
        assertEquals(heights.sortedDescending(), heights)
        assertEquals(one.total, three.edges.filter { it.cutting && it.heightMm == 55 }.sumOf { it.meters }, 1e-6)
        // Resume fingerprint: a single pass unchanged, several passes distinct.
        assertEquals(MissionProgress.fingerprint(one.edges), MissionProgress.fingerprint(one.edges.map { it.copy() }))
        assertNotEquals(MissionProgress.fingerprint(three.edges), MissionProgress.fingerprint(three.edges.map { it.copy(heightMm = 40) }))
        var state = three.start()
        repeat(4000) { if (state.phase != SimPhase.COMPLETE) state = three.advance(state, 10.0) }
        assertEquals(state.log.toString(), SimPhase.COMPLETE, state.phase)
        assertEquals(three.total, state.covered, 1e-6)
    }

    @Test fun passesSurviveTheInterruptionCheckpointAndOlderFilesReadAsOnePass() {
        val expanded = CutPasses.expand(single, listOf(70, 55, 40))
        val json = PreviewJson.encode(expanded)
        assertEquals(expanded, PreviewJson.decode(org.json.JSONObject(json.toString())))
        json.remove("pathHeights"); json.remove("cutHeights")
        val older = PreviewJson.decode(org.json.JSONObject(json.toString()))
        assertTrue(older.pathHeights.isEmpty() && older.cutHeights.isEmpty())
        // Resume in the middle of the second pass: the remaining edges keep their height.
        val engine = engine(expanded)
        val second = engine.edges.indexOfFirst { it.heightMm == 55 }
        val rest = MissionProgress.remaining(engine.edges, second + 2, null)
        assertEquals(55, rest.first().heightMm)
        assertEquals(listOf(55, 40), rest.mapNotNull { it.heightMm }.distinct())
    }

    /** All zones of the reference map: three passes prepared like a single one (links, recharges). */
    @Test(timeout = 600_000) fun referenceZonesPrepareThreePasses() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val failures = mutableListOf<String>()
        val prepared = mutableListOf<String>()
        map.shapes.filter { it.kind == ShapeKind.ZONE }.forEach { zone ->
            val request = fr.luba.poc.planning.PlanningRequest(map, listOf(zone.id), base.point,
                config = fr.luba.poc.planning.PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95,
                    boundary = fr.luba.poc.planning.BoundaryMeaning.RECORDED_TRACE),
                navigation = fr.luba.poc.planning.PlanningNavigation(emptyList(), base))
            val plan = fr.luba.poc.planning.RoutePlanning.coverage(request).preview
            val routes = fr.luba.poc.planning.RoutePlanning.mission(map, emptyList(), emptyList(), base, recordedBoundary = true)
            val config = SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true)
            try {
                val one = MowingSimulation(routes, base.point, plan, map, config)
                val three = MowingSimulation(routes, base.point, CutPasses.expand(plan, listOf(70, 55, 40)), map, config)
                assertEquals(zone.name, one.total * 3, three.total, 1e-6)
                assertEquals(zone.name, listOf(70, 55, 40), three.edges.mapNotNull { it.heightMm }.distinct())
                // No transit added between the passes: only the links of each pass.
                val transit = { e: MowingSimulation -> e.edges.filterNot { it.cutting }.sumOf { it.meters } }
                assertTrue("${zone.name}: transit ${transit(three)} m for ${transit(one)} m per pass", transit(three) <= transit(one) * 3 + 1.0)
                prepared += zone.name
            } catch (e: IllegalArgumentException) {
                // Zone whose single-pass route cannot be prepared already: outside the scope of this test.
                val alone = runCatching { MowingSimulation(routes, base.point, plan, map, config) }.isSuccess
                if (alone) failures += "${zone.name} : ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        println("Trois passes préparées : $prepared")
        assertTrue("Prepared zones: $prepared", prepared.size >= 5 && "Aire de jeux" in prepared)
    }
}
