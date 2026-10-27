package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.util.AffineTransformation
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*

enum class CoverageMode(private val en: String, private val fr: String) {
    LINES("Simple", "Simple"), CHECKERBOARD("Checkerboard", "Damier"), TOUR("Full loop", "Grand tour"),
    SURVEY_GRID("Signal grid", "Quadrillage des signaux"),
    /** Zigzag whose orientation may change from one part of the zone to another. */
    OPTIMIZED("Optimized", "Optimisé"),
    /** Zigzag passes driven in a front-advancing order with rolling U-turns ([ZamboniOrder]). Appended: persisted by name. */
    ZAMBONI("Zamboni", "Zamboni"),
    /** Passes parallel to the contour joined into inward spirals ([SpiralSweep]), zigzag in the pockets. Appended: persisted by name. */
    SPIRAL("Spiral", "Spirale");
    /** Display label in the interface language. */
    val label: String get() = tr(en, fr)
    val isSurvey: Boolean get() = this == TOUR || this == SURVEY_GRID
}

/** Experimental parameters; null keeps exactly the historical search. */
data class SweepOptions(val angles: List<Int>, val phase: Double = 0.5,
    val decompose: ((JtsGeometry) -> List<JtsGeometry>)? = null) {
    init { require(angles.isNotEmpty() && angles.all { it in 0..179 } && phase in 0.1..0.9) }
}

/** Metric computation only: never a robot command. */
object CoveragePlanner {
    private val factory = GeometryFactory()
    private const val OBSTACLE_SEGMENTS = 64
    /** Cost of a pass that cannot be joined to the previous one ([TurnPlan.score] units): a transit between two runs. */
    private const val BREAK_COST = 60.0
    /** Lawn friendly: equivalent in metres of one degree of wear ([TurnOptimizer.Drive.wear]): 9 m for a U of two pivots. */
    private const val LAWN_WEAR_COST_M = 0.05

