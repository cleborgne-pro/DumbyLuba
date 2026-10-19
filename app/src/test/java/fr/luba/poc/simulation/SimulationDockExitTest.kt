package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test

/** The simulation leaves the base like the robot ([DockExit]): slow straight line up to the link point, without pivot. */
class SimulationDockExitTest {
    private val map=Geometry.demo()
    private val holes=map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
    private fun engine(config:SimConfig)=MowingSimulation(TrajectoryPlanner(map,0.6,holes),Point(1.0,1.0),
        Geometry.plan(map.shapes.first().points,holes,borders=true),map,config)

    /** Step-by-step playback: checks the exit (fixed heading, axis, slow speed) and returns the state at the link point. */
    private fun leave(engine:MowingSimulation,initial:SimState):SimState {
        assertEquals(SimPhase.LEAVING_DOCK,initial.phase)
        val axis=(engine.dockEntryHeading+180.0)%360.0
        val exit=DockExit(engine.base,engine.baseApproach)
        var s=initial; var guard=0
        while(s.phase==SimPhase.LEAVING_DOCK && guard++<1000) {
            val before=s
            s=engine.advance(s,0.1)
            assertTrue("Advance limited to the exit speed",before.position.distance(s.position)<=engine.config.dockingSpeedMps*0.1+1e-9)
            if(s.phase==SimPhase.LEAVING_DOCK) {
                assertEquals("No pivot during the exit",axis,s.heading,1e-9)
                assertFalse(exit.step(s.position,s.heading).done && s.position.distance(engine.baseApproach)>0.05)
            }
        }
        assertEquals(engine.baseApproach,s.position)
        val seconds=engine.base.distance(engine.baseApproach)/engine.config.dockingSpeedMps
        assertEquals("Duration of the slow exit",seconds,s.elapsed-initial.elapsed,0.2)
        assertTrue(s.log.toString(),s.log.any { it.contains("Point de liaison atteint") })
        return s
    }

    @Test fun initialDepartureLeavesTheBaseSlowlyAlongItsAxisThenResumesNormalSpeed() {
        val e=engine(SimConfig(baseEntryHeadingDegrees=180.0))
        val start=e.start()
        assertEquals(e.base,start.transit[0]); assertEquals(e.baseApproach,start.transit[1])
        assertTrue(start.log.last().contains("Sortie de base"))
        val out=leave(e,start)
        assertTrue(out.phase in setOf(SimPhase.DEPARTING,SimPhase.MOWING))
        assertEquals("The link continues from the link point",1,out.transitIndex)
        val next=e.advance(out,1.0)
        assertTrue("Link at normal speed after the exit",next.position.distance(out.position)>e.config.dockingSpeedMps*1.0 ||
            next.heading!=out.heading)
    }

    @Test fun departureAfterRechargeAlsoLeavesTheBaseInAStraightLine() {
        val e=engine(SimConfig(autonomyMinutes=15.0,chargeMinutes=5.0))
        var s=leave(e,e.start()); var guard=0
        while(s.phase!=SimPhase.CHARGING && guard++<10000) s=e.advance(s,5.0)
        assertEquals(s.log.toString(),SimPhase.CHARGING,s.phase)
        while(s.phase==SimPhase.CHARGING && guard++<20000) s=e.advance(s,1.0)
        assertTrue(s.log.toString(),s.log.last().contains("sortie de base"))
        leave(e,s)
    }

    @Test fun batteryAboveReturnThresholdPlusMarginDepartsLikeTheRobot() {
        // Actual pilot: immediate departure from the return threshold + 10 points (RouteRecharge.departureAllowed).
        val departs=engine(SimConfig(initialBattery=31.0,returnPercent=20.0,resumePercent=90.0,baseEntryHeadingDegrees=180.0)).start()
        assertEquals(SimPhase.LEAVING_DOCK,departs.phase)
        val charges=engine(SimConfig(initialBattery=29.0,returnPercent=20.0,resumePercent=90.0,baseEntryHeadingDegrees=180.0)).start()
        assertEquals(SimPhase.CHARGING,charges.phase)
        assertEquals(RouteRecharge(20,90).departureAllowed(31),departs.phase==SimPhase.LEAVING_DOCK)
        assertEquals(RouteRecharge(20,90).departureAllowed(29),charges.phase==SimPhase.LEAVING_DOCK)
    }

    @Test fun backgroundPauseIsWrittenInTheLog() {
        val e=engine(SimConfig(baseEntryHeadingDegrees=180.0))
        val ui=SimulationUi(e,e.start(),playing=true)
        val paused=pausedInBackground(ui)
        assertFalse(paused.playing)
        assertTrue(paused.state!!.log.last().contains("arrière-plan"))
        assertSame("A simulation already paused stays unchanged",paused,pausedInBackground(paused))
        assertTrue(toggleSimulation(paused).playing)
        assertTrue(JOURNALED_PHASES.containsAll(listOf(SimPhase.LEAVING_DOCK,SimPhase.BLOCKED,SimPhase.COMPLETE)))
    }

    /** Fold scenario (2026-10-13, 22:36): Piscine from the saved base, complete mission with slow exits. */
    @Test(timeout=180000) fun recordedPoolMissionLeavesTheBaseAtEveryDeparture() {
        val (map,_)=ReferenceRoutes.referenceMap()
        val base=MapBase(Point(-0.4563,1.0859),"robot",1.5,153.0312,1.5)
        val zone=map.shapes.single { it.name=="Piscine" }
        val plan=RoutePlanning.coverage(PlanningRequest(map,listOf(zone.id),base.point,
            config=PlanningConfig(boundary=BoundaryMeaning.RECORDED_TRACE),navigation=PlanningNavigation(emptyList(),base)))
        val engine=MowingSimulation(RoutePlanning.mission(map,emptyList(),emptyList(),base),base.point,plan.preview,map,
            SimConfig(baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM,instantCharging=true))
        var s=leave(engine,engine.start())
        var exits=1; var guard=0
        while(s.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED) && guard++<20000) {
            val before=s.phase
            s=engine.advance(s,if(s.phase==SimPhase.CHARGING) 1.0 else 2.0)
            if(before==SimPhase.CHARGING && s.phase==SimPhase.LEAVING_DOCK) { s=leave(engine,s); exits++ }
        }
        assertEquals(s.log.toString(),SimPhase.COMPLETE,s.phase)
        assertTrue("At least one recharge and a new exit",exits>=2)
        assertEquals(engine.total,s.covered,1e-6)
    }
}
