package fr.luba.poc.gateway

import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.ble.RobotLink
import fr.luba.poc.gateway.GatewayProtocol.Frame
import fr.luba.poc.i18n.tr
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Blocking BLE search: calls [onResult] for each LUBA seen during [durationMs]. */
fun interface GatewayScanner { fun scan(durationMs: Long, onResult: (name: String, address: String, rssi: Int) -> Unit) }

/** Opens the local BLE link (GATT) to the robot. */
fun interface GatewayLinkFactory { fun open(address: String, listener: RobotLink.Listener): RobotLink? }

/**
 * One control session on the gateway side (phone mounted on the robot), on a dedicated thread, plugged into the shared
 * robot link [robot]. The pilot alone decides the commands; the gateway only decides to stop when the pilot goes silent
 * or drops the connection without an intentional close.
 *
 * Refusals and failures sent to the pilot (DENIED, FAILURE) are wire texts: they stay in French and the pilot
 * translates them at display ([GatewayWireText]). [report] texts are for this phone's screen and use [tr].
 */
class GatewaySession(
    private val socket: Socket,
    private val code: String,
    private val limiter: AttemptLimiter,
    private val robot: GatewayRobot,
    private val scanner: GatewayScanner,
    private val report: (String) -> Unit,
    private val scanMs: Long = 6000,
    private val silenceMs: Long = GatewayProtocol.SILENCE_MS,
    /** Reads the health state of the gateway phone (null: not reported). Called on the writer thread. */
    private val health: (() -> GatewayHealth?)? = null,
    private val healthMs: Long = GatewayProtocol.HEALTH_MS,
    /** Tilt of the gateway phone and its age in ms (null: no sensor); sent with the health every [tiltMs]. */
    private val tilt: (() -> Pair<fr.luba.poc.map.PhoneGravity, Int>?)? = null,
    private val tiltMs: Long = GatewayProtocol.TILT_MS,
) {
    private val outbox = LinkedBlockingQueue<Frame>()
    @Volatile private var finished = false
    @Volatile private var attached = false
    @Volatile private var legPilot = false
    @Volatile var authenticated = false
        private set
    val peer: String = socket.inetAddress?.hostAddress ?: "?"

    /** Safety stop outcome, for the gateway screen and tests. */
    @Volatile var emergencyOutcome: String? = null
        private set

    private fun send(type: Int, payload: ByteArray = ByteArray(0)) { if (!finished) outbox.offer(Frame(type, payload)) }
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Closes the session after sending the pending frames (gateway stopped: the pilot was already told). */
    fun close() { outbox.offer(Frame(CLOSE_AFTER_FLUSH)) }

    private val pilot = object : GatewayPilot {
        // A pilot that did not announce autonomous tracking (older version) receives none of its frames: it would take them for a fault.
        override fun frame(type: Int, payload: ByteArray) { if (legPilot) send(type, payload) }
        override fun discovering() = send(GatewayProtocol.DISCOVERING)
        override fun ready(mtu: Int) = send(GatewayProtocol.READY, GatewayProtocol.ints(mtu))
        override fun lost(code: Int, reason: String) = send(GatewayProtocol.LOST, GatewayProtocol.ints(code) + reason.toByteArray(Charsets.UTF_8))
        override fun failure(reason: String) = send(GatewayProtocol.FAILURE, reason.toByteArray(Charsets.UTF_8))
        override fun written(code: Int, gattMs: Int) = send(GatewayProtocol.WRITTEN, GatewayProtocol.ints(code, gattMs))
        override fun rssi(rssi: Int, code: Int) = send(GatewayProtocol.RSSI_RESULT, GatewayProtocol.ints(rssi, code))
        override fun notification(bytes: ByteArray) = send(GatewayProtocol.NOTIFY, bytes)
        override fun event(kind: String, text: String) {
            val bytes = "$kind\n$text".toByteArray(Charsets.UTF_8)
            send(GatewayProtocol.EVENT, bytes.copyOf(minOf(bytes.size, GatewayProtocol.MAX_PAYLOAD)))
        }
    }

    /** Runs the session until it ends; throws no exception. */
    fun run() {
        var intentional = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = silenceMs.toInt()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val nonce = ByteArray(GatewayProtocol.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            GatewayProtocol.write(output, GatewayProtocol.CHALLENGE, byteArrayOf(GatewayProtocol.VERSION.toByte()) + nonce)
            val hello = GatewayProtocol.read(input)
            val now = System.currentTimeMillis()
            if (limiter.blocked(now)) { refuse(output, "Trop d'essais : attendre une minute"); return }
            val proof = hello.payload.drop(1).toByteArray()
            if (hello.type != GatewayProtocol.HELLO || hello.payload.isEmpty() || hello.payload[0].toInt() != GatewayProtocol.VERSION) {
                refuse(output, "Version de passerelle différente : mettre à jour les deux téléphones"); return
            }
            if (!GatewayProtocol.verify(code, nonce, proof)) { limiter.failed(now); refuse(output, "Code de passerelle incorrect"); return }
            authenticated = true
            GatewayProtocol.write(output, GatewayProtocol.WELCOME, GatewayProtocol.welcome())
            report(tr("Pilot connected: $peer", "Pilote connecté : $peer"))
            Thread({ writeLoop(output) }, "gateway-writer").apply { isDaemon = true }.start()
            while (true) {
                val frame = try { GatewayProtocol.read(input) } catch (_: SocketTimeoutException) {
                    report(tr("Pilot silent for ${silenceMs / 1000.0} s", "Pilote muet depuis ${silenceMs / 1000.0} s")); break
                }
                when (frame.type) {
                    GatewayProtocol.PING -> Unit
                    GatewayProtocol.SCAN -> scan()
                    GatewayProtocol.OPEN -> {
                        val (address, name) = GatewayProtocol.parseOpen(frame)
                        val refused = if (attached) "Liaison robot déjà ouverte" else robot.attach(pilot, address, name)
                        if (refused != null) send(GatewayProtocol.FAILURE, refused.toByteArray(Charsets.UTF_8)) else attached = true
                    }
                    GatewayProtocol.WRITE -> robot.write(pilot, frame.payload)
                    GatewayProtocol.RSSI -> robot.readRssi(pilot)
                    GatewayProtocol.CLOSE -> { intentional = true; break }
                    LegFrames.LEG_HELLO -> legPilot = true
                    else -> {
                        // Autonomous tracking: only once the robot link was opened by this pilot, which announced it.
                        if (attached && legPilot && robot.autonomy.onFrame(frame.type, frame.payload)) Unit
                        else { send(GatewayProtocol.FAILURE, "Trame inconnue ${frame.type}".toByteArray(Charsets.UTF_8)); break }
                    }
                }
            }
        } catch (error: Exception) {
            if (authenticated) report(tr("Pilot disconnected: ${error.message ?: error.javaClass.simpleName}",
                "Pilote déconnecté : ${error.message ?: error.javaClass.simpleName}"))
        } finally {
            finished = true
            if (attached) {
                val detached = robot.detachWithResult(pilot, emergency = !intentional)
                val outcome = detached.outcome
                // Autonomous tracking in progress and Wi-Fi lost: the robot carries on alone, no safety stop was written.
                val autonomous = !intentional && detached.missionContinued
                if (intentional) report(tr("Pilot left (intentional close); Bluetooth link kept", "Pilote parti (fermeture volontaire) ; liaison Bluetooth conservée"))
                else if (autonomous) {
                    emergencyOutcome = tr("autonomous tracking continued without pilot", "suivi autonome poursuivi sans pilote")
                    report(tr("Pilot lost during autonomous tracking: the robot carries on alone", "Pilote perdu pendant un suivi autonome : le robot continue seul"))
                }
                // null: pilot already unplugged by the gateway stop, which wrote the safety stop.
                else if (outcome == null) emergencyOutcome = tr("stop already handled when the gateway stopped", "arrêt déjà traité à l'arrêt de la passerelle")
                else { emergencyOutcome = outcome; report(tr("Safety stop: ${GatewayWireText.display(outcome)}", "Arrêt de sécurité : $outcome")) }
            }
            outbox.clear(); outbox.offer(Frame(-1))
            runCatching { socket.close() }
        }
    }

    private fun writeLoop(out: DataOutputStream) {
        var lastSent = System.currentTimeMillis()
        var lastHealth = 0L
        var lastTilt = 0L
        var cached: GatewayHealth? = null
        fun sendHealth(base: GatewayHealth, now: Long) {
            val measured = tilt?.let { runCatching { it() }.getOrNull() }
            GatewayProtocol.write(out, GatewayProtocol.HEALTH, base.copy(gravity = measured?.first, gravityAgeMs = measured?.second ?: 0).encode())
            lastSent = now; lastTilt = now
        }
        try {
            while (true) {
                val frame = outbox.poll(GatewayProtocol.PING_MS, TimeUnit.MILLISECONDS)
                if (frame?.type == -1) return
                if (frame?.type == CLOSE_AFTER_FLUSH) { runCatching { socket.close() }; return }
                val now = System.currentTimeMillis()
                if (health != null && now - lastHealth >= healthMs) {
                    val read = health
                    lastHealth = now
                    cached = runCatching { read() }.getOrNull()
                    cached?.let { sendHealth(it, now) }
                } else if (tilt != null && now - lastTilt >= tiltMs) cached?.let { sendHealth(it, now) }
                if (frame != null) { GatewayProtocol.write(out, frame.type, frame.payload); lastSent = now }
                else if (now - lastSent >= GatewayProtocol.PING_MS) { GatewayProtocol.write(out, GatewayProtocol.PING); lastSent = now }
            }
        } catch (_: Exception) { runCatching { socket.close() } }
    }

    private fun refuse(output: DataOutputStream, reason: String) {
        report(tr("Connection refused ($peer): ${GatewayWireText.display(reason)}", "Connexion refusée ($peer) : $reason"))
        runCatching { GatewayProtocol.write(output, GatewayProtocol.DENIED, reason.toByteArray(Charsets.UTF_8)) }
    }

    private fun scan() {
        // Robot already held by the gateway: it no longer advertises, report it directly.
        robot.connected()?.let { (name, address, rssi) ->
            send(GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult(name, address, rssi)); send(GatewayProtocol.SCAN_END); return
        }
        // One search at a time: the one in progress will send the end of search.
        if (!scanning.compareAndSet(false, true)) return
        Thread({
            // Search impossible (Bluetooth off on the gateway…): reason sent to the pilot (wire text), not an empty list.
            val failure = try {
                scanner.scan(scanMs) { name, address, rssi -> send(GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult(name, address, rssi)) }
                null
            } catch (error: Exception) { error.message ?: error.javaClass.simpleName }
            failure?.let { report(tr("Bluetooth search: ${GatewayWireText.display(it)}", "Recherche Bluetooth : $it")) }
            scanning.set(false)
            if (failure != null) send(GatewayProtocol.FAILURE, failure.toByteArray(Charsets.UTF_8)) else send(GatewayProtocol.SCAN_END)
        }, "gateway-scan").apply { isDaemon = true }.start()
    }

    private companion object {
        /** Internal marker of the send queue: close the socket once the previous frames are sent. */
        const val CLOSE_AFTER_FLUSH = -2
    }
}

