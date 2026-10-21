package fr.luba.poc.mapsync

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.CorrectedMap
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/*
 * Sync of the local corrections with the Mammotion map stored in the robot.
 *
 * The robot only stores what it records itself while it is driven (APK "start drawing" → drive → "end drawing"): there
 * is no command to upload a boundary. Each difference between the corrected map and the Mammotion map therefore
 * becomes an outline that the robot drives on its own, blades stopped, while it records:
 *  - a DumbyLuba zone, passage or no-go zone missing from the Mammotion map is **created**;
 *  - a Mammotion zone or no-go zone changed by local corrections is **replaced**: the corrected outline is recorded as
 *    a new element first, the old one is deleted only once the new one is found in the robot map (never the other way
 *    round: a failed recording must not lose the zone).
 * The corrections carried over to the robot are then removed from the phone: the robot map holds them.
 */

/** Kind of map element recorded in the robot: upstream [type] and robot state [drawState] while it records. */
enum class SyncKind(val type: Int, val drawState: Int, private val en: String, private val fr: String) {
    ZONE(0, 31, "Zone", "Zone"), NO_GO(1, 32, "No-go zone", "Zone interdite"), PASSAGE(2, 34, "Passage", "Passage");
    val label: String get() = tr(en, fr)
    val closed: Boolean get() = this != PASSAGE

    companion object {
        fun of(shape: MapShape): SyncKind? = when (shape.kind) {
            ShapeKind.ZONE -> ZONE
            ShapeKind.NO_GO -> NO_GO
            ShapeKind.PASSAGE -> PASSAGE
            else -> null
        }
    }
}

enum class SyncAction(private val en: String, private val fr: String) {
    CREATE("Create", "Créer"), REPLACE("Replace", "Remplacer");
    val label: String get() = tr(en, fr)
}

/**
 * One difference between the corrected map and the Mammotion map. [outline]: shape wanted in the robot (outer side of
 * the chassis for a zone, as the robot records it; axis for a passage). [previous]: Mammotion element replaced (deleted
 * once the new one is recorded). [correctionIds]: corrections removed from the phone once the robot holds the result.
 * [trace]: path of the robot centre while it records, empty when [refusal] says why it cannot be driven.
 */
data class SyncItem(
    val key: String,
    val action: SyncAction,
    val kind: SyncKind,
    val title: String,
    val detail: String,
    val outline: List<Point>,
    val previous: MapShape?,
    val correctionIds: List<Long>,
    val trace: List<Point>,
    val refusal: String? = null,
    val warnings: List<String> = emptyList(),
    /** Element the robot removed together with a replaced zone, recorded again from its former outline. */
    val recreated: Boolean = false,
) {
    val possible: Boolean get() = refusal == null && trace.size >= 2
    val traceM: Double get() = trace.zipWithNext().sumOf { (a, b) -> a.distance(b) }
}

object MapSyncPlanner {
    /** Extra gap between the chassis and the wanted outline: the robot centre keeps the safe surface of the moves. The
     * Mammotion zone ends up this much smaller (no-go zone: larger) than the corrected outline, never the other way. */
    const val TRACE_MARGIN_M = 0.15
    /** Minimum distance between the robot centre and the station while it drives an outline, when the base safety zone
     * is not known (otherwise the safety zone of the moves plus [STATION_MARGIN_M]). */
    const val STATION_CLEARANCE_M = 0.7
    const val STATION_MARGIN_M = 0.15
    /** Forward speed ceiling while an outline is recorded (≈ 0.3 m/s): closer tracking, evenly spaced points. */
    const val TRACE_LINEAR = 180
    /** Change of a Mammotion outline below this area: computation noise, nothing to send. */
    const val MIN_CHANGE_M2 = 0.05
    /** Longest drive of one outline (the supervised drive is bounded to 600 s). */
    const val MAX_DRIVE_S = 540.0
    /** Share of the wanted outline that the recorded element must overlap to be recognised. */
    const val MIN_MATCH = 0.5
    /** Passage: mean distance of the recorded axis to the wanted one. */
    const val PASSAGE_MATCH_M = 1.0
    /**
     * Simplification of the outline: tight rounded corners become a few chords whose vertices turn more than the pivot
     * threshold of the tracking ([fr.luba.poc.simulation.PathFollower.CORNER_DEGREES]): the robot pivots there instead
     * of trying to follow a curve tighter than it can turn while driving (end-to-end bench: 0.4 m arcs cut by 13 cm).
     * The chords cut at most this much towards the boundary: the centre stays beyond the safe surface of the moves.
     */
    private const val SIMPLIFY_M = 0.06
    private val factory = GeometryFactory()

