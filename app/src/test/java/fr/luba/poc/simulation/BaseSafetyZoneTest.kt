package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Returns to base: safety zone around the station, arrival through the approach point in front of it. */
class BaseSafetyZoneTest {
    private val shapes: List<MapShape> = run {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
    }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
    private val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, noGo, emptyList(), base = base, recordedBoundary = true)
    private val radius = TrajectoryPlanner.baseSafetyRadius(base.approachDistanceM)
    private val approach = Point(base.point.x - kotlin.math.cos(Math.toRadians(152.8624890921028)) * 1.5,
        base.point.y - kotlin.math.sin(Math.toRadians(152.8624890921028)) * 1.5)
    private val axis = (152.8624890921028 + 180) % 360

    @Test(timeout = 300000) fun returnsFromEveryZoneArriveInFrontWithoutEnteringTheBaseZone() {
        assertEquals(1.05, radius, 0.01)
        var checked = 0
        for (zone in shapes.filter { it.kind == ShapeKind.ZONE }) {
            val xs = zone.points.map { it.x }; val ys = zone.points.map { it.y }
            val starts = generateSequence(ys.min()) { it + 1.5 }.takeWhile { it < ys.max() }.flatMap { y ->
                generateSequence(xs.min()) { it + 1.5 }.takeWhile { it < xs.max() }.map { x -> Point(x, y) } }
                .filter { Geometry.inside(it, zone.points) && routes.safe(it, it) && it.distance(base.point) > radius + 0.5 }.toList()
            for (start in starts.filterIndexed { i, _ -> i % maxOf(1, starts.size / 6) == 0 }) {
                val path = try { routes.between(start, approach, null, axis) } catch (_: IllegalArgumentException) { continue }
                routes.requirePath(path, null, axis)
                assertEquals(approach, path.last())
                // No segment enters the zone: the robot goes round the station and arrives in front.
                path.zipWithNext().forEach { (a, b) ->
                    assertTrue("${zone.name} $start: segment $a→$b at ${Geometry.distance(base.point, a, b)} m from the base",
                        Geometry.distance(base.point, a, b) >= radius - 0.03)
                }
                checked++
            }
        }
        assertTrue("returns checked: $checked", checked >= 20)
    }

    @Test fun stationIsOnlyReachedStraightThroughItsFrontCorridor() {
        // Point just behind / beside the station: forbidden to routes.
        val rad = Math.toRadians(152.8624890921028)
        val behind = Point(base.point.x + kotlin.math.cos(rad) * 0.6, base.point.y + kotlin.math.sin(rad) * 0.6)
        val side = Point(base.point.x - kotlin.math.sin(rad) * 0.6, base.point.y + kotlin.math.cos(rad) * 0.6)
        assertFalse(routes.safe(behind, behind)); assertFalse(routes.safe(side, side))
        // The front corridor stays usable for docking and exit, straight.
        assertTrue(routes.maneuverSafe(approach, base.point))
        assertNull(routes.motionCollision(RobotPose(approach, axis), RobotPose(base.point, axis)))
        routes.requirePath(listOf(base.point, approach), axis, null)
    }

    @Test(timeout = 300000) fun productionPlanKeepsMowingOutOfTheBaseZoneAndPreparesTheMission() {
        val request = PlanningRequest(map, listOf(shapes.single { it.name.trim() == "Aire de jeux" }.id), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base))
        val plan = RoutePlanning.coverage(request)
        assertTrue(plan.report!!.selected.diagnostics.toString(), plan.report!!.selected.valid)
        plan.preview.cuttingPaths.forEach { path -> path.zipWithNext().forEach { (a, b) ->
            assertTrue("pass $a→$b at ${Geometry.distance(base.point, a, b)} m", Geometry.distance(base.point, a, b) >= radius - 0.03) } }
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = 1.5, instantCharging = true))
        assertEquals(approach.x, engine.baseApproach.x, 1e-6); assertEquals(approach.y, engine.baseApproach.y, 1e-6)
        // Return from the end of mowing: arrival through the approach point, outside the zone.
        val ret = RobotRoute.edges(engine, engine.edges.first().a, 0.0)
        assertEquals(engine.baseApproach, ret.last().b)
        ret.filter { !it.cutting }.forEach { e -> assertTrue("link ${e.a}→${e.b} at ${Geometry.distance(base.point, e.a, e.b)} m",
            Geometry.distance(base.point, e.a, e.b) >= radius - 0.03) }
    }

    @Test(timeout = 300000) fun signalGridSurveyRunFromAFarStartNeverCrossesTheBaseZone() {
        val zone = shapes.single { it.name.trim() == "Aire de jeux" }
        val request = PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 3.0, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base))
        for (mode in listOf(CoverageMode.SURVEY_GRID, CoverageMode.TOUR)) {
            val plan = RoutePlanning.coverage(request, mode)
            val engine = MowingSimulation(routes, base.point, plan.preview, map,
                SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = 1.5, instantCharging = true))
            // Actual departures from several positions in the zone, including the back side of the station.
            val xs = zone.points.map { it.x }; val ys = zone.points.map { it.y }
            val starts = generateSequence(ys.min()) { it + 2.0 }.takeWhile { it < ys.max() }.flatMap { y ->
                generateSequence(xs.min()) { it + 2.0 }.takeWhile { it < xs.max() }.map { x -> Point(x, y) } }
                .filter { Geometry.inside(it, zone.points) && routes.safe(it, it) }.toList()
            var checked = 0
            for (start in starts.filterIndexed { k, _ -> k % maxOf(1, starts.size / 8) == 0 }) for (heading in listOf(0.0, 153.0)) {
                val edges = try { RobotRoute.edges(engine, start, heading) } catch (e: IllegalArgumentException) {
                    assertFalse("$mode $start: ${e.message}", e.message.orEmpty().contains("zone de sécurité")); continue }
                assertNull("$mode $start", routes.baseZoneViolation(listOf(edges.first().a) + edges.map { it.b }))
                checked++
            }
            assertTrue("$mode: departures checked $checked", checked >= 8)
        }
    }

    @Test fun passageGraphNeverEntersTheStationFromBehind() {
        // Simple zone with a passage (passage graph active) and a station facing south.
        val zone = MapShape(1, 0, listOf(Point(0.0,0.0), Point(20.0,0.0), Point(20.0,12.0), Point(0.0,12.0)), "Zone")
        val passage = MapShape(2, 2, listOf(Point(2.0,1.0), Point(2.0,11.0)), "Passage")
        val m = RobotMap(listOf(zone, passage), true)
        val station = Point(10.0, 6.0)
        val planner = TrajectoryPlanner.withDock(m, 0.3065, emptyList(), emptyList(), station, 90.0, 1.5, recordedBoundary = true)
        val behind = Point(10.0, 10.0); val front = Point(10.0, 2.0)
        for ((a, b) in listOf(behind to front, front to behind, Point(4.0, 6.0) to Point(16.0, 6.0))) {
            val path = planner.between(a, b, null, null)
            assertNull("$a → $b: $path", planner.baseZoneViolation(path))
            assertTrue(path.none { it.distance(station) < 1.0 })
        }
        // Exit and return: only through the front corridor (approach to the south, entry heading 90°).
        val leaving = planner.between(station, behind)
        assertEquals(Point(10.0, 4.5), leaving[1])
        assertNull(planner.baseZoneViolation(leaving))
        val returning = planner.between(behind, station)
        assertEquals(Point(10.0, 4.5), returning[returning.size - 2])
        assertNull(planner.baseZoneViolation(returning))
        // A route crossing the zone is refused explicitly at launch.
        val crossing = listOf(SimEdge(Point(10.0, 8.0), Point(10.0, 6.5), false))
        val refusal = assertThrows(IllegalArgumentException::class.java) { RobotRoute.requireOutsideBaseZone(planner, crossing) }
        assertTrue(refusal.message.orEmpty().contains("zone de sécurité de la base"))
    }
}
