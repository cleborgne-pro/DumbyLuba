package fr.luba.poc.map

import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import kotlin.math.ceil
import kotlin.math.hypot
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString

/**
 * Narrow passages of a zone on a recorded boundary ([CoveragePlanner.plan] with `recordedBoundary`): necks too narrow for
 * the pivot radius, which the coverage area and the border loops leave out (their outline is opened by
 * [RobotEnvelope.turnClearanceM], so a passage under about 1.5 m between two parts of a zone was never mowed), but where
 * the robot drives straight with its chassis inside the zone and the loop margin (its transits already go through).
 *
 * A neck is a part of the *corridor* (robot centres at half a width plus the outer loop margin from the boundary, the
 * no-go zones and the obstacles) outside the same corridor opened by the pivot radius, touching the opened part in two
 * places at least (a sharp corner touches it in one place: it cannot be left without reversing). Its rows run along the
 * line between its two widest mouths and cross it whole in one straight stretch of the corridor, at most one spacing
 * apart (a bent passage has none). Each row is a pass extended at both ends into the wide parts up to the first point
 * where the robot can pivot whole, then checked by the caller (chassis envelope). What the rows sweep inside the neck
 * extends the cutting target ([fr.luba.poc.planning.PlanningTerrain]), so the coverage counts the passage.
 */
internal object NarrowPassages {
    /** Smallest neck kept, in m² of robot-centre corridor. */
    const val MIN_NECK_AREA_M2 = 0.02
    /** A pass end is moved into the wide part by at most this much to find a point where the robot pivots whole. */
    const val MAX_EXTENSION_M = 2.0
    /** Cell ids of the narrow passage passes, apart from the sweep cells (angle × 100000 + cell) and the spiral chains. */
    const val NECK_CELL = 1_000_000_000
    /** Step of the search for the rows crossing a neck and for the pivot points. */
    private const val STEP_M = 0.02
    private val factory = GeometryFactory()

    /**
     * Row of a neck: the line through [origin] along [dir]; robot centres from [first] to [last] (abscissae along it)
     * inside the neck, the straight stretch of the corridor from [start] to [end] around them.
     */
    class Row(val origin: Point, val dir: Point, val first: Double, val last: Double, val start: Double, val end: Double) {
        fun at(s: Double) = Point(origin.x + dir.x * s, origin.y + dir.y * s)
    }

    /** Rows of one neck, in order across it. */
    class Neck(val rows: List<Row>)

