package fr.luba.poc.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Border types: each portion of a zone or no-go boundary can be marked OPEN, CURB, WALL or DANGER. This is the base of
 * the border, speed and precision strategies; for now the types are annotated, saved and drawn (no effect on the route
 * computation yet).
 *
 * An annotation keeps the **geometry** of its portion (polyline along the boundary, in map meters), not point indices:
 * when the boundary changes (map read again, zone recorded again, local correction), the portion is reprojected onto the
 * new boundary if it still follows it within [BorderTypes.TOLERANCE_M]; otherwise it is kept but no longer applied
 * ("to review"). Annotations are laid over the effective map (Mammotion map + local corrections).
 */

/** Type of a boundary portion, with its map colour (ARGB). */
enum class BorderType(
    private val labelEn: String,
    private val labelFr: String,
    private val detailEn: String,
    private val detailFr: String,
    val argb: Long,
) {
    OPEN("Open lawn", "Pelouse ouverte", "The grass carries on beyond: a slight overrun is harmless",
        "La pelouse continue au-delà : un léger dépassement est sans conséquence", 0xFF29B6F6),
    CURB("Curb", "Bordure", "Low edge the wheels can cross: curb, edging strip, path at ground level",
        "Bord bas franchissable par les roues : bordure, volige, allée au ras du sol", 0xFFFFA000),
    WALL("Wall", "Mur", "Vertical obstacle: wall, fence, hedge, house front",
        "Obstacle vertical : mur, clôture, haie, façade", 0xFF8D6E63),
    DANGER("Danger", "Danger", "Sensitive portion: drop, pond, flower bed, road",
        "Portion sensible : dénivelé, bassin, massif, route", 0xFFE53935);

    val label: String get() = tr(labelEn, labelFr)
    val detail: String get() = tr(detailEn, detailFr)
}

/**
 * Portion [points] (at least two, in the order of the boundary at annotation time) of the boundary [shapeId] marked
 * [type]; [whole] for the whole boundary ([points] is then the closed ring). [fingerprint]: boundary fingerprint at
 * annotation time ([BorderValidation.fingerprint]), to tell an unchanged boundary from a reprojected one.
 */
data class BorderAnnotation(
    val id: Long,
    val shapeId: Long,
    val type: BorderType,
    val points: List<Point>,
    val createdAtMs: Long,
    val fingerprint: Long,
    val whole: Boolean = false,
)

/** State of an annotation on the current map. */
enum class BorderAnnotationStatus(private val en: String, private val fr: String, val applied: Boolean) {
    EXACT("Up to date", "À jour", true),
    REPROJECTED("Follows the redrawn boundary", "Suit le contour redessiné", true),
    STALE("To review: boundary changed too much", "À revoir : contour trop modifié", false);
    val label: String get() = tr(en, fr)
}

/**
 * Annotation placed on the current map: target boundary [shapeId] (null if [status] is STALE), interval of the
 * boundary from [from] to [to] in ring positions (segment index + fraction, forward in point order, possibly wrapping;
 * whole boundary when [whole]), portion [points] and its length.
 */
data class ResolvedBorder(
    val annotation: BorderAnnotation,
    val status: BorderAnnotationStatus,
    val shapeId: Long?,
    val from: Double,
    val to: Double,
    val whole: Boolean,
    val points: List<Point>,
    val lengthM: Double,
)

/** Typed portion of a boundary after overlaps are settled (the most recent annotation wins). */
data class BorderPortion(val shapeId: Long, val type: BorderType, val annotationId: Long, val points: List<Point>, val lengthM: Double)

/** Portion chosen in the editor: positions on the ring of [shapeId] and points, in ring order. */
data class BorderSelection(val shapeId: Long, val from: Double, val to: Double, val points: List<Point>, val whole: Boolean = false) {
    val lengthM: Double get() = BorderTypes.length(points)
}

object BorderTypes {
    /** Maximum distance between the saved portion and the current boundary for it to be reprojected (m). */
    const val TOLERANCE_M = 0.30
    /** Shortest portion that can be annotated (m). */
    const val MIN_LENGTH_M = 0.20
    private const val SAMPLE_M = 0.25

    fun contours(map: RobotMap): List<MapShape> = map.shapes.filter { it.kind.isContour && it.points.size >= 3 }

    fun length(points: List<Point>): Double = points.zipWithNext { a, b -> a.distance(b) }.sum()

