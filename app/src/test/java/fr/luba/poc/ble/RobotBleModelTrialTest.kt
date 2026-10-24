package fr.luba.poc.ble

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.ModelTrial
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Model trial on a robot in read-only mode (LUBA 3 AWD, `Luba-VA`): the three unlocking tests only (command cutoff,
 * joystick at low speed, blade test), everything else still refused; ended at disconnection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleModelTrialTest {
    private val bench = RobotBench(Point(4.0, 3.0), 0.0, name = "Luba-VA-SIM01")
    @After fun tearDown() { bench.close(); ModelTrial.resetForTests() }

    private fun hold(linear: Int, angular: Int, ms: Long): Boolean {
        var accepted = true
        var left = ms
        while (left > 0) { accepted = bench.ble.manualInput(linear, angular) && accepted; bench.advance(100); left -= 100 }
        return accepted
    }

    @Test fun `joystick refused in read-only mode, then bounded to low speed during the trial`() {
        bench.installMap(); bench.connect()
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(300, 0))
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
        assertNull(bench.ble.armModelTrial())
        assertNotNull(ModelTrial.state.value)
        bench.ble.beginManualGesture()
        val since = bench.now
        assertTrue(bench.state.manualStatus, hold(1000, 0, 2000))
        val moves = bench.labelsSince(since).filter { it.startsWith("move") }
        assertTrue(moves.toString(), moves.isNotEmpty() && moves.all { it == "move ${ModelTrial.MAX_LINEAR} 0" })
        val released = bench.mark()
        bench.ble.stopManual()
        bench.advance(1000)
        assertEquals(listOf("stop", "blades-off"), bench.labelsAfter(released))
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        assertTrue(bench.state.events.any { it.contains("model_trial_armed") || it.contains("Essai du modèle armé") })
    }

    @Test fun `routes stay refused during the trial and disconnection ends it`() {
        bench.installMap(); bench.connect()
        assertNull(bench.ble.armModelTrial())
        val since = bench.now
        assertFalse(bench.ble.startRobotRoute(bench.mowingEngine(), false, 60))
        bench.advance(2000)
        assertTrue(bench.labelsSince(since).none { it.startsWith("move") || it == "blades-on" })
        bench.ble.disconnect()
        bench.advance(2000)
        assertNull(ModelTrial.state.value)
        bench.connect()
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(200, 0))
    }

    @Test fun `blade test while stationary during the trial`() {
        bench.connect()
        bench.ble.prepareBladeStop(50)
        bench.advance(1000)
        assertFalse("read-only: no blade check", bench.state.bladeStatus.contains("OFF demandé"))
        assertNull(bench.ble.armModelTrial())
        bench.ble.prepareBladeStop(50)
        bench.until(5000, "blade stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(50)
        assertTrue(bench.state.bladeStatus, bench.state.bladeTestActive)
        bench.until(5000, "blades running") { bench.sim.bladesOn }
        bench.until(15_000, "end of test") { !bench.state.bladeTestActive && !bench.sim.bladesOn }
        assertTrue("No movement during the test", bench.sim.trace.none { it.second.startsWith("move") })
    }

    @Test fun `command cutoff test during the trial`() {
        bench.installMap(); bench.connect()
        bench.ble.startSilenceTest(100)
        assertFalse(bench.state.silenceTest.running)
        assertNull(bench.ble.armModelTrial())
        bench.ble.startSilenceTest(100)
        assertTrue(bench.state.silenceTest.status, bench.state.silenceTest.running)
        bench.until(60_000, "verdict") { !bench.state.silenceTest.running }
        assertNotNull(bench.state.motorWatchdog)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }
}
