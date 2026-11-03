package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Narrow passages on the reference map (recorded boundaries, 25 cm, production entry point): Potager is the only zone
 * with necks, its two passages between the main lawn and the upper parts (never mowed before 2026-11-02, only crossed
 * by transits) are now crossed by cutting passes in every mowing mode, every plan validated without violation.
 */
class NarrowPassageReferenceTest {
    private val shapes: List<MapShape> = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        .getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE)
    private val holes = shapes.filter { it.excludes }.map { it.points }

    @Test(timeout = 600_000) fun potagerPassagesAreMowed() {
        val necksByZone = shapes.filter { it.kind == ShapeKind.ZONE }.associate { zone ->
            zone.name.trim() to NarrowPassages.necks(zone.points, holes, emptyList(), RobotEnvelope.LUBA2, config.outerOffsetM, config.spacingM) }
        assertEquals(necksByZone.toString(), setOf("Potager"), necksByZone.filterValues { it.isNotEmpty() }.keys)
        val necks = necksByZone.getValue("Potager")
        assertEquals(2, necks.size)
        // Middle of each passage: the middle of its middle row.
        val middles = necks.map { neck -> neck.rows[neck.rows.size / 2].let { r -> r.at((r.first + r.last) / 2) } }
        val potager = shapes.single { it.name.trim() == "Potager" }
        for (mode in listOf(CoverageMode.LINES, CoverageMode.ZAMBONI, CoverageMode.CHECKERBOARD, CoverageMode.OPTIMIZED, CoverageMode.SPIRAL)) {
            val selected = RoutePlanning.calculate(PlanningRequest(map, listOf(potager.id), base.point, config = config,
                navigation = PlanningNavigation(emptyList(), base)), mode).selected
            assertTrue("$mode: ${selected.diagnostics}", selected.valid)
            assertEquals("$mode", 0, selected.metrics!!.geometricViolations)
            val paths = selected.preview!!.cuttingPaths
            for (m in middles) assertTrue("$mode: passage at $m mowed", paths.any { path ->
                path.zipWithNext().any { (a, b) -> Geometry.distance(m, a, b) <= config.cutWidthM / 2 } })
            println("NARROW Potager $mode: coverage %.2f %%, mission %.0f s".format(java.util.Locale.ROOT,
                selected.metrics!!.coverage * 100, selected.metrics!!.durationSeconds))
        }
    }
}
