package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Border contacts played by the obstacle simulation ([ObstacleEncounterSimulation], same classification and resume as
 * actual mowing): fallback to the obstacle path when no back-off is possible, and the limit contact on a pass of the
 * `Enfer` reference zone (plan 2.6 / 2.7).
 */
class EdgeContactSimulationTest {
    private val robot = RobotEnvelope.LUBA2
    private val square = listOf(Point(0.0, 0.0), Point(14.0, 0.0), Point(14.0, 12.0), Point(0.0, 12.0))
    private val map = RobotMap(listOf(MapShape(1, 0, square, "Carré")), true, "carré")
    private val routes = RoutePlanning.navigation(map, robot.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
    private val wall = ProbeObject(ProbeShape.SQUARE, Point(7.0, 13.5), 3.0)
    private val edges = listOf(SimEdge(Point(7.0, 6.0), Point(7.0, 11.4), true), SimEdge(Point(7.0, 11.4), Point(7.6, 11.4), false),
        SimEdge(Point(7.6, 11.4), Point(7.6, 6.0), true))

    /** Slide of about 20 cm towards the north border during the first pivot near it (same as the 19/10 replay). */
    private fun slope(): (Point) -> Point {
        var top = 0.0
        return { p -> top = maxOf(top, p.y); if (top >= 11.62 || p.y < top - 0.1) Point(0.0, 0.0) else Point(0.0, 0.12) }
    }

    /**
     * Limit contact without any possible back-off (the physical surface behind the robot is closed by a no-go zone):
     * `DriftRecovery.plan(afterContact = true)` fails, the contact goes down the obstacle path as before (estimated
     * obstacle, bypass or stop), and it is not counted as a border contact.
     */
    @Test(timeout = 600000) fun limitContactWithoutBackOffFallsBackToTheObstaclePath() {
        // No-go zone covering the trail behind the robot, physical planner only (the tracking itself is unaffected).
        val behind = listOf(Point(5.0, 8.0), Point(9.0, 8.0), Point(9.0, 11.25), Point(5.0, 11.25))
        val physical = RoutePlanning.navigation(map, robot.halfWidthM + 0.05, listOf(behind), recordedBoundary = true)
        val sim = ObstacleEncounterSimulation(routes, physical, edges, wall, listOf(square), dynamics = SimulatedRobot.Params(pivotSlide = slope()))
        sim.finish()
        println("SIM border contact, no back-off: ${sim.phase} · ${sim.contacts.size} contact(s) · ${sim.log.joinToString(" | ")}")
        assertTrue(sim.contacts.isNotEmpty())
        // The same contact without the no-go zone is a limit contact (ObstacleEncounterSimulationTest).
        val first = sim.contacts.first()
        assertTrue("classified a limit contact first: ${sim.log}", sim.log.any { it.contains("Contact en bordure : Recul impossible") })
        assertEquals("not counted as a border contact", 0, sim.edgeContacts)
        assertNull("no border resume", first.resume)
        assertEquals("obstacle path: estimated obstacle recorded", 1, sim.discovered.size)
        assertTrue("obstacle path: repair or stop", first.repair != null || first.failure != null)
    }

    private class EnferReplay(val sim: ObstacleEncounterSimulation, val route: List<SimEdge>, val pass: Int, val inside: Double)

    private fun heading(e: SimEdge) = Math.toDegrees(atan2(e.b.y - e.a.y, e.b.x - e.a.x))
    private fun angle(a: Double, b: Double) = abs(((b - a) % 360 + 540) % 360 - 180)

    /**
     * Pass of the `Enfer` reference zone ending near the outer border (0.45-0.75 m inside, heading within 45° of it,
     * then a U-turn), wall 3 m wide whose inner face lies [beyondM] beyond the drawn border, and a slide of [slideMps]
     * towards the border while pivoting at the end of the pass, up to [slideM]; with [holdAfterBackOff] the ground holds
     * once the robot has backed off. Played on that pass and the turn only.
     */
    private fun enferReplay(beyondM: Double, slideMps: Double, slideM: Double, holdAfterBackOff: Boolean,
                            limitLinkMaxM: Double = EdgeContact.MAX_LINK_M): EnferReplay {
        val (refMap, base) = ReferenceRoutes.referenceMap()
        val zone = refMap.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == "Enfer" }
        val route = ObstacleEncounterSimulation.route(refMap, zone, base, emptyList(), emptyList())
        val border = EdgeContact.Border(route.zones)
        val path = route.edges
        // Next long cutting edge within a few edges of [i] (the pass after the turn).
        fun next(i: Int) = (i + 1..minOf(i + 4, path.lastIndex)).firstOrNull { j -> path[j].cutting && path[j].meters > 2.0 }
        fun outward(p: Point) = border.nearest(p).let { Math.toDegrees(atan2(it.y - p.y, it.x - p.x)) }
        val picks = (0 until path.size - 2).filter { i ->
            val e = path[i]
            e.cutting && e.meters >= 3.0 && border.signedDistance(e.b) in 0.45..0.75 && angle(heading(e), outward(e.b)) <= 45.0 &&
                next(i)?.let { angle(heading(path[it]), heading(e)) >= 150.0 } == true
        }
        assertTrue("a pass ending near the border in Enfer", picks.isNotEmpty())
        val i = picks[picks.size / 2]
        val e = path[i]
        val out = outward(e.b)
        val n = Point(cos(Math.toRadians(out)), sin(Math.toRadians(out)))
        val near = border.nearest(e.b)
        val wall = ProbeObject(ProbeShape.SQUARE, Point(near.x + (1.5 + beyondM) * n.x, near.y + (1.5 + beyondM) * n.y), 3.0, rotationDeg = out)
        assertTrue("wall beyond the border", wall.outline().all { border.signedDistance(it) <= -beyondM + 0.01 })
        val h = Math.toRadians(heading(e))
        val sub = listOf(e.copy(a = Point(e.b.x - 3.0 * cos(h), e.b.y - 3.0 * sin(h)))) + path.subList(i + 1, requireNotNull(next(i)) + 1)
        var top = -1e9
        var held = false
        val slide: (Point) -> Point = { p ->
            val s = (p.x - e.b.x) * n.x + (p.y - e.b.y) * n.y
            top = maxOf(top, s)
            if (holdAfterBackOff && top > 0.0 && s < top - 0.1) held = true
            if (held || top >= slideM || s < top - 0.1 || s < -0.3) Point(0.0, 0.0) else Point(slideMps * n.x, slideMps * n.y)
        }
        val sim = ObstacleEncounterSimulation(route.engine.routes, route.physical, sub, wall, route.zones, dynamics = SimulatedRobot.Params(pivotSlide = slide),
            limitLinkMaxM = limitLinkMaxM)
        sim.finish()
        println("SIM Enfer border contact (pass $i, %.2f m inside, wall %.2f m beyond, slide %.2f m/s): ${sim.phase} · ${sim.contacts.size} contact(s) · deviation %.2f m · ${sim.log.joinToString(" | ")}"
            .format(border.signedDistance(e.b), beyondM, slideMps, sim.worstDeviationM))
        sim.contacts.forEach { println("  contact ${it.pose} ${it.edge?.kind} ${it.edge?.cause} approach ${it.edge?.approachDegrees} drift ${it.edge?.driftM} " +
            "repair ${it.repair?.bypassMeters} resume link ${it.resume?.rejoinMeters}") }
        return EnferReplay(sim, sub, i, border.signedDistance(e.b))
    }