    /** Point of [ring] at position [pos] (segment + fraction, taken modulo the number of points). */
    fun anchorAt(ring: List<Point>, pos: Double): EdgeRedraw.Anchor {
        val n = ring.size
        val p = ((pos % n) + n) % n
        val seg = floor(p).toInt().coerceIn(0, n - 1)
        val t = (p - seg).coerceIn(0.0, 1.0)
        val a = ring[seg]
        val b = ring[(seg + 1) % n]
        return EdgeRedraw.Anchor(seg, t, Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
    }

    /** Portion of [ring] going forward from position [from] to position [to]. */
    fun portion(ring: List<Point>, from: Double, to: Double): List<Point> = EdgeRedraw.arc(ring, anchorAt(ring, from), anchorAt(ring, to))

    /** Forward span from [from] to [to] on a ring of [n] points. */
    private fun span(from: Double, to: Double, n: Int): Double = ((to - from) % n + n) % n

    private fun closed(ring: List<Point>): List<Point> = ring + ring.first()

    /**
     * Portion of [shape] between the points [a] and [b] (projected onto it): the shorter of the two arcs, or the other one
     * if [otherSide]. Null if the ends are closer than [MIN_LENGTH_M].
     */
    fun select(shape: MapShape, a: Point, b: Point, otherSide: Boolean = false): BorderSelection? {
        val ring = shape.points
        if (ring.size < 3) return null
        val pa = EdgeRedraw.project(ring, a)
        val pb = EdgeRedraw.project(ring, b)
        if (pa.point.distance(pb.point) < MIN_LENGTH_M) return null
        val ab = EdgeRedraw.arc(ring, pa, pb)
        val ba = EdgeRedraw.arc(ring, pb, pa)
        val forward = (length(ab) <= length(ba)) != otherSide
        return if (forward) BorderSelection(shape.id, pa.position(), pb.position(), ab)
        else BorderSelection(shape.id, pb.position(), pa.position(), ba)
    }

    /** The whole boundary of [shape]. */
    fun selectWhole(shape: MapShape): BorderSelection = BorderSelection(shape.id, 0.0, 0.0, closed(shape.points), whole = true)

    /** Points every [SAMPLE_M] along a polyline (ends included). */
    private fun densify(points: List<Point>): List<Point> {
        if (points.size < 2) return points
        val out = mutableListOf(points.first())
        for ((a, b) in points.zipWithNext()) {
            val steps = (a.distance(b) / SAMPLE_M).toInt().coerceIn(1, 4000)
            for (k in 1..steps) out.add(Point(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps))
        }
        return out
    }

    private fun distanceToPolyline(p: Point, line: List<Point>): Double {
        if (line.size == 1) return p.distance(line[0])
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) best = minOf(best, Geometry.distance(p, line[i], line[i + 1]))
        return best
    }

