package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.planning.BoundaryMeaning
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningNavigation
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.planning.PlanningResult
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import org.locationtech.jts.geom.Geometry as JtsGeometry

/** Brush stroke on the map, in meters: add or erase, constant radius. */
data class PaintStroke(val points: List<Point>, val radiusM: Double, val erase: Boolean = false) {
    init { require(radiusM.isFinite() && radiusM > 0) }
}

/** Mission prepared for debug mowing: planner preview and verified route. */
data class PaintedMission(val zones: List<MapShape>, val areaM2: Double, val preview: Preview,
    val report: PlanningResult?, val engine: MowingSimulation)

/**
 * Temporary mowing zone painted with a finger. Only the part covering existing zones is kept (never beyond a recorded
 * boundary); no-go zones, learned obstacles and the base zone stay applied by the planner, as for mowing whole zones
 * (loop around enclosed no-go zones, bypass of nearby learned obstacles). Displayed area and outline: no-go excluded.
 *
 * The kept area is split into simple boundaries (without holes) and added to a copy of the map as temporary zones:
 * they are only a target for the planner. Preparation and control use the unchanged robot map — same travel areas,
 * same checks.
 */
object PaintedZone {
    /** Piece ignored below this area (isolated stroke, brush overflow). */
    const val MIN_PIECE_M2 = 0.5
    /** Morphological opening: removes fringes narrower than twice this value. */
    private const val OPENING_M = 0.1
    private const val SIMPLIFY_M = 0.03
    private const val MAX_SPLITS = 32
    /** Temporary zone identifiers: negative, outside the observed Mammotion fingerprints. */
    const val FIRST_ID = -7_000_000_001L
    /** Minimum required coverage: a small painted area has proportionally more rounded edges out of cutting reach
     * (outer loop offset) than a whole zone. */
    const val MIN_COVERAGE = 0.8
    private val factory = GeometryFactory()

    private fun polygon(points: List<Point>): Polygon {
        val ring = (points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()
        return factory.createPolygon(ring)
    }
    private fun valid(g: JtsGeometry) = if (g.isValid) g else g.buffer(0.0)
    private fun polygons(g: JtsGeometry): List<Polygon> = (0 until g.numGeometries).flatMap { i ->
        when (val part = g.getGeometryN(i)) {
            is Polygon -> if (part.isEmpty) emptyList() else listOf(part)
            else -> if (part === g) emptyList() else polygons(part)
        }
    }

    /** Raw painted area: successive strokes, the eraser removes what was painted before it. */
    fun painted(strokes: List<PaintStroke>): JtsGeometry =
        strokes.filter { it.points.isNotEmpty() }.fold(factory.createPolygon() as JtsGeometry) { area, stroke ->
            val path = if (stroke.points.size == 1) factory.createPoint(Coordinate(stroke.points[0].x, stroke.points[0].y))
                else factory.createLineString(stroke.points.map { Coordinate(it.x, it.y) }.toTypedArray())
            val brush = path.buffer(stroke.radiusM, 8)
            if (stroke.erase) area.difference(brush) else area.union(brush)
        }

    /** Area of all map zones: paint is only kept inside. */
    fun zonesArea(map: RobotMap): JtsGeometry = UnaryUnionOp.union(
        map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }.map { valid(polygon(it.points)) })
        ?: factory.createPolygon()

    /** Vertical cut through a hole until only simple boundaries are left. */
    private fun holeFree(p: Polygon, depth: Int = 0): List<Polygon> {
        if (p.numInteriorRing == 0) return listOf(p)
        require(depth < MAX_SPLITS) { tr("Painted zone too fragmented: simplify the drawing", "Zone peinte trop morcelée : simplifiez le tracé") }
        val hole = p.getInteriorRingN(0).envelopeInternal
        val cut = (hole.minX + hole.maxX) / 2
        val env = p.envelopeInternal
        return listOf(Envelope(env.minX - 1, cut, env.minY - 1, env.maxY + 1), Envelope(cut, env.maxX + 1, env.minY - 1, env.maxY + 1))
            .flatMap { box -> polygons(p.intersection(factory.toGeometry(box))) }
            .flatMap { holeFree(it, depth + 1) }
    }

    /** Simple boundaries kept: paint ∩ existing zones, fringes removed, small pieces ignored. */
    fun pieces(map: RobotMap, strokes: List<PaintStroke>): List<List<Point>> = pieces(map, painted(strokes))

