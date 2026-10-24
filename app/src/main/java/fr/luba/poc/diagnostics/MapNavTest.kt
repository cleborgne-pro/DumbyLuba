package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Geometry as Area
import kotlin.math.*

data class MapNavPlan(
    val zone: List<Point>, val holes: List<List<Point>>, val points: List<Point>,
    val bands: List<List<Point>>, val borders: List<List<Point>>, val labels: List<String>,
    val routes: fr.luba.poc.simulation.TrajectoryPlanner? = null,
    /** Move to a point: checks use the physical footprint of the LUBA 2 (as mowing does),
     * not the fixed margin of the map test. */
    val physicalGuard: Boolean = false,
    /** Move to a point: navigable surface of the mapped zones alone, without the corridors opened beyond them
     * ([fr.luba.poc.simulation.PointPlanner]); null for the other courses. */
    val mapped: Area? = null,
    /** Straight exit from the base to the link point, driven before [points] (robot on its base); null otherwise. */
    val departure: fr.luba.poc.simulation.BaseDeparture.Exit? = null,
    /** Each range starts with an in-place pivot towards its first edge ([fr.luba.poc.simulation.PathFollower.alignFirst]),
     * kept for the paths replanned by a recovery. */
    val alignAtStart: Boolean = false,
) {
    val meters: Double get() = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    /** Sections of the route beyond the mapped zones (shown dotted, driven without map checks). */
    val offMap: List<List<Point>> by lazy { mapped?.let { fr.luba.poc.simulation.PointPlanner.offMapSections(it, points) }.orEmpty() }
    val offMapMeters: Double get() = offMap.sumOf { s -> s.zipWithNext().sumOf { (a, b) -> a.distance(b) } }

    /** Cutting test: edge [edge] (points[edge] → points[edge+1]) is driven with the blades running. */
    fun cutting(edge: Int): Boolean = BladeCourse.cuts(labels[edge + 1])

    /** Exclusive end (in edges) of the run with the same blade state starting at edge [from]. */
    fun bladeRangeEnd(from: Int): Int {
        require(from in 0 until points.size - 1) { tr("Edge outside the course", "Arête hors parcours") }
        val blades = cutting(from)
        var to = from + 1
        while (to < points.size - 1 && cutting(to) == blades) to++
        return to
    }

    /** Meters driven with the blades running over the whole course. */
    val cuttingMeters: Double get() = (0 until points.size - 1).filter(::cutting).sumOf { points[it].distance(points[it + 1]) }
}

/**
 * Labels of the test course: stripes and borders are cut, links and bypasses run with the blades stopped.
 * [START], [LINK] and [BYPASS] are compared by [cuts] (any other label cuts): they stay as stored French values.
 */
object BladeCourse {
    const val START = "Départ"
    const val LINK = "Liaison"
    const val BYPASS = "Contournement local"
    fun cuts(label: String): Boolean = label != START && label != LINK && label != BYPASS
}

