package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.*
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import java.util.PriorityQueue

/** Navigation within the mapped surface, never a shortcut through a hole. */
class TrajectoryPlanner(private val map: RobotMap, private val margin: Double, private val holes: List<List<Point>>, private val accesses: List<List<Point>> = emptyList(), val robot: RobotEnvelope = RobotEnvelope.LUBA2, private val recordedBoundary: Boolean = false, private val limitCenter: Point? = null, private val limitRadius: Double? = null, private val curveSegments: Int = 8,
    /** Areas forbidden to routes (robot centre) but not to docking manoeuvres checked by the physical
     * envelope: safety zone around the base. */
    private val routingExclusions: List<List<Point>> = emptyList(),
    /** Docking corridor (base → approach point): the only segment allowed inside the safety zone. */
    private val dockLine: List<Point>? = null,
    /** Difficult areas (difficulty heatmap) that transits bypass when the detour stays reasonable
     * ([AVOID_DETOUR_FACTOR]); never an exclusion: the safe surface and the checks are unchanged. */
    private val avoidAreas: List<List<Point>> = emptyList()) {
    companion object {
        private val surfaceCache = object : LinkedHashMap<Any,Surfaces>(8,0.75f,true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any,Surfaces>?) = size > 6
        }
        /** Move to a point ([PointPlanner]); [offMap] lets the start or the destination lie beyond the mapped zones. */
        fun pointPlan(map: RobotMap, start: Point, target: Point, obstacles: List<fr.luba.poc.obstacles.LocalObstacle>,
                      offMap: Boolean = false, base: MapBase? = null, charging: Boolean = false,
                      accesses: List<List<Point>> = emptyList()): fr.luba.poc.diagnostics.MapNavPlan =
            PointPlanner.plan(map, start, target, obstacles, offMap, base, charging, accesses)
        /** Only the known docking corridor extends the mapped surfaces.
         * No arbitrary link to the nearest zone; exclusions stay subtracted.
         */
        /**
         * Tracking tolerance required from detours computed during a mission: the chassis footprint stays this far from
         * the physical outline. The tracking (Fold or gateway) checks the footprint projected 25 cm or more ahead of the
         * robot on its measured heading, with a route deviation allowed up to 30 cm: a detour drawn right at the edge of
         * the safe surface stopped it ("Enveloppe robot hors du couloir sûr", 2026-10-14 at 14:17, 14 cm deviation).
         */
        const val FOLLOW_TOLERANCE_M = 0.15
        /** Detour accepted to avoid difficult areas: length ≤ factor × preferred path + [AVOID_DETOUR_M]. */
        const val AVOID_DETOUR_FACTOR = 1.5
        const val AVOID_DETOUR_M = 4.0
        /** Margin around the avoided difficult areas. */
        const val AVOID_MARGIN_M = 0.25
        fun withDock(map: RobotMap, margin: Double, holes: List<List<Point>>, accesses: List<List<Point>>, base: Point,
                     heading: Double?, distance: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2, recordedBoundary: Boolean = false,
                     avoidAreas: List<List<Point>> = emptyList()): TrajectoryPlanner {
            val dock = heading?.let {
                val angle = Math.toRadians(it)
                listOf(base, Point(base.x-cos(angle)*distance, base.y-sin(angle)*distance))
            }
            // Base safety zone: no route crosses it or grazes the station; the robot only reaches the
            // base through the approach point, in front of it, then reverses into the corridor.
            val safety = heading?.let { baseSafetyZone(base, it, distance, robot) }
            return TrajectoryPlanner(map, margin, holes, accesses + listOfNotNull(dock), robot, recordedBoundary,
                routingExclusions = listOfNotNull(safety), dockLine = dock, avoidAreas = avoidAreas)
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
                            private val avoid: org.locationtech.jts.geom.Geometry? = null) {
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
        /** Comfort without the difficult areas ([avoid]): first choice for transits, if the detour stays reasonable. */
        val calm: PreferredArea? by lazy {
            val comfortArea = comfort?.area ?: return@lazy null
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
        val avoidAreas: List<List<Point>>)
    private fun cachedSurfaces(key: SurfaceKey, build: () -> Surfaces): Surfaces {
        synchronized(surfaceCache) { surfaceCache[key] }?.let { return it }
        val built = build()
        return synchronized(surfaceCache) { surfaceCache.getOrPut(key) { built } }
    }

    // Logical query counter; atomic because the preparation queries in parallel.
    private val queryCount = java.util.concurrent.atomic.AtomicLong()
    internal val geometryQueries: Long get() = queryCount.get()
    private val factory = GeometryFactory()
    private fun polygon(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private val surfaces = cachedSurfaces(SurfaceKey(map.shapes,margin,holes,accesses,robot,recordedBoundary,
        limitCenter,limitRadius,curveSegments,routingExclusions,avoidAreas)) { buildSurfaces() }
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
        return Surfaces(physicalFree,routed,robot,margin,curveSegments,free,drawn,avoid)
    }
    /**
     * Same terrain, same settings (margin, accesses, base, safety zone), with extra exclusions: obstacles discovered
     * during a mission. Independent planner, surfaces recomputed once.
     */
    fun withHoles(extra: List<List<Point>>): TrajectoryPlanner = if (extra.isEmpty()) this else
        TrajectoryPlanner(map, margin, holes + extra, accesses, robot, recordedBoundary, limitCenter, limitRadius,
            curveSegments, routingExclusions, dockLine, avoidAreas)
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
        routingExclusions=routingExclusions, avoidAreas=avoidAreas) }
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
                    } catch (_:IllegalArgumentException) { }
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
            val middle = try { area.routes.between(a,b) } catch (_: IllegalArgumentException) { return null }
            val path = (listOf(start) + middle + end).withoutAlignedVertices()
            return path.takeIf { it.zipWithNext().all { (p,q) -> safe(p,q) } }
        }
        for (area in listOfNotNull(surfaces.comfort, surfaces.crossing)) {
            val path = through(area) ?: continue
            // Difficult areas avoided if the detour stays reasonable (otherwise the usual path).
            if (avoidAreas.isNotEmpty() && area === surfaces.comfort) surfaces.calm?.let(::through)?.let { calm ->
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
/** [cutting]: planned mowing pass; [bladesOn]: blades running on the edge (passes, and links internal to a zone, see
 * [BladeContinuity]). [heightMm]: cutting height of the successive pass the edge belongs to
 * ([fr.luba.poc.map.CutPasses]); null: height of the task, or that of the previous range. */
data class SimEdge(val a: Point, val b: Point, val cutting: Boolean, val escape: Boolean = false, val bladesOn: Boolean = cutting,
                   val heightMm: Int? = null) { val meters get()=a.distance(b) }
data class SimConfig(val initialProgress: Double=0.0, val initialBattery: Double=100.0, val autonomyMinutes: Double=60.0,
                     val speedMps: Double=0.3, val returnPercent: Double=20.0, val resumePercent: Double=90.0, val chargeMinutes: Double=30.0, val turnDegreesPerSecond: Double=45.0,
                     val baseEntryHeadingDegrees: Double?=null, val baseApproachMeters: Double=1.5, val dockingSpeedMps: Double=0.1, val instantCharging: Boolean=false,
                     /** Battery aimed for on arrival by the dynamic return ([ReturnReserve]). */
                     val returnReservePercent: Double=ReturnReserve.DEFAULT_PERCENT.toDouble())
enum class SimPhase(private val labelEn: String, private val labelFr: String) {
    LEAVING_DOCK("Leaving the base · slow forward", "Sortie de base · marche avant lente"), DEPARTING("Start / resume", "Départ / reprise"),
    MOWING("Mowing", "Tonte"), RETURNING("Return to base", "Retour à la base"), ALIGNING("Alignment before recharge", "Alignement avant recharge"),
    DOCKING("Recharge return · reversing", "Retour recharge · marche arrière"), CHARGING("Recharge", "Recharge"),
    COMPLETE("Mowing finished · at the base", "Tonte terminée · à la base"), BLOCKED("Simulation blocked", "Simulation bloquée");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}
data class SimState(val position: Point, val battery: Double, val phase: SimPhase, val edge: Int=0, val offset: Double=0.0,
                    val covered: Double=0.0, val elapsed: Double=0.0, val cycles: Int=0, val completedReturn: Boolean=false,
                    val transit: List<Point> = emptyList(), val transitIndex: Int=0, val transitOffset: Double=0.0,
                    val log: List<String> = emptyList(), val trail: List<Point> = emptyList(), val heading: Double=0.0,
                    val resumeTransit: List<Point> = emptyList(), val signalSamples: List<SignalSample> = emptyList(), val surveyId: String = "", val surveyStartedAt: Long = 0,
                    /** End of mowing below the return threshold, without recharge ([FinishPolicy]). */
                    val finishing: Boolean = false,
                    /** While charging: points needed to finish from the base ([FinishPolicy]), computed once per recharge. */
                    val chargeNeed: Double? = null)

/** Deterministic local engine. No link with RobotBle or the commands. */
class MowingSimulation private constructor(val routes: TrajectoryPlanner, val base: Point, val preview: Preview, val map: RobotMap, val config: SimConfig, prepared: MowingSimulation?, bladeZones: List<List<Point>>?, onPreparationProgress:(String)->Unit = {}) {
    /** [bladeZones]: zones where the links keep the blades running; by default the zones requested by the preview
     * ([Preview.zoneIds]) in [map]. */
    constructor(routes: TrajectoryPlanner, base: Point, preview: Preview, map: RobotMap, config: SimConfig,
                bladeZones: List<List<Point>>? = null, onPreparationProgress:(String)->Unit = {}):
        this(routes,base,preview,map,config,null,bladeZones,onPreparationProgress)
    /** Continuous-blade zones requested at preparation (null: those of the preview): the resume prepares again with the same. */
    val bladeZones: List<List<Point>>? = prepared?.bladeZones ?: bladeZones
    val baseApproach: Point
    /** Direction of travel towards the station; the robot's nose points the opposite way while reversing. */
    val dockEntryHeading: Double
    private fun edgeHeading(edge: SimEdge) = Math.toDegrees(atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
    private fun calculateDeparture(target: Point, targetHeading: Double? = null): List<Point> =
        (listOf(base) + routes.between(baseApproach,target, (dockEntryHeading+180)%360, targetHeading)).also {
            routes.requirePath(it, initialHeading=(dockEntryHeading+180)%360, finalHeading=targetHeading)
        }
    private fun calculateReturn(start: Point, initialHeading: Double? = null): List<Point> =
        routes.between(start,baseApproach, initialHeading, (dockEntryHeading+180)%360).also {
            routes.requirePath(it, initialHeading=initialHeading, finalHeading=(dockEntryHeading+180)%360)
        }
    val edges: List<SimEdge>
    val total: Double
    private data class Checkpoint(val edge: Int, val departure: List<Point>, val returning: List<Point>)
    private val checkpoints: List<Checkpoint>
    private val nextCheckpoint: IntArray
    private val departureShortcuts: List<List<List<Point>?>>
    private val returnShortcuts: List<List<List<Point>?>>
    init {
        require(config.autonomyMinutes>0 && config.speedMps>0 && config.chargeMinutes>0 && config.turnDegreesPerSecond>0)
        if (prepared != null) {
            require(config.baseEntryHeadingDegrees == prepared.config.baseEntryHeadingDegrees &&
                config.baseApproachMeters == prepared.config.baseApproachMeters) { tr("Recompute the route after changing the base", "Recalculez le trajet après une modification de la base") }
            edges=prepared.edges; total=prepared.total
            baseApproach=prepared.baseApproach; dockEntryHeading=prepared.dockEntryHeading
            checkpoints=prepared.checkpoints; nextCheckpoint=prepared.nextCheckpoint
            departureShortcuts=prepared.departureShortcuts; returnShortcuts=prepared.returnShortcuts
        } else {
            // Links from the computation: reused only if they pass the same surface, chassis
            // and heading checks; otherwise full search.
            val planned=preview.links.orEmpty().filter { it.size>=2 }.groupBy { it.first() to it.last() }
            fun link(a:Point,b:Point,incoming:Double?,outgoing:Double?):List<Point> =
                planned[a to b]?.firstOrNull { path ->
                    routes.pathSafe(path,incoming,outgoing)
                } ?: routes.between(a,b,incoming,outgoing)
            val mission=requireNotNull(fr.luba.poc.planning.RoutePlanning.assemble(
                preview.cuttingPaths.filter { it.size>=2 },::link,
                progress={ index,total -> onPreparationProgress(tr("Checking the links · $index/$total", "Vérification des liaisons · $index/$total")) })) {
                tr("First create a preview with non-degenerate passes", "Créez d'abord un aperçu avec des passes non dégénérées")
            }
            // Survey: blades never running, links unchanged.
            val blades=if(preview.mode.isSurvey) mission.map { it.cutting } else BladeContinuity.legs(mission,
                bladeZones ?: map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in preview.zoneIds }.map { it.points })
            // Height passes: each cutting path carries the height of its pass, and so does the link preceding it
            // (height change while stopped, before entering it).
            val legHeights=if(preview.pathHeights.isEmpty()) List(mission.size) { null } else {
                val cuttingIndex=mission.runningFold(-1) { k,leg -> if(leg.cutting) k+1 else k }.drop(1)
                mission.indices.map { index ->
                    val path=if(mission[index].cutting) cuttingIndex[index] else cuttingIndex[index]+1
                    preview.pathHeights.getOrNull(path.coerceAtMost(preview.pathHeights.lastIndex))
                }
            }
            val all=mission.flatMapIndexed { index,leg -> leg.path.zipWithNext().mapNotNull { (a,b) ->
                require(routes.safe(a,b)) { tr("Route outside the safe surface", "Trajet hors de la surface sûre") }
                if(a.distance(b)>1e-8) SimEdge(a,b,leg.cutting,bladesOn=blades[index],heightMm=legHeights[index]) else null
            } }
            require(all.isNotEmpty()) { tr("First create a preview", "Créez d'abord un aperçu") }
            routes.requirePath(listOf(all.first().a) + all.map { it.b })
            routes.baseZoneViolation(listOf(all.first().a) + all.map { it.b })?.let { (a,b) ->
                throw IllegalArgumentException(tr("Route inside the base safety zone: (%.2f; %.2f) → (%.2f; %.2f)",
                    "Trajet dans la zone de sécurité de la base : (%.2f ; %.2f) → (%.2f ; %.2f)").format(a.x,a.y,b.x,b.y))
            }
            edges=all; total=edges.filter { it.cutting }.sumOf { it.meters }
            require(config.baseApproachMeters.isFinite() && config.baseApproachMeters in 1.0..1.5 &&
                config.dockingSpeedMps.isFinite() && config.dockingSpeedMps>0)
            config.baseEntryHeadingDegrees?.let { require(it.isFinite() && it in 0.0..360.0) }
            val candidates=config.baseEntryHeadingDegrees?.let { listOf(it) } ?: (
                routes.between(base,edges.first().a,null,edgeHeading(edges.first())).zipWithNext().filter { (a,b)->a.distance(b)>1e-7 }.flatMap { (a,b)->
                    val heading=(Math.toDegrees(atan2(a.y-b.y,a.x-b.x))+360.0)%360.0
                    listOf(heading,(heading+180.0)%360.0)
                } + (0 until 8).map { it*45.0 })
            val approach=candidates.firstNotNullOfOrNull { heading ->
                val rad=Math.toRadians(heading)
                val p=Point(base.x-cos(rad)*config.baseApproachMeters,base.y-sin(rad)*config.baseApproachMeters)
                if(!routes.maneuverSafe(base,p) || !routes.safe(p,p)) null else try { routes.between(p,edges.first().a,(heading+180)%360,edgeHeading(edges.first())); heading to p }
                    catch(_:IllegalArgumentException) { null }
            }
            require(approach!=null) { tr("Base entry axis outside the reachable passage: turn the base or adjust its access.",
                "Axe d’entrée de la base hors du passage accessible : tournez la base ou ajustez son accès.") }
            dockEntryHeading=approach.first; baseApproach=approach.second
            require(routes.maneuverSafe(baseApproach,base)) { tr("Reversing outside the safe surface", "Recul hors de la surface sûre") }
            require(routes.motionCollision(RobotPose(baseApproach,(dockEntryHeading+180)%360),RobotPose(base,(dockEntryHeading+180)%360)) == null) { tr("Chassis collision while reversing towards the base", "Collision du châssis pendant le recul vers la base") }
            // Station links are computed and checked with the route, never during its
            // playback. Spacing the points avoids one computation per outline segment.
            val indices=mutableListOf(0)
            var meters=0.0
            edges.forEachIndexed { index, edge ->
                meters += edge.meters
                if (meters >= 5.0) { indices.add(index+1); meters=0.0 }
            }
            if (indices.last()!=edges.size) indices.add(edges.size)
            val stationPaths=mutableListOf<Checkpoint>()
            fun reuseStationPath(point:Point,heading:Double?,departure:Boolean):List<Point>? {
                val paths=(stationPaths.takeLast(3)+stationPaths.take(1)).distinct().map {
                    if(departure) it.departure else it.returning
                }
                val candidates=paths.flatMap { path -> path.indices.filter { !departure || it>=1 }.map { k ->
                    if(departure) path.take(k+1)+point else listOf(point)+path.drop(k)
                } }.sortedBy { path -> path.zipWithNext().sumOf { (a,b)->a.distance(b) } }
                return candidates.firstOrNull { path ->
                    val anchor=if(departure) path[path.size-2] else path[1]
                    if(!routes.supportsShortcut(point,point,anchor)) false else (if(routes.pathSafe(path,
                            initialHeading=if(departure) (dockEntryHeading+180)%360 else heading,
                            finalHeading=if(departure) heading else (dockEntryHeading+180)%360)) true else false)
                }?.distinctConsecutive()
            }
            fun returnViaCheckpoint(index:Int,heading:Double?):List<Point>? = stationPaths.asReversed().firstNotNullOfOrNull { checkpoint ->
                val path=(listOf(edges[index-1].b)+(checkpoint.edge until index).reversed().map { edges[it].a }+
                    checkpoint.returning).distinctConsecutive()
                (if(routes.pathSafe(path,heading,(dockEntryHeading+180)%360)) path else null)
            }
            checkpoints=indices.mapNotNull { index ->
                onPreparationProgress(tr("Preparing the recharges · ${indices.indexOf(index)+1}/${indices.size}", "Préparation des recharges · ${indices.indexOf(index)+1}/${indices.size}"))
                val point=if(index==0) edges.first().a else edges[index-1].b
                if(index!=0 && index!=edges.size && !routes.supportsPivot(point)) return@mapNotNull null
                val incoming=edges.getOrNull(index-1)?.let(::edgeHeading)
                val outgoing=edges.getOrNull(index)?.let(::edgeHeading)
                try {
                    val departure=if(index==edges.size) emptyList() else
                        reuseStationPath(point,outgoing,true) ?: calculateDeparture(point,targetHeading=outgoing)
                    val returning=if(index==0) departure.drop(1).reversed() else
                        reuseStationPath(point,incoming,false) ?: returnViaCheckpoint(index,incoming) ?:
                        calculateReturn(point,initialHeading=incoming)
                    Checkpoint(index,departure,returning).also { stationPaths.add(it) }
                } catch(e: IllegalArgumentException) {
                    // A border vertex does not necessarily allow a pivot towards the station.
                    // Continue on the validated route up to the next possible link.
                    if(index==0 || index==edges.size) throw e else null
                }
            }
            nextCheckpoint=IntArray(edges.size+1)
            var checkpoint=0
            for(index in nextCheckpoint.indices) {
                while(checkpoints[checkpoint].edge<index) checkpoint++
                nextCheckpoint[index]=checkpoint
            }
            fun stationCandidates(departure:Boolean):List<List<Point>> = checkpoints.flatMap { checkpoint ->
                val path=if(departure) checkpoint.departure else checkpoint.returning
                path.indices.filter { !departure || it>=1 }.map { index ->
                    if(departure) path.take(index+1) else path.drop(index)
                }
            }.groupBy { if(departure) it.last() else it.first() }.values.map { paths ->
                paths.minBy { path -> path.zipWithNext().sumOf { (a,b)->a.distance(b) } }
            }
            // Complete the reused paths with direct accesses spread over the area.
            // A given region is searched only once, even if the zigzags come back to it often.
            val directStations=checkpoints.filter { it.edge<edges.size && routes.supportsPivot(edges[it.edge].a) }
                .distinctBy { floor(edges[it.edge].a.x/2).toInt() to floor(edges[it.edge].a.y/2).toInt() }
                .mapNotNull { checkpoint ->
                    onPreparationProgress(tr("Optimizing direct accesses to the base", "Optimisation des accès directs à la base"))
                    val p=edges[checkpoint.edge].a
                    try { Checkpoint(checkpoint.edge,calculateDeparture(p),calculateReturn(p)) }
                    catch(_:IllegalArgumentException) { null }
                }
            data class StationAccess(val anchor:Point,val path:List<Point>,val meters:Double,val surface:Int=-1)
            fun price(paths:List<List<Point>>,departure:Boolean):List<StationAccess> = paths.map { path ->
                StationAccess(if(departure) path.last() else path.first(),path,path.zipWithNext().sumOf { (a,b)->a.distance(b) })
            }.groupBy { it.anchor }.values.map { it.minBy { access -> access.meters } }
                .filter { routes.supportsShortcutAnchor(it.anchor) }
                .map { it.copy(surface=routes.localSurfaceId(it.anchor)) }
            // Accesses grouped by component, in their original order: the other components
            // would fail anyway (segment or triangle not covered).
            fun bySurface(paths:List<StationAccess>) = paths.groupBy { it.surface }
            fun reachable(groups:Map<Int,List<StationAccess>>,point:Point):List<StationAccess> =
                routes.localSurfaceId(point).let { if(it<0) emptyList() else groups[it].orEmpty() }
            val departures=price(stationCandidates(true)+directStations.flatMap { it.departure.indices.drop(1).map { k -> it.departure.take(k+1) } },true)
            val returns=price(stationCandidates(false)+directStations.flatMap { it.returning.indices.map { k -> it.returning.drop(k) } },false)
            fun shortcut(edge:SimEdge,groups:Map<Int,List<StationAccess>>):List<Point>? {
                if(!routes.supportsShortcutSegment(edge.a,edge.b)) return null
                val middle=Point((edge.a.x+edge.b.x)/2,(edge.a.y+edge.b.y)/2)
                return firstInOrder(reachable(groups,edge.a),{ it.meters+middle.distance(it.anchor) }) {
                    if(routes.supportsShortcut(edge.a,edge.b,it.anchor)) it.path else null
                }
            }
            fun parts(edge:SimEdge,paths:List<StationAccess>,groups:Map<Int,List<StationAccess>>,departure:Boolean):List<List<Point>?> {
                // A link checked over the whole pass also covers each of its portions.
                // Keep the fine splitting only when an obstacle imposes different accesses.
                if (edge.meters > 0.5) {
                    val first=paths.minByOrNull { it.meters+edge.a.distance(it.anchor) }
                    val last=paths.minByOrNull { it.meters+edge.b.distance(it.anchor) }
                    if (first != null && first == last &&
                        routes.supportsShortcutSegment(edge.a,edge.b) &&
                        routes.supportsShortcut(edge.a,edge.b,first.anchor)) return listOf(first.path)
                }
                val count=ceil(edge.meters/0.5).toInt().coerceAtLeast(1)
                return (0 until count).map { i ->
                    val portion=edge.copy(a=point(edge,edge.meters*i/count),b=point(edge,edge.meters*(i+1)/count))
                    shortcut(portion,groups) ?: run {
                        // Continue along the pass heading up to a fixed local link.
                        // Check the actual heading, without imposing a full pivot or restarting the graph.
                        val pivot=if(departure) portion.a else portion.b
                        val local=reachable(groups,pivot)
                        val direct=firstInOrder(local,{ it.meters+pivot.distance(it.anchor) }) { access ->
                            if(!routes.supportsLocalLink(pivot,access.anchor)) return@firstInOrder null
                            val path=if(departure) access.path+pivot else listOf(pivot)+access.path
                            (if(routes.pathSafe(path,
                                    initialHeading=if(departure) (dockEntryHeading+180)%360 else edgeHeading(edge),
                                    finalHeading=if(departure) edgeHeading(edge) else (dockEntryHeading+180)%360)) path.distinctConsecutive() else null)
                        }
                        // Leave a border with a small heading change, then
                        // place the full pivot inside the zone. These
                        // links are prepared here, never during the simulation.
                        val directMeters=direct?.zipWithNext()?.sumOf { (a,b)->a.distance(b) } ?: Double.POSITIVE_INFINITY
                        // By the triangle inequality, a detour through an intermediate pivot
                        // cannot beat the best direct access already validated.
                        // Lower bound on the pivot's component: the others give no link.
                        val lowerBound=local.minOfOrNull { it.meters+pivot.distance(it.anchor) } ?: Double.POSITIVE_INFINITY
                        if (direct != null && directMeters <= lowerBound+1e-8) return@run direct
                        val cap=edgeHeading(edge)
                        val sign=if(departure) -1 else 1
                        val stages=listOf(0.6,1.0,1.5,2.0).flatMap { distance ->
                            listOf(5.0,-5.0,10.0,-10.0,15.0,-15.0,30.0,-30.0,60.0,-60.0).mapNotNull { angle ->
                                val radians=Math.toRadians(cap+angle)
                                val stage=Point(pivot.x+sign*distance*cos(radians),pivot.y+sign*distance*sin(radians))
                                stage.takeIf { routes.supportsShortcutAnchor(it) && routes.supportsLocalLink(pivot,it) }
                            }
                        }
                        val candidates=stages.flatMap { stage -> local.map { access ->
                            Triple(stage,access,pivot.distance(stage)+stage.distance(access.anchor)+access.meters)
                        } }.filter { it.third<directMeters-1e-6 }
                        val staged=firstInOrder(candidates,{ it.third }) { (stage,access,_) ->
                            if(!routes.supportsShortcut(stage,stage,access.anchor)) return@firstInOrder null
                            val path=if(departure) access.path+stage+pivot else listOf(pivot,stage)+access.path
                            (if(routes.pathSafe(path,
                                    initialHeading=if(departure) (dockEntryHeading+180)%360 else cap,
                                    finalHeading=if(departure) cap else (dockEntryHeading+180)%360)) path.distinctConsecutive() else null)
                        }
                        staged ?: direct
                    }
                }
            }
            // Independent portions (frozen accesses, deterministic checks): spread over
            // the available cores, identical result whatever the execution order.
            val returnGroups=bySurface(returns); val departureGroups=bySurface(departures)
            val shortcuts=PreparationPool.map(edges.size,{ done ->
                onPreparationProgress(tr("Optimizing returns and departures · $done/${edges.size}", "Optimisation des retours et départs · $done/${edges.size}"))
            }) { index -> parts(edges[index],returns,returnGroups,false) to parts(edges[index],departures,departureGroups,true) }
            returnShortcuts=shortcuts.map { it.first }
            departureShortcuts=shortcuts.map { it.second }
        }
    }
    /** First non-null result in the order of a stable sort by key, without sorting the whole list:
     * same candidates tried in the same order, cost proportional to the actual attempts. */
    private inline fun <T,R:Any> firstInOrder(items: List<T>, key: (T)->Double, attempt: (T)->R?): R? {
        if(items.isEmpty()) return null
        val keys=DoubleArray(items.size) { key(items[it]) }
        val heap=IndexHeap(keys)
        while(true) { val i=heap.pop(); if(i<0) return null; attempt(items[i])?.let { return it } }
    }
    /** New scenario: shares the prepared polylines, without geometry or search. */
    fun withConfig(config: SimConfig)=MowingSimulation(routes,base,preview,map,config,this,null)
    private fun preparedShortcut(paths:List<List<List<Point>?>>,edge:Int,offset:Double):List<Point>? {
        val parts=paths.getOrNull(edge) ?: return null
        val index=floor(offset/edges[edge].meters*parts.size).toInt().coerceIn(0,parts.lastIndex)
        return parts[index]
    }
    private fun departure(edge: Int, offset: Double): List<Point> {
        preparedShortcut(departureShortcuts,edge,offset)?.let { path ->
            return (path+point(edges[edge],offset)).distinctConsecutive()
        }
        val checkpoint=checkpoints.last { it.edge<=edge }
        val path=checkpoint.departure.toMutableList()
        for(index in checkpoint.edge until edge) path.add(edges[index].b)
        if(edge<edges.size) path.add(point(edges[edge],offset))
        return path.distinctConsecutive()
    }
    private fun returnPath(s: SimState): List<Point> {
        preparedShortcut(returnShortcuts,s.edge,s.offset)?.let { path ->
            return (listOf(s.position)+path).distinctConsecutive()
        }
        val index=if(s.edge>=edges.size) edges.size else if(s.offset<1e-8) s.edge else s.edge+1
        val checkpoint=checkpoints[nextCheckpoint[index]]
        val path=mutableListOf(s.position)
        for(edge in s.edge until checkpoint.edge) path.add(edges[edge].b)
        path.addAll(checkpoint.returning)
        return path.distinctConsecutive()
    }
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
        if(result.lastOrNull()!=point) result.add(point)
        result
    }
    private fun event(s:SimState,message:String)=s.copy(log=(s.log+"${(s.elapsed/60).toInt()} min · $message").takeLast(30))
    private fun point(e:SimEdge, offset:Double):Point { val p=(offset/e.meters).coerceIn(0.0,1.0); return Point(e.a.x+(e.b.x-e.a.x)*p,e.a.y+(e.b.y-e.a.y)*p) }
    fun start():SimState {
        val covered=total*config.initialProgress.coerceIn(0.0,100.0)/100
        var remaining=covered; var i=0; var offset=0.0
        while(i<edges.size) { val e=edges[i]; if(e.cutting && remaining<e.meters) { offset=remaining; break }; if(e.cutting) remaining-=e.meters; i++ }
        if (config.initialProgress >= 100.0) { i=edges.size; offset=0.0 }
        val trip=if(i==edges.size) listOf(base) else departure(i,offset)
        val s=SimState(base,config.initialBattery.coerceIn(0.0,100.0),if(i==edges.size) SimPhase.COMPLETE else departurePhase(trip),
            edge=i,offset=offset,covered=covered,transit=trip,trail=listOf(base),heading=(dockEntryHeading+180.0)%360.0,log=listOf(tr("0 min · Start at ${config.initialProgress.toInt()} % mowed", "0 min · Départ à ${config.initialProgress.toInt()} % de tonte")),
            surveyId=if(preview.mode.isSurvey) java.util.UUID.randomUUID().toString() else "",surveyStartedAt=if(preview.mode.isSurvey) System.currentTimeMillis() else 0)
        // Same rule as the actual pilot (RouteRecharge.departureAllowed): immediate exit above the return threshold +
        // margin, otherwise recharge up to the resume threshold.
        val departureMinimum=min(config.resumePercent,config.returnPercent+RouteRecharge.DEPARTURE_MARGIN_PERCENT)
        return if(s.phase!=SimPhase.COMPLETE && s.battery<departureMinimum) event(s.copy(phase=SimPhase.CHARGING),tr("Initial recharge", "Recharge initiale"))
            else if(s.phase==SimPhase.LEAVING_DOCK) event(s,tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF")) else s
    }
    /**
     * Every departure from the station starts, as on the robot ([DockExit]), with a slow straight forward motion from
     * the base to the link point, without pivoting on the base; the link then follows at normal speed.
     */
    private fun departurePhase(trip: List<Point>) =
        if(trip.size>=2 && trip[0]==base && trip[1]==baseApproach) SimPhase.LEAVING_DOCK else SimPhase.DEPARTING
    /** Duration of a departure from the base: slow exit along the station axis, then link. */
    private fun departureSeconds(trip: List<Point>): Double {
        if(departurePhase(trip)!=SimPhase.LEAVING_DOCK) return travelSeconds(trip)
        val exit=base.distance(baseApproach)
        return exit/config.dockingSpeedMps + 2.0 + travelSeconds(trip.drop(1))
    }
    private fun returning(s:SimState, final:Boolean): SimState {
        val path=returnPath(s)
        return event(s.copy(phase=SimPhase.RETURNING,completedReturn=final,transit=path,
            resumeTransit=(listOf(base)+path.reversed()).distinctConsecutive(),transitIndex=0,transitOffset=0.0),
            if(final) tr("End of mowing, final return", "Fin de tonte, retour final") else tr("Battery: return to recharge", "Batterie : retour pour recharge"))
    }
    /** [onPhase]: each phase change crossed during the advance (accelerated playback: several per call). */
    fun advance(initial:SimState, seconds:Double, simulateSignals:Boolean=true, onPhase:((SimState)->Unit)?=null):SimState {
        require(seconds.isFinite() && seconds>=0 && seconds<=3600)
        var s=initial; var left=seconds
        while(left>1e-8 && s.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED)) {
            if (simulateSignals && preview.mode.isSurvey && s.phase == SimPhase.MOWING && edges.getOrNull(s.edge)?.cutting == true &&
                (s.signalSamples.isEmpty() || s.elapsed*1000-(s.signalSamples.last().at)>=1000)) {
                s = s.copy(signalSamples=s.signalSamples+SignalCoverage.simulated(s.position,base,s.elapsed,s.heading))
            }
            val playbackStep = min(left,1.0); left -= playbackStep
            if (s.phase == SimPhase.CHARGING) s = withChargeNeed(s)
            val dt = if (config.instantCharging && s.phase == SimPhase.CHARGING)
                max(playbackStep, (chargeTarget(s) - s.battery).coerceAtLeast(0.0) * config.chargeMinutes * 60 / 100)
            else playbackStep
            val phase = s.phase
            s = try {
                tick(s,dt)
            } catch (e: IllegalArgumentException) {
                event(s.copy(phase=SimPhase.BLOCKED), e.message ?: tr("Physical trajectory impossible", "Trajectoire physique impossible"))
            }
            if (s.phase != phase) onPhase?.invoke(s)
        }; return s
    }
    private fun travelSeconds(path: List<Point>): Double {
        val segments = path.zipWithNext().filter { (a,b) -> a.distance(b) > 1e-9 }
        val headings = segments.map { (a,b) -> Math.toDegrees(atan2(b.y-a.y,b.x-a.x)) }
        val turns = headings.zipWithNext().sumOf { (a,b) -> abs(((b-a+540.0)%360.0)-180.0) }
        // Unknown initial heading: reserve a U-turn, then the actual angles of the outline.
        // Two seconds per segment cover the rounding of the move and pivot ticks.
        return segments.sumOf { (a,b) -> a.distance(b) } / config.speedMps +
            (180.0 + turns) / config.turnDegreesPerSecond + segments.size * 2.0
    }
    // The heading is in degrees in the map frame (+x = 0°). No movement during the pivot.
    private fun returnSeconds(state: SimState) = travelSeconds(returnPath(state)) +
        180.0/config.turnDegreesPerSecond + 2.0 + config.baseApproachMeters/config.dockingSpeedMps
    /** Rest of the mowing from [edge] / [offset]: remaining cutting and links, then final return and docking. */
    private fun restSeconds(edge: Int, offset: Double): Double {
        val end=SimState(edges.lastOrNull()?.b ?: base,0.0,SimPhase.MOWING,edge=edges.size)
        if(edge>=edges.size) return returnSeconds(end)
        return travelSeconds(listOf(point(edges[edge],offset))+edges.subList(edge,edges.size).map { it.b }) + returnSeconds(end)
    }
    /** While charging: energy of the rest (exit, link, rest of the mowing, final return) remembered for the current recharge. */
    private fun withChargeNeed(s: SimState): SimState {
        if(s.chargeNeed!=null) return s
        val trip=s.resumeTransit.ifEmpty { departure(s.edge,s.offset) }
        return s.copy(chargeNeed=FinishPolicy.neededPercent(departureSeconds(trip)+restSeconds(s.edge,s.offset),config.autonomyMinutes))
    }
    /** Charge level at which to leave again: resume threshold, or less if the rest is short ([FinishPolicy]). */
    private fun chargeTarget(s: SimState): Double =
        s.chargeNeed?.let { FinishPolicy.chargeTarget(it,config.returnPercent,config.resumePercent) }?.coerceAtMost(config.resumePercent) ?: config.resumePercent
    private fun align(s: SimState, e: SimEdge, dt: Double): SimState? =
        alignHeading(s,Math.toDegrees(atan2(e.b.y-e.a.y,e.b.x-e.a.x)),dt)
    private fun alignHeading(s: SimState, target: Double, dt: Double): SimState? {
        val delta=((target-s.heading+540.0)%360.0)-180.0
        if(abs(delta)<1e-6) return null
        return s.copy(heading=(s.heading+delta.coerceIn(-config.turnDegreesPerSecond*dt,config.turnDegreesPerSecond*dt)+360.0)%360.0)
    }
    private fun tick(input:SimState,dt:Double):SimState {
        var s=input.copy(elapsed=input.elapsed+dt)
        // Configured autonomy = full discharge duration, without arbitrary penalty.
        val drain=100.0/(config.autonomyMinutes*60)
        if(s.phase==SimPhase.CHARGING) {
            s=withChargeNeed(s)
            val target=chargeTarget(s)
            s=s.copy(battery=min(max(target,s.battery),s.battery+dt*100/(config.chargeMinutes*60)))
            if(s.battery>=target-1e-9) {
                val early=target<config.resumePercent
                val trip=s.resumeTransit.ifEmpty { departure(s.edge,s.offset) }
                val roundTrip=(departureSeconds(trip)+returnSeconds(s.copy(position=point(edges[s.edge],s.offset))))*drain
                if(!early && roundTrip+config.returnPercent>=config.resumePercent) return event(s.copy(phase=SimPhase.BLOCKED),
                    tr("Autonomy insufficient to reach the resume point and come back", "Autonomie insuffisante pour atteindre la reprise et revenir"))
                s=event(s.copy(phase=departurePhase(trip),transit=trip,transitIndex=0,transitOffset=0.0,heading=(dockEntryHeading+180.0)%360.0,
                        finishing=false,chargeNeed=null),
                    (if(early) tr("Battery enough to finish (${s.battery.roundToInt()} %) · ", "Batterie suffisante pour finir (${s.battery.roundToInt()} %) · ")
                        else tr("Recharge finished · ", "Recharge terminée · ")) +
                    if(departurePhase(trip)==SimPhase.LEAVING_DOCK) tr("leaving the base in a straight line, slowly", "sortie de base en ligne droite, lentement")
                        else tr("resume at the same point", "reprise au même point"))
            }; return s
        }
        if(s.phase==SimPhase.ALIGNING) {
            val aligned=alignHeading(s,(dockEntryHeading+180.0)%360.0,dt)
            s=aligned ?: event(s.copy(phase=SimPhase.DOCKING,transit=listOf(baseApproach,base),
                transitIndex=0,transitOffset=0.0),tr("Simulated recharge return mode on · sensor-guided reversing", "Mode retour recharge simulé activé · recul guidé par capteurs"))
        } else if(s.phase==SimPhase.MOWING) {
            if(s.edge>=edges.size) return returning(s,true)
            // Return threshold: finish without recharge if the rest allows it ([FinishPolicy]), emergency return lower down afterwards.
            if(!s.finishing && s.battery<=config.returnPercent) {
                if(!FinishPolicy.finishWithoutRecharge(s.battery,FinishPolicy.neededPercent(restSeconds(s.edge,s.offset),config.autonomyMinutes)))
                    return returning(s,false)
                s=event(s.copy(finishing=true),tr("Return threshold: battery enough to finish the mowing then return", "Seuil bas : batterie suffisante pour finir la tonte puis rentrer"))
            }
            if(s.finishing && s.battery<=FinishPolicy.ARRIVAL_FLOOR_PERCENT) return returning(s.copy(finishing=false),false)
            val backSeconds=returnSeconds(s)
            // Never Die Outside ([ReturnReserve]): far from the base, return as soon as the battery nears the return cost
            // plus the reserve, even above the low threshold; same choice as at the low threshold (finish or recharge).
            if(!s.finishing) {
                val low=config.returnPercent.roundToInt(); val high=config.resumePercent.roundToInt()
                val watch=ReturnReserve.threshold(low,high,FinishPolicy.neededPercent(backSeconds,config.autonomyMinutes),
                    ReturnReserve.sanitize(config.returnReservePercent.roundToInt(),low))
                if(watch>low && s.battery<=watch) {
                    if(!FinishPolicy.finishWithoutRecharge(s.battery,FinishPolicy.neededPercent(restSeconds(s.edge,s.offset),config.autonomyMinutes)))
                        return event(returning(s,false),tr("Return reserve: battery near the return cost plus the reserve ($watch %)", "Réserve de retour : batterie proche du coût du retour plus la réserve ($watch %)"))
                    s=event(s.copy(finishing=true),tr("Return reserve: battery enough to finish the mowing then return", "Réserve de retour : batterie suffisante pour finir la tonte puis rentrer"))
                }
            }
            val back=backSeconds*drain
            if(s.battery<=back+dt*drain) return event(returning(s,false),tr("Early return: energy needed to reach the base", "Retour anticipé : énergie nécessaire pour atteindre la base"))
            var distance=dt*config.speedMps
            while(distance>1e-8 && s.edge<edges.size) {
                val e=edges[s.edge]
                val aligned=align(s,e,dt)
                if(aligned != null) { s=aligned; break }
                val step=min(distance,e.meters-s.offset); val offset=s.offset+step
                val future=if(offset>=e.meters-1e-8)
                    s.copy(position=e.b,edge=s.edge+1,offset=0.0)
                else s.copy(position=point(e,offset),offset=offset)
                // Final return: arrival at the [FinishPolicy] floor (the return threshold only decides a recharge).
                val arrivalReserve=if(future.edge<edges.size) 0.0 else FinishPolicy.ARRIVAL_FLOOR_PERCENT.toDouble()
                if(s.battery <= (returnSeconds(future)+dt)*drain+arrivalReserve)
                    return event(returning(s,false),tr("Early return before the next link to the base", "Retour anticipé avant le prochain raccord à la base"))
                s=s.copy(position=future.position,offset=offset,covered=s.covered+if(e.cutting)step else 0.0)
                distance-=step
                if(offset>=e.meters-1e-8) { s=s.copy(edge=s.edge+1,offset=0.0); break }
            }
        } else {
            val slow=s.phase==SimPhase.DOCKING || s.phase==SimPhase.LEAVING_DOCK
            var distance=dt*if(slow) config.dockingSpeedMps else config.speedMps
            // Leaving the base: only the base → link point edge, axis heading kept (no pivot on the base).
            val last=if(s.phase==SimPhase.LEAVING_DOCK) 1 else s.transit.size-1
            while(distance>1e-8 && s.transitIndex<last) {
                val e=SimEdge(s.transit[s.transitIndex],s.transit[s.transitIndex+1],false)
                val aligned=if(e.meters>1e-8 && !slow) align(s,e,dt) else null
                if(aligned != null) { s=aligned; break }
                val step=min(distance,e.meters-s.transitOffset); val offset=s.transitOffset+step
                s=s.copy(position=if(e.meters<1e-8)e.b else point(e,offset),transitOffset=offset); distance-=step
                if(offset>=e.meters-1e-8) { s=s.copy(transitIndex=s.transitIndex+1,transitOffset=0.0); if(e.meters>1e-8) break }
            }
            if(s.phase==SimPhase.LEAVING_DOCK && s.transitIndex>=last)
                s=if(s.transitIndex>=s.transit.size-1) event(s.copy(phase=SimPhase.MOWING),tr("Link point reached · mowing / route resume", "Point de liaison atteint · tonte / reprise du trajet"))
                    else event(s.copy(phase=SimPhase.DEPARTING),tr("Link point reached · link towards the mowing", "Point de liaison atteint · raccord vers la tonte"))
            else if(s.transitIndex>=s.transit.size-1) s=when(s.phase) {
                SimPhase.DEPARTING -> event(s.copy(phase=SimPhase.MOWING),tr("Mowing / route resume", "Tonte / reprise du trajet"))
                SimPhase.RETURNING -> event(s.copy(phase=SimPhase.ALIGNING),tr("Approach point reached · rear alignment facing the base", "Point d’approche atteint · alignement arrière face à la base"))
                else -> if(s.completedReturn) event(s.copy(phase=SimPhase.COMPLETE,position=base),tr("Arrived at the base, mission finished", "Arrivée à la base, mission terminée"))
                    else event(s.copy(phase=SimPhase.CHARGING,position=base,cycles=s.cycles+1),tr("Arrived at the base, recharge ${s.cycles+1}", "Arrivée à la base, recharge ${s.cycles+1}"))
            }
        }
        s=s.copy(battery=(s.battery-dt*drain).coerceAtLeast(0.0),trail=(s.trail+ s.position).takeLast(1800))
        return if(s.battery<=0 && s.phase!=SimPhase.COMPLETE) event(s.copy(phase=SimPhase.BLOCKED),tr("Battery exhausted before the base", "Batterie épuisée avant la base")) else s
    }
}

/** Shared pool for route preparations: parallel computation off the calling thread,
 * which remains the only one publishing the progress (and hence the cancellation requested by the interface).
 */
internal object PreparationPool {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private class PoolThread(task: Runnable) : Thread(task, "luba-preparation") { init { isDaemon = true } }
    private val executor by lazy { java.util.concurrent.Executors.newFixedThreadPool(threads) { PoolThread(it) } }
    fun <T> map(count: Int, progress: (Int) -> Unit, minParallel: Int = 32, task: (Int) -> T): List<T> {
        // Nested call from a pool task: sequential, otherwise the threads busy
        // waiting for their sub-tasks could block the whole pool.
        if (threads == 1 || count < minParallel || Thread.currentThread() is PoolThread)
            return List(count) { if (it % 50 == 0) progress(it); task(it) }
        val results = arrayOfNulls<Any?>(count)
        val chunk = if (count < 64) 1 else max(4, count / (threads * 8))
        // Cancellation, error or interrupted progress: the remaining tasks stop
        // at the next element instead of occupying the pool for an abandoned result.
        val aborted = java.util.concurrent.atomic.AtomicBoolean()
        val futures = (0 until count step chunk).map { start ->
            executor.submit {
                for (i in start until min(count, start + chunk)) { if (aborted.get()) return@submit; results[i] = task(i) }
            }
        }
        var completed = false
        try {
            futures.forEachIndexed { index, future ->
                while (true) {
                    try { future.get(100, java.util.concurrent.TimeUnit.MILLISECONDS); break }
                    catch (_: java.util.concurrent.TimeoutException) { progress(min(count, index * chunk)) }
                }
                progress(min(count, (index + 1) * chunk))
            }
            completed = true
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (!completed) { aborted.set(true); futures.forEach { it.cancel(false) } }
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }
}

/** Binary heap of indices without boxing: order (key, index), identical to a stable sort. */
internal class IndexHeap(private val keys: DoubleArray) {
    private val heap = IntArray(keys.size) { it }
    private var size = keys.size
    private fun less(a: Int, b: Int) = keys[a] < keys[b] || (keys[a] == keys[b] && a < b)
    init { for (i in size / 2 - 1 downTo 0) down(i) }
    private fun down(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1; val r = l + 1
            var m = i
            if (l < size && less(heap[l], heap[m])) m = l
            if (r < size && less(heap[r], heap[m])) m = r
            if (m == i) return
            val t = heap[i]; heap[i] = heap[m]; heap[m] = t; i = m
        }
    }
    fun pop(): Int {
        if (size == 0) return -1
        val top = heap[0]
        heap[0] = heap[--size]
        down(0)
        return top
    }
}
