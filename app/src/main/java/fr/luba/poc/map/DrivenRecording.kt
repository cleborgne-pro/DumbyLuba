package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTWriter
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/** What the robot records while it is driven: a zone, a passage or a no-go zone. */
enum class DrivenKind(private val en: String, private val fr: String) {
    ZONE("Zone", "Zone"), PASSAGE("Passage", "Passage"), NO_GO("No-go", "No-go");
    val label: String get() = tr(en, fr)
    /** Closed outline (zone, no-go) rather than an open axis (passage). */
    val closed: Boolean get() = this != PASSAGE
}

/**
 * Trace being recorded: successive RTK FIX positions of the robot **centre**, at least [DrivenRecording.STEP_M] apart.
 * [paused]: positions ignored (the robot is moved without recording); the next point is joined in a straight line.
 * [gaps]: straight joins longer than [DrivenRecording.GAP_WARN_M] (FIX lost while moving, pause), shown as a warning.
 */
data class DrivenTrace(
    val kind: DrivenKind,
    val startedAtMs: Long,
    val points: List<Point> = emptyList(),
    val paused: Boolean = false,
    val lastFixAtMs: Long? = null,
    val withoutFix: Int = 0,
    val gaps: List<Double> = emptyList(),
) {
    val lengthM: Double get() = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }
    /** Distance between the robot (last point) and the starting point, for closing an outline. */
    val closingM: Double? get() = if (points.size < 2) null else points.last().distance(points.first())
    /** Outline ready to be closed: long enough and back near its start. */
    val closable: Boolean get() = kind.closed && points.size >= 3 && lengthM >= DrivenRecording.MIN_LOOP_M &&
        (closingM ?: Double.MAX_VALUE) <= DrivenRecording.CLOSE_M
    val finishable: Boolean get() = if (kind.closed) closable else points.size >= 2 && lengthM >= MapCorrections.MIN_PASSAGE_M
}

/** Enlargement of an existing zone by the recorded outline: outline after enlargement and area added. */
data class DrivenEnlargement(val targetId: Long, val targetName: String, val dumbyLuba: Boolean, val after: List<Point>, val addedM2: Double)

/** Result of a finished recording, before saving. */
sealed interface DrivenOutcome {
    val warnings: List<String>

    data class Refused(val reason: String, override val warnings: List<String> = emptyList()) : DrivenOutcome

    /**
     * Recorded zone: [separate] = new DumbyLuba zone (outline minus the existing zones), or the reason why it cannot be
     * one ([separateRefusal]); [enlargement] = the zone most overlapped, enlarged by the outline. [beyondM2] = area beyond
     * the Mammotion boundaries; [connected] = reachable from the other zones (otherwise record a passage).
     */
    data class Zone(
        val outline: List<Point>,
        val separate: List<Point>?,
        val separateRefusal: String?,
        val enlargement: DrivenEnlargement?,
        val separateAreaM2: Double,
        val beyondM2: Double,
        val connected: Boolean,
        override val warnings: List<String>,
    ) : DrivenOutcome

    /** Recorded passage: axis, length and number of ends inside a zone (2 = it connects two places). */
    data class Passage(val points: List<Point>, val lengthM: Double, val endsInZones: Int, override val warnings: List<String>) : DrivenOutcome

    /** Recorded no-go zone: area enclosed by the robot, minus half the chassis width. */
    data class NoGo(val points: List<Point>, val areaM2: Double, override val warnings: List<String>) : DrivenOutcome
}

/**
 * Recording of zones, passages and no-go zones by driving the robot (joystick, camera through the gateway), like the
 * Mammotion app, but kept **on the phone** as local corrections ([MapCorrections]): nothing is written to the robot.
 *
 * The robot publishes its centre. A Mammotion boundary describes the outer side of the chassis ([RecordedBoundary]):
 * the outline of a recorded zone is therefore the centre trace grown by half the chassis width (the chassis really went
 * there), and a no-go zone the trace shrunk by half the width (the chassis went around it).
 */
