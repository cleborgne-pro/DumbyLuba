package fr.luba.poc.diagnostics

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.UnknownFieldSet
import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice

/**
 * Probe looking for the ultrasonic sensors (and perception) in the BLE stream.
 *
 * No movement command is sent; during the capture, the telemetry subscription only adds the vision
 * streams (RIT_VISION_POINT, RIT_VIO, RIT_VISION_STATISTIC). The 4 ultrasonic sensors and the bumper
 * are decoded from `sensor_status` (3 bits each). Each received message is classified by (type, sender, receiver),
 * distinct `sensor_status` values are kept with their date, and any protobuf field unknown to the
 * embedded schema is reported with its path. Full frames stay in the log (`protobuf_decoded`)
 * for offline analysis.
 */
data class ProbeStream(val key: String, val count: Long, val firstAt: Long, val lastAt: Long, val lastSummary: String)
data class ProbeSensorValue(val value: Int, val count: Long, val firstAt: Long, val lastAt: Long) {
    val bits: String get() = Integer.toBinaryString(value and 0x3FFFFFFF).padStart(30, '0').chunked(3).joinToString(" ")
}
data class ProbeUnknown(val path: String, val count: Long, val lastAt: Long, val lastFields: String)

/**
 * 3-bit slot of `rpt_dev_status.sensor_status` (Mammotion APK `MACarDataManager.setUlt_Status`,
 * taken up by PyMammotion `docs/sensor_status.md`): 0 = OK, 1 = alert, 2..7 = error/obstacle.
 */
enum class SensorSlot(private val english: String, private val french: String, val shift: Int) {
    BUMPER("Bumper", "Bumper", 0),
    ULT_LEFT("Left ultrasonic", "Ultrason gauche", 12),
    ULT_LEFT_FRONT("Front left ultrasonic", "Ultrason avant gauche", 15),
    ULT_RIGHT_FRONT("Front right ultrasonic", "Ultrason avant droit", 18),
    ULT_RIGHT("Right ultrasonic", "Ultrason droit", 21);

    val label: String get() = tr(english, french)
    fun raw(sensorStatus: Int): Int = (sensorStatus shr shift) and 7
    val ultrasonic: Boolean get() = this != BUMPER

    companion object {
        fun stateLabel(raw: Int?): String = when (raw) {
            null -> "—"
            0 -> "OK"
            1 -> tr("alert", "alerte")
            else -> tr("error/obstacle ($raw)", "erreur/obstacle ($raw)")
        }
    }
}
data class ProbeSlotState(val slot: SensorSlot, val current: Int?, val max: Int, val changes: Long, val lastChangeAt: Long)
data class ProbeVision(
    val reportPoints: Long = 0, val tunnelPoints: Long = 0, val vio: Long = 0, val statistics: Long = 0,
    val lastPoints: String = "", val lastVio: String = "", val lastStatistics: String = "",
) {
    val any: Boolean get() = reportPoints + tunnelPoints + vio + statistics > 0
}
/** Firmware debug report (`debug_common_report`, `debug_errocode_report`, `debug_cfg_read`, `debug_res_cfg_ability`). */
data class ProbeDebugEntry(val source: String, val key: String, val value: String, val count: Long, val lastAt: Long)

/**
 * Raw integer array pushed by the robot (tunnels `systemRapidStateTunnel`, `systemTardStateTunnel`,
 * `systemTmpCycleTx`, `systemUpdateBuf` by type). [varying] lists the indices whose value changed
 * during the capture (min..max): an index that moves when approaching an obstacle would hint at a distance.
 */
data class ProbeArray(val name: String, val length: Int, val count: Long, val last: List<Long>, val varying: List<String>)

/** Auxiliary state fields: visual SLAM, FPV (camera), VIO survival, vision state and fusion from the slow tunnel. */
data class ProbeDevExtras(
    val vslamStatus: Int? = null, val fpvFlag: Int? = null, val fpvWifi: Int? = null, val fpvMobile: Int? = null,
    val vioSurvivalM: Float? = null, val visionState: Int? = null, val fuseStatus: Int? = null,
    val lastObstacles: String = "", val obstacleMessages: Long = 0,
)

/**
 * DDS → ZMQ debug bridge (`todev_set_dds2zmq`, topic `perception_post_result`, robot port 5555): the only known
 * path to raw perception, outside BLE. [samples] keeps the first messages (size, preview of the parts).
 */
