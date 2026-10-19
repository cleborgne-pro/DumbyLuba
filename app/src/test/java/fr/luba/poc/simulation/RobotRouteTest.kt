package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class RobotRouteTest {
    private fun engine(): MowingSimulation {
        val map = RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0)))),true)
        val preview = Geometry.plan(map.shapes.first().points,emptyList(),borders=true)
        return MowingSimulation(TrajectoryPlanner(map,0.6,emptyList()),Point(2.0,2.0),preview,map,SimConfig())
    }
    @Test fun explicitBaseReturnUsesOnlySharedTransitAndNoCuttingEdges() {
        val e=engine(); val start=Point(3.0,3.0)
        val route=RobotRoute.actionEdges(e,start,0.0,RouteIntent.RETURN_TO_BASE)
        assertEquals(start,route.first().a)
        assertEquals(e.baseApproach,route.last().b)
        assertTrue(route.none { it.cutting })
        e.routes.requirePath(listOf(start)+route.map { it.b },0.0,(e.dockEntryHeading+180)%360)
        assertTrue(RobotRoute.actionEdges(e,e.base,0.0,RouteIntent.LEAVE_BASE).isEmpty())
    }
    @Test fun returnDrivesLocallyToTheApproachPointInFrontOfTheBase() {
        val e=engine(); val start=Point(3.0,3.0)
        val route=RobotRoute.actionEdges(e,start,0.0,RouteIntent.RETURN_TO_BASE)
        assertEquals(e.baseApproach,route.last().b)
        assertTrue(route.none { it.cutting })
        assertTrue(RouteIntent.RETURN_TO_BASE.returnsToBase)
        assertFalse(RouteIntent.FOLLOW.returnsToBase || RouteIntent.LEAVE_BASE.returnsToBase)
    }
    @Test fun preservesAllCuttingEdgesAndReturnsOnlyToApproach() {
        val e=engine(); val start=Point(3.0,3.0)
        val route=RobotRoute.edges(e,start,0.0)
        assertEquals(start,route.first().a)
        assertEquals(e.baseApproach,route.last().b)
        assertEquals(e.edges.filter { it.cutting },route.filter { it.cutting })
        val first=route.indexOf(e.edges.first())
        assertEquals(e.edges,route.subList(first,first+e.edges.size))
        assertFalse(route.first().cutting)
        assertFalse(route.last().cutting)
        route.zipWithNext().forEach { (a,b) -> assertEquals(a.b,b.a) }
        e.routes.requirePath(listOf(start)+route.map { it.b },0.0)
    }
    @Test fun refusesStartOutsideMappedSurface() {
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.edges(engine(),Point(-3.0,-3.0),0.0) }
    }
    @Test fun bladesOffRequiresTelemetryAfterMatchingWrite() {
        val gate=RouteBladeGate("OFF_SEGMENT_2",false)
        assertFalse(gate.confirmed(100,0))
        gate.written("OFF_SEGMENT_1",100)
        assertFalse(gate.confirmed(101,0))
        gate.written("OFF_SEGMENT_2",200)
        assertFalse(gate.confirmed(199,0))
        assertFalse(gate.confirmed(200,0))
        assertFalse(gate.confirmed(201,1))
        assertTrue(gate.confirmed(201,0))
    }
    @Test fun bladesOnRequiresFreshOnRatherThanHealthyOff() {
        val gate=RouteBladeGate("ON",true)
        gate.written("ON",100)
        assertFalse(gate.confirmed(101,0))
        assertFalse(gate.confirmed(101,2))
        assertFalse(gate.confirmed(99,1))
        assertTrue(gate.confirmed(101,1))
    }
    @Test fun refusesNonFiniteHeading() {
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.edges(engine(),Point(3.0,3.0),Double.NaN) }
    }
    @Test fun oldTransitCompletionCannotConfirmNewGateAfterRecharge() {
        val old=RouteBladeGate.unique("OFF_TRANSIT",false)
        val next=RouteBladeGate.unique("OFF_TRANSIT",false)
        assertNotEquals(old.label,next.label)
        next.written(old.label,100)
        assertFalse(next.confirmed(101,0))
        next.written(next.label,200)
        assertFalse(next.confirmed(200,0))
        assertTrue(next.confirmed(201,0))
    }
    @Test fun supervisionIsVisibleForRealStartAndRefusalWithoutSimulation() {
        assertFalse(RobotRouteUi().showSupervision)
        assertTrue(RobotRouteUi(running=true,started=true).showSupervision)
        assertTrue(RobotRouteUi(status="Départ refusé : BLE non prêt").showSupervision)
        assertTrue(RobotRouteUi(started=true,phase=RoutePhase.COMPLETE).showSupervision)
    }
    @Test fun activeSettingsRemainAttachedToMissionThroughRecharge() {
        val mission=RobotRouteUi(started=true,running=true,heightMm=45,returnPercent=25,resumePercent=85)
        val charging=mission.copy(phase=RoutePhase.CHARGING,rechargeCycles=1)
        assertEquals(45,charging.heightMm)
        assertEquals(25,charging.returnPercent)
        assertEquals(85,charging.resumePercent)
    }
    @Test fun playbackCannotStartWithoutScenarioOrRestartFinishedScenario() {
        assertFalse(toggleSimulation(SimulationUi()).playing)
        val e=engine();val s=e.start()
        val ui=SimulationUi(e,s,false)
        assertTrue(toggleSimulation(ui).playing)
        assertFalse(toggleSimulation(toggleSimulation(ui)).playing)
        assertFalse(toggleSimulation(ui.copy(state=s.copy(phase=SimPhase.COMPLETE))).playing)
        assertFalse(toggleSimulation(ui.copy(state=s.copy(phase=SimPhase.BLOCKED))).playing)
    }
}

class PassHeightRangeTest {
    private fun e(x: Double, height: Int?, cutting: Boolean = true) = SimEdge(fr.luba.poc.map.Point(x, 0.0), fr.luba.poc.map.Point(x + 1, 0.0), cutting, heightMm = height)

    @Test fun routeIsSplitAtEachPassHeight() {
        // Start without height, pass at 70, bypass link without height, pass at 55, return without height.
        val path = listOf(e(0.0, null, false), e(1.0, 70), e(2.0, 70), e(3.0, null, false), e(4.0, 55), e(5.0, 55), e(6.0, null, false))
        assertEquals(1 to 60, RobotRoute.heightRange(path, 0, 60, true))
        assertEquals(4 to 70, RobotRoute.heightRange(path, 1, 60, true))
        assertEquals(7 to 55, RobotRoute.heightRange(path, 4, 70, true))
        // Bypass after a resume in the second pass: current height kept, up to the end of the pass.
        assertEquals(7 to 55, RobotRoute.heightRange(path, 3, 55, true))
        // Bypass at the end of the first pass: it keeps 70 mm, the next pass starts at edge 4.
        assertEquals(4 to 70, RobotRoute.heightRange(path, 3, 70, true))
        // Route with blades stopped: a single range, height unchanged.
        assertEquals(7 to 60, RobotRoute.heightRange(path, 0, 60, false))
        // A single pass: no edge has a height, a single range.
        assertEquals(7 to 45, RobotRoute.heightRange(path.map { it.copy(heightMm = null) }, 0, 45, true))
    }
}
