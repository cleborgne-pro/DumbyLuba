package fr.luba.poc.simulation

import fr.luba.poc.diagnostics.MapNavPlan
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.cos
import kotlin.math.sin
import org.locationtech.jts.geom.Geometry as Area

/**
 * Move to a point: path computed by the same navigation as mowing transits (recorded outline, chassis margin, no-go
 * zones and local obstacles, LUBA 2 footprint checked everywhere).
 *
 * Beyond the mapped zones ([plan] with `offMap`): a robot or a destination outside the navigable surface is joined by
 * a straight section from the nearest point of that surface. The section opens a corridor of ±[OFF_MAP_CORRIDOR_M]
 * that joins the surface of the planner, so the preview, the tracking checks (Fold or gateway) and the replanning
 * after a contact use the same surface. Nothing is known there apart from the no-go zones and the saved obstacles,
 * which the section keeps away from: it is shown dotted with a warning and stays under the user's supervision.
 */
object PointPlanner {
    /** Total length driven beyond the mapped surface for one move. */
    const val MAX_OFF_MAP_M = 30.0
    /** Half-width of the corridor opened around a section beyond the map (robot centre within ±0.64 m of its axis). */
    const val OFF_MAP_CORRIDOR_M = 0.9
    /** Shorter sections are not reported (geometric noise along the limits). */
    const val MIN_SECTION_M = 0.05
    /** Tolerance of the mapped surface: a route running along its limit is not "beyond" it. */
    private const val SURFACE_TOLERANCE_M = 0.02
    private val factory = GeometryFactory()

