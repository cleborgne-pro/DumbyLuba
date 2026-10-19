package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Signal loop refused on 2026-10-10 at 11:24: "Collision du châssis pendant le virage au segment 1".
 * The robot had stopped 0.87 m from an obstacle created by a bumper contact (radius 0.2 m, margin 0.3 m): the
 * departure pivot entered the margin, the route prepared from the base ignored it. */
class RouteDepartureEscapeTest {
    private fun circle(c: Point, r: Double) = (0 until 64).map { i -> val a = i*Math.PI*2/64; val k = r/kotlin.math.cos(Math.PI/64)
        Point(c.x + k*kotlin.math.cos(a), c.y + k*kotlin.math.sin(a)) }

    @Test(timeout = 600000) fun departureInsideAnObstacleReserveEscapesStraightThenFollowsTheTour() {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val map = RobotMap(shapes, true)
        val station = json.getJSONObject("station")
        val base = Point(station.getDouble("x"), station.getDouble("y")); val baseHeading = station.getDouble("heading")
        val start = Point(-12.9746, 7.2112)
        val zone = shapes.first { it.kind == ShapeKind.ZONE && Geometry.inside(start, it.points) }
        // Obstacles saved on the Fold after the bumper contacts (radius 0.2 m + margin 0.3 m).
        val obstacles = listOf(Point(-12.399586854574773, 5.124342482468902), Point(-13.827012900872177, 7.023493210873356))
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
        val routes = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + obstacles.map { circle(it, 0.5) },
            base = MapBase(base, "Transmise par le robot", 1.5, baseHeading, 1.5), recordedBoundary = true)
        val physical = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + obstacles.map { circle(it, 0.2) }, recordedBoundary = true)
        assertFalse("the departure pivot is indeed forbidden by the margin", routes.supportsPivot(start))
        val preview = MultiZonePlanner.plan(listOf(zone), noGo, 3.0, 0.6, CoverageMode.TOUR, true,
            obstacles = obstacles.map { ObstacleDisc(it.x, it.y, 0.5 + RobotEnvelope.LUBA2.turnClearanceM) } +
                ObstacleDisc(base.x, base.y, TrajectoryPlanner.baseSafetyRadius(1.5)),
            recordedBoundary = true, basePoint = base)
        val engine = MowingSimulation(routes, base, preview, map, SimConfig(baseEntryHeadingDegrees = baseHeading, instantCharging = true))
        // Without escape: same refusal as in the field.
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.edges(engine, start, 327.0) }
        for (heading in listOf(327.0) + (0 until 360 step 30).map { it.toDouble() }) {
            // Every heading starts: escape if needed, otherwise a straight exit already safe.
            val edges = RobotRoute.edges(engine, start, heading, physical)
            assertEquals(start, edges.first().a)
            assertTrue(edges.count { it.escape } <= 1)
            assertEquals(engine.baseApproach, edges.last().b)
            if (heading == 327.0) assertTrue("field heading: escape expected", edges.first().escape)
            edges.firstOrNull { it.escape }?.let { escape ->
                assertEquals(0, edges.indexOf(escape))
                assertTrue(escape.meters <= 2.0)
                assertTrue(routes.supportsPivot(escape.b))
                obstacles.forEach { assertTrue(Geometry.distance(it, escape.a, escape.b) >= 0.2 + RobotEnvelope.LUBA2.halfWidthM) }
            }
        }
    }
}
