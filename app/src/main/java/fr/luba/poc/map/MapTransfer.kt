package fr.luba.poc.map
import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*

/**
 * Bounded reading of the robot map, frame indices 1..total as upstream, one element at a time like the Mammotion app
 * (APK `HashDataManager`, PyMammotion `MapFetchSaga`):
 * - every frame of the reading (`action` 8) is echoed, stray ones included: the robot repeats an unacknowledged
 *   frame and does not serve the next request meanwhile;
 * - an element that stops answering is asked again [requestTimeoutMs] after its request or [frameTimeoutMs] after its
 *   last frame, at most [maxAttempts] times, then left out: the reading goes on with the next ones and the map stays
 *   incomplete instead of stalling on it until the global timeout;
 * - an element answered as a drawing (type 13, `toapp_svg_msg`) is done without boundary; one answered without
 *   points (`action` 8, empty frame) is done too, the reading goes on, but the map stays incomplete: the Mammotion app
 *   skips it, here a no-go zone must never vanish silently;
 * - every PyMammotion element type has its [ShapeKind]; an element of any other type is kept and drawn
 *   ([ShapeKind.UNKNOWN]), even without points, the map completes and the planning and moves refuse it
 *   ([MapShape.refusesPlanning], [MapShape.refusesMoves]); a frame that cannot be understood is skipped, only the size
 *   limits ([LimitException]) end the reading.
 * [clock]: monotonic milliseconds. [notices]: steps to record in the log, drained by the caller.
 */
