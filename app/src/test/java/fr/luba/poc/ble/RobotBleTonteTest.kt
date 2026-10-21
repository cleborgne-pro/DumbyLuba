package fr.luba.poc.ble

import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.history.MowingOutcome
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.RoutePhase
import fr.luba.poc.simulation.SimConfig
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
 * End-to-end real mowing on a small zone: departure from the base, cutting, docking through the official return from the
 * approach point only (project rule), recharge mid-mowing, start refusals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleTonteTest {
    private val bench = RobotBench(Point(5.0, 4.0), 0.0)
    @After fun tearDown() = bench.close()

    private fun docked(battery: Double = 80.0) {
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.sim.battery = battery
        bench.installMap()
        bench.connect()
    }

    /** Maximum deviation between the robot center (driven moves) and the [planned] route. */
    private fun maxDeviation(planned: List<Point>): Double = bench.sim.driven.maxOf { p ->
        planned.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }
    }

    @Test fun `complete mowing from the base up to docking`() {
        docked()
        val engine = bench.mowingEngine()
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 55))
        val s0 = bench.state.robotRoute
        assertTrue(s0.running && s0.started)
        val planned = s0.planned
        assertEquals(55, s0.heightMm)
        assertNotNull("Progress saved from the start", bench.state.missionCheckpoint)
        assertNotNull(bench.state.mowingRecord)
        // Straight exit with blades stopped, then cutting at the requested height.
        bench.until(60_000, "blades running") { bench.sim.bladesOn }
        assertFalse("Blades started on the base", bench.sim.charging)
        assertEquals(55, bench.sim.heightMm)
        bench.until(30 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        val route = bench.state.robotRoute
        assertEquals(bench.dump(), RoutePhase.COMPLETE, route.phase)
        assertTrue(route.status, route.status.contains("contact de charge confirmé"))
        assertTrue("Robot on the base", bench.sim.charging)
        assertEquals(0, bench.sim.bladesWhileDocked)
        assertFalse(bench.sim.bladesOn)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertEquals(1, bench.sim.nativeReturnRequests.size)
        // The robot stays in the zone and follows the prepared route (30 cm deviation tolerated, 60 cm at pivots).
        assertTrue(bench.sim.track.all { Geometry.inside(it, RobotBench.ZONE.points) })
        val deviation = maxDeviation(planned)
        println("Maximum route deviation: %.2f m".format(deviation))
        assertTrue("Maximum route deviation %.2f m".format(deviation), deviation <= 0.45)
        // Mission complete: progress cleared, complete mowing report.
        assertNull(bench.state.missionCheckpoint)
        val report = bench.state.mowingReport
        assertNotNull(report)
        assertEquals(MowingOutcome.COMPLETE, report!!.outcome)
        assertEquals(1.0, report.progress, 1e-6)
        assertTrue(bench.state.robotRoute.trail.size > 20)
        // Task track readable from history; task can be excluded from estimate calibration.
        bench.awaitReal(5000, "track saved") { kotlinx.coroutines.runBlocking { bench.ble.loadTaskTrack(report.id) }?.samples?.isNotEmpty() == true }
        // Route tracking recorded for the field comparison: pilot over Bluetooth, nothing handed to a gateway, and the
        // recorded positions stay on the prepared stripes.
        assertEquals(setOf(fr.luba.poc.history.TrackingLink.BLUETOOTH), report.links)
        assertEquals(0, report.gatewayLegs)
        val tracking = requireNotNull(fr.luba.poc.history.RouteDeviation.of(requireNotNull(kotlinx.coroutines.runBlocking { bench.ble.loadTaskTrack(report.id) })))
        println("Recorded track: $tracking")
        assertTrue("$tracking", tracking.samples > 20 && tracking.medianM < 0.10 && tracking.offRoute == 0 && tracking.gaps == 0)
        bench.ble.showMowingReport(report.id)
        bench.ble.setMowingExcluded(report.id, true)
        assertTrue(bench.state.mowingHistory.first { it.id == report.id }.excluded)
        bench.awaitReal(5000, "end-of-mowing notification") { bench.alerted(AlertKind.MOWING_REPORT) }
        // After the end: no commanded movement.
        val end = bench.now
        bench.advance(10_000)
        assertFalse(bench.sim.movedCommandSince(end))
    }

    @Test fun `mowing started with robot in the zone, departure in place and return to base`() {
        bench.sim.place(Point(6.0, 4.5), 90.0)
        bench.installMap()
        bench.connect()
        val engine = bench.mowingEngine()
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = false, heightMm = 60))
        bench.until(30 * 60_000, "end of route") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        // Following with blades stopped: no spin requested; first movement near the starting point.
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        assertTrue(bench.sim.track.first().distance(Point(6.0, 4.5)) < 0.3)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertEquals(MowingOutcome.COMPLETE, bench.state.mowingHistory.first().outcome)
    }

    @Test fun `recharge at low threshold then resume at the interrupted point`() {
        docked(battery = 45.0)
        // Short autonomy: the rest cannot be finished before the low threshold, the robot goes back to recharge.
        val base = bench.base
        val engine = bench.mowingEngine(config = SimConfig(instantCharging = true, autonomyMinutes = 3.0, returnPercent = 20.0, resumePercent = 60.0,
            baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
        bench.sim.drainPerMeter = 0.6
        bench.sim.chargePerSecond = 2.0
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        bench.until(30 * 60_000, "return at low threshold") { bench.state.robotRoute.phase == RoutePhase.RETURNING }
        // 3 min runtime: the return alone costs about half the battery, so the return reserve decides before the low threshold.
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Seuil bas") || bench.state.robotRoute.status.contains("Réserve de retour"))
        assertEquals(1, bench.state.robotRoute.rechargeCycles)
        assertEquals(1, bench.state.missionCheckpoint?.rechargeCycles)
        bench.until(5 * 60_000, "recharging") { bench.state.robotRoute.phase == RoutePhase.CHARGING }
        assertTrue(bench.sim.charging)
        assertFalse(bench.sim.bladesOn)
        val atDock = bench.sim.track.size
        bench.until(10 * 60_000, "resume after recharge") { bench.state.robotRoute.phase == RoutePhase.RESUMING }
        assertTrue("Battery recharged before leaving", bench.sim.battery >= 40.0)
        bench.until(40 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        assertTrue(bench.sim.track.size > atDock)
        // At least one recharge (the battery drains fast); one docking per recharge, plus the final docking.
        val recharges = bench.state.mowingReport!!.recharges
        assertTrue(recharges >= 1)
        assertEquals(recharges + 1, bench.sim.nativeReturnRequests.size)
        assertEquals(MowingOutcome.COMPLETE, bench.state.mowingReport!!.outcome)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertEquals(0, bench.sim.bladesWhileDocked)
        assertTrue(bench.state.events.any { it.contains("Estimation de pose réinitialisée après la recharge") } ||
            bench.state.robotRoute.rechargeCycles >= 1)
    }

    @Test fun `insufficient battery at start, recharge before leaving the base`() {
        docked(battery = 24.0)
        bench.sim.chargePerSecond = 3.0
        val engine = bench.mowingEngine()
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        bench.until(10_000, "recharging") { bench.state.robotRoute.phase == RoutePhase.CHARGING }
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
        bench.until(5 * 60_000, "leaving the base") { bench.state.robotRoute.phase == RoutePhase.RESUMING }
        assertTrue(bench.sim.battery > 30)
        bench.ble.stop()
        bench.until(5000, "stop") { !bench.state.robotRoute.running && bench.state.connection == ConnectionState.Disconnected }
    }

    @Test fun `start refused, height, different map, protection, double start, link closed`() {
        bench.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        bench.installMap()
        bench.connect()
        val engine = bench.mowingEngine()
        assertFalse(bench.ble.startRobotRoute(engine, cutting = true, heightMm = 80))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.startsWith("Départ refusé : Hauteur hors plage"))
        val other = fr.luba.poc.map.RobotMap(listOf(RobotBench.ZONE), true, "Autre", bench.state.robotMap.station)
        val foreign = MowingSimulation(engine.routes, engine.base, engine.preview, other, engine.config)
        assertFalse(bench.ble.startRobotRoute(foreign, cutting = true, heightMm = 60))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("carte du robot connecté"))
        bench.sim.selfCheck = 25
        bench.advance(1500)
        assertFalse(bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("collision"))
        bench.sim.selfCheck = 0
        bench.advance(1500)
        assertTrue(bench.dump(), bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        assertFalse(bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        assertTrue(bench.state.robotRoute.running)
        bench.ble.stop()
        bench.until(5000, "stop") { bench.state.connection == ConnectionState.Disconnected }
        assertFalse(bench.ble.startRobotRoute(engine, cutting = true, heightMm = 60))
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("BLE non prêt"))
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
    }

    @Test fun `recharge, local route to the approach point then official docking, then leaving the base`() {
        bench.sim.place(Point(6.5, 4.5), 200.0)
        bench.installMap()
        bench.connect()
        val engine = bench.stationEngine()
        assertTrue(bench.dump(), bench.ble.baseAction(engine, leave = false))
        bench.until(5 * 60_000, "return to base") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("contact de charge confirmé"))
        assertTrue(bench.sim.charging)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        bench.advance(3000)
        assertTrue(bench.ble.baseAction(engine, leave = false))
        assertEquals("Déjà sur la base : charge observée", bench.state.robotRoute.status)
        val exit = bench.now
        // Robot docked: the map's station is the one observed while charging, the station route is re-prepared.
        val leave = bench.stationEngine()
        val left = bench.ble.baseAction(leave, leave = true)
        assertTrue(bench.dump(), left)
        bench.until(60_000, "leaving the base") { !bench.state.robotRoute.running }
        assertEquals(bench.dump(), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        assertTrue(bench.state.status, bench.state.status.startsWith("Sortie de base terminée"))
        assertFalse(bench.sim.charging)
        assertTrue(bench.sim.published.distance(engine.baseApproach) < 0.15)
        assertTrue(bench.sim.labelsSince(exit).none { it == "blades-on" || it == "dock" })
    }

    @Test fun `recharge from the approach point, direct official return`() {
        bench.installMap()
        bench.sim.place(Point(3.0 + 0.08, 3.0), 0.0)
        bench.connect()
        val engine = bench.stationEngine()
        assertTrue(bench.dump(), bench.ble.baseAction(engine, leave = false))
        assertTrue(bench.state.nativeReturnActive)
        bench.until(60_000, "docking") { !bench.state.nativeReturnActive }
        assertEquals("Retour natif terminé : contact de charge confirmé", bench.state.nativeReturnStatus)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") })
    }
}