    /**
     * Planning hole of a reserve disc: the disc already describes the perimeter of the robot reference point (physical
     * radius + safety margin + turning envelope). The hole is reduced by the planner margin — corrected for the inscribed
     * polygon — so that after this margin the route holds exactly the disc, without double counting.
     */
    private fun plannerHole(disc: ObstacleDisc, margin: Double): List<Point> {
        val correction = cos(Math.PI / OBSTACLE_SEGMENTS)
        val radius = max(0.05, (disc.radiusM - margin) / correction)
        return (0 until OBSTACLE_SEGMENTS).map { index ->
            val angle = 2.0 * Math.PI * index / OBSTACLE_SEGMENTS
            Point(disc.x + radius * cos(angle), disc.y + radius * sin(angle))
        }
    }
    private fun polygon(points: List<Point>): Polygon {
        require(points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() }) { tr("Invalid boundary", "Contour invalide") }
        val coords = points.map { Coordinate(it.x,it.y) }.toMutableList()
        if (!coords.first().equals2D(coords.last())) coords.add(Coordinate(coords.first()))
        val polygon = factory.createPolygon(coords.toTypedArray())
        require(polygon.isValid && polygon.area > 0) { tr("Self-crossing boundary or zero area", "Contour croisé ou surface nulle") }
        return polygon
    }
    /** Area of the chosen zones, without double counting or outer exclusions. */
    fun selectedArea(map: RobotMap, zoneIds: Set<Long>): Double {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.id in zoneIds }.map { polygon(it.points) }
        if (zones.isEmpty()) return 0.0
        var surface: JtsGeometry = zones.first()
        zones.drop(1).forEach { surface = surface.union(it) }
        map.shapes.filter { it.excludes }.forEach { surface = surface.difference(polygon(it.points)) }
        return surface.area
    }

    /**
     * Row cut in the area: the classic JTS overlay sometimes fails on a rotated area ("non-noded intersection", Enfer with
     * a 1 m offset); retried with the robust overlay.
     */
    private fun clip(surface: JtsGeometry, row: LineString): JtsGeometry =
        try { surface.intersection(row) } catch (e: org.locationtech.jts.geom.TopologyException) {
            org.locationtech.jts.operation.overlayng.OverlayNGRobust.overlay(surface, row, org.locationtech.jts.operation.overlayng.OverlayNG.INTERSECTION)
        }
    private fun points(line: LineString) = line.coordinates.map { Point(it.x,it.y) }
    private fun parts(geometry: JtsGeometry): List<JtsGeometry> =
        if (geometry.numGeometries == 1 && geometry.getGeometryN(0) === geometry) listOf(geometry)
        else (0 until geometry.numGeometries).flatMap { parts(geometry.getGeometryN(it)) }

    /** Historical outer loop offset (test reference); the interface offers more. */
    const val RECORDED_OUTER_OFFSET_M = 0.08
    const val MAX_OUTER_OFFSET_M = 1.0
    /** Distance between the outer loop and the end of the passes: half a cut, adjoining cuts. */
    const val ZIGZAG_LOOP_INSET_M = Geometry.CUT_WIDTH_M / 2
    fun plan(zone: List<Point>, holes: List<List<Point>>, spacing: Double, margin: Double,
             mode: CoverageMode, includeBorders: Boolean,
             borderPasses: Int = 1, noGoPasses: Int = 1, obstacles: List<ObstacleDisc> = emptyList(),
             robot: RobotEnvelope = RobotEnvelope.LUBA2, recordedBoundary: Boolean = false, basePoint: Point? = null, onProgress: (Preview) -> Unit = {}, sweepOptions: SweepOptions? = null,
             continuous: Boolean = false, candidateCost: ((Preview) -> Double)? = null,
             /** Called in the order of the orientations, on the calling thread. */
             observeCandidate: ((Preview) -> Unit)? = null,
             /** Recorded boundary: margin between the side of the chassis and the boundary for the outer loop, in meters. */
             outerOffsetM: Double = RECORDED_OUTER_OFFSET_M,
             /** U-turns between passes ([TurnOptimizer]); [TurnStyle.LAWN] also takes the passes of a cell one in two. */
             turnStyle: TurnStyle = TurnStyle.FAST,
             /** Mowing speed for the duration of the U-turns compared. */
             speedMps: Double = 0.3,
             /** Cut width: what a U-turn sweeps of the pass parts it shortens; the spacing when unknown. */
             cutWidthM: Double = spacing,
             /** "Change every session": among the orientations, the cheapest one far enough from the last mowing
              * ([OrientationRule.pick]); none ⇒ the cheapest orientation with its rows shifted by [OrientationRule.phase]. */
             orientationRule: OrientationRule? = null,
             /** Called with the cheapest orientation before [orientationRule] applies. */
             observeAutomatic: ((Preview) -> Unit)? = null,
             /** Called at every layer of the Zamboni order (cancellation); may throw. */
             checkpoint: () -> Unit = {},
             /** Spiral mode: chains driven from the edge inwards (as built) or from the centre outwards. */
             spiralDirection: SpiralDirection = SpiralDirection.FROM_EDGE): Preview {
        val tour = mode == CoverageMode.TOUR
        val withBorders = mode.isSurvey || includeBorders
        val outerPasses = if (mode.isSurvey) 1 else borderPasses
        val holePasses = if (mode.isSurvey) 0 else noGoPasses
        require(spacing.isFinite() && margin.isFinite() && spacing >= 0.05 && margin >= 0.3)
        require(outerPasses >= 0 && holePasses >= 0) { tr("Invalid number of passes", "Nombre de passages invalide") }
        require(outerOffsetM.isFinite() && outerOffsetM in 0.0..MAX_OUTER_OFFSET_M) { tr("Outer loop offset out of range", "Retrait du tour extérieur hors plage") }
        // Recorded boundary = outer side of the chassis: the center of the outer loop is half a width further in.
        val outerOffset = if (recordedBoundary) RecordedBoundary.centerInsetM(robot) + outerOffsetM else outerOffsetM
        require(obstacles.all { it.radiusM.isFinite() && it.radiusM > 0 }) { tr("Invalid reserve disc", "Disque de réserve invalide") }
        val area = polygon(zone)
        require(area.envelopeInternal.width < 500 && area.envelopeInternal.height < 500) { tr("Zone too large for this POC", "Zone trop grande pour ce POC") }
        // Reserve the corners during pivots, even if the requested margin is smaller.
        val clearance = max(margin, robot.turnClearanceM + 0.01)
        val allHoles = holes + obstacles.map { plannerHole(it, clearance) }
        var physicalFree: JtsGeometry = if (recordedBoundary) RecordedBoundary.physicalArea(zone, robot) else area
        holes.forEach { physicalFree = physicalFree.difference(if (recordedBoundary) RecordedBoundary.forbiddenArea(it, robot) else polygon(it)) }
        // These discs already include the chassis radius: remove it for the physical simulation.
        obstacles.forEach { disc ->
            val radius = max(0.0001, disc.radiusM - robot.turnClearanceM)
            val physical = factory.createPoint(Coordinate(disc.x, disc.y)).buffer(radius / cos(Math.PI / 64), 16)
            physicalFree = physicalFree.difference(physical)
        }
        val envelope = TrajectoryEnvelope(physicalFree, robot)
        var free: JtsGeometry = area
        allHoles.forEach { free = free.difference(polygon(it)) }
        // Extra reserve: the chords of the buffer arcs stay outside the requested margin.
        var inset = free.buffer(-(clearance + 0.02), 16)
        require(!inset.isEmpty) { tr("No route possible with this margin", "Aucun trajet possible avec cette marge") }
        // Concentric rings: pass k is one more ring, shifted inwards by k*spacing. Pass 0 stays the safe boundary
        // (margin); the following ones are therefore always further from the boundaries, never closer.
        val outerRings = mutableListOf<List<Point>>()
        val noGoRings = mutableListOf<List<Point>>()
        if (withBorders) {
            val passes = maxOf(outerPasses, holePasses)
            for (k in 0 until passes) {
                val ring = if (k == 0) inset else free.buffer(-(clearance + 0.02 + k * spacing), 16)
                if (ring.isEmpty) break
                parts(ring).filterIsInstance<Polygon>().forEach { poly ->
                    if (!recordedBoundary && k < outerPasses) outerRings.add(points(poly.exteriorRing))
                    if (!recordedBoundary && k < holePasses) for (i in 0 until poly.numInteriorRing) noGoRings.add(points(poly.getInteriorRingN(i)))
                }
            }
        }
        if (withBorders && recordedBoundary) {
            // The straight portions run along the boundary; reserve the overhang during pivots.
            // Round the corners inwards, then check each chassis pivot.
            for (k in 0 until outerPasses) {
                val distance = outerOffset + k * spacing
                val radius = RecordedBoundary.ringRoundingM(robot)
                var ring = area.buffer(-(radius + distance), 16).buffer(radius, 16).intersection(area)
                // Chassis outside the no-go zones (boundary = side of the chassis), without the zigzag margin that would
                // artificially close the passages at the periphery.
                // Along a no-go zone, same margin as along the boundary (fixed 5 cm before, 2026-10-14).
                holes.forEach { ring = ring.difference(polygon(it).buffer(distance, 16)) }
                obstacles.forEach { ring = ring.difference(polygon(plannerHole(it,clearance)).buffer(clearance + 0.02,16)) }
                if (ring.isEmpty) break
                parts(ring).filterIsInstance<Polygon>().forEach { poly -> outerRings.add(points(poly.exteriorRing)) }
            }
        }
        var skippedBorderLoops = 0
        // Centers allowed for a border loop: chassis inside the zone (boundary = side of the chassis), like the navigation
        // area that validates the mission again ([fr.luba.poc.simulation.TrajectoryPlanner.safe]). A no-go loop merely
        // contained in the zone could pass closer than half a width to the boundary (2026-10-15, Fosse at 20 cm: loop
        // 21 cm from the boundary, "Trajet hors de la surface sûre" at preparation).
        val chassisArea by lazy { area.buffer(-(RecordedBoundary.centerInsetM(robot) - 1e-5), 16) }
        if (withBorders && recordedBoundary) {
            // Reserve of the learned obstacles, the same as the one removed from the outer loops.
            val obstacleReserves = obstacles.map { polygon(plannerHole(it,clearance)).buffer(clearance + 0.02,16) }
            val noGoPolygons = holes.map(::polygon).filter { it.intersects(area) }
            for (k in 0 until holePasses) {
                // Center at half a width plus the outer loop margin of the boundary: the chassis stays outside the no-go zone
                // (fixed 5 cm before: bumper contacts with a wider set margin, 2026-10-14). No-go zones closer than twice this
                // distance are bypassed together: a separate loop would pass too close to the neighbour.
                val distance = RecordedBoundary.noGoLoopOffsetM(robot, k, spacing, outerOffsetM)
                if (noGoPolygons.isEmpty()) break
                var ring = org.locationtech.jts.operation.union.UnaryUnionOp.union(noGoPolygons.map { it.buffer(distance, 16) })
                // A learned obstacle touching the no-go border is bypassed on the outside (same reserve as for the outer loops)
                // instead of being crossed.
                obstacleReserves.filter { it.intersects(ring) }.forEach { ring = ring.union(it) }
                // Order of the map's no-go zones (the union loses it): it guides the mowing order of the borders.
                parts(ring).filterIsInstance<Polygon>().sortedBy { poly ->
                    noGoPolygons.indexOfFirst { poly.covers(it) }.let { if (it < 0) Int.MAX_VALUE else it }
                }.forEach { poly ->
                    val inside = noGoPolygons.filter { poly.covers(it) }
                    // Only no-go zones fully inside the zone get a loop; a no-go zone straddling the boundary gets none.
                    val covered = inside.count { area.covers(it) }
                    if (covered == 0) return@forEach
                    if (covered < inside.size || !chassisArea.covers(poly.exteriorRing)) {
                        // Loop outside the zone (no-go zone near the boundary, no room for the chassis in between): no loop, the outer
                        // loop already runs along this side and the zigzags keep the reserve.
                        skippedBorderLoops += covered
                        return@forEach
                    }
                    noGoRings.add(points(poly.exteriorRing))
                }
            }
        }
        if (recordedBoundary) {
            // Recorded boundaries may contain very short angles: the reference point path is allowed, but the rectangle
            // pivot is not necessarily.
            // Reserve only the necessary offset, without hiding a collision.
            val exclusionPolygons = holes.map(::polygon)
            fun maneuverable(path: List<Point>, outer: Boolean): List<List<Point>> {
                fun valid(candidate: List<Point>): Boolean {
                    if (candidate.size < 4) return false
                    val line = factory.createLineString(candidate.map { Coordinate(it.x,it.y) }.toTypedArray())
                    if (!chassisArea.covers(line) || exclusionPolygons.any { it.intersects(line) }) return false
                    val heading = Math.toDegrees(atan2(candidate[1].y-candidate[0].y,candidate[1].x-candidate[0].x))
                    return envelope.pathCollision(candidate, finalHeading=heading) == null
                }
                if (valid(path)) return listOf(path)
                val contour = polygon(path)
                val rounded = if (outer) contour.buffer(-robot.lengthM,16).buffer(robot.lengthM,16) else contour
                if (outer && rounded.isEmpty) {
                    skippedBorderLoops++
                    return emptyList()
                }
                for (step in 0..30) {
                    val adjusted = rounded.buffer((if (outer) -1 else 1)*step*0.02,16)
                    val polygons = parts(adjusted).filterIsInstance<Polygon>().filterNot { it.isEmpty }
                    if (polygons.isEmpty()) continue
                    val candidates = polygons.map { points(it.exteriorRing) }
                    if (candidates.all(::valid)) return candidates
                }
                // Loop not drivable for the chassis (boundary = side of the robot: passage too narrow, no-go, obstacle): dropped,
                // the zigzags keep their reserve and the coverage is still evaluated; the mowing is no longer refused as a
                // whole.
                skippedBorderLoops++
                return emptyList()
            }
            val safeOuter = outerRings.flatMap { maneuverable(it,true) }
            val safeNoGo = noGoRings.flatMap { maneuverable(it,false) }
            outerRings.clear(); outerRings.addAll(safeOuter)
            noGoRings.clear(); noGoRings.addAll(safeNoGo)
        }
        if (recordedBoundary) {
            // Loop start/end on a point where the entry and exit can pivot without collision.
            fun anchor(path: List<Point>): List<Point> {
                val vertices = path.dropLast(1)
                val index = vertices.indexOfFirst { p -> envelope.turnCollision(p,0.0,180.0) == null && envelope.turnCollision(p,180.0,360.0) == null }
                if (index >= 0) {
                    val rotated = vertices.drop(index) + vertices.take(index)
                    return rotated + rotated.first()
                }
                // A loop may be drivable without allowing a full turn while stopped.
                // Tangent entry in the middle of the longest segment; the engine will check the real link.
                val edge = vertices.indices.maxByOrNull { i -> vertices[i].distance(vertices[(i+1)%vertices.size]) }
                    ?: error(tr("Empty border", "Bordure vide"))
                val a = vertices[edge]; val b = vertices[(edge+1)%vertices.size]
                val entry = Point((a.x+b.x)/2, (a.y+b.y)/2)
                return listOf(entry) + vertices.drop(edge+1) + vertices.take(edge+1) + entry
            }
            outerRings.indices.forEach { outerRings[it] = anchor(outerRings[it]) }
            noGoRings.indices.forEach { noGoRings[it] = anchor(noGoRings[it]) }
        }
        val borders = outerRings + noGoRings
        val outer = outerRings.size; val inner = noGoRings.size
        if (recordedBoundary && mode != CoverageMode.TOUR) {
            // Pass ends never closer to a boundary than its loop (2026-10-14: bumper contacts along the border).
            // Behind the outer loop, half a cut; behind a no-go loop, a quarter (30 cm nominal overlap). Without a loop (none
            // requested, or dropped): at the loop distance + half a cut, set margin included (half a cut only before, margin
            // ignored).
            // Use the loops actually drivable, including their manoeuvre offsets.
            val overlapInset = ZIGZAG_LOOP_INSET_M
            val noGoOverlapInset = Geometry.CUT_WIDTH_M / 4
            val bareInset = outerOffset + overlapInset
            val outerPolygons = outerRings.map(::polygon)
            val innermost = outerPolygons.filter { p -> outerPolygons.none { q ->
                q.area < p.area-1e-8 && p.covers(q)
            } }
            var coverage: JtsGeometry = if (outerRings.isEmpty()) area.buffer(-bareInset,16)
                else org.locationtech.jts.operation.union.UnaryUnionOp.union(innermost)
                    .buffer(-overlapInset,16)
            // The notches of the outer border do not define the cutting area: restore the drivable pockets behind the no-go
            // zones, with a full pivot reserve.
            // Behind a loop, the pockets follow its corner rounding (2026-10-15: without it, passes went up to the loop, or even
            // beyond, in the corners and narrow lobes that the loop cuts).
            val pocketInset = outerOffset + (if (outerRings.isEmpty()) 0 else max(0,outerPasses-1))*spacing + overlapInset
            val rounding = RecordedBoundary.ringRoundingM(robot)
            val pocketArea = if (outerRings.isEmpty()) area.buffer(-pocketInset,16)
                else area.buffer(-(rounding + pocketInset),16).buffer(rounding,16).intersection(area)
            val safePockets = physicalFree.buffer(-(robot.turnClearanceM + 0.01),16).intersection(pocketArea)
            coverage = coverage.union(safePockets)
            // Pass ends in the middle of the outer loop band: never closer than half a cut to its trace, even where the loop
            // was moved inwards to manoeuvre.
            if (outerRings.isNotEmpty()) coverage = coverage.difference(org.locationtech.jts.operation.union.UnaryUnionOp.union(
                outerRings.map { ring -> factory.createLineString(ring.map { Coordinate(it.x,it.y) }.toTypedArray()) }).buffer(overlapInset - 0.005,16))
            // Pivot at a pass end facing a no-go zone: the front corners sweep the pivot radius (43 cm), not just the bumper
            // (34.5 cm). The center stays at this radius plus the set boundary margin, so that the whole pivot keeps the margin
            // (2026-10-15: pass ends at 60.6 cm, corners at 17.6 cm from a no-go zone set to 25 cm, contacts).
            val noGoPivotInset = robot.turnClearanceM + outerOffsetM + 0.01
            holes.forEach { hole ->
                val exclusion = polygon(hole)
                val loops = noGoRings.filter { polygon(it).covers(exclusion) }
                val reserved = if (loops.isEmpty()) exclusion.buffer(bareInset,16)
                    else org.locationtech.jts.operation.union.UnaryUnionOp.union(loops.map(::polygon)).buffer(noGoOverlapInset,16)
                coverage = coverage.difference(reserved.union(exclusion.buffer(noGoPivotInset,16)))
            }
            obstacles.forEach { coverage = coverage.difference(polygon(plannerHole(it,clearance)).buffer(clearance+0.02,16)) }
            // Recorded boundary = side of the chassis: pass ends at the pivot radius of the physical area (edges, no-go zones,
            // obstacles), the chassis pivots there without leaving the zone or entering a no-go zone.
            coverage = coverage.intersection(physicalFree.buffer(-(robot.turnClearanceM + 0.02),16))
            require(!coverage.isEmpty) { tr("No coverage space left after the border loops", "Aucun espace de couverture après les tours de bordure") }
            inset = coverage
        }
        val navigableCoverage = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(inset.buffer(1e-7))
        // Shared by the orientations evaluated in parallel: concurrent table
        // (a possible double computation gives the same exact result).
        val safeSegments = java.util.concurrent.ConcurrentHashMap<Pair<Point, Point>, Boolean>()
        fun safe(a: Point, b: Point): Boolean {
            val key = if (a.x < b.x || (a.x == b.x && a.y <= b.y)) a to b else b to a
            return safeSegments.getOrPut(key) {
                if (!recordedBoundary) Geometry.safe(a,b,zone,allHoles,clearance)
                else navigableCoverage.covers(factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))) &&
                    envelope.pathCollision(listOf(a,b)) == null
            }
        }
        fun valid(run: List<Point>) = run.size >= 2 && run.zipWithNext().all { (a,b) -> safe(a,b) }
        /** Spiral piece: the checks of [valid] plus the chassis envelope along the whole path (pivots at its vertices
         * included), the surface read once for the whole line on a recorded boundary (thousands of short segments). */
        fun validChain(path: List<Point>): Boolean = path.size >= 2 &&
            (if (!recordedBoundary) path.zipWithNext().all { (a,b) -> safe(a,b) }
                else navigableCoverage.covers(factory.createLineString(path.map { Coordinate(it.x,it.y) }.toTypedArray()))) &&
            envelope.pathCollision(path) == null
        // U-turns: the candidates are checked like the joined passes (safe surface, chassis envelope); "roomy" the same
        // with the footprint [TurnOptimizer.RISK_MARGIN_M] further from the physical boundary.
        val roomyEnvelope by lazy { TrajectoryEnvelope(physicalFree.buffer(-TurnOptimizer.RISK_MARGIN_M, 16), robot) }
        val turnContext = TurnOptimizer.TurnContext(spacing, speedMps.coerceIn(0.05, 1.0),
            drivable = { path -> valid(path) && envelope.pathCollision(path) == null },
            roomy = { path -> roomyEnvelope.pathCollision(path) == null }, robot = robot, cutWidthM = max(spacing, cutWidthM))
        // Zamboni: the Lawn friendly objective (duration plus wear) whatever the toggle says, without the one-in-two order.
        val zamboni = mode == CoverageMode.ZAMBONI
        val centreSpiral = mode == CoverageMode.SPIRAL && spiralDirection == SpiralDirection.FROM_CENTRE
        val style = if (zamboni) TurnStyle.LAWN else turnStyle
        /** Best possible U-turn for [style] from the pass ending [aFrom] → [aEnd] into [run], checked as a whole with the
         * pass it leads to (safe surface, chassis envelope); null when none fits. */
        fun verifiedTurn(aFrom: Point, aEnd: Point, run: List<Point>): TurnPlan? =
            TurnOptimizer.candidates(aFrom, aEnd, run[0], run[1], turnContext)
                .filter { it.possible }.sortedBy { it.score(style) }.firstOrNull { plan ->
                    val joinedPath = listOf(aFrom) + plan.points + run.drop(1)
                    joinedPath.zipWithNext().all { (a,b) -> a.distance(b) < 1e-9 || safe(a,b) } && envelope.pathCollision(joinedPath) == null
                }
        // Zamboni: the same U-turn is asked by the zigzag ranking, the order search and the final join, from orientations
        // evaluated in parallel (concurrent table; a double computation gives the same result).
        val turns = java.util.concurrent.ConcurrentHashMap<Triple<Point, Point, List<Point>>, java.util.Optional<TurnPlan>>()
        fun sharedTurn(aFrom: Point, aEnd: Point, run: List<Point>): TurnPlan? =
            turns.getOrPut(Triple(aFrom, aEnd, run)) { java.util.Optional.ofNullable(verifiedTurn(aFrom, aEnd, run)) }.orElse(null)
        require((if (recordedBoundary) emptyList() else borders).all(::valid)) { tr("Unsafe border: increase the margin", "Bordure non sûre : augmenter la marge") }
        if (recordedBoundary) borders.forEach { run ->
            run.zipWithNext().forEach { (a,b) ->
                val segment = factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))
                require(area.covers(segment) && holes.none { polygon(it).intersects(segment) }) { tr("Border outside the allowed recorded passage", "Bordure hors du passage enregistré autorisé") }
                require(obstacles.none { disc -> Geometry.distance(Point(disc.x,disc.y),a,b) < disc.radiusM }) { tr("Local obstacle on the border", "Obstacle local sur la bordure") }
            }
        }
        borders.forEach { run ->
            val firstHeading = Math.toDegrees(atan2(run[1].y-run[0].y, run[1].x-run[0].x))
            envelope.requirePath(run, finalHeading = firstHeading)
        }
        if (tour) {
            require(borders.isNotEmpty() && skippedBorderLoops == 0) { tr("Incomplete boundary: no safe loop for this zone", "Contour incomplet : aucun tour sûr pour cette zone") }
            return Preview(emptyList(), borders.sumOf { run -> run.zipWithNext().sumOf { (a,b) -> a.distance(b) } },
                0, 0, mode, borders, outer, 0, startWithBorders=true, borderKinds=List(outer) { false },borderAnchors=if(recordedBoundary) borders.map { it.first() } else emptyList())
        }
        data class SweepLine(val path: List<Point>, val cell: Int)
        fun sweep(angle: Int, phase: Double = sweepOptions?.phase ?: 0.5): List<SweepLine> {
            val radians = Math.toRadians(angle.toDouble())
            val rotated = AffineTransformation.rotationInstance(-radians).transform(inset)
            val inverse = AffineTransformation.rotationInstance(radians)
            if (sweepOptions?.decompose != null) {
                return sweepOptions.decompose.invoke(rotated).flatMapIndexed { cell, geometry ->
                    val bounds=geometry.envelopeInternal
                    val count=max(1,ceil(bounds.height/spacing).toInt())
                    (0 until count).flatMap { row ->
                        val y=bounds.minY+(row+phase)*bounds.height/count
                        val intersection=clip(geometry,factory.createLineString(arrayOf(Coordinate(bounds.minX-1,y),Coordinate(bounds.maxX+1,y))))
                        parts(intersection).filterIsInstance<LineString>().filter { it.length>0.01 }.sortedBy { it.envelopeInternal.minX }.map { segment ->
                            val path=points(inverse.transform(segment) as LineString).let { if(row%2==0) it else it.reversed() }
                            require(valid(path)) { tr("Unsafe cell segment", "Segment de cellule non sûr") }
                            SweepLine(path,angle*100000+cell)
                        }
                    }
                }
            }
            val box = rotated.envelopeInternal
            val rows = max(1,ceil(box.height/spacing).toInt())
            val step = box.height/rows
            data class Interval(val low:Double,val high:Double,val cell:Int)
            val cells=mutableListOf<MutableList<List<Point>>>()
            var previous=emptyList<Interval>()
            for (row in 0 until rows) {
                val y=box.minY+(row+phase)*step
                val cut=clip(rotated,factory.createLineString(arrayOf(Coordinate(box.minX-1,y),Coordinate(box.maxX+1,y))))
                val segments=parts(cut).filterIsInstance<LineString>().filter { it.length>0.01 }.sortedBy { it.envelopeInternal.minX }
                val matches=segments.map { segment -> previous.indices.filter { k ->
                    min(segment.envelopeInternal.maxX,previous[k].high)-max(segment.envelopeInternal.minX,previous[k].low)>1e-7 } }
                val next=segments.mapIndexed { index,segment ->
                    val parents=matches[index]
                    val cell=if(parents.size==1 && matches.count { parents[0] in it }==1) previous[parents[0]].cell
                        else cells.size.also { cells.add(mutableListOf()) }
                    cells[cell].add(points(inverse.transform(segment) as LineString))
                    Interval(segment.envelopeInternal.minX,segment.envelopeInternal.maxX,cell)
                }
                previous=next
            }
            return cells.flatMapIndexed { cell, paths -> paths.mapIndexed { row,path ->
                val run=if(row%2==0) path else path.reversed()
                require(valid(run)) { tr("Unsafe coverage segment", "Segment de couverture non sûr") }
                SweepLine(run,angle*100000+cell)
            } }
        }
        /**
         * Optimized mode: bands of the [angle] sweep; a band changes orientation only if it clearly saves U-turns (extra link
         * included). Bands kept at the base orientation and adjacent in their passage keep a continuous zigzag.
         * Null if no band gains by changing (the simple candidate is then identical).
         */
        fun mixed(angle: Int): List<SweepLine>? {
            val radians = Math.toRadians(angle.toDouble())
            val rotated = AffineTransformation.rotationInstance(-radians).transform(inset)
            val inverse = AffineTransformation.rotationInstance(radians)
            val layout = MixedSweep.layout(rotated, spacing, sweepOptions?.phase ?: 0.5, max(1.5, 4 * spacing))
            val strips = layout.strips
            fun original(row: MixedSweep.Row) = points(inverse.transform(factory.createLineString(
                arrayOf(Coordinate(row.low, row.y), Coordinate(row.high, row.y)))) as LineString)
            // All the orientations of the computation: a band slightly overlapping its neighbour does not always have its own
            // axis among its edges or its minimum width.
            val searched = (0 until 180 step 15).toList() + sweepOptions?.angles.orEmpty()
            val chained = BooleanArray(strips.size)
            strips.forEachIndexed { i, strip -> strip.chainParent?.let { chained[i] = true; chained[it] = true } }
            val choices = strips.mapIndexed { i, strip ->
                if (strip.rows.size < MixedSweep.MIN_TURNS_SAVED + 1) return@mapIndexed null
                val cell = inverse.transform(MixedSweep.area(strip, layout.step, rotated))
                // A reoriented crumb gains nothing and disorganizes the order of the neighbouring cells.
                if (cell.isEmpty || cell.area < MixedSweep.MIN_AREA_M2) return@mapIndexed null
                var best: Pair<Int, List<List<List<Point>>>>? = null
                var bestCost = MixedSweep.cost(strip.rows.map(::original)) - if (chained[i]) MixedSweep.SWITCH_COST_M else 0.0
                for (alternative in MixedSweep.alternatives(cell, angle, searched)) {
                    val cells = try { MixedSweep.fill(cell, alternative, spacing) }
                        catch (e: org.locationtech.jts.geom.TopologyException) { continue }
                    if (cells.isEmpty()) continue
                    val value = cells.sumOf { MixedSweep.cost(it) } + (cells.size - 1) * MixedSweep.SWITCH_COST_M
                    val saved = strip.rows.size - cells.sumOf { it.size }
                    if (saved >= MixedSweep.MIN_TURNS_SAVED && value < bestCost && cells.all { it.all(::valid) }) {
                        best = alternative to cells; bestCost = value
                    }
                }
                best
            }
            // A single orientation overall: already evaluated as the simple candidate.
            if (choices.map { it?.first ?: angle }.distinct().size < 2) return null
            // Consecutive base bands in their passage: a single cell, like the simple sweep.
            val group = IntArray(strips.size)
            strips.forEachIndexed { i, strip ->
                group[i] = strip.chainParent?.takeIf { choices[i] == null && choices[it] == null }?.let { group[it] } ?: i
            }
            val lines = mutableListOf<SweepLine>()
            var nextId = 0
            for (first in strips.indices.filter { group[it] == it }) {
                val alternative = choices[first]
                if (alternative != null) {
                    alternative.second.forEach { cell ->
                        val id = angle * 100000 + nextId++
                        cell.forEachIndexed { row, path -> lines.add(SweepLine(if (row % 2 == 0) path else path.reversed(), id)) }
                    }
                } else {
                    val id = angle * 100000 + nextId++
                    strips.indices.filter { group[it] == first }.flatMap { strips[it].rows }.forEachIndexed { row, raw ->
                        val path = original(raw).let { if (row % 2 == 0) it else it.reversed() }
                        require(valid(path)) { tr("Unsafe coverage segment", "Segment de couverture non sûr") }
                        lines.add(SweepLine(path, id))
                    }
                }
            }
            return lines
        }
        /**
         * Passes of one cell joined by the U-turns of the Turn Optimizer (the joined path checked again as a whole, as
         * before); a pass that cannot be joined starts a new run. Cost: U-turn scores of [turnStyle], [BREAK_COST] per run.
         */
        fun join(cellLines: List<SweepLine>): Pair<List<MutableList<Point>>, Double> {
            val runs = mutableListOf<MutableList<Point>>()
            var cost = 0.0
            for (line in cellLines) {
                val run = line.path
                val last = runs.lastOrNull()
                val turn = if (last == null || last.size < 2 || run.size < 2) null
                    else if (zamboni) sharedTurn(last[last.size-2], last.last(), run) else verifiedTurn(last[last.size-2], last.last(), run)
                if (last != null && turn != null) {
                    last[last.size-1] = turn.points.first()
                    last.addAll(turn.points.drop(1)); last.addAll(run.drop(1))
                    cost += turn.score(style)
                } else { runs.add(run.toMutableList()); cost += BREAK_COST }
            }
            return runs to cost
        }
        // Zamboni order of each cell, by its passes in sweep order; the same cell driven from its other end reuses it backwards.
        val zamboniOrders = java.util.concurrent.ConcurrentHashMap<List<List<Point>>, java.util.Optional<List<List<Point>>>>()
        fun zamboniOrder(cellLines: List<SweepLine>): List<SweepLine>? {
            val mirrored = zamboniOrders[cellLines.reversed().map { it.path.reversed() }]
            if (mirrored != null) return mirrored.orElse(null)?.let { order -> order.reversed().map { cellLines[0].copy(path = it.reversed()) } }
            val order = ZamboniOrder.order(cellLines, spacing, TurnOptimizer.FOLLOW_RADIUS_M, { it.path }, { l, p -> l.copy(path = p) },
                { aFrom, aEnd, bStart, bTo -> sharedTurn(aFrom, aEnd, listOf(bStart, bTo))?.score(style) }, BREAK_COST, checkpoint)
            zamboniOrders[cellLines.map { it.path }] = java.util.Optional.ofNullable(order?.map { it.path })
            return order
        }
        /** [front]: Zamboni order in the regular cells ([ZamboniOrder]), the zigzag elsewhere or when it would cost runs.
         * [applied]: pattern recorded for the zone ([Preview.zoneModes]); the zigzag passes of the Spiral mode are its fallback. */
        fun route(lines: List<SweepLine>, angle: Int, phase: Double = sweepOptions?.phase ?: 0.5, front: Boolean = false,
                  applied: CoverageMode = if (mode == CoverageMode.SPIRAL) CoverageMode.LINES else mode,
                  /** Runs already checked as a whole with the chassis envelope (spiral chains). */
                  verified: Set<List<Point>> = emptySet()): Preview {
            // Borders stay separate from the coverage passes: they are counted in the meters (the robot does mow the
            // perimeter) but drawn apart.
            val joined = mutableListOf<MutableList<Point>>()
            val groups=mutableListOf<Int>()
            var connectors = 0
            var reordered = 0
            var i = 0
            while (i < lines.size) {
                var j = i
                while (j + 1 < lines.size && lines[j + 1].cell == lines[i].cell) j++
                val cellLines = lines.subList(i, j + 1)
                // Lawn friendly: passes one in two (wide enough for a rolling U-turn), kept only if it does not cost more
                // runs and U-turns than the zigzag in this cell (long links refused along curved boundaries).
                val zigzag = join(cellLines)
                val skipped = if (turnStyle != TurnStyle.LAWN || zamboni) null
                    else TurnOrder.skip(cellLines, spacing, TurnOptimizer.FOLLOW_RADIUS_M, { it.path }) { l, p -> l.copy(path = p) }
                        .takeIf { it != cellLines }?.let(::join)
                // Zamboni order kept unless it costs more runs or more than the zigzag order (also front-advancing) by the same
                // objective: the order search estimates its U-turns from untrimmed passes, the join drives the real ones.
                val ordered = if (!front) null else zamboniOrder(cellLines)?.let(::join)
                    ?.takeIf { it.first.size <= zigzag.first.size && it.second <= zigzag.second + 1e-9 }
                if (ordered != null) reordered++
                val (runs, _) = ordered ?: if (skipped != null && skipped.second < zigzag.second) skipped else zigzag
                runs.forEach { run -> joined.add(run); groups.add(lines[i].cell) }
                connectors += cellLines.size - runs.size
                i = j + 1
            }
            joined.forEach { if (it !in verified) envelope.requirePath(it) }
            val rowsMeters = joined.sumOf { it.zipWithNext().sumOf { (a,b)->a.distance(b) } }
            val borderMeters = borders.sumOf { it.zipWithNext().sumOf { (a,b)->a.distance(b) } }
            return Preview(joined,rowsMeters+borderMeters,angle,connectors,mode,borders,outer,inner,passes=lines.map { it.path },skippedBorderLoops=skippedBorderLoops,borderKinds=List(outer) { false }+List(inner) { true },runCellIds=groups,borderAnchors=if(recordedBoundary) borders.map { it.first() } else emptyList(),
                // One zone: its row phase and the pattern applied, aligned with the zone lists once merged ([MultiZonePlanner.combine]).
                zonePhases=listOf(phase),zoneModes=listOf(applied),
                // Zamboni: cells actually driven in their front-advancing order (none: the zone keeps the zigzag order).
                // Spiral from the centre: chains driven outwards, set by [outward] (0 for the zigzag of a fallback zone).
                zoneReorderedCells=if (zamboni) listOf(reordered) else if (centreSpiral) listOf(0) else emptyList())
        }
        /**
         * Spiral from the centre: the chains of [built] driven backwards, from their innermost ring out to their outer ring,
         * the last chain built first. [SpiralSweep.build] starts its first chain at the edge nearest to the base, so that this
         * chain, now driven last, ends at the edge near the base where the mission ends.
         *
         * Every reversed chain is checked again with [validChain] before use: the surface check reads the same segments
         * both ways, but the chassis envelope is not symmetric in general (a robot whose front and rear overhangs differ
         * sweeps another footprint when a straight is driven backwards, and its pivots at the vertices too). A chain refused
         * backwards keeps its inward direction (already checked by the build) and may be entered from either end, as in the
         * edge direction.
         *
         * Order ([Preview.outwardRuns], applied by the local order): a pocket within one cut and one spacing of the first
         * point of a reversed chain is its core (the centre its innermost ring leaves); the chain waits for its core and for
         * the chains that continue it inwards ([SpiralSweep.Result.parents]: the rings inside a split), so that every part
         * is mowed from its centre out. A chain, its core and the chains inside it share one cell (finished before another
         * block); the U-turns are joined before (a chain stays one run, never joined to a pocket pass). Other pockets
         * (sharp corners, thin lobes) keep their own cells, as in the edge direction.
         */
        fun outward(built: SpiralSweep.Result): Preview {
            val count = built.chains.size
            val reach = max(spacing, cutWidthM) + spacing
            // Chain k of the mission is chain count - 1 - k of the build.
            val chains = built.chains.asReversed().map { chain -> chain.asReversed().toList().takeIf(::validChain)?.let { it to true } ?: (chain to false) }
            fun parent(k: Int) = built.parents.getOrNull(count - 1 - k)?.let { count - 1 - it }
            fun root(k: Int): Int { var r = k; while (true) r = parent(r) ?: return r }
            val owners = built.pockets.map { pocket ->
                chains.indices.filter { chains[it].second }.map { k ->
                    val start = chains[k].first.first()
                    k to pocket.distance(factory.createPoint(Coordinate(start.x, start.y)))
                }.filter { it.second <= reach }.minByOrNull { it.second }?.first
            }
            val lines = mutableListOf<SweepLine>()
            val coreOf = HashMap<Int, Int>()
            var cell = count
            fun fill(pocket: Int, owner: Int?) {
                for (passes in SpiralSweep.fill(built.pockets[pocket], spacing, ::valid)) {
                    val id = cell++
                    owner?.let { coreOf[id] = it }
                    passes.forEachIndexed { row, raw -> lines.add(SweepLine(if (row % 2 == 0) raw else raw.reversed(), id)) }
                }
            }
            chains.forEachIndexed { k, (path, _) ->
                owners.indices.filter { owners[it] == k }.forEach { fill(it, k) }
                lines.add(SweepLine(path, k))
            }
            owners.indices.filter { owners[it] == null }.forEach { fill(it, null) }
            val routed = route(lines, 0, applied = CoverageMode.SPIRAL, verified = chains.map { it.first }.toHashSet())
            // Chain of each run: the chain itself or the chain whose core it mows.
            val chainOf = routed.runCellIds.map { if (it < count) it else coreOf[it] }
            val outward = routed.runCellIds.indices.filter { routed.runCellIds[it] < count && chains[routed.runCellIds[it]].second }.associateWith { i ->
                val k = routed.runCellIds[i]
                routed.runCellIds.indices.filter { j -> j != i && (routed.runCellIds[j] >= count && chainOf[j] == k ||
                    routed.runCellIds[j] < count && parent(routed.runCellIds[j]) == k) }.toSet()
            }
            return routed.copy(runCellIds = routed.runCellIds.indices.map { i -> chainOf[i]?.let(::root) ?: routed.runCellIds[i] },
                outwardRuns = outward, outwardReachM = reach, zoneReorderedCells = listOf(outward.size))
        }
        /**
         * Spiral: the chains of [SpiralSweep] (one cell each) then the zigzag passes of its pockets (one cell per pocket
         * part), every chain checked like the passes (safe surface and chassis envelope); null when it cannot be built.
         */
        fun spiral(): Pair<SpiralSweep.Result, Preview>? {
            val built = SpiralSweep.build(inset, spacing, max(spacing, cutWidthM), TurnOptimizer.FOLLOW_RADIUS_M, basePoint,
                ::validChain, checkpoint) ?: return null
            if (spiralDirection == SpiralDirection.FROM_CENTRE) return try { built to outward(built) } catch (e: IllegalArgumentException) { null }
            val lines = built.chains.mapIndexed { cell, path -> SweepLine(path, cell) }.toMutableList()
            var cell = built.chains.size
            for (pocket in built.pockets) for (passes in SpiralSweep.fill(pocket, spacing, ::valid)) {
                val id = cell++
                passes.forEachIndexed { row, raw -> lines.add(SweepLine(if (row % 2 == 0) raw else raw.reversed(), id)) }
            }
            return try { built to route(lines, 0, applied = CoverageMode.SPIRAL, verified = built.chains.toHashSet()) }
                catch (e: IllegalArgumentException) { null }
        }
        fun List<Point>.distinctConsecutive() = filterIndexed { i,p -> i == 0 || p.distance(this[i-1]) > 1e-8 }
        fun nearBase(preview: Preview): Preview {
            // Production finishes whole blocks: an entry in the middle of a zigzag must not leave its first half for a later
            // return.
            if (continuous) return preview
            // Spiral from the centre: its chains are entered at their centre end only, after their core; the planned order is
            // kept rather than entered in the middle of a chain.
            if (preview.outwardRuns.isNotEmpty()) return preview
            val base = basePoint ?: return preview
            data class Entry(val run: Int, val segment: Int, val point: Point, val distance: Double)
            val entries = (preview.runs+preview.borders).flatMapIndexed { index, run ->
                run.zipWithNext().mapIndexed { segment, (a,b) ->
                    val dx=b.x-a.x; val dy=b.y-a.y
                    val t=if (dx*dx+dy*dy < 1e-12) 0.0 else
                        (((base.x-a.x)*dx+(base.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                    val p=Point(a.x+t*dx,a.y+t*dy)
                    Entry(index,segment,p,p.distance(base))
                }
            }.sortedBy { it.distance }
            for (entry in entries) {
                if (entry.run >= preview.runs.size) {
                    val index = entry.run-preview.runs.size
                    val loop = preview.borders[index]
                    val rotated = (listOf(entry.point)+loop.drop(entry.segment+1)+loop.drop(1).take(entry.segment)+entry.point).distinctConsecutive()
                    val heading = Math.toDegrees(atan2(rotated[1].y-rotated[0].y,rotated[1].x-rotated[0].x))
                    if (envelope.pathCollision(rotated,finalHeading=heading) != null) continue
                    return preview.copy(borders=listOf(rotated)+preview.borders.filterIndexed { i,_ -> i != index },startWithBorders=true,
                        borderKinds=listOf(preview.borderKinds[index])+preview.borderKinds.filterIndexed { i,_ -> i != index })
                }
                val run = preview.runs[entry.run]
                val tail = (listOf(entry.point)+run.drop(entry.segment+1)).distinctConsecutive()
                val head = (run.take(entry.segment+1)+entry.point).distinctConsecutive()
                val first = if (tail.size >= 2) tail else run.reversed()
                if (envelope.pathCollision(first) != null) continue
                val remainder = if (tail.size >= 2 && head.size >= 2) listOf(head) else emptyList()
                val cell=preview.runCellIds.getOrNull(entry.run)
                val same=preview.runs.indices.filter { it!=entry.run && cell!=null && preview.runCellIds.getOrNull(it)==cell }
                val other=preview.runs.indices.filter { it!=entry.run && it !in same }
                return preview.copy(runs=listOf(first)+same.map { preview.runs[it] }+remainder+other.map { preview.runs[it] },
                    runCellIds=if(cell==null) emptyList() else listOf(cell)+same.map { cell }+remainder.map { cell }+other.map { preview.runCellIds[it] })
            }
            return preview
        }
        // Length + section penalty heuristic; not a guaranteed mathematical optimum.
        // Optimized mode without an evaluator: U-turns count, otherwise only length decides.
        // Lawn friendly: the wear of the U-turns weighs on the orientation too (longer passes, rolling U-turns).
        fun cost(p: Preview) = (candidateCost?.invoke(p) ?: (p.meters + p.runs.size*10 +
            if (mode == CoverageMode.OPTIMIZED) p.connectors * MixedSweep.TURN_COST_M else 0.0)) +
            if (style == TurnStyle.LAWN) p.runs.sumOf { TurnOptimizer.drive(it, speedMps).wear } * LAWN_WEAR_COST_M else 0.0
        // Each time a better orientation is found, the preview is published: the interface can draw the route as it is
        // refined instead of waiting for the end of the computation.
        var best: Preview? = null
        var bestCost = Double.POSITIVE_INFINITY
        val angles = sweepOptions?.angles ?: (0 until (if(mode==CoverageMode.CHECKERBOARD || mode==CoverageMode.SURVEY_GRID) 90 else 180) step 15).toList()
        // Independent orientations: generation, links and cost computed in parallel, then selection in the original order
        // (same ties, same errors, same progress).
        class Evaluated(val candidates: List<Pair<Preview,Double>>, val sweepFailure: IllegalArgumentException?,
                        val routeFailure: IllegalArgumentException? = null)
        // Candidates of one orientation: its sweep (both families for the checkerboard), both pass orders, both directions.
        fun candidates(angle: Int, phase: Double = sweepOptions?.phase ?: 0.5): List<Preview> {
            val first = sweep(angle, phase)
            val second = if(mode==CoverageMode.CHECKERBOARD || mode==CoverageMode.SURVEY_GRID) sweep(angle+90, phase) else emptyList()
            val orders = if(second.isEmpty()) listOf(first) else listOf(first+second,second+first)
            return orders.flatMap { order -> listOf(route(order,angle,phase),route(order.reversed().map { it.copy(path=it.path.reversed()) },angle,phase)) }
                .filter { it.runs.isNotEmpty() }
        }
        // Spiral: computed with the orientations (first task, the longest), they give its zigzag reference and fallback.
        val spiralMode = mode == CoverageMode.SPIRAL
        val extra = if (spiralMode) 1 else 0
        var spiralCandidate: Pair<SpiralSweep.Result, Preview>? = null
        val evaluated = fr.luba.poc.simulation.PreparationPool.map(angles.size + extra, {}, minParallel = 2) { slot ->
            if (slot < extra) { spiralCandidate = spiral(); return@map null }
            val angle = angles[slot - extra]
            val first: List<SweepLine>
            val second: List<SweepLine>
            try {
                first = sweep(angle)
                second = if(mode==CoverageMode.CHECKERBOARD || mode==CoverageMode.SURVEY_GRID) sweep(angle+90) else emptyList()
            } catch (e: IllegalArgumentException) { return@map Evaluated(emptyList(), e) }
            val orders = if(second.isEmpty()) listOf(first) else listOf(first+second,second+first)
            try {
                Evaluated(orders.flatMap { order -> listOf(route(order,angle),route(order.reversed().map { it.copy(path=it.path.reversed()) },angle)) }
                    .filter { it.runs.isNotEmpty() }.map { it to cost(it) }, null)
            } catch (e: IllegalArgumentException) { Evaluated(emptyList(), null, e) }
        }
        val ranked = mutableListOf<OrientationRule.Candidate<Preview>>()
        val zigzags = mutableListOf<Pair<Preview, Double>>()
        for (result in evaluated.filterNotNull()) {
            result.sweepFailure?.let { if(sweepOptions != null) throw it }
            result.routeFailure?.let { throw it }
            for ((candidate, value) in result.candidates) {
                observeCandidate?.invoke(candidate)
                if (zamboni) zigzags.add(candidate to value)
                if (orientationRule != null) ranked.add(OrientationRule.Candidate(candidate, candidate.angle, mode.orientationPeriod, value))
                if (best==null || value < bestCost) { best = candidate; bestCost=value; if (!spiralMode) onProgress(nearBase(candidate)) }
            }
        }
        if (spiralMode) {
            // The spiral is kept unless it sweeps more than 0.2 point less than the zigzag of reference (same measure as the
            // mission evaluation: 2 cm raster, blade width), over what the passes are there for (the coverage area widened by
            // half a cut); otherwise the zigzag, recorded as the pattern of the zone.
            val spiral = spiralCandidate
            val zigzag = best
            val target = try { free.intersection(inset.buffer(max(spacing, cutWidthM) / 2, 8)) } catch (e: org.locationtech.jts.geom.TopologyException) { free }
            fun covered(p: Preview) = fr.luba.poc.planning.RasterCoverage.measure(p.cuttingPaths.filter { it.size >= 2 }
                .map { fr.luba.poc.planning.MissionLeg(it, true) }, target, max(spacing, cutWidthM) / 2, 0.02).covered
            val spiralCovered = spiral?.let { covered(it.second) }
            val zigzagCovered = zigzag?.let(::covered)
            val kept = spiralCovered != null && (zigzagCovered == null || spiralCovered >= zigzagCovered - 0.002 * target.area)
            SpiralSweep.observer?.invoke(SpiralSweep.Decision(spiral?.first, spiralCovered, zigzagCovered, target.area, kept))
            if (kept) best = spiral!!.second
            best?.let { onProgress(nearBase(it)) }
        }
        requireNotNull(best) { tr("No route possible", "Aucun trajet possible") }
        if (orientationRule != null) {
            observeAutomatic?.invoke(best!!)
            // Far enough from the last mowing at a small extra cost; otherwise the same direction, rows shifted (a
            // sweep refused at the new phase keeps the centred rows).
            val chosen = orientationRule.pick(ranked)?.value ?: try {
                candidates(best!!.angle, orientationRule.phase).minByOrNull(::cost)
            } catch (e: IllegalArgumentException) { null }
            if (chosen != null && chosen !== best) { best = chosen; onProgress(nearBase(chosen)) }
        }
        if (zamboni) {
            // Zamboni: the orientations are ranked with the zigzag order; the passes of the 3 cheapest ones (the one kept by
            // "Change every session" or the manual angle alone) are then reordered cell by cell, in both directions, and the
            // cheapest wins. A failure keeps the zigzag order.
            val phase = best!!.zonePhases.singleOrNull() ?: sweepOptions?.phase ?: 0.5
            val tops = if (orientationRule != null) listOf(best!!.angle)
                else zigzags.sortedBy { it.second }.map { it.first.angle }.distinct().take(3)
            val fronts = fr.luba.poc.simulation.PreparationPool.map(tops.size, {}, minParallel = 2) { index ->
                val angle = tops[index]
                try {
                    val lines = sweep(angle, phase)
                    listOf(route(lines, angle, phase, front = true),
                        route(lines.reversed().map { it.copy(path = it.path.reversed()) }, angle, phase, front = true))
                        .filter { it.runs.isNotEmpty() }.map { it to cost(it) }
                } catch (e: IllegalArgumentException) { emptyList() }
            }
            fronts.flatten().minByOrNull { it.second }?.let { (candidate, _) -> best = candidate; onProgress(nearBase(candidate)) }
        }
        if (mode == CoverageMode.OPTIMIZED) {
            // Optimized mode: the kept zigzag, in which only the bands saving at least a few U-turns are reoriented.
            // Deterministic choice, then compared with the simple zigzag on the full mission. A failure keeps the zigzag.
            val angle = best!!.angle
            try {
                mixed(angle)?.let { lines ->
                    listOf(route(lines, angle), route(lines.reversed().map { it.copy(path = it.path.reversed()) }, angle))
                        .filter { it.runs.isNotEmpty() }.minByOrNull(::cost)
                }?.let { best = it; onProgress(nearBase(it)) }
            } catch (e: IllegalArgumentException) {
            } catch (e: org.locationtech.jts.geom.TopologyException) { }
        }
        return nearBase(best!!)
    }
}