data class ProbeZmq(
    val enabled: Boolean = false,
    val status: String = "",
    val robotIp: String? = null,
    val network: String = "",
    val peer: String = "",
    val messages: Long = 0, val bytes: Long = 0, val lastAt: Long = 0,
    val minSize: Int = 0, val maxSize: Int = 0,
    val samples: List<String> = emptyList(),
) {
    companion object {
        const val MAX_SAMPLES = 12

        /**
         * IPv4 address from a robot `fixed32`. The byte order is undocumented: little-endian (network order
         * read by an ESP32) by default, the other order only if it alone gives a private address.
         */
        fun ipv4(value: Int): String? {
            if (value == 0) return null
            val little = (0..3).map { (value ushr (8 * it)) and 0xFF }
            val big = little.reversed()
            fun private(o: List<Int>) = o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)
            return (if (!private(little) && private(big)) big else little).joinToString(".")
        }
    }
}

data class SensorProbeSnapshot(
    val running: Boolean = false,
    val startedAt: Long = 0, val messages: Long = 0,
    val streams: List<ProbeStream> = emptyList(),
    val sensorValues: List<ProbeSensorValue> = emptyList(),
    val unknown: List<ProbeUnknown> = emptyList(),
    val perceptionMessages: Long = 0, val peptPayloads: Long = 0,
    /** Last perception message decoded as text, truncated. */
    val lastPerception: String = "",
    /** Fields whose name suggests an ultrasonic sensor, a distance or an obstacle, present in a message. */
    val keywordHits: List<String> = emptyList(),
    /** Decoded states of the bumper and the 4 ultrasonic sensors, with the number of changes during the capture. */
    val slots: List<ProbeSlotState> = emptyList(),
    val vision: ProbeVision = ProbeVision(),
    val debugEntries: List<ProbeDebugEntry> = emptyList(),
    val arrays: List<ProbeArray> = emptyList(),
    val extras: ProbeDevExtras = ProbeDevExtras(),
    /** Firmware debug switch requested during this capture. */
    val debugEnabled: Boolean = false,
    /** Robot states (`sys_status`) seen during the capture, with their report count: perception depends on the activity. */
    val robotStates: Map<Int, Long> = emptyMap(),
    val zmq: ProbeZmq = ProbeZmq(),
) {
    /** Capture made with the robot stationary only (ready, charging…): ultrasonic sensors and vision probably only publish during a task. */
    val idleOnly: Boolean get() = robotStates.isNotEmpty() && robotStates.keys.none { it in MOVING_STATES }
    val robotStatesLabel: String get() = robotStates.entries.joinToString { "${it.key}×${it.value}" }.ifEmpty { "—" }
    val ultrasonicChanges: Long get() = slots.filter { it.slot.ultrasonic }.sumOf { it.changes }
    val verdict: String get() = when {
        !running && messages == 0L -> tr("Capture stopped", "Capture arrêtée")
        zmq.messages > 0 -> tr("ZMQ perception bridge: ${zmq.messages} message(s) received, to analyze", "Pont perception ZMQ : ${zmq.messages} message(s) reçus, à analyser")
        ultrasonicChanges > 0 -> tr("Ultrasonic sensors: state change observed", "Ultrasons : changement d'état observé")
        slots.any { it.slot.ultrasonic && it.max > 0 } -> tr("Ultrasonic sensors: non-zero state, no change", "Ultrasons : état non nul, sans changement")
        vision.reportPoints + vision.tunnelPoints > 0 -> tr("Vision obstacle points received: to analyze", "Points d'obstacle vision reçus : à analyser")
        vision.vio + vision.statistics > 0 -> tr("Vision streams (VIO, statistics) received: to analyze", "Flux vision (VIO, statistiques) reçus : à analyser")
        extras.obstacleMessages > 0 -> tr("Perception obstacles received: to analyze", "Obstacles de perception reçus : à analyser")
        debugEntries.isNotEmpty() -> tr("Firmware debug reports received: to analyze", "Rapports de débogage firmware reçus : à analyser")
        peptPayloads > 0 || perceptionMessages > 0 -> tr("Perception messages received: to analyze in the log", "Messages de perception reçus : à analyser dans le journal")
        unknown.isNotEmpty() -> tr("Unknown fields received: to analyze in the log", "Champs inconnus reçus : à analyser dans le journal")
        arrays.any { it.varying.isNotEmpty() } -> tr("Varying raw tunnels: compare with the obstacle", "Tunnels bruts variables : comparer avec l'obstacle")
        keywordHits.isNotEmpty() -> tr("Fields suggesting ultrasonic/distance present", "Champs évoquant ultrason/distance présents")
        messages == 0L -> tr("Waiting for messages", "En attente de messages")
        idleOnly -> tr("No ultrasonic or perception data: robot stayed stationary (state $robotStatesLabel), repeat the capture during a route",
            "Aucun ultrason ni perception : robot resté immobile (état $robotStatesLabel), refaire la capture pendant un trajet")
        else -> tr("No ultrasonic or perception data observed so far", "Aucun ultrason ni perception observé pour l'instant")
    }

    companion object {
        /** Moving states (PyMammotion WorkMode): task (13), return (14), manual mode of DumbyLuba routes (20). */
        val MOVING_STATES = setOf(13, 14, 20)
    }
}