object DrivenRecording {
    /** Minimum spacing between two recorded points. */
    const val STEP_M = 0.15
    /** Straight join beyond which a warning is given (FIX lost while moving). */
    const val GAP_WARN_M = 1.5
    /** Maximum distance between the robot and the starting point to close an outline. */
    const val CLOSE_M = 1.0
    /** Minimum length of a closed outline. */
    const val MIN_LOOP_M = 4.0
    const val MAX_POINTS = 20_000
    /** Position older than this: not recorded. */
    const val MAX_AGE_MS = 2_000L
    /** Overlap from which the outline can enlarge an existing zone. */
    const val MIN_OVERLAP_M2 = 0.5
    private const val SIMPLIFY_M = 0.03
    private const val PASSAGE_SIMPLIFY_M = 0.05
    /** A crossing loop larger than this share of the outline makes it ambiguous. */
    private const val CROSSING_SHARE = 0.05
    private val factory = GeometryFactory()
    private val precision = PrecisionModel(1000.0)

    /** Adds a robot position; ignored if paused, without RTK FIX or too close to the previous point. */
    fun add(trace: DrivenTrace, x: Double?, y: Double?, fix: Boolean, atMs: Long, joinAfterPause: Boolean = false): DrivenTrace {
        if (trace.paused) return trace
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return trace
        if (!fix) return trace.copy(withoutFix = trace.withoutFix + 1)
        val p = Point(x, y)
        val last = trace.points.lastOrNull()
        if (last != null && p.distance(last) < STEP_M) return trace.copy(lastFixAtMs = atMs)
        if (trace.points.size >= MAX_POINTS) return trace.copy(lastFixAtMs = atMs)
        val jump = last?.distance(p) ?: 0.0
        return trace.copy(points = trace.points + p, lastFixAtMs = atMs,
            gaps = if (jump > GAP_WARN_M || (joinAfterPause && jump > STEP_M * 4)) trace.gaps + jump else trace.gaps)
    }

    /** Removes the last [meters] of the trace (the robot is driven back to retrace them). */
    fun eraseBack(trace: DrivenTrace, meters: Double = 1.0): DrivenTrace {
        if (trace.points.size < 2) return trace.copy(points = emptyList())
        var left = meters
        val kept = trace.points.toMutableList()
        while (kept.size >= 2 && left > 0) {
            left -= kept[kept.size - 1].distance(kept[kept.size - 2])
            kept.removeAt(kept.size - 1)
        }
        return trace.copy(points = kept)
    }

    /** Geometric result of [trace] on the current (corrected) map [map]. */
    fun finish(trace: DrivenTrace, map: RobotMap, robot: RobotEnvelope = RobotEnvelope.LUBA2): DrivenOutcome {
        val warnings = trace.gaps.filter { it > GAP_WARN_M }.takeIf { it.isNotEmpty() }?.let { gaps ->
            listOf(tr("${gaps.size} straight join(s) without FIX position (up to ${MapCorrections.meters(gaps.max())} m): check the outline",
                "${gaps.size} raccord(s) en ligne droite sans position FIX (jusqu'à ${MapCorrections.meters(gaps.max())} m) : vérifiez le contour"))
        } ?: emptyList()
        return try {
            when (trace.kind) {
                DrivenKind.PASSAGE -> passage(trace, map, warnings)
                DrivenKind.ZONE, DrivenKind.NO_GO -> {
                    if (!trace.closable) return DrivenOutcome.Refused(closeHint(trace), warnings)
                    val centre = centrePolygon(trace.points) ?: return DrivenOutcome.Refused(
                        tr("The trace crosses itself: record it again without crossing", "La trace se croise : enregistrez-la à nouveau sans croisement"), warnings)
                    if (trace.kind == DrivenKind.ZONE) zone(centre, map, robot, warnings) else noGo(centre, robot, warnings)
                }
            }
        } catch (e: RuntimeException) {
            DrivenOutcome.Refused(tr("Unusable trace (${e.message ?: e.javaClass.simpleName})", "Trace inutilisable (${e.message ?: e.javaClass.simpleName})"), warnings)
        }
    }