    /** Robot centres with the chassis inside [zone] at [outerOffsetM] from it and from [holes], and the same opened. */
    private fun corridor(zone: List<Point>, holes: List<List<Point>>, obstacles: List<ObstacleDisc>, robot: RobotEnvelope,
                         outerOffsetM: Double): Pair<JtsGeometry, JtsGeometry> {
        val inset = RecordedBoundary.centerInsetM(robot) + outerOffsetM
        val rounding = RecordedBoundary.ringRoundingM(robot)
        val area = factory.createPolygon((zone + zone.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(0.0)
        val blocked = holes.map { hole ->
            factory.createPolygon((hole + hole.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(inset, 16) } +
            obstacles.map { factory.createPoint(Coordinate(it.x, it.y)).buffer(it.radiusM, 16) }
        fun free(g: JtsGeometry) = blocked.fold(g) { acc, b -> acc.difference(b) }
        return free(area.buffer(-inset, 16)) to free(area.buffer(-(inset + rounding), 16).buffer(rounding, 16))
    }

    private fun parts(g: JtsGeometry): List<JtsGeometry> = (0 until g.numGeometries).map { g.getGeometryN(it) }.filter { !it.isEmpty }
    private fun centre(g: JtsGeometry) = g.centroid.coordinate.let { Point(it.x, it.y) }

    /** Necks of [zone] (recorded boundary) with their rows [spacing] apart at most, deterministic. */
    fun necks(zone: List<Point>, holes: List<List<Point>>, obstacles: List<ObstacleDisc>, robot: RobotEnvelope,
              outerOffsetM: Double, spacing: Double): List<Neck> = try {
        val (corridor, opened) = corridor(zone, holes, obstacles, robot, outerOffsetM)
        val rest = if (corridor.isEmpty) corridor else corridor.difference(opened.buffer(1e-6, 8))
        parts(rest).filter { it.area >= MIN_NECK_AREA_M2 && !it.buffer(-0.005, 8).isEmpty }
            .sortedWith(compareBy({ centre(it).x }, { centre(it).y }))
            .mapNotNull { neck -> rows(neck, corridor, opened, spacing)?.let(::Neck) }
    } catch (e: org.locationtech.jts.geom.TopologyException) { emptyList() }

    /** Rows crossing [neck] whole from one mouth to the other, [spacing] apart at most; null when there is none. */
    private fun rows(neck: JtsGeometry, corridor: JtsGeometry, opened: JtsGeometry, spacing: Double): List<Row>? {
        val mouths = parts(neck.buffer(STEP_M, 8).intersection(opened)).filter { it.area > 1e-4 }.sortedByDescending { it.area }
        if (mouths.size < 2) return null
        val a = centre(mouths[0]); val b = centre(mouths[1])
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length < 1e-6) return null
        val dir = Point((b.x - a.x) / length, (b.y - a.y) / length)
        val normal = Point(-dir.y, dir.x)
        val offsets = neck.coordinates.map { (it.x - a.x) * normal.x + (it.y - a.y) * normal.y }
        // Rows every STEP_M across the neck that cross it from the level of one mouth to the other in one straight stretch.
        val crossing = (0..((offsets.max() - offsets.min()) / STEP_M).toInt()).mapNotNull { k ->
            row(neck, corridor, Point(a.x + normal.x * (offsets.min() + k * STEP_M), a.y + normal.y * (offsets.min() + k * STEP_M)), dir)
                ?.takeIf { it.start <= 0.0 && it.end >= length }?.let { k to it }
        }
        if (crossing.isEmpty()) return null
        // The widest run of consecutive crossing rows, then rows at its two sides and at most one spacing apart between.
        val runs = crossing.fold(mutableListOf<MutableList<Pair<Int, Row>>>()) { acc, r ->
            if (acc.lastOrNull()?.last()?.first == r.first - 1) acc.last().add(r) else acc.add(mutableListOf(r)); acc }
        val widest = runs.maxBy { it.size }
        val count = if (widest.size == 1) 1 else ceil((widest.size - 1) * STEP_M / spacing - 1e-9).toInt() + 1
        return (0 until count).map { i -> widest[if (count == 1) widest.size / 2 else (widest.size - 1) * i / (count - 1)].second }
    }

    /** Line through [origin] along [dir]: the straight stretch of [corridor] crossing [neck] and the part inside it. */
    private fun row(neck: JtsGeometry, corridor: JtsGeometry, origin: Point, dir: Point): Row? {
        val far = 1000.0
        val line = factory.createLineString(arrayOf(Coordinate(origin.x - dir.x * far, origin.y - dir.y * far),
            Coordinate(origin.x + dir.x * far, origin.y + dir.y * far)))
        val stretch = parts(corridor.intersection(line)).filterIsInstance<LineString>().firstOrNull { it.intersects(neck) } ?: return null
        val inside = parts(stretch.intersection(neck)).flatMap { it.coordinates.toList() }
        if (inside.isEmpty()) return null
        fun along(c: Coordinate) = (c.x - origin.x) * dir.x + (c.y - origin.y) * dir.y
        return Row(origin, dir, inside.minOf(::along), inside.maxOf(::along), stretch.coordinates.minOf(::along), stretch.coordinates.maxOf(::along))
    }

    /** Cutting area added by the necks: what their rows sweep inside them, half a cut on each side. */
    fun target(necks: List<Neck>, cutWidthM: Double): JtsGeometry? = necks.flatMap { it.rows }.map { r ->
        factory.createLineString(arrayOf(r.at(r.first), r.at(r.last)).map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(cutWidthM / 2, 16)
    }.reduceOrNull { x, y -> x.union(y) }

    /**
     * Passes of each neck (one list per neck, directions alternating): its rows extended at both ends to the first point
     * where [pivotFree] (whole pivot without collision) beyond the neck, within [MAX_EXTENSION_M], kept if [drivable].
     */
    fun passes(necks: List<Neck>, pivotFree: (Point) -> Boolean, drivable: (List<Point>) -> Boolean): List<List<List<Point>>> =
        necks.map { neck ->
            neck.rows.mapNotNull { r ->
                /** First pivot point from [from] towards [limit], at most [MAX_EXTENSION_M] away. */
                fun pivot(from: Double, limit: Double): Double? {
                    val sign = if (limit >= from) 1.0 else -1.0
                    var s = from
                    while (sign * (limit - s) >= -1e-9 && sign * (s - from) <= MAX_EXTENSION_M + 1e-9) {
                        if (pivotFree(r.at(s))) return s
                        s += sign * STEP_M
                    }
                    return null
                }
                val low = pivot(r.first, r.start) ?: return@mapNotNull null
                val high = pivot(r.last, r.end) ?: return@mapNotNull null
                listOf(r.at(low), r.at(high)).takeIf(drivable)
            }.mapIndexed { k, path -> if (k % 2 == 0) path else path.reversed() }
        }

    /**
     * [preview] of [zone] with the passes of its necks appended (runs, cells, passes, metres), checked with [envelope]:
     * whole pivot free at both ends, chassis along. They lie outside the coverage area, so they are never joined by a
     * U-turn: each pass is its own run, linked by the transits.
     */
    fun appendTo(preview: Preview, zone: List<Point>, holes: List<List<Point>>, obstacles: List<ObstacleDisc>, robot: RobotEnvelope,
                 outerOffsetM: Double, spacing: Double, envelope: TrajectoryEnvelope): Preview {
        val passes = passes(necks(zone, holes, obstacles, robot, outerOffsetM, spacing),
            { p -> envelope.turnCollision(p, 0.0, 180.0) == null && envelope.turnCollision(p, 180.0, 360.0) == null },
            { envelope.pathCollision(it) == null })
        val runs = passes.flatten()
        if (runs.isEmpty()) return preview
        val cells = passes.flatMapIndexed { k, neck -> List(neck.size) { NECK_CELL + k } }
        return preview.copy(runs = preview.runs + runs, passes = preview.passes + runs,
            meters = preview.meters + runs.sumOf { it.zipWithNext().sumOf { (a, b) -> a.distance(b) } },
            runCellIds = if (preview.runCellIds.size == preview.runs.size) preview.runCellIds + cells else preview.runCellIds)
    }
}