class SensorProbe {
    private class Stream(val firstAt: Long) { var count = 0L; var lastAt = 0L; var summary = "" }
    private class Sensor(val firstAt: Long) { var count = 0L; var lastAt = 0L }
    private class Unknown { var count = 0L; var lastAt = 0L; var fields = "" }
    private class Slot { var current: Int? = null; var max = 0; var changes = 0L; var lastChangeAt = 0L }
    private class Debug { var count = 0L; var lastAt = 0L; var value = "" }
    private class RawArray { var count = 0L; var last = LongArray(0); var min = LongArray(0); var max = LongArray(0) }

    private val streams = LinkedHashMap<String, Stream>()
    private val sensors = LinkedHashMap<Int, Sensor>()
    private val unknown = LinkedHashMap<String, Unknown>()
    private val hits = LinkedHashSet<String>()
    private var running = false
    private var startedAt = 0L
    private var messages = 0L
    private var perception = 0L
    private var pept = 0L
    private var lastPerception = ""
    private val slots = SensorSlot.entries.associateWith { Slot() }
    private var vision = ProbeVision()
    private val debug = LinkedHashMap<Pair<String, String>, Debug>()
    private val arrays = LinkedHashMap<String, RawArray>()
    private var extras = ProbeDevExtras()
    private val robotStates = LinkedHashMap<Int, Long>()
    var debugEnabled = false
    /** ZMQ bridge state, maintained by the listener (RobotBle); the IP comes from `toapp_networkinfo_rsp`. */
    @Volatile var zmq = ProbeZmq()

    fun start(now: Long) { reset(); running = true; startedAt = now }
    fun stop() { running = false }
    fun reset() {
        streams.clear(); sensors.clear(); unknown.clear(); hits.clear()
        messages = 0; perception = 0; pept = 0; lastPerception = ""; startedAt = 0
        slots.values.forEach { it.current = null; it.max = 0; it.changes = 0; it.lastChangeAt = 0 }
        vision = ProbeVision()
        debug.clear(); arrays.clear(); extras = ProbeDevExtras(); debugEnabled = false; robotStates.clear(); zmq = ProbeZmq()
    }
    val isRunning get() = running