    /**
     * Plan 2.6 (left open by the delivery): limit contact on a pass of the `Enfer` reference zone. The robot slides
     * about 30 cm towards a wall standing 6 cm beyond the border while pivoting at the end of an oblique pass. Expected
     * (2.7): 1 contact classified LIMIT, no obstacle, no repair, back-off of 0.3 to 1.0 m, link <= 2 m, skipped cutting
     * <= 1.0 m, route finished.
     */
    @Test(timeout = 900000) fun referenceZonePassEndingAtTheBorderBacksOffAndResumes() {
        val replay = enferReplay(beyondM = 0.06, slideMps = 0.3, slideM = 0.3, holdAfterBackOff = true)
        val sim = replay.sim
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
        assertEquals(1, sim.contacts.size)
        val contact = sim.contacts.single()
        assertEquals(contact.edge.toString(), EdgeContactKind.LIMIT, contact.edge?.kind)
        assertEquals("coded_with_drift", contact.edge?.cause)
        assertEquals(1, sim.edgeContacts)
        assertTrue("no obstacle", sim.discovered.isEmpty())
        assertNull("no repair", contact.repair)
        assertTrue(sim.detourRefusals.isEmpty())
        val resume = requireNotNull(contact.resume)
        val backOff = requireNotNull(resume.backOff).distance(contact.pose.position)
        assertTrue("back-off %.2f m".format(backOff), backOff >= 0.3 - 1e-6 && backOff <= 1.0 + 1e-6)
        assertTrue("link %.2f m".format(resume.rejoinMeters), resume.rejoinMeters <= 2.0)
        assertTrue("skipped %.2f m".format(resume.skippedCuttingMeters), resume.skippedCuttingMeters <= 1.0)
        assertEquals(replay.route.last().b, sim.edges.last().b)
        assertTrue("deviation %.2f m".format(sim.worstDeviationM), sim.worstDeviationM < 0.3)
    }

