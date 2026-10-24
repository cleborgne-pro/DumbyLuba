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
        // Manual mowing mode with blades OFF (the only mode where the LUBA 2 publishes the bumper), never the blades.
        assertTrue(bench.sim.trace.any { it.second == "manual" })
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        val released = bench.mark()
        bench.ble.stopManual()
        assertFalse(bench.state.manualActive)
        bench.advance(2000)
        // Release: stop, then manual mode left.
        assertEquals(listOf("stop", "blades-off"), bench.labelsAfter(released))
        assertEquals(11, bench.sim.robotState)
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

    /** 2026-10-22: a bumper contact while driving opens the obstacle sheet (contact logged, object in front of the bumper). */
    @Test fun `bumper contact while driving, obstacle sheet opened then kept`() {
        bench.connect()
        bench.sim.obstacle = Point(4.8, 3.0) to 0.15
        bench.ble.beginManualGesture()
        hold(250, 0, 4000)
        assertEquals(1, bench.sim.contacts)
        bench.until(5000, "contact sheet") { bench.state.manualContactId != null }
        assertFalse(bench.state.manualActive)
        val contact = bench.state.difficulties.events.single { it.id == bench.state.manualContactId }
        assertEquals("pilotage", contact.source)
        assertEquals(fr.luba.poc.difficulty.DifficultyKind.CONTACT, contact.kind)
        // Object estimated in front of the bumper, near the real one.
        assertTrue("estimated ${contact.point}", contact.point.distance(Point(4.8, 3.0)) < 0.25)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        // Kept with its size from the sheet, then closed.
        val saved = fr.luba.poc.map.ObstacleSelection.fromEncounter(contact, emptyList(), fr.luba.poc.obstacles.ObstacleKind.POLE,
            0.15, 0.5, "kept", 0L, "")
        bench.ble.saveObstacle(saved)
        bench.ble.closeManualContact()
        assertEquals(null, bench.state.manualContactId)
        assertEquals(0.15, bench.state.obstacles.single().radiusM, 1e-9)
    }

    @Test fun `collision lock while driving opens the sheet, the late 1014 refines the same contact`() {
        bench.connect()
        bench.ble.beginManualGesture()
        assertTrue(hold(-250, 0, 1000))
        bench.sim.selfCheck = 25
        hold(-250, 0, 1500)
        assertFalse(bench.state.manualActive)
        val id = requireNotNull(bench.state.manualContactId) { bench.dump() }
        val first = bench.state.difficulties.events.single { it.id == id }
        assertEquals(null, first.code)
        // Reversing: object behind the chassis.
        assertTrue("estimated ${first.point} for robot ${bench.sim.position}", first.point.x < bench.sim.position.x - 0.3)
        bench.sim.injectError(1014)
        bench.until(2500, "refined contact") { bench.state.difficulties.events.single { it.id == id }.code == 1014 }
        assertEquals(id, bench.state.manualContactId)
        assertEquals(1, bench.state.difficulties.events.count { it.kind == fr.luba.poc.difficulty.DifficultyKind.CONTACT })
    }

    /** 2026-10-20: beyond the mapped zones, self-check 25 does not prevent driving freely (blades OFF). */
    @Test fun `self-check 25 beyond the mapped zones, joystick still allowed`() {
        bench.installMap()
        bench.sim.place(Point(11.0, 3.0), 0.0)
        bench.connect()
        assertTrue(bench.state.robotMap.shapes.isNotEmpty())
        bench.sim.selfCheck = 25
        bench.advance(1500)
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(-250, 0, 1500))
        assertTrue(bench.state.manualActive)
        assertTrue("robot moved back towards the zone: ${bench.sim.position}", bench.sim.position.x < 10.9)
        assertTrue(bench.state.events.any { it.contains("hors des zones") || it.contains("beyond the mapped") })
        // Bumper pressed: stop as before.
        bench.sim.sensorLow = 1
        hold(-250, 0, 1500)
        assertFalse(bench.state.manualActive)
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
        // Robot in manual mode (bumper detection): the STOP also leaves it, blades OFF.
        assertEquals(listOf("blades-off", "stop"), bench.labelsSince(since))
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

    /** 2026-10-22: robot stuck under self-check 41 (weak satellite signal): the joystick brings it under open sky, blades OFF. */
    @Test fun `self-check 41 weak satellite signal, joystick still allowed, bumper still stops`() {
        bench.connect()
        bench.sim.selfCheck = 41
        bench.advance(1500)
        val start = bench.sim.position
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(250, 0, 1500))
        assertTrue(bench.state.manualActive)
        assertTrue("robot moved: ${bench.sim.position}", bench.sim.position.distance(start) > 0.1)
        assertFalse(bench.sim.bladesOn)
        bench.sim.sensorLow = 1
        hold(250, 0, 1500)
        assertFalse(bench.state.manualActive)
    }

    /** Lock after STOP (11): the joystick stays refused. */
    @Test fun `self-check 11, joystick refused`() {
        bench.connect()
        bench.sim.selfCheck = 11
        bench.advance(1500)
        bench.ble.beginManualGesture()
        assertFalse(hold(250, 0, 1000))
        assertFalse(bench.state.manualActive)
    }

    /** 2026-10-22: gentle driving stopped with "Wheels commanded without progress" (robot slower than nominal at low speed). */
    @Test fun `gentle joystick on a robot slow at low speed, no false stop`() {
        bench.connect()
        bench.sim.friction = 120
        bench.advance(1500)
        val start = bench.sim.position
        bench.ble.beginManualGesture()
        assertTrue(bench.state.manualStatus, hold(150, 0, 12_000))
        assertTrue(bench.state.manualActive)
        assertTrue(bench.state.events.none { it.contains("sans progression") })
        assertTrue("robot moved: ${bench.sim.position}", bench.sim.position.distance(start) > 0.3)
    }

    /** 2026-10-22: contact while driving not detected: the 1014 is only given on a read of the error history. */
    @Test fun `contact while driving detected through the error history`() {
        bench.connect()
        bench.sim.errorsOnQuery = true
        bench.sim.obstacle = Point(4.0, 3.0).let { Point(it.x + 0.9, it.y) } to 0.15
        bench.advance(1500)
        bench.ble.beginManualGesture()
        hold(250, 0, 8_000)
        assertTrue(bench.sim.contacts > 0)
        // 2026-10-22 16:48 (Fold log): driven in state 11, the LUBA 2 never published the contact; manual mode now.
        assertTrue(bench.sim.trace.any { it.second == "manual" })
        bench.until(5_000, "contact recorded") { bench.state.manualContactId != null }
        assertFalse(bench.state.manualActive)
        assertTrue(bench.state.difficulties.events.any { it.kind == fr.luba.poc.difficulty.DifficultyKind.CONTACT })
    }
}
