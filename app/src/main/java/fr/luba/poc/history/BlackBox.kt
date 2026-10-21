package fr.luba.poc.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * Robot state at one moment of a black box (epoch ms): rotation centre (map metres, NaN if unknown), heading
 * (degrees, NaN), last speed command written by the phone (raw units, null if none in the last seconds), measured
 * speed (m/s, NaN), battery, robot state, sensor bits, RTK status, satellites (-1 if unknown), link state, route phase
 * and signal strengths (dBm, null if unknown).
 */
data class BlackBoxFrame(val atMs: Long, val x: Double = Double.NaN, val y: Double = Double.NaN, val heading: Double = Double.NaN,
                         val linear: Int? = null, val angular: Int? = null, val speedMps: Double = Double.NaN,
                         val battery: Int = -1, val robotState: Int = -1, val sensorStatus: Int = -1, val rtkStatus: Int = -1,
                         val satellites: Int = -1, val link: TrackLinkState = TrackLinkState.UNKNOWN, val phase: String = "",
                         val bleRssi: Int? = null, val wifiRssi: Int? = null)

/** Command written to the robot (label of the write, motion setpoint if it is one). */
data class BlackBoxCommand(val atMs: Long, val label: String, val linear: Int? = null, val angular: Int? = null)

/** App log event of the window (kind and text as in the log). */
data class BlackBoxNote(val atMs: Long, val kind: String, val text: String)

/**
 * Incident box of a task: what the robot and the phone did during the [BlackBoxRecorder.BEFORE_MS] before the
 * incident at [atMs] and the [BlackBoxRecorder.AFTER_MS] after it. [triggers]: incident types grouped in it (a link
 * loss and its STOP), [detail]: text of the first one.
 */
data class BlackBox(val atMs: Long, val triggers: List<TaskEventType>, val detail: String,
                    val frames: List<BlackBoxFrame>, val commands: List<BlackBoxCommand>, val notes: List<BlackBoxNote>) {
    /** Frame at or just before [at] (the first one if none). */
    fun frameAt(at: Long): BlackBoxFrame? = frames.lastOrNull { it.atMs <= at } ?: frames.firstOrNull()
}

/**
 * Sliding memory of the last [KEEP_MS] (frames, commands, log events), frozen into a [BlackBox] around an incident.
 * Frames are kept at most every [FRAME_MS]; the memory never grows beyond [MAX_ITEMS] items of each kind.
 */
class BlackBoxRecorder {
    private val frames = ArrayDeque<BlackBoxFrame>()
    private val commands = ArrayDeque<BlackBoxCommand>()
    private val notes = ArrayDeque<BlackBoxNote>()

    @Synchronized fun frame(f: BlackBoxFrame) {
        val last = frames.lastOrNull()
        if (last != null && f.atMs >= last.atMs && f.atMs - last.atMs < FRAME_MS) return
        frames.addLast(f); trim(frames, f.atMs) { it.atMs }
    }

    @Synchronized fun command(c: BlackBoxCommand) { commands.addLast(c); trim(commands, c.atMs) { it.atMs } }

    @Synchronized fun note(n: BlackBoxNote) { notes.addLast(n); trim(notes, n.atMs) { it.atMs } }

    @Synchronized fun clear() { frames.clear(); commands.clear(); notes.clear() }

    /** Box of the incident at [at]: memory from [at] − [BEFORE_MS] to [at] + [AFTER_MS]. */
    @Synchronized fun freeze(at: Long, triggers: List<TaskEventType>, detail: String): BlackBox {
        val range = (at - BEFORE_MS)..(at + AFTER_MS)
        return BlackBox(at, triggers.distinct(), detail, frames.filter { it.atMs in range }, commands.filter { it.atMs in range },
            notes.filter { it.atMs in range })
    }

    private inline fun <T> trim(queue: ArrayDeque<T>, now: Long, at: (T) -> Long) {
        while (queue.isNotEmpty() && (now - at(queue.first()) > KEEP_MS || queue.size > MAX_ITEMS)) queue.removeFirst()
    }

    companion object {
        const val BEFORE_MS = 45_000L
        const val AFTER_MS = 3_000L
        const val KEEP_MS = 60_000L
        const val FRAME_MS = 400L
        const val MAX_ITEMS = 2_000
        /** Incidents less than this apart go in the same box. */
        const val GROUP_MS = 10_000L
        /** Boxes kept per task: the first ones (the later ones often repeat the same failure). */
        const val MAX_BOXES = 30

        /** App log events not worth keeping in a box (raw frames, write acknowledgements, periodic telemetry). */
        val NOISY = setOf("ble_rx", "ble_tx", "telemetry", "protobuf", "protobuf_decoded", "gatt_write_callback", "command_completed",
            "command_enqueued", "write_completed", "rssi_callback", "scan", "scan_advertisement", "scan_result", "first_telemetry",
            "sensor_probe_zmq_msg", "trajectory_speed")
    }
}

