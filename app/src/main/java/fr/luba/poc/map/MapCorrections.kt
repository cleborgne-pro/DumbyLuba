package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Local map corrections: a layer laid over the Mammotion map, never written to the robot nor to its cache. The map
 * used by the app (display, planning, simulation, control) is always recomputed: Mammotion map → active corrections,
 * in order → corrected map. Removing or disabling a correction means recomputing it without that correction.
 *
 * Boundary edits (points, redrawn edge, shape added or removed) are saved as a **geometric difference** (added area,
 * removed area) and not as point indices: each correction stays independent of the others and still applies if the
 * zone is recorded again on the Mammotion side.
 */

/** Simple shape laid on the map: square (rectangle), round (ellipse) or triangle, rotated around its center. */
enum class PrimitiveKind(private val en: String, private val fr: String) {
    SQUARE("Square", "Carré"), CIRCLE("Round", "Rond"), TRIANGLE("Triangle", "Triangle");
    val label: String get() = tr(en, fr)
}

data class PrimitiveShape(
    val kind: PrimitiveKind,
    val center: Point,
    val widthM: Double,
    val heightM: Double,
    val rotationDeg: Double = 0.0,
) {
    init {
        require(center.x.isFinite() && center.y.isFinite() && rotationDeg.isFinite()) { tr("Non-finite shape", "Forme non finie") }
        require(widthM in MIN_SIZE_M..MAX_SIZE_M && heightM in MIN_SIZE_M..MAX_SIZE_M) { tr("Shape size out of range", "Taille de forme hors limites") }
    }

    /** Point of the shape frame (before rotation) brought onto the map. */
    fun toMap(local: Point): Point {
        val a = Math.toRadians(rotationDeg)
        return Point(center.x + local.x * cos(a) - local.y * sin(a), center.y + local.x * sin(a) + local.y * cos(a))
    }

    /** Map point brought into the shape frame. */
    fun toLocal(p: Point): Point {
        val a = Math.toRadians(-rotationDeg)
        val dx = p.x - center.x
        val dy = p.y - center.y
        return Point(dx * cos(a) - dy * sin(a), dx * sin(a) + dy * cos(a))
    }

    fun polygon(): List<Point> {
        val w = widthM / 2
        val h = heightM / 2
        val local = when (kind) {
            PrimitiveKind.SQUARE -> listOf(Point(-w, -h), Point(w, -h), Point(w, h), Point(-w, h))
            PrimitiveKind.CIRCLE -> (0 until CIRCLE_SEGMENTS).map { i ->
                val t = 2 * Math.PI * i / CIRCLE_SEGMENTS
                Point(w * cos(t), h * sin(t))
            }
            PrimitiveKind.TRIANGLE -> listOf(Point(-w, -h), Point(w, -h), Point(0.0, h))
        }
        return local.map(::toMap)
    }

    /** Resize corner (top right in the shape frame). */
    fun corner(): Point = toMap(Point(widthM / 2, heightM / 2))

    /** Readable dimensions: "2,0 × 1,5 m", "⌀ 1,2 m" for a regular circle. */
    fun sizeText(): String =
        if (kind == PrimitiveKind.CIRCLE && abs(widthM - heightM) < 1e-6) "⌀ ${MapCorrections.meters(widthM)} m"
        else "${MapCorrections.meters(widthM)} × ${MapCorrections.meters(heightM)} m"

    companion object {
        const val MIN_SIZE_M = 0.1
        const val MAX_SIZE_M = 100.0
        const val CIRCLE_SEGMENTS = 32
    }
}

/** Tool that produced a boundary edit (label and re-editing). */
enum class CorrectionTool(private val en: String, private val fr: String) {
    POINTS("Points", "Points"), EDGE("Edge", "Bord"), SHAPE("Shape", "Forme");
    val label: String get() = tr(en, fr)
}

/** Origin of an added no-go zone. */
enum class NoGoOrigin { TRACE, SHAPE, OBSTACLE }

/** A local correction: stable identifier, date, activation and label shown in the list. */
sealed interface MapCorrection {
    val id: Long
    val createdAtMs: Long
    val enabled: Boolean
    val label: String
    fun withEnabled(on: Boolean): MapCorrection
}

