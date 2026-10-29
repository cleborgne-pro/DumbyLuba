package fr.luba.poc.ble

import fr.luba.poc.simulation.PositioningGrace
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.history.MowingOutcome
import fr.luba.poc.map.Point
import fr.luba.poc.mowing.WorkWindow
import fr.luba.poc.mowing.WorkWindowSetting
import fr.luba.poc.simulation.RoutePhase
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
import java.time.LocalTime

/**
 * Incidents during real mowing: bumper contact against a real object (back-off, bypass, resume),
 * transient protection, lock, interruption then resume from the saved progress, working hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleIncidentsTest {
    private val bench = RobotBench(Point(5.0, 4.0), 0.0)
    @After fun tearDown() { WorkWindowSetting.set(WorkWindow()); bench.close() }

    /** One-hour working window that excludes the current hour. */
    private fun closedWindow(): WorkWindow {
        val hour = LocalTime.now().hour
        val start = if (hour <= 22) (hour + 1) * 60 else 0
        return WorkWindow(enabled = true, startMinutes = start, endMinutes = start + 60)
    }

    @Test fun `bumper contact against an object, back-off, bypass then mowing complete`() {
        // Real 50 cm object in the middle of the zone, absent from the map; collision lock published on contact.
        bench.sim.obstacle = Point(5.0, 3.6) to 0.25
        bench.sim.latchOnContact = true
        bench.sim.latchReleaseDelayMs = 15_000
        bench.ble.setObstaclePhotoEnabled(false)
        val engine = bench.mowingUnderway()
        bench.until(30 * 60_000, "contact") { bench.sim.contacts > 0 }
        val contactAt = bench.mark()
        bench.until(60_000, "back-off") { bench.labelsAfter(contactAt).any { it.startsWith("move -") } }
        // Blades stopped before the back-off.
        val afterContact = bench.labelsAfter(contactAt)
        assertTrue(afterContact.toString(), afterContact.indexOf("blades-off") in 0 until afterContact.indexOfFirst { it.startsWith("move -") })
        val seen = linkedSetOf<String>()
        bench.sim.onTick = { bench.newEvents().forEach { e -> if (e.contains("Protection levée par le robot après le recul")) seen += "levée" } }
        bench.until(5 * 60_000, "bypass") { bench.state.events.any { it.contains("Trajet restant réparé autour de l'obstacle") } }
        bench.until(60_000, "waiting for the lock to clear") { seen.isNotEmpty() }
        assertTrue(bench.state.robotRoute.running)
        bench.until(40 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        bench.assertNativeDockOnlyAtApproach(engine)
        // Contact recorded on the map, complete mowing report with the obstacle counted.
        assertTrue(bench.state.difficulties.events.any { it.kind == DifficultyKind.CONTACT })
        assertTrue((bench.state.mowingReport?.obstacles ?: 0) >= 1)
        assertNotNull(bench.state.teach.proposal)
        assertEquals(0L, bench.sim.selfCheck)
    }

    @Test fun `transient protection while mowing, pause then resume`() {
        bench.mowingUnderway()
        bench.sim.selfCheck = 41
        // Positioning loss: first ridden through (PositioningGrace, 20 s / 5 m), then the usual pause.
        bench.until(5000, "positioning grace") { bench.state.events.any { it.contains("trajet poursuivi quelques secondes") } }
        bench.until(PositioningGrace.MAX_MS + 5000, "pause on protection") { bench.state.events.any { it.contains("attente de sa levée") } }
        assertFalse(bench.sim.bladesOn)
        bench.advance(2000)
        bench.sim.selfCheck = 0
        bench.until(60_000, "resume") { bench.state.events.any { it.contains("Protection levée par le robot") } }
        bench.until(60_000, "blades restarted") { bench.sim.bladesOn }
        assertTrue(bench.state.robotRoute.running)
        bench.ble.stop()
    }

    /** 2026-10-22: weak satellite signal (41) held for two minutes: the mowing waits, blades OFF, then resumes. */
    @Test fun `weak satellite signal held two minutes while mowing, wait then resume`() {
        bench.mowingUnderway()
        bench.sim.selfCheck = 41
        // Positioning loss: first ridden through (PositioningGrace, 20 s / 5 m), then the usual pause.
        bench.until(5000, "positioning grace") { bench.state.events.any { it.contains("trajet poursuivi quelques secondes") } }
        bench.until(PositioningGrace.MAX_MS + 5000, "pause on protection") { bench.state.events.any { it.contains("attente de sa levée") } }
        assertFalse(bench.sim.bladesOn)
        bench.advance(120_000)
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.running)
        assertFalse(bench.sim.bladesOn)
        bench.sim.selfCheck = 10
        bench.until(60_000, "resume") { bench.state.events.any { it.contains("Protection levée par le robot") } }
        bench.until(60_000, "blades restarted") { bench.sim.bladesOn }
        assertTrue(bench.state.robotRoute.running)
        bench.ble.stop()
    }

    @Test fun `lock after STOP while mowing, stop held then unlock`() {
        bench.mowingUnderway()
        bench.sim.selfCheck = 11
        bench.until(5000, "stop") { !bench.state.robotRoute.running }
        assertEquals(RoutePhase.STOPPED, bench.state.robotRoute.phase)
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("protection 11"))
        bench.advance(3000)
        assertFalse(bench.sim.bladesOn)
        val mark = bench.mark()
        bench.ble.unlockAfterStop()
        assertTrue(bench.state.unlockPending)
        bench.until(15_000, "unlock") { !bench.state.unlockPending }
        assertTrue(bench.state.unlockStatus, bench.state.unlockStatus.startsWith("Robot déverrouillé"))
        assertEquals(listOf("stop", "task-cancel"), bench.labelsAfter(mark))
        // Robot locked for good: failure reported, no movement command.
        bench.sim.unlockOnCancel = false
        bench.sim.selfCheck = 11
        bench.advance(2000)
        bench.ble.unlockAfterStop()
        bench.until(30_000, "failure") { !bench.state.unlockPending }
        assertTrue(bench.state.unlockStatus, bench.state.unlockStatus.contains("reste verrouillé"))
        assertTrue(bench.labelsAfter(mark).none { it.startsWith("move") })
    }

    @Test fun `mowing interrupted then resumed from the saved progress`() {
        bench.mowingUnderway()
        bench.advance(60_000)
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val saved = requireNotNull(bench.state.missionCheckpoint)
        assertEquals("STOP manuel", saved.interruption)
        assertTrue(saved.progress > 0.05)
        // Resume refused while disconnected, then with a route re-prepared differently.
        bench.ble.connect(bench.device)
        bench.until(10_000, "reconnection") { bench.state.connection == ConnectionState.Receiving && bench.state.telemetry.x != null }
        assertEquals(saved.startedAt, bench.state.missionCheckpoint?.startedAt)
        assertFalse(bench.ble.resumeRobotRoute(bench.mowingEngine(spacingM = 0.36)))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Trajet re-préparé différent"))
        val engine = bench.mowingEngine()
        assertTrue(bench.dump(), bench.ble.resumeRobotRoute(engine))
        assertEquals(1, bench.state.missionCheckpoint?.resumes)
        assertTrue("Mowing trail kept", bench.state.robotRoute.trail.isNotEmpty())
        bench.until(40 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        assertNull(bench.state.missionCheckpoint)
        val report = requireNotNull(bench.state.mowingReport)
        assertEquals(MowingOutcome.COMPLETE, report.outcome)
        assertEquals(1, report.resumes)
        bench.assertNativeDockOnlyAtApproach(engine)
    }

    @Test fun `blades-off run interrupted then resumed at the point reached, blades still stopped`() {
        bench.mowingUnderway(cutting = false)
        bench.advance(60_000)
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val saved = requireNotNull(bench.state.missionCheckpoint) { "Blades-off run saved (2026-10-16)" }
        assertEquals(fr.luba.poc.simulation.MissionTask.FOLLOW, saved.task)
        assertFalse(saved.cutting)
        assertTrue("progress ${saved.doneMeters} m · ${bench.dump()}", saved.doneMeters > 0.5)
        bench.ble.connect(bench.device)
        bench.until(10_000, "reconnection") { bench.state.connection == ConnectionState.Receiving && bench.state.telemetry.x != null }
        val mark = bench.mark()
        assertTrue(bench.dump(), bench.ble.resumeRobotRoute(bench.resumeEngine()))
        assertEquals(1, bench.state.missionCheckpoint?.resumes)
        bench.until(40 * 60_000, "end of run") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        assertNull(bench.state.missionCheckpoint)
        assertTrue(bench.labelsAfter(mark).none { it == "blades-on" })
    }

    @Test fun `discarding the interrupted mowing`() {
        bench.mowingUnderway()
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        assertNotNull(bench.state.missionCheckpoint)
        bench.ble.discardMissionCheckpoint()
        assertNull(bench.state.missionCheckpoint)
        assertFalse(bench.ble.resumeRobotRoute(bench.mowingEngine()))
        assertTrue(bench.state.robotRoute.status.contains("aucune tonte à reprendre"))
    }

    @Test fun `end of working hours while mowing, return to base and mowing to resume`() {
        val engine = bench.mowingUnderway()
        bench.advance(20_000)
        WorkWindowSetting.set(closedWindow())
        bench.until(5000, "end of working hours announced") { bench.state.status.contains("Fin de la plage horaire") }
        bench.until(10_000, "return") { bench.state.robotRoute.phase == RoutePhase.RETURNING }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Fin de la plage horaire"))
        bench.until(10 * 60_000, "mission stopped on the base") { !bench.state.robotRoute.running }
        assertTrue(bench.sim.charging)
        assertTrue(bench.state.status, bench.state.status.contains("tonte à reprendre"))
        bench.assertNativeDockOnlyAtApproach(engine)
        assertNotNull("Progress kept", bench.state.missionCheckpoint)
        // Outside working hours: no mowing starts.
        bench.advance(3000)
        assertFalse(bench.ble.resumeRobotRoute(bench.resumeEngine()))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("hors de la plage horaire"))
    }

    @Test fun `station re-observed on re-docking, interrupted mowing stays resumable`() {
        // 2026-10-18: the station observed while charging (RTK noise, axis taken during docking) replaced the transmitted
        // base; the re-prepared route changed and the resume was refused ("Trajet re-préparé différent").
        bench.mowingUnderway()
        bench.advance(20_000)
        WorkWindowSetting.set(closedWindow())
        bench.until(10 * 60_000, "mission stopped on the base") { !bench.state.robotRoute.running && bench.sim.charging }
        assertNotNull("Progress kept", bench.state.missionCheckpoint)
        assertEquals(RobotBench.BASE, bench.state.missionCheckpoint?.base?.point)
        // Re-docking 6 cm further: beyond the noise tolerated by the map, the observed station replaces the base.
        bench.sim.placeDocked(Point(RobotBench.BASE.x, RobotBench.BASE.y + 0.06), RobotBench.DOCKED_HEADING)
        bench.until(10_000, "station observed") { bench.base.origin == "Position et axe observés en recharge" }
        WorkWindowSetting.set(WorkWindow())
        assertTrue(bench.dump(), bench.ble.resumeRobotRoute(bench.resumeEngine()))
        assertEquals(1, bench.state.missionCheckpoint?.resumes)
        assertEquals("Starting base kept from one resume to the next", RobotBench.BASE, bench.state.missionCheckpoint?.base?.point)
    }

    @Test fun `outside working hours, robot left outside brought back to base`() {
        bench.sim.place(Point(6.0, 4.5), 90.0)
        bench.installMap()
        bench.connect()
        WorkWindowSetting.set(closedWindow())
        bench.until(10 * 60_000, "automatic return") { bench.sim.charging }
        bench.until(60_000, "end of return") { !bench.state.robotRoute.running }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("contact de charge confirmé"))
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        assertEquals(1, bench.sim.nativeReturnRequests.size)
        // Only once per period outside working hours.
        bench.advance(30_000)
        assertEquals(1, bench.sim.nativeReturnRequests.size)
    }
}
