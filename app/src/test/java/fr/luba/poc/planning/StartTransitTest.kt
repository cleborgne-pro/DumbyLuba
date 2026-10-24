package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Start point chosen by the planner (nearest safe entry of the first loop or pass, [LoopEntries]) against the fixed
 * anchor of the plan (its first vertex, the former start): on every reference zone, the robot standing at the far
 * corner of the zone reaches the chosen start with a shorter transit.
 */
class StartTransitTest {
    @Test fun chosenStartShortensTheInitialTransitOnTheReferenceZones() {
        var chosenTotal = 0.0; var fixedTotal = 0.0
        val report = mutableListOf<String>()
        for ((name, reference) in PlannerBenchmarkTest().referenceCases()) {
            val zone = reference.map.shapes.single { it.id == reference.zoneIds.single() }
            val anchor = reference.start
            val routes = PlanningTerrain(reference).routes
            // Robot near the zone vertices farthest from the fixed anchor (first one with a valid plan).
            val (start, result) = requireNotNull(zone.points.sortedByDescending { it.distance(anchor) }.asSequence()
                .filterIndexed { i, _ -> i % 4 == 0 }.mapNotNull { RoutePlanning.nearestUsable(routes, it) }.take(12)
                .map { it to RoutePlanning.calculate(reference.copy(start = it)) }
                .firstOrNull { it.second.selected.valid }) { "$name: no valid plan from the far corners" }
            assertTrue("$name: start ${start.distance(anchor)} m from the anchor", start.distance(anchor) > 5.0)
            val first = result.selected.mission.first()
            val chosen = if (first.cutting) 0.0 else length(first.path)
            val fixed = length(routes.between(start, anchor))
            report += "$name: chosen ${"%.1f".format(chosen)} m, fixed anchor ${"%.1f".format(fixed)} m"
            assertTrue("$name: transit to the chosen start $chosen m > fixed anchor $fixed m", chosen <= fixed + 1e-6)
            chosenTotal += chosen; fixedTotal += fixed
        }
        println(report.joinToString("\n"))
        assertTrue("total ${"%.1f".format(chosenTotal)} m against ${"%.1f".format(fixedTotal)} m", chosenTotal < 0.5 * fixedTotal)
    }

    @Test fun startArrowFollowsTheFirstEdgeOfTheFirstPass() {
        val pass = listOf(Point(2.0, 3.0), Point(2.0, 3.0), Point(2.0, 7.0), Point(5.0, 7.0))
        assertEquals(Point(2.0, 4.0), routeStartAhead(pass))
        assertEquals(Point(2.0, 3.5), routeStartAhead(pass, 0.5))
        assertNull(routeStartAhead(listOf(Point(1.0, 1.0))))
        assertNull(routeStartAhead(null))
    }
}
