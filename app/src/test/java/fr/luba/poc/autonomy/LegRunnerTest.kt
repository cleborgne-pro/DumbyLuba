package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.map.TrajectoryEnvelope
import fr.luba.poc.simulation.PathFollower
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Route tracking run on the gateway. The expected outcome texts are French wire values (sent to the pilot as is). */
class LegRunnerTest {
    /** Two 6 m mowing stripes joined by a transit, blades running on the stripes. */
    private fun stripes(): List<SimEdge> {
        val p = ::Point
        return listOf(
            SimEdge(p(0.0, 0.0), p(6.0, 0.0), cutting = true),
            SimEdge(p(6.0, 0.0), p(6.0, 0.5), cutting = false, bladesOn = false),
            SimEdge(p(6.0, 0.5), p(0.0, 0.5), cutting = true),
        )
    }

    private fun spec(from: Int = 0, cutting: Boolean = true, shown: Boolean = true, watchBattery: Boolean = false, sampling: Boolean = false,
        returnPercent: Int = 20) = LegSpec(legId = 7, routeId = 1, from = from, cutting = cutting, shown = shown, watchBattery = watchBattery,
        departure = false, lastArrivalM = null, heightMm = 60, speedPercent = 40, returnPercent = returnPercent, baseApproach = null,
        guardId = -1, physicalGuardId = -1, calibration = null, obstacles = emptyList(), sampling = sampling)

    private fun run(env: SimulatedRobotEnv, spec: LegSpec, path: List<SimEdge> = stripes(), guard: EnvelopeGuard? = null) =
        runBlocking { LegRunner(env, spec, path, guard, null).run() }

    private fun robotAtStart(path: List<SimEdge> = stripes(), battery: Int = 100) =
        SimulatedRobotEnv(path.first().a, PathFollower.headingOf(path.first()), battery = battery)

    @Test fun followsTheWholeRouteAndStops() {
        val env = robotAtStart()
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertEquals(3, result.index)
        // Arrived at the end of the last stripe, at the commanded stop.
        assertTrue("end at ${env.position}", env.position.distance(Point(0.0, 0.5)) < 0.35)
        assertEquals("ROUTE_SEGMENT_STOP", env.sent.last())
        // Blades: manual mode then OFF/ON confirmed before each range.
        assertTrue(env.sent.any { it.startsWith("ROUTE_BLADES_0_ON") })
        assertTrue(env.sent.any { it.startsWith("ROUTE_BLADES_1_OFF") })
        assertTrue(env.sent.any { it.startsWith("ROUTE_BLADES_2_ON") })
        assertTrue("progress reported", env.progresses.isNotEmpty() && env.progresses.last().index >= 2)
    }

    @Test fun surveySamplesCarryThePhoneTiltForTheReliefLayers() {
        val env = robotAtStart()
        env.gravity = fr.luba.poc.map.PhoneGravity(0.3, 0.1, 9.75)
        val result = run(env, spec(cutting = false, sampling = true))
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue(env.samples.isNotEmpty())
        assertTrue("tilt and heading attached to every survey sample", env.samples.all { it.gravity == env.gravity && it.heading != null })
    }

    @Test fun surveyNeverSpinsTheBlades() {
        val env = robotAtStart()
        val result = run(env, spec(cutting = false, sampling = true))
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        // cutting=false: no blade range, manual mode with blades OFF for bumper detection.
        assertTrue(env.sent.none { it.contains("_ON") })
        assertTrue("signal samples on the cutting edges", env.samples.size >= 8)
        env.samples.forEach { assertTrue(it.point.y < 0.9 && it.point.y > -0.4) }
    }

