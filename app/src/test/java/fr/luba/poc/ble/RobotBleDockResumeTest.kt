package fr.luba.poc.ble

import fr.luba.poc.map.Point
import fr.luba.poc.simulation.RoutePhase
import fr.luba.poc.simulation.SimConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Link lost while docking to recharge (2026-10-16, 16:28:54): mowing is interrupted, then resumes
 * on its own once the robot is charging and the link is back ([fr.luba.poc.simulation.DockResume]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleDockResumeTest {
    private val bench = RobotBench(Point(5.0, 4.0), 0.0)
    @After fun tearDown() = bench.close()

    @Test fun `link lost while docking to recharge, mowing resumes on its own once charging`() {
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.sim.battery = 45.0
        bench.installMap()
        bench.connect()
        bench.ble.dockResumeAnyLink = true
        val base = bench.base
        val engine = bench.mowingEngine(config = SimConfig(instantCharging = true, autonomyMinutes = 3.0, returnPercent = 20.0, resumePercent = 60.0,
            baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
        bench.sim.drainPerMeter = 0.6
        bench.sim.chargePerSecond = 2.0
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        bench.until(30 * 60_000, "docking to recharge") { bench.state.robotRoute.phase == RoutePhase.DOCKING }
        // Official return requested and started (state 14): the link drops during docking.
        bench.until(10_000, "official return in progress") { bench.sim.nativeReturnRequests.isNotEmpty() && bench.sim.robotState == 14 }
        // Gateway silent during the official return: link closed, mowing interrupted with its progress.
        bench.link.drop(-2)
        bench.until(5000, "mowing interrupted") { !bench.state.robotRoute.running }
        assertNotNull(bench.state.missionCheckpoint)
        assertTrue(bench.dump(), bench.state.autoResumeStatus.isNotBlank())
        // The robot finishes docking on its own; the link comes back, then mowing resumes from the base.
        bench.until(3 * 60_000, "mowing resumed on its own") { bench.state.robotRoute.running }
        assertTrue(bench.dump(), bench.state.events.any { it.contains("Tonte reprise seule après la perte de la passerelle") })
        assertTrue(bench.sim.charging || bench.state.robotRoute.phase in setOf(RoutePhase.CHARGING, RoutePhase.RESUMING))
        assertTrue(bench.state.autoResumeStatus.isBlank())
        bench.until(40 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        assertTrue(bench.dump(), bench.state.robotRoute.phase == RoutePhase.COMPLETE)
        bench.assertNativeDockOnlyAtApproach(engine)
    }

    @Test fun `link lost mid-mowing, no automatic resume`() {
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.sim.battery = 90.0
        bench.installMap()
        bench.connect()
        bench.ble.dockResumeAnyLink = true
        val engine = bench.mowingEngine()
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        bench.until(5 * 60_000, "cutting in progress") { bench.sim.bladesOn && bench.state.robotRoute.phase == RoutePhase.FOLLOWING }
        bench.advance(3000)
        bench.link.drop(-2)
        bench.until(5000, "mowing interrupted") { !bench.state.robotRoute.running }
        assertTrue(bench.state.autoResumeStatus + "\n" + bench.dump(), bench.state.autoResumeStatus.isBlank())
        bench.advance(60_000)
        assertTrue(bench.dump(), !bench.state.robotRoute.running)
        assertNotNull(bench.state.missionCheckpoint)
    }
}
