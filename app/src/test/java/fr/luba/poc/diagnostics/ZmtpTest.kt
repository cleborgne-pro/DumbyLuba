package fr.luba.poc.diagnostics

import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.DevNetProto.GetNetworkInfoRsp
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.ExternalNavigationPolicy
import fr.luba.poc.protocol.PERCEPTION_TOPIC
import fr.luba.poc.protocol.PERCEPTION_ZMQ_URL
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ZmtpTest {
    /** Fake robot: ZMTP 3.0 NULL greeting, READY [peerType], checks the subscription if PUB, then publishes [messages]. */
    private fun fakePeer(peerType: String, messages: List<List<ByteArray>>, received: MutableList<String>): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                out.write(Zmtp.greeting().also { it[33] = 1 }) // as-server
                val greeting = ByteArray(64).also { input.readFully(it) }
                received += "greeting ${greeting[10]}.${greeting[11]} ${String(greeting, 12, 4)}"
                val ready = Zmtp.readFrame(input)
                received += "ready ${Zmtp.readyProperties(ready.body)["Socket-Type"]}"
                out.write(Zmtp.readyCommand(peerType))
                if (peerType == "PUB") received += "subscribe ${Zmtp.hex(Zmtp.readFrame(input).body)}"
                for (parts in messages) parts.forEachIndexed { i, p -> out.write(Zmtp.frame(p, more = i < parts.lastIndex)) }
                out.flush()
            }
        }
        return server
    }

    @Test fun subscribesToPublisherAndReadsMultipartMessages() {
        val received = mutableListOf<String>()
        val big = ByteArray(1000) { it.toByte() }
        val server = fakePeer("PUB", listOf(listOf("perception_post_result".toByteArray(), big), listOf(byteArrayOf(1, 2, 3))), received)
        Socket("127.0.0.1", server.localPort).use { s ->
            val input = DataInputStream(s.getInputStream())
            val hs = Zmtp.handshake(input, s.getOutputStream(), "SUB")
            assertEquals("PUB", hs.peerSocketType)
            assertEquals("3.0", hs.version)
            assertEquals("NULL", hs.mechanism)
            val first = Zmtp.readMessage(input)
            assertEquals(2, first.size)
            assertEquals("perception_post_result", String(first[0]))
            assertArrayEquals(big, first[1])
            assertArrayEquals(byteArrayOf(1, 2, 3), Zmtp.readMessage(input).single())
        }
        server.close()
        assertEquals(listOf("greeting 3.0 NULL", "ready SUB", "subscribe 01"), received)
    }

    @Test fun reportsPeerTypeForReconnection() {
        val received = mutableListOf<String>()
        val server = fakePeer("PUSH", emptyList(), received)
        Socket("127.0.0.1", server.localPort).use { s ->
            val hs = Zmtp.handshake(DataInputStream(s.getInputStream()), s.getOutputStream(), "SUB")
            assertEquals("PUSH", hs.peerSocketType)
            assertEquals("PULL", Zmtp.compatible(hs.peerSocketType))
        }
        server.close()
        assertEquals("SUB", Zmtp.compatible("XPUB"))
    }

    @Test fun longFramesUseEightByteSize() {
        val frame = Zmtp.frame(ByteArray(300), more = true)
        assertEquals(0x03, frame[0].toInt())
        assertEquals(1 + 8 + 300, frame.size)
        val parsed = Zmtp.readFrame(DataInputStream(frame.inputStream()))
        assertTrue(parsed.more); assertEquals(300, parsed.body.size)
    }

    @Test fun rejectsNonZmtpStreams() {
        val bytes = ByteArrayOutputStream().apply { write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); write(ByteArray(64)) }.toByteArray()
        val error = runCatching { Zmtp.handshake(DataInputStream(bytes.inputStream()), ByteArrayOutputStream(), "SUB") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("Pas une salutation ZMTP"))
    }

    @Test fun previewShowsTextOrHex() {
        assertEquals("\"abc\"", Zmtp.preview("abc".toByteArray()))
        assertEquals("00ff", Zmtp.preview(byteArrayOf(0, -1)))
    }

    @Test fun bridgeCommandMatchesMammotionApp() {
        val on = LubaMsg.parseFrom(Commands().perceptionZmq(true))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_ESP, on.msgtype)
        assertEquals(MsgDevice.DEV_COMM_ESP, on.rcver)
        val zmq = on.net.todevSetDds2Zmq
        assertTrue(zmq.isEnable)
        assertEquals(PERCEPTION_TOPIC, zmq.rxTopicName)
        assertEquals(PERCEPTION_ZMQ_URL, zmq.txZmqUrl)
        assertFalse(LubaMsg.parseFrom(Commands().perceptionZmq(false)).net.todevSetDds2Zmq.isEnable)
        assertEquals(1, LubaMsg.parseFrom(Commands().networkInfo()).net.todevNetworkinfoReq.reqIds)
        assertTrue(ExternalNavigationPolicy.allows(Commands().perceptionZmq(true)))
    }

    @Test fun robotIpComesFromNetworkInfoResponse() {
        // 192.168.0.42 in network order read as little-endian: 0x2A00A8C0.
        assertEquals("192.168.0.42", ProbeZmq.ipv4(0x2A00A8C0))
        assertEquals("192.168.0.42", ProbeZmq.ipv4(0xC0A8002A.toInt()))
        assertNull(ProbeZmq.ipv4(0))
        val probe = SensorProbe(); probe.start(0)
        probe.accept(LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_ESP).setSender(MsgDevice.DEV_COMM_ESP)
            .setNet(DevNet.newBuilder().setToappNetworkinfoRsp(GetNetworkInfoRsp.newBuilder().setWifiSsid("Jardin")
                .setIp(0x2A00A8C0).setMask(0x00FFFFFF).setWifiRssi(-60))).build(), 10)
        val z = probe.snapshot().zmq
        assertEquals("192.168.0.42", z.robotIp)
        assertEquals("Jardin · 192.168.0.42 · masque 255.255.255.0 · RSSI -60", z.network)
    }
}