    /** Symmetric deviation (Hausdorff on sampled points) between two polylines. */
    fun deviation(a: List<Point>, b: List<Point>): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.MAX_VALUE
        return maxOf(densify(a).maxOf { distanceToPolyline(it, b) }, densify(b).maxOf { distanceToPolyline(it, a) })
    }

    private data class Fit(val from: Double, val to: Double, val points: List<Point>, val deviation: Double)

    private fun fitOn(annotation: BorderAnnotation, ring: List<Point>): Fit? {
        if (ring.size < 3 || annotation.points.size < 2) return null
        if (annotation.whole) return Fit(0.0, 0.0, closed(ring), deviation(closed(ring), annotation.points))
        val pa = EdgeRedraw.project(ring, annotation.points.first())
        val pb = EdgeRedraw.project(ring, annotation.points.last())
        if (pa.point.distance(pb.point) < 1e-6) return null
        val ab = EdgeRedraw.arc(ring, pa, pb)
        val ba = EdgeRedraw.arc(ring, pb, pa)
        val dab = deviation(ab, annotation.points)
        val dba = deviation(ba, annotation.points)
        return if (dab <= dba) Fit(pa.position(), pb.position(), ab, dab) else Fit(pb.position(), pa.position(), ba, dba)
    }

    /** Places [annotation] on [map]: its boundary first, otherwise any boundary it still follows (shape recorded again). */
    fun resolve(annotation: BorderAnnotation, map: RobotMap): ResolvedBorder {
        val shapes = contours(map)
        val own = shapes.firstOrNull { it.id == annotation.shapeId }
        val candidates = listOfNotNull(own) + shapes.filter { it.id != annotation.shapeId }
        val ends = listOf(annotation.points.first(), annotation.points.last())
        for (shape in candidates) {
            // Cheap filter: the ends must lie near the boundary.
            if (ends.any { EdgeRedraw.project(shape.points, it).point.distance(it) > TOLERANCE_M }) continue
            val fit = fitOn(annotation, shape.points) ?: continue
            if (fit.deviation > TOLERANCE_M) continue
            val exact = shape.id == annotation.shapeId && BorderValidation.fingerprint(shape) == annotation.fingerprint
            return ResolvedBorder(annotation, if (exact) BorderAnnotationStatus.EXACT else BorderAnnotationStatus.REPROJECTED,
                shape.id, fit.from, fit.to, annotation.whole, fit.points, length(fit.points))
        }
        return ResolvedBorder(annotation, BorderAnnotationStatus.STALE, null, 0.0, 0.0, annotation.whole, annotation.points,
            length(annotation.points))
    }

    fun resolveAll(map: RobotMap, annotations: List<BorderAnnotation>): List<ResolvedBorder> = annotations.map { resolve(it, map) }

    /** Position [pos] inside the interval of [r] (whole boundary: always). */
    private fun covers(r: ResolvedBorder, pos: Double, n: Int): Boolean = r.whole || span(r.from, pos, n) <= span(r.from, r.to, n)

    /**
     * Typed portions of [shape]: the applied annotations cut the boundary into intervals, each taking the type of the most
     * recent annotation covering it (list order = creation order). Untyped parts are left out. Contiguous intervals of the
     * same annotation are merged, also across the start of the ring.
     */
    fun portionsOf(shape: MapShape, resolved: List<ResolvedBorder>): List<BorderPortion> {
        val ring = shape.points
        val n = ring.size
        val mine = resolved.filter { it.status.applied && it.shapeId == shape.id }
        if (mine.isEmpty() || n < 3) return emptyList()
        val cuts = (mine.filter { !it.whole }.flatMap { listOf(it.from, it.to) } + 0.0)
            .map { ((it % n) + n) % n }.distinct().sorted()
        data class Piece(val from: Double, val to: Double, val owner: ResolvedBorder?)
        val pieces = cuts.indices.mapNotNull { i ->
            val from = cuts[i]
            val to = if (i + 1 < cuts.size) cuts[i + 1] else cuts[0] + n
            if (to - from < 1e-9) return@mapNotNull null
            val mid = (from + to) / 2
            Piece(from, to, mine.lastOrNull { covers(it, mid, n) })
        }
        val merged = mutableListOf<Piece>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            if (last != null && last.owner === p.owner) merged[merged.size - 1] = last.copy(to = p.to) else merged.add(p)
        }
        if (merged.size > 1 && merged.first().owner === merged.last().owner) {
            val first = merged.removeAt(0)
            val last = merged.removeAt(merged.size - 1)
            merged.add(Piece(last.from, first.to + n, first.owner))
        }
        return merged.mapNotNull { p ->
            val owner = p.owner ?: return@mapNotNull null
            val points = if (p.to - p.from >= n - 1e-9) closed(ring) else portion(ring, p.from, p.to)
            BorderPortion(shape.id, owner.annotation.type, owner.annotation.id, points, length(points))
        }
    }

    /** Typed portions of every boundary of [map]. */
    fun portions(map: RobotMap, annotations: List<BorderAnnotation>): List<BorderPortion> {
        if (annotations.isEmpty()) return emptyList()
        val resolved = resolveAll(map, annotations)
        return contours(map).flatMap { portionsOf(it, resolved) }
    }

    /** Type of the boundary [shapeId] at the point [p] (projected onto it), or null if this part is not typed. */
    fun typeAt(map: RobotMap, annotations: List<BorderAnnotation>, shapeId: Long, p: Point): BorderType? {
        val shape = contours(map).firstOrNull { it.id == shapeId } ?: return null
        val pos = EdgeRedraw.project(shape.points, p).position()
        val n = shape.points.size
        return resolveAll(map, annotations).lastOrNull { it.status.applied && it.shapeId == shapeId && covers(it, pos, n) }?.annotation?.type
    }

    /** Annotation of [map] whose drawn portion passes nearest to [p], within [reachM]; null otherwise. */
    fun annotationNear(map: RobotMap, annotations: List<BorderAnnotation>, p: Point, reachM: Double): BorderAnnotation? {
        val best = portions(map, annotations).minByOrNull { distanceToPolyline(p, it.points) } ?: return null
        if (distanceToPolyline(p, best.points) > reachM) return null
        return annotations.firstOrNull { it.id == best.annotationId }
    }

    /**
     * Adds (or replaces, same id) [annotation] in [list] and drops the applied annotations now hidden entirely by newer
     * ones: a retyped portion is not kept twice. Stale annotations are kept (they can be reviewed).
     */
    fun add(list: List<BorderAnnotation>, annotation: BorderAnnotation, map: RobotMap): List<BorderAnnotation> =
        prune(list.filter { it.id != annotation.id } + annotation, map)

    fun prune(list: List<BorderAnnotation>, map: RobotMap): List<BorderAnnotation> {
        val resolved = resolveAll(map, list)
        val visible = contours(map).flatMap { portionsOf(it, resolved) }.map { it.annotationId }.toSet()
        return list.filterIndexed { i, a -> !resolved[i].status.applied || a.id in visible }
    }

    /** New annotation of the [selection] of [shape]. */
    fun annotate(shape: MapShape, selection: BorderSelection, type: BorderType, id: Long, nowMs: Long): BorderAnnotation =
        BorderAnnotation(id, shape.id, type, if (selection.whole) closed(shape.points) else selection.points, nowMs,
            BorderValidation.fingerprint(shape), selection.whole)

    /**
     * Storage format (`border-types.json`): `{schemaVersion: 1, borders: [{id, shape, type, at, fingerprint, whole,
     * points: [x0, y0, x1, y1, ...]}]}`, coordinates to the millimeter. An unreadable entry (including an unknown type)
     * is ignored without losing the others.
     */
    const val SCHEMA_VERSION = 1
    const val MAX_ANNOTATIONS = 500
    private const val MAX_POINTS = 20_000

    fun encode(list: List<BorderAnnotation>): String {
        val items = JSONArray()
        list.forEach { a ->
            val coords = JSONArray()
            a.points.forEach { coords.put(mm(it.x)); coords.put(mm(it.y)) }
            items.put(JSONObject().put("id", a.id).put("shape", a.shapeId).put("type", a.type.name).put("at", a.createdAtMs)
                .put("fingerprint", a.fingerprint).put("whole", a.whole).put("points", coords))
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("borders", items).toString()
    }

    private fun mm(v: Double): Double = (v * 1000).roundToLong() / 1000.0

    fun decode(text: String?): List<BorderAnnotation> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optInt("schemaVersion", 0) > SCHEMA_VERSION) return emptyList()
        val items = root.optJSONArray("borders") ?: return emptyList()
        return (0 until minOf(items.length(), MAX_ANNOTATIONS)).mapNotNull { i ->
            runCatching {
                val o = items.getJSONObject(i)
                val coords = o.getJSONArray("points")
                require(coords.length() % 2 == 0 && coords.length() in 4..MAX_POINTS * 2)
                val points = (0 until coords.length() / 2).map { k ->
                    Point(coords.getDouble(2 * k), coords.getDouble(2 * k + 1)).also { require(it.x.isFinite() && it.y.isFinite()) }
                }
                BorderAnnotation(o.getLong("id"), o.getLong("shape"), BorderType.valueOf(o.getString("type")), points,
                    o.getLong("at"), o.getLong("fingerprint"), o.optBoolean("whole", false))
            }.getOrNull()
        }.distinctBy { it.id }
    }
}