    /**
     * Differences between the Mammotion map [raw] and the corrected map [corrected] built from [corrections], in the
     * order they must be recorded: zones first (a passage or a no-go zone needs them), then passages, then no-go zones.
     * [station]: charging station kept at [STATION_CLEARANCE_M]; [check]: drivability of a trace on the corrected map
     * (null when it can be driven, otherwise the reason), [fr.luba.poc.simulation.PointPlanner.tracePlan] in the app.
     */
    fun differences(raw: RobotMap, corrected: CorrectedMap, corrections: List<MapCorrection>, robot: RobotEnvelope = RobotEnvelope.LUBA2,
                    station: Point? = null, check: (List<Point>) -> String? = { null }, stationClearanceM: Double = STATION_CLEARANCE_M): List<SyncItem> {
        if (!raw.complete || raw.source.contains("DÉMONSTRATION", true)) return emptyList()
        val applied = corrections.filter { it.enabled && corrected.status[it.id]?.applied == true }
        val byShape = LinkedHashMap<Long, MutableList<Long>>()
        applied.forEach { c -> MapCorrections.shapeOf(c)?.let { byShape.getOrPut(it) { mutableListOf() }.add(c.id) } }
        val current = corrected.map.shapes.associateBy { it.id }
        val original = raw.shapes.associateBy { it.id }
        val items = byShape.mapNotNull { (id, ids) ->
            val shape = current[id] ?: return@mapNotNull null
            val kind = SyncKind.of(shape) ?: return@mapNotNull null
            if (MapCorrections.isAdded(id)) item("create:$id", SyncAction.CREATE, kind, shape, null, ids, robot, station, check, stationClearanceM)
            else {
                val before = original[id] ?: return@mapNotNull null
                if (kind == SyncKind.PASSAGE || changedArea(before.points, shape.points) < MIN_CHANGE_M2) null
                else item("replace:$id", SyncAction.REPLACE, kind, shape, before, ids, robot, station, check, stationClearanceM)
            }
        }
        return ordered(items)
    }

    /** Zones, then passages, then no-go zones; stable inside each kind. */
    fun ordered(items: List<SyncItem>): List<SyncItem> = items.sortedBy { listOf(SyncKind.ZONE, SyncKind.PASSAGE, SyncKind.NO_GO).indexOf(it.kind) }

    /** Element removed by the robot with a replaced zone ([lost]): recorded again from its former outline. */
    fun recreate(shape: MapShape, robot: RobotEnvelope = RobotEnvelope.LUBA2, station: Point? = null,
                 check: (List<Point>) -> String? = { null }, stationClearanceM: Double = STATION_CLEARANCE_M): SyncItem? {
        val kind = SyncKind.of(shape) ?: return null
        val item = item("recreate:${shape.id}", SyncAction.CREATE, kind, shape, null, emptyList(), robot, station, check, stationClearanceM)
        return item.copy(recreated = true, title = tr("${item.title} (removed with its zone)", "${item.title} (supprimée avec sa zone)"))
    }