    /** Simple boundaries kept from any target area (paint, gaps of the real coverage): same clipping and filtering. */
    fun pieces(map: RobotMap, painted: JtsGeometry): List<List<Point>> {
        if (painted.isEmpty) return emptyList()
        val clipped = valid(painted.intersection(zonesArea(map)))
        val opened = clipped.buffer(-OPENING_M, 8).buffer(OPENING_M, 8).intersection(clipped)
        return polygons(opened).flatMap { holeFree(it) }
            .map { valid(TopologyPreservingSimplifier.simplify(it, SIMPLIFY_M)) }
            .flatMap(::polygons)
            .filter { it.area >= MIN_PIECE_M2 }
            .map { piece -> piece.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) } }
            .filter { it.size >= 3 }
    }

    /** Area actually mowed: kept pieces minus the no-go zones (holes included). No-go zones stay planner exclusions;
     * they are never counted or circled as painted. */
    fun mowable(map: RobotMap, pieces: List<List<Point>>): JtsGeometry {
        val shapes = pieces.filter { it.size >= 3 }.map { valid(polygon(it)) }
        if (shapes.isEmpty()) return factory.createPolygon()
        val noGo = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }.map { valid(polygon(it.points)) }
        val painted = valid(UnaryUnionOp.union(shapes))
        return if (noGo.isEmpty()) painted else valid(painted.difference(UnaryUnionOp.union(noGo)))
    }

    /** Boundaries to display: outer edges and boundaries of enclosed no-go zones. */
    fun outlines(area: JtsGeometry): List<List<Point>> = polygons(area).flatMap { p ->
        listOf(p.exteriorRing) + (0 until p.numInteriorRing).map { p.getInteriorRingN(it) }
    }.map { ring -> ring.coordinates.dropLast(1).map { Point(it.x, it.y) } }.filter { it.size >= 3 }

    /** Temporary zones ordered from the start to the farthest (nearest neighbour). */
    fun zones(map: RobotMap, pieces: List<List<Point>>, start: Point): List<MapShape> {
        val used = map.shapes.map { it.id }.toSet()
        val ids = generateSequence(FIRST_ID) { it - 1 }.filter { it !in used }.iterator()
        val remaining = pieces.toMutableList()
        val ordered = mutableListOf<List<Point>>()
        var from = start
        while (remaining.isNotEmpty()) {
            val next = remaining.minBy { piece -> piece.minOf { it.distance(from) } }
            remaining.remove(next); ordered.add(next)
            val center = polygon(next).interiorPoint
            from = Point(center.x, center.y)
        }
        return ordered.mapIndexed { index, points -> MapShape(ids.next(), 0, points, tr("Test zone ${index + 1}", "Zone test ${index + 1}")) }
    }

    /**
     * Full computation and preparation, like mowing zones from the map: same automatic planner, same navigation and
     * same route preparation as real mowing.
     */
    fun prepare(map: RobotMap, strokes: List<PaintStroke>, start: Point, base: MapBase, accesses: List<List<Point>>,
                obstacles: List<LocalObstacle>, spacingM: Double, borders: Boolean, speedMps: Double,
                checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): PaintedMission =
        prepare(map, painted(strokes), start, base, accesses, obstacles, spacingM, borders, speedMps, checkpoint, progress)

    /**
     * Same preparation for a target [area] in map metres, e.g. the gaps of a real coverage
     * ([fr.luba.poc.history.CoverageMap.gapArea]): only its part inside the zones is mowed.
     */
    fun prepare(map: RobotMap, area: JtsGeometry, start: Point, base: MapBase, accesses: List<List<Point>>,
                obstacles: List<LocalObstacle>, spacingM: Double, borders: Boolean, speedMps: Double,
                checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): PaintedMission {
        require(map.complete && !map.source.contains("DÉMONSTRATION", true)) { tr("A complete real map is required", "Carte réelle complète obligatoire") }
        requireNotNull(base.entryHeadingDegrees) { tr("Base axis unknown: observe the robot while charging", "Axe de la base inconnu : observer le robot en charge") }
        // Start nearest to the robot (nearest free point if it stopped inside a reserve).
        val routes = RoutePlanning.mission(map, obstacles, accesses, base)
        val from = RoutePlanning.missionStart(routes, start, docked = false, base = base.point) ?: base.point
        val zones = zones(map, pieces(map, area), from)
        require(zones.isNotEmpty()) { tr("No painted area inside the zones (minimum ${MIN_PIECE_M2} m²)", "Aucune surface peinte à l'intérieur des zones (minimum ${MIN_PIECE_M2} m²)") }
        val planningMap = map.copy(shapes = map.shapes + zones)
        val config = PlanningConfig(spacingM = spacingM, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = MIN_COVERAGE,
            speedMps = speedMps, borders = borders, borderPasses = 1, noGoPasses = 1, bordersFirst = true, nearbyNoGo = true,
            boundary = BoundaryMeaning.RECORDED_TRACE, outerOffsetM = OuterOffsetSetting.meters)
        progress(tr("Computing the passes", "Calcul des passes"))
        val planned = RoutePlanning.coverage(PlanningRequest(planningMap, zones.map { it.id }, from, config = config,
            obstacles = fr.luba.poc.planning.DockClearance.planning(obstacles, base),
            navigation = PlanningNavigation(accesses, base), end = base.point.takeIf { it != from }), checkpoint = checkpoint)
        require(!planned.preview.localOnly) {
            val detail = planned.report?.selected?.diagnostics?.joinToString(" ; ")?.takeIf { it.isNotBlank() }
            tr("Plan not validated: ${detail ?: "insufficient coverage"}", "Plan non validé : ${detail ?: "couverture insuffisante"}")
        }
        checkpoint()
        progress(tr("Checking the links", "Vérification des raccords"))
        // Preparation on the unchanged robot map: the temporary zones are only a target.
        val engine = MowingSimulation(routes, base.point, planned.preview, map,
            SimConfig(speedMps = speedMps, instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees,
                baseApproachMeters = base.approachDistanceM), bladeZones = zones.map { it.points }) { progress(it); checkpoint() }
        return PaintedMission(zones, mowable(map, zones.map { it.points }).area, planned.preview, planned.report, engine)
    }
}
