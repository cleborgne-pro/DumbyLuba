package fr.luba.poc.history

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Robot activity at a point of the track: trace colour and replay label. */
enum class TrackPhase(private val labelEn: String, private val labelFr: String) {
    CUTTING("Mowing", "Tonte"), TRAVEL("Travel with blades stopped", "Déplacement lames arrêtées"), RETURN("Return to base", "Retour à la base"), DOCKING("Docking", "Accostage"),
    CHARGING("Recharge", "Recharge"), OTHER("Other", "Autre");

    val label: String get() = tr(labelEn, labelFr)

    companion object { fun of(code: Int): TrackPhase = entries.getOrElse(code) { OTHER } }
}

/**
 * Actual position recorded during a task: time (epoch ms), robot rotation centre in map metres,
 * heading (mathematical degrees, NaN if unknown), activity and battery (-1 if unknown).
 */
data class TrackSample(val atMs: Long, val x: Double, val y: Double, val heading: Double, val phase: TrackPhase, val battery: Int = -1) {
    val point: Point get() = Point(x, y)
}

/** Replay context, frozen at start: task map and prepared route (cutting stripes, links). */
data class TrackMeta(val map: RobotMap, val cutting: List<List<Point>> = emptyList(), val transit: List<List<Point>> = emptyList())

/** Full track of a history task; [meta] is null for a track without context (lost file). */
data class TaskTrack(val meta: TrackMeta?, val samples: List<TrackSample>) {
    val startMs: Long get() = samples.firstOrNull()?.atMs ?: 0L
    val endMs: Long get() = samples.lastOrNull()?.atMs ?: 0L

    /** Index of the last sample at time [atMs] (−1 before the first one). */
    fun indexAt(atMs: Long): Int {
        var lo = 0; var hi = samples.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].atMs <= atMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Interpolated pose at time [atMs]: position and heading between the two surrounding samples. */
    fun poseAt(atMs: Long): TrackSample? {
        val i = indexAt(atMs)
        if (i < 0) return samples.firstOrNull()
        val a = samples[i]
        val b = samples.getOrNull(i + 1) ?: return a
        // No interpolation across a gap (lost positions, robot moved).
        if (TaskTrackPieces.broken(a, b) || b.atMs <= a.atMs) return a
        val t = ((atMs - a.atMs).toDouble() / (b.atMs - a.atMs)).coerceIn(0.0, 1.0)
        val heading = if (a.heading.isNaN() || b.heading.isNaN()) a.heading else {
            val d = ((b.heading - a.heading + 540.0) % 360.0) - 180.0
            a.heading + d * t
        }
        return a.copy(atMs = atMs, x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t, heading = heading)
    }
}

/**
 * Filters received positions: the track keeps every move of at least [MIN_STEP_M], every rotation of at least
 * [MIN_TURN_DEG], every activity change and one position every [KEEP_ALIVE_MS] while stationary (recharge,
 * bypass). A 3 h mowing thus fits in a few hundred kB.
 */
class TrackSampler {
    private var last: TrackSample? = null

    fun accept(sample: TrackSample): Boolean {
        if (!sample.x.isFinite() || !sample.y.isFinite()) return false
        val previous = last
        val keep = previous == null || sample.atMs < previous.atMs || sample.phase != previous.phase ||
            sample.atMs - previous.atMs >= KEEP_ALIVE_MS ||
            Point(previous.x, previous.y).distance(sample.point) >= MIN_STEP_M ||
            (!sample.heading.isNaN() && !previous.heading.isNaN() && abs(((sample.heading - previous.heading + 540.0) % 360.0) - 180.0) >= MIN_TURN_DEG)
        if (keep) last = sample
        return keep
    }

    companion object {
        const val MIN_STEP_M = 0.08
        const val MIN_TURN_DEG = 6.0
        const val KEEP_ALIVE_MS = 10_000L
    }
}

/** Continuous pieces of the track, per activity, for the coloured replay trace. */
object TaskTrackPieces {
    /** Positions more than [GAP_M] or [GAP_MS] apart are not joined (link lost, robot moved). */
    const val GAP_M = 3.0
    const val GAP_MS = 60_000L

    data class Piece(val phase: TrackPhase, val from: Int, val to: Int, val points: List<Point>)

    fun broken(a: TrackSample, b: TrackSample): Boolean = b.atMs - a.atMs > GAP_MS || a.point.distance(b.point) > GAP_M

    /**
     * Pieces [from, to] (inclusive indices). On an activity change, the previous piece extends to the first point of
     * the new activity so the trace stays continuous. A gap separates two pieces without joining them.
     */
    fun of(samples: List<TrackSample>): List<Piece> {
        if (samples.isEmpty()) return emptyList()
        val out = mutableListOf<Piece>()
        fun piece(from: Int, to: Int) { out += Piece(samples[from].phase, from, to, samples.subList(from, to + 1).map { it.point }) }
        var start = 0
        for (i in 1 until samples.size) {
            if (broken(samples[i - 1], samples[i])) { piece(start, i - 1); start = i }
            else if (samples[i].phase != samples[start].phase) { piece(start, i); start = i }
        }
        piece(start, samples.size - 1)
        return out
    }
}

/**
 * Tracks of history tasks: one binary file per task, appended as the task runs (resumes included), and a JSON
 * context file (map, prepared route). Only the [MAX_TRACKS] most recent tasks keep their track.
 */
class TaskTrackStore(directory: File) {
    private val dir = File(directory, "task-tracks")