    private fun item(key: String, action: SyncAction, kind: SyncKind, shape: MapShape, previous: MapShape?, ids: List<Long>,
                     robot: RobotEnvelope, station: Point?, check: (List<Point>) -> String?, clearanceM: Double): SyncItem {
        val name = MapCorrections.shapeName(shape)
        val title = when (kind) {
            SyncKind.ZONE -> if (shape.name.isBlank()) kind.label else "${kind.label} · $name"
            SyncKind.NO_GO -> if (shape.name.isBlank()) kind.label else "${kind.label} · $name"
            SyncKind.PASSAGE -> kind.label
        }
        val size = if (kind.closed) MapCorrections.squareMeters(MapCorrections.polygonArea(shape.points)) else "${MapCorrections.meters(length(shape.points))} m"
        val detail = when (action) {
            SyncAction.CREATE -> tr("Missing from the Mammotion map · $size", "Absente de la carte Mammotion · $size")
            SyncAction.REPLACE -> {
                val (added, removed) = balance(previous!!.points, shape.points)
                tr("Corrected on the phone · +${MapCorrections.squareMeters(added)} / −${MapCorrections.squareMeters(removed)} · now $size",
                    "Corrigée sur le téléphone · +${MapCorrections.squareMeters(added)} / −${MapCorrections.squareMeters(removed)} · désormais $size")
            }
        }
        val warnings = buildList {
            if (action == SyncAction.REPLACE && kind == SyncKind.ZONE) add(tr("Recorded again in full: the robot gives it a new identifier, Mammotion schedules of this zone must be set again",
                "Réenregistrée en entier : le robot lui donne un nouvel identifiant, les plannings Mammotion de cette zone sont à refaire"))
            if (kind == SyncKind.ZONE) add(tr("Ends ${(TRACE_MARGIN_M * 100).toInt()} cm inside the corrected outline (safety margin of the moves), corners rounded like the border laps",
                "Finit ${(TRACE_MARGIN_M * 100).toInt()} cm en retrait du contour corrigé (marge de sécurité des déplacements), angles arrondis comme les tours de bordure"))
        }
        val trace = try { trace(kind, shape.points, robot) } catch (e: IllegalArgumentException) {
            return SyncItem(key, action, kind, title, detail, shape.points, previous, ids, emptyList(), e.message, warnings)
        }
        val refusal = station?.takeIf { s -> distanceTo(trace, s) < clearanceM }?.let {
            tr("The outline passes less than ${MapCorrections.meters(clearanceM)} m from the charging station: record it with the Mammotion app",
                "Le contour passe à moins de ${MapCorrections.meters(clearanceM)} m de la station de charge : enregistrez-le avec l'app Mammotion")
        } ?: check(trace)?.let { tr("The robot cannot drive this outline: $it", "Le robot ne peut pas parcourir ce contour : $it") }
        return SyncItem(key, action, kind, title, detail, shape.points, previous, ids, if (refusal == null) trace else emptyList(), refusal, warnings)
    }

    /**
     * Path of the robot centre that makes the robot record [outline]: a zone boundary is the outer side of the chassis
     * (centre half a width plus [TRACE_MARGIN_M] inside), a no-go zone is driven around (centre as much outside), a
     * passage along its axis. Closed outlines end on their first point. Throws when the robot cannot follow it.
     */
    fun trace(kind: SyncKind, outline: List<Point>, robot: RobotEnvelope = RobotEnvelope.LUBA2): List<Point> {
        if (kind == SyncKind.PASSAGE) {
            val axis = outline.fold(mutableListOf<Point>()) { acc, p -> if (acc.isEmpty() || acc.last().distance(p) > 0.01) acc.add(p); acc }
            require(axis.size >= 2 && length(axis) >= 1.0) { tr("Passage too short", "Passage trop court") }
            return axis
        }
        require(outline.size >= 3) { tr("Outline incomplete", "Contour incomplet") }
        val offset = robot.halfWidthM + TRACE_MARGIN_M
        val area = valid(polygon(outline))
        val params = BufferParameters(8, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 3.0)
        // Corners rounded to the chassis pivot radius, like the mowing border loops ([RecordedBoundary.ringRoundingM]):
        // the robot turns while it drives instead of pivoting in a corner, where its envelope would leave the safe surface.
        val r = fr.luba.poc.map.RecordedBoundary.ringRoundingM(robot)
        val moved = if (kind == SyncKind.ZONE)
            BufferOp.bufferOp(BufferOp.bufferOp(area, -(offset + r), params), r, params)
            else BufferOp.bufferOp(BufferOp.bufferOp(BufferOp.bufferOp(area, offset, params), r, params), -r, params)
        val parts = (0 until moved.numGeometries).map { moved.getGeometryN(it) }.filterIsInstance<Polygon>().filter { it.area > 0.01 }
        val main = parts.maxByOrNull { it.area }
        require(main != null && main.exteriorRing.length >= 2.0) {
            tr("Too narrow for the robot to drive inside its outline", "Trop étroite pour que le robot en parcoure le contour")
        }
        require(kind != SyncKind.ZONE || parts.size == 1) {
            tr("A narrow part splits the outline driven by the robot into ${parts.size} pieces: widen it or record it with the Mammotion app",
                "Un passage étroit coupe le contour parcouru par le robot en ${parts.size} morceaux : élargissez-le ou enregistrez-la avec l'app Mammotion")
        }
        val ring = DouglasPeuckerSimplifier.simplify(main.exteriorRing, SIMPLIFY_M).coordinates.map { Point(it.x, it.y) }
        val open = ring.dropLast(1).fold(mutableListOf<Point>()) { acc, p -> if (acc.isEmpty() || acc.last().distance(p) > 0.01) acc.add(p); acc }
        require(open.size >= 3) { tr("Outline incomplete", "Contour incomplet") }
        return open + open.first()
    }

