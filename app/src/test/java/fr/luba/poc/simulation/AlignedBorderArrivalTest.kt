package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Border mowing of 2026-10-25 (Aire de jeux, Fold log): after a bumper contact near the swing poles at 15:42:16 and a
 * 70 cm back-off, the link to the rest of the border pass met it at 74°, nose first: the front went over the border and
 * the tracking stopped ("Enveloppe robot hors du couloir sûr", 15:42:47), then again on the resumes further on.
 * A link joining a pass near the outline now ends along the pass axis.
 */
class AlignedBorderArrivalTest {
    private fun circle(c: Point, r: Double) = (0 until 64).map { i -> val a = i*Math.PI*2/64; val k = r/kotlin.math.cos(Math.PI/64)
        Point(c.x + k*kotlin.math.cos(a), c.y + k*kotlin.math.sin(a)) }
    private fun headingOf(a: Point, b: Point) = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
    private fun turn(a: Double, b: Double) = abs(((b - a + 540) % 360) - 180)

    private fun assertAligned(routes: TrajectoryPlanner, link: List<SimEdge>, end: Point, heading: Double, passHeading: Double) {
        assertEquals(end, link.last().b)
        val joined = link.dropWhile { it.escape }
        val arrival = headingOf(joined.last().a, joined.last().b)
        assertTrue("arrival at ${"%.0f".format(arrival)}° on a pass at ${"%.0f".format(passHeading)}°",
            turn(arrival, passHeading) <= TrajectoryPlanner.ALIGNED_ARRIVAL_DEGREES + 1e-6)
        val fromHeading = link.lastOrNull { it.escape }?.let { headingOf(it.a, it.b) } ?: heading
        routes.requirePath(listOf(joined.first().a) + joined.map { it.b }, fromHeading, passHeading)
    }

    @Test(timeout = 600000) fun linkJoinsTheBorderPassAlongItsAxis() {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val map = RobotMap(shapes, true)
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        // Swing poles (0.1 m + 0.1 m margin) and the obstacle saved after the contact (0.2 m + 0.3 m).
        val poles = listOf(Point(-9.9668, 9.1529), Point(-9.9232, 8.0784), Point(-8.2994, 9.3450), Point(-8.1299, 8.0475))
        val contact = Point(-7.947, 8.6925)
        val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
        val routes = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + poles.map { circle(it, 0.2) } + listOf(circle(contact, 0.5)),
            recordedBoundary = true)
        val physical = fr.luba.poc.planning.RoutePlanning.navigation(map, margin, noGo + poles.map { circle(it, 0.1) } + listOf(circle(contact, 0.2)),
            recordedBoundary = true)
        // Pass along the north-east border (edge 91 of the resumed route) and the pose after the back-off (15:42:30).
        val passA = Point(-7.545255762700806, 8.942666744856172); val passB = Point(-5.340298194155903, 7.420233694927273)
        val passHeading = headingOf(passA, passB)
        val entry = Point(passA.x + (passB.x - passA.x) * 0.32, passA.y + (passB.y - passA.y) * 0.32)
        val start = Point(-7.270135862837389, 7.647587500128143); val heading = 122.93

        // Former link: last segment nose first into the pass.
        val plain = routes.directJoin(routes.between(start, entry, heading, passHeading), heading, passHeading)
        assertTrue("field case: steep arrival expected, ${headingOf(plain[plain.size - 2], plain.last())}°",
            turn(headingOf(plain[plain.size - 2], plain.last()), passHeading) > TrajectoryPlanner.ALIGNED_ARRIVAL_DEGREES)

        // Upstream of the entry, the saved obstacle leaves no room to arrive along the pass: the direct link is kept.
        assertEquals(plain, routes.alignedArrival(plain, heading, passHeading))
        // Entry further on: the link ends along the pass axis.
        val further = Point(passA.x + (passB.x - passA.x) * 0.6, passA.y + (passB.y - passA.y) * 0.6)
        val link = RobotRoute.departure(routes, physical, start, heading, further, passHeading)
        assertAligned(routes, link, further, heading, passHeading)
        val nose = routes.directJoin(routes.between(start, further, heading, passHeading), heading, passHeading)
        assertTrue("detour ${link.sumOf { it.meters }} m", link.sumOf { it.meters } <=
            nose.zipWithNext().sumOf { (a, b) -> a.distance(b) } + TrajectoryPlanner.ALIGNED_ARRIVAL_EXTRA_M + 1e-6)

        // Resume after the stop: the strict search takes an entry further on the pass, met along its axis.
        val path = listOf(SimEdge(passA, entry, true), SimEdge(entry, passB, true))
        val plan = DriftRecovery.plan(routes, physical, path, null, 1, start, heading, listOf(Point(-7.65, 8.235), start))
        val joined = plan.edges.take(plan.origins.indexOfFirst { it >= 0 })
        assertTrue("link expected", joined.isNotEmpty())
        assertAligned(routes, joined, joined.last().b, heading, passHeading)

        // Target inside the comfort surface (centre of the zone): link unchanged.
        val inner = Point(-11.0, 8.0)
        val direct = routes.directJoin(routes.between(start, inner, heading, 0.0), heading, 0.0)
        assertEquals(direct, routes.alignedArrival(direct, heading, 0.0))
    }
}
