package fr.luba.poc.gateway

import fr.luba.poc.ble.RobotLink
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.Blufi
import fr.luba.poc.protocol.Commands
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayTest {
    private val code = "042137"

    /** Simulated robot link: every write is confirmed, like a healthy GATT. */
    private class FakeLink(val listener: RobotLink.Listener, val ack: Boolean = true) : RobotLink {
        val writes = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed = false
        override val description = "fake"
        override fun write(frame: ByteArray): Boolean {
            writes += frame.copyOf()
            if (ack) Thread { Thread.sleep(5); listener.onWritten(this, 0) }.start()
            return true
        }
        override fun readRssi(): Boolean { Thread { listener.onRssi(this, -61, 0) }.start(); return true }
        override fun close() { closed = true }
    }

    private val links = LinkedBlockingQueue<FakeLink>()
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val sessions = LinkedBlockingQueue<GatewaySession>()
    private val reports = CopyOnWriteArrayList<String>()

    private var robot: GatewayRobot? = null

    private val remembered = CopyOnWriteArrayList<Pair<String, String?>>()

    /** The gateway's shared robot link, over simulated links. */
    private fun robot(keepaliveMs: Long = 60_000) = GatewayRobot({ _, listener ->
        FakeLink(listener).also { link ->
            links.offer(link)
            Thread { Thread.sleep(20); listener.onDiscovering(link); listener.onReady(link, 247) }.start()
        }
    }, { reports += it }, keepaliveMs = keepaliveMs, onRemember = { a, n -> remembered += a to n }).also { robot = it }

    private val testHealth = GatewayHealth(batteryPercent = 62, charging = true, batteryTempDeciC = 318, wifiRssiDbm = -64, wifiMbps = 72,
        thermalStatus = 0, powerSave = false, batteryExempt = true, screenOn = true)

    @Test fun pilotReceivesGatewayHealthPeriodically() {
        serve(silenceMs = 5000)
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        var frame = channel.read()
        while (frame.type != GatewayProtocol.HEALTH) frame = channel.read()
        assertEquals(testHealth, GatewayHealth.decode(frame.payload))
        // A second frame follows: the state is refreshed while the session lives.
        frame = channel.read()
        while (frame.type != GatewayProtocol.HEALTH) frame = channel.read()
        assertEquals(62, GatewayHealth.decode(frame.payload)?.batteryPercent)
        channel.close()
    }

    @Test fun healthCodecAndWarnings() {
        val h = GatewayHealth(batteryPercent = 12, charging = false, batteryTempDeciC = 470, wifiRssiDbm = -80, thermalStatus = 3,
            powerSave = true, batteryExempt = false)
        assertEquals(h, GatewayHealth.decode(h.encode()))
        assertEquals(5, h.warnings().size)
        assertTrue(testHealth.warnings().isEmpty())
        assertTrue(GatewayHealth(batteryPercent = 10, charging = true).warnings().isEmpty()) // charging: no battery warning
        assertEquals(1, GatewayHealth(batteryPercent = 20).warnings().size)
        assertEquals(null, GatewayHealth.decode(ByteArray(8)))
    }

    @Test fun scanFailureReasonReachesPilot() {
        serve(scanner = { _, _ -> error("Bluetooth désactivé sur la passerelle") })
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.SCAN)
        var frame = channel.read()
        while (frame.type != GatewayProtocol.FAILURE && frame.type != GatewayProtocol.SCAN_END) frame = channel.read()
        channel.send(GatewayProtocol.CLOSE); channel.close()
        assertEquals(GatewayProtocol.FAILURE, frame.type)
        assertEquals("Bluetooth désactivé sur la passerelle", frame.text())
        assertTrue(reports.any { it.contains("Bluetooth désactivé") })
    }

    private fun serve(silenceMs: Long = 600, shared: GatewayRobot = robot(), admissionWaitMs: Long = GatewayAdmission.WAIT_MS,
                      scanner: GatewayScanner = GatewayScanner { _, onResult -> onResult("Luba-VS123", "AA:BB:CC:DD:EE:FF", -58) }) {
        val limiter = AttemptLimiter()
        val admission = GatewayAdmission({ reports += it }, admissionWaitMs)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val session = GatewaySession(socket, code, limiter, shared,
                    scanner, { reports += it },
                    scanMs = 50, silenceMs = silenceMs, health = { testHealth }, healthMs = 100)
                sessions.offer(session)
                Thread { if (admission.admit(session, socket)) try { session.run() } finally { admission.release(session) } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun close() { server.close(); robot?.shutdown() }

    private fun waitFor(timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }

    private fun GatewayChannel.until(type: Int): GatewayProtocol.Frame {
        var frame = read()
        while (frame.type != type) frame = read()
        return frame
    }

    private class Recorder : RobotLink.Listener {
        val ready = LinkedBlockingQueue<Int>()
        val written = LinkedBlockingQueue<Int>()
        val notifications = LinkedBlockingQueue<ByteArray>()
        val lost = LinkedBlockingQueue<String>()
        val rssi = LinkedBlockingQueue<Int>()
        override fun onReady(link: RobotLink, mtu: Int) { ready.offer(mtu) }
        override fun onLost(link: RobotLink, code: Int, reason: String) { lost.offer(reason) }
        override fun onFailure(link: RobotLink, reason: String) { lost.offer(reason) }
        override fun onWritten(link: RobotLink, code: Int) { written.offer(code) }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) { this.rssi.offer(rssi) }
        override fun onNotification(link: RobotLink, bytes: ByteArray) { notifications.offer(bytes) }
    }

    @Test fun proofDependsOnCodeAndNonce() {
        val nonce = ByteArray(16) { it.toByte() }
        assertTrue(GatewayProtocol.verify(code, nonce, GatewayProtocol.proof(code, nonce)))
        assertFalse(GatewayProtocol.verify("042138", nonce, GatewayProtocol.proof(code, nonce)))
        assertFalse(GatewayProtocol.verify(code, ByteArray(16), GatewayProtocol.proof(code, nonce)))
    }

    @Test fun limiterBlocksAfterFiveFailuresForOneMinute() {
        val limiter = AttemptLimiter()
        repeat(5) { assertFalse(limiter.blocked(1000L + it)); limiter.failed(1000L + it) }
        assertTrue(limiter.blocked(2000))
        assertFalse(limiter.blocked(61_005))
    }

    @Test fun scanResultRoundTrip() {
        val frame = GatewayProtocol.Frame(GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult("Luba-VS", "AA:BB", -71))
        assertEquals(Triple("Luba-VS", "AA:BB", -71), GatewayProtocol.parseScanResult(frame))
    }

    @Test fun emergencyFramesContinueBlufiSequenceAndStopBlades() {
        val pilot = Blufi(); val commands = Commands()
        val safety = GatewaySafety()
        listOf(commands.sync(), commands.manualBlades(true, 45), commands.movement(300, 0)).forEach { payload ->
            pilot.encode(payload, 247).forEach(safety::written)
        }
        assertTrue(safety.bladesMayTurn)
        val frames = requireNotNull(safety.emergencyFrames(247))
        // The robot already received frames 0..2: the next ones must continue at 3.
        assertEquals(3, frames[0][2].toInt() and 255)
        val robot = robotAfter(2)
        val decoded = frames.mapNotNull { robot.receive(it) }.map { LubaMsg.parseFrom(it) }
        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].driver.mowCtrlByHand.cutKnifeCtrl)
        assertEquals(45, decoded[0].driver.mowCtrlByHand.cutKnifeHeight)
        assertEquals(0, decoded[1].driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(0, decoded[1].driver.todevDevmotionCtrl.setAngularSpeed)
    }

    @Test fun noInjectionInsideFragmentedMessage() {
        val pilot = Blufi(); val safety = GatewaySafety()
        val big = Commands().mowPaths((1L..40L).toList(), 7)
        val frames = pilot.encode(big, 23)
        assertTrue(frames.size > 2)
        safety.written(frames[0])
        assertTrue(safety.midMessage)
        assertNull(safety.emergencyFrames(23))
        frames.drop(1).forEach(safety::written)
        assertFalse(safety.midMessage)
        assertNotNull(safety.emergencyFrames(23))
    }

    @Test fun tunnelCarriesWritesNotificationsAndRssi() {
        serve()
        val recorder = Recorder()
        val link = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", recorder))
        assertEquals(247, recorder.ready.poll(3, TimeUnit.SECONDS))
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        val frame = Blufi().encode(Commands().movement(200, 0), 247).single()
        assertTrue(link.write(frame))
        assertEquals(0, recorder.written.poll(2, TimeUnit.SECONDS))
        assertArrayEquals(frame, fake.writes.single())
        fake.listener.onNotification(fake, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), recorder.notifications.poll(2, TimeUnit.SECONDS))
        assertTrue(link.readRssi())
        assertEquals(-61, recorder.rssi.poll(2, TimeUnit.SECONDS))
        // Deliberate close: no stop frame, the gateway keeps the Bluetooth link.
        link.close()
        assertTrue(waitFor { robot!!.state.ready && !robot!!.state.pilot })
        assertFalse(fake.closed)
        assertEquals(1, fake.writes.size)
        assertNull(recorder.lost.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun silentPilotTriggersGatewayStopInSequence() {
        serve(silenceMs = 400)
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.OPEN, "AA:BB:CC:DD:EE:FF".toByteArray())
        var frame = channel.read()
        while (frame.type != GatewayProtocol.READY) frame = channel.read()
        val pilot = Blufi()
        pilot.encode(Commands().movement(400, 0), 247).forEach { channel.send(GatewayProtocol.WRITE, it) }
        do frame = channel.read() while (frame.type != GatewayProtocol.WRITTEN)
        // The pilot goes silent: no frame at all, not even PING.
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        val session = requireNotNull(sessions.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { session.emergencyOutcome != null })
        assertFalse(fake.closed)
        assertEquals(2, fake.writes.size)
        val stop = fake.writes[1]
        assertEquals(1, stop[2].toInt() and 255)
        val message = LubaMsg.parseFrom(requireNotNull(robotAfter(0).receive(stop)))
        assertEquals(0, message.driver.todevDevmotionCtrl.setLinearSpeed)
        assertTrue(session.emergencyOutcome!!.startsWith("STOP écrit"))
        channel.close()
    }

    @Test fun writtenFrameCarriesGattDuration() {
        serve()
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.OPEN, "AA:BB:CC:DD:EE:FF".toByteArray())
        var frame = channel.read()
        while (frame.type != GatewayProtocol.READY) frame = channel.read()
        Blufi().encode(Commands().movement(400, 0), 247).forEach { channel.send(GatewayProtocol.WRITE, it) }
        do frame = channel.read() while (frame.type != GatewayProtocol.WRITTEN)
        // GATT code (0 = success) then the Bluetooth write duration measured by the gateway.
        assertEquals(8, frame.payload.size)
        assertEquals(0, frame.int(0))
        assertTrue(frame.int(4) in 0..2000)
        channel.close()
    }

    @Test fun droppedSocketAlsoStopsRobot() {
        serve()
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.OPEN, "AA".toByteArray())
        var frame = channel.read()
        while (frame.type != GatewayProtocol.READY) frame = channel.read()
        channel.close()
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        val session = requireNotNull(sessions.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { session.emergencyOutcome != null })
        assertEquals(1, fake.writes.size)
        assertFalse(fake.closed)
        assertEquals(0, LubaMsg.parseFrom(requireNotNull(Blufi().receive(fake.writes[0]))).driver.todevDevmotionCtrl.setLinearSpeed)
    }

    @Test fun wrongCodeIsRejectedWithoutLink() {
        serve()
        val error = runCatching { GatewayChannel.open("127.0.0.1", server.localPort, "111111") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Code"))
        assertNull(links.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun scanThroughGatewayListsRobots() {
        serve()
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.SCAN)
        val results = mutableListOf<Triple<String, String, Int>>()
        while (true) {
            val frame = channel.read()
            if (frame.type == GatewayProtocol.SCAN_RESULT) results += GatewayProtocol.parseScanResult(frame)
            if (frame.type == GatewayProtocol.SCAN_END) break
        }
        channel.send(GatewayProtocol.CLOSE); channel.close()
        assertEquals(listOf(Triple("Luba-VS123", "AA:BB:CC:DD:EE:FF", -58)), results)
    }

    @Test fun gatewayHoldsRobotWithoutPilotAndKeepsItAlive() {
        val shared = robot(keepaliveMs = 100)
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VS123")
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { fake.writes.size >= 2 })
        val sync = LubaMsg.parseFrom(requireNotNull(Blufi().receive(fake.writes[0])))
        assertTrue(sync.hasNet())
        assertEquals(0, fake.writes[0][2].toInt() and 255)
        assertEquals(1, fake.writes[1][2].toInt() and 255)
        assertTrue(shared.state.ready)
        assertNull(shared.release())
        assertTrue(fake.closed)
        assertNull(shared.state.address)
    }

    @Test fun successivePilotsShareLinkSequenceAndStartAtMessageBoundary() {
        serve()
        val first = Recorder()
        val link1 = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", first))
        assertEquals(247, first.ready.poll(3, TimeUnit.SECONDS))
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        assertTrue(link1.write(Blufi().encode(Commands().sync(), 247).single()))
        assertEquals(0, first.written.poll(2, TimeUnit.SECONDS))
        link1.close()
        assertTrue(waitFor { !robot!!.state.pilot })
        // The robot sends a fragmented message: the second pilot arrives in the middle.
        val robotTx = Blufi()
        val big = robotTx.encode(Commands().mowPaths((1L..40L).toList(), 3), 23)
        fake.listener.onNotification(fake, big[0])
        val second = Recorder()
        val link2 = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", second))
        assertEquals(247, second.ready.poll(3, TimeUnit.SECONDS))
        assertNull("no new Bluetooth link", links.poll(100, TimeUnit.MILLISECONDS))
        big.drop(1).forEach { fake.listener.onNotification(fake, it) }
        val whole = robotTx.encode(Commands().sync(), 247).single()
        fake.listener.onNotification(fake, whole)
        assertArrayEquals(whole, second.notifications.poll(2, TimeUnit.SECONDS))
        assertNull(second.notifications.poll(100, TimeUnit.MILLISECONDS))
        // The second pilot restarts its sequence at 0: the gateway extends it (0 already written).
        val frame = Blufi().encode(Commands().movement(100, 0), 247).single()
        assertTrue(link2.write(frame))
        assertEquals(0, second.written.poll(2, TimeUnit.SECONDS))
        assertEquals(1, fake.writes.last()[2].toInt() and 255)
        assertArrayEquals(frame.copyOfRange(3, frame.size), fake.writes.last().copyOfRange(3, frame.size))
        link2.close()
    }

    @Test fun scanWhileGatewayHoldsRobotReportsIt() {
        val shared = robot()
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VSDEMO01")
        assertTrue(waitFor { shared.state.ready })
        serve(shared = shared)
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.SCAN)
        val result = GatewayProtocol.parseScanResult(channel.until(GatewayProtocol.SCAN_RESULT))
        assertEquals("Luba-VSDEMO01" to "AA:BB:CC:DD:EE:FF", result.first to result.second)
        channel.send(GatewayProtocol.CLOSE); channel.close()
    }

    /** Robot off: the gateway tries the Bluetooth connection without ever succeeding. */
    private fun silentRobot() = GatewayRobot({ _, listener -> FakeLink(listener).also { links.offer(it) } }, { reports += it }).also { robot = it }

    @Test fun scanWhileRobotOffDoesNotReportPendingConnection() {
        val shared = silentRobot()
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VSDEMO01")
        assertTrue(shared.state.connecting)
        assertNull(shared.connected())
        assertTrue(shared.state.label.contains("éteint") || shared.state.label.contains("Connexion"))
        serve(shared = shared)
        val channel = GatewayChannel.open("127.0.0.1", server.localPort, code)
        channel.send(GatewayProtocol.SCAN)
        // Only the real Bluetooth search answers (measured signal), not the pending connection (0 dBm).
        val result = GatewayProtocol.parseScanResult(channel.until(GatewayProtocol.SCAN_RESULT))
        assertEquals(-58, result.third)
        channel.send(GatewayProtocol.CLOSE); channel.close()
    }

    @Test fun robotBluetoothLossKeepsGatewayReachable() {
        GatewayHealthStore.clear()
        serve()
        val recorder = Recorder()
        val link = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", recorder))
        assertEquals(247, recorder.ready.poll(3, TimeUnit.SECONDS))
        assertTrue(waitFor { GatewayHealthStore.latest.value != null })
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        fake.listener.onLost(fake, 8, "robot éteint")
        assertNotNull(recorder.lost.poll(2, TimeUnit.SECONDS))
        // Robot lost over Bluetooth: the gateway was answering, it stays reachable and its health can still be shown.
        assertEquals(true, GatewayHealthStore.reach.value?.reachable)
        assertEquals(62, GatewayHealthStore.latest.value?.health?.batteryPercent)
        link.close()
    }

    @Test fun unreachableGatewayIsRecordedWithoutPrefix() {
        GatewayHealthStore.update(testHealth)
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.close() }.localPort
        val recorder = Recorder()
        requireNotNull(GatewayLink.open("127.0.0.1", closed, code, "AA:BB:CC:DD:EE:FF", recorder))
        assertTrue(GatewayLink.gatewayUnreachable(requireNotNull(recorder.lost.poll(5, TimeUnit.SECONDS))))
        val reach = requireNotNull(GatewayHealthStore.reach.value)
        assertFalse(reach.reachable)
        assertFalse(reach.detail.startsWith(GatewayLink.UNREACHABLE))
        assertNull(GatewayHealthStore.latest.value)
    }

    @Test fun stoppingGatewayDuringPilotWritesStopThenWarnsPilot() {
        GatewayHealthStore.clear()
        val shared = robot()
        serve(shared = shared)
        val recorder = Recorder()
        val link = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", recorder))
        assertEquals(247, recorder.ready.poll(3, TimeUnit.SECONDS))
        val fake = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        assertTrue(link.write(Blufi().encode(Commands().manualBlades(true, 45), 247).single()))
        assertEquals(0, recorder.written.poll(2, TimeUnit.SECONDS))
        // Gateway stopped (button, notification) in the middle of control: blades OFF then zero speed, before Bluetooth.
        val outcome = shared.shutdown()
        assertTrue(outcome.orEmpty(), outcome.orEmpty().startsWith("STOP écrit"))
        assertEquals(3, fake.writes.size)
        val robotRx = robotAfter(0)
        val decoded = fake.writes.drop(1).map { LubaMsg.parseFrom(requireNotNull(robotRx.receive(it))) }
        assertEquals(0, decoded[0].driver.mowCtrlByHand.cutKnifeCtrl)
        assertEquals(0, decoded[1].driver.todevDevmotionCtrl.setLinearSpeed)
        assertTrue(fake.closed)
        sessions.poll(1, TimeUnit.SECONDS)?.close()
        // The pilot is warned: gateway unreachable (not just a lost robot).
        assertTrue(requireNotNull(recorder.lost.poll(2, TimeUnit.SECONDS)).contains("passerelle arrêtée"))
        assertEquals(false, GatewayHealthStore.reach.value?.reachable)
        link.close()
    }

    @Test fun connectionTimeoutWaitsRetryDelayBeforeNewAttempt() {
        val shared = GatewayRobot({ _, listener -> FakeLink(listener).also { links.offer(it) } }, { reports += it },
            retryMs = 800, connectTimeoutMs = 1000).also { robot = it }
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VS")
        val first = requireNotNull(links.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { first.closed })
        // No new attempt at the next tick: the gateway waits for the retry delay.
        assertNull(links.poll(500, TimeUnit.MILLISECONDS))
        assertNotNull(links.poll(2, TimeUnit.SECONDS))
    }

    @Test fun idleGatewayLeavesTheRobotToTheDeviceUsingIt() {
        // 2026-10-25: robot restarted under the ESP32 in use; the idle Redmi, still retrying, took it back first.
        val opened = LinkedBlockingQueue<Pair<FakeLink, RobotLink.Listener>>()
        val shared = GatewayRobot({ _, listener ->
            FakeLink(listener).also { link ->
                opened.offer(link to listener)
                Thread { Thread.sleep(20); listener.onReady(link, 247) }.start()
            }
        }, { reports += it }, keepaliveMs = 60_000, retryMs = 200, idleRetryMs = 1500).also { robot = it }
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VS")
        val (first, firstListener) = requireNotNull(opened.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { shared.state.ready })
        // Lost within the window after its last use: retried on its own.
        firstListener.onLost(first, 8, "robot redémarré")
        val (second, secondListener) = requireNotNull(opened.poll(2, TimeUnit.SECONDS))
        assertTrue(waitFor { shared.state.ready })
        // Unused beyond the window: the link is kept, but once lost the gateway waits for a pilot.
        Thread.sleep(1600)
        secondListener.onLost(second, 8, "robot redémarré")
        assertNull(opened.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { shared.state.retryPaused })
        assertTrue(shared.state.label, shared.state.label.contains("inutilisée"))
        assertEquals(1, reports.count { it.startsWith("Reconnexion automatique suspendue") })
        // A pilot (or the gateway screen) asking for the robot reconnects it at once.
        shared.connect("AA:BB:CC:DD:EE:FF", "Luba-VS")
        assertNotNull(opened.poll(1, TimeUnit.SECONDS))
        assertTrue(waitFor { shared.state.ready && !shared.state.retryPaused })
    }

    @Test fun samePhoneReconnectingWaitsForItsPreviousSessionAndLearnsTheStop() {
        serve(silenceMs = 400)
        val first = GatewayChannel.open("127.0.0.1", server.localPort, code)
        first.send(GatewayProtocol.OPEN, GatewayProtocol.open("AA:BB:CC:DD:EE:FF", "Luba-VS"))
        first.until(GatewayProtocol.READY)
        // Wi-Fi cut: the first channel goes silent; the same phone comes back at once, before the old session ends.
        val second = GatewayChannel.open("127.0.0.1", server.localPort, code)
        second.send(GatewayProtocol.OPEN, GatewayProtocol.open("AA:BB:CC:DD:EE:FF", "Luba-VS"))
        val event = second.until(GatewayProtocol.EVENT).text()
        assertTrue(event, event.startsWith(EMERGENCY_STOP_EVENT + "\n" + GatewayStopReport.STOP_WRITTEN))
        assertTrue(GatewayStopReport.confirmed("Passerelle : " + event.substringAfter('\n')))
        second.until(GatewayProtocol.READY)
        // The stop is delivered only once.
        second.send(GatewayProtocol.CLOSE); second.close(); first.close()
        assertTrue(waitFor { !robot!!.state.pilot })
        val third = GatewayChannel.open("127.0.0.1", server.localPort, code)
        third.send(GatewayProtocol.OPEN, "AA:BB:CC:DD:EE:FF".toByteArray())
        var frame = third.read()
        while (frame.type != GatewayProtocol.READY) { assertNotEquals(GatewayProtocol.EVENT, frame.type); frame = third.read() }
        third.send(GatewayProtocol.CLOSE); third.close()
    }

    @Test fun busyGatewayIsReachableNotDisconnected() {
        GatewayHealthStore.clear()
        serve(admissionWaitMs = 300)
        val first = Recorder()
        val link1 = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", first))
        assertEquals(247, first.ready.poll(3, TimeUnit.SECONDS))
        GatewayHealthStore.clear()
        val second = Recorder()
        requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", second))
        val reason = requireNotNull(second.lost.poll(3, TimeUnit.SECONDS))
        assertTrue(reason, reason.contains("occupée"))
        assertFalse(GatewayLink.gatewayUnreachable(reason))
        assertEquals(true, GatewayHealthStore.reach.value?.reachable)
        // The current session is not touched.
        assertNull(first.lost.poll(300, TimeUnit.MILLISECONDS))
        link1.close()
    }

    @Test fun robotRequestedByPilotIsRemembered() {
        serve()
        val recorder = Recorder()
        val link = requireNotNull(GatewayLink.open("127.0.0.1", server.localPort, code, "AA:BB:CC:DD:EE:FF", recorder, name = "Luba-VSDEMO01"))
        assertEquals(247, recorder.ready.poll(3, TimeUnit.SECONDS))
        assertEquals("AA:BB:CC:DD:EE:FF" to "Luba-VSDEMO01", remembered.last())
        assertEquals("Luba-VSDEMO01", robot!!.state.name)
        link.close()
    }

    @Test fun openFrameCarriesOptionalName() {
        assertEquals("AA" to "Luba", GatewayProtocol.parseOpen(GatewayProtocol.Frame(GatewayProtocol.OPEN, GatewayProtocol.open("AA", "Luba"))))
        assertEquals("AA" to null, GatewayProtocol.parseOpen(GatewayProtocol.Frame(GatewayProtocol.OPEN, GatewayProtocol.open("AA", null))))
        assertFalse(GatewayStopReport.confirmed("message fragmenté en cours : arrêt non injectable"))
    }

    /** Robot-side receiver that already accepted the frame with sequence [previous]: enforces continuity. */
    private fun robotAfter(previous: Int) = Blufi().also { it.receive(byteArrayOf(0x4d, 0, previous.toByte(), 1, 0x08)) }
}