/**
 * Edit of the boundary of a zone or a no-go zone ([target]): area [add] added then area [cut] removed, in WKT (meters,
 * to the millimeter). [primitive] keeps the laid shape (Shape tool) to display and reopen it; [removing] tells that it
 * was removed rather than added.
 */
data class ShapePatch(
    override val id: Long,
    override val createdAtMs: Long,
    override val label: String,
    val target: Long,
    val baseFingerprint: Long?,
    val add: String,
    val cut: String,
    val tool: CorrectionTool,
    val primitive: PrimitiveShape? = null,
    val removing: Boolean = false,
    override val enabled: Boolean = true,
) : MapCorrection {
    override fun withEnabled(on: Boolean) = copy(enabled = on)
}

/** Offset of the whole boundary of [target]: positive outwards, negative inwards. */
data class ShapeOffset(
    override val id: Long,
    override val createdAtMs: Long,
    override val label: String,
    val target: Long,
    val baseFingerprint: Long?,
    val distanceM: Double,
    override val enabled: Boolean = true,
) : MapCorrection {
    init { require(distanceM.isFinite() && abs(distanceM) <= MapCorrections.MAX_OFFSET_M) { tr("Offset out of range", "Décalage hors limites") } }
    override fun withEnabled(on: Boolean) = copy(enabled = on)
}

/** New no-go zone (drawn, laid shape or round obstacle). Its shape identifier is [shapeId]. */
data class AddedNoGo(
    override val id: Long,
    override val createdAtMs: Long,
    override val label: String,
    val points: List<Point>,
    val origin: NoGoOrigin,
    val primitive: PrimitiveShape? = null,
    override val enabled: Boolean = true,
) : MapCorrection {
    val shapeId: Long get() = MapCorrections.shapeId(id)
    override fun withEnabled(on: Boolean) = copy(enabled = on)
}

/** Effect of a correction on the current map. */
enum class CorrectionStatus(private val en: String, private val fr: String, val applied: Boolean) {
    APPLIED("Applied", "Appliquée", true),
    CHANGED("Mammotion boundary changed since: check it", "Contour Mammotion modifié depuis : vérifiez", true),
    MISSING("Shape not found on this map", "Forme introuvable sur cette carte", false),
    REFUSED("Invalid result: correction ignored", "Résultat invalide : correction ignorée", false),
    DISABLED("Disabled", "Désactivée", false);
    val label: String get() = tr(en, fr)
}

/**
 * Corrected map: [map] replaces the Mammotion map throughout the app; [status] per correction;
 * [edited] = touched shapes (identifier → corrections), to mark them on the map.
 */
data class CorrectedMap(
    val map: RobotMap,
    val status: Map<Long, CorrectionStatus> = emptyMap(),
    val reasons: Map<Long, String> = emptyMap(),
    val edited: Map<Long, List<Long>> = emptyMap(),
)

/** Result of a boundary operation: normalized points, or the reason for the refusal. */
sealed interface ShapeResult {
    data class Ok(val points: List<Point>) : ShapeResult
    data class Refused(val reason: String) : ShapeResult
}

object MapCorrections {
    /** Added shapes get a stable negative identifier, far from -1/-2 (MapScreen framing). */
    private const val ADDED_ID_BASE = 1_000_000L
    const val MAX_OFFSET_M = 5.0
    /** Minimum area of a no-go zone (shrinking it to nothing would in effect remove it) and of a zone. */
    const val MIN_NO_GO_AREA_M2 = 0.05
    const val MIN_ZONE_AREA_M2 = 0.5
    /** Ignored slivers: below this, a difference comes from the computation and not from an edit. */
    private const val SLIVER_M2 = 1e-4
    private val precision = PrecisionModel(1000.0)
    private val factory = GeometryFactory()

    fun shapeId(correctionId: Long): Long = -(ADDED_ID_BASE + correctionId)
    fun isAdded(shapeId: Long): Boolean = shapeId <= -ADDED_ID_BASE
    fun nextId(corrections: List<MapCorrection>): Long = (corrections.maxOfOrNull { it.id } ?: 0L) + 1

