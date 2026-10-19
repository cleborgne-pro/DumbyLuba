package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.DockClearance
import fr.luba.poc.planning.LoopEntries
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import fr.luba.poc.simulation.TrajectoryPlanner
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import kotlin.math.atan2
import org.locationtech.jts.geom.Geometry as JtsGeometry

/** Border offered by the "Border" debug mode: outer boundary of a zone or loop around a no-go zone. */
data class BorderChoice(val shape: MapShape, val noGo: Boolean, val label: String)

/**
 * Prepared border loop: kept loops, requested offset and offset actually applied (rounded corners or increased offset
 * when the chassis does not fit), deviations measured between the planned route and the recorded boundary, route
 * verified like real mowing.
 */
data class BorderMission(val choice: BorderChoice, val requestedOffsetM: Double, val appliedOffsetM: Double,
    val roundingM: Double, val loops: List<List<Point>>, val minDistanceM: Double, val medianDistanceM: Double,
    val preview: Preview, val engine: MowingSimulation) {
    val meters: Double get() = loops.sumOf { loop -> loop.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
}

/**
 * Service → Border following: follow a single recorded boundary (outer boundary of a zone or perimeter of a no-go
 * zone) at a chosen offset, to compare the planned route and the measured position.
 *
 * The loop is a cutting pass (blades running); link from the robot and return to base with blades stopped.
 * Navigation, chassis checks and preparation are those of real mowing ([RoutePlanning.mission], [MowingSimulation]):
 * the loop is only kept if it passes these checks.
 */
object BorderFollow {
    /** Preview label of a border following: recognized by the resume ([fr.luba.poc.simulation.MissionTask]); stored, not translated. */
    const val PLANNER_LABEL = "Bordure debug"
    /** Same range as the mowing outer loop offset (up to 1 m, request of 2026-10-14). */
    const val MAX_OFFSET_M = CoveragePlanner.MAX_OUTER_OFFSET_M
    /** Minimum applied offset: a loop placed exactly on the trace would touch the boundary. */
    const val MIN_APPLIED_M = 0.01
    /** Beyond this, the loop deviates too much from the request: refused rather than giving a misleading result. */
    const val MAX_EXTRA_M = 0.2
    private const val EXTRA_STEP_M = 0.02
    private const val MIN_LOOP_AREA_M2 = 0.05
    private val factory = GeometryFactory()

    private fun polygon(points: List<Point>): JtsGeometry {
        val ring = (if (points.first() == points.last()) points else points + points.first()).map { Coordinate(it.x, it.y) }
        return factory.createPolygon(ring.toTypedArray()).let { if (it.isValid) it else it.buffer(0.0) }
    }
    private fun ring(points: List<Point>): LineString =
        factory.createLineString((if (points.first() == points.last()) points else points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun polygons(g: JtsGeometry): List<Polygon> = (0 until g.numGeometries).flatMap { i ->
        when (val part = g.getGeometryN(i)) {
            is Polygon -> if (part.isEmpty) emptyList() else listOf(part)
            else -> if (part === g) emptyList() else polygons(part)
        }
    }
    private fun exterior(p: Polygon) = p.exteriorRing.coordinates.map { Point(it.x, it.y) }
    private fun heading(a: Point, b: Point) = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
    private fun firstHeading(path: List<Point>) = path.zipWithNext().first { (a, b) -> a.distance(b) > 1e-9 }.let { heading(it.first, it.second) }

    /** Zones then no-go zones of the map, with a readable label (Mammotion no-go zones have no name). */
    fun choices(map: RobotMap): List<BorderChoice> {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val noGos = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }
        fun zoneName(z: MapShape) = z.name.trim().ifBlank { "Zone ${zones.indexOf(z) + 1}" }
        return zones.map { BorderChoice(it, false, tr("Boundary · ${zoneName(it)}", "Limite · ${zoneName(it)}")) } + noGos.mapIndexed { index, shape ->
            val center = runCatching { polygon(shape.points).interiorPoint.let { Point(it.x, it.y) } }.getOrNull()
            val home = center?.let { c -> zones.firstOrNull { Geometry.inside(c, it.points) } }
            val name = shape.name.trim().ifBlank { "No-go ${index + 1}" }
            BorderChoice(shape, true, if (home != null) "$name · ${zoneName(home)}" else name)
        }
    }

    /** Distance between a point and the recorded boundary of the border, in meters. */
    fun distance(choice: BorderChoice, p: Point): Double =
        ring(choice.shape.points).distance(factory.createPoint(Coordinate(p.x, p.y)))
    /**
     * Gap between the side of the robot centered at [p] and the recorded boundary (outer side of the chassis): negative
     * if the chassis overlaps it.
     */
    fun clearance(choice: BorderChoice, p: Point, robot: RobotEnvelope = RobotEnvelope.LUBA2): Double =
        distance(choice, p) - RecordedBoundary.centerInsetM(robot)

    /** Border nearest to a tap on the map, within [maxDistanceM]; no-go zones win ties. */
    fun nearest(map: RobotMap, p: Point, maxDistanceM: Double = 1.5): BorderChoice? =
        choices(map).map { it to distance(it, p) }.filter { it.second <= maxDistanceM }
            .minWithOrNull(compareBy<Pair<BorderChoice, Double>> { it.second }.thenBy { if (it.first.noGo) 0 else 1 })?.first

    /**
     * Raw loops at offset [offsetM]: outer boundary of the zone shrunk by [offsetM], or perimeter of the no-go zone grown
     * by [offsetM]. The other no-go zones (at the same offset), the learned obstacles and the base safety zone
     * ([reserves]) are bypassed.
     */
    internal fun rawLoops(map: RobotMap, choice: BorderChoice, offsetM: Double, reserves: List<JtsGeometry>): List<List<Point>> {
        val shape = polygon(choice.shape.points)
        val noGos = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 && it.id != choice.shape.id }.map { polygon(it.points) }
        val area = if (choice.noGo) {
            // Perimeter: reserves touching the loop are enclosed (bypassed on the outside).
            var around = shape.buffer(offsetM, 16)
            reserves.filter { it.intersects(around) }.forEach { around = around.union(it) }
            around
        } else {
            var inside = shape.buffer(-offsetM, 16)
            noGos.filter { it.intersects(shape) }.forEach { inside = inside.difference(it.buffer(offsetM, 16)) }
            reserves.forEach { inside = inside.difference(it) }
            inside
        }
        return polygons(area).filter { it.area >= MIN_LOOP_AREA_M2 }.sortedByDescending { it.area }.map(::exterior)
    }

    /** Corners rounded to radius [radiusM]: inwards for a zone, filled around a no-go zone. */
    private fun rounded(loop: List<Point>, noGo: Boolean, radiusM: Double): List<List<Point>> {
        if (radiusM <= 0) return listOf(loop)
        val p = polygon(loop)
        val g = if (noGo) p.buffer(radiusM, 16).buffer(-radiusM, 16) else p.buffer(-radiusM, 16).buffer(radiusM, 16)
        return polygons(g).filter { it.area >= MIN_LOOP_AREA_M2 }.map(::exterior)
    }

    /** Bypassed reserves: learned obstacles (chassis pivot included) and base safety zone. */
    private fun reserves(routes: TrajectoryPlanner, obstacles: List<LocalObstacle>, robot: RobotEnvelope): List<JtsGeometry> =
        obstacles.map { factory.createPoint(Coordinate(it.x, it.y)).buffer(it.exclusionRadiusM + robot.turnClearanceM + 0.03, 16) } +
            listOfNotNull(routes.baseSafety?.let { (station, _, radius) -> factory.createPoint(Coordinate(station.x, station.y)).buffer(radius + 0.05, 16) })

    private fun usable(routes: TrajectoryPlanner, loop: List<Point>) = loop.size >= 4 && routes.pathSafe(loop, finalHeading = firstHeading(loop))

    /** Kept loop: applied offset, corner rounding radius and drivable loops. */
    internal data class Fit(val offsetM: Double, val roundingM: Double, val loops: List<List<Point>>)

    /**
     * First drivable loop: requested offset, corners rounded more and more, then offset increased in 2 cm steps (at most
     * [MAX_EXTRA_M]). Each loop passes the mission navigation checks (center area, chassis envelope, base, obstacles).
     * The offset is the gap between the side of the robot and the boundary: center half a width further.
     */
    internal fun fit(routes: TrajectoryPlanner, map: RobotMap, choice: BorderChoice, offsetM: Double, obstacles: List<LocalObstacle>,
                     robot: RobotEnvelope = RobotEnvelope.LUBA2, checkpoint: () -> Unit = {}): Fit? {
        val reserves = reserves(routes, obstacles, robot)
        val requested = offsetM.coerceAtLeast(MIN_APPLIED_M)
        val radii = listOf(0.0, robot.lengthM / 2, robot.lengthM, robot.lengthM * 1.5)
        val steps = (MAX_EXTRA_M / EXTRA_STEP_M).toInt()
        for (step in 0..steps) {
            val applied = requested + step * EXTRA_STEP_M
            val raw = rawLoops(map, choice, RecordedBoundary.centerInsetM(robot) + applied, reserves)
            if (raw.isEmpty()) return null
            for (radius in radii) {
                checkpoint()
                val loops = raw.flatMap { rounded(it, choice.noGo, radius) }
                if (loops.isNotEmpty() && loops.all { usable(routes, it) }) return Fit(applied, radius, loops)
            }
        }
        return null
    }

    /** Loops ordered from nearest to farthest, each opened at the nearest drivable entry point. */
    internal fun ordered(routes: TrajectoryPlanner, loops: List<List<Point>>, from: Point): List<List<Point>> {
        val remaining = loops.toMutableList()
        val result = mutableListOf<List<Point>>()
        var position = from
        while (remaining.isNotEmpty()) {
            val next = remaining.minBy { loop -> loop.minOf { it.distance(position) } }
            remaining.remove(next)
            val entries = LoopEntries.entries(next, position).sortedBy { it.point.distance(position) }.take(24)
            val entered = entries.firstOrNull { routes.supportsPivot(it.point) && usable(routes, it.path) }?.path
                ?: entries.firstOrNull { usable(routes, it.path) }?.path ?: next
            result.add(entered)
            position = entered.last()
        }
        return result
    }

    /** Minimum and median gaps between the side of the robot on the loops (every 5 cm) and the recorded boundary. */
    internal fun distances(choice: BorderChoice, loops: List<List<Point>>): Pair<Double, Double> {
        val samples = loops.flatMap { loop ->
            loop.zipWithNext().flatMap { (a, b) ->
                val n = maxOf(1, (a.distance(b) / 0.05).toInt())
                (0 until n).map { i -> Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n) }
            }
        }.map { clearance(choice, it) }.sorted()
        if (samples.isEmpty()) return 0.0 to 0.0
        return samples.first() to samples[samples.size / 2]
    }

    /**
     * Full computation and preparation: same mission navigation and same route preparation as mowing from the map.
     * Start nearest to the robot, end at the base.
     */
    fun prepare(map: RobotMap, shapeId: Long, offsetM: Double, start: Point, base: MapBase, accesses: List<List<Point>>,
                obstacles: List<LocalObstacle>, speedMps: Double, checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): BorderMission {
        require(map.complete && !map.source.contains("DÉMONSTRATION", true)) { tr("A complete real map is required", "Carte réelle complète obligatoire") }
        requireNotNull(base.entryHeadingDegrees) { tr("Base axis unknown: observe the robot while charging", "Axe de la base inconnu : observer le robot en charge") }
        require(offsetM.isFinite() && offsetM in 0.0..MAX_OFFSET_M) { tr("Offset outside the 0–${(MAX_OFFSET_M * 100).toInt()} cm range", "Retrait hors plage 0–${(MAX_OFFSET_M * 100).toInt()} cm") }
        val choice = requireNotNull(choices(map).firstOrNull { it.shape.id == shapeId }) { tr("Border not on the map: choose another one", "Bordure absente de la carte : choisissez-en une autre") }
        // Loop reserves identical to navigation: margin adjusted near the docking corridor.
        val fitted = DockClearance.fit(obstacles, base)
        val routes = RoutePlanning.mission(map, fitted, accesses, base)
        val from = RoutePlanning.missionStart(routes, start, docked = false, base = base.point) ?: base.point
        progress(tr("Computing the border loop", "Calcul du tour de bordure"))
        val fit = requireNotNull(fit(routes, map, choice, offsetM, fitted, checkpoint = checkpoint)) {
            val low = (offsetM.coerceAtLeast(MIN_APPLIED_M) * 100).toInt()
            val high = ((offsetM.coerceAtLeast(MIN_APPLIED_M) + MAX_EXTRA_M) * 100).toInt()
            tr("No drivable loop for the chassis between $low and $high cm from this border (passage too narrow, no-go, obstacle or base too close)",
                "Aucun tour praticable pour le châssis entre $low et $high cm de cette bordure (passage trop étroit, no-go, obstacle ou base trop proche)")
        }
        checkpoint()
        val loops = ordered(routes, fit.loops, from)
        val (minimum, median) = distances(choice, loops)
        val meters = loops.sumOf { loop -> loop.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
        // Border passes only, blades running; empty zoneIds: links always with blades stopped.
        val preview = Preview(emptyList(), meters, 0, 0, CoverageMode.LINES, loops,
            outerLoops = if (choice.noGo) 0 else loops.size, noGoLoops = if (choice.noGo) loops.size else 0,
            startWithBorders = true, borderKinds = List(loops.size) { choice.noGo }, borderAnchors = loops.map { it.first() },
            plannerLabel = PLANNER_LABEL)
        progress(tr("Checking the links", "Vérification des raccords"))
        val engine = MowingSimulation(routes, base.point, preview, map,
            SimConfig(speedMps = speedMps, instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees,
                baseApproachMeters = base.approachDistanceM), bladeZones = emptyList()) { progress(it); checkpoint() }
        return BorderMission(choice, offsetM, fit.offsetM, fit.roundingM, loops, minimum, median, preview, engine)
    }
}
