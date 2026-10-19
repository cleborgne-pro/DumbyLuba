package fr.luba.poc.gateway

import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.autonomy.LegTransport
import fr.luba.poc.ble.RobotLink
import fr.luba.poc.gateway.GatewayProtocol.Frame
import fr.luba.poc.i18n.tr
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Explicit refusal from the gateway (wrong code, busy, version): it is reachable, it answered. [wire] is the refusal as
 * received (compared by the pilot, see [GatewayLink]); the message is translated for display ([GatewayWireText]).
 */
class GatewayDenied(val wire: String) : IOException(GatewayWireText.display(wire))

/** Authenticated connection to the gateway (the code never travels in clear). */
class GatewayChannel private constructor(val socket: Socket, val input: DataInputStream, val output: DataOutputStream) {
    /** Capabilities announced by the gateway (see [GatewayProtocol.CAP_AUTONOMY]); empty for an older gateway. */
    @Volatile var capabilities: Set<String> = emptySet(); private set
    fun send(type: Int, payload: ByteArray = ByteArray(0)) = GatewayProtocol.write(output, type, payload)
    fun read(): Frame = GatewayProtocol.read(input)
    fun close() { runCatching { socket.close() } }

    companion object {
        /** Maximum wait for the challenge: the gateway may hold the pilot (PING) while the previous session ends. */
        const val ADMISSION_MS = GatewayAdmission.WAIT_MS + 2000

        fun open(host: String, port: Int, code: String, connectTimeoutMs: Int = 3000, silenceMs: Long = GatewayProtocol.SILENCE_MS): GatewayChannel {
            require(host.isNotBlank()) { tr("gateway address unknown", "adresse de la passerelle inconnue") }
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.tcpNoDelay = true
                socket.soTimeout = silenceMs.toInt()
                val channel = GatewayChannel(socket, DataInputStream(BufferedInputStream(socket.getInputStream())),
                    DataOutputStream(BufferedOutputStream(socket.getOutputStream())))
                val deadline = System.currentTimeMillis() + ADMISSION_MS
                var challenge = channel.read()
                while (challenge.type == GatewayProtocol.PING && System.currentTimeMillis() < deadline) challenge = channel.read()
                if (challenge.type == GatewayProtocol.DENIED) throw GatewayDenied(challenge.text())
                check(challenge.type == GatewayProtocol.CHALLENGE && challenge.payload.isNotEmpty()) { tr("unexpected answer", "réponse inattendue") }
                check(challenge.payload[0].toInt() == GatewayProtocol.VERSION) {
                    tr("different version: update both phones", "version différente : mettre à jour les deux téléphones")
                }
                channel.send(GatewayProtocol.HELLO, GatewayProtocol.hello(code, challenge.payload.copyOfRange(1, challenge.payload.size)))
                val answer = channel.read()
                if (answer.type == GatewayProtocol.DENIED) throw GatewayDenied(answer.text())
                check(answer.type == GatewayProtocol.WELCOME) { tr("unexpected answer", "réponse inattendue") }
                channel.capabilities = GatewayProtocol.capabilities(answer)
                GatewayHealthStore.reached()
                return channel
            } catch (error: Exception) { runCatching { socket.close() }; throw error }
        }
    }
}

/**
 * [RobotLink] of the pilot phone: each BluFi frame goes over Wi-Fi to the gateway, which writes it to the robot and
 * returns the GATT confirmation. The pilot keeps all its controls; gateway silence beyond [GatewayProtocol.SILENCE_MS]
 * counts as a link loss.
 *
 * Reasons received from the gateway (DENIED, FAILURE, LOST, EVENT) are wire texts: they are shown through
 * [GatewayWireText.display].
 */
