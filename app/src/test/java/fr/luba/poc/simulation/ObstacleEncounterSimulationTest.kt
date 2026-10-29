package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Simulation of an unknown object placed on the map: contact, back-off and route adapted by the actual mowing building block. */
class ObstacleEncounterSimulationTest {
    private val factory = GeometryFactory()
    private fun polygon(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())

    private fun square(): Pair<RobotMap, List<SimEdge>> {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(14.0, 0.0), Point(14.0, 12.0), Point(0.0, 12.0)), "Carré")
        val rows = (0 until 21).map { 1.0 + it * 0.5 }
        val points = rows.flatMapIndexed { i, y -> if (i % 2 == 0) listOf(Point(1.5, y), Point(12.5, y)) else listOf(Point(12.5, y), Point(1.5, y)) }
        return RobotMap(listOf(zone), true, "carré") to points.zipWithNext().mapIndexed { i, (a, b) -> SimEdge(a, b, i % 2 == 0) }
    }

    private fun run(shape: ProbeShape, size: Double): ObstacleEncounterSimulation {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
        val obstacle = ProbeObject(shape, Point(7.0, 4.0), size, rotationDeg = 10.0)
        val sim = ObstacleEncounterSimulation(routes, routes, edges, obstacle, listOf(map.shapes[0].points),
            ObstacleEncounterSimulation.startNear(edges, obstacle))
        sim.finish()
        return sim
    }

    @Test fun eachShapeIsTouchedThenBypassedUntilTheEndOfTheRoute() {
        for (shape in ProbeShape.entries) for (size in listOf(0.3, 0.8)) {
            val sim = run(shape, size)
            println("SIM $shape $size m : ${sim.phase} · ${sim.contacts.size} contact(s) · ${sim.log.joinToString(" | ")}")
            assertTrue("$shape $size: contact expected", sim.contacts.isNotEmpty())
            assertEquals("$shape $size: ${sim.message}", EncounterPhase.DONE, sim.phase)
            // The robot footprint never crosses the object: each contact stops the robot before.
            val body = polygon(ProbeObject(shape, Point(7.0, 4.0), size, 10.0).outline())
            assertFalse(body.intersects(factory.createLineString(sim.trail.map { Coordinate(it.x, it.y) }.toTypedArray())))
            // The adapted route joins the end of the original route, back-off included.
            assertEquals(sim.original.last().b, sim.edges.last().b)
            sim.contacts.forEach { assertNotNull(it.repair?.backOff) }
            // Actual mowing pilot: the robot stays on the line (published deviation below 20 cm, stop beyond 30).
            assertTrue("$shape $size: deviation %.2f m".format(sim.worstDeviationM), sim.worstDeviationM < 0.2)
            // Mid-zone object: obstacle path unchanged, within the detour budget, never a border contact.
            assertTrue(sim.contacts.all { it.edge?.kind == EdgeContactKind.OBJECT })
            assertTrue(sim.detourRefusals.toString(), sim.detourRefusals.isEmpty())
        }
    }

    /** Contact during a transit without mowing: direct link to the destination, no return behind the object. */
    @Test fun contactDuringATransitGoesStraightToItsDestination() {
        val (map, _) = square()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
        val corner = Point(12.5, 11.0); val destination = Point(2.0, 11.0)
        val edges = listOf(SimEdge(Point(1.5, 1.0), Point(12.5, 1.0), true), SimEdge(Point(12.5, 1.0), corner, false), SimEdge(corner, destination, false))
        val obstacle = ProbeObject(ProbeShape.CIRCLE, Point(12.5, 5.0), 0.3)
        val sim = ObstacleEncounterSimulation(routes, routes, edges, obstacle, listOf(map.shapes[0].points))
        sim.finish()
        println("SIM transit: ${sim.phase} · %.1f m · deviation %.2f m · ${sim.log.joinToString(" | ")}".format(sim.meters, sim.worstDeviationM))
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
        assertEquals(1, sim.contacts.size)
        assertTrue(sim.trail.none { it.distance(corner) < 1.5 })
        assertTrue(sim.trail.last().distance(destination) < 0.2)
    }

    @Test fun largeObjectIsTouchedSeveralTimesAndStopsWhenTooLarge() {
        val sim = run(ProbeShape.SQUARE, 3.0)
        println("SIM large square: ${sim.phase} · ${sim.contacts.size} contact(s) · ${sim.message}")
        assertTrue(sim.contacts.size >= 2)
        if (sim.phase == EncounterPhase.STOPPED) assertNotNull(sim.contacts.last().failure)
        else assertEquals(EncounterPhase.DONE, sim.phase)
    }

    @Test fun objectAwayFromTheRouteIsNeverTouched() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
        val obstacle = ProbeObject(ProbeShape.CIRCLE, Point(13.6, 11.6), 0.2)
        val sim = ObstacleEncounterSimulation(routes, routes, edges.take(6), obstacle, listOf(map.shapes[0].points))
        sim.finish()
        assertEquals(EncounterPhase.DONE, sim.phase)
        assertTrue(sim.contacts.isEmpty())
    }

    /**
     * Pivot slide towards the border (2026-10-19 at 14:50: 24 cm slide during a pivot at the end of a pass, then
     * "obstacle" and bypass): wall along the north border, the robot slides 20 cm towards it while pivoting at the end
     * of a pass and its front corner hits it. Border contact: back-off, link to the same route, no obstacle, no detour.
     */
    @Test fun driftingRobotBumpingTheBorderBacksOffAndResumesWithoutDetour() {
        val (map, _) = square()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
        val wall = ProbeObject(ProbeShape.SQUARE, Point(7.0, 13.5), 3.0)
        val edges = listOf(SimEdge(Point(7.0, 6.0), Point(7.0, 11.4), true), SimEdge(Point(7.0, 11.4), Point(7.6, 11.4), false),
            SimEdge(Point(7.6, 11.4), Point(7.6, 6.0), true))
        // Slope towards the border during the first pivot only (the robot slides about 20 cm; once it has backed off, the ground holds).
        var top = 0.0
        val slope: (Point) -> Point = { p -> top = maxOf(top, p.y); if (top >= 11.62 || p.y < top - 0.1) Point(0.0, 0.0) else Point(0.0, 0.12) }
        val sim = ObstacleEncounterSimulation(routes, routes, edges, wall, listOf(map.shapes[0].points),
            dynamics = SimulatedRobot.Params(pivotSlide = slope))
        sim.finish()
        println("SIM border contact: ${sim.phase} · ${sim.contacts.size} contact(s) · deviation %.2f m · ${sim.log.joinToString(" | ")}".format(sim.worstDeviationM))
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
        assertEquals(1, sim.contacts.size)
        val contact = sim.contacts.single()
        assertEquals(contact.edge.toString(), EdgeContactKind.LIMIT, contact.edge?.kind)
        assertEquals(1, sim.edgeContacts)
        assertTrue("no obstacle", sim.discovered.isEmpty())
        assertNull("no repair, no detour", contact.repair)
        val resume = requireNotNull(contact.resume)
        assertTrue("back-off ≥ 30 cm", requireNotNull(resume.backOff).distance(contact.pose.position) >= 0.3 - 1e-6)
        assertTrue("skipped %.2f m".format(resume.skippedCuttingMeters), resume.skippedCuttingMeters <= 1.0)
        assertTrue("link %.2f m".format(resume.rejoinMeters), resume.rejoinMeters <= 2.0)
        assertEquals(edges.last().b, sim.edges.last().b)
        // The slide itself is the deviation (20 cm); the 30 cm route deviation stop is never reached.
        assertTrue("deviation %.2f m".format(sim.worstDeviationM), sim.worstDeviationM < 0.3)
        // Same pass without the slide: no contact.
        val calm = ObstacleEncounterSimulation(routes, routes, edges, wall, listOf(map.shapes[0].points))
        calm.finish()
        assertTrue(calm.contacts.isEmpty())
    }

    @Test(timeout = 600000) fun referenceZoneRouteAdaptsAroundAPlacedObject() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == "Enfer" }
        val route = ObstacleEncounterSimulation.route(map, zone, base, emptyList(), emptyList())
        val third = route.edges.size / 3
        val pass = (third until 2 * third).filter { route.edges[it].cutting }.maxBy { route.edges[it].meters }
        val e = route.edges[pass]
        val obstacle = ProbeObject(ProbeShape.TRIANGLE, Point((e.a.x + e.b.x) / 2, (e.a.y + e.b.y) / 2), 0.6)
        val sim = ObstacleEncounterSimulation(route.engine.routes, route.physical, route.edges, obstacle, route.zones,
            ObstacleEncounterSimulation.startNear(route.edges, obstacle))
        sim.finish()
        println("SIM Enfer : ${sim.phase} · ${sim.contacts.size} contact(s) · ${sim.log.joinToString(" | ")}")
        assertTrue(sim.contacts.isNotEmpty())
        assertEquals(sim.message, EncounterPhase.DONE, sim.phase)
        assertEquals(route.engine.baseApproach, sim.edges.last().b)
    }
}
