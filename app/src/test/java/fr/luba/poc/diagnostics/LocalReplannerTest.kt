package fr.luba.poc.diagnostics

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.*
import org.junit.Assert.*
import org.junit.Test

class LocalReplannerTest {
    private val zone = listOf(Point(0.0,0.0),Point(20.0,0.0),Point(20.0,20.0),Point(0.0,20.0))
    private val start = Point(5.0,5.0)
    private val path = listOf(start,Point(6.5,5.0),Point(8.5,5.0),Point(12.0,5.0))
    private val plan = MapNavPlan(zone,emptyList(),path,emptyList(),emptyList(),List(4) { "Bande" })
    private val obstacle = LocalObstacle("o",ObstacleKind.POLE,6.7,5.0,0.1,0.2)

    @Test fun `bypasses locally then keeps the end of the mission`() {
        val repaired = LocalReplanner.repair(plan,start,1,listOf(obstacle))
        assertEquals(start,repaired.points.first())
        assertEquals(path.last(),repaired.points.last())
        assertEquals(repaired.points.size,repaired.labels.size)
        assertTrue(repaired.points.contains(path[2]))
        assertTrue(repaired.points.dropLast(1).all { it.distance(start) <= LocalReplanner.WINDOW_M })
        assertTrue(repaired.points.zipWithNext().all { (a,b) ->
            Geometry.distance(Point(obstacle.x,obstacle.y),a,b) >= obstacle.exclusionRadiusM + MapNavPlanner.MARGIN_M })
        // The corridor detector must not trigger again on its own bypass.
        assertTrue(repaired.points.zipWithNext().all { (a,b) ->
            Geometry.distance(Point(obstacle.x,obstacle.y),a,b) > obstacle.exclusionRadiusM +
                RobotEnvelope.LUBA2.turnClearanceM + MapNavFollower.MAX_DEVIATION_M })
        assertTrue(repaired.points.any { kotlin.math.abs(it.y - 5.0) > 1.0 })
    }
    @Test fun `contact without room to turn stays stopped`() {
        assertThrows(Exception::class.java) { LocalReplanner.repair(plan,Point(6.2,5.0),1,listOf(obstacle)) }
    }
    @Test fun `barrier without local bypass refuses the resume`() {
        val wall = listOf(Point(6.0,0.0),Point(7.0,0.0),Point(7.0,20.0),Point(6.0,20.0))
        assertThrows(Exception::class.java) { LocalReplanner.repair(plan.copy(holes=listOf(wall)),start,1,emptyList()) }
    }
    @Test fun `border forbids the robot volume`() {
        assertThrows(Exception::class.java) { LocalReplanner.repair(plan,Point(0.5,5.0),1,listOf(obstacle)) }
    }
    @Test fun `history feeds the heatmap and a suggestion after three incidents`() {
        val repeated = obstacle.copy(incidents = List(3) { ObstacleIncident(it.toLong(),"bumper",x=6.2,y=5.0) })
        val hotspot = listOf(repeated).incidentHeatmap().single()
        assertEquals(3,hotspot.count)
        assertTrue(hotspot.correctionSuggested)
        val repaired = LocalReplanner.repair(plan,start,1,listOf(repeated))
        assertTrue(repaired.points.zipWithNext().all { (a,b) ->
            Geometry.distance(Point(obstacle.x,obstacle.y),a,b) >= obstacle.exclusionRadiusM + MapNavPlanner.MARGIN_M + 0.3 })
    }
}