class GatewayLink private constructor(private val host: String, private val port: Int, private val code: String,
    private val address: String, private val name: String?, private val listener: RobotLink.Listener) : RobotLink {
    private val outbox = LinkedBlockingDeque<Frame>()
    @Volatile private var closed = false
    @Volatile private var channel: GatewayChannel? = null
    private var lastHealth: GatewayHealth? = null
    private var lastHealthAt = 0L
    override val description = tr("gateway $host", "passerelle $host")

    /** The gateway can track a prepared route without the pilot (capability announced on connection). */
    val supportsLegs: Boolean get() = channel?.capabilities?.contains(GatewayProtocol.CAP_AUTONOMY) == true
    /** Gateway able to run the station exit itself (otherwise: exit driven from here, over Wi-Fi). */
    val supportsDockExit: Boolean get() = supportsLegs && channel?.capabilities?.contains(GatewayProtocol.CAP_DOCK_EXIT) == true
    /** Gateway updated over Wi-Fi (ESP32): it receives the [GatewayOta.duringMowing] setting. */
    val supportsOta: Boolean get() = channel?.capabilities?.contains(GatewayProtocol.CAP_OTA) == true

    /** Sends the pilot settings; no effect if the gateway does not know them (no unknown frame sent). */
    fun pushConfig() {
        if (!closed && supportsOta) outbox.offer(Frame(GatewayProtocol.CONFIG, GatewayProtocol.config(GatewayOta.duringMowing)))
    }

    /**
     * Time until which a Wi-Fi outage is not a link loss: autonomous tracking goes on on the gateway and the pilot takes
     * the link back as soon as Wi-Fi returns. [Long.MAX_VALUE] during tracking; a short delay after a requested stop, so
     * the stop order still gets through.
     */
    @Volatile var holdUntil = 0L
    private fun holding() = holdUntil > System.currentTimeMillis()

    /** Autonomous tracking frame to the gateway. */
    val legTransport = LegTransport { type, payload -> !closed && outbox.offer(Frame(type, payload)) }

    private fun start() {
        GatewayOta.register(this)
        Thread({ run() }, "gateway-link").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            listener.onEvent(this, "gateway_connect", tr("Connecting to the gateway $host:$port", "Connexion à la passerelle $host:$port"))
            var opened = GatewayChannel.open(host, port, code)
            channel = opened
            if (closed) { opened.close(); return }
            var writer = startWriter(opened)
            if (opened.capabilities.contains(GatewayProtocol.CAP_AUTONOMY)) outbox.offer(Frame(LegFrames.LEG_HELLO))
            pushConfig()
            outbox.offer(Frame(GatewayProtocol.OPEN, GatewayProtocol.open(address, name)))
            listener.onEvent(this, "gateway_open", tr("Gateway authenticated; robot Bluetooth connection requested",
                "Passerelle authentifiée ; connexion Bluetooth du robot demandée"))
            var resumed = false
            while (!closed) {
                try {
                    if (readFrames(opened, resumed)) return
                } catch (outage: Exception) {
                    // Autonomous tracking in progress: the gateway carries on alone, this is not a link loss.
                    if (closed || !holding() || outage !is IOException) throw outage
                    val cause = outage.message ?: outage.javaClass.simpleName
                    listener.onEvent(this, "gateway_outage", tr("Wi-Fi link with the gateway lost ($cause): the robot carries on alone · ${PhoneWifi.describe()}",
                        "Liaison Wi-Fi avec la passerelle perdue ($cause) : le robot poursuit seul · ${PhoneWifi.describe()}"))
                    opened.close(); writer.interrupt(); writer.join(500)
                    opened = reconnect() ?: throw GatewayGaveUp(tr("gateway unreachable for ${OUTAGE_LIMIT_MS / 60_000} min · ${PhoneWifi.describe()}",
                        "passerelle injoignable depuis ${OUTAGE_LIMIT_MS / 60_000} min · ${PhoneWifi.describe()}"))
                    channel = opened
                    // The robot link is reopened BEFORE any kept frame (stop order, writes): otherwise the gateway refuses them.
                    outbox.offerFirst(Frame(GatewayProtocol.OPEN, GatewayProtocol.open(address, name)))
                    if (opened.capabilities.contains(GatewayProtocol.CAP_AUTONOMY)) outbox.offerFirst(Frame(LegFrames.LEG_HELLO))
                    pushConfig()
                    writer = startWriter(opened)
                    resumed = true
                    listener.onEvent(this, "gateway_resumed", tr("Gateway reached again", "Passerelle de nouveau jointe"))
                }
            }
        } catch (gaveUp: GatewayGaveUp) {
            lose(LOST_WIFI, gaveUp.message ?: UNREACHABLE)
        } catch (timeout: SocketTimeoutException) {
            // Connection timeout ("failed to connect …"): gateway unreachable, not a silent gateway (retries of 2026-10-14).
            if (channel == null || timeout.message?.contains("connect", ignoreCase = true) == true)
                lose(LOST_WIFI, tr("gateway unreachable (connection timed out) · ${PhoneWifi.describe()}",
                    "passerelle injoignable (délai de connexion dépassé) · ${PhoneWifi.describe()}") + GatewayHealthStore.weakWifiHint())
            else lose(LOST_SILENT, tr("gateway Wi-Fi silent for ${GatewayProtocol.SILENCE_MS / 1000.0} s · ${PhoneWifi.describe()}",
                "passerelle Wi-Fi muette depuis ${GatewayProtocol.SILENCE_MS / 1000.0} s · ${PhoneWifi.describe()}") + GatewayHealthStore.weakWifiHint())
        } catch (denied: GatewayDenied) {
            // The gateway answered (busy, code, version): it is reachable, this is not a Wi-Fi outage.
            GatewayHealthStore.reached()
            fail(tr("Gateway: ${denied.message}", "Passerelle : ${denied.message}"))
        } catch (error: Exception) {
            val cause = error.message ?: error.javaClass.simpleName
            if (channel == null) fail(tr("$UNREACHABLE: $cause", "$UNREACHABLE : $cause"))
            else lose(LOST_WIFI, tr("gateway Wi-Fi link lost ($cause)", "liaison Wi-Fi passerelle perdue ($cause)"))
        }
    }

    /** Reads frames until the link ends. True: link finished (loss or failure already reported). */
    private fun readFrames(opened: GatewayChannel, resumed: Boolean): Boolean {
        while (!closed) {
            val frame = opened.read()
            when (frame.type) {
                GatewayProtocol.PING -> Unit
                GatewayProtocol.HEALTH -> {
                    GatewayHealth.decode(frame.payload)?.let { received ->
                        val now = System.currentTimeMillis()
                        received.gravity?.let { fr.luba.poc.map.RobotTilt.update(it, now - received.gravityAgeMs) }
                        // Tilt sent every 500 ms: log and health only when the state changes.
                        val health = received.copy(gravity = null, gravityAgeMs = 0)
                        if (health == lastHealth && now - lastHealthAt < GatewayProtocol.HEALTH_MS) return@let
                        lastHealth = health; lastHealthAt = now
                        GatewayHealthStore.update(health)
                        listener.onEvent(this, "gateway_health", tr("Gateway health", "Santé de la passerelle"), mapOf("batteryPercent" to health.batteryPercent,
                            "charging" to health.charging, "batteryTempDeciC" to health.batteryTempDeciC, "wifiRssiDbm" to health.wifiRssiDbm,
                            "wifiMbps" to health.wifiMbps, "thermalStatus" to health.thermalStatus, "powerSave" to health.powerSave,
                            "batteryExempt" to health.batteryExempt, "screenOn" to health.screenOn))
                    }
                }
                GatewayProtocol.DISCOVERING -> listener.onDiscovering(this)
                // Link resumed after a Wi-Fi outage: the pilot already received READY, it does not reset.
                GatewayProtocol.READY -> if (!resumed) listener.onReady(this, frame.int())
                GatewayProtocol.WRITTEN -> {
                    // Bluetooth leg measured by the gateway: the rest of the write delay is Wi-Fi.
                    val gattMs = if (frame.payload.size >= 8) frame.int(4) else -1
                    if (gattMs >= SLOW_GATT_MS) listener.onEvent(this, "gateway_gatt_slow", tr("Slow Bluetooth write on the gateway side", "Écriture Bluetooth lente côté passerelle"),
                        mapOf("gattMs" to gattMs))
                    listener.onWritten(this, frame.int())
                }
                GatewayProtocol.RSSI_RESULT -> listener.onRssi(this, frame.int(0), frame.int(4))
                GatewayProtocol.NOTIFY -> listener.onNotification(this, frame.payload)
                GatewayProtocol.EVENT -> frame.text().split('\n', limit = 2).let { parts ->
                    listener.onEvent(this, "gateway_" + parts[0], tr("Gateway: ", "Passerelle : ") + GatewayWireText.display(parts.getOrElse(1) { "" }))
                }
                in LegFrames.GATEWAY_TO_PILOT -> listener.onLegFrame(this, frame.type, frame.payload)
                GatewayProtocol.LOST -> {
                    val reason = String(frame.payload, 4, frame.payload.size - 4, Charsets.UTF_8)
                    lose(frame.int(), tr("gateway: ", "passerelle : ") + GatewayWireText.display(reason)); return true
                }
                GatewayProtocol.FAILURE -> { fail(tr("Gateway: ", "Passerelle : ") + GatewayWireText.display(frame.text())); return true }
                else -> { fail(tr("Gateway: unknown frame ${frame.type}", "Passerelle : trame inconnue ${frame.type}")); return true }
            }
        }
        return true
    }

    /**
     * Link recovery during autonomous tracking: a new connection every second while the hold lasts.
     * Stop frames already requested (stop order, writes) are kept and sent on recovery.
     */
    private fun reconnect(): GatewayChannel? {
        outbox.removeAll { it.type != LegFrames.LEG_ABORT && it.type != GatewayProtocol.WRITE }
        // Ceiling: beyond it, the gateway stopped tracking by itself (20 min without pilot) or is gone; the pilot gives up.
        val began = System.currentTimeMillis()
        val giveUpAt = began + OUTAGE_LIMIT_MS
        var attempts = 0
        var reportedAt = began
        var lastError = ""
        while (!closed && holding() && System.currentTimeMillis() < giveUpAt) {
            attempts++
            try { return GatewayChannel.open(host, port, code, connectTimeoutMs = 2000) }
            // Busy refusal: the wire text stays French ("Passerelle occupée par un autre pilote"), also from the ESP32.
            catch (denied: GatewayDenied) { if (!denied.wire.contains("occupée")) throw denied; lastError = tr("gateway busy", "passerelle occupée") }
            catch (error: Exception) { lastError = "${error.javaClass.simpleName}: ${error.message ?: ""}".trim().take(160) }
            // On 2026-10-14, 25 min of retries without a single log line: a status line every 30 s, with the phone Wi-Fi state.
            val now = System.currentTimeMillis()
            if (now - reportedAt >= RECONNECT_REPORT_MS) {
                reportedAt = now
                val seconds = (now - began) / 1000
                listener.onEvent(this, "gateway_reconnecting", tr("Gateway still unreachable after $attempts attempt(s) in $seconds s · " +
                    "${PhoneWifi.describe()} · last error: $lastError",
                    "Passerelle toujours injoignable après $attempts essai(s) en $seconds s · ${PhoneWifi.describe()} · dernière erreur : $lastError"))
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
        return null
    }

    private fun startWriter(opened: GatewayChannel): Thread =
        Thread({ writeLoop(opened) }, "gateway-link-writer").apply { isDaemon = true; start() }

    private fun lose(code: Int, reason: String) {
        if (closed) return
        // Only a loss of the Wi-Fi tunnel makes the gateway unreachable; a robot lost over Bluetooth leaves it reachable.
        if (gatewayDisconnected(code)) GatewayHealthStore.unreachable(reason)
        shutdown(); listener.onLost(this, code, reason)
    }
    private fun fail(reason: String) {
        if (closed) return
        if (gatewayUnreachable(reason)) GatewayHealthStore.unreachable(UNREACHABLE_PREFIXES.fold(reason) { r, p -> r.removePrefix(p) }
            .removePrefix(" : ").removePrefix(": "))
        shutdown(); listener.onFailure(this, reason)
    }

    private fun shutdown() {
        GatewayOta.unregister(this)
        outbox.clear(); outbox.offer(Frame(-1))
        channel?.close()
    }

    private fun writeLoop(opened: GatewayChannel) {
        var lastSent = System.currentTimeMillis()
        var inHand: Frame? = null
        try {
            while (true) {
                val frame = outbox.poll(GatewayProtocol.PING_MS, TimeUnit.MILLISECONDS)
                if (frame?.type == -1) return
                // Link resumed on another channel while waiting: this frame is for the new writer.
                if (frame != null && channel !== opened) { outbox.offerFirst(frame); return }
                val now = System.currentTimeMillis()
                if (frame != null) {
                    inHand = frame
                    opened.send(frame.type, frame.payload); lastSent = now
                    inHand = null
                    if (frame.type == GatewayProtocol.CLOSE) { opened.close(); return }
                } else if (now - lastSent >= GatewayProtocol.PING_MS) { opened.send(GatewayProtocol.PING); lastSent = now }
            }
        } catch (_: Exception) {
            // Failed write on a dead channel: the stop order or the write in hand is resent when the link resumes.
            inHand?.let { if (it.type == LegFrames.LEG_ABORT || it.type == GatewayProtocol.WRITE) outbox.offerFirst(it) }
            opened.close()
        }
    }

    override fun write(frame: ByteArray): Boolean = !closed && outbox.offer(Frame(GatewayProtocol.WRITE, frame))
    override fun readRssi(): Boolean = !closed && outbox.offer(Frame(GatewayProtocol.RSSI))

    /** Intentional close: the gateway closes Bluetooth without an extra stop (the pilot already sent STOP). */
    override fun close() {
        if (closed) return
        closed = true
        GatewayOta.unregister(this)
        if (channel == null) return
        outbox.offer(Frame(GatewayProtocol.CLOSE))
        // Safety net: close the socket even if the writer is stuck.
        Thread({ Thread.sleep(1000); channel?.close() }, "gateway-link-close").apply { isDaemon = true }.start()
    }

    companion object {
        /** Bluetooth write time (gateway) from which the leg is logged on the pilot. */
        const val SLOW_GATT_MS = 150
        /** Wi-Fi tunnel loss codes (positive codes come from Bluetooth on the gateway side). */
        const val LOST_SILENT = -2
        const val LOST_WIFI = -3
        /** Gateway stopped on the phone mounted on the robot (after its safety stop). */
        const val LOST_STOPPED = -4
        /** Start of a failure meaning the gateway cannot be reached (see [gatewayUnreachable]). */
        val UNREACHABLE: String get() = tr("Gateway unreachable", "Passerelle injoignable")
        private val UNREACHABLE_PREFIXES = listOf("Passerelle injoignable", "Gateway unreachable")
        /** Longest Wi-Fi outage recovered during autonomous tracking: a little more than the gateway's automatic stop (20 min). */
        const val OUTAGE_LIMIT_MS = 25 * 60_000L
        /** Interval between status lines while recovering from an outage. */
        const val RECONNECT_REPORT_MS = 30_000L
        /** True if the loss concerns the gateway phone itself (Wi-Fi, silence, unreachable), not the robot Bluetooth. */
        fun gatewayDisconnected(code: Int) = code == LOST_SILENT || code == LOST_WIFI || code == LOST_STOPPED
        /** Recognizes both languages, the reason may have been built before a language change. */
        fun gatewayUnreachable(reason: String) = UNREACHABLE_PREFIXES.any { reason.startsWith(it) }
        fun open(config: GatewayConfig, address: String, name: String?, listener: RobotLink.Listener): GatewayLink? =
            open(config.host, config.port, config.code, address, listener, name)
        fun open(host: String, port: Int, code: String, address: String, listener: RobotLink.Listener, name: String? = null): GatewayLink? {
            if (host.isBlank()) return null
            return GatewayLink(host, port, code, address, name, listener).also { it.start() }
        }
    }
}

/** Recovery abandoned after [GatewayLink.OUTAGE_LIMIT_MS]: the original cause (read timeout) is no longer the right one. */
private class GatewayGaveUp(message: String) : Exception(message)