    /**
     * [base]: known station; a robot [charging] or inside its safety zone first leaves in a straight line to the link
     * point ([BaseDeparture], [MapNavPlan.departure]) and the path starts there. Routes never cross the base safety zone.
     */
    fun plan(map: RobotMap, robot: Point, target: Point, obstacles: List<LocalObstacle>, offMap: Boolean = false,
             base: MapBase? = null, charging: Boolean = false, accesses: List<List<Point>> = emptyList()): MapNavPlan {
        require(map.complete && !map.source.contains("DÉMONSTRATION", true)) { tr("A complete real map is required", "Carte réelle complète obligatoire") }
        requireInterpretedLines(map)
        require(listOf(robot.x, robot.y, target.x, target.y).all { it.isFinite() }) { tr("Invalid position", "Position invalide") }
        require(robot.distance(target) > fr.luba.poc.diagnostics.MapNavFollower.ARRIVAL_M) { tr("The robot is already at the requested point", "Le robot est déjà au point demandé") }
        require(!BaseDeparture.inSafetyZone(base, target)) { tr("Destination inside the base safety zone: use Recharge to return to the base",
            "Destination dans la zone de sécurité de la base : utiliser Recharger pour revenir à la base") }
        val departure = BaseDeparture.required(base, robot, charging)
        val start = departure?.approach ?: robot
        require(start.distance(target) > fr.luba.poc.diagnostics.MapNavFollower.ARRIVAL_M) { tr("The destination is the link point in front of the base: use Leave the station", "La destination est le point de liaison devant la base : utiliser Sortir de la station") }
        val dock = base?.takeIf { it.entryHeadingDegrees != null }
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        require(zones.isNotEmpty()) { tr("No mapped zone", "Aucune zone cartographiée") }
        if (!offMap) {
            require(zones.any { Geometry.inside(start, it.points) }) { tr("Place the robot inside a mapped zone", "Placer le robot dans une zone cartographiée") }
            require(zones.any { Geometry.inside(target, it.points) }) { tr("Choose a point inside a mapped zone", "Choisir un point dans une zone cartographiée") }
        }
        val passages = map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }
        val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
        val holes = holes(map, obstacles)
        val planner = fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes = zones + passages), margin, holes, accesses, base = dock, recordedBoundary = true)
        val surface = planner.navigableSurface
        val startSafe = planner.safe(start, start)
        val targetSafe = planner.safe(target, target)
        if (!offMap || (startSafe && targetSafe)) {
            require(startSafe) { robotOutside() }
            require(targetSafe) { tr("Destination too close to a limit or an exclusion", "Destination trop proche d’une limite ou d’une exclusion") }
            return build(zones, passages, holes, margin, planner, start, target, surface).copy(departure = departure)
        }
        val holeAreas = holes.map { polygon(it).let { area -> if (area.isValid) area else area.buffer(0.0) } }
        require(holeAreas.none { it.covers(point(start)) }) { tr("Robot inside a no-go zone or an obstacle: move it away with the joystick",
            "Robot dans une zone interdite ou un obstacle : l’éloigner au joystick") }
        require(holeAreas.none { it.covers(point(target)) }) { tr("Destination inside a no-go zone or an obstacle", "Destination dans une zone interdite ou un obstacle") }
        // Sections beyond the map: from the robot to the surface, from the surface to the destination, or straight from
        // the robot to the destination when both are outside. The shortest distance without a map is tried first.
        val starts: List<List<Point>?> = if (startSafe) listOf(null) else entries(surface, planner, start).map { listOf(start, it) }
        val targets: List<List<Point>?> = if (targetSafe) listOf(null) else entries(surface, planner, target).map { listOf(it, target) }
        val options = (starts.flatMap { s -> targets.map { t -> listOfNotNull(s, t) } } +
            (if (!startSafe && !targetSafe) listOf(listOf(listOf(start, target))) else emptyList()))
            .filter { it.isNotEmpty() && (startSafe || it.any { leg -> leg.first() == start }) && (targetSafe || it.any { leg -> leg.last() == target }) }
            .sortedBy { legs -> legs.sumOf { length(it) } }
        require(options.isNotEmpty()) { tr("No mapped surface reachable from this point", "Aucune surface cartographiée accessible depuis ce point") }
        val shortest = options.first().sumOf { length(it) }
        require(shortest <= MAX_OFF_MAP_M) {
            tr("Too far beyond the mapped zones: %.1f m without a map (maximum %.0f m)", "Trop loin au-delà des zones cartographiées : %.1f m hors carte (maximum %.0f m)")
                .format(fr.luba.poc.i18n.I18n.locale, shortest, MAX_OFF_MAP_M)
        }
        var failure: Exception? = null
        for (legs in options.filter { legs -> legs.sumOf { length(it) } <= MAX_OFF_MAP_M }) {
            if (legs.any { leg -> holeAreas.any { it.distance(line(leg)) < margin } }) {
                failure = IllegalArgumentException(tr("The section beyond the map passes too close to a no-go zone or an obstacle",
                    "Le tronçon hors carte passe trop près d’une zone interdite ou d’un obstacle"))
                continue
            }
            val corridors = legs.mapIndexed { i, leg -> MapShape(-1000L - i, 0, corridor(leg), tr("Beyond the map", "Hors carte")) }
            try {
                val routes = fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes = zones + corridors + passages), margin, holes, accesses, base = dock, recordedBoundary = true)
                require(routes.safe(start, start)) { robotOutside() }
                require(routes.safe(target, target)) { tr("Destination too close to a no-go zone or an obstacle", "Destination trop proche d’une zone interdite ou d’un obstacle") }
                return build(zones + corridors, passages, holes, margin, routes, start, target, surface).copy(departure = departure)
            } catch (e: IllegalArgumentException) { failure = e } catch (e: IllegalStateException) { failure = e }
        }
        throw failure ?: IllegalArgumentException(noPath())
    }

    /**
     * Surface of a move around the station: same zones, passages, accesses, exclusions and obstacles as [plan], with the
     * docking corridor; checks the straight exit from the base ([BaseDeparture]) when the path has none.
     */
    fun stationRoutes(map: RobotMap, obstacles: List<LocalObstacle>, base: MapBase, accesses: List<List<Point>> = emptyList()): TrajectoryPlanner =
        fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes = map.shapes.filter {
            (it.kind == ShapeKind.ZONE && it.points.size >= 3) || (it.kind == ShapeKind.PASSAGE && it.points.size >= 2) }),
            RobotEnvelope.LUBA2.halfWidthM + 0.05, holes(map, obstacles), accesses, base = base, recordedBoundary = true)

    /** An element the moves cannot avoid as a hole (virtual wall, outline of fewer than 3 points) refuses them. */
    private fun requireInterpretedLines(map: RobotMap) { map.movesRefusal()?.let { throw IllegalArgumentException(it) } }

    private fun holes(map: RobotMap, obstacles: List<LocalObstacle>): List<List<Point>> =
        map.shapes.filter { it.kind.avoidedByMoves }.map { it.points }.filter { it.size >= 3 } +
            obstacles.map { obstacle ->
                val r = obstacle.exclusionRadiusM / cos(Math.PI / 64)
                (0 until 64).map { i -> Point(obstacle.x + r * cos(i * 2 * Math.PI / 64), obstacle.y + r * sin(i * 2 * Math.PI / 64)) }
            }

    /** Parts of [points] beyond [surface] (navigable surface of the mapped zones), at least [MIN_SECTION_M] long. */
    fun offMapSections(surface: Area, points: List<Point>): List<List<Point>> {
        if (points.size < 2 || surface.isEmpty) return emptyList()
        return try {
            val outside = OverlayNGRobust.overlay(line(points), surface.buffer(SURFACE_TOLERANCE_M, 4), OverlayNG.DIFFERENCE)
            val merger = LineMerger()
            merger.add(outside)
            merger.mergedLineStrings.filterIsInstance<LineString>().filter { it.length >= MIN_SECTION_M }
                .map { s -> s.coordinates.map { Point(it.x, it.y) } }
        } catch (_: RuntimeException) { emptyList() }
    }

    /** Length of the trace kept behind a robot beyond the map for its back-off ([trailCorridors]). */
    const val TRAIL_CORRIDOR_M = 3.0

    /**
     * Back-off of a robot stopped beyond the mapped zones (move to a point beyond the map, self-check 25 or contact
     * there): corridors of ±[OFF_MAP_CORRIDOR_M] around the parts of the last [TRAIL_CORRIDOR_M] of its [trail] that lie
     * outside the zones and passages, as for the section of the move. That ground has just been driven over; the no-go
     * zones and obstacles stay excluded by the planner. Empty when the robot is not in such a corridor: inside the zones
     * the borders keep applying unchanged.
     */
    fun trailCorridors(map: RobotMap, robot: Point, trail: List<Point>): List<MapShape> {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        if (zones.isEmpty() || trail.size < 2 || !robot.x.isFinite() || !robot.y.isFinite()) return emptyList()
        val tail = mutableListOf(trail.last())
        var meters = 0.0
        for (p in trail.asReversed().drop(1)) {
            if (!p.x.isFinite() || !p.y.isFinite()) break
            meters += p.distance(tail.last())
            tail += p
            if (meters >= TRAIL_CORRIDOR_M) break
        }
        if (tail.size < 2) return emptyList()
        val passages = map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }
        val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
        return try {
            val mapped = UnaryUnionOp.union(zones.map { polygon(it.points).let { area -> if (area.isValid) area else area.buffer(0.0) } } +
                passages.map { line(it.points).buffer(margin + 0.4) })
            val sections = offMapSections(mapped, tail.asReversed())
            val here = point(robot)
            if (sections.none { line(it).distance(here) <= OFF_MAP_CORRIDOR_M }) emptyList()
            else sections.mapIndexed { i, s -> MapShape(-2000L - i, 0, corridor(s), tr("Trace beyond the map", "Trace hors carte")) }
        } catch (_: RuntimeException) { emptyList() }
    }

    /** Inner part of the zones and passages (last map), cached: [beyondMapped] is called on every joystick / move tick. */
    private var innerCache: Pair<List<MapShape>, org.locationtech.jts.geom.prep.PreparedGeometry?>? = null

    /**
     * True if the robot centre [p] stands beyond the zones and passages of [map], or within
     * [fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M] inside their limits (chassis across the border). False
     * without a complete real map, without zone, or for an invalid position.
     */
    fun beyondMapped(map: RobotMap, p: Point?): Boolean {
        if (p == null || !p.x.isFinite() || !p.y.isFinite() || !map.complete || map.source.contains("DÉMONSTRATION", true)) return false
        val shapes = map.shapes
        val inner = innerCache?.takeIf { it.first === shapes }?.second ?: run {
            val zones = shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
            val built = if (zones.isEmpty()) null else try {
                val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
                val mapped = UnaryUnionOp.union(zones.map { polygon(it.points).let { area -> if (area.isValid) area else area.buffer(0.0) } } +
                    shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }.map { line(it.points).buffer(margin + 0.4) })
                org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(mapped.buffer(-fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M))
            } catch (_: RuntimeException) { null }
            innerCache = shapes to built
            built
        } ?: return false
        return !inner.covers(point(p))
    }

    /** No-go zones of the last map widened by [fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M], cached like [innerCache]. */
    private var exclusionCache: Pair<List<MapShape>, org.locationtech.jts.geom.prep.PreparedGeometry?>? = null

    /**
     * True if the robot centre [p] stands inside a no-go zone of [map] or within
     * [fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M] of it (chassis across its edge). Same conditions as
     * [beyondMapped] on the map.
     */
    fun atExclusion(map: RobotMap, p: Point?): Boolean {
        if (p == null || !p.x.isFinite() || !p.y.isFinite() || !map.complete || map.source.contains("DÉMONSTRATION", true)) return false
        val shapes = map.shapes
        val widened = exclusionCache?.takeIf { it.first === shapes }?.second ?: run {
            val noGos = shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }
            val built = if (noGos.isEmpty()) null else try {
                org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(UnaryUnionOp.union(noGos.map { polygon(it.points).let { area -> if (area.isValid) area else area.buffer(0.0) } })
                    .buffer(fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M))
            } catch (_: RuntimeException) { null }
            exclusionCache = shapes to built
            built
        } ?: return false
        return widened.covers(point(p))
    }

    /** True if [p] lies beyond [surface] by more than [toleranceM]. */
    fun beyond(surface: Area, p: Point, toleranceM: Double = 0.1): Boolean =
        runCatching { surface.distance(point(p)) > toleranceM }.getOrDefault(false)

    /**
     * Exact outline to drive ([points], robot centre), without any computed bypass: same surface, holes and footprint
     * checks as a move to a point. Used to record a map element in the robot (sync with the Mammotion app), where a
     * detour would be drawn into the element. The station is not a routing exclusion here: [MapSyncPlanner] keeps the
     * outline away from it. Throws if a section leaves the safe surface or the chassis cannot pivot at a corner.
     */
    fun tracePlan(map: RobotMap, points: List<Point>, obstacles: List<LocalObstacle>, accesses: List<List<Point>> = emptyList(),
                  routes: TrajectoryPlanner? = null): MapNavPlan {
        require(points.size >= 2 && points.all { it.x.isFinite() && it.y.isFinite() }) { tr("Invalid outline", "Tracé invalide") }
        val planner = routes ?: traceRoutes(map, obstacles, accesses)
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val passages = map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }
        return build(zones, passages, holes(map, obstacles), RobotEnvelope.LUBA2.halfWidthM + 0.05, planner, points.first(), points.last(),
            planner.navigableSurface, preset = points)
    }

    /** Surface of [tracePlan], built once to check several candidate outlines ([TrajectoryPlanner.requirePath]). */
    fun traceRoutes(map: RobotMap, obstacles: List<LocalObstacle>, accesses: List<List<Point>> = emptyList()): TrajectoryPlanner {
        require(map.complete && !map.source.contains("DÉMONSTRATION", true)) { tr("A complete real map is required", "Carte réelle complète obligatoire") }
        requireInterpretedLines(map)
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        require(zones.isNotEmpty()) { tr("No mapped zone", "Aucune zone cartographiée") }
        val passages = map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }
        return fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes = zones + passages), RobotEnvelope.LUBA2.halfWidthM + 0.05,
            holes(map, obstacles), accesses, recordedBoundary = true)
    }

    private fun build(zones: List<MapShape>, passages: List<MapShape>, holes: List<List<Point>>, margin: Double,
                      routes: TrajectoryPlanner, start: Point, target: Point, mapped: Area, preset: List<Point>? = null): MapNavPlan {
        val points = preset ?: try { routes.between(start, target) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException(noPath(), e) }
        routes.requirePath(points)
        // Single outline for the tracking checks: union of the zones and passage corridors
        // (same corridors as the planner), holes and exclusions kept.
        val corridors = passages.map { path -> line(path.points).buffer(margin + 0.4) }
        val union = UnaryUnionOp.union(zones.map { polygon(it.points) } + corridors)
        val part = (0 until union.numGeometries).map { union.getGeometryN(it) }.filterIsInstance<Polygon>()
            .minByOrNull { it.distance(point(start)) } ?: error(tr("Robot zone not found", "Zone du robot introuvable"))
        val outline = part.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
        val enclaves = (0 until part.numInteriorRing).map { i -> part.getInteriorRingN(i).coordinates.dropLast(1).map { Point(it.x, it.y) } }
        return MapNavPlan(outline, holes + enclaves, points, emptyList(), emptyList(),
            points.indices.map { if (it == 0) tr("Start", "Départ") else if (it == points.lastIndex) "Destination" else tr("Bypass", "Contournement") }, routes,
            physicalGuard = true, mapped = mapped)
    }

    /** Entry points of the navigable surface nearest to [p], one per surface part (three nearest parts). */
    private fun entries(surface: Area, planner: TrajectoryPlanner, p: Point): List<Point> {
        val here = point(p)
        val parts = (0 until surface.numGeometries).map { surface.getGeometryN(it) }.filter { !it.isEmpty }
            .sortedBy { it.distance(here) }.take(3)
        return parts.mapNotNull { part ->
            val nearest = DistanceOp.nearestPoints(part, here)[0]
            val dx = nearest.x - p.x; val dy = nearest.y - p.y
            val d = kotlin.math.hypot(dx, dy)
            if (d < 1e-6) return@mapNotNull null
            // Slightly inside the surface, along the approach direction.
            listOf(0.05, 0.15, 0.3).map { push -> Point(nearest.x + dx / d * push, nearest.y + dy / d * push) }
                .firstOrNull { planner.safe(it, it) }
        }
    }

    private fun corridor(leg: List<Point>): List<Point> {
        val area = line(leg).buffer(OFF_MAP_CORRIDOR_M, 8) as Polygon
        return area.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
    }

    private fun length(leg: List<Point>) = leg.zipWithNext().sumOf { (a, b) -> a.distance(b) }
    private fun point(p: Point) = factory.createPoint(Coordinate(p.x, p.y))
    private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun polygon(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun robotOutside() = tr("Robot outside the navigable surface (too close to a limit or an exclusion): move it away with the joystick",
        "Robot hors de la surface navigable (trop près d’une limite ou d’une exclusion) : l’éloigner au joystick")
    private fun noPath() = tr("No safe path to this point (zones not connected by a recorded passage?)",
        "Aucun chemin sûr jusqu’à ce point (zones non reliées par un passage enregistré ?)")
}
