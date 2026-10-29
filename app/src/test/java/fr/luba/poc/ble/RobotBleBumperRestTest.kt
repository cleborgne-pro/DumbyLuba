package fr.luba.poc.ble

import fr.luba.poc.map.Point
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DumbyLuba#1: a LUBA 2 AWD 3000X (`Luba-VP`, firmware 1.30.31.19) reports the bumper slot of `sensor_status` at 1
 * ("alert") in every report, bumper free and no contact; joystick, routes and tests were refused for good. The value
 * present from the connection, robot at rest, is now its resting value; 2–7, a contact (1014) and the collision lock (25)
 * still stop and refuse, as does an alert that appears during the connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleBumperRestTest {
    private val bench = RobotBench(Point(4.0, 3.0), 0.0, name = "Luba-VP-SIM01")
    @After fun tearDown() = bench.close()

    private fun hold(linear: Int, angular: Int, ms: Long): Boolean {
        var accepted = true
        var left = ms
        while (left > 0) { accepted = bench.ble.manualInput(linear, angular) && accepted; bench.advance(100); left -= 100 }
        return accepted
    }

    private fun connectAtRestInAlert() {
        bench.sim.sensorLow = 1
        bench.connect()
        bench.advance(1000)
        assertEquals(bench.dump(), 1, bench.state.bumperRest)
        assertEquals("raw value kept in the telemetry", 1, bench.state.telemetry.sensorStatus!! and 7)
    }

    @Test fun `3000X bumper alert at rest from the connection, joystick allowed`() {
        connectAtRestInAlert()
        assertTrue(bench.state.events.any { it.contains("valeur de repos du robot") })
        val start = bench.sim.position
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(250, 0, 2000))
        assertTrue(bench.state.manualActive)
        assertTrue("robot moved: ${bench.sim.position}", bench.sim.position.distance(start) > 0.1)
        bench.ble.stopManual()
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `3000X bumper alert at rest, mowing route starts`() {
        bench.sim.place(Point(6.0, 4.5), 90.0)
        bench.installMap()
        connectAtRestInAlert()
        val since = bench.now
        assertTrue(bench.dump(), bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
        bench.until(20_000, "first move") { bench.labelsSince(since).any { it.startsWith("move") } }
        assertTrue(bench.state.robotRoute.running)
        bench.ble.stop()
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `bumper error value from the connection is never learnt and refuses joystick and cutoff test`() {
        bench.sim.sensorLow = 2
        bench.connect()
        bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(250, 0))
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("pare-chocs (2)"))
        bench.ble.startSilenceTest(100)
        assertFalse(bench.state.silenceTest.running)
    }

    @Test fun `bumper error value while driving stops, resting value forgotten`() {
        connectAtRestInAlert()
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(250, 0, 1000))
        bench.sim.sensorLow = 3
        hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
        assertEquals(0, bench.state.bumperRest)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        // Back to 1: no longer the resting value on this connection.
        bench.sim.sensorLow = 1
        bench.advance(1500)
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(250, 0))
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("reconnecter"))
    }

    @Test fun `contact 1014 and collision lock still stop and refuse with an alert at rest`() {
        bench.installMap()
        connectAtRestInAlert()
        bench.ble.beginManualGesture()
        assertTrue(hold(250, 0, 1000))
        bench.sim.injectError(1014)
        hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        bench.sim.selfCheck = 25
        bench.advance(1500)
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(250, 0))
        assertFalse(bench.state.manualActive)
        bench.ble.startSilenceTest(100)
        assertFalse(bench.state.silenceTest.running)
    }

    @Test fun `alert that appears after a clear connection still refuses`() {
        bench.connect()
        bench.advance(1000)
        bench.sim.sensorLow = 1
        bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        bench.ble.beginManualGesture()
        assertFalse(bench.ble.manualInput(250, 0))
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("non retenue comme valeur de repos"))
        // A new connection with the alert present from the start takes it as the resting value.
        bench.ble.disconnect()
        bench.advance(2000)
        bench.connect()
        bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(250, 0, 1000))
    }
}
