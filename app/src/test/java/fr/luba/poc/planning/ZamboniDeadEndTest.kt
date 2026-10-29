package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Zamboni zone whose chained runs lead the local order to a dead end ([LocalMowingOrder.schedule]): the zone is ordered
 * again without chains, as in Zigzag, and the preview says so. Reference map, Aire de jeux, manual angles that Zigzag mows.
 */
class ZamboniDeadEndTest {
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
    private val zone = shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == "Aire de jeux" }
    private fun request(angle: Int) = PlanningRequest(map, listOf(zone.id), base.point,
        config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
            orientation = OrientationMode.MANUAL, manualAngleDeg = angle),
        navigation = PlanningNavigation(emptyList(), base))

    @Test(timeout = 600_000) fun aDeadEndIsOrderedLikeZigzagAndWarned() {
        // 0° no longer leads to a dead end since the U-turns are costed at the speed the tracking drives them (turn
        // tracking, step C): the Zamboni order is found there (8 cells, 81 m of transit against 104 m in Zigzag).
        for (angle in listOf(115, 155)) {
            val zigzag = RoutePlanning.calculate(request(angle), CoverageMode.LINES).selected
            assertTrue("zigzag $angle°: ${zigzag.diagnostics}", zigzag.valid)
            val zamboni = RoutePlanning.calculate(request(angle), CoverageMode.ZAMBONI)
            val selected = zamboni.selected
            assertTrue("$angle°: ${selected.status} ${selected.diagnostics}", selected.valid)
            assertEquals(0, selected.metrics!!.geometricViolations)
            val preview = selected.preview!!
            assertEquals(listOf(CoverageMode.ZAMBONI), preview.zoneModes)
            assertEquals(listOf(angle), preview.zoneAngles)
            assertEquals("$angle°", listOf(-1), preview.zoneReorderedCells)
            assertEquals(listOf("Zamboni sur Aire de jeux : ordre Zamboni sans raccord sûr, ordre du zigzag"), zoneModeWarnings(map, preview))
            // Deterministic.
            assertEquals(selected.planHash, RoutePlanning.calculate(request(angle), CoverageMode.ZAMBONI).selected.planHash)
        }
    }
}