    /** Distances tried, from the start of a closed outline, for the point the robot drives to before recording. */
    val APPROACH_M = listOf(0.4, 0.25)

    /**
     * Points the robot drives to before recording [trace] (first that a move accepts): slightly inside a zone, outside a
     * no-go zone, away from [outline] along the line from it to the start of the trace; then the start itself.
     */
    fun approaches(trace: List<Point>, kind: SyncKind, outline: List<Point>): List<Point> {
        val start = trace.firstOrNull() ?: return emptyList()
        if (!kind.closed || outline.size < 3) return listOf(start)
        val ring = factory.createLinearRing((if (outline.first() == outline.last()) outline else outline + outline.first())
            .map { Coordinate(it.x, it.y) }.toTypedArray())
        val nearest = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(ring, factory.createPoint(Coordinate(start.x, start.y)))[0]
        val dx = start.x - nearest.x; val dy = start.y - nearest.y
        val d = kotlin.math.hypot(dx, dy)
        if (d < 1e-6) return listOf(start)
        return APPROACH_M.map { m -> Point(start.x + dx / d * m, start.y + dy / d * m) } + start
    }

    /** Spacing of the candidate join points along a closed outline. */
    const val JOIN_STEP_M = 0.25
    /** Candidate join points tried, nearest first. */
    const val MAX_JOINS = 60
    /** Robot this close to the start of the outline: no join needed. */
    const val JOINED_M = 0.05

    /**
     * Ways to join [trace] from [from] before recording it: the same outline started at one of its points (every
     * [JOIN_STEP_M], nearest first), so that the robot reaches it in a straight line at a shallow angle instead of facing
     * the boundary. A passage is only joined at its start.
     */
    fun joins(trace: List<Point>, closed: Boolean, from: Point): List<List<Point>> {
        if (!closed || trace.size < 3) return listOf(trace)
        val loop = densify(if (trace.first() == trace.last()) trace.dropLast(1) else trace, JOIN_STEP_M)
        return loop.indices.sortedBy { loop[it].distance(from) }.take(MAX_JOINS).map { i ->
            val turned = loop.drop(i) + loop.take(i)
            turned + turned.first()
        }
    }

    /** [loop] (open ring) with intermediate points so that no edge is longer than [stepM]. */
    private fun densify(loop: List<Point>, stepM: Double): List<Point> = (loop + loop.first()).zipWithNext().flatMap { (a, b) ->
        val n = kotlin.math.max(1, kotlin.math.ceil(a.distance(b) / stepM).toInt())
        (0 until n).map { k -> Point(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n) }
    }

