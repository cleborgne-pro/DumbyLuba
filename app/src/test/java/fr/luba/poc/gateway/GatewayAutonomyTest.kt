package fr.luba.poc.gateway

import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.autonomy.LegOutcome
import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.autonomy.LegTransport
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.autonomy.LegProgress
import fr.luba.poc.map.Point
import fr.luba.poc.map.SignalSample
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end autonomous tracking, in real time: pilot (TCP, [RemoteLegs]) → [GatewaySession] → [GatewayRobot] and its
 * observer → simulated robot behind the Bluetooth link ([FakeRobotLink]) answering with BluFi frames.
 */
class GatewayAutonomyTest {
    private val code = "042137"
    private val address = "AA:BB:CC:DD:EE:FF"
    private val links = LinkedBlockingQueue<FakeRobotLink>()
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val reports = CopyOnWriteArrayList<String>()
    private val sessions = LinkedBlockingQueue<GatewaySession>()
    private var robot: GatewayRobot? = null
    private val pilots = mutableListOf<Pilot>()

    private fun robot(hostCheck: () -> String? = { null }, orphanLimitMs: Long = 20 * 60_000L) = GatewayRobot({ _, listener ->
        FakeRobotLink(listener, 0.0, 0.0, 0.0).also { link ->
            links.offer(link); link.start()
            Thread { Thread.sleep(20); listener.onDiscovering(link); listener.onReady(link, 247) }.start()
        }
    }, { reports += it }, keepaliveMs = 60_000, hostCheck = hostCheck, orphanLimitMs = orphanLimitMs).also { robot = it }

