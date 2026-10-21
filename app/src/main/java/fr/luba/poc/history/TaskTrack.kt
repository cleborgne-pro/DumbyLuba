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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/** Robot activity at a point of the track: trace colour and replay label. */
enum class TrackPhase(private val labelEn: String, private val labelFr: String) {
    CUTTING("Mowing", "Tonte"), TRAVEL("Travel with blades stopped", "Déplacement lames arrêtées"), RETURN("Return to base", "Retour à la base"), DOCKING("Docking", "Accostage"),
    CHARGING("Recharge", "Recharge"), OTHER("Other", "Autre");

    val label: String get() = tr(labelEn, labelFr)

    companion object { fun of(code: Int): TrackPhase = entries.getOrElse(code) { OTHER } }
}

/** State of the link to the robot when a position was recorded. */
enum class TrackLinkState(private val labelEn: String, private val labelFr: String) {
    UNKNOWN("Unknown", "Inconnue"),
    /** Pilot phone over Bluetooth. */
    BLUETOOTH("Direct Bluetooth", "Bluetooth direct"),
    /** Pilot phone through the gateway relay (Wi-Fi in the control loop). */
    RELAY("Gateway relay", "Relais passerelle"),
    /** Route handed to the gateway, which tracks it on its own. */
    GATEWAY("Gateway, autonomous tracking", "Passerelle, suivi autonome"),
    /** Gateway Wi-Fi cut during autonomous tracking: the robot carries on alone. */
    GATEWAY_OUTAGE("Gateway Wi-Fi cut", "Coupure Wi-Fi passerelle");

    val label: String get() = tr(labelEn, labelFr)

    companion object { fun of(code: Int): TrackLinkState = entries.getOrElse(code) { UNKNOWN } }
}

/**
 * Actual position recorded during a task: time (epoch ms), robot rotation centre in map metres,
 * heading (mathematical degrees, NaN if unknown), activity and battery (-1 if unknown).
 *
 * Since track format 2: last speed command written by this phone ([requestedLinear], [requestedAngular], raw protocol
 * units, null when this phone did not drive the robot in the last seconds: gateway tracking, native return), speed
 * measured between two positions ([speedMps], NaN if unknown), RTK status ([rtkStatus], 4 = FIX, -1 unknown),
 * satellites ([satellites], -1 unknown) and link state ([link]).
 */
data class TrackSample(val atMs: Long, val x: Double, val y: Double, val heading: Double, val phase: TrackPhase, val battery: Int = -1,
                       val requestedLinear: Int? = null, val requestedAngular: Int? = null, val speedMps: Double = Double.NaN,
                       val rtkStatus: Int = -1, val satellites: Int = -1, val link: TrackLinkState = TrackLinkState.UNKNOWN) {
    val point: Point get() = Point(x, y)
}

/** Replay context, frozen at start: task map, prepared route (cutting stripes, links) and task zones (empty: unknown,
 * tracks recorded before the real coverage). */
data class TrackMeta(val map: RobotMap, val cutting: List<List<Point>> = emptyList(), val transit: List<List<Point>> = emptyList(),
                     val zoneIds: List<Long> = emptyList())

/**
 * Full track of a history task; [meta] is null for a track without context (lost file); [events]: typed task journal;
 * [boxes]: black boxes frozen at the incidents.
 */
