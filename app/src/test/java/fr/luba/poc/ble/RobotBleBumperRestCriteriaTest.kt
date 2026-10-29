package fr.luba.poc.ble

import fr.luba.poc.gateway.GatewayChannel
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.gateway.GatewayProtocol
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.ModelTrial
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Acceptance criteria of the bumper resting value (DumbyLuba#1, LUBA 2 AWD 3000X `Luba-VP` reading the bumper slot of
 * `sensor_status` at 1 in every report), on the test bench: docked start, error values 2–7, collision lock while
 * driving, alert appearing during a route, conditions that prevent learning (contact 1014 or lock 25 seen before, robot
 * not ready, motion driven by the app), reset at reconnection and robot change, LUBA 2 at 0 and unvalidated model
 * unchanged, gateway tracking disabled while a resting value is learnt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleBumperRestCriteriaTest {
    private var opened: RobotBench? = null
    @After fun tearDown() { opened?.close(); ModelTrial.resetForTests() }

    private fun bench(name: String = "Luba-VP-SIM01", start: Point = Point(4.0, 3.0)): RobotBench =
        RobotBench(start, 0.0, name = name).also { opened = it }

    private fun RobotBench.hold(linear: Int, angular: Int, ms: Long): Boolean {
        var accepted = true
        var left = ms
        while (left > 0) { accepted = ble.manualInput(linear, angular) && accepted; advance(100); left -= 100 }
        return accepted
    }

    private fun RobotBench.joystickRefused(): Boolean {
        ble.beginManualGesture()
        return !ble.manualInput(250, 0)
    }

    private fun RobotBench.learntEvents() = state.events.count { it.contains("valeur de repos du robot") }

    private fun RobotBench.reconnect() {
        ble.disconnect()
        advance(2000)
        connect()
        advance(1000)
    }

    // ───── 1. 3000X at rest with the slot at 1 ─────

    @Test fun `3000X docked in state 11 with the alert learns it and starts a cutting route from the base`() {
        val bench = bench()
        bench.sim.chargingState = 11 // 3000X logs of DumbyLuba#1: docked in state 11.
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.installMap()
        bench.sim.sensorLow = 1
        bench.connect()
        bench.advance(1000)
        assertTrue(bench.sim.charging)
        assertEquals(bench.dump(), 1, bench.state.bumperRest)
        val since = bench.now
        assertTrue(bench.dump(), bench.ble.startRobotRoute(bench.mowingEngine(), cutting = true, heightMm = 55))
        bench.until(30_000, "first move") { bench.labelsSince(since).any { it.startsWith("move") } }
        assertTrue(bench.state.robotRoute.running)
        bench.until(30_000, "robot off the base") { !bench.sim.charging }
        bench.ble.stop()
        bench.advance(3000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        assertFalse(bench.sim.bladesOn)
    }

    // ───── 2. Slot at 2–7 ─────

    @Test fun `3000X bumper slot 2 to 7 from the connection refuses joystick and route, never learnt`() {
        val bench = bench(start = Point(6.0, 4.5))
        bench.installMap()
        for (slot in 2..7) {
            bench.sim.sensorLow = slot
            if (slot == 2) { bench.connect(); bench.advance(1000) } else bench.reconnect()
            assertEquals("slot $slot", 0, bench.state.bumperRest)
            assertTrue("slot $slot", bench.joystickRefused())
            assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("pare-chocs ($slot)"))
            val since = bench.now
            assertFalse("slot $slot", bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
            bench.advance(1000)
            assertTrue(bench.labelsSince(since).none { it.startsWith("move") || it == "blades-on" })
        }
        assertEquals(0, bench.learntEvents())
    }

    @Test fun `3000X learnt alert then error value at rest refuses joystick and route`() {
        val bench = bench(start = Point(6.0, 4.5))
        bench.installMap()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        bench.sim.sensorLow = 5
        bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        assertTrue(bench.state.events.any { it.contains("valeur de repos oubliée") })
        assertTrue(bench.joystickRefused())
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("pare-chocs (5)"))
        assertFalse(bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
    }

    // ───── 3. Contact 1014 / self-check 25 ─────

    @Test fun `collision lock 25 while driving with a learnt alert stops, then refuses joystick and route`() {
        val bench = bench(start = Point(4.0, 3.0))
        bench.installMap()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, bench.hold(250, 0, 1000))
        bench.sim.selfCheck = 25
        bench.hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        assertTrue(bench.joystickRefused())
        assertFalse(bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
    }

    // ───── 4. Alert that appears after a clear connection ─────

    @Test fun `3000X alert appearing during a route started with a clear bumper stops it and refuses the next one`() {
        val bench = bench(start = Point(6.0, 4.5))
        bench.sim.place(Point(6.0, 4.5), 90.0)
        bench.installMap()
        bench.connect(); bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        val since = bench.now
        assertTrue(bench.dump(), bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
        bench.until(20_000, "first move") { bench.labelsSince(since).any { it.startsWith("move") } }
        bench.sim.sensorLow = 1
        bench.until(10_000, "route stopped") { !bench.state.robotRoute.running }
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        assertEquals(0, bench.state.bumperRest)
        assertFalse(bench.ble.startRobotRoute(bench.mowingEngine(), cutting = false, heightMm = 60))
        assertTrue(bench.joystickRefused())
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("non retenue comme valeur de repos"))
    }

    // ───── 5. Conditions that prevent learning ─────

    @Test fun `contact 1014 received before the robot is ready prevents learning`() {
        val bench = bench()
        bench.sim.idleState = 13
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        bench.sim.injectError(1014)
        bench.advance(2000)
        bench.sim.idleState = 11
        bench.advance(2000)
        assertEquals(bench.dump(), 0, bench.state.bumperRest)
        assertEquals(0, bench.learntEvents())
        assertTrue(bench.joystickRefused())
    }

    @Test fun `collision lock 25 seen since the connection prevents learning once cleared`() {
        val bench = bench()
        bench.sim.selfCheck = 25
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        bench.sim.selfCheck = 0
        bench.advance(2000)
        assertEquals(bench.dump(), 0, bench.state.bumperRest)
        assertEquals(0, bench.learntEvents())
        assertTrue(bench.joystickRefused())
    }

    @Test fun `robot not ready is not learnt, learnt once it reports state 11`() {
        val bench = bench()
        bench.sim.idleState = 13
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(3000)
        assertEquals(0, bench.state.bumperRest)
        assertEquals(0, bench.learntEvents())
        bench.sim.idleState = 11
        bench.advance(1500)
        assertEquals(bench.dump(), 1, bench.state.bumperRest)
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, bench.hold(250, 0, 1000))
        bench.ble.stopManual()
        bench.advance(2000)
    }

    @Test fun `motion driven by the app prevents learning until it ends`() {
        val bench = bench()
        bench.sim.idleState = 13
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        val remoteLeg = RobotBle::class.java.getDeclaredField("remoteLegActive").apply { isAccessible = true }
        remoteLeg.setBoolean(bench.ble, true)
        bench.sim.idleState = 11
        bench.advance(2500)
        assertEquals("not learnt while a move is driven", 0, bench.state.bumperRest)
        remoteLeg.setBoolean(bench.ble, false)
        bench.advance(1500)
        assertEquals(bench.dump(), 1, bench.state.bumperRest)
    }

    // ───── 6. Reset at reconnection and robot change ─────

    @Test fun `learnt value reset at the reconnection`() {
        val bench = bench()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        bench.ble.disconnect()
        bench.advance(2000)
        bench.sim.sensorLow = 0
        bench.ble.connect(bench.device)
        assertEquals("reset as soon as the connection starts", 0, bench.state.bumperRest)
        bench.until(10_000, "telemetry") { bench.state.connection == ConnectionState.Receiving && bench.state.telemetry.robotState != null }
        bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        bench.sim.sensorLow = 1
        bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        assertTrue(bench.joystickRefused())
        assertTrue(bench.state.manualStatus, bench.state.manualStatus.contains("non retenue comme valeur de repos"))
    }

    @Test fun `learnt value reset when another robot is connected`() {
        val bench = bench()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        // Another robot (connect() is ignored while a link is open: disconnection first).
        bench.ble.disconnect()
        bench.advance(2000)
        val other = SeenRobot("Luba-VS-SIM02", "AA:BB:CC:00:00:02", -50)
        bench.sim.sensorLow = 0
        bench.ble.connect(other)
        assertEquals(0, bench.state.bumperRest)
        bench.until(10_000, "telemetry of the other robot") {
            bench.state.selected?.address == other.address && bench.state.connection == ConnectionState.Receiving && bench.state.telemetry.robotState != null }
        bench.advance(1000)
        assertEquals(0, bench.state.bumperRest)
        bench.sim.sensorLow = 1
        bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        assertTrue(bench.joystickRefused())
    }

    // ───── 7. LUBA 2 at 0 unchanged ─────

    @Test fun `LUBA 2 bumper at 0 learns nothing, drives, and an alert appearing while driving stops and refuses`() {
        val bench = bench(name = "Luba-VS-SIM01")
        bench.connect(); bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        assertEquals(0, bench.learntEvents())
        assertTrue(bench.state.events.none { it.contains("valeur de repos oubliée") })
        val start = bench.sim.position
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, bench.hold(250, 0, 1500))
        assertTrue(bench.sim.position.distance(start) > 0.1)
        bench.sim.sensorLow = 1
        bench.hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        assertEquals(0, bench.state.bumperRest)
        assertTrue(bench.joystickRefused())
    }

    // ───── 8. Unvalidated model: model trial unchanged ─────

    @Test fun `unvalidated LUBA 3 with the alert from the connection learns nothing without the trial`() {
        val bench = bench(name = "Luba-VA-SIM01")
        bench.installMap()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(2000)
        assertEquals(0, bench.state.bumperRest)
        assertEquals(0, bench.learntEvents())
        assertTrue(bench.joystickRefused())
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
    }

    // ───── 9. Gateway tracking ─────

    /** [GatewayLink] announcing autonomous tracking, without a socket (never started). */
    private fun RobotBench.autonomousGateway(): GatewayLink {
        val channel = GatewayChannel::class.java
            .getDeclaredConstructor(Socket::class.java, DataInputStream::class.java, DataOutputStream::class.java)
            .apply { isAccessible = true }
            .newInstance(Socket(), DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(ByteArrayOutputStream()))
        GatewayChannel::class.java.getDeclaredField("capabilities").apply { isAccessible = true }.set(channel, setOf(GatewayProtocol.CAP_AUTONOMY))
        val listener = RobotBle::class.java.getDeclaredField("linkListener").apply { isAccessible = true }.get(ble)
        val link = GatewayLink::class.java.declaredConstructors.first { it.parameterCount == 6 }.apply { isAccessible = true }
            .newInstance("127.0.0.1", 1, "000000", device.address, device.name, listener) as GatewayLink
        GatewayLink::class.java.getDeclaredField("channel").apply { isAccessible = true }.set(link, channel)
        assertTrue(link.supportsLegs)
        return link
    }

    /** `autonomousLegsAvailable()` with [gateway] as the link to the robot (the simulated link put back afterwards). */
    private fun RobotBench.legsAvailable(gateway: GatewayLink): Boolean {
        val gatt = RobotBle::class.java.getDeclaredField("gatt").apply { isAccessible = true }
        val previous = gatt.get(ble)
        gatt.set(ble, gateway)
        try {
            return RobotBle::class.java.getDeclaredMethod("autonomousLegsAvailable").apply { isAccessible = true }.invoke(ble) as Boolean
        } finally { gatt.set(ble, previous) }
    }

    @Test fun `gateway tracking available on a LUBA 2 at 0`() {
        val bench = bench(name = "Luba-VS-SIM01")
        bench.connect(); bench.advance(1000)
        assertTrue(bench.legsAvailable(bench.autonomousGateway()))
    }

    @Test fun `gateway tracking disabled while a resting value is learnt, back once it is forgotten`() {
        val bench = bench()
        bench.sim.sensorLow = 1
        bench.connect(); bench.advance(1000)
        assertEquals(1, bench.state.bumperRest)
        val gateway = bench.autonomousGateway()
        assertFalse(bench.legsAvailable(gateway))
        bench.sim.sensorLow = 0
        bench.advance(1500)
        assertEquals(0, bench.state.bumperRest)
        assertTrue(bench.legsAvailable(gateway))
    }
}
