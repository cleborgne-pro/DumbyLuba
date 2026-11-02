package fr.luba.poc.simulation

import fr.luba.poc.diagnostics.MapNavPlan
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Navigation within the mapped surface, never a shortcut through a hole. */
class TrajectoryPlanner(private val map: RobotMap, private val margin: Double, private val holes: List<List<Point>>, private val accesses: List<List<Point>> = emptyList(), val robot: RobotEnvelope = RobotEnvelope.LUBA2, private val recordedBoundary: Boolean = false, private val limitCenter: Point? = null, private val limitRadius: Double? = null, private val curveSegments: Int = 8,
    /** Areas forbidden to routes (robot centre) but not to docking manoeuvres checked by the physical
     * envelope: safety zone around the base. */
    private val routingExclusions: List<List<Point>> = emptyList(),
    /** Docking corridor (base → approach point): the only segment allowed inside the safety zone. */
    private val dockLine: List<Point>? = null,
    /** Difficult areas (difficulty heatmap) that transits bypass when the detour stays reasonable
     * ([AVOID_DETOUR_FACTOR]); never an exclusion: the safe surface and the checks are unchanged. */
    private val avoidAreas: List<List<Point>> = emptyList(),
    /** DANGER boundary portions ([fr.luba.poc.map.BorderType.DANGER], polylines): transits keep the chassis at least
     * [DANGER_TRANSIT_MARGIN_M] away from them whenever the terrain allows it ([dangerClearance]); never an exclusion
     * of the safe surface, the passes and the checks are unchanged. */
    private val dangerLines: List<List<Point>> = emptyList()) {
    companion object {
        private val surfaceCache = object : LinkedHashMap<Any,Surfaces>(8,0.75f,true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any,Surfaces>?) = size > 6
        }
        /** Move to a point ([PointPlanner]); [offMap] lets the start or the destination lie beyond the mapped zones. */
        fun pointPlan(map: RobotMap, start: Point, target: Point, obstacles: List<LocalObstacle>,
                      offMap: Boolean = false, base: MapBase? = null, charging: Boolean = false,
                      accesses: List<List<Point>> = emptyList()): MapNavPlan =
            PointPlanner.plan(map, start, target, obstacles, offMap, base, charging, accesses)
        /**
         * Tracking tolerance required from detours computed during a mission: the chassis footprint stays this far from
         * the physical outline. The tracking (Fold or gateway) checks the footprint projected 25 cm or more ahead of the
         * robot on its measured heading, with a route deviation allowed up to 30 cm: a detour drawn right at the edge of
         * the safe surface stopped it ("Enveloppe robot hors du couloir sûr", 2026-10-14 at 14:17, 14 cm deviation).
         */
        const val FOLLOW_TOLERANCE_M = 0.15
        /** Largest angle between the last segment of a link and the pass it joins near the outline ([alignedArrival]). */
        const val ALIGNED_ARRIVAL_DEGREES = 25.0
        /** Extra length allowed for an aligned arrival ([alignedArrival]). */
        const val ALIGNED_ARRIVAL_EXTRA_M = 3.0
        /** Approach points of an aligned arrival: distance behind the target and angle to the pass axis. */
        private val ALIGNED_APPROACH_M = listOf(0.8, 1.2, 1.8)
        private val ALIGNED_APPROACH_ANGLES = listOf(0.0, 10.0, -10.0, 20.0, -20.0)
        /** Shorter last edge of a link before its approach point: dropped ([alignedArrival]). */
        private const val SHORT_TAIL_M = 0.15
        /** Detour accepted to avoid difficult areas: length ≤ factor × preferred path + [AVOID_DETOUR_M]. */
        const val AVOID_DETOUR_FACTOR = 1.5
        const val AVOID_DETOUR_M = 4.0
        /** Margin around the avoided difficult areas. */
        const val AVOID_MARGIN_M = 0.25
        /** Minimum distance between the chassis side and a DANGER boundary portion in transit (m). */
        const val DANGER_TRANSIT_MARGIN_M = 0.8
        /** Robot centre distance kept from a DANGER portion in transit: half a width plus [DANGER_TRANSIT_MARGIN_M]. */
        fun dangerCenterDistance(robot: RobotEnvelope = RobotEnvelope.LUBA2): Double = robot.halfWidthM + DANGER_TRANSIT_MARGIN_M
        fun withDock(map: RobotMap, margin: Double, holes: List<List<Point>>, accesses: List<List<Point>>, base: Point,
                     heading: Double?, distance: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2, recordedBoundary: Boolean = false,
                     avoidAreas: List<List<Point>> = emptyList(), dangerLines: List<List<Point>> = emptyList()): TrajectoryPlanner {
            val dock = heading?.let {
                val angle = Math.toRadians(it)
                listOf(base, Point(base.x-cos(angle)*distance, base.y-sin(angle)*distance))
            }
            // Base safety zone: no route crosses it or grazes the station; the robot only reaches the
            // base through the approach point, in front of it, then reverses into the corridor.
            val safety = heading?.let { baseSafetyZone(base, it, distance, robot) }
            return TrajectoryPlanner(map, margin, holes, accesses + listOfNotNull(dock), robot, recordedBoundary,
                routingExclusions = listOfNotNull(safety), dockLine = dock, avoidAreas = avoidAreas, dangerLines = dangerLines)
        }
        /** Safety zone radius: the pivot at the approach point stays just outside. */
        fun baseSafetyRadius(approachDistance: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): Double =
            (approachDistance - robot.turnClearanceM - 0.02).coerceAtLeast(0.0)
        /**
         * Disc around the station, forbidden to any route. It is only left and entered straight through the front
         * corridor (base ↔ approach point), never from the sides or the back.
         */
        fun baseSafetyZone(base: Point, entryHeading: Double, approachDistance: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): List<Point> {
            val r = baseSafetyRadius(approachDistance, robot) / cos(Math.PI / 48)
            return (0 until 48).map { i -> val a = i * 2 * Math.PI / 48; Point(base.x + r * cos(a), base.y + r * sin(a)) }
        }
    }
    /** Immutable terrain geometry: shared by identical planners
     * (computation, simulation preparation, control), never modified after construction.
     */
    internal class Surfaces(val physicalFree: org.locationtech.jts.geom.Geometry, val free: org.locationtech.jts.geom.Geometry,
                            private val robot: RobotEnvelope, private val margin: Double, private val curveSegments: Int,
                            /** Centre surface without the areas reserved for manoeuvres (docking corridor). */
                            val maneuverFree: org.locationtech.jts.geom.Geometry = free,
                            /** Drawn outline (zones, corridors, exclusions) to which the comfort distance applies. */
                            private val drawn: org.locationtech.jts.geom.Geometry? = null,
                            /** Union of the difficult areas to avoid in transit, or null. */
                            private val avoid: org.locationtech.jts.geom.Geometry? = null,
                            /** Band kept from the DANGER portions in transit (robot centre), or null. */
                            private val danger: org.locationtech.jts.geom.Geometry? = null) {
        val envelope = TrajectoryEnvelope(physicalFree, robot)
        /** Physical surface shrunk by the tracking tolerance ([FOLLOW_TOLERANCE_M]): check of tight detours. */
        val followEnvelope by lazy { TrajectoryEnvelope(physicalFree.buffer(-FOLLOW_TOLERANCE_M, curveSegments), robot) }
        /** Preferred transit surfaces: robot centre at least [BORDER_CLEARANCE_M] from the drawn outline
         * ([comfort]), then the same surface completed only by the bottlenecks too narrow for that distance
         * ([crossing]). Lazy computation; absent if the geometry does not allow it. */
        private val preferred by lazy { drawn?.let { PreferredArea.build(it,free) } }
        /** Distance to the drawn outline (zones, corridors, exclusions), indexed: choice of a bypass side. */
        val drawnDistance by lazy { drawn?.let { org.locationtech.jts.operation.distance.IndexedFacetDistance(it.boundary) } }
        val comfort: PreferredArea? get() = preferred?.first
        val crossing: PreferredArea? get() = preferred?.second
        private fun guard(area: PreferredArea?): PreferredArea? {
            val band = danger ?: return null
            val surface = area?.area ?: return null
            return try {
                val kept = surface.difference(band)
                if (kept.isEmpty) null else PreferredArea(kept)
            } catch (_: RuntimeException) { null }
        }
        /** Comfort and crossing without the DANGER band: first choices for transits, with no detour limit. */
        val guardedComfort: PreferredArea? by lazy { guard(comfort) }
        val guardedCrossing: PreferredArea? by lazy { guard(crossing) }
        /** Distance to the DANGER portions, indexed (null without DANGER portion). */
        val dangerDistance by lazy { danger?.let { org.locationtech.jts.operation.distance.IndexedFacetDistance(it.boundary) } }
        val dangerIndex by lazy { danger?.let { AreaIndex(it, 0.0) } }
        val speedZones by lazy { SpeedZones.of(free, comfort?.area, danger, avoid) }
        /** Comfort without the difficult areas ([avoid]): first choice for transits, if the detour stays reasonable. */
        val calm: PreferredArea? by lazy {
            val comfortArea = (guardedComfort ?: comfort)?.area ?: return@lazy null
            val zone = avoid ?: return@lazy null
            try {
                val area = comfortArea.difference(zone)
                if (area.isEmpty) null else PreferredArea(area)
            } catch (_: RuntimeException) { null }
        }
        val navigable = AreaIndex(free, 0.00001)
        val maneuverNavigable by lazy { if (maneuverFree === free) navigable else AreaIndex(maneuverFree, 0.00001) }
        val pivot by lazy { AreaIndex(physicalFree.buffer(-robot.turnClearanceM-0.01), 1e-9) }
        val components by lazy { (0 until free.numGeometries).map { AreaIndex(free.getGeometryN(it), 0.00001) } }
        val shortest by lazy { ShortestRoutes(free) { a,b -> if (a.distance(b) < 1e-9) navigable.covers(a) else navigable.covers(a,b) } }
        val turnRoutes by lazy {
            listOf(robot.turnClearanceM + 0.01, robot.turnClearanceM * 0.9,
                robot.turnClearanceM * 0.8, robot.turnClearanceM * 0.7, margin)
                .map { max(margin,it) }.distinct().mapNotNull { clearance ->
                    val area = physicalFree.buffer(-clearance,curveSegments).intersection(free)
                    if (area.isEmpty) return@mapNotNull null
                    val prepared = AreaIndex(area, 0.00001)
                    ShortestRoutes(area) { a,b -> if (a.distance(b) < 1e-9) prepared.covers(a) else prepared.covers(a,b) }
                }
        }
    }
    private data class SurfaceKey(val shapes: List<MapShape>, val margin: Double, val holes: List<List<Point>>,
        val accesses: List<List<Point>>, val robot: RobotEnvelope, val recordedBoundary: Boolean,
        val limitCenter: Point?, val limitRadius: Double?, val curveSegments: Int, val routingExclusions: List<List<Point>>,
        val avoidAreas: List<List<Point>>, val dangerLines: List<List<Point>>)
    private fun cachedSurfaces(key: SurfaceKey, build: () -> Surfaces): Surfaces {
        synchronized(surfaceCache) { surfaceCache[key] }?.let { return it }
        val built = build()
        return synchronized(surfaceCache) { surfaceCache.getOrPut(key) { built } }
    }

    // Logical query counter; atomic because the preparation queries in parallel.
    private val queryCount = AtomicLong()
    internal val geometryQueries: Long get() = queryCount.get()
    private val factory = GeometryFactory()
    private fun polygon(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private val surfaces = cachedSurfaces(SurfaceKey(map.shapes,margin,holes,accesses,robot,recordedBoundary,
        limitCenter,limitRadius,curveSegments,routingExclusions,avoidAreas,dangerLines)) { buildSurfaces() }
    private fun buildSurfaces(): Surfaces {
        require(margin.isFinite() && margin >= 0) { tr("Invalid margin", "Marge invalide") }
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        require(zones.isNotEmpty()) { tr("No mapped zone", "Aucune zone cartographiée") }
        val passages = map.shapes.filter { it.kind == ShapeKind.PASSAGE }.map { it.points }
        val corridors = (passages + accesses).filter { it.size >= 2 }.map { path ->
            factory.createLineString(path.map { Coordinate(it.x,it.y) }.toTypedArray()).buffer(margin + 0.4)
        }
        var area = UnaryUnionOp.union(zones.map { if (recordedBoundary) RecordedBoundary.physicalArea(it.points, robot) else polygon(it.points) } + corridors)
        val taughtNoGo = map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }.toSet()
        holes.filter { it.size >= 3 }.forEach {
            area = area.difference(if (recordedBoundary && it in taughtNoGo) RecordedBoundary.forbiddenArea(it,robot) else polygon(it))
        }
        val physicalFree = area
        // Drawn outline (outer side of the chassis for a recorded outline), exclusions subtracted; the centre
        // stays half a width from the physical surface.
        var drawn = physicalFree
        val free = (if (recordedBoundary) {
            val zoneShapes = map.shapes.filter { it.kind == ShapeKind.ZONE }.map { polygon(it.points) }
            val centerCorridors = (map.shapes.filter { it.kind == ShapeKind.PASSAGE }.map { it.points } + accesses)
                .filter { it.size >= 2 }.map { factory.createLineString(it.map { p -> Coordinate(p.x,p.y) }.toTypedArray()).buffer(margin + 0.4) }
            var centerArea = UnaryUnionOp.union(zoneShapes + centerCorridors)
            holes.filter { it.size >= 3 }.forEach { centerArea = centerArea.difference(polygon(it)) }
            drawn = centerArea
            // Centre at least half a width from the outlines (outer side of the chassis): strict drawn surface, without
            // the overhang tolerated in manoeuvres by the physical surface.
            var strict = UnaryUnionOp.union(zoneShapes + corridors)
            holes.filter { it.size >= 3 }.forEach { strict = strict.difference(polygon(it)) }
            strict.buffer(-RecordedBoundary.centerInsetM(robot)).intersection(physicalFree.buffer(-robot.halfWidthM)).intersection(centerArea)
        } else physicalFree.buffer(-margin,curveSegments)).let { free ->
            if(limitCenter!=null && limitRadius!=null) free.intersection(factory.createPoint(Coordinate(limitCenter.x,limitCenter.y)).buffer(limitRadius,32)) else free
        }.also { require(!it.isEmpty) { tr("Margin too large to travel", "Marge trop grande pour circuler") } }
        val routed = routingExclusions.filter { it.size >= 3 }.fold(free) { area, zone -> area.difference(polygon(zone)) }
        require(!routed.isEmpty) { tr("Base safety zone too large to travel", "Zone de sécurité de la base trop grande pour circuler") }
        val avoid = avoidAreas.filter { it.size >= 3 }.takeIf { it.isNotEmpty() }?.let { areas ->
            // Margin: a transit does not run along the edge of a difficult area.
            runCatching { UnaryUnionOp.union(areas.map { polygon(it).buffer(AVOID_MARGIN_M, 4) }) }.getOrNull()?.takeIf { !it.isEmpty }
        }
        val danger = dangerLines.filter { it.size >= 2 }.takeIf { it.isNotEmpty() }?.let { lines ->
            runCatching { UnaryUnionOp.union(lines.map { line ->
                factory.createLineString(line.map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(dangerCenterDistance(robot), 8)
            }) }.getOrNull()?.takeIf { !it.isEmpty }
        }
        return Surfaces(physicalFree,routed,robot,margin,curveSegments,free,drawn,avoid,danger)
    }
    /**
     * Same terrain, same settings (margin, accesses, base, safety zone), with extra exclusions: obstacles discovered
     * during a mission. Independent planner, surfaces recomputed once.
     */
    fun withHoles(extra: List<List<Point>>): TrajectoryPlanner = if (extra.isEmpty()) this else
        TrajectoryPlanner(map, margin, holes + extra, accesses, robot, recordedBoundary, limitCenter, limitRadius,
            curveSegments, routingExclusions, dockLine, avoidAreas, dangerLines)
    private val physicalFree get() = surfaces.physicalFree
    private val envelope get() = surfaces.envelope
    private val free get() = surfaces.free
    /** Surface allowed to the robot centre (zones, corridors, exclusions and reserves applied). */
    val navigableSurface: org.locationtech.jts.geom.Geometry get() = free
    // Same tolerance as the former prepared free.buffer(1e-5), without a JTS allocation per query.
    private val navigable get() = surfaces.navigable
    // Only the reused polyline validations, not the visibility searches
    // on often unique segments. The terrain stays fixed here.
    private val preparedSegmentChecks = object : LinkedHashMap<Pair<Point,Point>,Boolean>(256,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Point,Point>,Boolean>?) = size>4096
    }
    /**
     * Distance from [p] to the drawn outline of this planner (zone borders, corridors, exclusions and holes), or +∞
     * without outline. On the planner of the prepared mission, the discovered obstacles are not included.
     */
    fun borderClearance(p: Point): Double {
        val index = surfaces.drawnDistance ?: return Double.POSITIVE_INFINITY
        return index.distance(factory.createPoint(Coordinate(p.x, p.y)))
    }
    /**
     * Distance from the robot centre at [p] to the nearest DANGER portion minus the kept distance
     * ([dangerCenterDistance]): negative inside the band, +∞ without DANGER portion.
     */
    fun dangerClearance(p: Point): Double {
        val index = surfaces.dangerDistance ?: return Double.POSITIVE_INFINITY
        val d = index.distance(factory.createPoint(Coordinate(p.x, p.y)))
        return if (surfaces.dangerIndex?.covers(p) == true) -d else d
    }
    fun safe(a: Point, b: Point): Boolean {
        queryCount.incrementAndGet()
        return if(a.distance(b)<1e-9) navigable.covers(a) else navigable.covers(a,b)
    }
    /**
     * Check independent of the computation: first segment of a route entering the base safety zone outside the front
     * corridor (base ↔ approach axis), or null. The tolerated radius is reduced by 3 cm for the polygonal approximation
     * of the disc.
     */
    fun baseZoneViolation(path: List<Point>): Pair<Point,Point>? {
        val line = dockLine ?: return null
        val zone = routingExclusions.firstOrNull() ?: return null
        val radius = zone.maxOf { it.distance(line[0]) } * cos(Math.PI / 48) - 0.03
        return path.zipWithNext().firstOrNull { (a,b) -> a.distance(b) > 1e-9 && !onDockLine(a,b) && Geometry.distance(line[0],a,b) < radius }
    }
    /** Station, docking axis and safety zone radius (log). */
    val baseSafety: Triple<Point,Point,Double>? get() {
        val line = dockLine ?: return null
        val zone = routingExclusions.firstOrNull() ?: return null
        return Triple(line[0], line[1], zone.maxOf { it.distance(line[0]) } * cos(Math.PI / 48))
    }
    /** Segment lying on the docking corridor axis (station exit or entry). */
    private fun onDockLine(a: Point, b: Point): Boolean {
        val line = dockLine ?: return false
        return Geometry.distance(a, line[0], line[1]) <= 1e-6 && Geometry.distance(b, line[0], line[1]) <= 1e-6
    }
    /** Route segment check: routable surface, or docking corridor on its manoeuvre surface. */
    private fun segmentSafe(a: Point, b: Point): Boolean = safe(a,b) || (onDockLine(a,b) && maneuverSafe(a,b))
    /** Docking corridor: centre surface without the base safety zone (manoeuvre only). */
    fun maneuverSafe(a: Point, b: Point): Boolean {
        queryCount.incrementAndGet()
        val area = surfaces.maneuverNavigable
        return if(a.distance(b)<1e-9) area.covers(a) else area.covers(a,b)
    }
    private fun preparedSegmentSafe(a:Point,b:Point):Boolean {
        val key=a to b
        synchronized(preparedSegmentChecks) { preparedSegmentChecks[key] }?.let {
            queryCount.incrementAndGet() // Same number of logical queries, exact result already verified.
            return it
        }
        return segmentSafe(a,b).also { synchronized(preparedSegmentChecks) { preparedSegmentChecks[key]=it } }
    }

    // A link to the station requires a free pivot space in all
    // orientations. This filter avoids impossible link searches along the borders.
    private val pivotNavigable get() = surfaces.pivot
    fun supportsPivot(point: Point): Boolean {
        queryCount.incrementAndGet()
        return pivotNavigable.covers(point)
    }
    fun supportsShortcutSegment(a:Point,b:Point):Boolean {
        queryCount.incrementAndGet()
        if(passagePaths.isNotEmpty()) return zoneRoutes.supportsShortcutSegment(a,b)
        return pivotNavigable.covers(a,b)
    }
    fun supportsShortcutAnchor(point:Point):Boolean =
        if(passagePaths.isNotEmpty()) zoneRoutes.supportsPivot(point) else supportsPivot(point)

    /** Local links stay inside the zones; corridors keep their axis. */
    fun supportsLocalLink(a:Point,b:Point):Boolean {
        queryCount.incrementAndGet()
        return if(passagePaths.isNotEmpty()) zoneRoutes.safe(a,b) else safe(a,b)
    }

    /** The shortcut stays safe for any position on the segment and all headings. */
    fun supportsShortcut(a:Point,b:Point,target:Point):Boolean {
        queryCount.incrementAndGet()
        if(passagePaths.isNotEmpty()) return zoneRoutes.supportsShortcut(a,b,target)
        // Filled triangle (convex hull of the three points), degenerating into a segment if needed.
        return navigable.covers(a,b,target) && pivotNavigable.covers(a,b,target)
    }

    private val zoneComponents get() = surfaces.components
    private fun sameSurface(a:Point,b:Point):Boolean = zoneComponents.any { it.covers(a) && it.covers(b) }
    private fun surfaceId(point: Point): Int = zoneComponents.indexOfFirst { it.covers(point) }
    /** Component of the local link surface containing the point (-1 if none):
     * a covered segment or triangle never connects two distinct components. */
    fun localSurfaceId(point: Point): Int =
        if(passagePaths.isNotEmpty()) zoneRoutes.surfaceId(point) else surfaceId(point)

    private val passagePaths by lazy {
        // The docking axis is not a passage: extended by a chassis length like a passage,
        // it created an entry behind the station and a shortcut through the base.
        // Station exit and return go through viaDock, straight from the front.
        val axes = (map.shapes.filter { it.kind == ShapeKind.PASSAGE }.map { it.points } + accesses.filterNot { it == dockLine })
            .map { it.distinctConsecutive() }.filter { it.size >= 2 }
        // Crossing axes share a node, even without a common recorded vertex.
        val segments = axes.flatMap { it.zipWithNext() }
        axes.map { axis ->
            (axis.zipWithNext().flatMap { (a,b) ->
                val line = factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y)))
                val crossings = segments.flatMap { (c,d) ->
                    line.intersection(factory.createLineString(arrayOf(Coordinate(c.x,c.y),Coordinate(d.x,d.y))))
                        .coordinates.map { Point(it.x,it.y) }
                }
                (listOf(a,b) + crossings).distinct().sortedBy { it.distance(a) }
            }).distinctConsecutive()
        }.map { path ->
                // Step back one chassis length into the zone to pivot before the path entry.
                fun approach(endpoint: Point, neighbor: Point): Point? {
                    val length=endpoint.distance(neighbor)
                    val distance=robot.lengthM
                    val p=Point(endpoint.x+(endpoint.x-neighbor.x)*distance/length,
                        endpoint.y+(endpoint.y-neighbor.y)*distance/length)
                    return p.takeIf { zoneRoutes.safe(p,p) && safe(p,endpoint) }
                }
                listOfNotNull(approach(path.first(),path[1])) + path +
                    listOfNotNull(approach(path.last(),path[path.size-2]))
            }
    }
    private val entryNodes by lazy { passagePaths.flatMap { listOf(it.first(),it.last()) }.toSet() }
    private val zoneRoutes by lazy { TrajectoryPlanner(map.copy(shapes = map.shapes.filter { it.kind != ShapeKind.PASSAGE }), margin, holes, robot = robot, recordedBoundary = recordedBoundary, limitCenter=limitCenter,limitRadius=limitRadius,curveSegments=curveSegments,
        // Shortcuts and local links: same base safety zone as the routes.
        routingExclusions=routingExclusions, avoidAreas=avoidAreas, dangerLines=dangerLines) }
    private data class Link(val target: Int, val path: List<Point>)
    private val passageNodes by lazy { passagePaths.flatten().distinct() }
    private val passageGraph by lazy {
        val nodes = passageNodes
        val graph = Array(nodes.size) { mutableListOf<Link>() }
        fun connect(a: Int, b: Int, path: List<Point>) {
            graph[a].add(Link(b,path)); graph[b].add(Link(a,path.reversed()))
        }
        passagePaths.forEach { path -> path.zipWithNext().forEach { (a,b) ->
            if (safe(a,b)) connect(nodes.indexOf(a),nodes.indexOf(b),listOf(a,b))
        } }
        for (a in nodes.indices) for (b in a+1 until nodes.size) {
            if(nodes[a] in entryNodes && nodes[b] in entryNodes) zoneConnection(nodes[a],nodes[b])?.let { connect(a,b,it) }
        }
        graph
    }
    private val zoneConnections = object : LinkedHashMap<Pair<Point,Point>,List<Point>?>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Point,Point>,List<Point>?>?) = size > 4096
    }
    private fun zoneConnection(a: Point, b: Point): List<Point>? {
        val key=a to b
        synchronized(zoneConnections) { if(zoneConnections.containsKey(key)) return zoneConnections[key] }
        val path=if (!zoneRoutes.sameSurface(a,b)) null
            else try { zoneRoutes.between(a,b) } catch (_: IllegalArgumentException) { null }
        synchronized(zoneConnections) { zoneConnections[key]=path; zoneConnections[b to a]=path?.reversed() }
        return path
    }

    /** Same checks as [requirePath], without exception: for candidate trials. */
    fun pathSafe(path: List<Point>, initialHeading: Double? = null, finalHeading: Double? = null): Boolean {
        queryCount.incrementAndGet()
        if (path.isEmpty() || !path.all { it.x.isFinite() && it.y.isFinite() }) return false
        for (i in 0 until path.size-1) if (!preparedSegmentSafe(path[i],path[i+1])) return false
        return try { envelope.pathCollision(path, initialHeading, finalHeading) == null }
            catch (_: IllegalArgumentException) { false }
    }
    /**
     * [pathSafe] with the tracking tolerance: footprint (translations and pivots) at least [FOLLOW_TOLERANCE_M] from the
     * physical outline, so that a normal tracking deviation or its projection ahead of the robot stays on the surface.
     */
    fun pathTolerant(path: List<Point>, initialHeading: Double? = null, finalHeading: Double? = null): Boolean {
        queryCount.incrementAndGet()
        if (path.isEmpty() || !path.all { it.x.isFinite() && it.y.isFinite() }) return false
        return try { surfaces.followEnvelope.pathCollision(path, initialHeading, finalHeading) == null }
            catch (_: IllegalArgumentException) { false }
    }
    /** Free manoeuvre inside the zones; recorded axis mandatory in the passages. */
    fun requirePath(path: List<Point>, initialHeading: Double? = null, finalHeading: Double? = null) {
        queryCount.incrementAndGet()
        require(path.zipWithNext().all { (a,b) -> preparedSegmentSafe(a,b) }) { tr("Route outside the safe surface", "Trajet hors de la surface sûre") }
        envelope.requirePath(path, initialHeading, finalHeading)
    }
    /** LUBA 2 footprint entirely inside the physical surface (recorded outline, exclusions). */
    fun footprintSafe(pose: RobotPose): Boolean { queryCount.incrementAndGet(); return envelope.poseSafe(pose) }
    /** Straight escape of a chassis that may already overlap an edge ([TrajectoryEnvelope.leavesStraight]). */
    fun leavesStraight(from: Point, to: Point, heading: Double): Boolean { queryCount.incrementAndGet(); return envelope.leavesStraight(from, to, heading) }
    /** Chassis inside the free physical surface at [center], whatever the heading ([TrajectoryEnvelope.bodySafe]). */
    fun bodySafe(center: Point): Boolean = envelope.bodySafe(center)
    fun motionCollision(from: RobotPose, to: RobotPose): EnvelopeCollision? {
        queryCount.incrementAndGet()
        return envelope.motionCollision(from, to)
    }
    val speedZones: SpeedZones get() = surfaces.speedZones
    /** Free physical surface (centre + footprint): everything the on-board tracking needs for the envelope checks. */
    val physicalSurface: org.locationtech.jts.geom.Geometry get() = physicalFree

    // Immutable terrain: the same link without heading always gives the same result.
    // Synchronised caches (parallel preparation), computation outside the lock: a possible
    // double computation gives the same exact result.
    private inline fun <K:Any> LinkedHashMap<K,HeadingRoute>.memo(key: K, compute: () -> List<Point>): List<Point> {
        val result = synchronized(this) { this[key] } ?: (try { HeadingRoute(compute()) }
            catch (e: IllegalArgumentException) { HeadingRoute(null,e.message ?: tr("Link unreachable", "Raccord inaccessible")) })
            .also { synchronized(this) { this[key]=it } }
        return result.path ?: throw IllegalArgumentException(result.failure)
    }
    private val plainRoutes = object : LinkedHashMap<Pair<Point,Point>,HeadingRoute>(256,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Point,Point>,HeadingRoute>?) = size > 4096
    }
    private val plainCandidates = object : LinkedHashMap<Pair<Point,Point>,HeadingRoute>(256,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Point,Point>,HeadingRoute>?) = size > 4096
    }
    fun between(start: Point, end: Point): List<Point> {
        queryCount.incrementAndGet()
        viaDock(start,end,null,null)?.let { return it }
        return plainRoutes.memo(start to end) { plainBetween(start,end) }
    }
    /**
     * Station at the centre of its safety zone: it is only left and entered straight through the front corridor
     * (base ↔ approach point); the rest of the route avoids the whole zone.
     */
    private fun viaDock(start: Point, end: Point, initialHeading: Double?, finalHeading: Double?): List<Point>? {
        val line = dockLine ?: return null
        val base = line[0]; val approach = line[1]
        val atStart = start.distance(base) < 1e-6; val atEnd = end.distance(base) < 1e-6
        if (!atStart && !atEnd) return null
        if (atStart && atEnd) return listOf(start)
        // Nose facing outwards on the axis: exit going forward, return reversing.
        val outward = Math.toDegrees(atan2(approach.y-base.y,approach.x-base.x))
        if (atStart) return (listOf(base) + between(approach,end,outward,finalHeading)).distinctConsecutive()
        return (between(start,approach,initialHeading,outward) + base).distinctConsecutive()
    }
    private fun plainBetween(start: Point, end: Point): List<Point> {
        val candidate = candidateBetween(start,end)
        if (envelope.pathCollision(candidate) == null) return candidate
        // Inside a zone, look for an alternative reserving the swept radius in all orientations.
        // Passages must stay on their axis; no off-map bypass is invented.
        if (passagePaths.isEmpty()) {
            for (routes in turnRoutes) {
                val alternative = try { routes.between(start,end) }
                    catch (_: IllegalArgumentException) { continue }
                if (envelope.pathCollision(alternative) == null) {
                    requirePath(alternative)
                    return alternative
                }
            }
        }
        envelope.requirePath(candidate)
        return candidate
    }
    /** Heading-aware link: move the pivots into the free area. */
    private data class RouteKey(val start: Point, val end: Point, val initialHeading: Double?, val finalHeading: Double?)
    private data class HeadingRoute(val path:List<Point>?,val failure:String? = null)
    private val headingRoutes = object : LinkedHashMap<RouteKey,HeadingRoute>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RouteKey,HeadingRoute>?) = size > 4096
    }
    fun between(start: Point, end: Point, initialHeading: Double?, finalHeading: Double?): List<Point> {
        viaDock(start,end,initialHeading,finalHeading)?.let { return it }
        // The map and the footprint are immutable: an identical failure does not require
        // redoing all the pivot searches for each recharge point.
        return headingRoutes.memo(RouteKey(start,end,initialHeading,finalHeading)) { betweenWithHeading(start,end,initialHeading,finalHeading) }
    }
    private fun betweenWithHeading(start: Point, end: Point, initialHeading: Double?, finalHeading: Double?): List<Point> {
        queryCount.incrementAndGet()
        val direct = try { between(start,end) } catch (_: IllegalArgumentException) {
            try { candidateBetween(start,end) } catch (_: IllegalArgumentException) { null }
        }
        if (direct != null && envelope.pathCollision(direct,initialHeading,finalHeading) == null) return direct
        if(passagePaths.isNotEmpty() && (initialHeading!=null || finalHeading!=null)) {
            val headed=try { candidateBetween(start,end,initialHeading,finalHeading) }
                catch (_:IllegalArgumentException) { null }
            if(headed!=null && envelope.pathCollision(headed,initialHeading,finalHeading)==null) return headed
        }
        // A link inside the zone surface must be able to move its pivots away from the no-go zones.
        // The map passages must not impose their graph on this local link.
        if (passagePaths.isNotEmpty() && zoneRoutes.sameSurface(start,end)) {
            val local = try { zoneRoutes.between(start,end,initialHeading,finalHeading) }
                catch (_: IllegalArgumentException) { null }
            if (local != null) {
                requirePath(local,initialHeading,finalHeading)
                return local
            }
        }
        if(direct==null && !sameSurface(start,end)) throw IllegalArgumentException(missingConnection(start,end))
        fun staging(point: Point, heading: Double?, arrival: Boolean): List<Point> {
            if (heading == null) return listOf(point)
            return listOf(point) + listOf(0.6,1.0,1.5,2.0).flatMap { distance ->
                listOf(0.0,5.0,-5.0,10.0,-10.0,15.0,-15.0,30.0,-30.0,60.0,-60.0,90.0,-90.0).mapNotNull { angle ->
                    val rad = Math.toRadians(heading+angle)
                    val sign = if (arrival) -1 else 1
                    val p = Point(point.x+sign*distance*cos(rad),point.y+sign*distance*sin(rad))
                    val segment = if (arrival) listOf(p,point) else listOf(point,p)
                    p.takeIf { safe(point,p) && envelope.pathCollision(segment,
                        if (arrival) null else heading, if (arrival) heading else null) == null }
                }
            }
        }
        for (a in staging(start,initialHeading,false)) for (b in staging(end,finalHeading,true)) {
            val middle = try { between(a,b) } catch (_: IllegalArgumentException) { continue }
            val path = (listOf(start)+middle+end).withoutAlignedVertices()
            if (envelope.pathCollision(path,initialHeading,finalHeading) == null) {
                requirePath(path,initialHeading,finalHeading)
                return path
            }
        }
        require(direct != null) { missingConnection(start,end) }
        envelope.requirePath(direct,initialHeading,finalHeading)
        return direct
    }
    private fun missingConnection(start: Point, end: Point): String {
        fun location(p: Point): String = map.shapes.firstOrNull { it.kind==ShapeKind.ZONE && Geometry.inside(p,it.points) }
            ?.let { it.name.ifBlank { "Zone ${it.id}" } } ?: tr("base / access", "base / accès")
        val incomplete=map.shapes.count { it.kind==ShapeKind.PASSAGE && it.points.distinct().size<2 }
        val sameZone=map.shapes.any { it.kind==ShapeKind.ZONE && Geometry.inside(start,it.points) && Geometry.inside(end,it.points) }
        return tr("Link unreachable: ${location(start)} → ${location(end)}.", "Liaison inaccessible : ${location(start)} → ${location(end)}.") +
            if(sameZone) tr(" No safe local link: check the exclusions and the manoeuvring space.", " Aucun raccord local sûr : vérifier les exclusions et l’espace de manœuvre.")
            else if(incomplete>0) tr(" $incomplete passage(s) without a usable axis (single point). Reload the map or fill in the missing accesses in Options → Base / access.",
                " $incomplete passage(s) sans axe exploitable (un seul point). Rechargez la carte ou renseignez les accès manquants dans Options → Base / accès.")
            else tr(" Check the continuity of the passages, the exclusions and the manoeuvring space.", " Vérifiez la continuité des passages, les exclusions et l’espace de manœuvre.")
    }
    private fun candidateBetween(start: Point, end: Point, initialHeading:Double? = null, finalHeading:Double? = null): List<Point> {
        if (initialHeading==null && finalHeading==null) {
            return plainCandidates.memo(start to end) { searchBetween(start,end,null,null) }
        }
        return searchBetween(start,end,initialHeading,finalHeading)
    }
    private fun searchBetween(start: Point, end: Point, initialHeading:Double?, finalHeading:Double?): List<Point> {
        if (passagePaths.isEmpty()) return try { unconstrainedBetween(start,end) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException(missingConnection(start,end),failure) }
        require(safe(start,start) && safe(end,end)) { tr("Base or route outside the reachable surface", "Base ou trajet hors de la surface accessible") }
        val directZone = zoneConnection(start,end)
        val preferredEntries=if(initialHeading!=null || finalHeading!=null)
            candidateBetween(start,end).filter { it in entryNodes }.toSet() else emptySet()
        val nodes = (passageNodes + listOf(start,end)).toMutableList()
        val source = nodes.size-2; val target = nodes.size-1
        data class Access(val endpoint: Int, val projection: Int, val a: Point, val b: Point)
        val localAccesses = mutableListOf<Access>()
        // The station frame may be offset from the axis while staying in the mapped corridor.
        // Short checked link to the axis; the following routes always follow the polyline.
        for(j in listOf(source,target)) if(!zoneRoutes.safe(nodes[j],nodes[j])) {
            passagePaths.forEach { path -> path.zipWithNext().forEach { (a,b) ->
                val dx=b.x-a.x; val dy=b.y-a.y
                val t=(((nodes[j].x-a.x)*dx+(nodes[j].y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                val projection=Point(a.x+t*dx,a.y+t*dy)
                if(nodes[j].distance(projection)<=0.40001 && safe(nodes[j],projection)) {
                    val index=nodes.indexOf(projection).takeIf { it>=0 } ?: nodes.size.also { nodes.add(projection) }
                    localAccesses.add(Access(j,index,a,b))
                }
            } }
        }
        val graph = Array(nodes.size) { mutableListOf<Link>() }
        passageGraph.forEachIndexed { i, links -> graph[i].addAll(links) }
        fun connect(i: Int, j: Int, path: List<Point>) {
            graph[i].add(Link(j,path)); graph[j].add(Link(i,path.reversed()))
        }
        directZone?.let { connect(source,target,it) }
        localAccesses.forEach { access ->
            connect(access.endpoint,access.projection,listOf(nodes[access.endpoint],nodes[access.projection]))
            for(p in listOf(access.a,access.b)) if(safe(nodes[access.projection],p))
                connect(access.projection,nodes.indexOf(p),listOf(nodes[access.projection],p))
        }
        // Euclidean bound: a detour through this entry cannot beat the direct link
        // inside the zone; no need to search its access (shortest path result unchanged).
        val directLength = directZone?.zipWithNext()?.sumOf { (a,b) -> a.distance(b) } ?: Double.POSITIVE_INFINITY
        // Passage entry ↔ end accesses: evaluated lazily by the Dijkstra
        // (optimistic cost = Euclidean distance), computed only if they reach the head of the queue.
        val lazyAccess = Array(nodes.size) { mutableListOf<IntArray>() }
        for (i in passageNodes.indices) for (j in listOf(source,target)) {
            val useful = nodes[i].distance(start)+nodes[i].distance(end) <= directLength*(1+1e-12)+1e-9
            if(useful && nodes[i] in entryNodes && initialHeading==null && finalHeading==null) {
                lazyAccess[i].add(intArrayOf(j,i,j)); lazyAccess[j].add(intArrayOf(i,i,j))
            } else if (nodes[i].distance(nodes[j]) < 1e-8) connect(i,j,listOf(nodes[i],nodes[j]))
            if(nodes[i] in preferredEntries && zoneRoutes.sameSurface(nodes[i],nodes[j])) {
                val directions=passagePaths.flatMap { path ->
                    when(nodes[i]) {
                        path.first() -> listOf(path[1])
                        path.last() -> listOf(path[path.size-2])
                        else -> emptyList()
                    }
                }
                directions.forEach { neighbor ->
                    val axis=Math.toDegrees(atan2(neighbor.y-nodes[i].y,neighbor.x-nodes[i].x))
                    try {
                        if(j==source) connect(j,i,zoneRoutes.between(nodes[j],nodes[i],initialHeading,axis))
                        else connect(i,j,zoneRoutes.between(nodes[i],nodes[j],axis+180,finalHeading))
                    } catch (_:IllegalArgumentException) { /* no safe route between these nodes in this direction: no link */ }
                }
            }
        }
        // An interruption in the middle of a link must be able to restart in both directions along its axis.
        passagePaths.forEach { path -> path.zipWithNext().forEach { (a,b) ->
            val onSegment = listOf(source,target).filter { Geometry.distance(nodes[it],a,b)<1e-7 }
            onSegment.forEach { j ->
                for (p in listOf(a,b)) if(safe(nodes[j],p)) connect(j,nodes.indexOf(p),listOf(nodes[j],p))
            }
            if(onSegment.size==2 && safe(start,end)) connect(source,target,listOf(start,end))
        } }
        val distances = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        val previous = arrayOfNulls<Pair<Int,List<Point>>>(nodes.size)
        val resolved = HashMap<Long,List<Point>?>()
        fun resolve(i: Int, j: Int): List<Point>? {
            val key = i.toLong()*nodes.size+j
            if (resolved.containsKey(key)) return resolved[key]
            return (zoneConnection(nodes[i],nodes[j]) ?: if (nodes[i].distance(nodes[j]) < 1e-8) listOf(nodes[i],nodes[j]) else null)
                .also { resolved[key] = it }
        }
        // Queue entry: computed link, or deferred access (lazyI >= 0) at optimistic cost.
        class Entry(val node: Int, val cost: Double, val parent: Int, val lazyI: Int, val lazyJ: Int)
        val queue = PriorityQueue<Entry>(compareBy { it.cost })
        distances[source]=0.0; queue.add(Entry(source,0.0,-1,-1,-1))
        while(queue.isNotEmpty()) {
            val entry=queue.remove(); val i=entry.node
            if(entry.lazyI>=0) {
                // The actual cost is at least the optimistic one: useless if it can no longer improve.
                if(entry.cost>=distances[i]) continue
                val forward=resolve(entry.lazyI,entry.lazyJ) ?: continue
                val path=if(entry.parent==entry.lazyI) forward else forward.reversed()
                val next=distances[entry.parent]+path.zipWithNext().sumOf { (a,b)->a.distance(b) }
                if(next<distances[i]) {
                    distances[i]=next; previous[i]=entry.parent to path
                    queue.add(Entry(i,next,entry.parent,-1,-1))
                }
                continue
            }
            val cost=entry.cost
            if(cost>distances[i]) continue
            if(i==target) break
            graph[i].forEach { link ->
                val next=cost+link.path.zipWithNext().sumOf { (a,b)->a.distance(b) }
                if(next<distances[link.target]) {
                    distances[link.target]=next; previous[link.target]=i to link.path
                    queue.add(Entry(link.target,next,i,-1,-1))
                }
            }
            lazyAccess[i].forEach { (v,li,lj) ->
                val optimistic=cost+nodes[i].distance(nodes[v])
                if(optimistic<distances[v]) queue.add(Entry(v,optimistic,i,li,lj))
            }
        }
        require(distances[target].isFinite()) { missingConnection(start,end) }
        val parts=mutableListOf<List<Point>>(); var i=target
        while(i!=source) { val (parent,path)=requireNotNull(previous[i]); parts.add(path); i=parent }
        return parts.reversed().flatten().distinctConsecutive()
    }

    /** Traffic variation only inside the zones, after the shortest path computation.
     * Accesses, passages and ends stay fixed. 30 cm offset per available lane.
     */
    fun variedBetween(start: Point, end: Point, journey: Int, initialHeading: Double? = null, finalHeading: Double? = null): List<Point> {
        val path=between(start,end,initialHeading,finalHeading)
        if(journey<=0 || start.distance(end)<1e-8) return path
        val lane=journey-1
        val distance=0.3*(lane/2+1)
        val canonical=if(start.x<end.x || (start.x==end.x && start.y<end.y)) 1.0 else -1.0
        val preferred=canonical*if(lane%2==0) distance else -distance
        fun fixed(a: Point,b: Point): Boolean = passagePaths.any { passage ->
            passage.zipWithNext().any { (p,q) -> Geometry.distance(a,p,q)<1e-7 && Geometry.distance(b,p,q)<1e-7 }
        }
        fun shift(chunk: List<Point>, offset: Double): List<Point>? {
            val directions=chunk.zipWithNext().map { (a,b) ->
                val length=a.distance(b)
                Point((b.x-a.x)/length,(b.y-a.y)/length)
            }
            val normals=directions.map { Point(-it.y,it.x) }
            val first=chunk.first(); val last=chunk.last()
            val entry=min(1.0,first.distance(chunk[1])/3)
            val exit=min(1.0,last.distance(chunk[chunk.size-2])/3)
            val shifted=mutableListOf(first,
                Point(first.x+directions.first().x*entry+normals.first().x*offset,
                      first.y+directions.first().y*entry+normals.first().y*offset))
            for(i in 1 until chunk.size-1) {
                val a=normals[i-1]; val b=normals[i]
                val denominator=1+a.x*b.x+a.y*b.y
                if(denominator<0.2) return null
                shifted.add(Point(chunk[i].x+(a.x+b.x)*offset/denominator,
                                  chunk[i].y+(a.y+b.y)*offset/denominator))
            }
            shifted.add(Point(last.x-directions.last().x*exit+normals.last().x*offset,
                              last.y-directions.last().y*exit+normals.last().y*offset))
            shifted.add(last)
            val zone=if(passagePaths.isEmpty()) this else zoneRoutes
            if(!shifted.zipWithNext().all { (a,b) -> zone.safe(a,b) && safe(a,b) } || envelope.pathCollision(shifted) != null) return null
            val originalLength=chunk.zipWithNext().sumOf { (a,b) -> a.distance(b) }
            val length=shifted.zipWithNext().sumOf { (a,b) -> a.distance(b) }
            return shifted.takeIf { length<=originalLength+max(1.0,originalLength*0.05) }
        }
        val result=mutableListOf(path.first())
        var i=0
        while(i<path.size-1) {
            if(fixed(path[i],path[i+1])) { result.add(path[++i]); continue }
            val from=i
            while(i<path.size-1 && !fixed(path[i],path[i+1])) i++
            val chunk=path.subList(from,i+1)
            // If the preferred lane is blocked, try the other side at the same distance.
            val shifted=shift(chunk,preferred) ?: shift(chunk,-preferred) ?: chunk
            result.addAll(shifted.drop(1))
        }
        val varied = result.distinctConsecutive()
        return if (envelope.pathCollision(varied, initialHeading, finalHeading) == null) varied else path
    }

    // A full pivot reserve may cut a passage that is passable in translation.
    // Several insets are tried; each route is then still checked with the
    // rectangle and the pivot angles actually travelled.
    private val turnRoutes get() = surfaces.turnRoutes
    private val shortest get() = surfaces.shortest
    /** Lower bound of the length of any link produced by this planner: geodesic shortest
     * path of the centre in the navigable surface (exact visibility graph, without heading
     * or footprint). Infinite if no link exists in this surface.
     */
    fun distanceLowerBound(start: Point, end: Point): Double {
        queryCount.incrementAndGet()
        return try { shortest.between(start,end).zipWithNext().sumOf { (a,b) -> a.distance(b) } }
        catch (_: IllegalArgumentException) { Double.POSITIVE_INFINITY }
    }
    private fun unconstrainedBetween(start: Point, end: Point): List<Point> {
        require(safe(start,start) && safe(end,end)) { tr("Base or route outside the reachable surface: place the base inside a zone or draw its access.",
            "Base ou trajet hors de la surface accessible : placez la base dans une zone ou tracez son accès.") }
        return preferredBetween(start,end) ?: shortest.between(start,end)
    }
    /**
     * Transit away from the borders: perpendicular exit towards the comfort surface, shortest path at
     * [BORDER_CLEARANCE_M] from the outline, then perpendicular arrival. If the comfort surface does not connect the
     * ends, only the bottlenecks that are too narrow are added (crossing); otherwise (no choice) the shortest path of
     * the safe surface is kept. A short hop already safe stays direct: moving 50 cm away and back would double its
     * length without protecting anything.
     */
    private fun preferredBetween(start: Point, end: Point): List<Point>? {
        if (start.distance(end) <= SHORT_HOP_M && safe(start,end)) return null
        fun through(area: PreferredArea): List<Point>? {
            val a = area.attach(start) { p,q -> safe(p,q) } ?: return null
            val b = area.attach(end) { p,q -> safe(p,q) } ?: return null
            val middle = try { area.between(a,b) } catch (_: IllegalArgumentException) { return null }
            val path = (listOf(start) + middle + end).withoutAlignedVertices()
            return path.takeIf { it.zipWithNext().all { (p,q) -> safe(p,q) } }
        }
        // DANGER portions: the guarded surfaces come first whatever the detour; the usual ones only if they cannot
        // connect the ends (the robot then keeps to the shortest safe transit, unchanged).
        for (area in listOfNotNull(surfaces.guardedComfort, surfaces.guardedCrossing, surfaces.comfort, surfaces.crossing)) {
            val path = through(area) ?: continue
            // Difficult areas avoided if the detour stays reasonable (otherwise the usual path).
            if (avoidAreas.isNotEmpty() && (area === surfaces.comfort || area === surfaces.guardedComfort)) surfaces.calm?.let(::through)?.let { calm ->
                if (calm.length() <= AVOID_DETOUR_FACTOR * path.length() + AVOID_DETOUR_M) return calm
            }
            return path
        }
        return null
    }
    private fun List<Point>.length() = zipWithNext().sumOf { (a,b) -> a.distance(b) }

    /**
     * Link from the actual robot pose (start of a route, "Recharge"): the perpendicular exit towards the comfort surface
     * ([preferredBetween]) becomes a direct oblique link to a path vertex within [SHORT_HOP_M] along the route, if this
     * segment never brings the centre closer to the drawn outline than at the start and if the whole route passes the
     * same checks ([requirePath], headings included).
     *
     * Return to base of 2026-10-13 (15:16): robot 41.5 cm from a border, heading 23°, route opened by an 8.5 cm edge at
     * 124° then the rest at 33°: 100° pivot in place then 92° in the opposite direction for 8.5 cm travelled, robot
     * protection (self-check 25) during the first pivot and route interrupted. Without a perpendicular exit (robot
     * already in the comfort surface, or start far from the border), the route is unchanged.
     */
    fun directJoin(path: List<Point>, initialHeading: Double?, finalHeading: Double?): List<Point> {
        if (path.size < 3) return path
        val start = path[0]
        if (start.distance(path[1]) > BORDER_CLEARANCE_M) return path
        val comfort = surfaces.comfort ?: return path
        val startDeficit = comfort.deficit(start)
        if (startDeficit <= 1e-6) return path
        // The centre never gets closer to the outline than at the start (1 cm numerical tolerance).
        fun noCloser(b: Point): Boolean {
            val steps = max(1, ceil(start.distance(b) / 0.02).toInt())
            return (1..steps).all { i -> val t = i.toDouble() / steps
                comfort.deficit(Point(start.x + (b.x - start.x) * t, start.y + (b.y - start.y) * t)) <= startDeficit + 0.01 }
        }
        var along = start.distance(path[1])
        var best: List<Point>? = null
        for (k in 2 until path.size) {
            along += path[k - 1].distance(path[k])
            if (along > SHORT_HOP_M) break
            if (!safe(start, path[k]) || !noCloser(path[k])) continue
            val candidate = listOf(start) + path.drop(k)
            if (runCatching { requirePath(candidate, initialHeading, finalHeading) }.isSuccess) best = candidate
        }
        return best ?: path
    }

    /**
     * Arrival on a pass near the drawn outline along its axis. A link whose last segment meets the target more than
     * [ALIGNED_ARRIVAL_DEGREES] off [finalHeading], with the target closer to the outline than the comfort distance,
     * arrives nose first: the front (35 cm ahead of the reference point) ends up over the border and the entry pivot
     * is made right at the edge. 2026-10-25 at 15:42:36, Aire de jeux: link after a contact bypass meeting the border
     * pass at 74°, robot nose into the border, footprint outside the safe corridor ("Enveloppe robot hors du couloir
     * sûr"), same on the following resumes further on. The last segment is replaced by an approach point behind the
     * target, never closer to the outline than the target, from which the robot meets the pass within
     * [ALIGNED_ARRIVAL_DEGREES]: the shortest candidate keeping the tracking tolerance ([pathTolerant]), otherwise the
     * shortest one passing the usual checks, at most [ALIGNED_ARRIVAL_EXTRA_M] longer; the link is unchanged otherwise.
     */
    fun alignedArrival(path: List<Point>, initialHeading: Double?, finalHeading: Double?): List<Point> {
        if (finalHeading == null || path.size < 2) return path
        val end = path.last()
        val previous = path[path.size - 2]
        if (headingGap(Math.toDegrees(atan2(end.y - previous.y, end.x - previous.x)), finalHeading) <= ALIGNED_ARRIVAL_DEGREES) return path
        val comfort = surfaces.comfort ?: return path
        val endDeficit = comfort.deficit(end)
        if (endDeficit <= 1e-6) return path
        val limit = path.length() + ALIGNED_ARRIVAL_EXTRA_M
        var tolerant: List<Point>? = null
        var safeOnly: List<Point>? = null
        for (distance in ALIGNED_APPROACH_M) {
            for (angle in ALIGNED_APPROACH_ANGLES) {
                val rad = Math.toRadians(finalHeading + angle)
                val approach = Point(end.x - distance * cos(rad), end.y - distance * sin(rad))
                if (!safe(approach, end) || comfort.deficit(approach) > endDeficit + 0.01) continue
                // From the last vertex before the end first (keeps the start of the link), then from the start.
                for (from in listOf(path.size - 2, 0).distinct()) {
                    val head = try { between(path[from], approach) } catch (_: IllegalArgumentException) { continue }
                    val candidate = (path.take(from) + head.withoutShortTail() + end).withoutAlignedVertices()
                    val length = candidate.length()
                    if (length > limit || candidate.size < 2) continue
                    if ((tolerant?.length() ?: Double.POSITIVE_INFINITY) <= length) continue
                    if (!pathSafe(candidate, initialHeading, finalHeading)) continue
                    if (pathTolerant(candidate, initialHeading, finalHeading)) tolerant = candidate
                    else if (length < (safeOnly?.length() ?: Double.POSITIVE_INFINITY)) safeOnly = candidate
                }
            }
            // Searches bounded (a drift resume tries many entries): the nearest ring with a tolerant arrival wins.
            if (tolerant != null) break
        }
        return tolerant ?: safeOnly ?: path
    }
    /** Last segment [from] → [end] meets [finalHeading] within [ALIGNED_ARRIVAL_DEGREES], or [end] lies in the comfort surface. */
    fun arrivalAligned(from: Point, end: Point, finalHeading: Double): Boolean =
        from.distance(end) < 1e-6 || headingGap(Math.toDegrees(atan2(end.y - from.y, end.x - from.x)), finalHeading) <= ALIGNED_ARRIVAL_DEGREES ||
            (surfaces.comfort?.deficit(end) ?: 0.0) <= 1e-6
    private fun headingGap(a: Double, b: Double) = abs(((b - a) % 360 + 540) % 360 - 180)
    /** Vertex a few cm before the approach point dropped when the shortcut is safe: no stop and pivot on a stub edge. */
    private fun List<Point>.withoutShortTail(): List<Point> =
        if (size >= 3 && this[size - 2].distance(last()) < SHORT_TAIL_M && safe(this[size - 3], last())) dropLast(2) + last() else this

    /** Aligned vertices removed (exit along the route axis, intermediate support): no needless stop. */
    private fun List<Point>.withoutAlignedVertices(): List<Point> = distinctConsecutive().fold(mutableListOf()) { result, p ->
        if (result.size >= 2) {
            val a = result[result.size-2]; val b = result.last()
            val cross = (b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)
            val dot = (b.x-a.x)*(p.x-b.x)+(b.y-a.y)*(p.y-b.y)
            if (abs(cross) <= 1e-6*a.distance(p) && dot > 0) result.removeAt(result.lastIndex)
        }
        result.add(p); result
    }
    private fun List<Point>.distinctConsecutive(): List<Point> = fold(mutableListOf()) { result, point ->
        if (result.lastOrNull() != point) result.add(point)
        result
    }

}

/** [escape]: straight-line escape out of an obstacle reserve, validated against the physical obstacles (without
 * margin); its tracking checks use the same physical geometry. */