data class TaskTrack(val meta: TrackMeta?, val samples: List<TrackSample>, val events: List<TaskEvent> = emptyList(),
                     val boxes: List<BlackBox> = emptyList()) {
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
 * Tracks of history tasks, one set of files per task:
 * - `<key>.trk`: samples appended as the task runs (resumes included), raw so that a killed app loses at most the
 *   last sample;
 * - `<key>.trkz`: samples of the finished runs, gzip-compressed by [seal] at the end of each run
 *   (smaller than raw); a resume appends to a new `.trk`, merged at the next [seal];
 * - `<key>.evt`: typed task events ([TaskEvent]), one JSON line each;
 * - `<key>.box`: black boxes frozen at the incidents ([BlackBox]), one JSON line each;
 * - `<key>.json`: context (map, prepared route).
 *
 * Size stays bounded: [MAX_TRACKS] most recent tasks, [MAX_RAW_BYTES] of raw samples and [MAX_EVENT_BYTES] of events
 * per task, [MAX_BOX_BYTES] of black boxes per task, and [MAX_DIR_BYTES] for all tasks (oldest tracks deleted first by [prune]).
 */
class TaskTrackStore(directory: File) {
    private val dir = File(directory, "task-tracks")

    private fun key(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
    private fun samplesFile(id: String) = File(dir, "${key(id)}.trk")
    private fun sealedFile(id: String) = File(dir, "${key(id)}.trkz")
    private fun eventsFile(id: String) = File(dir, "${key(id)}.evt")
    private fun boxesFile(id: String) = File(dir, "${key(id)}.box")
    private fun metaFile(id: String) = File(dir, "${key(id)}.json")

    @Synchronized fun hasMeta(id: String): Boolean = metaFile(id).exists()

    @Synchronized fun writeMeta(id: String, meta: TrackMeta) {
        dir.mkdirs()
        val file = metaFile(id)
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(TrackJson.encode(id, meta).toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { tr("Failed to save the track context", "Échec sauvegarde du contexte de la piste") }
    }

    /**
     * Appends [samples] to the track of [id] (file created on the first append). Returns false when the raw file has
     * reached [MAX_RAW_BYTES]: the samples are dropped.
     */
    @Synchronized fun append(id: String, samples: List<TrackSample>): Boolean {
        if (samples.isEmpty()) return true
        dir.mkdirs()
        val file = samplesFile(id)
        val fresh = !file.exists() || file.length() < HEADER_BYTES
        if (!fresh && file.length() >= MAX_RAW_BYTES) return false
        // A run started before format 2 keeps its layout until it is sealed.
        val version = if (fresh) VERSION else runCatching { DataInputStream(file.inputStream()).use { it.readInt(); it.readInt() } }.getOrDefault(VERSION)
        FileOutputStream(file, !fresh).use { stream ->
            DataOutputStream(stream.buffered()).apply {
                if (fresh) { writeInt(MAGIC); writeInt(VERSION) }
                samples.forEach { writeSample(this, it, version) }
                flush()
            }
            stream.fd.sync()
        }
        return true
    }

    /** Appends [events] to the journal of task [id]; returns false once [MAX_EVENT_BYTES] is reached (events dropped). */
    @Synchronized fun appendEvents(id: String, events: List<TaskEvent>): Boolean {
        if (events.isEmpty()) return true
        dir.mkdirs()
        val file = eventsFile(id)
        if (file.length() >= MAX_EVENT_BYTES) return false
        FileOutputStream(file, true).use { stream ->
            stream.write(events.joinToString("") { TaskEventJson.encode(it).toString() + "\n" }.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        return true
    }

    /** Appends black box [box] to task [id]; returns false once [MAX_BOX_BYTES] is reached (box dropped). */
    @Synchronized fun appendBox(id: String, box: BlackBox): Boolean {
        dir.mkdirs()
        val file = boxesFile(id)
        if (file.length() >= MAX_BOX_BYTES) return false
        FileOutputStream(file, true).use { stream ->
            stream.write((BlackBoxJson.encode(box).toString() + "\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        return true
    }

    /**
     * End of a run of task [id]: the raw samples are merged into the compressed file (current format), then the raw
     * file is deleted. A crash between the two leaves both files; [read] drops the duplicates.
     */
    @Synchronized fun seal(id: String) {
        val raw = samplesFile(id)
        if (!raw.exists()) return
        val sealed = sealedFile(id)
        val samples = (readSealed(sealed) + readRaw(raw)).let(::dedupe)
        if (samples.isNotEmpty()) {
            val tmp = File(sealed.path + ".tmp")
            FileOutputStream(tmp).use { stream ->
                DataOutputStream(GZIPOutputStream(stream.buffered()).buffered()).apply {
                    writeInt(MAGIC); writeInt(VERSION)
                    samples.forEach { writeSample(this, it, VERSION) }
                    close()
                }
            }
            // FileOutputStream closed by the gzip stream: sync through a fresh handle.
            RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            check(tmp.renameTo(sealed)) { tr("Failed to compress the track", "Échec compression de la piste") }
        }
        raw.delete()
    }

    @Synchronized fun read(id: String): TaskTrack? {
        val meta = runCatching { metaFile(id).takeIf { it.exists() }?.let { TrackJson.decode(JSONObject(it.readText())) } }.getOrNull()
        val samples = dedupe(readSealed(sealedFile(id)) + readRaw(samplesFile(id)))
        val events = readEvents(eventsFile(id))
        val boxes = readBoxes(boxesFile(id))
        if (meta == null && samples.isEmpty() && events.isEmpty() && boxes.isEmpty()) return null
        return TaskTrack(meta, samples, events, boxes)
    }

    /** Bytes used by the files of task [id]. */
    @Synchronized fun bytes(id: String): Long = listOf(samplesFile(id), sealedFile(id), eventsFile(id), boxesFile(id), metaFile(id)).sumOf { it.length() }

    private fun dedupe(samples: List<TrackSample>): List<TrackSample> =
        if (samples.zipWithNext().all { (a, b) -> b.atMs > a.atMs }) samples else samples.distinctBy { it.atMs }.sortedBy { it.atMs }

    private fun readRaw(file: File): List<TrackSample> {
        if (!file.exists() || file.length() < HEADER_BYTES || file.length() > MAX_RAW_BYTES + 1024 * 1024) return emptyList()
        return runCatching { file.inputStream().buffered().use(::readSamples) }.getOrDefault(emptyList())
    }

    private fun readSealed(file: File): List<TrackSample> {
        if (!file.exists() || file.length() < HEADER_BYTES) return emptyList()
        return runCatching { GZIPInputStream(file.inputStream().buffered()).use(::readSamples) }.getOrDefault(emptyList())
    }

    private fun readSamples(stream: java.io.InputStream): List<TrackSample> {
        val out = ArrayList<TrackSample>()
        runCatching {
            val input = DataInputStream(stream)
            if (input.readInt() != MAGIC) return emptyList()
            val version = input.readInt()
            if (version != 1 && version != VERSION) return emptyList()
            while (true) {
                val at = try { input.readLong() } catch (_: EOFException) { break }
                // Truncated last sample (app killed while writing): ignored.
                val sample = runCatching { readSample(input, at, version) }.getOrNull() ?: break
                if (sample.x.isFinite() && sample.y.isFinite()) out += sample
            }
        }
        return out
    }

    private fun readSample(input: DataInputStream, at: Long, version: Int): TrackSample {
        val x = input.readFloat().toDouble(); val y = input.readFloat().toDouble(); val heading = input.readFloat().toDouble()
        val phase = TrackPhase.of(input.readByte().toInt()); val battery = input.readByte().toInt()
        if (version == 1) return TrackSample(at, x, y, heading, phase, battery)
        val linear = input.readShort().toInt(); val angular = input.readShort().toInt()
        val speed = input.readFloat().toDouble()
        val rtk = input.readByte().toInt(); val satellites = input.readByte().toInt(); val link = TrackLinkState.of(input.readByte().toInt())
        return TrackSample(at, x, y, heading, phase, battery, linear.takeIf { it != NO_COMMAND }, angular.takeIf { it != NO_COMMAND },
            speed, rtk, satellites, link)
    }

    private fun writeSample(out: DataOutputStream, s: TrackSample, version: Int) {
        out.writeLong(s.atMs); out.writeFloat(s.x.toFloat()); out.writeFloat(s.y.toFloat()); out.writeFloat(s.heading.toFloat())
        out.writeByte(s.phase.ordinal); out.writeByte(s.battery.coerceIn(-1, 127))
        if (version == 1) return
        out.writeShort(s.requestedLinear?.coerceIn(-32767, 32767) ?: NO_COMMAND); out.writeShort(s.requestedAngular?.coerceIn(-32767, 32767) ?: NO_COMMAND)
        out.writeFloat(s.speedMps.toFloat())
        out.writeByte(s.rtkStatus.coerceIn(-1, 127)); out.writeByte(s.satellites.coerceIn(-1, 127)); out.writeByte(s.link.ordinal)
    }

    private fun readBoxes(file: File): List<BlackBox> {
        if (!file.exists() || file.length() > MAX_BOX_BYTES + 512 * 1024) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { BlackBoxJson.decode(JSONObject(line)) }.getOrNull() } }
            .getOrDefault(emptyList()).sortedBy { it.atMs }
    }

    private fun readEvents(file: File): List<TaskEvent> {
        if (!file.exists() || file.length() > MAX_EVENT_BYTES + 64 * 1024) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { TaskEventJson.decode(JSONObject(line)) }.getOrNull() } }
            .getOrDefault(emptyList()).sortedBy { it.atMs }
    }

    /**
     * Keeps the files of [keepIds] (most recent first) while they fit in [MAX_DIR_BYTES]: the older tracks beyond the
     * budget are deleted too. Files of other tasks are deleted.
     */
    @Synchronized fun prune(keepIds: List<String>) {
        var used = 0L
        val keep = HashSet<String>()
        keepIds.forEach { id ->
            val size = bytes(id)
            if (used + size <= MAX_DIR_BYTES || keep.isEmpty()) { keep += key(id); used += size }
        }
        dir.listFiles().orEmpty().forEach { f -> if (f.name.substringBefore('.') !in keep) f.delete() }
    }

    @Synchronized fun delete(id: String) { samplesFile(id).delete(); sealedFile(id).delete(); eventsFile(id).delete(); boxesFile(id).delete(); metaFile(id).delete() }

    companion object {
        const val MAX_TRACKS = 40
        /** Raw samples of one task (about 400 000 positions, far beyond a day of mowing). */
        const val MAX_RAW_BYTES = 12L * 1024 * 1024
        /** Events of one task (several thousand events). */
        const val MAX_EVENT_BYTES = 512L * 1024
        /** Black boxes of one task (a few dozen boxes). */
        const val MAX_BOX_BYTES = 2L * 1024 * 1024
        /** All task files together. */
        const val MAX_DIR_BYTES = 64L * 1024 * 1024
        private const val MAGIC = 0x4C544B31 // "LTK1"
        /** 1: position, heading, phase, battery (22 bytes); 2: + commands, measured speed, RTK, satellites, link (33 bytes). */
        private const val VERSION = 2
        private const val HEADER_BYTES = 8L
        private const val NO_COMMAND = -32768

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
        .put("zones", JSONArray().apply { meta.zoneIds.forEach { put(it.toString()) } })

    fun decode(j: JSONObject): TrackMeta {
        val shapes = j.getJSONArray("shapes")
        val map = RobotMap((0 until shapes.length()).map { i -> shapes.getJSONObject(i).let { s ->
            MapShape(s.getString("id").toLong(), s.getInt("type"), points(s.getJSONArray("points")), s.optString("name")) } },
            true, j.optString("source", tr("Task map", "Carte de la tâche")),
            j.optJSONObject("station")?.let { s -> MapStation(Point(s.getDouble("x"), s.getDouble("y")),
                if (s.isNull("heading")) null else s.getDouble("heading"), s.optString("origin")) })
        val zones = j.optJSONArray("zones")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() } }.orEmpty()
        return TrackMeta(map, lines(j.optJSONArray("cutting")), lines(j.optJSONArray("transit")), zones)
    }
}
