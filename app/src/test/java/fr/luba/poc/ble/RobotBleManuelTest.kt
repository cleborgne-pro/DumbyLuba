package fr.luba.poc.ble

import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Commands
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Manual control (joystick), blade test and height setting, with their guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleManuelTest {
    private val bench = RobotBench(Point(4.0, 3.0), 0.0)
    @After fun tearDown() = bench.close()

    /** Finger held on the joystick: input refreshed every 100 ms for [ms]. */
    private fun hold(linear: Int, angular: Int, ms: Long): Boolean {
        var accepted = true
        var left = ms
        while (left > 0) { accepted = bench.ble.manualInput(linear, angular) && accepted; bench.advance(100); left -= 100 }
        return accepted
    }

    // ───── Joystick ─────

    @Test fun `joystick, forward motion capped at 5 Hz then stop on release`() {
        bench.connect()
        assertFalse("New press required", bench.ble.manualInput(300, 0))
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("Nouvel appui"))
        bench.ble.beginManualGesture()
        val since = bench.now
        assertTrue(hold(300, 0, 3000))
        assertTrue(bench.state.manualActive)
        assertTrue("The robot moves forward", bench.sim.speed > 0.2)
        val moves = bench.labelsSince(since).filter { it.startsWith("move") }
        assertTrue(moves.toString(), moves.all { it == "move 300 0" })
        assertTrue("Setpoints at about 5 Hz: ${moves.size}", moves.size in 12..17)
        assertTrue(bench.sim.trace.none { it.second == "blades-on" || it.second == "manual" })
        val released = bench.mark()
        bench.ble.stopManual()
        assertFalse(bench.state.manualActive)
        bench.advance(2000)
        assertEquals(listOf("stop"), bench.labelsAfter(released))
        assertEquals(0.0, bench.sim.speed, 0.005)
        // New gesture required after release.
        assertFalse(bench.ble.manualInput(300, 0))
        bench.ble.beginManualGesture()
        assertTrue(hold(0, 200, 1000))
        bench.ble.stopManual()
    }

    @Test fun `joystick out of bounds or robot other than LUBA 2, refused without command`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(Commands.JOYSTICK_MAX_LINEAR + 1, 0))
        assertTrue(bench.state.manualStatus.contains("hors limites"))
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(0, Commands.JOYSTICK_MAX_ANGULAR + 1))
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
    }

    @Test fun `stale joystick input, automatic stop and alert`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1000))
        val since = bench.now
        bench.advance(1500)
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("Entrée joystick périmée"))
        assertTrue(bench.labelsSince(since).toString(), "stop" in bench.labelsSince(since))
        bench.advance(1500)
        assertEquals(0.0, bench.sim.speed, 0.005)
        bench.awaitReal(5000, "unexpected stop alert") { bench.alerted(AlertKind.UNEXPECTED_STOP) }
        assertFalse("No resume without a new press", bench.ble.manualInput(250, 0))
    }

    @Test fun `stale telemetry during manual control, stop`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1000))
        bench.sim.silent = true
        hold(250, 0, 3000)
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("périmé") || bench.state.manualStatus.contains("refusé"))
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `protection or robot error during manual control, stop`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1000))
        bench.sim.selfCheck = 25
        hold(250, 0, 2500)
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("collision") || bench.state.manualStatus.contains("refusé"))
        bench.sim.selfCheck = 0
        bench.advance(2000)
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1000))
        bench.sim.injectError(1014)
        hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("1014") || bench.state.manualStatus.contains("refusé"))
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `battery at floor, manual control refused`() {
        bench.sim.battery = 30.0
        bench.connect()
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(200, 0))
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("Batterie"))
    }

    @Test fun `lift during manual control, STOP`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1500))
        val since = bench.now
        bench.sim.injectError(1004)
        bench.advance(1000)
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.status, bench.state.status.contains("Soulèvement"))
        assertTrue("stop" in bench.labelsSince(since))
        bench.awaitReal(5000, "lifted alert") { bench.alerted(AlertKind.LIFTED) }
    }

    @Test fun `global STOP during manual control`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(400, 100, 1500))
        val since = bench.now
        bench.ble.stop()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertFalse(bench.state.manualActive)
        assertEquals(listOf("stop"), bench.labelsSince(since))
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    // ───── Blades ─────

    @Test fun `blade test, OFF check then 10 s spin and confirmed stop`() {
        bench.connect()
        bench.ble.startBladeTest(50)
        assertFalse(bench.state.bladeTestActive)
        assertTrue(bench.state.bladeStatus, bench.state.bladeStatus.contains("Vérifier OFF"))
        bench.ble.prepareBladeStop(50)
        bench.until(5000, "blade stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(50)
        assertTrue(bench.state.bladeStatus, bench.state.bladeTestActive)
        bench.until(5000, "blades running") { bench.sim.bladesOn }
        assertEquals(50, bench.sim.heightMm)
        bench.until(15_000, "end of test") { !bench.state.bladeTestActive && !bench.sim.bladesOn }
        bench.until(5000, "result") { bench.state.bladeResults.isNotEmpty() }
        assertEquals("ON_AND_OFF_REPORTED", bench.state.bladeResults.last().outcome)
        assertTrue("No movement during the test", bench.sim.trace.none { it.second.startsWith("move") })
    }

    @Test fun `blade test stopped on release`() {
        bench.connect()
        bench.ble.prepareBladeStop(60)
        bench.until(5000, "stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(60)
        bench.until(5000, "blades running") { bench.sim.bladesOn }
        bench.ble.stopBladeTest()
        bench.until(3000, "blades stopped") { !bench.sim.bladesOn }
        bench.until(3000, "result") { bench.state.bladeResults.isNotEmpty() }
        assertFalse(bench.state.bladeTestActive)
        // Joystick refused until the stop is confirmed, accepted afterwards.
        bench.advance(2000)
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, bench.ble.manualInput(100, 0))
        bench.ble.stopManual()
    }

    @Test fun `blades that do not stop, STOP and link closed`() {
        bench.connect()
        bench.ble.prepareBladeStop(60)
        bench.until(5000, "stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(60)
        bench.until(5000, "blades running") { bench.sim.bladesOn }
        bench.sim.stuckBlades = true
        bench.ble.stopBladeTest()
        bench.until(8000, "stop not confirmed") { bench.state.connection != ConnectionState.Receiving }
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertTrue(bench.state.events.any { it.contains("OFF lames non confirmé") })
        assertTrue(bench.state.bladeResults.any { it.outcome == "OFF_UNCONFIRMED" })
    }

    @Test fun `blade test refused on low battery, during manual control, robot not ready`() {
        bench.sim.battery = 25.0
        bench.connect()
        bench.ble.prepareBladeStop(60)
        bench.until(5000, "stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(60)
        assertFalse(bench.state.bladeTestActive)
        assertTrue(bench.state.bladeStatus, bench.state.bladeStatus.contains("Batterie"))
        bench.sim.battery = 80.0
        bench.sim.idleState = 13
        bench.advance(2000)
        bench.ble.startBladeTest(60)
        assertFalse(bench.state.bladeTestActive)
        assertTrue(bench.state.bladeStatus, bench.state.bladeStatus.contains("non prêt"))
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
    }

    // ───── Height ─────

    @Test fun `height setting reported then kept outside manual mode`() {
        bench.connect()
        bench.ble.setBladeHeight(40)
        assertTrue(bench.state.bladeHeightPending)
        bench.until(20_000, "height result") { !bench.state.bladeHeightPending }
        assertEquals(bench.state.bladeStatus, "REPORTED", bench.state.bladeResults.last().outcome)
        assertEquals(40, bench.sim.heightMm)
        assertFalse("Manual mode left", bench.sim.manual)
        assertTrue(bench.sim.trace.none { it.second == "blades-on" || it.second.startsWith("move") })
    }

    @Test fun `height never reported, result unconfirmed`() {
        bench.connect()
        bench.sim.freezeHeight = true
        bench.ble.setBladeHeight(35, timeoutMs = 10_000)
        bench.until(20_000, "height result") { !bench.state.bladeHeightPending }
        assertEquals("UNCONFIRMED", bench.state.bladeResults.last().outcome)
        assertFalse(bench.sim.manual)
    }

    @Test fun `height sequence and cancellation`() {
        bench.connect()
        bench.ble.startHeightSequence(listOf(45, 60))
        assertTrue(bench.state.heightSequence.running)
        bench.until(60_000, "end of sequence") { !bench.state.heightSequence.running }
        assertEquals(bench.state.heightSequence.status, "PASS", bench.state.bladeResults.last().outcome)
        assertEquals(60, bench.sim.heightMm)
        // Cancelling a pending wait.
        bench.sim.freezeHeight = true
        bench.ble.setBladeHeight(30)
        bench.advance(1000)
        bench.ble.cancelBladeHeight()
        assertFalse(bench.state.bladeHeightPending)
        assertEquals("INTERRUPTED", bench.state.bladeResults.last().outcome)
        // Sequence stopped while running.
        bench.sim.freezeHeight = false
        bench.advance(2000)
        bench.ble.startHeightSequence(listOf(35, 50))
        bench.advance(500)
        bench.ble.stopHeightSequence()
        assertFalse(bench.state.heightSequence.running)
        assertEquals("Séquence annulée", bench.state.heightSequence.status)
        // Invalid sequence refused.
        bench.ble.startHeightSequence(listOf(10))
        assertTrue(bench.state.heightSequence.status, bench.state.heightSequence.status.startsWith("Séquence refusée"))
    }
}
