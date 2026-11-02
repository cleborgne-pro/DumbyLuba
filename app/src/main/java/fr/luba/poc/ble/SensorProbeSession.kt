@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sensor and perception capture (ultrasound, vision streams, ZMQ perception listener) and camera commands: no motion command.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class SensorProbeSession(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private val sensorProbe get() = host.sensorProbe
    private val state get() = host.state

    /**
     * Ultrasound/perception capture: no motion command; the telemetry subscription adds the vision streams
     * for the duration of the capture. Full frames go to the log.
     */
    fun startSensorProbe() {
        sensorProbe.start(Clock.now())
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
        event("sensor_probe", tr("Sensor capture started (decoded ultrasound + vision streams)", "Capture capteurs démarrée (ultrasons décodés + flux vision)"))
        if (gatt != null) host.send(host.reportsCommand(false, periodMs = host.reportPeriodMs()), "PROBE_RPT_VISION")
    }
    /** Firmware debug switch during the capture (no movement); reset to 0 on stop. */
    fun setSensorProbeDebug(on: Boolean) {
        if (gatt == null || !sensorProbe.isRunning || sensorProbe.debugEnabled == on) return
        host.send(commands.debugEnable(on), if (on) "PROBE_DEBUG_ON" else "PROBE_DEBUG_OFF")
        sensorProbe.debugEnabled = on
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
        event("sensor_probe", if (on) tr("Firmware debug enabled for the capture", "Débogage firmware activé pour la capture") else tr("Firmware debug disabled", "Débogage firmware désactivé"))
    }
    private var zmqJob: Job? = null
    @Volatile private var zmqSocket: Socket? = null
    private fun zmqUpdate(block: (ProbeZmq) -> ProbeZmq) {
        sensorProbe.zmq = block(sensorProbe.zmq)
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
    }

    /**
     * Robot DDS → ZMQ debug bridge (sensor probe, no movement): asks for the robot IP, enables
     * `todev_set_dds2zmq`, then listens on its port 5555 over ZMTP on the local Wi-Fi. Disabled when the capture stops.
     */
    fun setSensorProbeZmq(on: Boolean) {
        if (!on) { stopSensorProbeZmq(sendOff = true); return }
        if (gatt == null || !sensorProbe.isRunning || sensorProbe.zmq.enabled) return
        host.send(commands.networkInfo(), "PROBE_NETWORK_INFO")
        host.send(commands.perceptionZmq(true), "PROBE_ZMQ_ON")
        zmqUpdate { it.copy(enabled = true, status = tr("Bridge requested: waiting for the robot's IP address", "Pont demandé : attente de l'adresse IP du robot")) }
        event("sensor_probe_zmq", tr("DDS → ZMQ perception bridge requested ($PERCEPTION_TOPIC → $PERCEPTION_ZMQ_URL)", "Pont perception DDS → ZMQ demandé ($PERCEPTION_TOPIC → $PERCEPTION_ZMQ_URL)"))
        zmqJob?.cancel()
        zmqJob = host.viewModelScope.launch { listenPerceptionZmq() }
    }

    private fun stopSensorProbeZmq(sendOff: Boolean) {
        val z = sensorProbe.zmq
        if (!z.enabled) return
        zmqJob?.cancel(); zmqJob = null
        runCatching { zmqSocket?.close() }; zmqSocket = null
        if (sendOff && gatt != null) host.send(commands.perceptionZmq(false), "PROBE_ZMQ_OFF")
        zmqUpdate { it.copy(enabled = false, status = tr("Bridge disabled · ${it.messages} message(s) received", "Pont désactivé · ${it.messages} message(s) reçus")) }
        event("sensor_probe_zmq", tr("ZMQ perception bridge disabled: ${z.messages} message(s), ${z.bytes} bytes", "Pont perception ZMQ désactivé : ${z.messages} message(s), ${z.bytes} octets"),
            mapOf("robotIp" to z.robotIp, "peer" to z.peer, "messages" to z.messages, "bytes" to z.bytes,
                "minSize" to z.minSize, "maxSize" to z.maxSize, "samples" to z.samples.joinToString(" | ")))
    }

    private suspend fun listenPerceptionZmq() {
        val began = SystemClock.elapsedRealtime()
        var ip = sensorProbe.zmq.robotIp
        var asked = 1
        while (ip == null && sensorProbe.zmq.enabled) {
            val elapsed = SystemClock.elapsedRealtime() - began
            if (elapsed > 20_000) {
                zmqUpdate { it.copy(status = tr("Robot IP unknown: toapp_networkinfo_rsp not received (robot without Wi-Fi?)", "IP du robot inconnue : toapp_networkinfo_rsp non reçu (robot sans Wi-Fi ?)")) }
                event("sensor_probe_zmq", tr("ZMQ bridge: robot IP not received within 20 s", "Pont ZMQ : IP du robot non reçue en 20 s")); return
            }
            if (elapsed > asked * 5_000L && gatt != null) { asked++; host.send(commands.networkInfo(), "PROBE_NETWORK_INFO") }
            delay(300)
            ip = sensorProbe.zmq.robotIp
        }
        if (ip == null) return
        event("sensor_probe_zmq", tr("ZMQ bridge: robot at $ip, connecting to port $PERCEPTION_ZMQ_PORT", "Pont ZMQ : robot à $ip, connexion au port $PERCEPTION_ZMQ_PORT"), mapOf("network" to sensorProbe.zmq.network))
        var socketType = "SUB"
        var attempt = 0
        while (sensorProbe.zmq.enabled) {
            attempt++
            val socket = Socket()
            zmqSocket = socket
            try {
                val input = withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(ip, PERCEPTION_ZMQ_PORT), 3_000)
                    socket.soTimeout = 0
                    DataInputStream(BufferedInputStream(socket.getInputStream()))
                }
                val handshake = withContext(Dispatchers.IO) {
                    socket.soTimeout = 5_000
                    Zmtp.handshake(input, socket.getOutputStream(), socketType).also { socket.soTimeout = 0 }
                }
                val peer = "${handshake.peerSocketType.ifEmpty { "?" }} · ZMTP ${handshake.version} ${handshake.mechanism}" +
                    handshake.properties.filterKeys { it != "Socket-Type" }.entries.joinToString("") { " · ${it.key}=${it.value}" }
                if (handshake.error != null) {
                    zmqUpdate { it.copy(peer = peer, status = tr("Refused by the robot: ${handshake.error}", "Refusé par le robot : ${handshake.error}")) }
                    event("sensor_probe_zmq", tr("ZMQ bridge refused: ${handshake.error}", "Pont ZMQ refusé : ${handshake.error}"), mapOf("peer" to peer)); return
                }
                val wanted = Zmtp.compatible(handshake.peerSocketType)
                if (wanted != socketType && attempt < 4) {
                    event("sensor_probe_zmq", tr("ZMQ bridge: the robot is ${handshake.peerSocketType}, reconnecting as $wanted", "Pont ZMQ : le robot est ${handshake.peerSocketType}, reconnexion en $wanted"))
                    socketType = wanted
                    runCatching { socket.close() }
                    continue
                }
                zmqUpdate { it.copy(peer = peer, status = tr("Connected to $ip:$PERCEPTION_ZMQ_PORT as $socketType: listening", "Connecté à $ip:$PERCEPTION_ZMQ_PORT en $socketType : en écoute")) }
                event("sensor_probe_zmq", tr("ZMQ bridge connected to $ip:$PERCEPTION_ZMQ_PORT", "Pont ZMQ connecté à $ip:$PERCEPTION_ZMQ_PORT"), mapOf("peer" to peer, "socketType" to socketType))
                while (sensorProbe.zmq.enabled) {
                    val parts = withContext(Dispatchers.IO) { Zmtp.readMessage(input) }
                    recordZmqMessage(parts)
                }
            } catch (e: CancellationException) {
                runCatching { socket.close() }; throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                if (!sensorProbe.zmq.enabled) return
                val reason = if (Zmtp.isEnd(e)) tr("connection closed by the robot", "connexion fermée par le robot") else (e.message ?: e.javaClass.simpleName)
                zmqUpdate { it.copy(status = tr("Port $PERCEPTION_ZMQ_PORT of $ip: $reason (attempt $attempt, retrying in 3 s)", "Port $PERCEPTION_ZMQ_PORT de $ip : $reason (essai $attempt, nouvel essai dans 3 s)")) }
                if (attempt == 1 || attempt % 10 == 0) event("sensor_probe_zmq", tr("ZMQ bridge: $reason", "Pont ZMQ : $reason"), mapOf("attempt" to attempt, "ip" to ip))
                if (attempt >= 60) {
                    zmqUpdate { it.copy(status = tr("Port $PERCEPTION_ZMQ_PORT of $ip unreachable after 3 min: $reason", "Port $PERCEPTION_ZMQ_PORT de $ip injoignable après 3 min : $reason")) }
                    event("sensor_probe_zmq", tr("ZMQ bridge abandoned after $attempt attempts: $reason", "Pont ZMQ abandonné après $attempt essais : $reason")); return
                }
                delay(3_000)
            }
        }
    }

    private fun recordZmqMessage(parts: List<ByteArray>) {
        val size = parts.sumOf { it.size }
        val z = sensorProbe.zmq
        val n = z.messages + 1
        val sample = tr("#$n · $size B · ", "#$n · $size o · ") + parts.joinToString(" | ") { tr("${it.size} B ${Zmtp.preview(it)}", "${it.size} o ${Zmtp.preview(it)}") }
        zmqUpdate { it.copy(messages = n, bytes = it.bytes + size, lastAt = Clock.now(),
            minSize = if (it.messages == 0L) size else minOf(it.minSize, size), maxSize = maxOf(it.maxSize, size),
            samples = if (it.samples.size < ProbeZmq.MAX_SAMPLES) it.samples + sample else it.samples) }
        // Full frames (at most 4 KB per part) for the first messages, then one checkpoint every 100.
        if (n <= 20) event("sensor_probe_zmq_msg", sample.take(200), mapOf("index" to n, "size" to size,
            "parts" to parts.joinToString(" | ") { Zmtp.hex(it, minOf(it.size, 4096)) }))
        else if (n % 100 == 0L) event("sensor_probe_zmq", tr("ZMQ bridge: $n messages received", "Pont ZMQ : $n messages reçus"), mapOf("bytes" to z.bytes + size))
    }

    fun stopSensorProbe() {
        if (!sensorProbe.isRunning) return
        stopSensorProbeZmq(sendOff = true)
        if (sensorProbe.debugEnabled && gatt != null) host.send(commands.debugEnable(false), "PROBE_DEBUG_OFF")
        sensorProbe.debugEnabled = false
        sensorProbe.stop()
        if (gatt != null) host.send(host.reportsCommand(false, periodMs = host.reportPeriodMs()), "PROBE_RPT_RESTORE")
        val snapshot = sensorProbe.snapshot()
        update { it.copy(sensorProbe = snapshot) }
        event("sensor_probe", tr("Sensor capture stopped: ${snapshot.verdict}", "Capture capteurs arrêtée : ${snapshot.verdict}"), mapOf("messages" to snapshot.messages,
            "streams" to snapshot.streams.joinToString { "${it.key}×${it.count}" },
            "sensorValues" to snapshot.sensorValues.joinToString { "${it.value}×${it.count}" },
            "unknown" to snapshot.unknown.joinToString { "${it.path} ${it.lastFields}" },
            "perception" to snapshot.perceptionMessages + snapshot.peptPayloads, "keywordHits" to snapshot.keywordHits.joinToString("; "),
            "slots" to snapshot.slots.joinToString { "${it.slot.name}=${it.current}/max ${it.max}/×${it.changes}" },
            "visionPoints" to snapshot.vision.reportPoints + snapshot.vision.tunnelPoints, "vio" to snapshot.vision.vio,
            "visionStatistics" to snapshot.vision.statistics, "lastVisionPoints" to snapshot.vision.lastPoints,
            "debugReports" to snapshot.debugEntries.joinToString("; ") { "${it.source} ${it.key}=${it.value.take(80)}" },
            "rawArrays" to snapshot.arrays.joinToString("; ") { "${it.name}×${it.count} varie ${it.varying.joinToString(" ")}" },
            "robotStates" to snapshot.robotStatesLabel, "obstacles" to snapshot.extras.obstacleMessages, "visionState" to snapshot.extras.visionState, "vslam" to snapshot.extras.vslamStatus))
    }

    /**
     * Camera: multimedia module command (vi_switch or refresh_fpv) over the current robot link
     * (direct Bluetooth or gateway). No motion command. False if no link is ready.
     */
    fun sendCameraCommand(payload: ByteArray, label: String): Boolean {
        if (gatt == null || !state.value.ready) return false
        host.send(payload, label)
        return true
    }
    fun cameraVideo(on: Boolean): Boolean = sendCameraCommand(commands.video(on), if (on) "CAMERA_VIDEO_ON" else "CAMERA_VIDEO_OFF").also {
        if (it) event("camera", if (on) tr("Camera: publishing requested (vi_switch=1)", "Caméra : publication demandée (vi_switch=1)") else tr("Camera: stop requested (vi_switch=0)", "Caméra : arrêt demandé (vi_switch=0)"))
    }
    fun cameraRefresh(): Boolean = sendCameraCommand(commands.refreshFpv(), "CAMERA_REFRESH_FPV")
    fun cameraEvent(text: String, fields: Map<String, Any?> = emptyMap()) = event("camera", text, fields)
    fun resetSensorProbe() { sensorProbe.reset(); update { it.copy(sensorProbe = sensorProbe.snapshot()) } }
}
