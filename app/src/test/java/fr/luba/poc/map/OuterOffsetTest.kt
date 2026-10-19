package fr.luba.poc.map

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OuterOffsetTest {
    private fun minDistanceToBoundary(path: List<Point>, zone: List<Point>) =
        path.minOf { p -> (zone + zone.first()).zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) } }

    @Test(timeout = 300000) fun `outer loop respects the requested offset on the real map`() {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val zone = shapes.single { it.name == "Piscine" }
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        fun outer(offset: Double) = CoveragePlanner.plan(zone.points, holes, 0.25, 0.6, CoverageMode.LINES, true,
            recordedBoundary = true, outerOffsetM = offset).let { it.borders.take(it.outerLoops) }
        val historic = outer(CoveragePlanner.RECORDED_OUTER_OFFSET_M)
        val wider = outer(0.35)
        assertTrue(historic.isNotEmpty() && wider.isNotEmpty())
        val historicMin = historic.minOf { minDistanceToBoundary(it, zone.points) }
        val widerMin = wider.minOf { minDistanceToBoundary(it, zone.points) }
        // Recorded boundary = side of the chassis: the center passes half a width further than the set margin.
        val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
        assertTrue("historic $historicMin m", historicMin >= half + CoveragePlanner.RECORDED_OUTER_OFFSET_M - 0.01)
        assertTrue("offset 35 cm: $widerMin m", widerMin >= half + 0.35 - 0.01)
        assertTrue(widerMin > historicMin + 0.2)
        // Up to 1 m (2026-10-14): the loop really moves away from the boundary, with no 30 cm cap.
        for (offset in listOf(0.6, CoveragePlanner.MAX_OUTER_OFFSET_M)) {
            val loops = outer(offset)
            assertTrue("offset $offset m: no loop", loops.isNotEmpty())
            val min = loops.minOf { minDistanceToBoundary(it, zone.points) }
            assertTrue("offset $offset m: $min m", min >= offset - 0.01)
        }
        assertEquals(1.0, OuterOffsetSetting.sanitize(1.0), 1e-9)
    }

    @Test(timeout = 300000) fun `signal survey outer loop shifted by its dedicated setting`() {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val zone = shapes.single { it.name == "Piscine" }
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        // Adjustable survey offset, passed down to the merged full loop.
        fun tour(offset: Double) = MultiZonePlanner.plan(listOf(zone), holes, 0.25, 0.6, CoverageMode.TOUR, false,
            recordedBoundary = true, outerOffsetM = offset).let { it.borders.take(it.outerLoops) }
        val byDefault = tour(SurveyOffsetSetting.DEFAULT_M)
        val wider = tour(0.40)
        assertTrue(byDefault.isNotEmpty() && wider.isNotEmpty())
        val defaultMin = byDefault.minOf { minDistanceToBoundary(it, zone.points) }
        val widerMin = wider.minOf { minDistanceToBoundary(it, zone.points) }
        // Margin between the side of the robot and the boundary: center half a width further.
        val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
        assertEquals(0.10, SurveyOffsetSetting.DEFAULT_M, 0.0)
        assertTrue("survey 10 cm: $defaultMin m", defaultMin >= half + 0.10 - 0.01)
        assertTrue("survey 40 cm: $widerMin m", widerMin >= half + 0.40 - 0.01 && widerMin > defaultMin + 0.1)
    }

    @Test fun `setting clamped and rounded`() {
        assertEquals(OuterOffsetSetting.DEFAULT_M, OuterOffsetSetting.sanitize(Double.NaN), 0.0)
        assertEquals(1.0, OuterOffsetSetting.sanitize(5.0), 0.0)
        assertEquals(OuterOffsetSetting.MIN_M, OuterOffsetSetting.sanitize(0.0), 0.0)
        assertEquals(0.33, OuterOffsetSetting.sanitize(0.3333), 1e-9)
        assertEquals(SurveyOffsetSetting.DEFAULT_M, SurveyOffsetSetting.sanitize(Double.NaN), 0.0)
        assertEquals(OuterOffsetSetting.MIN_M, SurveyOffsetSetting.sanitize(-1.0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { fr.luba.poc.planning.PlanningConfig(outerOffsetM = 1.5) }
    }
}
