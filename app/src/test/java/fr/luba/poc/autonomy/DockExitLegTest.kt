package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.simulation.DockExit
import fr.luba.poc.simulation.RouteRecharge
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving the station run by the gateway (test of 2026-10-13: error around 20 cm, exit driven over the Wi-Fi). */
class DockExitLegTest {
    private val base = Point(2.0, 1.0)
    private val approach = Point(2.0, 2.2)   // 90° axis
    private val path = listOf(SimEdge(base, approach, false))

    private fun spec(dockExit: Boolean = true) = LegSpec(legId = 3, routeId = 1, from = 0, cutting = false, shown = false, watchBattery = false,
        departure = true, lastArrivalM = null, heightMm = 60, speedPercent = 40, returnPercent = 20, baseApproach = approach,
        guardId = -1, physicalGuardId = -1, calibration = null, obstacles = emptyList(), sampling = false, dockExit = dockExit)

    private fun docked(heading: Double = 90.0) = SimulatedRobotEnv(base, heading, noiseM = 0.005).also { it.dock = base }

    private fun run(env: SimulatedRobotEnv) = runBlocking { LegRunner(env, spec(), path, null, null).run() }

    @Test fun dockExitFlagSurvivesTheWire() {
        assertTrue(LegSpec.decode(spec().encode()).dockExit)
        assertTrue(!LegSpec.decode(spec(dockExit = false).encode()).dockExit)
    }

    @Test fun leavesTheStationStraightAndConfirmsTheChargeContactIsLost() {
        val env = docked()
        val result = run(env)
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("arrived in front of the base: ${env.position}", env.position.distance(approach) < 0.15)
        assertEquals("ROUTE_DOCK_EXIT_STOP", env.sent.last())
        // No pivot: only slow forward moves, reports renewed from the start.
        assertTrue(env.sent.first().startsWith("ROUTE_SYNC") || env.sent.first().startsWith("ROUTE_REPORTS_KEEP"))
        assertTrue(env.maxLinearSeen <= RouteRecharge.EXIT_LINEAR)
    }

    @Test fun publishedHeadingRecalibratedWhileRollingNoLongerStopsTheExit() {
        val env = docked()
        // Published heading realigned by 80° after ~20 cm (LUBA 2, ROBOT_ROUTE_BLE.md): the real trajectory stays straight.
        env.onTick = { if (it.position.distance(base) > 0.18) it.headingBias = 80.0 }
        val result = run(env)
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("arrived despite the wrong published heading: ${env.position}", env.position.distance(approach) < 0.15)
    }

    @Test fun misalignedRobotOnTheBaseIsRefusedBeforeMoving() {
        val env = docked(heading = 110.0)
        val result = run(env)
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("non aligné"))   // French wire reason from DockExit
        assertTrue("no forward move", env.position.distance(base) < 0.03)
    }

    @Test fun lateralDriftOutOfTheCorridorStops() {
        val env = docked(heading = 75.0 + 0.0)   // 15°: refused at the start
        assertEquals(LegOutcome.FAILED, run(env).outcome)
        val exit = DockExit(base, approach)
        exit.step(base, 90.0)
        assertThrows(IllegalStateException::class.java) { exit.step(Point(2.15, 1.3), 90.0) }
    }

    @Test fun trackDirectionIsCheckedOnceTheRobotHasRolled() {
        val exit = DockExit(base, approach)
        assertEquals(RouteRecharge.EXIT_LINEAR, exit.step(base, 92.0).linear)
        // Wrong published heading while rolling: ignored, the travelled (straight) direction prevails.
        val rolling = exit.step(Point(2.0, 1.15), 170.0)
        assertEquals(90.0, rolling.heading, 1e-6)
        assertNotNull(exit.headingJump)
        assertEquals(90.0, exit.step(Point(2.01, 1.4), 170.0).heading, 3.0)
        // Robot leaving at an angle (≥ 15° on the track): stop, even inside the corridor.
        val skewed = DockExit(base, approach); skewed.step(base, 90.0)
        assertThrows(IllegalStateException::class.java) { skewed.step(Point(2.09, 1.26), 90.0) }
        assertTrue(DockExit(base, approach).step(Point(2.0, 2.18), 90.0).done)
        assertNull(DockExit(base, approach).headingJump)
    }
}