/**
 * Border types saved on the phone (`filesDir/border-types.json`, atomic write), like the local corrections: not tied
 * to a robot, each annotation follows its boundary and has no effect on a map where it finds none. The list is a
 * Compose state: maps drawing it are redrawn when it changes.
 */
object BorderTypeRepository {
    private const val FILE = "border-types.json"
    private var dir: File? = null
    private var memo: Triple<RobotMap, List<BorderAnnotation>, List<BorderPortion>>? = null

    var annotations by mutableStateOf(emptyList<BorderAnnotation>())
        private set

    /** Editor requested from the Map sheet, shown by [BorderTypeEditorHost]. */
    var editorOpen by mutableStateOf(false)

    fun init(context: android.content.Context) {
        if (dir != null) return
        val d = context.applicationContext.filesDir
        dir = d
        annotations = load(d)
    }

    internal fun load(d: File): List<BorderAnnotation> = runCatching {
        val f = File(d, FILE)
        if (!f.exists() || f.length() > 8 * 1024 * 1024) emptyList() else BorderTypes.decode(f.readText())
    }.getOrDefault(emptyList())

    /** Saves the full list; false if writing failed (the list in memory is then unchanged). */
    fun save(list: List<BorderAnnotation>): Boolean {
        val d = dir
        if (d != null) {
            val ok = runCatching {
                val f = File(d, FILE)
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { it.write(BorderTypes.encode(list).toByteArray()); it.fd.sync() }
                check(tmp.renameTo(f))
            }.isSuccess
            if (!ok) return false
        }
        annotations = list
        return true
    }

    /** Typed portions of [map] for drawing, kept while neither the map nor the list changes. */
    fun portions(map: RobotMap): List<BorderPortion> {
        val list = annotations
        if (list.isEmpty()) return emptyList()
        memo?.let { (m, l, p) -> if ((m === map || m == map) && l === list) return p }
        val p = runCatching { BorderTypes.portions(map, list) }.getOrDefault(emptyList())
        memo = Triple(map, list, p)
        return p
    }

    /** Reset for tests: memory only ([d] null) or a temporary directory. */
    internal fun resetForTest(list: List<BorderAnnotation> = emptyList(), d: File? = null) {
        dir = d; memo = null; annotations = list; editorOpen = false
    }
}