    private fun key(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
    private fun samplesFile(id: String) = File(dir, "${key(id)}.trk")
    private fun metaFile(id: String) = File(dir, "${key(id)}.json")

    @Synchronized fun hasMeta(id: String): Boolean = metaFile(id).exists()

    @Synchronized fun writeMeta(id: String, meta: TrackMeta) {
        dir.mkdirs()
        val file = metaFile(id)
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(TrackJson.encode(id, meta).toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { tr("Failed to save the track context", "Échec sauvegarde du contexte de la piste") }
    }

    /** Appends [samples] to the track of [id] (file created on the first append). */
    @Synchronized fun append(id: String, samples: List<TrackSample>) {
        if (samples.isEmpty()) return
        dir.mkdirs()
        val file = samplesFile(id)
        val fresh = !file.exists() || file.length() < HEADER_BYTES
        FileOutputStream(file, !fresh).use { stream ->
            DataOutputStream(stream.buffered()).apply {
                if (fresh) { writeInt(MAGIC); writeInt(VERSION) }
                samples.forEach { s ->
                    writeLong(s.atMs); writeFloat(s.x.toFloat()); writeFloat(s.y.toFloat()); writeFloat(s.heading.toFloat())
                    writeByte(s.phase.ordinal); writeByte(s.battery.coerceIn(-1, 127))
                }
                flush()
            }
            stream.fd.sync()
        }
    }

    @Synchronized fun read(id: String): TaskTrack? {
        val meta = runCatching { metaFile(id).takeIf { it.exists() }?.let { TrackJson.decode(JSONObject(it.readText())) } }.getOrNull()
        val samples = readSamples(samplesFile(id))
        if (meta == null && samples.isEmpty()) return null
        return TaskTrack(meta, samples)
    }

    private fun readSamples(file: File): List<TrackSample> {
        if (!file.exists() || file.length() < HEADER_BYTES || file.length() > MAX_FILE_BYTES) return emptyList()
        val out = ArrayList<TrackSample>((file.length() / SAMPLE_BYTES).toInt())
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
                while (true) {
                    val at = try { input.readLong() } catch (_: EOFException) { break }
                    // Truncated last sample (app killed while writing): ignored.
                    val sample = runCatching { TrackSample(at, input.readFloat().toDouble(), input.readFloat().toDouble(),
                        input.readFloat().toDouble(), TrackPhase.of(input.readByte().toInt()), input.readByte().toInt()) }.getOrNull() ?: break
                    if (sample.x.isFinite() && sample.y.isFinite()) out += sample
                }
            }
        }
        return out
    }

    /** Deletes the tracks of tasks missing from [keepIds]. */
    @Synchronized fun prune(keepIds: Collection<String>) {
        val keep = keepIds.map(::key).toSet()
        dir.listFiles().orEmpty().forEach { f -> if (f.nameWithoutExtension.substringBefore('.') !in keep) f.delete() }
    }

    @Synchronized fun delete(id: String) { samplesFile(id).delete(); metaFile(id).delete() }

    companion object {
        const val MAX_TRACKS = 40
        private const val MAGIC = 0x4C544B31 // "LTK1"
        private const val VERSION = 1
        private const val HEADER_BYTES = 8L
        private const val SAMPLE_BYTES = 22L
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024

        /** Ids of the tasks that keep their track: the most recent ones. */
        fun kept(history: List<MowingRecord>): List<String> = history.sortedByDescending { it.startedAt }.take(MAX_TRACKS).map { it.id }
    }
}

/** Track context as JSON: same map shape as the map cache. */
object TrackJson {
    private fun points(list: List<Point>) = JSONArray().apply { list.forEach { put(JSONArray().put(it.x).put(it.y)) } }
    private fun points(array: JSONArray) = (0 until array.length()).map { i -> array.getJSONArray(i).let { Point(it.getDouble(0), it.getDouble(1)) } }
    private fun lines(list: List<List<Point>>) = JSONArray().apply { list.forEach { put(points(it)) } }
    private fun lines(array: JSONArray?) = array?.let { a -> (0 until a.length()).map { points(a.getJSONArray(it)) } }.orEmpty()

    fun encode(id: String, meta: TrackMeta): JSONObject = JSONObject().put("version", 1).put("id", id)
        .put("source", meta.map.source)
        .put("shapes", JSONArray().apply { meta.map.shapes.forEach { s ->
            put(JSONObject().put("id", s.id.toString()).put("type", s.type).put("name", s.name).put("points", points(s.points))) } })
        .apply { meta.map.station?.let { st -> put("station", JSONObject().put("x", st.point.x).put("y", st.point.y)
            .put("heading", st.entryHeadingDegrees ?: JSONObject.NULL).put("origin", st.origin)) } }
        .put("cutting", lines(meta.cutting)).put("transit", lines(meta.transit))

    fun decode(j: JSONObject): TrackMeta {
        val shapes = j.getJSONArray("shapes")
        val map = RobotMap((0 until shapes.length()).map { i -> shapes.getJSONObject(i).let { s ->
            MapShape(s.getString("id").toLong(), s.getInt("type"), points(s.getJSONArray("points")), s.optString("name")) } },
            true, j.optString("source", tr("Task map", "Carte de la tâche")),
            j.optJSONObject("station")?.let { s -> MapStation(Point(s.getDouble("x"), s.getDouble("y")),
                if (s.isNull("heading")) null else s.getDouble("heading"), s.optString("origin")) })
        return TrackMeta(map, lines(j.optJSONArray("cutting")), lines(j.optJSONArray("transit")))
    }
}
