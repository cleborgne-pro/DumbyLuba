package fr.luba.poc.auth

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [MqttSubscriber] against a scripted broker on a loopback socket: packets checked byte by byte. */
class MqttSubscriberTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val messages = LinkedBlockingQueue<Pair<String, ByteArray>>()
    private val lost = LinkedBlockingQueue<Throwable>()

    @After fun closeServer() = server.close()

    private fun subscriber(keepAlive: Int = 30, maxPayload: Int = 1_048_576) = MqttSubscriber(
        open = { Socket(InetAddress.getLoopbackAddress(), server.localPort) },
        clientId = "client-1", username = "user", password = "secret", keepAliveSeconds = keepAlive,
        onMessage = { topic, payload -> messages.add(topic to payload) }, onLost = { lost.add(it) }, maxPayloadBytes = maxPayload,
    )

    /** Broker side of one connection: reads client packets, writes scripted ones. */
    private class Broker(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output: OutputStream = socket.getOutputStream()

        fun read(): Pair<Int, ByteArray> {
            val header = input.readUnsignedByte()
            var length = 0
            var multiplier = 1
            while (true) {
                val digit = input.readUnsignedByte()
                length += (digit and 0x7F) * multiplier
                if (digit and 0x80 == 0) break
                multiplier *= 128
            }
            return header to ByteArray(length).also(input::readFully)
        }

        fun write(header: Int, body: ByteArray) {
            val out = ByteArrayOutputStream().apply {
                write(header)
                var length = body.size
                do {
                    var digit = length % 128
                    length /= 128
                    if (length > 0) digit = digit or 0x80
                    write(digit)
                } while (length > 0)
                write(body)
            }
            output.write(out.toByteArray()); output.flush()
        }

        fun publish(topic: String, payload: ByteArray, qos: Int = 0, id: Int = 7) {
            val t = topic.toByteArray()
            val body = ByteArrayOutputStream().apply {
                write(t.size shr 8); write(t.size and 0xFF); write(t)
                if (qos > 0) { write(id shr 8); write(id and 0xFF) }
                write(payload)
            }
            write(0x30 or (qos shl 1), body.toByteArray())
        }
    }

    private fun mqttString(value: String) = value.toByteArray().let { byteArrayOf((it.size shr 8).toByte(), it.size.toByte()) + it }

    /** Runs [script] as the broker of the next connection, on its own thread; failures are rethrown by [join]. */
    private fun broker(script: Broker.() -> Unit): () -> Unit {
        val failure = LinkedBlockingQueue<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try { server.accept().use { Broker(it).script() } } catch (e: Throwable) { failure.add(e) } finally { done.countDown() }
        }.start()
        return { assertTrue(done.await(10, TimeUnit.SECONDS)); failure.poll()?.let { throw it } }
    }

    private fun Broker.acceptConnection() {
        val (type, body) = read()
        assertEquals(0x10, type)
        val expected = mqttString("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 30) + mqttString("client-1") + mqttString("user") + mqttString("secret")
        assertArrayEquals(expected, body)
        write(0x20, byteArrayOf(0, 0))
    }

    private fun Broker.acceptSubscription(vararg topics: String) {
        val (type, body) = read()
        assertEquals(0x82, type)
        val expected = byteArrayOf(0, 1) + topics.fold(ByteArray(0)) { acc, t -> acc + mqttString(t) + byteArrayOf(0) }
        assertArrayEquals(expected, body)
        write(0x90, byteArrayOf(0, 1) + ByteArray(topics.size))
    }

    @Test fun `publishes only the Aliyun account bind, at QoS 1`() {
        val bind = "/sys/a1APPPK0000/APPDEVICE-DEMO/app/up/account/bind"
        val join = broker {
            acceptConnection(); acceptSubscription("/sys/a1APPPK0000/APPDEVICE-DEMO/app/down/account/bind_reply")
            val (type, body) = read()
            assertEquals(0x32, type)
            assertArrayEquals(mqttString(bind) + byteArrayOf(0, 2) + "{}".toByteArray(), body)
            write(0x40, byteArrayOf(0, 2)) // PUBACK, skipped by the reader
            publish("/sys/a1APPPK0000/APPDEVICE-DEMO/app/down/account/bind_reply", """{"code":200}""".toByteArray())
            assertEquals(0xE0, read().first)
        }
        val client = subscriber()
        client.connect(); client.subscribe(listOf("/sys/a1APPPK0000/APPDEVICE-DEMO/app/down/account/bind_reply"))
        try { client.publishAccountBind("/sys/pk/dn/thing/service/invoke", ByteArray(1)); fail("robot topic published") } catch (_: IllegalArgumentException) { }
        client.publishAccountBind(bind, "{}".toByteArray())
        assertEquals("""{"code":200}""", messages.poll(5, TimeUnit.SECONDS)?.second?.let(::String))
        client.close(); join()
        assertEquals(null, lost.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `connects, subscribes and delivers QoS 0 messages`() {
        val join = broker {
            acceptConnection(); acceptSubscription("/a/+/post", "/b/+/post")
            publish("/a/x/post", "hello".toByteArray())
            publish("/a/big/post", ByteArray(20_000) { it.toByte() }) // three-byte remaining length
            assertEquals(0xE0, read().first) // DISCONNECT on close
        }
        val client = subscriber()
        client.connect(); client.subscribe(listOf("/a/+/post", "/b/+/post"))
        val first = messages.poll(5, TimeUnit.SECONDS)
        assertEquals("/a/x/post", first!!.first); assertArrayEquals("hello".toByteArray(), first.second)
        val big = messages.poll(5, TimeUnit.SECONDS)
        assertEquals(20_000, big!!.second.size); assertEquals(19_999.toByte(), big.second.last())
        client.close(); join()
        assertEquals(null, lost.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `acknowledges QoS 1 and QoS 2 messages`() {
        val join = broker {
            acceptConnection(); acceptSubscription("/t")
            publish("/t", byteArrayOf(1), qos = 1, id = 0x0102)
            val (puback, ackBody) = read(); assertEquals(0x40, puback); assertArrayEquals(byteArrayOf(1, 2), ackBody)
            publish("/t", byteArrayOf(2), qos = 2, id = 9)
            val (pubrec, recBody) = read(); assertEquals(0x50, pubrec); assertArrayEquals(byteArrayOf(0, 9), recBody)
            write(0x62, byteArrayOf(0, 9))
            val (pubcomp, compBody) = read(); assertEquals(0x70, pubcomp); assertArrayEquals(byteArrayOf(0, 9), compBody)
        }
        val client = subscriber()
        client.connect(); client.subscribe(listOf("/t"))
        assertArrayEquals(byteArrayOf(1), messages.poll(5, TimeUnit.SECONDS)!!.second)
        assertArrayEquals(byteArrayOf(2), messages.poll(5, TimeUnit.SECONDS)!!.second)
        join(); client.close()
    }

    @Test fun `refused connection reports the CONNACK code`() {
        val join = broker { read(); write(0x20, byteArrayOf(0, 5)) }
        try {
            subscriber().connect(); fail("refusal expected")
        } catch (e: MqttRefusedException) {
            assertEquals(5, e.reasonCode)
        }
        join()
    }

    @Test fun `refused subscription is an error`() {
        val join = broker { acceptConnection(); read(); write(0x90, byteArrayOf(0, 1, 0x80.toByte())) }
        val client = subscriber()
        client.connect()
        try {
            client.subscribe(listOf("/t")); fail("refusal expected")
        } catch (e: MqttRefusedException) {
            assertEquals(0x80, e.reasonCode)
        }
        client.close(); join()
    }

    @Test fun `oversized messages are dropped without losing the stream`() {
        val join = broker {
            acceptConnection(); acceptSubscription("/t")
            publish("/t", ByteArray(300)); publish("/t", byteArrayOf(42))
            read()
        }
        val client = subscriber(maxPayload = 100)
        client.connect(); client.subscribe(listOf("/t"))
        val message = messages.poll(5, TimeUnit.SECONDS)
        assertArrayEquals(byteArrayOf(42), message!!.second)
        client.close(); join()
    }

    @Test fun `broker closing the connection is reported once`() {
        val join = broker { acceptConnection(); acceptSubscription("/t") }
        val client = subscriber()
        client.connect(); client.subscribe(listOf("/t"))
        join()
        assertNotNull(lost.poll(5, TimeUnit.SECONDS))
        client.close()
        assertEquals(null, lost.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `pings the broker and reports a silent one`() {
        val join = broker {
            val (type, body) = read(); assertEquals(0x10, type); assertEquals(1, body[9].toInt()) // keep-alive 1 s
            write(0x20, byteArrayOf(0, 0))
            read(); write(0x90, byteArrayOf(0, 1, 0))
            assertEquals(0xC0, read().first) // PINGREQ after 0.75 s, never answered
            Thread.sleep(2_000)
        }
        val client = MqttSubscriber(
            open = { Socket(InetAddress.getLoopbackAddress(), server.localPort) }, clientId = "client-1", username = "user", password = "secret",
            keepAliveSeconds = 1, onMessage = { _, _ -> }, onLost = { lost.add(it) },
        )
        client.connect(); client.subscribe(listOf("/t"))
        assertNotNull(lost.poll(5, TimeUnit.SECONDS))
        join()
    }
}