    /** What is missing to close an outline. */
    fun closeHint(trace: DrivenTrace): String = when {
        trace.points.size < 3 || trace.lengthM < MIN_LOOP_M -> tr("Drive along the whole outline (at least ${MapCorrections.meters(MIN_LOOP_M)} m)",
            "Parcourez tout le contour (au moins ${MapCorrections.meters(MIN_LOOP_M)} m)")
        else -> tr("Bring the robot back to the starting point (${MapCorrections.meters(trace.closingM ?: 0.0)} m left, ${MapCorrections.meters(CLOSE_M)} m max)",
            "Ramenez le robot au point de départ (encore ${MapCorrections.meters(trace.closingM ?: 0.0)} m, ${MapCorrections.meters(CLOSE_M)} m max)")
    }

    /**
     * Correction to save for [outcome]: a new DumbyLuba zone ([enlarge] false) or the enlargement of the zone it
     * overlaps, a DumbyLuba passage, or a no-go zone. Null if the requested choice is not possible.
     */
    fun correction(outcome: DrivenOutcome, enlarge: Boolean, name: String, corrections: List<MapCorrection>, raw: RobotMap,
                   current: RobotMap, nowMs: Long): MapCorrection? {
        val id = MapCorrections.nextId(corrections)
        return when (outcome) {
            is DrivenOutcome.Refused -> null
            is DrivenOutcome.Zone -> if (enlarge) {
                val e = outcome.enlargement ?: return null
                val before = current.shapes.firstOrNull { it.id == e.targetId } ?: return null
                val (add, cut) = MapCorrections.diff(before.points, e.after)
                if (add.isEmpty()) return null
                ShapePatch(id, nowMs, tr("${e.targetName} · driven enlargement (${MapCorrections.balance(add, cut)})",
                    "${e.targetName} · agrandissement piloté (${MapCorrections.balance(add, cut)})"), e.targetId,
                    raw.shapes.firstOrNull { it.id == e.targetId }?.let(MapCorrections::fingerprint), add, cut, CorrectionTool.DRIVEN)
            } else {
                val points = outcome.separate ?: return null
                val title = name.trim().ifEmpty { defaultZoneName(current) }
                AddedZone(id, nowMs, tr("DumbyLuba zone $title · ${MapCorrections.squareMeters(outcome.separateAreaM2)}",
                    "Zone DumbyLuba $title · ${MapCorrections.squareMeters(outcome.separateAreaM2)}"), title, points)
            }
            is DrivenOutcome.Passage -> AddedPassage(id, nowMs, tr("DumbyLuba passage · ${MapCorrections.meters(outcome.lengthM)} m",
                "Passage DumbyLuba · ${MapCorrections.meters(outcome.lengthM)} m"), outcome.points)
            is DrivenOutcome.NoGo -> AddedNoGo(id, nowMs, tr("Driven no-go · ${MapCorrections.squareMeters(outcome.areaM2)}",
                "No-go pilotée · ${MapCorrections.squareMeters(outcome.areaM2)}"), outcome.points, NoGoOrigin.TRACE)
        }
    }

