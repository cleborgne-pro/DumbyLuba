package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RouteRechargeTest {
    private fun engine(): MowingSimulation {
        val map=RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0)))),true)
        return MowingSimulation(TrajectoryPlanner(map,0.6,emptyList()),Point(2.0,2.0),
            Geometry.plan(map.shapes.first().points,emptyList(),borders=true),map,
            SimConfig(baseEntryHeadingDegrees=180.0))
    }
    @Test fun thresholdsIncludeEqualityAndDoNotUseSimulationBattery() {
        val r=RouteRecharge(20,90)
        assertFalse(r.returnRequired(21))
        assertTrue(r.returnRequired(20))
        assertTrue(r.returnRequired(19))
        assertFalse(r.resumeAllowed(89,1,100))
        assertTrue(r.resumeAllowed(90,1,100))
        assertTrue(r.resumeAllowed(100,2,100))
        assertFalse(r.resumeAllowed(100,0,100))
        assertFalse(r.resumeAllowed(100,null,100))
        assertFalse(r.resumeAllowed(100,1,2001))
        assertFalse(r.resumeAllowed(100,1,-1))
    }
    @Test fun dockedStartLeavesAtOnceWhenTheBatteryIsWellAboveTheReturnThreshold() {
        val r=RouteRecharge(20,90)
        assertTrue(r.departureAllowed(84))   // test of 2026-10-13: border loop at 84 %, wrongly waiting for 90 %
        assertTrue(r.departureAllowed(30))
        assertFalse(r.departureAllowed(29))
        assertFalse(r.departureAllowed(null))
        // Tightened thresholds: never beyond the resume threshold.
        assertTrue(RouteRecharge(20,25).departureAllowed(25))
        assertFalse(RouteRecharge(20,25).departureAllowed(24))
    }
    @Test fun invalidThresholdsAreRefusedBeforeRobotCommands() {
        listOf(5 to 90,20 to 20,20 to 24,81 to 100,20 to 101).forEach { (low,high) ->
            assertThrows(IllegalArgumentException::class.java) { RouteRecharge(low,high) }
        }
    }
    @Test fun returnsAndResumesExactlyAtInterruptedPositionWithBladesOff() {
        val e=engine()
        val edge=e.edges.first { it.cutting }
        val saved=Point((edge.a.x+edge.b.x)/2,(edge.a.y+edge.b.y)/2)
        val heading=Math.toDegrees(atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
        val paths=RobotRoute.rechargePaths(e,saved,heading)
        assertEquals(saved,paths.returning.first().a)
        assertEquals(e.baseApproach,paths.returning.last().b)
        assertEquals(e.baseApproach,paths.resuming.first().a)
        assertEquals(saved,paths.resuming.last().b)
        assertTrue((paths.returning+paths.resuming).none { it.cutting })
        listOf(paths.returning,paths.resuming).forEach { path ->
            path.zipWithNext().forEach { (a,b) -> assertEquals(a.b,b.a) }
            e.routes.requirePath(listOf(path.first().a)+path.map { it.b })
        }
    }
    @Test fun dockingMovesBackwardOnlyAlongStationAxisAndStopsAtBase() {
        val e=engine()
        val heading=(e.dockEntryHeading+180)%360
        assertEquals(-10 to 0,RouteRecharge.reverseCommand(e,e.baseApproach,heading))
        assertEquals(0 to 0,RouteRecharge.reverseCommand(e,e.base,heading))
        assertThrows(IllegalArgumentException::class.java) { RouteRecharge.reverseCommand(e,e.baseApproach,heading+10) }
        val lateral=Point(e.baseApproach.x,e.baseApproach.y+0.11)
        assertThrows(IllegalArgumentException::class.java) { RouteRecharge.reverseCommand(e,lateral,heading) }
        val overshoot=Point(e.base.x-0.2,e.base.y)
        assertThrows(IllegalArgumentException::class.java) { RouteRecharge.reverseCommand(e,overshoot,heading) }
    }
    @Test fun exitMovesSlowlyForwardInAStraightLineUntilTheApproachPoint() {
        val e=engine()
        val heading=(e.dockEntryHeading+180)%360
        assertEquals(RouteRecharge.EXIT_LINEAR to 0,RouteRecharge.exitCommand(e,e.base,heading))
        assertTrue(RouteRecharge.EXIT_LINEAR*DeadReckoning.DEFAULT_METERS_PER_UNIT<=0.12)
        val middle=Point((e.base.x+e.baseApproach.x)/2,(e.base.y+e.baseApproach.y)/2)
        assertEquals(RouteRecharge.EXIT_LINEAR to 0,RouteRecharge.exitCommand(e,middle,heading+8))
        assertEquals(0 to 0,RouteRecharge.exitCommand(e,e.baseApproach,heading))
        // No pivot on the base: off-axis heading refused, like a lateral drift.
        assertThrows(IllegalArgumentException::class.java) { RouteRecharge.exitCommand(e,e.base,heading+15) }
        val rad=Math.toRadians(heading+90)
        val drift=Point(middle.x+0.11*kotlin.math.cos(rad),middle.y+0.11*kotlin.math.sin(rad))
        assertThrows(IllegalArgumentException::class.java) { RouteRecharge.exitCommand(e,drift,heading) }
    }
    @Test fun stationApproachDoesNotStopAtOrdinaryWaypointTolerance() {
        val p=Point(0.0,0.0); val target=Point(0.10,0.0)
        assertEquals(0 to 0,fr.luba.poc.diagnostics.MapNavFollower.command(p,0.0,target,30,20))
        val (approach,turn)=fr.luba.poc.diagnostics.MapNavFollower.command(p,0.0,target,30,20,0.05)
        assertTrue(approach in 1..30); assertEquals(0,turn)
        assertEquals(0 to 0,fr.luba.poc.diagnostics.MapNavFollower.command(Point(0.06,0.0),0.0,target,30,20,0.05))
    }
    @Test fun rechargeAtWaypointContinuesWithNextSegmentWithoutRestartingFinishedCut() {
        val edges=listOf(SimEdge(Point(0.0,0.0),Point(2.0,0.0),true),SimEdge(Point(2.0,0.0),Point(4.0,0.0),true))
        val approach=Point(4.0,0.0)
        assertEquals(0,RobotRoute.resumeIndex(edges,0,Point(1.0,0.0),approach))
        assertEquals(1,RobotRoute.resumeIndex(edges,0,Point(2.0,0.0),approach))
        assertEquals(1,RobotRoute.resumeIndex(edges,1,Point(3.90,0.0),approach))
        assertEquals(2,RobotRoute.resumeIndex(edges,1,Point(4.0,0.0),approach))
    }
    @Test fun headingWrapDoesNotRequestFullTurn() {
        assertEquals(2.0,RouteRecharge.headingError(1.0,359.0),0.001)
        assertEquals(-2.0,RouteRecharge.headingError(359.0,1.0),0.001)
    }
}
