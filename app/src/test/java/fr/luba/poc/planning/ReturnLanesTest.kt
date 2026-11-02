package fr.luba.poc.planning

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.ReturnLaneChoice
import fr.luba.poc.simulation.ReturnLaneHistory
import fr.luba.poc.simulation.ReturnLanes
import fr.luba.poc.simulation.RobotRoute
import fr.luba.poc.simulation.SimEdge
import fr.luba.poc.simulation.SmartFeatureFlags
import fr.luba.poc.simulation.SmartFeatures
import fr.luba.poc.simulation.TrajectoryPlanner
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Returns to the base alternated between approach lanes ([ReturnLanes]) so that no single track wears a rut. */
class ReturnLanesTest {
    @After fun switchesBack() = SmartFeatures.set(SmartFeatureFlags())
    private val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
    /** 20 × 14 m lawn, base on its west edge facing east, approach point 1.5 m in front of it. */
    private val base = Point(0.6, 7.0)
    private val approach = Point(2.1, 7.0)
    private val axis = 0.0
    private fun routes(): TrajectoryPlanner = TrajectoryPlanner.withDock(
        RobotMap(listOf(MapShape(1, 0, rectangle(w = 20.0, h = 14.0), "Pelouse")), true, "pelouse"),
        margin, emptyList(), emptyList(), base, 180.0, 1.5)

    private fun returning(routes: TrajectoryPlanner, from: Point, history: ReturnLaneHistory?) =
        ReturnLanes.returning(routes, from, 90.0, base, approach, axis, history) { s, h, t, th -> RobotRoute.departure(routes, null, s, h, t, th) }

    @Test fun withoutHistoryTheReturnIsTheUsualDirectOne() {
        val routes = routes()
        val from = Point(16.0, 3.0)
        val choice = returning(routes, from, null)
        assertEquals(0, choice.lane)
        assertEquals(RobotRoute.departure(routes, null, from, 90.0, approach, axis), choice.edges)
    }

    @Test fun successiveReturnsSpreadOverSeveralLanes() {
        val routes = routes()
        val history = ReturnLaneHistory.InMemory()
        val from = Point(16.0, 3.0)
        val choices = (1..5).map { returning(routes, from, history) }
        println("RETURN lanes: ${choices.map { "${it.lane} +%.2f m".format(Locale.ROOT, it.extraM) }}")
        assertTrue("several lanes used: ${choices.map { it.lane }}", choices.map { it.lane }.distinct().size >= 3)
        assertEquals("never the same lane twice in a row", 0, choices.zipWithNext().count { (a, b) -> a.lane == b.lane })
        choices.forEach { choice ->
            val points = listOf(choice.edges.first().a) + choice.edges.map { it.b }
            routes.requirePath(points, 90.0, axis)
            assertEquals(approach, points.last())
            assertTrue("within the extra length allowed: ${choice.extraM}", choice.extraM <= ReturnLanes.MAX_EXTRA_M + 1e-9)
            if (choice.lane > 0) assertTrue("through its via point", points.any { it.distance(ReturnLanes.via(base, approach, choice.lane)) < 1e-6 })
        }
        assertEquals((choices.map { it.lane }).takeLast(ReturnLanes.KEEP), history.recent(ReturnLanes.key(base, approach)))
    }

    @Test fun switchedOffEveryReturnIsDirect() {
        SmartFeatures.set(SmartFeatureFlags(returnLanes = false))
        val routes = routes()
        val history = ReturnLaneHistory.InMemory()
        val choices = (1..3).map { returning(routes, Point(16.0, 3.0), history) }
        assertEquals(listOf(0, 0, 0), choices.map { it.lane })
        assertTrue("nothing recorded", history.recent(ReturnLanes.key(base, approach)).isEmpty())
    }

    @Test fun laneThroughAnIncidentAreaOrTooLongIsAvoided() {
        val edge = listOf(SimEdge(Point(0.0, 0.0), Point(10.0, 0.0), false))
        val direct = ReturnLaneChoice(0, edge, 0.0, 0.0)
        val worn = listOf(0, 0)
        val troubled = ReturnLaneChoice(1, edge, 1.0, incidentM = 6.0)
        val clean = ReturnLaneChoice(2, edge, 2.0, 0.0)
        val far = ReturnLaneChoice(3, edge, ReturnLanes.MAX_EXTRA_M + 0.5, 0.0)
        assertEquals(2, ReturnLanes.choose(listOf(direct, troubled, clean, far), worn, directM = 10.0).lane)
        assertEquals("a long return allows 15 % more", 3, ReturnLanes.choose(listOf(direct, far), worn, directM = 40.0).lane)
        assertEquals("no history: the shortest", 0, ReturnLanes.choose(listOf(direct, clean), emptyList(), directM = 10.0).lane)
    }
}