    /** Returns the slots whose state changed with this message (empty if none). */
    fun accept(message: LubaMsg, now: Long): List<Pair<SensorSlot, Int>> {
        if (!running) return emptyList()
        val changed = ArrayList<Pair<SensorSlot, Int>>()
        messages++
        val key = "${message.msgtype.name.removePrefix("MSG_CMD_TYPE_")} ${message.sender.name.removePrefix("DEV_")}→${message.rcver.name.removePrefix("DEV_")}"
        val stream = streams[key] ?: if (streams.size < MAX_STREAMS) Stream(now).also { streams[key] = it } else null
        stream?.let { it.count++; it.lastAt = now; it.summary = summary(message) }
        val perceptionTagged = message.msgtype == MsgCmdType.MSG_CMD_TYPE_PEPT ||
            message.sender == MsgDevice.DEV_PERCEPTION || message.rcver == MsgDevice.DEV_PERCEPTION
        if (perceptionTagged) { perception++; lastPerception = message.toString().take(MAX_TEXT) }
        if (message.hasPept()) { pept++; lastPerception = message.pept.toString().take(MAX_TEXT) }
        if (message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasDev()) {
            val value = message.sys.toappReportData.dev.sensorStatus
            val sysStatus = message.sys.toappReportData.dev.sysStatus
            if (robotStates.containsKey(sysStatus) || robotStates.size < MAX_STATES) robotStates[sysStatus] = (robotStates[sysStatus] ?: 0) + 1
            val sensor = sensors.getOrPut(value) { Sensor(now) }
            sensor.count++; sensor.lastAt = now
            if (sensors.size > MAX_SENSOR_VALUES) sensors.remove(sensors.keys.first())
            for ((slot, state) in slots) {
                val raw = slot.raw(value)
                if (state.current != null && state.current != raw) { state.changes++; state.lastChangeAt = now; changed += slot to raw }
                state.current = raw; state.max = maxOf(state.max, raw)
            }
        }
        if (message.hasNet() && message.net.hasToappNetworkinfoRsp()) message.net.toappNetworkinfoRsp.let { n ->
            zmq = zmq.copy(robotIp = ProbeZmq.ipv4(n.ip) ?: zmq.robotIp,
                network = "${n.wifiSsid.ifEmpty { "—" }} · ${ProbeZmq.ipv4(n.ip) ?: tr("unknown IP", "IP inconnue")} · ${tr("mask", "masque")} ${ProbeZmq.ipv4(n.mask) ?: "—"} · RSSI ${n.wifiRssi}")
        }
        if (message.hasSys()) { acceptVision(message); acceptDebug(message, now); acceptTunnels(message) }
        if (message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasDev()) acceptDev(message.sys.toappReportData.dev)
        if (message.hasPept() && message.pept.hasPerceptionObstaclesVisualization()) message.pept.perceptionObstaclesVisualization.let { v ->
            extras = extras.copy(obstacleMessages = extras.obstacleMessages + 1, lastObstacles = (tr("state ${v.status} · ${v.num} obstacle(s) · scale ${v.scale}: ", "état ${v.status} · ${v.num} obstacle(s) · échelle ${v.scale} : ") +
                v.obstaclesList.take(6).joinToString(" | ") { o -> "label ${o.label} ×${o.num} " +
                    o.pointsXList.zip(o.pointsYList).take(4).joinToString(" ") { (x, y) -> "($x, $y)" } }).take(MAX_TEXT))
        }
        walk(message, "LubaMsg", now)
        return changed
    }

    private fun acceptVision(message: LubaMsg) {
        val sys = message.sys
        if (sys.hasToappReportData()) {
            val data = sys.toappReportData
            if (data.visionPointInfoCount > 0) vision = vision.copy(reportPoints = vision.reportPoints + 1,
                lastPoints = data.visionPointInfoList.joinToString(" | ") { group ->
                    "label ${group.label} ×${group.num} " + group.visionPointList.take(4).joinToString(" ") { "(%.2f, %.2f, %.2f)".format(it.x, it.y, it.z) }
                }.take(MAX_TEXT))
            if (data.hasVioToAppInfo()) vision = vision.copy(vio = vision.vio + 1, lastVio = data.vioToAppInfo.let {
                tr("state ${it.vioState} · bright. ${it.brightness} · points ${it.detectFeatureNum}/${it.trackFeatureNum} · (%.2f, %.2f)",
                    "état ${it.vioState} · lum. ${it.brightness} · points ${it.detectFeatureNum}/${it.trackFeatureNum} · (%.2f, %.2f)").format(it.x, it.y)
            })
            if (data.hasVisionStatisticInfo()) vision = vision.copy(statistics = vision.statistics + 1,
                lastStatistics = data.visionStatisticInfo.visionStatisticsList.take(8).joinToString(" ") { "%.2f±%.2f".format(it.mean, it.`var`) })
        }
        if (sys.hasSystemRapidStateTunnel() && sys.systemRapidStateTunnel.visionPointInfoCount > 0) {
            val tunnel = sys.systemRapidStateTunnel
            vision = vision.copy(tunnelPoints = vision.tunnelPoints + 1,
                lastPoints = tunnel.visionPointInfoList.joinToString(" | ") { "label ${it.label} ×${it.num}" }.take(MAX_TEXT))
        }
        // Fast tunnel (≈ 4 Hz during a task): VIO and vision statistics travel there too, outside the subscription.
        if (sys.hasSystemRapidStateTunnel()) {
            val tunnel = sys.systemRapidStateTunnel
            if (tunnel.hasVioToAppInfo()) vision = vision.copy(vio = vision.vio + 1, lastVio = tunnel.vioToAppInfo.let {
                tr("state ${it.vioState} · bright. ${it.brightness} · points ${it.detectFeatureNum}/${it.trackFeatureNum} · (%.2f, %.2f)",
                    "état ${it.vioState} · lum. ${it.brightness} · points ${it.detectFeatureNum}/${it.trackFeatureNum} · (%.2f, %.2f)").format(it.x, it.y)
            })
            if (tunnel.hasVisionStatisticInfo()) vision = vision.copy(statistics = vision.statistics + 1,
                lastStatistics = tunnel.visionStatisticInfo.visionStatisticsList.take(8).joinToString(" ") { "%.2f±%.2f".format(it.mean, it.`var`) })
        }
    }