    @Test fun lowBatteryStopsAtAnEdgeAndReports() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 12_000) it.battery = 19 }
        val result = run(env, spec(watchBattery = true, returnPercent = 20))
        assertEquals(result.text, LegOutcome.LOW_BATTERY, result.outcome)
        assertTrue(result.index in 0..2)
        assertEquals("ROUTE_LOW_BATTERY_STOP", env.sent.last())
    }

    @Test fun bumperContactIsReportedWithTrailAndPose() {
        val env = robotAtStart()
        env.onTick = {
            if (it.elapsedMs > 15_000 && it.pendingContact == null) it.pendingContact = LegContact(-1014, it.position.x, it.position.y, 0.0, 1L)
        }
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.CONTACT, result.outcome)
        assertEquals(-1014, result.contact?.code)
        assertTrue("back-off track available: ${result.trail.size}", result.trail.size in 8..LegResult.MAX_TRAIL)
        // Codec round trip.
        assertEquals(result.copy(trail = result.trail.takeLast(LegResult.MAX_TRAIL)), LegResult.decode(result.encode()))
    }

    /** 2026-10-15, 11:00:06: self-check 25 in the contact report, code -1014 read from the history one second later. */
    @Test fun collisionLatchBeforeTheContactCodeIsReportedAsAContact() {
        val env = robotAtStart()
        var latchedAt = 0L
        env.onTick = {
            if (it.elapsedMs > 15_000 && it.selfCheck == 0L) { it.selfCheck = 25L; latchedAt = it.elapsedMs }
            if (latchedAt > 0 && it.elapsedMs > latchedAt + 900 && it.pendingContact == null)
                it.pendingContact = LegContact(-1014, it.position.x, it.position.y, 0.0, 1L)
        }
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.CONTACT, result.outcome)
        assertEquals(-1014, result.contact?.code)
        assertTrue("stop as soon as the lock appears", env.sent.contains("ROUTE_LATCH_STOP"))
        assertTrue("manual mode with blades OFF kept alive while waiting", env.sent.contains("ROUTE_LATCH_BUMPER_OFF"))
        assertTrue("errors queried while waiting", env.sent.contains("ROUTE_LATCH_ERRORS"))
        assertTrue(env.events.contains("route_latch_wait"))
        // No movement command between the lock and the contact.
        val after = env.sent.drop(env.sent.indexOf("ROUTE_LATCH_STOP") + 1)
        assertTrue(after.none { it.startsWith("ROUTE_MOVE") })
    }

    @Test fun collisionLatchWithoutContactStopsOnTheProtectionAfterTheGrace() {
        val env = robotAtStart()
        var latchedAt = 0L
        env.onTick = { if (it.elapsedMs > 15_000 && it.selfCheck == 0L) { it.selfCheck = 25L; latchedAt = it.elapsedMs } }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertEquals("Protection / état robot changé (état 20, protection 25)", result.text)
        assertTrue(fr.luba.poc.simulation.ProtectionPause.pausable(result.text))
        assertTrue("bounded wait for the contact: ${env.elapsedMs - latchedAt} ms",
            env.elapsedMs - latchedAt in fr.luba.poc.protocol.CollisionLatch.CONTACT_GRACE_MS..fr.luba.poc.protocol.CollisionLatch.CONTACT_GRACE_MS + 1500)
    }

    /** 2026-10-20: move to a point beyond the map, 25 published on the section beyond the map: not a contact. */
    @Test fun collisionLatchOnTheSectionBeyondTheMapIsIgnoredForAMoveBladesOff() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 15_000 && it.selfCheck == 0L) it.selfCheck = 25L }
        val beyond = listOf(listOf(Point(-1.0, 0.0), Point(7.0, 0.0), Point(7.0, 0.5), Point(-1.0, 0.5)))
        val result = run(env, spec(cutting = false).copy(offMapSections = beyond))
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue(env.sent.none { it == "ROUTE_LATCH_STOP" })
        // Off its sections, or with the bumper pressed, 25 stops the move as before.
        val elsewhere = robotAtStart()
        elsewhere.onTick = { if (it.elapsedMs > 15_000 && it.selfCheck == 0L) it.selfCheck = 25L }
        val far = listOf(listOf(Point(20.0, 20.0), Point(25.0, 20.0)))
        assertEquals(LegOutcome.FAILED, run(elsewhere, spec(cutting = false).copy(offMapSections = far)).outcome)
    }

    @Test fun fleetingProtectionStillStopsTheRunForThePause() {
        val env = robotAtStart()
        var latchedAt = 0L
        env.onTick = {
            if (it.elapsedMs > 15_000 && latchedAt == 0L) { it.selfCheck = 41L; latchedAt = it.elapsedMs }
            if (latchedAt > 0 && it.elapsedMs > latchedAt + 1000) it.selfCheck = 10L
        }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertEquals("Protection / état robot changé (état 20, protection 41)", result.text)
    }

    @Test fun stopLockIsNotHeldForAContact() {
        val env = robotAtStart()
        var latchedAt = 0L
        env.onTick = { if (it.elapsedMs > 15_000 && latchedAt == 0L) { it.selfCheck = 11L; latchedAt = it.elapsedMs } }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(env.sent.none { it == "ROUTE_LATCH_STOP" })
        assertTrue("immediate stop: ${env.elapsedMs - latchedAt} ms", env.elapsedMs - latchedAt < 1000)
    }

    /** Slope: 3.5 cm/s downhill slide during pivots (Jardin d'Eden, 2026-10-15), measured and handed to the pilot. */
    @Test fun pivotSlidesAreMeasuredAndReportedToThePilot() {
        val env = robotAtStart()
        env.pivotSlide = Point(0.0, -0.035)
        val reported = mutableListOf<Map<String, Any?>>()
        env.hooks = object : LegHooks {
            override fun obstacles() = emptyList<LegObstacle>()
            override fun event(kind: String, text: String, fields: Map<String, Any?>) { if (kind == LegRunner.PIVOT_SLIDE_EVENT) reported += fields }
            override fun progress(progress: LegProgress) = Unit
            override fun sample(sample: fr.luba.poc.map.SignalSample) = Unit
        }
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("U-turn pivots measured: $reported", reported.isNotEmpty())
        val vy = reported.sumOf { (it["vy"] as Double) * (it["seconds"] as Double) } / reported.sumOf { it["seconds"] as Double }
        assertEquals("mean measured slide", -0.035, vy, 0.015)
    }

    @Test fun slideFieldTravelsWithTheSpecAndOlderPayloadsStillDecode() {
        var field = fr.luba.poc.simulation.SlideField()
        repeat(3) { field = field.learned(fr.luba.poc.simulation.SlideSample(3.5, 0.5, 0.0, -0.03, 4.0, 1L)) }
        val withSlide = spec().copy(slideField = field)
        val decoded = LegSpec.decode(withSlide.encode())
        assertEquals(-0.03, decoded.slideField!!.velocity(Point(3.5, 0.5))!!.y, 1e-6)
        // Older pilot: neither base zone nor slides.
        assertNull(LegSpec.decode(spec().encode()).slideField)
        val old = spec().encode().let { it.copyOf(it.size - 2) }   // without the final "slides" and "beyond the map" bytes
        assertNull(LegSpec.decode(old).slideField)
    }

    @Test fun addedObstacleOnThePathStopsTheRun() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 10_000) it.obstacleList = listOf(LegObstacle("ajout-1", 3.0, 0.0, 0.2, 0.5, planned = false)) }
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.OBSTACLE_ADDED, result.outcome)
        assertEquals(listOf("ajout-1"), result.obstacleIds)
    }

    @Test fun plannedObstacleOnThePathIsAHardFailureNotABypass() {
        val env = robotAtStart()
        env.obstacleList = listOf(LegObstacle("connu", 3.0, 0.0, 0.2, 0.5, planned = true))
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        // Handed to the pilot as a recoverable exit (back-off and link), not as a bypass.
        assertEquals(fr.luba.poc.simulation.DriftRecovery.OBSTACLE_RESERVE, result.text)
        assertTrue(fr.luba.poc.simulation.DriftRecovery.recoverable(result.text))
    }

    /** 2026-10-15 at 09:06:51: 30 cm straight escape out of an obstacle reserve (radius 0.2 m, margin 0.153 m). */
    @Test fun escapeOutOfAnObstacleReserveIsFollowed() {
        val start = Point(0.9984, 0.4482)
        val escape = Point(0.8402518555542677, 0.1932702755495707)
        val path = listOf(SimEdge(start, escape, cutting = false, escape = true), SimEdge(escape, Point(-1.0, -1.0), cutting = false))
        val env = SimulatedRobotEnv(start, PathFollower.headingOf(path.first()))
        env.obstacleList = listOf(LegObstacle("devant-base", 1.6227098745739885, 0.4706412615117156, 0.2, 0.353, planned = true))
        val result = run(env, spec(cutting = false), path)
        assertEquals("${result.text} at ${env.position}", LegOutcome.DONE, result.outcome)
    }

    /** 2026-10-15: 30 to 45 cm slip during the U-turn at the end of a stripe, blades running, wide safe surface. */
    @Test fun slideDuringTheEndOfStripePivotIsAbsorbed() {
        val env = robotAtStart()
        var slid = false
        env.onTick = { if (!slid && it.position.x > 5.9 && it.trueHeading > 30.0) { slid = true; it.shove(0.0, -0.4) } }
        val result = run(env, spec(), guard = EnvelopeGuard(TrajectoryEnvelope(GeometryFactory().createPolygon(arrayOf(Coordinate(-2.0, -3.0),
            Coordinate(9.0, -3.0), Coordinate(9.0, 3.0), Coordinate(-2.0, 3.0), Coordinate(-2.0, -3.0))))))
        assertTrue("simulated slip", slid)
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
    }

    @Test fun stalePositionStopsTheRun() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 9_000) it.staleEverything = true }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("périmé") || result.text.contains("Position absente"))
    }

    @Test fun hostFailureStopsTheRun() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 9_000) it.hostFailure = "Soulèvement signalé par le robot" }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertEquals("Soulèvement signalé par le robot", result.text)
    }

    @Test fun gatewayLowBatteryRefusesBladesButLetsTheRobotMoveBladesOff() {
        val moving = robotAtStart()
        moving.cuttingFailure = "Batterie du téléphone posé sur le robot à 3 % : lames interdites"
        val transit = run(moving, spec(cutting = false))
        assertEquals(transit.text, LegOutcome.DONE, transit.outcome)
        assertTrue(moving.sent.none { it.contains("_ON") })

        val mowing = robotAtStart()
        mowing.cuttingFailure = "Batterie du téléphone posé sur le robot à 3 % : lames interdites"
        val refused = run(mowing, spec())
        assertEquals(LegOutcome.FAILED, refused.outcome)
        assertTrue(refused.text, refused.text.contains("lames interdites"))
        assertTrue("no blades ON command", mowing.sent.none { it.contains("_ON") })

        val dropping = robotAtStart()
        dropping.onTick = { if (it.elapsedMs > 6_000) it.cuttingFailure = "lames interdites" }
        val stopped = run(dropping, spec())
        assertEquals(LegOutcome.FAILED, stopped.outcome)
        assertEquals("lames interdites", stopped.text)
    }

    @Test fun lostLinkStopsTheRun() {
        val env = robotAtStart()
        env.onTick = { if (it.elapsedMs > 9_000) it.healthy = false }
        val result = run(env, spec())
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("BLE"))
    }

    @Test fun leavingTheEnvelopeSurfaceStopsTheRun() {
        val env = robotAtStart()
        // Free physical surface of only 3 m: the 6 m stripe leaves it.
        val factory = GeometryFactory()
        val area = factory.createPolygon(arrayOf(Coordinate(-1.0, -1.0), Coordinate(3.0, -1.0), Coordinate(3.0, 1.0), Coordinate(-1.0, 1.0), Coordinate(-1.0, -1.0)))
        val result = run(env, spec(), guard = EnvelopeGuard(TrajectoryEnvelope(area)))
        assertEquals(result.text, LegOutcome.FAILED, result.outcome)
        assertEquals("Enveloppe robot hors du couloir sûr", result.text)
        assertTrue("stopped before leaving: ${env.position}", env.position.x < 3.3)
    }

    /** 10 m return to base towards the approach point (10; 0), station behind it; safe surface 3 m on each side. */
    private val approach = Point(10.0, 0.0)
    private fun returnPath() = listOf(SimEdge(Point(0.0, 0.0), Point(5.0, 0.0), false), SimEdge(Point(5.0, 0.0), approach, false))
    private fun returnSpec(baseZone: fr.luba.poc.simulation.ReturnDrift.BaseZone?) = spec(cutting = false).copy(baseApproach = approach, baseZone = baseZone)
    private val station = fr.luba.poc.simulation.ReturnDrift.BaseZone(Point(11.5, 0.0), 1.05)
    private fun wideGuard() = EnvelopeGuard(TrajectoryEnvelope(GeometryFactory().createPolygon(arrayOf(Coordinate(-2.0, -3.0), Coordinate(14.0, -3.0),
        Coordinate(14.0, 3.0), Coordinate(-2.0, 3.0), Coordinate(-2.0, -3.0)))))
    private fun slidingRobot(dy: Double): SimulatedRobotEnv {
        val env = robotAtStart(returnPath())
        var slid = false
        env.onTick = { if (!slid && it.elapsedMs > 8_000) { slid = true; it.shove(0.0, dy) } }
        return env
    }

    @Test fun returnToBaseToleratesADriftInsideTheSafeArea() {
        val env = slidingRobot(0.6)
        val result = run(env, returnSpec(station), returnPath(), wideGuard())
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("arrived at the approach point: ${env.position}", env.position.distance(approach) < 0.35)
    }

    @Test fun driftBeyondOneMetreOrWithoutBaseZoneStopsTheReturn() {
        val tooFar = run(slidingRobot(1.4), returnSpec(station), returnPath(), wideGuard())
        assertEquals(LegOutcome.FAILED, tooFar.outcome)
        assertTrue(tooFar.text, tooFar.text.contains("pendant le retour"))
        // Pilot without base zone: normal 30 cm deviation.
        val legacy = run(slidingRobot(0.6), returnSpec(null), returnPath(), wideGuard())
        assertEquals(LegOutcome.FAILED, legacy.outcome)
        assertTrue(legacy.text, legacy.text.startsWith("Écart au trajet >30 cm"))
    }

    @Test fun driftDuringMowingStillStopsAtThirtyCentimetres() {
        val env = robotAtStart()
        var slid = false
        env.onTick = { if (!slid && it.elapsedMs > 8_000) { slid = true; it.shove(0.0, 0.6) } }
        val result = run(env, spec().copy(baseApproach = approach, baseZone = station))
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.startsWith("Écart au trajet >30 cm"))
    }

    @Test fun baseZoneTravelsWithTheSpecAndOlderPayloadsStillDecode() {
        val spec = returnSpec(station)
        assertEquals(spec, LegSpec.decode(spec.encode()))
        // Up-to-date gateway, older pilot: frame without the final field.
        val legacy = returnSpec(null)
        assertEquals(legacy, LegSpec.decode(legacy.encode().dropLast(1).toByteArray()))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingASafetyFailure() {
        val env = robotAtStart()
        var job: kotlinx.coroutines.Job? = null
        env.onTick = { if (it.elapsedMs > 9_000) job?.cancel(kotlinx.coroutines.CancellationException("Arrêt demandé")) }
        var outcome: Any? = null
        runBlocking {
            job = launch { outcome = LegRunner(env, spec(), stripes(), null, null).run() }
            job!!.join()
        }
        assertTrue("cancelled, not returned as an outcome: $outcome", job!!.isCancelled && outcome == null)
    }

    @Test fun missingRobotDataIsASafetyFailureNotAnInternalError() {
        val env = robotAtStart()
        // Position published but heading missing: the tracking must answer with a clean safety stop.
        env.onTick = { if (it.elapsedMs > 6_000) it.dropHeading = true }
        val result = run(env, spec())
        assertEquals(result.text, LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.isNotBlank())
    }

    @Test fun resumesFromTheGivenEdge() {
        val path = stripes()
        val env = SimulatedRobotEnv(path[2].a, PathFollower.headingOf(path[2]))
        val result = run(env, spec(from = 2), path)
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue(env.position.distance(Point(0.0, 0.5)) < 0.35)
        assertNull(env.progresses.firstOrNull { it.index < 3 })
    }

    @Test fun noMovementCommandIsSentBeforeTheBladeGateIsConfirmed() {
        val env = robotAtStart()
        run(env, spec())
        val firstMove = env.sent.indexOfFirst { it.startsWith("ROUTE_MOVE") }
        val gate = env.sent.indexOfFirst { it.startsWith("ROUTE_BLADES_0_ON") }
        assertTrue(gate in 0 until firstMove)
        assertTrue(env.maxLinearSeen <= 500)
    }
}
