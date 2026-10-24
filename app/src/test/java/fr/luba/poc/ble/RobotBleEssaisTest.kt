package fr.luba.poc.ble

import fr.luba.poc.diagnostics.CampaignConfig
import fr.luba.poc.diagnostics.MotorWatchdogResult
import fr.luba.poc.map.Point
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
 * End-to-end moves and supervised tests: go-to-point move (and contact on the way), bumper test,
 * command cutoff (motor watchdog), unlock, back-off before resume, Mammotion task, BLE campaign.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleEssaisTest {
    private val bench = RobotBench(Point(5.0, 2.0), 90.0)
    @After fun tearDown() = bench.close()

    private fun connectedInZone() { bench.installMap(); bench.connect() }

    // ───── Go-to-point move ─────

    @Test fun `go-to-point move with blades stopped up to the destination`() {
        connectedInZone()
        bench.ble.previewNavTest(2.0)
        assertTrue(bench.state.navTest.preview, bench.state.navTest.preview.startsWith("Aperçu"))
        bench.ble.startPointNavigation(Point(6.0, 4.5))
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        assertTrue(bench.sim.position.distance(Point(6.0, 4.5)) < 0.35)
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        // Manual mode with blades OFF held for the bumper, left on arrival.
        assertTrue(bench.sim.trace.any { it.second == "manual" })
        bench.advance(2000)
        assertFalse(bench.sim.manual)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `go-to-point move interrupted, destination kept then resumed until arrival`() {
        connectedInZone()
        val target = Point(6.0, 4.5)
        bench.ble.startPointNavigation(target)
        assertEquals(target, bench.state.pointResume?.target)
        assertNull("In progress: no reason (app closed if it stops there)", bench.state.pointResume?.interruption)
        bench.until(30_000, "moving") { bench.sim.speed > 0.05 }
        bench.ble.stopNavTest("STOP manuel", userInitiated = true)
        val saved = requireNotNull(bench.state.pointResume)
        assertEquals("STOP manuel", saved.interruption)
        // Saved to disk (write off the main thread): read back after an app restart.
        val store = fr.luba.poc.simulation.PointResumeStore(bench.app.filesDir)
        bench.awaitReal(5000, "reason saved") { store.read(bench.device.address) == saved }
        bench.until(15_000, "robot ready after the stop") { bench.state.telemetry.robotState == 11 }
        bench.ble.startPointNavigation(saved.target)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        assertNull(bench.state.pointResume)
        bench.awaitReal(5000, "save cleared") { store.read(bench.device.address) == null }
    }

    @Test fun `go-to-point move from the base leaves straight to the link point before turning`() {
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        connectedInZone()
        assertTrue(bench.sim.charging)
        val traceMark = bench.mark(); val trackMark = bench.sim.track.size
        val target = Point(6.0, 4.5)
        bench.ble.startPointNavigation(target)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        // Base (1.5, 3.0) docking towards −x: link point at (3.0, 3.0).
        val exit = bench.state.navTest.plannedExit
        assertEquals(2, exit.size)
        assertEquals(3.0, exit.last().x, 1e-6); assertEquals(3.0, exit.last().y, 1e-6)
        assertTrue(bench.state.navTest.planned.first().distance(exit.last()) < 1e-6)
        bench.until(60_000, "link point reached") { bench.sim.position.x >= 2.9 }
        // Straight exit: no rotation command and the robot stays in the 10 cm corridor of the base axis.
        val exitMoves = bench.labelsAfter(traceMark).filter { it.startsWith("move") }
        assertTrue(exitMoves.toString(), exitMoves.isNotEmpty() && exitMoves.all { it.split(" ")[2].toDouble() == 0.0 })
        assertTrue(bench.sim.track.drop(trackMark).all { kotlin.math.abs(it.y - 3.0) <= 0.1 })
        assertFalse(bench.sim.charging)
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        assertTrue(bench.dump(), bench.sim.position.distance(target) < 0.35)
        assertTrue(bench.state.navTest.plannedExit.isEmpty())
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
    }

    @Test fun `go-to-point move interrupted then discarded`() {
        connectedInZone()
        bench.ble.startPointNavigation(Point(6.5, 5.0))
        bench.until(30_000, "moving") { bench.sim.speed > 0.05 }
        bench.ble.stopNavTest(userInitiated = true)
        assertNotNull(bench.state.pointResume)
        bench.ble.discardPointResume()
        assertNull(bench.state.pointResume)
    }

    @Test fun `go-to-point move stopped by the user then refused too far beyond the map`() {
        connectedInZone()
        bench.ble.startPointNavigation(Point(6.5, 5.0))
        bench.until(30_000, "moving") { bench.sim.speed > 0.05 }
        val mark = bench.mark()
        bench.ble.stopNavTest(userInitiated = true)
        assertFalse(bench.state.navTest.running)
        assertEquals("ABORTED", bench.state.navTest.outcome)
        bench.advance(3000)
        assertTrue(bench.labelsAfter(mark).none { it.startsWith("move") })
        assertEquals(0.0, bench.sim.speed, 0.005)
        bench.ble.startPointNavigation(Point(60.0, 60.0))
        assertFalse(bench.state.navTest.running)
        assertTrue(bench.state.navTest.status, bench.state.navTest.status.startsWith("Navigation refusée") &&
            bench.state.navTest.status.contains("hors carte"))
    }

    @Test fun `go-to-point move beyond the mapped zone reaches its destination through the dotted section`() {
        connectedInZone()
        // Zone 8 × 6 m: destination 2 m beyond its east edge.
        val target = Point(10.0, 3.0)
        bench.ble.startPointNavigation(target)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        val offMap = bench.state.navTest.plannedOffMap
        assertEquals(1, offMap.size)
        assertEquals(target, offMap.single().last())
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        assertTrue(bench.dump(), bench.sim.position.distance(target) < 0.35)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    /** 2026-10-20: self-check 25 published once the robot crosses the zone border: the move goes on to its point. */
    @Test fun `self-check 25 beyond the mapped zone does not stop the go-to-point move`() {
        connectedInZone()
        val target = Point(10.0, 3.0)
        bench.ble.startPointNavigation(target)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "border crossed") { bench.sim.position.x > 8.2 || !bench.state.navTest.running }
        bench.sim.selfCheck = 25
        bench.until(5 * 60_000, "destination") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
        assertTrue(bench.dump(), bench.sim.position.distance(target) < 0.35)
        // A new move from beyond the map starts despite the 25, back inside the zone.
        bench.advance(2000)
        bench.ble.startPointNavigation(Point(5.0, 3.0))
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.sim.selfCheck = 0
        bench.until(5 * 60_000, "back in the zone") { !bench.state.navTest.running }
        assertEquals(bench.dump(), "PASS", bench.state.navTest.outcome)
    }

    @Test fun `contact during a go-to-point move, back-off then new path`() {
        connectedInZone()
        bench.ble.setObstaclePhotoEnabled(false)
        bench.sim.obstacle = Point(5.0, 3.6) to 0.2
        bench.ble.startPointNavigation(Point(5.0, 5.2))
        bench.until(2 * 60_000, "contact") { bench.sim.contacts > 0 }
        val mark = bench.mark()
        bench.until(60_000, "back-off") { bench.labelsAfter(mark).any { it.startsWith("move -") } }
        bench.until(5 * 60_000, "end of move") { !bench.state.navTest.running }
        // Destination reached via the new path, or safety stop by the footprint guard (pivot near the
        // obstacle's margin): never a second contact nor movement after the stop.
        assertTrue(bench.dump(), bench.state.navTest.outcome == "PASS" && bench.sim.position.distance(Point(5.0, 5.2)) < 0.35 ||
            bench.state.navTest.outcome == "ABORTED" && bench.state.navTest.status.contains("Emprise"))
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        // Obstacle saved in front of the position where the robot stopped at contact, bypassed by the new path.
        val touched = bench.state.obstacles.single()
        assertEquals(3.6, touched.y, 0.15)
        assertEquals(1, bench.sim.contacts)
    }

    // ───── Bumper ─────

    /** 2026-10-22: the approach no longer waits for a stationary contact test; a contact during it validates detection. */
    @Test fun `approach without prior stationary test, stop on contact validates detection`() {
        connectedInZone()
        assertFalse(bench.state.teach.contactValidated)
        bench.sim.obstacle = Point(5.0, 2.7) to 0.15
        bench.sim.friction = 0
        bench.ble.startObstacleTest(approach = true)
        assertTrue(bench.state.teach.status, bench.state.teach.running)
        bench.until(40_000, "approach contact") { !bench.state.teach.running }
        assertEquals(bench.dump(), "CONTACT_DETECTED", bench.state.teach.outcome)
        assertTrue(bench.state.teach.contactValidated)
        assertNotNull(bench.state.teach.proposal)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    /**
     * 2026-10-22: the slow approach was interrupted as a problem. Realistic robot: low-speed dead band (60 units), 1014
     * only on a read of the error history, collision lock (25) published with the contact.
     */
    @Test fun `slow approach on a realistic robot, collision lock then 1014, contact detected`() {
        connectedInZone()
        bench.sim.obstacle = Point(5.0, 2.7) to 0.15
        bench.sim.friction = 60
        bench.sim.errorsOnQuery = true
        bench.sim.latchOnContact = true
        bench.ble.startObstacleTest(approach = true)
        bench.until(40_000, "approach contact") { !bench.state.teach.running }
        assertEquals(bench.state.teach.status, "CONTACT_DETECTED", bench.state.teach.outcome)
        assertTrue(bench.state.teach.contactValidated)
        assertNotNull(bench.state.teach.proposal)
        assertTrue(bench.sim.contacts > 0)
    }

    /** Soft object, no 1014: the robot stops in front of it, the obstacle is proposed without validating the bumper. */
    @Test fun `slow approach stopped by an object without bumper code, obstacle proposed`() {
        connectedInZone()
        bench.sim.obstacle = Point(5.0, 2.7) to 0.15
        bench.sim.friction = 60
        bench.sim.errorsOnQuery = true
        bench.sim.bumperReports = false
        bench.ble.startObstacleTest(approach = true)
        bench.until(40_000, "approach halt") { !bench.state.teach.running }
        assertEquals(bench.state.teach.status, "BLOCKED_AHEAD", bench.state.teach.outcome)
        assertFalse(bench.state.teach.contactValidated)
        assertNotNull(bench.state.teach.proposal)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    /** Robot that does not move at the approach speed: interrupted with a clear reason. */
    @Test fun `slow approach of a robot that does not move, interrupted with the reason`() {
        connectedInZone()
        bench.sim.friction = 200
        bench.ble.startObstacleTest(approach = true)
        bench.until(40_000, "end") { !bench.state.teach.running }
        assertEquals("ABORTED", bench.state.teach.outcome)
        assertTrue(bench.state.teach.status, bench.state.teach.status.contains("n'a pas avancé"))
        assertNull(bench.state.teach.proposal)
    }

    @Test fun `obstacle pointed at in front of the robot, without contact nor command`() {
        connectedInZone()
        bench.advance(1000)
        val mark = bench.mark()
        bench.ble.proposeObstacleAhead()
        val proposal = requireNotNull(bench.state.teach.proposal) { bench.state.teach.status }
        val ahead = fr.luba.poc.obstacles.ManualContact.defaultAheadM(fr.luba.poc.obstacles.ObstacleKind.OTHER.defaultRadiusM)
        assertEquals(ahead, requireNotNull(proposal.aheadM), 1e-9)
        // Robot at (5, 2) heading north: obstacle against the bumper, on the north.
        assertEquals(bench.state.telemetry.x!!, proposal.estimated.x, 0.05)
        assertEquals(bench.state.telemetry.y!! + ahead, proposal.estimated.y, 0.05)
        assertTrue(bench.labelsAfter(mark).toString(), bench.labelsAfter(mark).none { it.startsWith("move") || it == "manual" })
        bench.ble.dismissTeachProposal()
        assertNull(bench.state.teach.proposal)
    }

    @Test fun `stationary bumper test validated then approach`() {
        connectedInZone()
        bench.ble.startObstacleTest(approach = false)
        assertTrue(bench.state.teach.running)
        bench.advance(2000)
        bench.sim.injectError(1014)
        bench.until(3000, "contact") { !bench.state.teach.running }
        assertEquals("CONTACT_DETECTED", bench.state.teach.outcome)
        assertTrue(bench.state.teach.contactValidated)
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") && it.second != "move 0 0" })
        // Slow approach, stop on contact against a real object.
        bench.sim.obstacle = Point(5.0, 2.7) to 0.15
        bench.advance(2000)
        bench.ble.startObstacleTest(approach = true)
        assertTrue(bench.state.teach.status, bench.state.teach.running)
        bench.until(40_000, "approach contact") { !bench.state.teach.running }
        assertEquals(bench.dump(), "CONTACT_DETECTED", bench.state.teach.outcome)
        assertNotNull(bench.state.teach.proposal)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `bumper test without contact, ends after 30 s`() {
        connectedInZone()
        bench.ble.startObstacleTest(approach = false, manualMode = true)
        bench.until(40_000, "end of test") { !bench.state.teach.running }
        assertEquals("NOT_DETECTED", bench.state.teach.outcome)
        assertFalse(bench.sim.bladesOn)
        bench.ble.recordBumperBeep()
    }

    // ───── Command cutoff ─────

    @Test fun `command cutoff, robot stops on its own`() {
        connectedInZone()
        bench.ble.startSilenceTest(100)
        assertTrue(bench.state.silenceTest.status, bench.state.silenceTest.running)
        bench.until(60_000, "verdict") { !bench.state.silenceTest.running }
        val result = requireNotNull(bench.state.motorWatchdog)
        assertTrue(result.detail, result.outcome != MotorWatchdogResult.INCONCLUSIVE)
        assertEquals(0.0, bench.sim.speed, 0.005)
        assertEquals("stop", bench.sim.trace.last().second)
    }

    @Test fun `command cutoff, robot keeps going, STOP by the app`() {
        connectedInZone()
        bench.sim.motorWatchdogMs = null
        bench.ble.startSilenceTest(100)
        bench.until(60_000, "verdict") { !bench.state.silenceTest.running }
        val result = requireNotNull(bench.state.motorWatchdog)
        assertTrue(result.detail, result.outcome != MotorWatchdogResult.INCONCLUSIVE)
        bench.advance(2000)
        assertEquals("STOP sent: the robot is stopped", 0.0, bench.sim.speed, 0.005)
    }

    @Test fun `command cutoff refused without clearance, interrupted on release`() {
        bench.sim.place(Point(5.0, 5.2), 90.0)
        connectedInZone()
        bench.ble.startSilenceTest(100)
        assertFalse(bench.state.silenceTest.running)
        assertTrue(bench.state.silenceTest.status, bench.state.silenceTest.status.startsWith("Essai refusé"))
        bench.sim.place(Point(5.0, 2.0), 90.0)
        bench.advance(2000)
        bench.ble.startSilenceTest(100)
        bench.until(5000, "moving forward") { bench.sim.speed > 0.02 }
        bench.ble.stopSilenceTest()
        assertFalse(bench.state.silenceTest.running)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    // ───── Locks and back-off ─────

    @Test fun `collision lock, unlock back-off along a free line`() {
        connectedInZone()
        bench.sim.selfCheck = 25
        bench.sim.latchRelease = true
        bench.sim.obstacle = Point(5.0, 2.5) to 0.1
        bench.advance(2000)
        bench.ble.unlockAfterStop()
        assertTrue(bench.state.unlockPending)
        bench.until(60_000, "unlock") { !bench.state.unlockPending }
        assertTrue(bench.state.unlockStatus, bench.state.unlockStatus.startsWith("Robot déverrouillé"))
        assertTrue(bench.sim.trace.any { it.second.startsWith("move -") })
        assertTrue(bench.sim.position.y < 1.9)
    }

    @Test fun `back-off before resume, reversing to a safe position`() {
        bench.sim.place(Point(4.0, 0.55), 270.0)
        connectedInZone()
        val engine = bench.mowingEngine()
        bench.ble.backOffBeforeResume(engine)
        bench.until(60_000, "back-off") { !bench.state.backOffPending }
        assertTrue(bench.state.backOffStatus, bench.state.backOffStatus.startsWith("Robot reculé") || bench.state.backOffStatus.startsWith("Déblocage impossible"))
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        bench.ble.backOffBeforeResume(engine)
        bench.ble.stopBackOff()
        // Wait for the stop to be applied rather than a fixed delay (the back-off thread may lag under load).
        bench.until(10_000, "back-off stopped") { !bench.state.backOffPending }
        assertFalse(bench.state.backOffPending)
    }

    @Test fun `Mammotion task stopped, then refused without a task`() {
        bench.sim.idleState = 13
        connectedInZone()
        bench.ble.stopMammotionTask()
        assertTrue(bench.state.taskStopPending)
        bench.until(20_000, "task stopped") { !bench.state.taskStopPending }
        assertEquals("Tâche arrêtée : READY et lames OFF reçus", bench.state.taskStopStatus)
        val labels = bench.sim.trace.map { it.second }
        assertTrue(labels.toString(), labels.containsAll(listOf("task-pause", "task-cancel", "blades-off", "stop")))
        bench.ble.stopMammotionTask()
        assertEquals("Aucune tâche Mammotion active ou en pause observée", bench.state.taskStopStatus)
    }

    // ───── Diagnostic campaign ─────

    @Test fun `stationary BLE campaign complete`() {
        bench.connect()
        bench.ble.startCampaign(CampaignConfig(durationMinutes = 1))
        assertTrue(bench.state.status, bench.state.campaign.running)
        bench.until(10 * 60_000, "end of campaign") { !bench.state.campaign.running }
        assertEquals(bench.state.campaign.summary + "\n" + bench.state.campaign.results.joinToString("\n"), "PASS BLE ONLY", bench.state.campaign.outcome)
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
        assertTrue(bench.links.size >= 3)
    }

    @Test fun `campaign interrupted by STOP`() {
        bench.connect()
        bench.ble.startCampaign(CampaignConfig(durationMinutes = 1))
        bench.advance(10_000)
        bench.ble.stop()
        assertEquals("ABORTED", bench.state.campaign.outcome)
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
    }
}
