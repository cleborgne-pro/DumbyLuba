package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.planning.RoutePlanning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Mowing of 2026-10-14 at 14:14 (0.38.2), real map: bumper contact at edge 46, bypass computed, then stop
 * "Enveloppe robot hors du couloir sûr" at 14:17:16 at (-0.73; -9.72), robot pivoting right at the border at the end of
 * the detour, and resume refused 10 s later ("Écart au trajet >30 cm"). Route, trail and contact pose: Fold log
 * (`route-2026-10-14-contact.json`).
 *
 * Since 0.40.0, the recorded outline is the outer side of the chassis: the route of that day (border pass 25 cm from
 * the drawn border) is no longer a valid route. The tests therefore replay the same zone, the same object and the same
 * stop pose on the route recomputed with the current rule.
 */
class ZoneExit20261014Test {
    private val fixture = JSONObject(javaClass.getResourceAsStream("/route-2026-10-14-contact.json")!!.bufferedReader().use { it.readText() })
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
    private val base = fixture.getJSONObject("base").let { b ->
        MapBase(Point(b.getDouble("x"), b.getDouble("y")), "Base du 14/10", 1.5, b.getDouble("heading"), b.getDouble("approachM")) }
    private val obstacles = fixture.getJSONArray("obstacles").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) }.map { o ->
        LocalObstacle(o.getString("id"), ObstacleKind.OTHER, o.getDouble("x"), o.getDouble("y"), o.getDouble("radiusM"), o.getDouble("marginM")) } }
    private val routes = RoutePlanning.mission(map, obstacles, emptyList(), base)
    private val physical = RoutePlanning.physical(map, obstacles)
    private val edges = fixture.getJSONArray("edges").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) }.map { e ->
        SimEdge(Point(e.getDouble(0), e.getDouble(1)), Point(e.getDouble(2), e.getDouble(3)), e.getInt(4) == 1) } }
    private val trail = fixture.getJSONArray("trail").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) }.map { Point(it.getDouble(0), it.getDouble(1)) } }
    private val contact = fixture.getJSONObject("contact")
    private val contactAt = Point(contact.getDouble("x"), contact.getDouble("y"))
    private val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.id.toString() == fixture.getString("zoneId") }.map { it.points }

    /** Route of the same zone, computed with the current rule (centre at least half a width from the outlines). */
    private val route by lazy {
        val zone = map.shapes.single { it.id.toString() == fixture.getString("zoneId") }
        ObstacleEncounterSimulation.route(map, zone, base, emptyList(), obstacles)
    }
    private val obstacleAt = Point(-0.3836, -8.7248)

    @Test(timeout = 600_000) fun historicBorderPassIsNowKeptHalfAWidthInside() {
        val zone = zones.single()
        fun toBorder(p: Point) = zone.indices.minOf { i -> Geometry.distance(p, zone[i], zone[(i + 1) % zone.size]) }
        val near = { e: SimEdge -> e.cutting && e.a.distance(contactAt) < 3.0 }
        // On 2026-10-14: border pass 25 cm from the drawn border, chassis straddling the outline.
        assertTrue(edges.filter(near).minOf { minOf(toBorder(it.a), toBorder(it.b)) } < 0.26)
        // Today: all the cutting keeps the centre half a width plus the margin from the outline.
        val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
        val inZone = route.edges.filter { it.cutting && Geometry.inside(it.a, zone) && Geometry.inside(it.b, zone) }
        assertTrue(inZone.isNotEmpty())
        assertTrue(inZone.all { minOf(toBorder(it.a), toBorder(it.b)) >= half - 1e-6 })
    }

    @Test(timeout = 600_000) fun exitAtTheSamePoseIsRecoveredOnTheCurrentRoute() {
        val failure = fixture.getJSONObject("failure")
        val pose = RobotPose(Point(failure.getDouble("x"), failure.getDouble("y")), failure.getDouble("heading"))
        // Trail of the detour of 2026-10-14: the robot came from the west while turning.
        val detourTrail = listOf(Point(-1.607, -8.576), Point(-1.591, -8.914), Point(-1.503, -9.209), Point(-1.335, -9.497),
            Point(-1.131, -9.664), Point(-0.937, -9.744), Point(-0.792, -9.788), Point(-0.768, -9.734), pose.position)
        val path = route.edges
        val index = path.indices.minBy { Geometry.distance(pose.position, path[it].a, path[it].b) }
        val plan = DriftRecovery.plan(route.engine.routes, route.physical, path, null, index, pose.position, pose.headingDegrees, detourTrail)
        println("Resume: back-off ${plan.backOff?.distance(pose.position)}, link %.2f m, skipped cutting %.2f m".format(plan.rejoinMeters, plan.skippedCuttingMeters))
        plan.backOff?.let { assertTrue(DriftRecovery.bodyInside(route.physical, it, pose.headingDegrees)) }
        val joined = plan.edges.filterNot { it.escape }
        route.engine.routes.requirePath(listOf(joined.first().a) + joined.map { it.b })
        assertEquals(path.last().b, plan.edges.last().b)
        assertTrue(plan.edges.indices.takeWhile { plan.origins[it] == -1 }.none { plan.edges[it].bladesOn })
    }

    /**
     * End to end with the actual pilot on a simulated robot (same footprint check as the gateway): the object of
     * 2026-10-14 is hit and bypassed with the tracking tolerance; any route exit is recovered and the mowing continues.
     */
    @Test(timeout = 600_000) fun encounterSimulationRecoversInsteadOfStopping() {
        val obstacle = ProbeObject(ProbeShape.CIRCLE, obstacleAt, 0.4)
        val sim = ObstacleEncounterSimulation(route.engine.routes, route.physical, route.edges, obstacle, route.zones,
            ObstacleEncounterSimulation.startNear(route.edges, obstacle), speedPercent = 30, envelopeGuard = true)
        sim.finish(limitS = 1800.0)
        sim.log.forEach(::println)
        println("Contacts ${sim.contacts.size}, resumes ${sim.driftRecoveries}, phase ${sim.phase}, max deviation %.2f m".format(sim.worstDeviationM))
        assertTrue(sim.contacts.isNotEmpty())
        assertNotEquals(sim.message, EncounterPhase.STOPPED, sim.phase)
        val repair = requireNotNull(sim.contacts.first().repair)
        println("Detours: " + repair.detours.joinToString { "side ${it.side} r=%.2f %.2f m border %.2f m".format(it.radiusM, it.meters, it.borderClearanceM) })
        // Links after the first (which starts from the interrupted pass): tracking tolerance, entry pivot included.
        var i = 0; var links = 0
        while (i < repair.edges.size) {
            if (repair.origins[i] != -1) { i++; continue }
            var j = i
            while (j < repair.edges.size && repair.origins[j] == -1) j++
            if (links++ > 0 && i > 0) {
                val points = listOf(repair.edges[i].a) + repair.edges.subList(i, j).map { it.b }
                assertTrue("link $i..${j - 1}", repair.routes.pathTolerant(points, PathFollower.headingOf(repair.edges[i - 1]),
                    repair.edges.getOrNull(j)?.let { PathFollower.headingOf(it) }) || !repair.routes.pathTolerant(listOf(points.last()), PathFollower.headingOf(repair.edges[j - 1]), null))
            }
            i = j
        }
    }
}