    /**
     * QA reproduction (2026-10-28): same pass, wall right on the drawn border, slower slide (12 cm/s, as in the 19/10
     * replay). The front corner touches at 77° from the outward direction, so the first contact is an object at the
     * edge (estimated obstacle and repair). The next contact, 0.3 m from it, was classified LIMIT and resumed through a
     * 52 m blades-OFF link computed around that obstacle. Now a contact within 1.5 m of an obstacle discovered during
     * the mission is an object at the edge (`near_obstacle`), and any limit contact left keeps a link <= 2 m (plan 2.7).
     */
    @Test(timeout = 900000) fun limitContactNextToAnObjectAtTheEdgeKeepsAShortLink() {
        val sim = enferReplay(beyondM = 0.0, slideMps = 0.12, slideM = 0.22, holdAfterBackOff = false).sim
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
        assertTrue(sim.contacts.size >= 2)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, sim.contacts.first().edge?.kind)
        val next = sim.contacts[1]
        assertEquals(next.edge.toString(), EdgeContactKind.OBJECT_AT_EDGE, next.edge?.kind)
        assertEquals("near_obstacle", next.edge?.cause)
        for (c in sim.contacts) {
            c.resume?.let { assertTrue("link %.1f m after a limit contact".format(it.rejoinMeters), it.rejoinMeters <= 2.0) }
            c.repair?.let { assertTrue("bypass %.1f m".format(it.bypassMeters), it.bypassMeters <= 2.0) }
        }
        assertTrue(sim.detourRefusals.isEmpty())
    }

    /**
     * Guard on the resume link: the limit contact of [referenceZonePassEndingAtTheBorderBacksOffAndResumes] (link
     * about 0.26 m) played with a cap lowered to 0.1 m. The limit path is dropped (note logged, not counted as a border
     * contact, no resume) and the contact goes down the obstacle path: estimated obstacle, repair, route finished.
     */
    @Test(timeout = 900000) fun limitContactWithATooLongResumeLinkTakesTheObstaclePath() {
        val sim = enferReplay(beyondM = 0.06, slideMps = 0.3, slideM = 0.3, holdAfterBackOff = true, limitLinkMaxM = 0.1).sim
        assertTrue(sim.contacts.isNotEmpty())
        val first = sim.contacts.first()
        assertEquals(first.edge.toString(), EdgeContactKind.LIMIT, first.edge?.kind)
        assertTrue("guard noted: ${sim.log}", sim.log.any { it.contains("Contact en bordure : raccord de reprise de") && it.contains("traité comme un obstacle") })
        assertEquals("not counted as a border contact", 0, sim.edgeContacts)
        assertNull("no border resume", first.resume)
        assertEquals("obstacle path: estimated obstacle recorded", 1, sim.discovered.size)
        assertNotNull("obstacle path: repair", first.repair)
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
    }
}
