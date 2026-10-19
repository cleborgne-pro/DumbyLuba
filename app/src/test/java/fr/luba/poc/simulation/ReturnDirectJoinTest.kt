package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Return to base of 2026-10-13 (15:16, Fold log): robot stopped 41.5 cm from a border of the Piscine zone, heading 23°.
 * The link started with a perpendicular exit of 8.5 cm at 124° towards the comfort surface, then set off again at
 * 33°: 100° pivot in place, robot protection (self-check 25) during this pivot, return interrupted.
 */
class ReturnDirectJoinTest {
    private fun circle(c: Point, r: Double) = (0 until 64).map { i -> val a = i*Math.PI*2/64; val k = r/kotlin.math.cos(Math.PI/64)
        Point(c.x + k*kotlin.math.cos(a), c.y + k*kotlin.math.sin(a)) }
    private fun headingOf(e: SimEdge) = Math.toDegrees(atan2(e.b.y - e.a.y, e.b.x - e.a.x))
    private fun turn(a: Double, b: Double) = abs(((b - a + 540) % 360) - 180)

    @Test(timeout = 600000) fun returnFromNearABorderJoinsObliquelyWithoutAPivot() {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val map = RobotMap(shapes, true)
        val station = json.getJSONObject("station")
        val base = Point(station.getDouble("x"), station.getDouble("y")); val baseHeading = station.getDouble("heading")
        // Obstacle saved after the bumper contact of 15:12 (radius 0.2 m + margin 0.3 m).
        val obstacles = listOf(Point(-19.52285474661653, -13.564523748609211))
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
        val routes = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + obstacles.map { circle(it, 0.5) },
            base = MapBase(base, "Transmise par le robot", 1.5, baseHeading, 1.5), recordedBoundary = true)
        val physical = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + obstacles.map { circle(it, 0.2) }, recordedBoundary = true)
        val start = Point(-17.2389, -11.724)
        val zone = shapes.first { it.kind == ShapeKind.ZONE && Geometry.inside(start, it.points) }
        val preview = MultiZonePlanner.plan(listOf(zone), noGo, 3.0, 0.6, CoverageMode.TOUR, true,
            obstacles = obstacles.map { ObstacleDisc(it.x, it.y, 0.5 + RobotEnvelope.LUBA2.turnClearanceM) } +
                ObstacleDisc(base.x, base.y, TrajectoryPlanner.baseSafetyRadius(1.5)),
            recordedBoundary = true, basePoint = base)
        val engine = MowingSimulation(routes, base, preview, map, SimConfig(baseEntryHeadingDegrees = baseHeading, instantCharging = true))

        // Actual poses of the two departures near the border (15:16:01 and after the stop at 15:16:09).
        for ((pose, heading) in listOf(start to 23.0, Point(-17.013, -11.840) to 124.4)) {
            val edges = RobotRoute.actionEdges(engine, pose, heading, RouteIntent.RETURN_TO_BASE, physical)
            assertEquals(pose, edges.first().a)
            assertEquals(engine.baseApproach, edges.last().b)
            assertTrue("short first edge: ${edges.first()}", edges.first().meters >= 0.3)
            assertTrue("U-turn at the start: ${headingOf(edges.first())}°", turn(heading, headingOf(edges.first())) < 70.0)
            // Outline = chassis side: too close to the border to pivot, the robot exits straight along its axis, then pivots.
            val joined = edges.dropWhile { it.escape }
            if (edges.first().escape) assertTrue("escape along the axis", turn(heading, headingOf(edges.first())) < 1.0)
            else assertTrue("sharp turn just after the start", turn(headingOf(edges[0]), headingOf(edges[1])) < PathFollower.CORNER_DEGREES)
            routes.requirePath(listOf(joined.first().a) + joined.map { it.b }, headingOf(edges.first()), (engine.dockEntryHeading + 180) % 360)
        }
        // Field heading: no more in-place pivot (below the tracking's pivot entry threshold).
        val edges = RobotRoute.actionEdges(engine, start, 23.0, RouteIntent.RETURN_TO_BASE, physical)
        assertTrue(turn(23.0, headingOf(edges.first())) < PathFollower.PIVOT_ENTER_DEGREES)

        // Start already inside the comfort surface (15:15:10): direct link unchanged.
        val far = RobotRoute.actionEdges(engine, Point(-18.742, -12.1484), 67.3, RouteIntent.RETURN_TO_BASE, physical)
        assertEquals(Point(-11.563433298029885, -8.2049594096105).x, far.first().b.x, 1e-6)
        assertEquals(Point(-11.563433298029885, -8.2049594096105).y, far.first().b.y, 1e-6)
    }
}