/**
 * A single control session at a time. The same phone coming back (Wi-Fi down for a moment, automatic reconnection)
 * while its previous session ends — silence of [GatewayProtocol.SILENCE_MS] then safety stop — waits for it to free the
 * place, at most [waitMs], receiving PINGs; another phone is refused.
 */
class GatewayAdmission(private val report: (String) -> Unit, private val waitMs: Long = WAIT_MS) {
    @Volatile var current: GatewaySession? = null
        private set

    private fun claim(session: GatewaySession) = synchronized(this) { current.also { if (it == null) current = session } }

    /** True if [session] gets the place (to give back with [release]); otherwise the refusal was sent and the socket closed. */
    fun admit(session: GatewaySession, client: Socket): Boolean {
        var busy = claim(session) ?: return true
        if (busy.peer == session.peer) {
            report(tr("Reconnection of ${session.peer}: waiting for its previous session to end", "Reconnexion de ${session.peer} : attente de la fin de sa session précédente"))
            val deadline = System.currentTimeMillis() + waitMs
            try {
                val out = DataOutputStream(client.getOutputStream())
                while (System.currentTimeMillis() < deadline) {
                    GatewayProtocol.write(out, GatewayProtocol.PING)
                    Thread.sleep(GatewayProtocol.PING_MS)
                    busy = claim(session) ?: return true
                }
            } catch (_: Exception) { runCatching { client.close() }; return false }
        }
        report(tr("Connection of ${session.peer} refused: a pilot is already connected (${busy.peer})",
            "Connexion de ${session.peer} refusée : un pilote est déjà connecté (${busy.peer})"))
        runCatching {
            GatewayProtocol.write(DataOutputStream(client.getOutputStream()), GatewayProtocol.DENIED, BUSY.toByteArray(Charsets.UTF_8))
        }
        runCatching { client.close() }
        return false
    }

    fun release(session: GatewaySession) { synchronized(this) { if (current === session) current = null } }

    companion object {
        /** Wire text, unchanged: the pilot retries when a refusal contains "occupée" ([GatewayLink]). */
        const val BUSY = "Passerelle occupée par un autre pilote"
        /** Silence of the previous session, then its safety stop (two confirmed frames at most). */
        const val WAIT_MS = GatewayProtocol.SILENCE_MS + 6000
    }
}
