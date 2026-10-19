package fr.luba.poc.gateway

import fr.luba.poc.ble.RobotLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ESP32 gateway updated over Wi-Fi: the pilot sends it its setting (CONFIG frame) only if it announces the `ota`
 * capability, sends it again when it changes, and receives the restart announcement before the session closes.
 */
class GatewayOtaTest {
    private val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
    private val received = LinkedBlockingQueue<GatewayProtocol.Frame>()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    private var link: GatewayLink? = null

    /** Minimal gateway, like session.cpp: challenge, WELCOME with [caps], then records the pilot's frames. */
    private fun startGateway(caps: String?) {
        Thread {
            val s = runCatching { server.accept() }.getOrNull() ?: return@Thread
            socket = s
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(s.getOutputStream()).also { output = it }
            GatewayProtocol.write(out, GatewayProtocol.CHALLENGE, byteArrayOf(GatewayProtocol.VERSION.toByte()) + ByteArray(GatewayProtocol.NONCE_BYTES))
            GatewayProtocol.read(input)  // HELLO
            val welcome = GatewayProtocol.GATEWAY_NAME + (caps?.let { "\ncaps=$it" } ?: "")
            GatewayProtocol.write(out, GatewayProtocol.WELCOME, welcome.toByteArray())
            Thread { runCatching { while (true) { Thread.sleep(200); GatewayProtocol.write(out, GatewayProtocol.PING) } } }.apply { isDaemon = true }.start()
            runCatching { while (true) received.offer(GatewayProtocol.read(input)) }
        }.apply { isDaemon = true }.start()
    }

    private class Listener : RobotLink.Listener {
        val events = LinkedBlockingQueue<String>()
        val lost = LinkedBlockingQueue<Int>()
        override fun onReady(link: RobotLink, mtu: Int) { }
        override fun onLost(link: RobotLink, code: Int, reason: String) { lost.offer(code) }
        override fun onFailure(link: RobotLink, reason: String) { lost.offer(Int.MIN_VALUE) }
        override fun onWritten(link: RobotLink, code: Int) { }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) { }
        override fun onNotification(link: RobotLink, bytes: ByteArray) { }
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) { events.offer(kind) }
    }

    @After fun close() { link?.close(); server.close(); socket?.close(); GatewayOta.update(false) }

    private fun nextOfType(type: Int, timeoutMs: Long = 3000): GatewayProtocol.Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
            if (f.type == type) return f
        }
        return null
    }

    @Test fun configSentToOtaGatewayAndResentOnChange() {
        GatewayOta.update(false)
        startGateway("ota")
        link = GatewayLink.open("127.0.0.1", server.localPort, "123456", "AA:BB:CC:DD:EE:FF", Listener())
        val first = nextOfType(GatewayProtocol.CONFIG)
        assertNotNull("setting sent on connection", first)
        assertEquals("ota_during_mowing=0", first!!.text())
        GatewayOta.update(true)
        assertEquals("ota_during_mowing=1", nextOfType(GatewayProtocol.CONFIG)?.text())
    }

    @Test fun noConfigFrameForGatewayWithoutOta() {
        GatewayOta.update(true)
        startGateway(null)
        link = GatewayLink.open("127.0.0.1", server.localPort, "123456", "AA:BB:CC:DD:EE:FF", Listener())
        assertNotNull("OPEN received", nextOfType(GatewayProtocol.OPEN))
        GatewayOta.update(false)
        // An unknown frame would close the session of an older gateway (Redmi, ESP32 0.1.0).
        assertTrue(received.none { it.type == GatewayProtocol.CONFIG })
        Thread.sleep(400)
        assertTrue(received.none { it.type == GatewayProtocol.CONFIG })
        assertFalse(link!!.supportsOta)
    }

    @Test fun updateAnnouncedThenSessionClosedIsGatewayLoss() {
        startGateway("ota")
        val listener = Listener()
        link = GatewayLink.open("127.0.0.1", server.localPort, "123456", "AA:BB:CC:DD:EE:FF", listener)
        assertNotNull(nextOfType(GatewayProtocol.OPEN))
        GatewayProtocol.write(output!!, GatewayProtocol.EVENT, "${GatewayProtocol.OTA_BEGIN_EVENT}\nMise à jour".toByteArray())
        val deadline = System.currentTimeMillis() + 3000
        var seen = false
        while (!seen && System.currentTimeMillis() < deadline) seen = listener.events.poll(200, TimeUnit.MILLISECONDS) == "gateway_" + GatewayProtocol.OTA_BEGIN_EVENT
        assertTrue("announcement received", seen)
        socket!!.close()
        val code = listener.lost.poll(5, TimeUnit.SECONDS)
        assertNotNull(code)
        assertTrue("loss of the gateway itself ($code)", GatewayLink.gatewayDisconnected(code!!))
    }
}