/**
 * Black boxes waiting for their after-incident window: an incident opens a box, incidents within
 * [BlackBoxRecorder.GROUP_MS] join it, and the box is due [BlackBoxRecorder.AFTER_MS] after its first incident.
 */
class BlackBoxTrigger {
    data class Pending(val taskId: String, val atMs: Long, val types: List<TaskEventType>, val detail: String)

    private var pending: Pending? = null
    private val counts = HashMap<String, Int>()

    /** Incident [event] of task [taskId]: true if it opens a new box (to freeze at [dueAt]), false if it joins one or is over the cap. */
    @Synchronized fun incident(taskId: String, event: TaskEvent): Boolean {
        val p = pending
        if (p != null && p.taskId == taskId && event.atMs - p.atMs in 0..BlackBoxRecorder.GROUP_MS) {
            pending = p.copy(types = p.types + event.type); return false
        }
        if ((counts[taskId] ?: 0) >= BlackBoxRecorder.MAX_BOXES) return false
        counts[taskId] = (counts[taskId] ?: 0) + 1
        pending = Pending(taskId, event.atMs, listOf(event.type), event.detail)
        return true
    }

    /** Time at which the open box is due. */
    @Synchronized fun dueAt(): Long? = pending?.let { it.atMs + BlackBoxRecorder.AFTER_MS }

    /** The open box, closed. */
    @Synchronized fun take(): Pending? = pending.also { pending = null }
}

object BlackBoxJson {
    private fun nan(v: Double): Any = if (v.isFinite()) Math.round(v * 1000) / 1000.0 else JSONObject.NULL
    private fun num(a: JSONArray, i: Int): Double = if (a.isNull(i)) Double.NaN else a.getDouble(i)
    private fun int(a: JSONArray, i: Int): Int? = if (a.isNull(i)) null else a.getInt(i)

    /** Frame columns, in the order of the arrays of `frames`. */
    val FRAME_COLUMNS = listOf("at", "x", "y", "heading", "linear", "angular", "speed", "battery", "robotState", "sensorStatus",
        "rtkStatus", "satellites", "link", "phase", "bleRssi", "wifiRssi")

    fun encode(b: BlackBox): JSONObject = JSONObject().put("at", b.atMs).put("triggers", JSONArray(b.triggers.map { it.name }))
        .put("detail", b.detail).put("frameColumns", JSONArray(FRAME_COLUMNS))
        .put("frames", JSONArray(b.frames.map { f ->
            JSONArray(listOf(f.atMs, nan(f.x), nan(f.y), nan(f.heading), f.linear ?: JSONObject.NULL, f.angular ?: JSONObject.NULL, nan(f.speedMps),
                f.battery, f.robotState, f.sensorStatus, f.rtkStatus, f.satellites, f.link.name, f.phase, f.bleRssi ?: JSONObject.NULL,
                f.wifiRssi ?: JSONObject.NULL))
        }))
        .put("commands", JSONArray(b.commands.map { c -> JSONArray(listOf(c.atMs, c.label, c.linear ?: JSONObject.NULL, c.angular ?: JSONObject.NULL)) }))
        .put("notes", JSONArray(b.notes.map { n -> JSONArray(listOf(n.atMs, n.kind, n.text)) }))

    fun decode(j: JSONObject): BlackBox {
        val frames = j.getJSONArray("frames").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { f ->
            BlackBoxFrame(f.getLong(0), num(f, 1), num(f, 2), num(f, 3), int(f, 4), int(f, 5), num(f, 6), f.getInt(7), f.getInt(8), f.getInt(9),
                f.getInt(10), f.getInt(11), runCatching { TrackLinkState.valueOf(f.getString(12)) }.getOrDefault(TrackLinkState.UNKNOWN),
                f.getString(13), int(f, 14), int(f, 15))
        } } }
        val commands = j.getJSONArray("commands").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { c ->
            BlackBoxCommand(c.getLong(0), c.getString(1), int(c, 2), int(c, 3)) } } }
        val notes = j.getJSONArray("notes").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { n ->
            BlackBoxNote(n.getLong(0), n.getString(1), n.getString(2)) } } }
        val triggers = j.getJSONArray("triggers").let { a -> (0 until a.length()).mapNotNull { TaskEventType.of(a.getString(it)) } }
        return BlackBox(j.getLong("at"), triggers, j.optString("detail"), frames, commands, notes)
    }
}