    /**
     * Same [trace], started where the robot is: a closed loop begins at its vertex nearest to [from] (and still ends on
     * it), a passage at its end nearest to [from].
     */
    fun startNear(trace: List<Point>, closed: Boolean, from: Point?): List<Point> {
        if (from == null || trace.size < 2) return trace
        if (!closed) return if (trace.last().distance(from) < trace.first().distance(from)) trace.reversed() else trace
        val loop = if (trace.first() == trace.last()) trace.dropLast(1) else trace
        val i = loop.indices.minByOrNull { loop[it].distance(from) } ?: 0
        val turned = loop.drop(i) + loop.take(i)
        return turned + turned.first()
    }

    /**
     * Element recorded by the robot for [kind] and [outline]: a new identifier of [after] (absent from [before]) of the
     * same type, overlapping the wanted outline by at least [MIN_MATCH] (passage: axis within [PASSAGE_MATCH_M]).
     */
    fun created(before: RobotMap, after: RobotMap, kind: SyncKind, outline: List<Point>): MapShape? {
        val known = before.shapes.map { it.id }.toSet()
        val fresh = after.shapes.filter { it.id !in known && it.type == kind.type && it.points.size >= 2 }
        return fresh.map { it to match(kind, outline, it.points) }.filter { it.second >= 0.0 }.maxByOrNull { it.second }?.first
    }

    /** Zones, no-go zones and passages of [before] that [after] no longer holds, except [ignored]. */
    fun lost(before: RobotMap, after: RobotMap, ignored: Set<Long>): List<MapShape> {
        val kept = after.shapes.map { it.id }.toSet()
        return before.shapes.filter { it.type in setOf(0, 1, 2) && it.id !in kept && it.id !in ignored }
    }

    /** Corrections left on the phone once [item] is held by the robot. */
    fun remaining(corrections: List<MapCorrection>, item: SyncItem): List<MapCorrection> {
        val ids = item.correctionIds.toSet()
        return corrections.filter { it.id !in ids }
    }

    /** Estimated drive of [item] at [speedMps], in seconds. */
    fun durationS(item: SyncItem, speedMps: Double): Double = if (speedMps <= 0) Double.POSITIVE_INFINITY else item.traceM / speedMps

    // ---------------------------------------------------------------- internal

    /** Match score (≥ 0) or -1 if [candidate] is not the wanted element. */
    private fun match(kind: SyncKind, wanted: List<Point>, candidate: List<Point>): Double = try {
        if (kind == SyncKind.PASSAGE) {
            val axis = line(wanted)
            val mean = candidate.map { factory.createPoint(Coordinate(it.x, it.y)).distance(axis) }.average()
            if (mean <= PASSAGE_MATCH_M) PASSAGE_MATCH_M - mean else -1.0
        } else {
            val a = valid(polygon(wanted)); val b = valid(polygon(candidate))
            val inter = OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION).area
            val union = OverlayNGRobust.overlay(a, b, OverlayNG.UNION).area
            val score = if (union <= 0) 0.0 else inter / union
            if (score >= MIN_MATCH) score else -1.0
        }
    } catch (_: RuntimeException) { -1.0 }

    /** Area added and area removed from [before] to [after]. */
    fun balance(before: List<Point>, after: List<Point>): Pair<Double, Double> = try {
        val a = valid(polygon(before)); val b = valid(polygon(after))
        OverlayNGRobust.overlay(b, a, OverlayNG.DIFFERENCE).area to OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE).area
    } catch (_: RuntimeException) { 0.0 to 0.0 }

    private fun changedArea(before: List<Point>, after: List<Point>): Double =
        if (before.size < 3 || after.size < 3) Double.MAX_VALUE else balance(before, after).let { it.first + it.second }

    private fun distanceTo(path: List<Point>, p: Point): Double = line(path).distance(factory.createPoint(Coordinate(p.x, p.y)))

    private fun length(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    private fun valid(g: JtsGeometry): JtsGeometry = if (g.isValid) g else g.buffer(0.0)

    private fun polygon(points: List<Point>): Polygon {
        val ring = if (points.first() == points.last()) points else points + points.first()
        return factory.createPolygon(ring.map { Coordinate(it.x, it.y) }.toTypedArray())
    }

    private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
}