class MapTransfer(private val syncType: Int = 2, initialStation: MapStation? = null,
                  private val requestTimeoutMs: Long = 5_000, private val frameTimeoutMs: Long = 3_000,
                  private val maxAttempts: Int = 5, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Notice(val kind: String, val detail: String, val fields: Map<String, Any?> = emptyMap())

    private val stationReader = StationReader(initialStation)
    private val roots = sortedMapOf<Int, NavGetHashListAck>()
    private val frames = mutableMapOf<Long, MutableMap<Int, NavGetCommDataAck>>()
    private val drawingFrames = mutableMapOf<Long, MutableSet<Int>>()
    /** Elements answered without points or as a drawing: done, no boundary. */
    private val withoutBoundary = mutableMapOf<Long, String>()
    private val abandoned = mutableSetOf<Long>()
    private val pending = mutableListOf<Notice>()
    private var ignoredNotices = 0
    private var total = 0
    private var hashList: List<Long> = emptyList()
    private var current: Long? = null
    private var attempts = 0
    private var rootAttempts = 1
    private var deadline: Long? = clock() + requestTimeoutMs
    /** Reading stopped for good (hash list never received). */
    var failure: String? = null; private set

    private val rootsComplete get() = total > 0 && roots.size == total
    private fun complete(h: Long) = frames[h]?.let { it.size == it.values.first().totalFrame } == true
    private fun done(h: Long) = complete(h) || h in withoutBoundary || h in abandoned
    /** Every element is either read or left out (or the reading failed): nothing more will come. */
    val finished: Boolean get() = failure != null || (rootsComplete && hashList.all(::done))
    /** Elements read (boundary or drawing), out of [announced] (null until the element list is received). */
    val read: Int get() = hashList.count { complete(it) || it in withoutBoundary }
    val announced: Int? get() = if (rootsComplete) hashList.size else null
    /** Elements left out (no answer) or answered empty: why a finished reading is not complete. */
    val missing: Int get() = abandoned.size + withoutBoundary.count { it.value == EMPTY }

    fun notices(): List<Notice> = pending.toList().also { pending.clear() }
    private fun note(kind: String, detail: String, fields: Map<String, Any?> = emptyMap()) { pending.add(Notice(kind, detail, fields)) }
    private fun ignored(reason: String, f: NavGetCommDataAck) {
        if (ignoredNotices++ < 40) note("map_frame_ignored", reason, mapOf("hash" to f.hash, "type" to f.type, "action" to f.action, "result" to f.result,
            "frame" to f.currentFrame, "totalFrames" to f.totalFrame, "points" to f.dataCoupleCount, "current" to current))
    }
    private fun index(h: Long) = hashList.indexOf(h) + 1

    fun accept(message: LubaMsg, commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        stationReader.accept(message)
        if (!message.hasNav() || failure != null) return emptyList()
        val n = message.nav; val out = mutableListOf<ByteArray>()
        if (n.hasToappGethashAck()) { val f = n.toappGethashAck
            if (f.subCmd != 0) return out
            // Refused or malformed list frame: echoed when possible, the list is asked again (tick).
            if (f.result != 0 || f.totalFrame !in 1..1024 || f.currentFrame !in 1..f.totalFrame) {
                if (f.totalFrame in 1..1024 && f.currentFrame in 1..f.totalFrame) out.add(commands.mapHashesAck(f.totalFrame, f.currentFrame))
                if (ignoredNotices++ < 40) note("map_hash_list_ignored", tr("Element list frame refused", "Trame de liste d'éléments refusée"),
                    mapOf("result" to f.result, "frame" to f.currentFrame, "totalFrames" to f.totalFrame))
                return out
            }
            out.add(commands.mapHashesAck(f.totalFrame, f.currentFrame))
            if (!rootsComplete) {
                if (total != f.totalFrame) roots.clear()
                total = f.totalFrame; roots[f.currentFrame] = f
                deadline = clock() + frameTimeoutMs
                if (rootsComplete) {
                    hashList = roots.values.flatMap { it.dataCoupleList }.distinct()
                    if (hashList.size > 1024) throw LimitException(tr("Map too large: ${hashList.size} elements", "Carte trop volumineuse : ${hashList.size} éléments"))
                    note("map_hash_list", tr("${hashList.size} map elements announced", "${hashList.size} éléments de carte annoncés"), mapOf("elements" to hashList.size, "frames" to total))
                    out.addAll(next(commands))
                }
            }
        }
        // A frame the reading does not understand is noted and skipped: only the size limits (LimitException) end it.
        if (n.hasToappGetCommondataAck()) guarded("boundary", out) { boundaryFrame(n.toappGetCommondataAck, commands) }
        if (n.hasToappSvgMsg()) guarded("drawing", out) { drawingFrame(n.toappSvgMsg, commands) }
        return out
    }

    private fun guarded(channel: String, out: MutableList<ByteArray>, block: () -> List<ByteArray>) {
        try { out.addAll(block()) }
        catch (e: LimitException) { throw e }
        catch (e: RuntimeException) {
            if (ignoredNotices++ < 40) note("map_frame_error", tr("Map frame skipped: ${e.javaClass.simpleName}", "Trame de carte ignorée : ${e.javaClass.simpleName}"),
                mapOf("channel" to channel, "error" to e.message, "current" to current))
        }
    }

    private fun boundaryFrame(f: NavGetCommDataAck, commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        // Only the reading frames are echoed: any other action belongs to another flow (recording, editing).
        if (f.action != 8) return emptyList()
        val out = mutableListOf(commands.mapDataAck(f))
        if (!rootsComplete || f.hash !in hashList || done(f.hash)) { if (f.hash != 0L) ignored("stray", f); return out }
        if (f.result != 0) { ignored("result", f); return out }
        val kind = ShapeKind.from(f.type)
        if (f.dataCoupleCount == 0 && f.totalFrame <= 1) when {
            // Drawing answered on the boundary channel (PyMammotion `test_svg_routing`) or informational element without
            // points: nothing to draw, done.
            kind.informational -> { finish(f.hash, if (kind == ShapeKind.DRAWING) DRAWING else NO_GEOMETRY, f.type, 0); return out + next(commands) }
            // Zone, no-go zone or passage without points: anomalous, the map stays incomplete.
            kind == ShapeKind.ZONE || kind == ShapeKind.NO_GO || kind == ShapeKind.PASSAGE -> { finish(f.hash, EMPTY, f.type, 0); return out + next(commands) }
            // Any other element (virtual wall, rectangular no-go zone, unknown type…) is kept without points: the map
            // completes and the planning and moves refuse it (MapShape.refusesPlanning / refusesMoves), it never vanishes.
            else -> return out + store(f.toBuilder().setTotalFrame(1).setCurrentFrame(1).build(), commands)
        }
        if (f.totalFrame !in 1..4096 || f.currentFrame !in 1..f.totalFrame || f.dataCoupleCount > 10000 ||
            !f.dataCoupleList.all { it.x.isFinite() && it.y.isFinite() }) { ignored("invalid", f); return out }
        return out + store(f, commands)
    }

    private fun store(f: NavGetCommDataAck, commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val bucket = frames.getOrPut(f.hash) { mutableMapOf() }
        // The robot restarted the element with other metadata (asked again): the new series replaces the old one.
        if (bucket.values.any { it.totalFrame != f.totalFrame || it.type != f.type }) bucket.clear()
        if (frames.values.sumOf { values -> values.values.sumOf { it.dataCoupleCount } } + f.dataCoupleCount > 100000) throw LimitException(tr("Map too large", "Carte trop volumineuse"))
        bucket[f.currentFrame] = f
        if (f.hash == current) deadline = clock() + frameTimeoutMs
        if (complete(f.hash)) {
            note("map_element", tr("Element ${index(f.hash)}/${hashList.size} read", "Élément ${index(f.hash)}/${hashList.size} lu"),
                mapOf("index" to index(f.hash), "hash" to f.hash, "type" to f.type, "frames" to f.totalFrame,
                    "points" to bucket.values.sumOf { it.dataCoupleCount }, "attempts" to attempts, "kind" to ShapeKind.from(f.type).name))
            if (f.hash == current) out.addAll(next(commands))
        }
        return out
    }

    private fun drawingFrame(f: svg_message_ack_t, commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        val out = mutableListOf(commands.svgAck(f))
        if (!rootsComplete || f.result != 0) return out
        // A drawing tile tied to the element being read (its parent) is progress of that element.
        if (current != null && f.paternalHashA == current) deadline = clock() + frameTimeoutMs
        val h = f.dataHash
        if (h !in hashList || done(h)) return out
        if (h == current) deadline = clock() + frameTimeoutMs
        val got = drawingFrames.getOrPut(h) { mutableSetOf() }.apply { add(f.currentFrame) }
        if (f.totalFrame <= 0 || got.size >= f.totalFrame) {
            finish(h, DRAWING, DRAWING_TYPE, f.totalFrame)
            if (h == current) out.addAll(next(commands))
        }
        return out
    }

    private fun finish(h: Long, reason: String, type: Int, frameCount: Int) {
        withoutBoundary[h] = reason
        note("map_element", tr("Element ${index(h)}/${hashList.size} without boundary ($reason)", "Élément ${index(h)}/${hashList.size} sans contour ($reason)"),
            mapOf("index" to index(h), "hash" to h, "type" to type, "frames" to frameCount, "points" to 0, "attempts" to attempts, "kind" to reason))
    }

    /** Requests the first element not read yet, or ends the reading. */
    private fun next(commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        val h = hashList.firstOrNull { !done(it) }
        if (h == null) { current = null; deadline = null; return emptyList() }
        if (h != current) { current = h; attempts = 0 }
        return request(h, commands)
    }

    private fun request(h: Long, commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        attempts++
        frames.remove(h); drawingFrames.remove(h)
        deadline = clock() + requestTimeoutMs
        return listOf(commands.sync(syncType = syncType), commands.mapData(h))
    }

    /** To call regularly (every few hundred ms): asks again what stopped answering, leaves out what never answers. */
    fun tick(commands: fr.luba.poc.protocol.Commands): List<ByteArray> {
        val limit = deadline ?: return emptyList()
        if (failure != null || clock() < limit) return emptyList()
        if (!rootsComplete) {
            if (rootAttempts >= maxAttempts) {
                failure = tr("map element list not received after $rootAttempts requests", "liste des éléments de carte non reçue après $rootAttempts demandes")
                note("map_hash_list_failed", failure!!, mapOf("attempts" to rootAttempts, "framesReceived" to roots.size, "totalFrames" to total)); deadline = null
                return emptyList()
            }
            rootAttempts++
            note("map_hash_list_retry", tr("Element list asked again", "Liste des éléments redemandée"), mapOf("attempt" to rootAttempts, "framesReceived" to roots.size, "totalFrames" to total))
            deadline = clock() + requestTimeoutMs
            return listOf(commands.sync(syncType = syncType), commands.mapHashes())
        }
        val h = current ?: return emptyList()
        val received = frames[h]?.size ?: drawingFrames[h]?.size ?: 0
        val expected = frames[h]?.values?.firstOrNull()?.totalFrame
        if (attempts >= maxAttempts) {
            abandoned.add(h)
            note("map_element_abandoned", tr("Element ${index(h)}/${hashList.size} without answer after $attempts requests: left out", "Élément ${index(h)}/${hashList.size} sans réponse après $attempts demandes : ignoré"),
                mapOf("index" to index(h), "hash" to h, "attempts" to attempts, "framesReceived" to received, "totalFrames" to expected))
            return next(commands)
        }
        note("map_element_retry", tr("Element ${index(h)}/${hashList.size} asked again", "Élément ${index(h)}/${hashList.size} redemandé"),
            mapOf("index" to index(h), "hash" to h, "attempt" to attempts + 1, "framesReceived" to received, "totalFrames" to expected))
        return request(h, commands)
    }

    fun snapshot(): RobotMap {
        val shapes = frames.mapNotNull { (h, fs) -> val first = fs.values.first(); if (fs.size != first.totalFrame) null else MapShape(h, first.type, fs.toSortedMap().values.flatMap { it.dataCoupleList }.map { Point(it.x.toDouble(), it.y.toDouble()) }, first.nameTime.name) }
        val uninterpreted = shapes.filter { it.kind == ShapeKind.UNKNOWN }.map { it.type }.distinct().sorted()
        val empty = withoutBoundary.count { it.value == EMPTY }
        val done = rootsComplete && abandoned.isEmpty() && empty == 0 && hashList.all(::done)
        val label = tr("${if (syncType == 2) "BLE" else "Cloud"} map · $read/${if (rootsComplete) hashList.size else "?"} items",
            "Carte ${if (syncType == 2) "BLE" else "cloud"} · $read/${if (rootsComplete) hashList.size else "?"} éléments") +
            (if (abandoned.isNotEmpty()) tr(" · ${abandoned.size} without answer", " · ${abandoned.size} sans réponse") else "") +
            (if (empty > 0) tr(" · $empty empty", " · $empty vide(s)") else "") +
            (if (uninterpreted.isNotEmpty()) tr(" · unknown types: ${uninterpreted.joinToString()}", " · types inconnus : ${uninterpreted.joinToString()}") else "")
        return RobotMap(shapes, done && shapes.all { !it.kind.isContour || it.points.size >= 3 }, label, stationReader.station)
    }

    /** Size limit of the reading (elements, points): the only failure that ends it at once. */
    class LimitException(message: String) : IllegalArgumentException(message)

    companion object {
        /** Upstream type of the drawings (SVG patterns, PyMammotion `PathType.SVG`). */
        const val DRAWING_TYPE = 13
        private const val DRAWING = "drawing"
        private const val NO_GEOMETRY = "no geometry"
        private const val EMPTY = "empty"
    }
}