    /** "DL zone n": first number not used by a zone of the map. */
    fun defaultZoneName(map: RobotMap): String {
        val names = map.shapes.map { it.name.trim().lowercase() }.toSet()
        val base = tr("DL zone", "Zone DL")
        return generateSequence(1) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in names }
    }

    // ---------------------------------------------------------------- internal

    /** Polygon enclosed by the centre trace (closed); null if it crosses itself into several significant loops. */
    internal fun centrePolygon(points: List<Point>): Polygon? {
        val ring = (points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()
        val noded = UnaryUnionOp.union(factory.createLineString(ring))
        val polygonizer = Polygonizer()
        polygonizer.add(noded)
        @Suppress("UNCHECKED_CAST")
        val loops = (polygonizer.polygons as Collection<Polygon>).map { factory.createPolygon(it.exteriorRing.coordinates) }
            .filter { it.area > 1e-6 }.sortedByDescending { it.area }
        val main = loops.firstOrNull() ?: return null
        // A loop nested in the main one is part of it; only the loops outside it reveal a crossing (figure eight).
        val outside = loops.drop(1).filter { !main.covers(it.interiorPoint) }.sumOf { it.area }
        return if (outside > main.area * CROSSING_SHARE) null else main
    }

    private fun zone(centre: Polygon, map: RobotMap, robot: RobotEnvelope, warnings: List<String>): DrivenOutcome {
        val grown = buffer(centre, robot.halfWidthM)
        val outline = when (val r = MapCorrections.check(points(grown), ShapeKind.ZONE)) {
            is ShapeResult.Ok -> r.points
            is ShapeResult.Refused -> return DrivenOutcome.Refused(r.reason, warnings)
        }
        val polygon = polygon(outline)
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val zoneGeometries = zones.associate { it.id to valid(polygon(it.points)) }
        val overlaps = zones.map { it to OverlayNGRobust.overlay(polygon, zoneGeometries.getValue(it.id), OverlayNG.INTERSECTION).area }
            .filter { it.second >= MIN_OVERLAP_M2 }.sortedByDescending { it.second }
        // New zone: what the outline adds to all the zones.
        val rest = if (zones.isEmpty()) polygon else OverlayNGRobust.overlay(polygon, union(zoneGeometries.values), OverlayNG.DIFFERENCE)
        var separate: List<Point>? = null
        var refusal: String? = null
        if (rest.area < MapCorrections.MIN_ZONE_AREA_M2) refusal = tr("The outline stays inside the existing zones", "Le contour reste dans les zones existantes")
        else when (val r = MapCorrections.check(points(largest(rest) ?: rest), ShapeKind.ZONE)) {
            is ShapeResult.Ok -> {
                val pieces = parts(reduce(rest)).count { it.area >= MapCorrections.MIN_ZONE_AREA_M2 }
                if (pieces > 1) refusal = tr("The existing zones cut the outline into $pieces pieces: enlarge the zone instead, or record each piece",
                    "Les zones existantes coupent le contour en $pieces morceaux : agrandissez plutôt la zone, ou enregistrez chaque morceau")
                else separate = r.points
            }
            is ShapeResult.Refused -> refusal = r.reason
        }
        // Enlargement: the most overlapped zone grows by the part of the outline that no other zone covers.
        val enlargement = overlaps.firstOrNull()?.first?.let { target ->
            val others = zoneGeometries.filterKeys { it != target.id }.values
            val addition = if (others.isEmpty()) polygon else OverlayNGRobust.overlay(polygon, union(others), OverlayNG.DIFFERENCE)
            val wkt = WKTWriter().write(reduce(addition))
            (MapCorrections.patched(target, wkt, "") as? ShapeResult.Ok)?.let { after ->
                val added = MapCorrections.polygonArea(after.points) - MapCorrections.polygonArea(target.points)
                if (added < 0.05) null
                else DrivenEnlargement(target.id, MapCorrections.shapeName(target), MapCorrections.isDumbyLuba(target), after.points, added)
            }
        }
        if (separate == null && enlargement == null) return DrivenOutcome.Refused(refusal ?: tr("Outline not usable", "Contour inutilisable"), warnings)
        val separatePolygon = separate?.let(::polygon)
        val original = (map.mammotionShapes ?: map.shapes).filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val target = separatePolygon ?: polygon
        val beyond = if (original.isEmpty()) target.area
            else OverlayNGRobust.overlay(target, union(original.map { valid(polygon(it.points)) }), OverlayNG.DIFFERENCE).area
        return DrivenOutcome.Zone(outline, separate, refusal, enlargement, separatePolygon?.area ?: 0.0, beyond,
            separatePolygon == null || connected(separatePolygon, map, robot), warnings)
    }

    /**
     * Reachable zone: in the surface where the robot centre can be (zones and passage corridors shrunk by half a width),
     * the part covering the new zone also reaches another zone. Same corridor as the planner (axis ± half-width + 0.45 m).
     */
    internal fun connected(zone: JtsGeometry, map: RobotMap, robot: RobotEnvelope): Boolean {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }.map { valid(polygon(it.points)) }
        if (zones.isEmpty()) return true
        val corridors = map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.distinct().size >= 2 }
            .map { line(it.points).buffer(robot.halfWidthM + 0.45) }
        val others = union(zones + corridors)
        val inner = OverlayNGRobust.union(listOf(others, zone)).buffer(-robot.halfWidthM)
        val own = zone.buffer(-robot.halfWidthM)
        if (own.isEmpty) return false
        val reached = OverlayNGRobust.overlay(others, zone, OverlayNG.DIFFERENCE).buffer(-robot.halfWidthM)
        return parts(inner).any { part -> part.intersects(own) && part.intersects(reached) }
    }

    private fun passage(trace: DrivenTrace, map: RobotMap, warnings: List<String>): DrivenOutcome {
        if (trace.points.size < 2) return DrivenOutcome.Refused(tr("Drive the robot along the passage", "Conduisez le robot le long du passage"), warnings)
        val simple = DouglasPeuckerSimplifier.simplify(line(trace.points), PASSAGE_SIMPLIFY_M).coordinates.map { Point(it.x, it.y) }
        val points = when (val r = MapCorrections.checkPath(simple)) {
            is ShapeResult.Ok -> r.points
            is ShapeResult.Refused -> return DrivenOutcome.Refused(r.reason, warnings)
        }
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val ends = listOf(points.first(), points.last()).count { end -> zones.any { Geometry.inside(end, it.points) } }
        val noGos = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }
        val axis = line(points)
        val extra = buildList {
            if (ends < 2) add(tr("$ends end(s) inside a zone out of 2: start and finish the passage inside the zones it connects",
                "$ends extrémité(s) dans une zone sur 2 : commencez et terminez le passage dans les zones qu'il relie"))
            if (noGos.any { axis.intersects(valid(polygon(it.points))) }) add(tr("The passage crosses a no-go zone: the robot will not travel there",
                "Le passage traverse une no-go : le robot n'y circulera pas"))
        }
        return DrivenOutcome.Passage(points, axis.length, ends, warnings + extra)
    }

    private fun noGo(centre: Polygon, robot: RobotEnvelope, warnings: List<String>): DrivenOutcome {
        val shrunk = buffer(centre, -robot.halfWidthM)
        if (shrunk.isEmpty || shrunk.area < MapCorrections.MIN_NO_GO_AREA_M2) return DrivenOutcome.Refused(
            tr("Loop too tight for a no-go zone: drive around the obstacle without touching it", "Boucle trop serrée pour une no-go : faites le tour de l'obstacle sans le toucher"), warnings)
        return when (val r = MapCorrections.check(points(largest(shrunk) ?: shrunk), ShapeKind.NO_GO)) {
            is ShapeResult.Ok -> DrivenOutcome.NoGo(r.points, MapCorrections.polygonArea(r.points), warnings)
            is ShapeResult.Refused -> DrivenOutcome.Refused(r.reason, warnings)
        }
    }

    private fun buffer(g: JtsGeometry, distance: Double): JtsGeometry {
        val params = BufferParameters(8, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 3.0)
        return DouglasPeuckerSimplifier.simplify(BufferOp.bufferOp(g, distance, params), SIMPLIFY_M)
    }

    private fun largest(g: JtsGeometry): Polygon? = parts(g).maxByOrNull { it.area }

    private fun parts(g: JtsGeometry): List<Polygon> = (0 until g.numGeometries).map { g.getGeometryN(it) }.filterIsInstance<Polygon>()

    private fun points(g: JtsGeometry): List<Point> {
        val p = (if (g is Polygon) g else largest(g)) ?: return emptyList()
        return p.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
    }

    private fun reduce(g: JtsGeometry): JtsGeometry = GeometryPrecisionReducer.reduce(g, precision)

    private fun union(items: Collection<JtsGeometry>): JtsGeometry =
        if (items.isEmpty()) factory.createPolygon() else OverlayNGRobust.union(items)

    private fun valid(g: JtsGeometry): JtsGeometry = if (g.isValid) g else g.buffer(0.0)

    private fun polygon(points: List<Point>): Polygon =
        factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())

    private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
}
