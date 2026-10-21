package fr.luba.poc.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Task records and tracks for the log export ([fr.luba.poc.EventLog.export]): `tasks/history.json` (all task records)
 * then, per task that kept a track, `tasks/<start>-<kind>.json` (record, map and prepared route, typed events, black boxes) and
 * `tasks/<start>-<kind>.csv` (one line per recorded position). Positions stay in the robot's local frame.
 */
object TaskExport {
    /** Columns of the CSV file; empty cell = unknown. */
    val CSV_HEADER = listOf("time_ms", "x_m", "y_m", "heading_deg", "phase", "battery_pct", "requested_linear", "requested_angular",
        "speed_mps", "rtk_status", "satellites", "link").joinToString(",")

    private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** Export entries (name in the archive, content), built one task at a time. */
    fun entries(filesDir: File): Sequence<Pair<String, String>> = sequence {
        val history = MowingHistoryStore(filesDir).read()
        if (history.isEmpty()) return@sequence
        yield("tasks/history.json" to JSONObject().put("version", 1).put("records", JSONArray(history.map(MowingHistoryJson::encode))).toString(2))
        val store = TaskTrackStore(filesDir)
        val used = HashSet<String>()
        history.sortedBy { it.startedAt }.forEach { record ->
            val track = runCatching { store.read(record.id) }.getOrNull() ?: return@forEach
            var base = "tasks/${NAME.format(Instant.ofEpochMilli(record.startedAt).atZone(ZoneId.systemDefault()))}-${record.kind.name.lowercase()}"
            if (!used.add(base)) base += "-${used.size}"
            yield("$base.json" to document(record, track).toString(2))
            if (track.samples.isNotEmpty()) yield("$base.csv" to csv(track.samples))
        }
    }

    fun document(record: MowingRecord, track: TaskTrack): JSONObject = JSONObject()
        .put("record", MowingHistoryJson.encode(record))
        .put("context", track.meta?.let { TrackJson.encode(record.id, it) } ?: JSONObject.NULL)
        .put("positions", track.samples.size)
        .put("events", JSONArray(track.events.map(TaskEventJson::encode)))
        .put("blackBoxes", JSONArray(track.boxes.map(BlackBoxJson::encode)))

    fun csv(samples: List<TrackSample>): String = buildString {
        append(CSV_HEADER).append('\n')
        samples.forEach { s ->
            fun num(v: Double, decimals: Int) = if (v.isFinite()) "%.${decimals}f".format(Locale.ROOT, v) else ""
            append(s.atMs).append(',').append(num(s.x, 3)).append(',').append(num(s.y, 3)).append(',').append(num(s.heading, 1)).append(',')
            append(s.phase.name).append(',').append(if (s.battery >= 0) s.battery.toString() else "").append(',')
            append(s.requestedLinear?.toString() ?: "").append(',').append(s.requestedAngular?.toString() ?: "").append(',')
            append(num(s.speedMps, 3)).append(',').append(if (s.rtkStatus >= 0) s.rtkStatus.toString() else "").append(',')
            append(if (s.satellites >= 0) s.satellites.toString() else "").append(',').append(s.link.name).append('\n')
        }
    }
}
