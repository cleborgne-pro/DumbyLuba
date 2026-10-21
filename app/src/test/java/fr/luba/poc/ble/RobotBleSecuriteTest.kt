package fr.luba.poc.ble

import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.RoutePhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end safety guards: global STOP from every activity (blades OFF then zero speed actually
 * written, no setpoint afterwards), lift, robot errors, link loss and reconnection without resume,
 * silent telemetry, failed GATT write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleSecuriteTest {
    private val bench = RobotBench(Point(5.0, 4.0), 0.0)
    @After fun tearDown() = bench.close()

    /**
     * After [since]: from the first stop command, blades stopped (if [bladesOff]) then zero speed written, no further
     * movement or blade setpoint, robot still.
     */
    private fun assertStoppedSince(since: Long, bladesOff: Boolean = true) {
        bench.advance(500)
        val labels = bench.labelsSince(since)
        val first = labels.indexOfFirst { it == "stop" || it == "blades-off" }
        assertTrue(labels.toString(), first >= 0)
        val after = labels.drop(first)
        assertTrue(labels.toString(), after.none { it.startsWith("move") || it == "blades-on" || it == "dock" })
        assertTrue(labels.toString(), "stop" in after)
        if (bladesOff) assertTrue(labels.toString(), after.indexOf("blades-off") in 0 until after.indexOf("stop"))
        bench.advance(3000)
        assertEquals("Robot stopped", 0.0, bench.sim.speed, 0.005)
        assertFalse("Blades stopped", bench.sim.bladesOn)
    }

    @Test fun `global STOP at rest, zero speed written then link closed without reconnection`() {
        bench.connect()
        val since = bench.now
        bench.ble.stop()
        assertEquals(ConnectionState.Closing, bench.state.connection)
        bench.until(3000, "link closed") { bench.state.connection == ConnectionState.Disconnected }
        assertEquals(listOf("stop"), bench.labelsSince(since))
        assertTrue(bench.state.status, bench.state.status.contains("STOP manuel"))
        assertTrue(bench.link.closed)
        bench.advance(30_000)
        assertEquals("No reconnection after a deliberate STOP", 1, bench.links.size)
    }

    @Test fun `global STOP while mowing, blades OFF then zero speed, progress kept`() {
        bench.mowingUnderway()
        assertTrue(bench.sim.bladesOn)
        val since = bench.now
        bench.ble.stop()
        assertFalse(bench.state.robotRoute.running)
        assertEquals(RoutePhase.STOPPED, bench.state.robotRoute.phase)
        bench.until(3000, "link closed") { bench.state.connection == ConnectionState.Disconnected }
        assertStoppedSince(since)
        val checkpoint = bench.state.missionCheckpoint
        assertNotNull("Mowing resumable", checkpoint)
        assertEquals("STOP manuel", checkpoint!!.interruption)
        assertTrue(checkpoint.progress > 0.0)
        assertEquals(fr.luba.poc.history.MowingOutcome.INTERRUPTED, bench.state.mowingReport?.outcome)
    }

    @Test fun `STOP during final docking, official return cancelled then pause and zero speed`() {
        bench.sim.place(Point(6.5, 4.5), 200.0)
        bench.installMap(); bench.connect()
        bench.sim.nativeReturnSpeed = 0.05
        assertTrue(bench.dump(), bench.ble.baseAction(bench.stationEngine(), leave = false))
        bench.until(5 * 60_000, "official docking in progress") { bench.sim.nativeReturn && bench.state.telemetry.robotState == 14 }
        val since = bench.now
        bench.ble.stop()
        bench.until(3000, "link closed") { bench.state.connection == ConnectionState.Disconnected }
        val labels = bench.labelsSince(since)
        assertEquals(labels.toString(), listOf("dock-cancel", "task-pause", "stop"), labels.filter { it != "blades-off" })
        assertFalse(bench.sim.nativeReturn)
    }

    @Test fun `STOP during a direct official return, task paused then zero speed`() {
        bench.installMap()
        bench.sim.place(Point(3.08, 3.0), 0.0)
        bench.sim.nativeReturnSpeed = 0.02
        bench.connect()
        assertTrue(bench.state.nativeReturnStatus, bench.ble.baseAction(bench.stationEngine(), leave = false))
        bench.until(10_000, "official return observed") { bench.state.nativeReturnStatus.contains("en cours") }
        val since = bench.now
        bench.ble.stop()
        bench.until(3000, "link closed") { bench.state.connection == ConnectionState.Disconnected }
        assertEquals(listOf("task-pause", "stop"), bench.labelsSince(since))
        bench.advance(1000)
        assertFalse(bench.state.nativeReturnStatus, bench.state.nativeReturnActive)
    }

    @Test fun `lift while mowing, immediate stop blades OFF and critical alert`() {
        bench.mowingUnderway()
        val since = bench.now
        bench.sim.injectError(1004)
        bench.until(3000, "stop on lift") { !bench.state.robotRoute.running }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Soulèvement"))
        assertEquals(RoutePhase.STOPPED, bench.state.robotRoute.phase)
        assertStoppedSince(since)
        assertTrue("Link kept", bench.state.ready)
        bench.awaitReal(5000, "robot lifted alert") { bench.alerted(AlertKind.LIFTED) }
        assertNotNull(bench.state.missionCheckpoint)
    }

    @Test fun `disconnection during a STOP already in flight, closing after the STOP`() {
        bench.mowingUnderway()
        val since = bench.now
        bench.sim.injectError(1004)
        bench.until(2000, "stop on lift") { !bench.state.robotRoute.running }
        bench.ble.disconnect()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertTrue("stop" in bench.labelsSince(since))
        bench.advance(30_000)
        assertEquals(1, bench.links.size)
    }

    @Test fun `lift reported at rest, recorded without command`() {
        bench.connect()
        val since = bench.now
        bench.sim.injectError(1004)
        bench.advance(3000)
        assertTrue(bench.labelsSince(since).none { it == "stop" || it.startsWith("move") })
        assertTrue(bench.state.events.any { it.contains("Soulèvement signalé par le robot") })
        assertEquals(1004, bench.state.lastRobotError?.info?.code)
    }

    @Test fun `stopping robot error while mowing, route interrupted`() {
        bench.mowingUnderway()
        val since = bench.now
        bench.sim.injectError(424242)
        bench.until(3000, "stop on error") { !bench.state.robotRoute.running }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Erreur robot"))
        assertStoppedSince(since)
        bench.awaitReal(5000, "unexpected stop alert") { bench.alerted(AlertKind.UNEXPECTED_STOP) }
    }

    @Test fun `informational error or low battery at rest, no command`() {
        bench.connect()
        val since = bench.now
        bench.sim.injectError(2806)
        bench.advance(1000)
        bench.sim.injectError(1005)
        bench.advance(2000)
        assertTrue(bench.labelsSince(since).none { it == "stop" || it.startsWith("move") })
        assertEquals(1005, bench.state.lastRobotError?.info?.code)
        bench.awaitReal(5000, "robot error alert") { bench.alerted(AlertKind.ROBOT_ERROR) }
        assertTrue(bench.state.ready)
    }

    @Test fun `link loss while mowing, the robot stops on its own and reconnection resumes nothing`() {
        bench.mowingUnderway()
        val lostAt = bench.now
        bench.link.drop()
        bench.advance(100)
        assertFalse(bench.state.robotRoute.running)
        assertEquals(RoutePhase.STOPPED, bench.state.robotRoute.phase)
        assertTrue(bench.state.status, bench.state.status.contains("reconnexion automatique 1/5"))
        assertEquals("Liaison fermée", bench.state.missionCheckpoint?.interruption)
        // No frame could be sent: the robot stops by itself (motor safety stop of the simulated firmware).
        bench.advance(2500)
        assertEquals(0.0, bench.sim.speed, 0.005)
        bench.until(10_000, "reconnection") { bench.links.size == 2 && bench.state.connection == ConnectionState.Receiving }
        val reconnected = bench.now
        bench.advance(20_000)
        assertFalse(bench.state.robotRoute.running)
        // The simulated link refuses every write after drop(); virtual time stays frozen until the next advance, so a
        // frame written just before the loss by a late background thread carries the loss instant: only later ones count.
        val afterLoss = bench.labelsSince(lostAt + 1)
        assertTrue("No movement resumed after reconnection: $afterLoss", afterLoss.none { it.startsWith("move") || it == "blades-on" })
        assertTrue(bench.labelsSince(reconnected).none { it == "manual" })
        bench.awaitReal(5000, "unexpected stop alert") { bench.alerted(AlertKind.UNEXPECTED_STOP) }
        assertEquals(1, bench.state.interruptions)
    }

    @Test fun `robot unreachable after a loss, reconnection abandoned after five attempts`() {
        bench.connect()
        bench.reachable = false
        bench.link.drop()
        bench.until(4 * 60_000, "reconnection abandoned") { bench.state.status.contains("abandonnée après 5 essais") }
        assertEquals(6, bench.links.size)
        assertEquals(ConnectionState.Disconnected, bench.state.connection)
        bench.advance(60_000)
        assertEquals(6, bench.links.size)
    }

    @Test fun `silent telemetry while mowing, stop on stale position then watchdog`() {
        bench.mowingUnderway()
        val since = bench.now
        bench.sim.silent = true
        bench.until(4000, "stop on stale position") { !bench.state.robotRoute.running }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("périmée"))
        assertStoppedSince(since)
        bench.until(15_000, "telemetry watchdog") { bench.state.status.contains("timeout télémétrie") || bench.links.size > 1 }
    }

    @Test fun `GATT write error, link closed then reconnection without command`() {
        bench.connect()
        bench.link.writeStatus = 133
        val since = bench.now
        bench.until(15_000, "write failure") { bench.state.connection == ConnectionState.Disconnected || bench.links.size > 1 }
        assertTrue(bench.state.events.any { it.contains("Écriture GATT : 133") })
        bench.until(15_000, "reconnection") { bench.links.size == 2 && bench.state.connection == ConnectionState.Receiving }
        assertTrue(bench.labelsSince(since).none { it.startsWith("move") })
    }

    @Test fun `GATT operation stuck, failure declared and link closed`() {
        bench.connect()
        bench.link.muteWrites = true
        bench.until(15_000, "GATT timeout failure") { bench.state.events.any { it.contains("timeout GATT") } }
        bench.until(5000, "link closed") { bench.state.connection == ConnectionState.Disconnected || bench.links.size > 1 }
        assertTrue(bench.link.closed || bench.links.size > 1)
    }

    @Test fun `deliberate disconnection, STOP written then closing without reconnection`() {
        bench.connect()
        val since = bench.now
        bench.ble.disconnect()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertEquals(listOf("stop"), bench.labelsSince(since))
        assertTrue(bench.state.status, bench.state.status.contains("Déconnexion demandée"))
        bench.advance(60_000)
        assertEquals(1, bench.links.size)
        assertFalse(bench.state.ready)
    }

    @Test fun `background without link service, stop and closing`() {
        bench.mowingUnderway()
        val since = bench.now
        bench.ble.background()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertStoppedSince(since)
        assertFalse(bench.state.robotRoute.running)
    }

    @Test fun `background with link kept, mowing continues`() {
        bench.mowingUnderway()
        bench.ble.background(keepConnection = true)
        bench.advance(10_000)
        assertTrue(bench.state.robotRoute.running)
        assertTrue(bench.sim.bladesOn)
    }
}