    private fun serve(shared: GatewayRobot = robot(), silenceMs: Long = 1500) {
        val limiter = AttemptLimiter()
        val admission = GatewayAdmission({ reports += it }, 6000)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val session = GatewaySession(socket, code, limiter, shared, { _, onResult -> onResult("Luba-VS123", address, -58) }, { reports += it },
                    scanMs = 50, silenceMs = silenceMs)
                sessions.offer(session)
                Thread { if (admission.admit(session, socket)) try { session.run() } finally { admission.release(session) } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun close() { pilots.forEach { it.drop() }; server.close(); robot?.shutdown() }

    /**
     * Pilot phone: authenticated TCP session, PING every 250 ms, tracking frames handed to [RemoteLegs].
     * [connect] opens a new session (after a Wi-Fi outage, the client and its tracking in progress are kept).
     */
    private inner class Pilot(val announceLegs: Boolean = true, val robotName: String = "Luba-VS123") {
        @Volatile var channel: GatewayChannel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        val client = RemoteLegs(LegTransport { type, payload -> val c = channel; runCatching { synchronized(c) { c.send(type, payload) } }.isSuccess })
        val ready = LinkedBlockingQueue<Int>()
        val lost = LinkedBlockingQueue<String>()
        val events = CopyOnWriteArrayList<String>()
        val written = LinkedBlockingQueue<Int>()
        /** Types of all frames received from the gateway. */
        val seenTypes = CopyOnWriteArrayList<Int>()
        init { pilots += this; attach(channel); Thread.sleep(1700) /* first robot position (1 Hz) received by the gateway */ }

        private fun attach(opened: GatewayChannel) {
            Thread {
                try {
                    while (channel === opened) {
                        val frame = opened.read()
                        seenTypes += frame.type
                        when {
                            frame.type in LegFrames.GATEWAY_TO_PILOT -> client.onFrame(frame.type, frame.payload)
                            frame.type == GatewayProtocol.READY -> ready.offer(frame.int())
                            frame.type == GatewayProtocol.EVENT -> events += frame.text()
                            frame.type == GatewayProtocol.WRITTEN -> written.offer(frame.int())
                            frame.type == GatewayProtocol.LOST || frame.type == GatewayProtocol.FAILURE -> lost.offer(frame.text())
                        }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()
            Thread {
                try { while (channel === opened) { synchronized(opened) { opened.send(GatewayProtocol.PING) }; Thread.sleep(250) } } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()
            synchronized(opened) {
                if (announceLegs) opened.send(LegFrames.LEG_HELLO)
                opened.send(GatewayProtocol.OPEN, GatewayProtocol.open(address, robotName))
            }
            assertNotNull("robot link ready", ready.poll(8, TimeUnit.SECONDS))
        }

        /** New session after a Wi-Fi outage. */
        fun connect() { val opened = GatewayChannel.open("127.0.0.1", server.localPort, code); channel = opened; attach(opened) }
        /** Wi-Fi outage: the socket drops without CLOSE. */
        fun drop() { channel.close() }
        fun leave() { val c = channel; synchronized(c) { c.send(GatewayProtocol.CLOSE) }; c.close() }
        fun write(frame: ByteArray) { val c = channel; synchronized(c) { c.send(GatewayProtocol.WRITE, frame) } }
    }

    private class Recorder : RemoteLegs.Callbacks {
        val progress = Collections.synchronizedList(mutableListOf<LegProgress>())
        val samples = Collections.synchronizedList(mutableListOf<SignalSample>())
        override fun progress(progress: LegProgress) { this.progress += progress }
        override fun sample(sample: SignalSample) { samples += sample }
        override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) { }
    }

    private fun wideSurface() = GeometryFactory().createPolygon(arrayOf(Coordinate(-5.0, -5.0), Coordinate(10.0, -5.0), Coordinate(10.0, 5.0), Coordinate(-5.0, 5.0), Coordinate(-5.0, -5.0)))
    private fun path(meters: Double = 1.5) = listOf(SimEdge(Point(0.0, 0.0), Point(meters, 0.0), cutting = true))
    private fun spec(pilot: Pilot, route: Int, guard: Int, cutting: Boolean = false, shown: Boolean = true, watchBattery: Boolean = false, speed: Int = 100) =
        LegSpec(pilot.client.nextLegId(), route, 0, cutting, shown, watchBattery, departure = false, lastArrivalM = null, heightMm = 60, speedPercent = speed,
            returnPercent = 20, baseApproach = null, guardId = guard, physicalGuardId = guard, calibration = null, obstacles = emptyList(), sampling = true)
    private fun waitFor(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }
    private fun FakeRobotLink.stopsAfterLastMove() = trace.dropWhile { it != "move" }.dropWhile { it == "move" }

    @Test(timeout = 120_000) fun hostBatteryAndHeatStopAnAutonomousLegOnlyWhenCritical() {
        // Low battery: never stops the motion (the robot stops by itself if the gateway shuts down).
        for (percent in listOf(0, 4, 6, 7, 50)) assertEquals(null, GatewayHealth(batteryPercent = percent).autonomyFailure())
        assertEquals(null, GatewayHealth().autonomyFailure())   // unknown state: no stop
        assertEquals(null, GatewayHealth(batteryPercent = 50, thermalStatus = 3).autonomyFailure())
        assertTrue(GatewayHealth(batteryPercent = 50, thermalStatus = 4).autonomyFailure()!!.contains("surchauffe"))
        // Blades forbidden below 5 %, only when not charging.
        assertEquals(null, GatewayHealth(batteryPercent = 6).cuttingFailure())
        assertEquals(null, GatewayHealth(batteryPercent = 5).cuttingFailure())
        assertTrue(GatewayHealth(batteryPercent = 4).cuttingFailure()!!.contains("4 %"))
        assertTrue(GatewayHealth(batteryPercent = 0).cuttingFailure()!!.contains("lames"))
        assertEquals(null, GatewayHealth(batteryPercent = 2, charging = true).cuttingFailure())
        assertEquals(null, GatewayHealth().cuttingFailure())
    }

    @Test(timeout = 120_000) fun aPilotThatDidNotAnnounceLegsIsNeverSentLegFrames() {
        serve(); val old = Pilot(announceLegs = false)
        // An older pilot version would take LEG_STATE for a failure: nothing of the kind must reach it.
        Thread.sleep(1500)
        assertTrue("received frames: ${old.seenTypes}", old.seenTypes.isNotEmpty() && old.seenTypes.none { it in LegFrames.GATEWAY_TO_PILOT })
        // And its tracking frames are refused as unknown.
        runCatching { synchronized(old.channel) { old.channel.send(LegFrames.LEG_START, ByteArray(8)) } }
        assertTrue(waitFor { old.lost.isNotEmpty() || old.seenTypes.contains(GatewayProtocol.FAILURE) })
    }

    @Test(timeout = 120_000) fun welcomeAnnouncesAutonomy() {
        serve()
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        assertTrue(channel.capabilities.contains(GatewayProtocol.CAP_AUTONOMY))
        channel.close()
        assertEquals(setOf("autonomy"), GatewayProtocol.capabilities(GatewayProtocol.Frame(GatewayProtocol.WELCOME, "x\ncaps=autonomy".toByteArray())))
        assertEquals(emptySet<String>(), GatewayProtocol.capabilities(GatewayProtocol.Frame(GatewayProtocol.WELCOME, "DumbyLuba passerelle ESP32".toByteArray())))
    }

    @Test(timeout = 120_000) fun gatewayFollowsAPathAndReportsDone() = runBlocking {
        serve(); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path()); val guard = pilot.client.ensureGuard(wideSurface())
        val recorder = Recorder()
        val leg = pilot.client.start(spec(pilot, route, guard), recorder)
        val result = withTimeout(60_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("arrived near the end: ${link.x}", link.x in 1.2..1.8 && kotlin.math.abs(link.y) < 0.3)
        assertTrue("progress reported", recorder.progress.isNotEmpty())
        assertTrue("signal surveys reported: ${recorder.samples.size}", recorder.samples.isNotEmpty())
        assertTrue(recorder.samples.all { it.wifi == -60 && it.satellites == 24 })
        // The robot stopped (zero speed written last) and manual mode with blades OFF was used for bumper detection.
        assertEquals("stop", link.trace.last { it == "stop" || it == "move" })
        assertFalse("no blade turned", link.trace.contains("blades-on"))
        assertEquals("tracking ends on the Redmi", false, robot!!.autonomy.running)
    }

    @Test(timeout = 120_000) fun aRobotModelInReadOnlyModeNeverGetsAnAutonomousLeg() = runBlocking {
        serve(); val pilot = Pilot(robotName = "Yuka-MNDEMO01"); val link = links.take()
        val route = pilot.client.ensureRoute(path()); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard, cutting = true), Recorder())
        val failure = runCatching { withTimeout(30_000) { leg.result.await() } }.exceptionOrNull()
        assertTrue("leg refused: $failure", failure is fr.luba.poc.autonomy.RemoteLegs.RefusedLeg && failure.message!!.contains("lecture seule"))
        assertFalse("the robot never moved: ${link.trace}", link.trace.contains("move") || link.trace.contains("blades-on"))
        assertEquals(false, robot!!.autonomy.running)
    }

    @Test(timeout = 120_000) fun wifiLostMidLegTheRobotKeepsGoingWithoutEmergencyStopThenResultIsDelivered() = runBlocking {
        serve(); val first = Pilot(); val link = links.take()
        val route = first.client.ensureRoute(path(2.0)); val guard = first.client.ensureGuard(wideSurface())
        val leg = first.client.start(spec(first, route, guard), Recorder())
        assertTrue("the robot is moving", waitFor { link.trace.contains("move") })
        val stopsBefore = link.trace.count { it == "stop" }
        first.drop()                                                  // Wi-Fi cut: dead socket, no CLOSE
        assertTrue("session ended on the gateway side", waitFor { reports.any { it.contains("Pilote perdu pendant un suivi autonome") } })
        assertTrue("tracking continues without a pilot", robot!!.autonomy.running)
        assertTrue("no safety stop written because of the pilot loss", link.trace.count { it == "stop" } == stopsBefore || link.lastLinear != 0 || link.trace.last() == "move")
        // The robot finishes its route on its own.
        assertTrue("the robot reaches the end", waitFor(60_000) { !robot!!.autonomy.running })
        assertTrue("arrived: ${link.x}\n${reports.joinToString("\n")}", link.x in 1.7..2.3)
        // Wi-Fi back: new session of the same pilot, the gateway sends the state then the tracking outcome.
        first.connect()
        val result = withTimeout(20_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
    }

    @Test(timeout = 120_000) fun contactBumperStopsAndReportsContactWithTrail() = runBlocking {
        serve(); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path(3.0)); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard), Recorder())
        assertTrue("the robot is moving", waitFor { link.trace.contains("move") && link.x > 0.25 })
        Thread.sleep(1500)
        link.injectError(-1014)
        val result = withTimeout(30_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.CONTACT, result.outcome)
        assertEquals(-1014, result.contact?.code)
        assertTrue("trail sent: ${result.trail.size}", result.trail.size >= 2)
        assertTrue("robot stopped", waitFor { link.lastLinear == 0 })
    }

    @Test(timeout = 120_000) fun liftedRobotIsStoppedAtOnceWithBladesOff() = runBlocking {
        serve(); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path(3.0)); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard, cutting = true), Recorder())
        assertTrue("blades running", waitFor(15_000) { link.bladesOn })
        assertTrue("the robot is moving", waitFor { link.trace.contains("move") })
        link.injectError(2801)   // lift sensor
        val result = withTimeout(30_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("Soulèvement"))
        assertFalse("blades stopped", link.bladesOn)
        assertEquals(0, link.lastLinear)
        // Nothing from the tracking restarts after the stop: no speed setpoint, no queued keep-alive blades ON.
        Thread.sleep(1200)
        assertFalse("blades still stopped", link.bladesOn)
        assertEquals("speed still zero", 0, link.lastLinear)
        assertEquals("blades-off", link.trace.last { it.startsWith("blades") || it == "manual" })
    }

    @Test(timeout = 120_000) fun pilotWriteDuringALegTakesBackControl() = runBlocking {
        serve(); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path(4.0)); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard), Recorder())
        assertTrue(waitFor { link.trace.contains("move") })
        pilot.write(fr.luba.poc.protocol.Blufi().encode(fr.luba.poc.protocol.Commands().stop(), 247).first())
        val result = withTimeout(20_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.ABORTED, result.outcome)
        assertFalse(robot!!.autonomy.running)
        assertEquals(0, link.lastLinear)
    }

    @Test(timeout = 120_000) fun pilotLeavingOnPurposeStopsTheRobot() {
        serve(); val pilot = Pilot(); val link = links.take()
        runBlocking {
            val route = pilot.client.ensureRoute(path(4.0)); val guard = pilot.client.ensureGuard(wideSurface())
            pilot.client.start(spec(pilot, route, guard), Recorder())
            assertTrue(waitFor { link.trace.contains("move") })
        }
        pilot.leave()
        assertTrue("tracking interrupted", waitFor { !robot!!.autonomy.running })
        assertTrue("robot stopped", waitFor { link.lastLinear == 0 })
    }

    @Test(timeout = 120_000) fun hostFailureStopsTheLeg() = runBlocking {
        var failure: String? = null
        serve(robot(hostCheck = { failure })); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path(4.0)); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard), Recorder())
        assertTrue(waitFor { link.trace.contains("move") })
        failure = "Batterie de la passerelle trop faible"
        val result = withTimeout(20_000) { leg.result.await() }
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertEquals("Batterie de la passerelle trop faible", result.text)
        assertEquals(0, link.lastLinear)
    }

    @Test(timeout = 120_000) fun silentRobotStopsTheLegWithoutPositions() = runBlocking {
        serve(); val pilot = Pilot(); val link = links.take()
        val route = pilot.client.ensureRoute(path(4.0)); val guard = pilot.client.ensureGuard(wideSurface())
        val leg = pilot.client.start(spec(pilot, route, guard), Recorder())
        assertTrue(waitFor { link.trace.contains("move") })
        link.silent = true
        val result = withTimeout(20_000) { leg.result.await() }
        assertEquals(LegOutcome.FAILED, result.outcome)
        assertTrue(result.text, result.text.contains("périmé") || result.text.contains("absente"))
    }
}