    /** Fingerprint of the original Mammotion boundary, to flag a zone recorded again since the correction. */
    fun fingerprint(shape: MapShape): Long = BorderValidation.fingerprint(shape)

    fun apply(raw: RobotMap, corrections: List<MapCorrection>): CorrectedMap {
        if (corrections.isEmpty()) return CorrectedMap(raw)
        if (!raw.complete) return CorrectedMap(raw, corrections.associate { it.id to CorrectionStatus.MISSING })
        val shapes = LinkedHashMap<Long, MapShape>()
        raw.shapes.forEach { shapes[it.id] = it }
        val original = raw.shapes.associateBy { it.id }
        val status = LinkedHashMap<Long, CorrectionStatus>()
        val reasons = LinkedHashMap<Long, String>()
        val edited = LinkedHashMap<Long, MutableList<Long>>()
        for (c in corrections) {
            if (!c.enabled) { status[c.id] = CorrectionStatus.DISABLED; continue }
            when (c) {
                is AddedNoGo -> {
                    if (shapes.containsKey(c.shapeId)) { status[c.id] = CorrectionStatus.REFUSED; reasons[c.id] = tr("Identifier already used", "Identifiant déjà utilisé"); continue }
                    when (val r = normalize({ polygon(c.points) }, ShapeKind.NO_GO, null)) {
                        is ShapeResult.Ok -> {
                            shapes[c.shapeId] = MapShape(c.shapeId, 1, r.points, c.label)
                            status[c.id] = CorrectionStatus.APPLIED
                            edited.getOrPut(c.shapeId) { mutableListOf() }.add(c.id)
                        }
                        is ShapeResult.Refused -> { status[c.id] = CorrectionStatus.REFUSED; reasons[c.id] = r.reason }
                    }
                }
                is ShapePatch, is ShapeOffset -> {
                    val targetId = target(c)!!
                    val fingerprint = if (c is ShapePatch) c.baseFingerprint else (c as ShapeOffset).baseFingerprint
                    val target = shapes[targetId]
                    if (target == null || target.kind !in listOf(ShapeKind.ZONE, ShapeKind.NO_GO)) {
                        status[c.id] = CorrectionStatus.MISSING; continue
                    }
                    val result = when (c) {
                        is ShapePatch -> patched(target, c.add, c.cut)
                        is ShapeOffset -> offset(target, c.distanceM)
                        else -> error("unreachable")
                    }
                    when (result) {
                        is ShapeResult.Ok -> {
                            shapes[targetId] = target.copy(points = result.points)
                            val base = original[targetId]
                            status[c.id] = if (fingerprint != null && base != null && fingerprint(base) != fingerprint)
                                CorrectionStatus.CHANGED else CorrectionStatus.APPLIED
                            edited.getOrPut(targetId) { mutableListOf() }.add(c.id)
                        }
                        is ShapeResult.Refused -> { status[c.id] = CorrectionStatus.REFUSED; reasons[c.id] = result.reason }
                    }
                }
            }
        }
        val changed = status.values.any { it.applied }
        val map = if (!changed) raw else raw.copy(shapes = shapes.values.toList(), mammotionShapes = raw.originalShapes)
        return CorrectedMap(map, status, reasons, edited)
    }

    /** Shape targeted by an edit or an offset; null for an added no-go zone. */
    fun target(c: MapCorrection): Long? = when (c) {
        is ShapePatch -> c.target
        is ShapeOffset -> c.target
        is AddedNoGo -> null
    }

    /** List without [id], nor the edits of the no-go zone it added (they would have no target any more). */
    fun remove(corrections: List<MapCorrection>, id: Long): List<MapCorrection> {
        val removed = corrections.firstOrNull { it.id == id } ?: return corrections
        val orphan = (removed as? AddedNoGo)?.shapeId
        return corrections.filter { it.id != id && (orphan == null || target(it) != orphan) }
    }

    /** Corrections that would disappear with [id] (including it). */
    fun dependents(corrections: List<MapCorrection>, id: Long): Int = corrections.size - remove(corrections, id).size

