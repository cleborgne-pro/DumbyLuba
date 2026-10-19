package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fast computation: same guarantees as the historical pipeline, on the reference map. */
class FastPipelineTest {
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
    private fun request(vararg names: String) = PlanningRequest(map, names.map { n -> shapes.single { it.name.trim() == n }.id }, base.point,
        config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
        navigation = PlanningNavigation(emptyList(), base))
    private fun routes() = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
        shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
    private fun simulation(preview: Preview) = MowingSimulation(routes(), base.point, preview, map,
        SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))

    @Test fun rasterCoverageMatchesTheVectorOracle() {
        val zone = shapes.single { it.name.trim() == "Piscine" }
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val preview = Geometry.plan(zone.points, holes, spacing = 0.25, recordedBoundary = true)
        val target = union(listOf(polygon(zone.points))).difference(union(holes.map(::polygon)))
        // Chained passes, then a transition that breaks the continuity.
        val mission = preview.cuttingPaths.flatMapIndexed { i, path ->
            if (i % 5 == 4) listOf(MissionLeg(listOf(path.first(), path.first()), false), MissionLeg(path, true)) else listOf(MissionLeg(path, true))
        }
        val exact = MissionEvaluator.coverage(mission, target, 0.2)
        val raster = MissionEvaluator.coverage(mission, target, 0.2, rasterCell = 0.02)
        println("RASTER exact=$exact raster=$raster target=${target.area}")
        assertEquals(exact.covered, raster.covered, 0.0025 * target.area)
        assertEquals(exact.repeated, raster.repeated, maxOf(0.5, 0.05 * exact.repeated))
        assertEquals(exact.extra, raster.extra, maxOf(0.5, 0.05 * exact.extra))
        assertTrue(raster.covered <= target.area)
    }

    @Test(timeout = 120_000) fun everyReferenceZoneIsPlannedAndPreparedFromTheRecordedBase() {
        for (zone in shapes.filter { it.kind == ShapeKind.ZONE }) {
            val plan = RoutePlanning.coverage(request(zone.name.trim()))
            val report = requireNotNull(plan.report)
            assertTrue("${zone.name}: ${report.selected.diagnostics}", report.selected.valid)
            assertFalse(plan.preview.localOnly)
            assertNotNull("Plan links passed to the preparation", plan.preview.links)
            assertTrue(report.selected.metrics!!.coverage >= 0.95)
            val prepared = simulation(plan.preview)
            prepared.routes.requirePath(listOf(prepared.edges.first().a) + prepared.edges.map { it.b })
            // Continuous blades in the zone: internal links with blades running, no blade stop
            // outside the links leaving the zone.
            val edges = prepared.edges
            val links = edges.filterNot { it.cutting }
            val off = links.filterNot { it.bladesOn }
            val ranges = edges.zipWithNext().count { (x, y) -> !x.bladesOn && y.bladesOn } + if (edges.first().bladesOn) 1 else 0
            println("BLADES ${zone.name.trim()}: links ${links.size} edges, ${off.size} blades stopped (%.1f m / %.1f m), blades-running ranges $ranges"
                .format(off.sumOf { it.meters }, links.sumOf { it.meters }))
            assertTrue(edges.all { !it.cutting || it.bladesOn })
            assertTrue("${zone.name}: blades-running ranges $ranges", ranges <= 3)
        }
    }

    @Test(timeout = 120_000) fun plannedLinksAreRevalidatedAndGiveTheSamePreparation() {
        val plan = RoutePlanning.coverage(request("Enfer")).preview
        val links = requireNotNull(plan.links)
        assertEquals(plan.cuttingPaths.size - 1, links.size)
        val reused = simulation(plan)
        val searched = simulation(plan.copy(links = null))
        assertEquals(searched.edges, reused.edges)
        // An altered link (crossing an exclusion) is not reused: full search.
        val noGo = shapes.first { it.kind == ShapeKind.NO_GO }.points
        val center = Point(noGo.sumOf { it.x } / noGo.size, noGo.sumOf { it.y } / noGo.size)
        val corrupted = plan.copy(links = links.map { listOf(it.first(), center, it.last()) })
        assertEquals(searched.edges, simulation(corrupted).edges)
        // Deterministic parallel preparation: same resumes and returns at each progress step.
        for (progress in listOf(0.0, 33.0, 66.0, 99.0)) {
            val a = reused.withConfig(reused.config.copy(initialProgress = progress)).start()
            val b = searched.withConfig(searched.config.copy(initialProgress = progress)).start()
            assertEquals(b.transit, a.transit)
        }
    }

    @Test(timeout = 120_000) fun allZonesTogetherFormOneValidatedMission() {
        val names = shapes.filter { it.kind == ShapeKind.ZONE }.map { it.name.trim() }.toTypedArray()
        val plan = RoutePlanning.coverage(request(*names))
        val report = requireNotNull(plan.report)
        assertTrue(report.selected.diagnostics.toString(), report.selected.valid)
        assertEquals(names.size, plan.preview.zoneIds.size)
        val prepared = simulation(plan.preview)
        assertTrue(prepared.total > 0)
    }
}
