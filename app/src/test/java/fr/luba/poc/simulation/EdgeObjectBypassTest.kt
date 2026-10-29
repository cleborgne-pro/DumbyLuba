package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * B0 replay of 2026-10-28, 09:52:08 (Zamboni zone, passes at 59°, 0.35 m spacing): bumper contact against a wood pile
 * at the outer border of a 2 m wide corridor, bumper point 0.41 m inside the border, deviation 4 cm. In the field the
 * repair took 79.2 m of links (6 links) for 14.1 m of cutting skipped.
 */
class EdgeObjectBypassTest {
    private val robot = RobotEnvelope.LUBA2
    // Two lawns (west, east) joined by a 2 m wide corridor along the north border (y 6..8) and, the long way round, by
    // 2 m strips around a house drawn as a no-go zone.
    private val zone = MapShape(1, 0, listOf(Point(0.0, -10.0), Point(22.0, -10.0), Point(22.0, 8.0), Point(0.0, 8.0)), "Zamboni")
    private val house = MapShape(2, 1, listOf(Point(2.0, -8.0), Point(20.0, -8.0), Point(20.0, 0.0), Point(14.0, 0.0), Point(14.0, 6.0),
        Point(8.0, 6.0), Point(8.0, 0.0), Point(2.0, 0.0)), "House")
    private val map = RobotMap(listOf(zone, house), true, "corridor")
    private val base = MapBase(Point(0.6, 4.0), "test", 1.5, 180.0, 1.5)

    private fun heading(e: SimEdge) = Math.toDegrees(atan2(e.b.y - e.a.y, e.b.x - e.a.x))

    private class Scenario(val routes: TrajectoryPlanner, val edges: List<SimEdge>, val index: Int, val position: Point, val heading: Double, val trail: List<Point>)

