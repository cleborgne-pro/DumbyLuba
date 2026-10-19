package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.map.SignalSample
import fr.luba.poc.simulation.PathFollower
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.util.Collections

/**
 * Pilot ([RemoteLegs]) ↔ gateway ([GatewayAutonomy]) through an in-memory pipe, simulated robot: route and envelope
 * surface upload, route tracking, survey samples, outcome; Wi-Fi cut then back in the middle of the tracking; deliberate
 * departure, stop requested.
 */
class EndToEndLegTest {
    private class Pipe(val robot: SimulatedRobotEnv) {
        val stops = Collections.synchronizedList(mutableListOf<String>())
        val frames = Collections.synchronizedList(mutableListOf<Int>())
        lateinit var gateway: GatewayAutonomy
        lateinit var client: RemoteLegs
        @Volatile var wifi = true
        val host = object : LegHost {
            override fun env(hooks: LegHooks): LegEnv { robot.hooks = hooks; return robot }
            override fun emergencyStop(): String { stops += "stop"; return "STOP écrit par la passerelle (1 trame(s))" }   // French wire value checked by GatewayAutonomy
            override fun refusal(): String? = null
        }
        /** Gateway → pilot: lost if the Wi-Fi is down. */
        val sink = PilotSink { type, payload -> frames += type; if (wifi) client.onFrame(type, payload) }
        fun open(orphanLimitMs: Long = 20 * 60_000L, wall: () -> Long = System::currentTimeMillis) {
            gateway = GatewayAutonomy(host, {}, orphanLimitMs, wall)
            client = RemoteLegs(LegTransport { type, payload -> if (wifi) { gateway.onFrame(type, payload); true } else false })
            gateway.pilotAttached(sink)
        }
    }

    private val pipes = mutableListOf<Pipe>()
    @After fun cleanup() { pipes.forEach { runCatching { it.gateway.shutdown() } } }

    private fun stripes(): List<SimEdge> {
        val p = ::Point
        return listOf(SimEdge(p(0.0, 0.0), p(6.0, 0.0), cutting = true), SimEdge(p(6.0, 0.0), p(6.0, 0.5), cutting = false, bladesOn = false),
            SimEdge(p(6.0, 0.5), p(0.0, 0.5), cutting = true))
    }
    private fun wideSurface() = GeometryFactory().createPolygon(arrayOf(Coordinate(-3.0, -3.0), Coordinate(10.0, -3.0), Coordinate(10.0, 4.0), Coordinate(-3.0, 4.0), Coordinate(-3.0, -3.0)))

    private fun pipe(): Pipe {
        val path = stripes()
        return Pipe(SimulatedRobotEnv(path.first().a, PathFollower.headingOf(path.first()))).also { pipes += it; it.open() }
    }

    private class Recorder : RemoteLegs.Callbacks {
        val progress = Collections.synchronizedList(mutableListOf<LegProgress>())
        val samples = Collections.synchronizedList(mutableListOf<SignalSample>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        override fun progress(progress: LegProgress) { this.progress += progress }
        override fun sample(sample: SignalSample) { samples += sample }
        override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) { events += kind }
    }

    private fun spec(pipe: Pipe, routeId: Int, guardId: Int, sampling: Boolean = true) = LegSpec(pipe.client.nextLegId(), routeId, 0, cutting = false, shown = true,
        watchBattery = false, departure = false, lastArrivalM = null, heightMm = 60, speedPercent = 40, returnPercent = 20, baseApproach = null,
        guardId = guardId, physicalGuardId = guardId, calibration = null, obstacles = emptyList(), sampling = sampling)

