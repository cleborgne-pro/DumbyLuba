package fr.luba.poc.gateway

import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.autonomy.LegOutcome
import fr.luba.poc.autonomy.LegProgress
import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.ble.RobotLink
import fr.luba.poc.map.Point
import fr.luba.poc.map.SignalSample
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The real pilot side ([GatewayLink]) against a real gateway: a Wi-Fi tunnel outage during autonomous tracking is not a
 * link loss — the link is resumed, the tracking state sent again, the outcome delivered.
 */
class GatewayLinkAutonomyTest {
    private val code = "042137"
    private val address = "AA:BB:CC:DD:EE:FF"
    private val robotLinks = LinkedBlockingQueue<FakeRobotLink>()
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val reports = CopyOnWriteArrayList<String>()
    private lateinit var robot: GatewayRobot
    private var link: GatewayLink? = null

    private fun startGateway() {
        robot = GatewayRobot({ _, listener ->
            FakeRobotLink(listener, 0.0, 0.0, 0.0).also { l ->
                robotLinks.offer(l); l.start()
                Thread { Thread.sleep(20); listener.onDiscovering(l); listener.onReady(l, 247) }.start()
            }
        }, { reports += it }, keepaliveMs = 60_000)
        val limiter = AttemptLimiter()
        val admission = GatewayAdmission({ reports += it }, 6000)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted += socket
                val session = GatewaySession(socket, code, limiter, robot, { _, onResult -> onResult("Luba-VS123", address, -58) }, { reports += it },
                    scanMs = 50, silenceMs = 1500)
                Thread { if (admission.admit(session, socket)) try { session.run() } finally { admission.release(session) } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun close() { link?.close(); server.close(); if (::robot.isInitialized) robot.shutdown() }

    private class Listener(val client: () -> RemoteLegs) : RobotLink.Listener {
        val ready = LinkedBlockingQueue<Int>()
        val events = CopyOnWriteArrayList<String>()
        val lost = LinkedBlockingQueue<String>()
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        override fun onReady(link: RobotLink, mtu: Int) { ready.offer(mtu) }
        override fun onLost(link: RobotLink, code: Int, reason: String) { lost.offer(reason) }
        override fun onFailure(link: RobotLink, reason: String) { lost.offer(reason) }
        override fun onWritten(link: RobotLink, code: Int) { }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) { }
        override fun onNotification(link: RobotLink, bytes: ByteArray) { notifications.incrementAndGet() }
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) { events += kind }
        override fun onLegFrame(link: RobotLink, type: Int, payload: ByteArray) { client().onFrame(type, payload) }
    }