    private fun scenario(angle: Int = 59): Scenario {
        val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.9, boundary = BoundaryMeaning.RECORDED_TRACE,
                orientation = OrientationMode.MANUAL, manualAngleDeg = angle),
            navigation = PlanningNavigation(emptyList(), base)), CoverageMode.ZAMBONI)
        assertFalse(plan.preview.localOnly)
        val (edges, _) = ReferenceRoutes.routeEdges(map, base, zone, plan)
        val routes = RoutePlanning.mission(map, emptyList(), emptyList(), base)
        val border = EdgeContact.Border(listOf(zone.points))
        // Cutting pass crossing the corridor towards its outer (north) border, in its middle.
        if (angle == 0) {
            // Passes along the corridor: the one nearest to its north border, at x = 11.
            val index = edges.indices.filter { i -> val e = edges[i]
                e.cutting && kotlin.math.abs(e.a.y - e.b.y) < 0.01 && e.a.y in 6.0..8.0 && minOf(e.a.x, e.b.x) < 10.5 && maxOf(e.a.x, e.b.x) > 11.5 }.maxBy { edges[it].a.y }
            val e = edges[index]
            val position = Point(11.0, e.a.y)
            return Scenario(routes, edges, index, position, heading(e), listOf(e.a, position))
        }
        val index = edges.indices.filter { i -> val e = edges[i]
            e.cutting && e.meters > 1.0 && e.b.x in 9.0..13.0 && e.b.y > e.a.y && e.b.y > 7.0 }.minBy { kotlin.math.abs(edges[it].b.x - 11.0) }
        val e = edges[index]
        val h = heading(e)
        // Robot on the pass where the bumper point is 0.41 m inside the border.
        var t = 0.0
        while (t < 1.0 && border.signedDistance(EdgeContact.bumper(Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t), h)) > 0.41) t += 0.002
        val position = Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t)
        val trail = listOf(e.a, position)
        return Scenario(routes, edges, index, position, h, trail)
    }

    private fun describe(label: String, repair: RouteRepair) = "$label: links %.1f m (%d), skipped %.1f m, detours %s".format(
        repair.bypassMeters, repair.bypasses, repair.skippedCuttingMeters,
        repair.detours.joinToString { "%.1f m side %d clearance %.2f".format(it.meters, it.side, it.borderClearanceM) })

    private fun repair(s: Scenario, margin: Double): RouteRepair {
        val obstacle = ObstacleBypass.contactObstacle("wood", s.position, s.heading, 0L, marginM = margin)
        return ObstacleBypass.repair(s.routes, s.routes, s.edges, null, s.index, s.position, s.heading, listOf(obstacle),
            listOf(zone.points), contact = true, trail = s.trail)
    }

    /**
     * Passes along the corridor (the 28/10 order of magnitude): with the former 0.30 m margin the estimated disc closes
     * the corridor for the blade-OFF link (50 cm border rule) and the repair goes the long way round the house
     * (66 m of links for 9 m skipped, 28/10: 79.2 m for 14.1 m); the detour budget refuses it. Object at the edge, margin
     * 0.20 m: the link passes on the inner side, within the budget.
     */
    @Test(timeout = 600000) fun corridorPassesAlongTheBorderStayWithinTheDetourBudget() {
        val s = scenario(0)
        val border = EdgeContact.Border(listOf(zone.points))
        val c = EdgeContact.classify(true, 0L, 1, s.position, s.heading, border, s.edges, s.index, s.trail)
        println("B0 along: ${c.kind} ${c.cause} bumper %.2f m inside, drift %.2f m".format(c.borderDistM, c.driftM))
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, c.kind)
        val before = repair(s, ObstacleBypass.CONTACT_MARGIN_M)
        println("B0 along " + describe("margin 0.30", before))
        assertTrue("former margin: long way round", before.bypassMeters > 40.0)
        assertNotNull(ObstacleBypass.detourRefusal(before))
        val after = repair(s, EdgeContact.marginM(c.kind))
        println("B0 along " + describe("margin 0.20", after))
        assertNull(ObstacleBypass.detourRefusal(after))
        assertTrue(after.bypassMeters <= 25.0)
        assertTrue("arcs around the object ${after.detours.size}", after.detours.size <= 2)
        assertTrue(after.skippedCuttingMeters <= 14.1)
        val run = RouteExecutionModel.drive(after.edges, RouteExecutionModel.Robot(), 30)
        println("B0 along execution: $run")
        assertTrue(run.toString(), run.worst <= 0.27 && run.blocked == 0)
    }

    /** Passes crossing the corridor at 59°: the repair stays local (two short links), nothing to refuse. */
    @Test(timeout = 600000) fun corridorCrossingPassesStayLocal() {
        val s = scenario(59)
        val border = EdgeContact.Border(listOf(zone.points))
        val c = EdgeContact.classify(true, 0L, 1, s.position, s.heading, border, s.edges, s.index, s.trail)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, c.kind)
        assertEquals(0.41, requireNotNull(c.borderDistM), 0.01)
        for (margin in listOf(ObstacleBypass.CONTACT_MARGIN_M, ObstacleBypass.CONTACT_EDGE_MARGIN_M)) {
            val r = repair(s, margin)
            println("B0 crossing " + describe("margin %.2f".format(margin), r))
            assertTrue(r.bypassMeters <= 2.0)
            assertNull(ObstacleBypass.detourRefusal(r))
        }
    }

    /**
     * B4, 2026-10-28 at 09:53:53: hedge met on a blade-OFF link kept 0.5 m from the border. Object at the edge; the
     * estimated disc closes the corridor whatever the margin, so the transit goes the long way round. The detour budget
     * is not applied to a transit contact (deferring cannot shorten the way to its destination): the repair is kept.
     */
    @Test(timeout = 600000) fun transitContactAtTheBorderIsAnObjectAtTheEdge() {
        val routes = RoutePlanning.mission(map, emptyList(), emptyList(), base)
        val border = EdgeContact.Border(listOf(zone.points))
        val edges = listOf(SimEdge(Point(1.0, 3.0), Point(6.0, 3.0), true), SimEdge(Point(6.0, 3.0), Point(6.0, 7.5), false),
            SimEdge(Point(6.0, 7.5), Point(16.0, 7.5), false), SimEdge(Point(16.0, 7.5), Point(16.0, 3.0), false), SimEdge(Point(16.0, 3.0), Point(21.0, 3.0), true))
        val position = Point(11.0, 7.5)
        val c = EdgeContact.classify(true, 0L, 1, position, 0.0, border, edges, 2, listOf(Point(6.0, 7.5), position))
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, c.kind)
        assertFalse(edges[2].cutting)
        for (margin in listOf(ObstacleBypass.CONTACT_MARGIN_M, ObstacleBypass.CONTACT_EDGE_MARGIN_M)) {
            val obstacle = ObstacleBypass.contactObstacle("hedge", position, 0.0, 0L, marginM = margin)
            val r = ObstacleBypass.repair(routes, routes, edges, null, 2, position, 0.0, listOf(obstacle), listOf(zone.points), contact = true,
                trail = listOf(Point(6.0, 7.5), position))
            println("B4 transit " + describe("margin %.2f".format(margin), r))
            assertEquals(edges.last().b, r.edges.last().b)
        }
    }

    @Test(timeout = 600000) fun detourBudgetBoundaries() {
        assertEquals(15.0, ObstacleBypass.detourBudgetM(0.0), 1e-9)
        assertEquals(15.0, ObstacleBypass.detourBudgetM(5.0), 1e-9)
        assertEquals(42.3, ObstacleBypass.detourBudgetM(14.1), 1e-9)
        val base = repair(scenario(59), ObstacleBypass.CONTACT_MARGIN_M)
        assertNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 15.0, skippedCuttingMeters = 0.0)))
        assertNotNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 15.1, skippedCuttingMeters = 0.0)))
        assertNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 30.0, skippedCuttingMeters = 10.0)))
        assertNotNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 30.1, skippedCuttingMeters = 10.0)))
        // 28/10: both field repairs would have been refused.
        assertNotNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 79.2, skippedCuttingMeters = 14.1)))
        assertNotNull(ObstacleBypass.detourRefusal(base.copy(bypassMeters = 73.6, skippedCuttingMeters = 14.0)))
    }
}