    private fun acceptDev(dev: fr.luba.poc.proto.MctrlSysProto.rpt_dev_status) {
        extras = extras.copy(vslamStatus = dev.vslamStatus,
            fpvFlag = if (dev.hasFpvInfo()) dev.fpvInfo.fpvFlag else extras.fpvFlag,
            fpvWifi = if (dev.hasFpvInfo()) dev.fpvInfo.wifiAvailable else extras.fpvWifi,
            fpvMobile = if (dev.hasFpvInfo()) dev.fpvInfo.mobileNetAvailable else extras.fpvMobile,
            vioSurvivalM = if (dev.hasVioSurvivalInfo()) dev.vioSurvivalInfo.vioSurvivalDistance else extras.vioSurvivalM)
    }

    private fun acceptDebug(message: LubaMsg, now: Long) {
        val sys = message.sys
        fun note(source: String, key: String, value: String) {
            val k = source to key
            if (!debug.containsKey(k) && debug.size >= MAX_DEBUG) return
            debug.getOrPut(k) { Debug() }.apply { count++; lastAt = now; this.value = value.take(MAX_TEXT) }
        }
        if (sys.hasDebugCommonReport()) sys.debugCommonReport.let { note(tr("report ${it.mName}", "rapport ${it.mName}"), it.key, it.value) }
        if (sys.hasDebugErrocodeReport()) sys.debugErrocodeReport.let { note("code ${it.mname}", it.code.toString(), it.value) }
        if (sys.hasDebugCfgRead()) sys.debugCfgRead.let { note(tr("read", "lecture"), it.key, it.value) }
        if (sys.hasDebugResCfgAbility()) sys.debugResCfgAbility.let { note(tr("keys ${it.curKeyId}/${it.totalKeys}", "clés ${it.curKeyId}/${it.totalKeys}"), it.keys, it.value) }
    }

    private fun acceptTunnels(message: LubaMsg) {
        val sys = message.sys
        if (sys.hasSystemRapidStateTunnel() && sys.systemRapidStateTunnel.rapidStateDataCount > 0)
            noteArray(tr("State tunnel (25)", "Tunnel d'état (25)"), sys.systemRapidStateTunnel.rapidStateDataList)
        if (sys.hasSystemTardStateTunnel() && sys.systemTardStateTunnel.tardStateDataCount > 0) {
            val data = sys.systemTardStateTunnel.tardStateDataList
            noteArray(tr("State tunnel (26)", "Tunnel d'état (26)"), data)
            // PyMammotion RapidState (tunnel 26, ≈ 4 Hz during a task according to PyMammotion; not received over BLE on 2026-10-18..20, native return included): index 16 = fuse_status (bits 8-15) and vision_state (bits 24-31).
            data.getOrNull(16)?.let { v -> extras = extras.copy(fuseStatus = ((v and 0xFF00L) shr 8).toInt(), visionState = ((v and 0xFF000000L) shr 24).toInt()) }
        }
        if (sys.hasSystemTmpCycleTx() && sys.systemTmpCycleTx.cycleTxDataCount > 0)
            noteArray(tr("Temporary cycle (29)", "Cycle temporaire (29)"), sys.systemTmpCycleTx.cycleTxDataList)
        if (sys.hasSystemUpdateBuf() && sys.systemUpdateBuf.updateBufDataCount > 0) {
            val data = sys.systemUpdateBuf.updateBufDataList
            noteArray(tr("Buffer (27) type ${data[0]}", "Tampon (27) type ${data[0]}"), data)
        }
    }

