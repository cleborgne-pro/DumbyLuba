package fr.luba.poc.ble

import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.protocol.ModelTrial
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DumbyLuba#2 end to end: `cut_knife_height` actually received by [SimRobot] in every `mow_ctrl_by_hand`.
 * Commands that must leave the deck where it is (joystick, release, STOP, moves with blades stopped) carry the reported
 * height; a height setting, blade test, route or cutting test carries its own target, never the reported height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleHoldHeightTest {
    private var current: RobotBench? = null
    private val bench: RobotBench get() = requireNotNull(current)

    @After fun tearDown() { current?.close(); ModelTrial.resetForTests() }

    private fun bench(start: Point = Point(4.0, 3.0), heading: Double = 0.0, name: String = "Luba-VS-SIM01"): RobotBench =
        RobotBench(start, heading, name = name).also { current = it }

    /** Finger held on the joystick: input refreshed every 100 ms for [ms]. */
    private fun hold(linear: Int, angular: Int, ms: Long): Boolean {
        var accepted = true
        var left = ms
        while (left > 0) { accepted = bench.ble.manualInput(linear, angular) && accepted; bench.advance(100); left -= 100 }
        return accepted
    }

    /** Joystick gesture then release; returns the heights received from the gesture to 1.5 s after the release. */
    private fun gesture(linear: Int = 200, ms: Long = 2000): List<Int> {
        val since = bench.now
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(linear, 0, ms))
        bench.ble.stopManual()
        bench.advance(1500)
        assertFalse(bench.state.manualActive)
        return bench.sim.handHeightsSince(since)
    }

    // ───── 1. Joystick, release and STOP: whatever the reported height ─────

    @Test fun `joystick keeps the reported height at the ends of the range and on an H version`() {
        bench()
        bench.sim.heightMm = 25
        bench.connect()
        // 25 and 70 mm: ends of the standard range; 85 mm: H version (range raised from the report).
        for (mm in listOf(25, 70, 85)) {
            bench.sim.heightMm = mm
            bench.until(3000, "height $mm reported") { bench.state.telemetry.bladeHeightMm == mm }
            val heights = gesture()
            assertTrue("$mm: $heights", heights.size >= 3)
            assertEquals("$mm: $heights", setOf(mm), heights.toSet())
            assertEquals(mm, bench.sim.heightMm)
        }
        // STOP while driving at 85 mm: blades OFF at 85 mm.
        val stopped = bench.now
        bench.ble.beginManualGesture()
        assertTrue(hold(200, 0, 1500))
        bench.ble.stop()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val atStop = bench.sim.handHeightsSince(stopped)
        assertEquals(atStop.toString(), setOf(85), atStop.toSet())
    }

    /** The robot of the issue: LUBA 3 AWD (`Luba-VA`) during the model trial, deck at 70 mm. */
    @Test fun `LUBA 3 during the model trial, joystick keeps a 70 mm deck`() {
        bench(name = "Luba-VA-SIM01")
        bench.sim.heightMm = 70
        bench.connect()
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 70 }
        assertNull(bench.ble.armModelTrial())
        val heights = gesture(linear = ModelTrial.MAX_LINEAR)
        assertTrue(heights.toString(), heights.size >= 3)
        assertEquals(heights.toString(), setOf(70), heights.toSet())
        assertEquals(70, bench.sim.heightMm)
    }

    // ───── 2. Height setting in progress: its target, not the reported height ─────

    @Test fun `STOP during a pending height setting sends its target, not the reported height`() {
        bench()
        bench.connect()
        // Deck stuck at 60 mm: the reported height never reaches the 40 mm target.
        bench.sim.freezeHeight = true
        val since = bench.now
        bench.ble.setBladeHeight(40)
        assertTrue(bench.state.bladeHeightPending)
        bench.until(5000, "manual mode for the height") { bench.sim.robotState == 20 }
        bench.advance(1500)
        assertEquals(60, bench.state.telemetry.bladeHeightMm)
        val stopped = bench.now
        bench.ble.stop()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val atStop = bench.sim.handHeightsSince(stopped)
        assertTrue("Blades OFF on STOP: $atStop", atStop.isNotEmpty())
        assertEquals(atStop.toString(), setOf(40), atStop.toSet())
        val all = bench.sim.handHeightsSince(since)
        assertEquals(all.toString(), setOf(40), all.toSet())
    }

    @Test fun `STOP during a blade test sends the test height, not the reported height`() {
        bench()
        bench.sim.heightMm = 35
        bench.connect()
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 35 }
        bench.sim.freezeHeight = true
        val since = bench.now
        bench.ble.prepareBladeStop(50)
        bench.until(5000, "blade stop verified") { bench.state.bladeStatus.contains("État lames OFF reçu") }
        bench.ble.startBladeTest(50)
        assertTrue(bench.state.bladeStatus, bench.state.bladeTestActive)
        bench.until(5000, "blades running") { bench.sim.bladesOn }
        bench.advance(1000)
        assertEquals(35, bench.state.telemetry.bladeHeightMm)
        val stopped = bench.now
        bench.ble.stop()
        bench.until(3000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val atStop = bench.sim.handHeightsSince(stopped)
        assertTrue("Blades OFF on STOP: $atStop", atStop.isNotEmpty())
        assertEquals(atStop.toString(), setOf(50), atStop.toSet())
        val all = bench.sim.handHeightsSince(since)
        assertEquals(all.toString(), setOf(50), all.toSet())
        assertFalse(bench.sim.bladesOn)
    }

    /** Route that sets its height (deck stuck at 60 mm, route at 40 mm): the route and its STOP carry 40 mm. */
    @Test fun `STOP while a route sets its height sends the route height`() {
        bench()
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.installMap()
        bench.connect()
        bench.sim.freezeHeight = true
        val since = bench.now
        assertTrue(bench.dump(), bench.ble.startRobotRoute(bench.mowingEngine(), cutting = true, heightMm = 40))
        bench.until(90_000, "blades running") { bench.sim.bladesOn }
        bench.advance(2000)
        assertEquals(60, bench.state.telemetry.bladeHeightMm)
        val stopped = bench.now
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val atStop = bench.sim.handHeightsSince(stopped)
        assertTrue("Blades OFF on STOP: $atStop", atStop.isNotEmpty())
        assertEquals(atStop.toString(), setOf(40), atStop.toSet())
        val all = bench.sim.handHeightsSince(since)
        assertEquals(all.toString(), setOf(40), all.toSet())
        assertFalse(bench.sim.bladesOn)
    }

    // ───── 3. Route and cutting test: the chosen height, never the reported one ─────

    @Test fun `mowing route sends its chosen height, not the reported one`() {
        bench()
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.sim.heightMm = 35
        bench.installMap()
        bench.connect()
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 35 }
        val since = bench.now
        assertTrue(bench.dump(), bench.ble.startRobotRoute(bench.mowingEngine(), cutting = true, heightMm = 55))
        bench.until(90_000, "cutting in progress") { bench.sim.bladesOn && bench.sim.speed > 0.05 && !bench.sim.charging }
        bench.advance(5000)
        assertEquals(55, bench.sim.heightMm)
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val all = bench.sim.handHeightsSince(since)
        assertTrue(all.toString(), all.size >= 3)
        assertEquals(all.toString(), setOf(55), all.toSet())
    }

    @Test fun `cutting test while moving sends its chosen height, not the reported one`() {
        bench(Point(5.0, 2.0), 90.0)
        bench.sim.heightMm = 35
        bench.installMap()
        bench.connect()
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 35 }
        val since = bench.now
        bench.ble.startBladeMoveTest(radiusM = 2.0, heightMm = 45, maxDurationS = 120)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(60_000, "blades running") { bench.sim.bladesOn }
        bench.until(3 * 60_000, "end of test") { !bench.state.navTest.running }
        bench.until(10_000, "blades stopped") { !bench.sim.bladesOn }
        bench.advance(2000)
        val all = bench.sim.handHeightsSince(since)
        assertTrue(all.toString(), all.size >= 3)
        assertEquals(all.toString(), setOf(45), all.toSet())
        assertEquals(45, bench.sim.heightMm)
    }

    // ───── 4. Unknown or out-of-range height: fallback without exception ─────

    @Test fun `unknown or out-of-range reported height, joystick falls back to 60 mm`() {
        bench()
        // 0 mm: outside the protocol bound, never decoded as a height.
        bench.sim.heightMm = 0
        bench.connect()
        bench.advance(2000)
        assertNull(bench.state.telemetry.bladeHeightMm)
        val unknown = gesture()
        assertTrue(unknown.toString(), unknown.isNotEmpty())
        assertEquals(unknown.toString(), setOf(60), unknown.toSet())
        // 22 mm: decoded, but below the 25-70 mm range of the LUBA 2 standard version.
        bench.sim.heightMm = 22
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 22 }
        val below = gesture()
        assertTrue(below.toString(), below.isNotEmpty())
        assertEquals(below.toString(), setOf(60), below.toSet())
        // 150 mm: outside the protocol bound, ignored; the command stays within the range.
        bench.sim.heightMm = 150
        bench.advance(2000)
        val above = gesture()
        assertTrue(above.toString(), above.isNotEmpty())
        assertTrue(above.toString(), above.all { it in bench.state.cutHeights })
        assertTrue(bench.state.ready)
    }

    @Test fun `LUBA mini during the model trial, its own range and the fallback`() {
        bench(name = "Luba-MN-SIM01")
        bench.sim.heightMm = 0
        bench.connect()
        bench.advance(2000)
        assertNull(bench.ble.armModelTrial())
        // Unknown: 60 mm, inside the 20-65 mm range of the mini.
        val unknown = gesture(linear = ModelTrial.MAX_LINEAR)
        assertTrue(unknown.toString(), unknown.isNotEmpty())
        assertEquals(unknown.toString(), setOf(60), unknown.toSet())
        // 20 mm: outside the LUBA 2 range but inside the mini one, kept.
        bench.sim.heightMm = 20
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 20 }
        val low = gesture(linear = ModelTrial.MAX_LINEAR)
        assertTrue(low.toString(), low.isNotEmpty())
        assertEquals(low.toString(), setOf(20), low.toSet())
        assertEquals(20, bench.sim.heightMm)
    }

    @Test fun `go-to-point move with an unknown height starts and falls back to 60 mm`() {
        bench(Point(5.0, 2.0), 90.0)
        bench.sim.heightMm = 0
        bench.installMap()
        bench.connect()
        bench.advance(2000)
        assertNull(bench.state.telemetry.bladeHeightMm)
        val since = bench.now
        bench.ble.startPointNavigation(Point(6.0, 4.5))
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        bench.advance(2000)
        val heights = bench.sim.handHeightsSince(since)
        assertTrue(heights.isNotEmpty())
        assertEquals(heights.toString(), setOf(60), heights.toSet())
    }

    // ───── 5. Moves with blades stopped: the reported height ─────

    @Test fun `obstacle bypass keeps the reported deck height`() {
        bench(Point(5.0, 2.0), 90.0)
        bench.sim.heightMm = 40
        bench.installMap()
        bench.connect()
        bench.until(3000, "height reported") { bench.state.telemetry.bladeHeightMm == 40 }
        bench.ble.saveObstacle(LocalObstacle("poteau", ObstacleKind.POLE, 5.0, 3.6, 0.1, 0.3))
        val since = bench.now
        bench.ble.startObstacleCourse("poteau")
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "end of course") { !bench.state.navTest.running }
        bench.advance(2000)
        val heights = bench.sim.handHeightsSince(since)
        assertTrue(heights.isNotEmpty())
        assertEquals(heights.toString(), setOf(40), heights.toSet())
        assertEquals(40, bench.sim.heightMm)
    }
}