    /** Boundary [shape] grown by [add] then reduced by [cut] (WKT). */
    fun patched(shape: MapShape, add: String, cut: String): ShapeResult = normalize({
        var g: JtsGeometry = polygon(shape.points)
        read(add)?.let { g = OverlayNGRobust.overlay(g, it, OverlayNG.UNION) }
        read(cut)?.let { g = OverlayNGRobust.overlay(g, it, OverlayNG.DIFFERENCE) }
        g
    }, shape.kind, shape.points)

    /** Boundary [shape] offset by [distanceM] (corners kept, sharp angles bounded). */
    fun offset(shape: MapShape, distanceM: Double): ShapeResult = normalize({
        val params = BufferParameters(8, BufferParameters.CAP_FLAT, BufferParameters.JOIN_MITRE, 3.0)
        BufferOp.bufferOp(polygon(shape.points), distanceM, params)
    }, shape.kind, shape.points)

    /** Boundary of a new shape or a draft: readable refusal if self-crossing, too small or oversized. */
    fun check(points: List<Point>, kind: ShapeKind, reference: List<Point>? = null): ShapeResult =
        normalize({ polygon(points) }, kind, reference)

    /**
     * Difference between two boundaries: added area and removed area (WKT), computation slivers removed.
     * Applied to the boundary [before], it gives back [after].
     */
    fun diff(before: List<Point>, after: List<Point>): Pair<String, String> {
        val b = polygon(before)
        val a = polygon(after)
        return write(clean(OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE))) to
            write(clean(OverlayNGRobust.overlay(b, a, OverlayNG.DIFFERENCE)))
    }

    /** Area (m²) of a correction WKT geometry; 0 if empty. */
    fun area(wkt: String): Double = read(wkt)?.area ?: 0.0

    fun polygonArea(points: List<Point>): Double = abs(signedArea(points))

    /** Area of [points] outside the original boundary [original] (enlargement under the user's responsibility). */
    fun outside(points: List<Point>, original: List<Point>): Double = runCatching {
        OverlayNGRobust.overlay(polygon(points), polygon(original), OverlayNG.DIFFERENCE).area
    }.getOrDefault(0.0)

    /** Outer rings (and holes) of a WKT geometry, for drawing the hatching. */
    fun rings(wkt: String): List<List<Point>> {
        val g = read(wkt) ?: return emptyList()
        return (0 until g.numGeometries).flatMap { i ->
            val p = g.getGeometryN(i) as? Polygon ?: return@flatMap emptyList()
            listOf(p.exteriorRing) + (0 until p.numInteriorRing).map { p.getInteriorRingN(it) }
        }.map { ring -> ring.coordinates.dropLast(1).map { Point(it.x, it.y) } }
    }

    fun primitiveWkt(shape: PrimitiveShape): String = write(GeometryPrecisionReducer.reduce(polygon(shape.polygon()), precision))

    /** Displayed name of a shape: its Mammotion name, otherwise "Zone" / "No-go". */
    fun shapeName(shape: MapShape): String = shape.name.trim().ifEmpty { if (shape.kind == ShapeKind.NO_GO) "No-go" else "Zone" }

    fun meters(value: Double): String = String.format(fr.luba.poc.i18n.I18n.locale, "%.1f", value)
    fun squareMeters(value: Double): String = String.format(fr.luba.poc.i18n.I18n.locale, "%.1f m²", value)
    fun centimeters(value: Double): String = String.format(fr.luba.poc.i18n.I18n.locale, "%+d cm", Math.round(value * 100))

    /** Summary of an edit: "+4,2 m² / −0,8 m²". */
    fun balance(add: String, cut: String): String {
        val plus = area(add)
        val minus = area(cut)
        return listOfNotNull(
            plus.takeIf { it >= 0.05 }?.let { "+" + squareMeters(it) },
            minus.takeIf { it >= 0.05 }?.let { "−" + squareMeters(it) },
        ).joinToString(" / ").ifEmpty { "< 0,1 m²" }
    }

    // ---------------------------------------------------------------- internal

    private fun polygon(points: List<Point>): Polygon {
        require(points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() }) { tr("Incomplete boundary", "Contour incomplet") }
        val coordinates = points.map { Coordinate(it.x, it.y) }.toMutableList()
        if (!coordinates.first().equals2D(coordinates.last())) coordinates.add(Coordinate(coordinates.first()))
        require(coordinates.size >= 4) { tr("Incomplete boundary", "Contour incomplet") }
        return factory.createPolygon(coordinates.toTypedArray())
    }

    private fun read(wkt: String): JtsGeometry? =
        if (wkt.isBlank()) null else WKTReader(factory).read(wkt).takeUnless { it.isEmpty }

    private fun write(g: JtsGeometry): String = if (g.isEmpty) "" else WKTWriter().write(g)

    private fun clean(g: JtsGeometry): JtsGeometry {
        val reduced = GeometryPrecisionReducer.reduce(g, precision)
        val parts = (0 until reduced.numGeometries).map { reduced.getGeometryN(it) }
            .filter { it is Polygon && it.area >= SLIVER_M2 }
        return if (parts.isEmpty()) factory.createPolygon() else factory.buildGeometry(parts).union()
    }

    private fun signedArea(points: List<Point>): Double {
        var s = 0.0
        for (i in points.indices) { val a = points[i]; val b = points[(i + 1) % points.size]; s += a.x * b.y - b.x * a.y }
        return s / 2
    }

    /**
     * Brings a computation result back to a map boundary: a single polygon, without holes, valid, neither tiny nor
     * oversized; same winding and same starting point as [reference] when it is known.
     */
    private fun normalize(build: () -> JtsGeometry, kind: ShapeKind, reference: List<Point>?): ShapeResult {
        val raw = try { build() } catch (e: Exception) { return ShapeResult.Refused(tr("Self-crossing or incomplete boundary", "Contour croisé ou incomplet")) }
        if (raw.isEmpty) return ShapeResult.Refused(tr("The shape would disappear", "La forme disparaîtrait"))
        if (!raw.isValid) return ShapeResult.Refused(tr("Self-crossing boundary", "Contour croisé"))
        val reduced = try { GeometryPrecisionReducer.reduce(raw, precision) } catch (_: Exception) {
            return ShapeResult.Refused(tr("Self-crossing boundary", "Contour croisé"))
        }
        val parts = (0 until reduced.numGeometries).map { reduced.getGeometryN(it) }
            .filterIsInstance<Polygon>().filter { it.area >= 0.01 }
        if (parts.isEmpty()) return ShapeResult.Refused(tr("The shape would disappear", "La forme disparaîtrait"))
        if (parts.size > 1) return ShapeResult.Refused(tr("The shape would be split into several pieces", "La forme serait coupée en plusieurs morceaux"))
        val part = parts.single()
        val holes = (0 until part.numInteriorRing).map { part.getInteriorRingN(it) }
            .filter { factory.createPolygon(it.coordinates).area >= 0.01 }
        if (holes.isNotEmpty()) return ShapeResult.Refused(tr("The correction would make a hole: create a no-go zone instead", "La correction creuserait un trou : créez plutôt une no-go"))
        val shell = factory.createPolygon(part.exteriorRing.coordinates)
        val simple = DouglasPeuckerSimplifier.simplify(shell, 0.003) as? Polygon ?: shell
        if (!simple.isValid) return ShapeResult.Refused(tr("Self-crossing boundary", "Contour croisé"))
        val min = if (kind == ShapeKind.NO_GO) MIN_NO_GO_AREA_M2 else MIN_ZONE_AREA_M2
        if (simple.area < min) return ShapeResult.Refused(tr("Shape too small (less than ${squareMeters(min)})", "Forme trop petite (moins de ${squareMeters(min)})"))
        val env = simple.envelopeInternal
        if (env.width >= 500 || env.height >= 500) return ShapeResult.Refused(tr("Shape oversized (more than 500 m)", "Forme démesurée (plus de 500 m)"))
        var points = simple.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
        if (points.size < 3) return ShapeResult.Refused(tr("Incomplete boundary", "Contour incomplet"))
        if (reference != null && reference.size >= 3) {
            if (signedArea(points) * signedArea(reference) < 0) points = points.reversed()
            val start = reference.first()
            val i = points.indices.minBy { points[it].distance(start) }
            points = points.drop(i) + points.take(i)
        }
        return ShapeResult.Ok(points)
    }
}
