package fr.luba.poc.auth

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The broker refused the connection or a subscription ([reasonCode]: CONNACK return code, or 0x80 for SUBACK). */
class MqttRefusedException(val reasonCode: Int, message: String) : IOException(message)

/**
 * Minimal MQTT 3.1.1 subscriber for the account broker: CONNECT (clean session, user name and password), SUBSCRIBE,
 * reception of PUBLISH at any QoS (acknowledged as the protocol requires), keep-alive pings and DISCONNECT. Its only
 * publication is the account bind of the Aliyun broker ([publishAccountBind]), a message to the broker and not to the
 * robot: the cloud stays read-only for the robot ([CloudReadOnly]).
 *
 * Replaces the Eclipse Paho client (unmaintained since 2019) for the only use the app has of it. [open] gives a
 * connected socket (TLS for the real broker, see [tls]); [onMessage] and [onLost] run on the reader thread.
 */
internal class MqttSubscriber(
    private val open: () -> Socket,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val keepAliveSeconds: Int = 30,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onLost: (Throwable) -> Unit,
    /** Larger messages are read and dropped (the reports used by the app stay far below). */
    private val maxPayloadBytes: Int = 1_048_576,
) : AutoCloseable {
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val closed = AtomicBoolean(false)
    private var nextPacketId = 1
    private var reader: Thread? = null
    private var pinger: ScheduledExecutorService? = null

    /** Opens the socket, sends CONNECT and waits for an accepted CONNACK within [timeoutMs]. */
    fun connect(timeoutMs: Int = 15_000) {
        val s = open()
        socket = s
        s.soTimeout = timeoutMs
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        val body = ByteArrayOutputStream().apply {
            writeString("MQTT")
            write(4) // protocol level 3.1.1
            write(0x80 or 0x40 or 0x02) // user name, password, clean session
            write(keepAliveSeconds shr 8); write(keepAliveSeconds and 0xFF)
            writeString(clientId); writeString(username); writeString(password)
        }
        send(CONNECT, body.toByteArray())
        val (type, payload) = readPacket()
        if (type != CONNACK || payload.size < 2) throw IOException("MQTT: CONNACK expected, packet type $type received")
        val code = payload[1].toInt() and 0xFF
        if (code != 0) throw MqttRefusedException(code, "MQTT connection refused (code $code)")
    }

    /** Subscribes to [topics] at QoS 0, waits for the SUBACK, then starts the reader and the keep-alive. */
    fun subscribe(topics: List<String>, timeoutMs: Int = 15_000) {
        require(topics.isNotEmpty())
        val id = nextPacketId++
        val body = ByteArrayOutputStream().apply {
            write(id shr 8); write(id and 0xFF)
            topics.forEach { writeString(it); write(0) }
        }
        send(SUBSCRIBE or 0x02, body.toByteArray())
        socket?.soTimeout = timeoutMs
        val (type, payload) = readPacket()
        if (type != SUBACK || payload.size < 2 + topics.size) throw IOException("MQTT: SUBACK expected, packet type $type received")
        val refused = payload.drop(2).map { it.toInt() and 0xFF }.firstOrNull { it == 0x80 }
        if (refused != null) throw MqttRefusedException(refused, "MQTT subscription refused")
        startReading()
    }

    /**
     * Account bind of the Aliyun broker (PyMammotion `AliyunMQTTTransport._run`), QoS 1 without waiting for the PUBACK
     * (skipped by the reader). Any other topic is refused.
     */
    fun publishAccountBind(topic: String, payload: ByteArray) {
        require(topic.startsWith("/sys/") && topic.endsWith(ACCOUNT_BIND_SUFFIX)) { "MQTT: only the Aliyun account bind is published" }
        val id = nextPacketId++
        val body = ByteArrayOutputStream().apply { writeString(topic); write(id shr 8); write(id and 0xFF); write(payload) }
        send(PUBLISH or 0x02, body.toByteArray())
    }

    private fun startReading() {
        // No packet from the broker for 1.5 keep-alive periods: the connection is deemed lost (MQTT 3.1.1 § 3.1.2.10).
        socket?.soTimeout = keepAliveSeconds * 1500
        pinger = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-ping").apply { isDaemon = true } }.also {
            val period = (keepAliveSeconds * 750L).coerceAtLeast(100)
            it.scheduleWithFixedDelay({ runCatching { send(PINGREQ, ByteArray(0)) }.onFailure(::lost) }, period, period, TimeUnit.MILLISECONDS)
        }
        reader = Thread({ readLoop() }, "mqtt-reader").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val header = input.read()
                if (header < 0) throw EOFException("MQTT connection closed by the broker")
                val length = readRemainingLength()
                when (header shr 4) {
                    PUBLISH shr 4 -> receivePublish(header, length)
                    PUBREL shr 4 -> { val p = readBytes(length); send(PUBCOMP, p.copyOf(2)) }
                    else -> skip(length) // PINGRESP, unsolicited acknowledgements
                }
            }
        } catch (e: Exception) {
            lost(if (e is SocketTimeoutException) IOException("MQTT broker silent for ${keepAliveSeconds * 3 / 2} s", e) else e)
        }
    }

    private fun receivePublish(header: Int, length: Int) {
        val qos = (header shr 1) and 0x03
        val topicLength = (readByte() shl 8) or readByte()
        val topic = String(readBytes(topicLength), Charsets.UTF_8)
        var left = length - 2 - topicLength
        var packetId = 0
        if (qos > 0) { packetId = (readByte() shl 8) or readByte(); left -= 2 }
        if (left < 0) throw IOException("MQTT: malformed PUBLISH")
        val payload = if (left > maxPayloadBytes) { skip(left); null } else readBytes(left)
        val id = byteArrayOf((packetId shr 8).toByte(), packetId.toByte())
        when (qos) {
            1 -> send(PUBACK, id)
            2 -> send(PUBREC, id)
        }
        if (payload != null) onMessage(topic, payload)
    }

    private fun lost(cause: Throwable) {
        if (closed.getAndSet(true)) return
        shutdown()
        onLost(cause)
    }

    /** Sends DISCONNECT (best effort) and closes the socket; [onLost] is not called. */
    override fun close() {
        if (closed.getAndSet(true)) return
        runCatching { send(DISCONNECT, ByteArray(0)) }
        shutdown()
    }

    private fun shutdown() {
        pinger?.shutdownNow()
        runCatching { socket?.close() }
    }

    private fun send(type: Int, body: ByteArray) {
        val packet = ByteArrayOutputStream(body.size + 5).apply {
            write(type)
            var length = body.size
            do {
                var digit = length % 128
                length /= 128
                if (length > 0) digit = digit or 0x80
                write(digit)
            } while (length > 0)
            write(body)
        }.toByteArray()
        synchronized(this) { output.write(packet); output.flush() }
    }

    private fun readPacket(): Pair<Int, ByteArray> {
        val header = input.read()
        if (header < 0) throw EOFException("MQTT connection closed by the broker")
        return (header and 0xF0) to readBytes(readRemainingLength())
    }

    private fun readRemainingLength(): Int {
        var value = 0
        var multiplier = 1
        repeat(4) {
            val digit = readByte()
            value += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) return value
            multiplier *= 128
        }
        throw IOException("MQTT: malformed remaining length")
    }

    private fun readByte(): Int = input.read().also { if (it < 0) throw EOFException("MQTT connection closed by the broker") }

    private fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(bytes, read, count - read)
            if (n < 0) throw EOFException("MQTT connection closed by the broker")
            read += n
        }
        return bytes
    }

    private fun skip(count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) { readByte(); left-- } else left -= n
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(bytes.size shr 8); write(bytes.size and 0xFF); write(bytes)
    }

    companion object {
        private const val CONNECT = 0x10
        private const val CONNACK = 0x20
        private const val PUBLISH = 0x30
        private const val PUBACK = 0x40
        private const val PUBREC = 0x50
        private const val PUBREL = 0x60
        private const val PUBCOMP = 0x70
        private const val SUBSCRIBE = 0x80
        private const val SUBACK = 0x90
        private const val PINGREQ = 0xC0
        private const val DISCONNECT = 0xE0

        const val ACCOUNT_BIND_SUFFIX = "/app/up/account/bind"

        /**
         * TLS socket to [host]:[port], hostname checked against the certificate like an HTTPS connection. [factory]: the
         * system trust store by default, the Aliyun IoT roots for the Aliyun broker ([AliyunBroker.socketFactory]).
         */
        fun tls(host: String, port: Int, timeoutMs: Int = 15_000, factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory): Socket {
            val plain = Socket()
            val socket = try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                // Layered over the connected socket with the host name, so the handshake carries SNI.
                factory.createSocket(plain, host, port, true) as SSLSocket
            } catch (e: Exception) {
                runCatching { plain.close() }
                throw e
            }
            try {
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
                    throw IOException("MQTT: TLS certificate does not match $host")
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            return socket
        }
    }
}