/** Small real course: every link stays inside the map, away from no-go zones and within the chosen radius. */
object MapNavPlanner {
    const val MARGIN_M = 0.9
    private val factory = GeometryFactory()
    private fun polygon(points: List<Point>) = factory.createPolygon(
        (points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray(),
    ).also { require(it.isValid && it.area > 0) { tr("Invalid map outline", "Contour de carte invalide") } }
    private fun parts(area: Area): List<Area> = if (area.isEmpty) emptyList() else if (area.numGeometries == 1 && area.getGeometryN(0) === area)
        listOf(area) else (0 until area.numGeometries).flatMap { parts(area.getGeometryN(it)) }
    private fun points(line: LineString) = line.coordinates.map { Point(it.x, it.y) }

    fun plan(map: RobotMap, start: Point, radius: Double, obstacles: List<LocalObstacle>): MapNavPlan {
        require(map.complete) { tr("Read the complete map before navigating", "Lire la carte complète avant la navigation") }
        require(start.x.isFinite() && start.y.isFinite() && radius.isFinite() && radius >= 2) { tr("Invalid start or radius", "Départ ou rayon invalide") }
        val zone = map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && Geometry.inside(start, it.points) }
            ?: error(tr("Place the robot inside a mapped mowing zone", "Placer le robot dans une zone de tonte cartographiée"))
        val holes = map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points } + obstacles.map { obstacle ->
            // The general margin already includes the robot envelope; keep at least its real reserve.
            val disc = obstacle.keepOutDisc(RobotEnvelope.LUBA2)
            val r = max(0.05, disc.radiusM - MARGIN_M) / cos(PI / 64)
            (0 until 64).map { i -> Point(disc.x + r*cos(i*2*PI/64), disc.y + r*sin(i*2*PI/64)) }
        }
        var free: Area = polygon(zone.points)
        holes.forEach { free = free.difference(polygon(it)) }
        val inset = free.buffer(-(MARGIN_M + 0.05), 16)
        val local = inset.intersection(factory.createPoint(Coordinate(start.x,start.y)).buffer(radius - 0.3, 32))
        val navigable = local.buffer(0.00001)
        fun safe(a: Point, b: Point) = Geometry.safe(a,b,zone.points,holes,MARGIN_M) &&
            a.distance(start) <= radius && b.distance(start) <= radius &&
            navigable.covers(if (a.distance(b) < 1e-9) factory.createPoint(Coordinate(a.x,a.y))
                else factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y))))
        require(!local.isEmpty && safe(start,start)) {
            val clearance = (listOf(zone.points) + holes).minOf { contour ->
                (contour + contour.first()).zipWithNext().minOf { (a,b) -> Geometry.distance(start,a,b) }
            }
            tr("Start %.2f m from a map limit or a no-go zone; clearance required: 0.95 m", "Départ à %.2f m d'une limite ou d'une exclusion ; dégagement requis : 0,95 m").format(clearance)
        }
        val routes = fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes=listOf(zone)),MARGIN_M+0.05,holes,limitCenter=start,limitRadius=radius-0.3,curveSegments=16)
        val path = mutableListOf(start)
        val labels = mutableListOf(BladeCourse.START)
        val bands = mutableListOf<List<Point>>()
        val borders = mutableListOf<List<Point>>()
        fun append(run: List<Point>, label: String): Boolean {
            if (run.size < 2 || !run.zipWithNext().all { (a,b) -> safe(a,b) }) return false
            val choices = listOf(run, run.reversed()).mapNotNull { candidate ->
                runCatching { routes.between(path.last(),candidate.first()) to candidate }.getOrNull()
            }
            val choice = choices.minByOrNull { (connection,_) -> connection.zipWithNext().sumOf { (a,b) -> a.distance(b) } } ?: return false
            choice.first.drop(1).forEach { path.add(it); labels.add(BladeCourse.LINK) }
            choice.second.drop(1).forEach { path.add(it); labels.add(label) }
            return true
        }
        // Three spaced stripes, limited to 3 m: not a full coverage of the garden.
        for (dy in listOf(0.0, 0.8, -0.8)) {
            val line = factory.createLineString(arrayOf(Coordinate(start.x-radius,start.y+dy),Coordinate(start.x+radius,start.y+dy)))
            val candidates = parts(local.intersection(line)).filterIsInstance<LineString>().filter { it.length >= 0.6 }
            val nearest = candidates.minByOrNull { it.distance(factory.createPoint(Coordinate(start.x,start.y))) } ?: continue
            val xs = nearest.coordinates.map { it.x }; val low = max(xs.min(),start.x-1.5); val high = min(xs.max(),start.x+1.5)
            if (high-low < 0.6) continue
            val run = listOf(Point(low,start.y+dy),Point(high,start.y+dy))
            if (append(run,tr("Stripe ${bands.size+1}", "Bande ${bands.size+1}"))) bands.add(run)
        }
        require(bands.isNotEmpty()) { tr("No reachable stripe around the robot", "Pas de bande accessible autour du robot") }
        val polygons = parts(inset).filterIsInstance<Polygon>()
        fun addBorder(rings: List<LineString>, label: String) {
            val candidates = rings.flatMap { ring ->
                // Fragment of a real border, without inventing a border around the test radius.
                parts(ring.intersection(local.buffer(0.00001))).filterIsInstance<LineString>().map(::points)
            }.sortedBy { run -> run.minOf { it.distance(start) } }
            for (run in candidates) {
                val short = mutableListOf(run.first()); var meters = 0.0
                for (p in run.drop(1)) {
                    val length = short.last().distance(p)
                    if (meters + length > 4.0) {
                        val fraction = (4.0-meters)/length
                        short.add(Point(short.last().x+(p.x-short.last().x)*fraction,short.last().y+(p.y-short.last().y)*fraction)); break
                    }
                    short.add(p); meters += length
                }
                if (append(short,label)) { borders.add(short); break }
            }
        }
        addBorder(polygons.map { it.exteriorRing },tr("Border", "Bordure"))
        addBorder(polygons.flatMap { poly -> (0 until poly.numInteriorRing).map { poly.getInteriorRingN(it) } },"No-go / obstacle")
        require(path.zipWithNext().all { (a,b) -> safe(a,b) }) { tr("Unsafe link", "Liaison non sûre") }
        return MapNavPlan(zone.points,holes,path,bands,borders,labels,routes)
    }
}

/**
 * Bypass of a recorded obstacle: go to the opposite side then come back, each leg computed by
 * the same planner as moves to a point (local obstacles avoided).
 */