    @Test(timeout = 120_000) fun uploadsTheRouteOnceAndFollowsItToTheEnd() = runBlocking {
        val pipe = pipe()
        val route = stripes()
        val routeId = pipe.client.ensureRoute(route)
        assertEquals("same route already uploaded: no new upload", routeId, pipe.client.ensureRoute(route))
        val guardId = pipe.client.ensureGuard(wideSurface())
        val recorder = Recorder()
        val leg = pipe.client.start(spec(pipe, routeId, guardId), recorder)
        val result = withTimeout(30_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertEquals(3, result.index)
        assertTrue("progress received: ${recorder.progress.size}", recorder.progress.isNotEmpty())
        assertTrue("survey samples received in order: ${recorder.samples.size}", recorder.samples.size >= 8)
        assertTrue(recorder.samples.zipWithNext().all { (a, b) -> b.at > a.at })
        assertEquals("one safety stop written at the end of the tracking", 1, pipe.stops.size)
        assertTrue(pipe.frames.contains(LegFrames.LEG_RESULT))
    }

    @Test(timeout = 120_000) fun wifiLostMidLegTheRobotKeepsGoingAndSamplesAreReplayedWithoutGapsOrDuplicates() = runBlocking {
        val pipe = pipe()
        val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
        val recorder = Recorder()
        var cut = false; var back = false
        pipe.robot.onTick = { robot ->
            if (!cut && robot.elapsedMs > 8_000) { cut = true; pipe.wifi = false; pipe.gateway.pilotDetached(intentional = false) }
            // The Wi-Fi comes back later: the pilot reconnects, the gateway hands back the tracking state.
            if (cut && !back && robot.elapsedMs > 26_000) { back = true; pipe.wifi = true; pipe.gateway.pilotAttached(pipe.sink) }
        }
        val leg = pipe.client.start(spec(pipe, routeId, guardId), recorder)
        val result = withTimeout(30_000) { leg.result.await() }
        assertTrue("Wi-Fi cut then back during the tracking", cut && back)
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        // Survey samples taken during the outage are replayed: complete sequence, without gaps or duplicates.
        val expected = pipe.robot.samples.fold(mutableListOf<SignalSample>()) { kept, s ->
            if (kept.lastOrNull()?.let { s.at - it.at < GatewayAutonomy.MIN_SAMPLE_GAP_MS } != true) kept += s; kept }
        assertTrue("survey samples were taken during the outage: ${expected.size}", expected.size >= 8)
        assertEquals(expected.size, recorder.samples.size)
        assertEquals(expected.map { it.at }, recorder.samples.map { it.at })
        assertTrue("the robot was not stopped by the loss of the pilot", pipe.stops.size == 1)
    }

    @Test(timeout = 120_000) fun resultFinishedWhileWifiIsDownIsDeliveredAtReconnection() = runBlocking {
        val pipe = pipe()
        val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
        var cut = false
        pipe.robot.onTick = { robot -> if (!cut && robot.elapsedMs > 5_000) { cut = true; pipe.wifi = false; pipe.gateway.pilotDetached(intentional = false) } }
        val leg = pipe.client.start(spec(pipe, routeId, guardId), Recorder())
        // The tracking finishes without a pilot (the gateway thread runs on its own).
        val deadline = System.currentTimeMillis() + 20_000
        while (pipe.gateway.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(leg.result.isCompleted)
        pipe.wifi = true; pipe.gateway.pilotAttached(pipe.sink)
        val result = withTimeout(10_000) { leg.result.await() }
        assertEquals(LegOutcome.DONE, result.outcome)
    }

    @Test(timeout = 120_000) fun pilotLeavingOnPurposeInterruptsTheLegAndStopsTheRobot() = runBlocking {
        val pipe = pipe()
        val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
        val started = CompletableDeferred<Unit>()
        pipe.robot.onTick = { robot -> if (robot.elapsedMs > 7_000 && !started.isCompleted) { started.complete(Unit); Thread.sleep(50) } }
        pipe.client.start(spec(pipe, routeId, guardId), Recorder())
        started.await()
        Thread { pipe.gateway.pilotDetached(intentional = true) }.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (pipe.gateway.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // The pilot has left: the outcome has no recipient any more, but the robot was stopped.
        assertFalse(pipe.gateway.running)
        assertTrue(pipe.stops.isNotEmpty())
    }

    @Test(timeout = 120_000) fun abortFromThePilotStopsTheRobotAndReportsAborted() = runBlocking {
        val pipe = pipe()
        val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
        var asked = false
        lateinit var leg: RemoteLegs.Leg
        pipe.robot.onTick = { robot -> if (!asked && robot.elapsedMs > 7_000) { asked = true; Thread { leg.abort() }.start(); Thread.sleep(200) } }
        leg = pipe.client.start(spec(pipe, routeId, guardId), Recorder())
        val result = withTimeout(20_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.ABORTED, result.outcome)
        assertTrue(pipe.stops.isNotEmpty())
    }

    @Test(timeout = 120_000) fun abortRightAfterStartNeverWedgesTheGateway() = runBlocking {
        // The abort follows LEG_START so closely that the tracking thread sometimes has not taken over yet: the slot must always be freed.
        repeat(40) { round ->
            val pipe = pipe()
            val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
            val leg = pipe.client.start(spec(pipe, routeId, guardId), Recorder())
            leg.abort()
            val result = withTimeout(20_000) { leg.result.await() }
            assertEquals("round $round: ${result.text}", LegOutcome.ABORTED, result.outcome)
            val deadline = System.currentTimeMillis() + 5_000
            while (pipe.gateway.running && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertFalse("round $round: slot freed", pipe.gateway.running)
            pipe.client.release(leg)
        }
    }

    @Test(timeout = 120_000) fun pilotAbsentTooLongStopsTheLeg() = runBlocking {
        var clock = 1_000_000L
        val path = stripes()
        val pipe = Pipe(SimulatedRobotEnv(path.first().a, PathFollower.headingOf(path.first()))).also { pipes += it; it.open(orphanLimitMs = 60_000, wall = { clock }) }
        val routeId = pipe.client.ensureRoute(path); val guardId = pipe.client.ensureGuard(wideSurface())
        var cut = false
        pipe.robot.onTick = { robot ->
            if (!cut && robot.elapsedMs > 5_000) { cut = true; pipe.wifi = false; pipe.gateway.pilotDetached(intentional = false) }
            if (cut) clock += 200   // time passes without a pilot
        }
        val leg = pipe.client.start(spec(pipe, routeId, guardId), Recorder())
        val deadline = System.currentTimeMillis() + 20_000
        while (pipe.gateway.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        pipe.wifi = true; pipe.gateway.pilotAttached(pipe.sink)
        val result = withTimeout(10_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("Pilote absent"))   // French wire reason
        assertTrue(pipe.stops.isNotEmpty())
    }

    @Test(timeout = 120_000) fun unexpectedRunningLegIsAbortedWhenThePilotComesBackWithoutExpectingOne() = runBlocking {
        val pipe = pipe()
        val routeId = pipe.client.ensureRoute(stripes()); val guardId = pipe.client.ensureGuard(wideSurface())
        val reached = CompletableDeferred<Unit>()
        pipe.robot.onTick = { robot ->
            if (robot.elapsedMs > 6_000 && !reached.isCompleted) {
                pipe.wifi = false; pipe.gateway.pilotDetached(intentional = false); reached.complete(Unit)
                Thread.sleep(300)   // gives the test time to "restart the pilot"
            }
        }
        pipe.client.start(spec(pipe, routeId, guardId), Recorder())
        reached.await()
        // App restarted: a new client that expects no route tracking.
        val fresh = RemoteLegs(LegTransport { type, payload -> pipe.gateway.onFrame(type, payload); true })
        pipe.wifi = true
        val freshSink = PilotSink { type, payload -> fresh.onFrame(type, payload) }
        pipe.gateway.pilotAttached(freshSink)
        val deadline = System.currentTimeMillis() + 20_000
        while (pipe.gateway.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse("tracking interrupted by the pilot that does not expect it", pipe.gateway.running)
        assertTrue(pipe.stops.isNotEmpty())
    }

    @Test(timeout = 120_000) fun refusedLegWhenRouteWasNeverUploaded() = runBlocking {
        val pipe = pipe()
        val leg = pipe.client.start(spec(pipe, routeId = 999, guardId = -1), Recorder())
        val failure = runCatching { withTimeout(5_000) { leg.result.await() } }.exceptionOrNull()
        assertTrue(failure is RemoteLegs.RefusedLeg)
        assertTrue(pipe.stops.isEmpty())
    }

    @Test(timeout = 120_000) fun tooManyObstaclesForOneFrameAreRefusedLocallyInsteadOfKillingTheLink() {
        val pipe = pipe()
        val many = (0 until 600).map { LegObstacle("mission-${java.util.UUID.randomUUID()}", it.toDouble(), 0.0, 0.3, 0.6, true) }
        val spec = spec(pipe, 1, -1).copy(obstacles = many)
        assertTrue(spec.encode().size > LegFrames.MAX_PAYLOAD)
        val refused = runCatching { pipe.client.start(spec, Recorder()) }.exceptionOrNull()
        assertTrue(refused is IllegalStateException)
        assertEquals("no route tracking was left half handed over", null, pipe.client.current)
        assertEquals("same limit as the gateway protocol", fr.luba.poc.gateway.GatewayProtocol.MAX_PAYLOAD, LegFrames.MAX_PAYLOAD)
    }

    @Test(timeout = 120_000) fun codecsRoundTrip() {
        val spec = LegSpec(5, 6, 7, cutting = true, shown = false, watchBattery = true, departure = true, lastArrivalM = 0.05, heightMm = 55,
            speedPercent = 33, returnPercent = 25, baseApproach = Point(1.5, -2.5), guardId = 8, physicalGuardId = -1,
            calibration = fr.luba.poc.simulation.DeadReckoning.Calibration(0.001, 0.12),
            obstacles = listOf(LegObstacle("a", 1.0, 2.0, 0.3, 0.6, true), LegObstacle("é", -1.0, 0.5, 0.2, 0.4, false)), sampling = true)
        assertEquals(spec, LegSpec.decode(spec.encode()))
        val edges = stripes() + SimEdge(Point(0.0, 0.5), Point(0.0, 0.0), cutting = false, escape = true, bladesOn = true)
        assertEquals(edges, LegBlobs.decodeRoute(LegBlobs.encodeRoute(edges)))
        val surface = wideSurface()
        assertTrue(surface.equalsExact(LegBlobs.decodeSurface(LegBlobs.encodeSurface(surface))))
        val sample = SignalSample(Point(1.0, 2.0), 123456789L, -60, null, -90, 0, 24, 37.5, fr.luba.poc.map.PhoneGravity(0.4, -0.2, 9.7))
        val decoded = LegFrames.parseSample(LegFrames.sample(3, 9, sample))
        assertEquals(3, decoded.legId); assertEquals(9, decoded.seq); assertEquals(sample, decoded.sample)
        val plain = SignalSample(Point(1.0, 2.0), 5L, null, -50, null, null, null)
        assertEquals(plain, LegFrames.parseSample(LegFrames.sample(1, 0, plain)).sample)
        val obstacles = listOf(LegObstacle("x", 1.0, 2.0, 3.0, 4.0, false))
        assertEquals(4 to obstacles, LegFrames.parseObstacles(LegFrames.obstacles(4, obstacles)))
        val progress = LegProgress(1, 2, 3, "Coupe · 2/3", true, true)
        assertEquals(progress, LegProgress.decode(progress.encode()))
    }
}