    private class Recorder : RemoteLegs.Callbacks {
        val progress = Collections.synchronizedList(mutableListOf<LegProgress>())
        val samples = Collections.synchronizedList(mutableListOf<SignalSample>())
        override fun progress(progress: LegProgress) { this.progress += progress }
        override fun sample(sample: SignalSample) { samples += sample }
        override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) { }
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }

    @Test(timeout = 120_000) fun wifiTunnelLostDuringALegIsRecoveredAndTheResultDelivered() = runBlocking {
        startGateway()
        lateinit var client: RemoteLegs
        val listener = Listener { client }
        val opened = GatewayLink.open("127.0.0.1", server.localPort, code, address, listener, "Luba-VS123")!!
        link = opened
        client = RemoteLegs(opened.legTransport)
        assertTrue("robot link ready", listener.ready.poll(8, TimeUnit.SECONDS) != null)
        assertTrue("the gateway announces autonomous tracking", opened.supportsLegs)
        Thread.sleep(1700)   // first robot position received by the gateway

        val path = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), cutting = true))
        val guard = GeometryFactory().createPolygon(arrayOf(Coordinate(-5.0, -5.0), Coordinate(10.0, -5.0), Coordinate(10.0, 5.0), Coordinate(-5.0, 5.0), Coordinate(-5.0, -5.0)))
        val routeId = client.ensureRoute(path); val guardId = client.ensureGuard(guard)
        val spec = LegSpec(client.nextLegId(), routeId, 0, cutting = false, shown = true, watchBattery = false, departure = false, lastArrivalM = null,
            heightMm = 60, speedPercent = 100, returnPercent = 20, baseApproach = null, guardId = guardId, physicalGuardId = guardId,
            calibration = null, obstacles = emptyList(), sampling = true)
        val recorder = Recorder()
        opened.holdUntil = Long.MAX_VALUE          // like RobotBle during tracking
        val leg = client.start(spec, recorder)
        val robotLink = robotLinks.take()
        assertTrue("the robot is moving", waitFor { robotLink.trace.contains("move") })

        // Wi-Fi tunnel outage on the gateway side: the session socket drops abruptly.
        val session = accepted.last()
        session.setSoLinger(true, 0); session.close()
        assertTrue("outage reported to the pilot", waitFor { listener.events.contains("gateway_outage") })
        assertTrue("link resumed", waitFor(20_000) { listener.events.contains("gateway_resumed") })
        assertTrue("never declared lost", listener.lost.isEmpty())

        val result = withTimeout(60_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.DONE, result.outcome)
        assertTrue("arrived: ${robotLink.x}", robotLink.x in 1.7..2.3)
        assertTrue("the robot received no safety stop during the outage",
            robotLink.trace.dropWhile { it != "move" }.takeWhile { it != "stop" || robotLink.trace.count { t -> t == "stop" } == 0 }.isNotEmpty())
        assertTrue("surveys reported: ${recorder.samples.size}", recorder.samples.size >= 2)
        assertTrue(recorder.samples.zipWithNext().all { (a, b) -> b.at > a.at })
        assertFalse(robot.autonomy.running)
    }

    @Test(timeout = 120_000) fun abortOrderedDuringTheOutageIsDeliveredOnReconnection() = runBlocking {
        startGateway()
        lateinit var client: RemoteLegs
        val listener = Listener { client }
        val opened = GatewayLink.open("127.0.0.1", server.localPort, code, address, listener, "Luba-VS123")!!
        link = opened
        client = RemoteLegs(opened.legTransport)
        assertTrue(listener.ready.poll(8, TimeUnit.SECONDS) != null)
        Thread.sleep(1700)
        val path = listOf(SimEdge(Point(0.0, 0.0), Point(6.0, 0.0), cutting = true))
        val guard = GeometryFactory().createPolygon(arrayOf(Coordinate(-5.0, -5.0), Coordinate(10.0, -5.0), Coordinate(10.0, 5.0), Coordinate(-5.0, 5.0), Coordinate(-5.0, -5.0)))
        val routeId = client.ensureRoute(path); val guardId = client.ensureGuard(guard)
        val spec = LegSpec(client.nextLegId(), routeId, 0, cutting = false, shown = true, watchBattery = false, departure = false, lastArrivalM = null,
            heightMm = 60, speedPercent = 100, returnPercent = 20, baseApproach = null, guardId = guardId, physicalGuardId = guardId,
            calibration = null, obstacles = emptyList(), sampling = false)
        opened.holdUntil = Long.MAX_VALUE
        val leg = client.start(spec, Recorder())
        val robotLink = robotLinks.take()
        assertTrue(waitFor { robotLink.trace.contains("move") })
        accepted.last().let { it.setSoLinger(true, 0); it.close() }
        assertTrue("outage reported", waitFor { listener.events.contains("gateway_outage") })
        // STOP requested during the outage: the order waits in the queue and leaves as soon as the link is back, before anything else.
        leg.abort()
        opened.holdUntil = System.currentTimeMillis() + 60_000
        val result = withTimeout(60_000) { leg.result.await() }
        assertEquals(result.text, LegOutcome.ABORTED, result.outcome)
        assertTrue("robot stopped", waitFor { robotLink.lastLinear == 0 })
        assertFalse(robot.autonomy.running)
        assertTrue("link never declared lost", listener.lost.isEmpty())
    }

    @Test(timeout = 120_000) fun withoutHoldAWifiLossIsALostLink() {
        startGateway()
        lateinit var client: RemoteLegs
        val listener = Listener { client }
        val opened = GatewayLink.open("127.0.0.1", server.localPort, code, address, listener, "Luba-VS123")!!
        link = opened
        client = RemoteLegs(opened.legTransport)
        assertTrue(listener.ready.poll(8, TimeUnit.SECONDS) != null)
        accepted.last().let { it.setSoLinger(true, 0); it.close() }
        assertTrue("loss declared", listener.lost.poll(8, TimeUnit.SECONDS) != null)
        assertFalse(listener.events.contains("gateway_resumed"))
    }
}