object ObstacleCoursePlanner {
    fun plan(map: RobotMap, start: Point, far: Point, home: Point, obstacles: List<LocalObstacle>, includeFar: Boolean = true): MapNavPlan {
        if (!includeFar) {
            val back = fr.luba.poc.planning.RoutePlanning.point(map, start, home, obstacles)
            return back.copy(labels = back.labels.dropLast(1) + fr.luba.poc.obstacles.ObstacleCourse.HOME_LABEL)
        }
        val out = fr.luba.poc.planning.RoutePlanning.point(map, start, far, obstacles)
        val back = fr.luba.poc.planning.RoutePlanning.point(map, far, home, obstacles)
        return out.copy(points = out.points + back.points.drop(1),
            labels = out.labels.dropLast(1) + fr.luba.poc.obstacles.ObstacleCourse.FAR_LABEL +
                back.labels.drop(1).dropLast(1) + fr.luba.poc.obstacles.ObstacleCourse.HOME_LABEL)
    }
}

/** Driving towards a single point: same building block as routes ([fr.luba.poc.simulation.PathFollower]). */
object MapNavFollower {
    const val ARRIVAL_M = 0.15
    const val MAX_DEVIATION_M = 0.3
    fun command(position: Point, heading: Double, target: Point, linear: Int, angular: Int, arrivalM: Double = ARRIVAL_M): Pair<Int,Int> {
        if (position.distance(target) <= arrivalM) return 0 to 0
        val command = fr.luba.poc.simulation.PathFollower(listOf(fr.luba.poc.simulation.SimEdge(position,target,false)),0,1,arrivalM)
            .command(position,heading,linear,angular)
        return command.linear to command.angular
    }
}

/**
 * Tracking checks shared by the test course and the move to a point.
 * The checked reserves are physical: the planning corridor (0.95 m, radius - 0.3 m) runs
 * exactly along the borders and the stripe ends; RTK noise of a few centimeters must not
 * interrupt the test there. The deviation from the segment stays bounded at 30 cm.
 */
object MapNavGuard {
    /** Minimum physical reserve of the reference point to boundaries and no-go zones. */
    const val POSITION_MARGIN_M = MapNavPlanner.MARGIN_M - 0.2
    /** Maximum projection in front of the robot, bounded by the remaining distance to the target point. */
    const val LOOKAHEAD_M = 0.3

    fun positionFailure(plan: MapNavPlan, position: Point, heading: Double? = null, headingTrusted: Boolean = true): String? {
        val routes = plan.routes
        if (plan.physicalGuard && routes != null) {
            // Heading not confirmed by motion: the oriented footprint would be wrong; check the center.
            if (heading == null || !headingTrusted)
                return if (routes.safe(position, position)) null else tr("Robot outside the navigable area (map limit or no-go zone)", "Robot hors de la surface navigable (limite ou exclusion)")
            return if (routes.footprintSafe(RobotPose(position, heading))) null else tr("Robot footprint outside the safe area (map limit or no-go zone)", "Emprise du robot hors de la surface sûre (limite ou exclusion)")
        }
        return if (Geometry.safe(position, position, plan.zone, plan.holes, POSITION_MARGIN_M)) null
            else tr("Map limit / no-go margin reached (physical reserve %.2f m)", "Marge carte / exclusion atteinte (réserve physique %.2f m)").format(POSITION_MARGIN_M)
    }

    /** Imminent motion: 6° pivot or forward move up to the target point at most, LUBA 2 envelope included. */
    fun motionFailure(plan: MapNavPlan, position: Point, heading: Double, target: Point, linear: Int, angular: Int,
                      headingTrusted: Boolean = true): String? {
        if (linear == 0 && angular == 0) return null
        // Heading not confirmed: oriented envelope not meaningful; slow forward move, center and deviation checked.
        if (plan.physicalGuard && !headingTrusted) return null
        val pose = RobotPose(position, heading)
        val reach = min(LOOKAHEAD_M, position.distance(target))
        val rad = Math.toRadians(heading)
        val ahead = Point(position.x + reach*cos(rad), position.y + reach*sin(rad))
        val projected = if (linear == 0) RobotPose(position, heading + if (angular > 0) 6.0 else -6.0)
            else RobotPose(ahead, heading)
        // Compared value: recovery checks (DriftRecovery, RemoteNavOutcome) match this text in both languages.
        if (plan.routes?.motionCollision(pose, projected) != null) return tr("Robot envelope outside the safe corridor", "Enveloppe robot hors du couloir sûr")
        if (!plan.physicalGuard && linear != 0 && !Geometry.safe(position, ahead, plan.zone, plan.holes, POSITION_MARGIN_M))
            return tr("Corridor in front of the robot not safe", "Couloir devant le robot non sûr")
        return null
    }

    /** Obstacle added or changed after planning: known obstacles are already exclusions of the plan. */
    fun unplannedObstacle(position: Point, target: Point, obstacles: List<LocalObstacle>, planned: Set<LocalObstacle>): LocalObstacle? =
        obstacles.filter { it !in planned }.firstOrNull { obstacle ->
            Geometry.distance(Point(obstacle.x, obstacle.y), position, target) <=
                obstacle.exclusionRadiusM + RobotEnvelope.LUBA2.turnClearanceM + MapNavFollower.MAX_DEVIATION_M + min(0.6, obstacle.incidents.size * 0.1)
        }
}