    private fun noteArray(name: String, data: List<Long>) {
        if (!arrays.containsKey(name) && arrays.size >= MAX_ARRAYS) return
        val values = data.take(MAX_ARRAY_LENGTH).toLongArray()
        val entry = arrays.getOrPut(name) { RawArray() }
        if (entry.count == 0L || entry.last.size != values.size) { entry.min = values.copyOf(); entry.max = values.copyOf() }
        else for (i in values.indices) { entry.min[i] = minOf(entry.min[i], values[i]); entry.max[i] = maxOf(entry.max[i], values[i]) }
        entry.last = values; entry.count++
    }

    private fun summary(message: LubaMsg): String {
        val entry = message.allFields.entries.firstOrNull { it.key.containingOneof != null } ?: return "—"
        return entry.key.name + " " + entry.value.toString().replace('\n', ' ').take(160)
    }

    private fun walk(message: Message, path: String, now: Long) {
        noteUnknown(message.unknownFields, path, now)
        for ((field, value) in message.allFields) {
            val childPath = "$path.${field.name}"
            if (KEYWORDS.any { field.name.contains(it, ignoreCase = true) } && hits.size < MAX_HITS) hits += "$childPath=${value.toString().replace('\n', ' ').take(60)}"
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) (value as List<*>).take(MAX_REPEATED).forEach { walk(it as Message, childPath, now) }
            else walk(value as Message, childPath, now)
        }
    }

    private fun noteUnknown(set: UnknownFieldSet, path: String, now: Long) {
        if (set.asMap().isEmpty()) return
        if (!unknown.containsKey(path) && unknown.size >= MAX_UNKNOWN) return
        val entry = unknown.getOrPut(path) { Unknown() }
        entry.count++; entry.lastAt = now
        entry.fields = set.asMap().entries.joinToString(" ") { (number, field) ->
            "#$number=" + (field.varintList.map { it.toString() } + field.fixed32List.map { "f32:$it" } + field.fixed64List.map { "f64:$it" } +
                field.lengthDelimitedList.map { "${it.size()}o" }).joinToString(",")
        }.take(MAX_TEXT)
    }

    fun snapshot() = SensorProbeSnapshot(
        running = running, startedAt = startedAt, messages = messages,
        streams = streams.map { (k, s) -> ProbeStream(k, s.count, s.firstAt, s.lastAt, s.summary) }.sortedByDescending { it.count },
        sensorValues = sensors.map { (v, s) -> ProbeSensorValue(v, s.count, s.firstAt, s.lastAt) }.sortedBy { it.firstAt },
        unknown = unknown.map { (p, u) -> ProbeUnknown(p, u.count, u.lastAt, u.fields) },
        perceptionMessages = perception, peptPayloads = pept, lastPerception = lastPerception,
        keywordHits = hits.toList(),
        slots = slots.map { (slot, s) -> ProbeSlotState(slot, s.current, s.max, s.changes, s.lastChangeAt) },
        vision = vision,
        debugEntries = debug.map { (k, d) -> ProbeDebugEntry(k.first, k.second, d.value, d.count, d.lastAt) },
        arrays = arrays.map { (name, a) -> ProbeArray(name, a.last.size, a.count, a.last.take(24),
            a.last.indices.filter { a.min[it] != a.max[it] }.take(24).map { "[$it] ${a.min[it]}..${a.max[it]}" }) },
        extras = extras, robotStates = robotStates.toMap(),
        debugEnabled = debugEnabled,
        zmq = zmq,
    )

    companion object {
        val KEYWORDS = listOf("ultra", "sonic", "sonar", "tof", "range", "distance", "obstacle", "barrier")
        private const val MAX_STREAMS = 64
        private const val MAX_STATES = 16
        private const val MAX_SENSOR_VALUES = 64
        private const val MAX_UNKNOWN = 64
        private const val MAX_HITS = 40
        private const val MAX_REPEATED = 8
        private const val MAX_TEXT = 400
        private const val MAX_DEBUG = 200
        private const val MAX_ARRAYS = 16
        private const val MAX_ARRAY_LENGTH = 128
    }
}
