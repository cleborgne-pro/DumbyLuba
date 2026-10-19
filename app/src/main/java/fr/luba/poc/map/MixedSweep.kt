package fr.luba.poc.map

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*

/**
 * "Optimized" mode: the zone is split into sweep bands (boustrophedon cells, plus a cut where the pass width jumps
 * sharply, typically the corner of an L). Each band keeps the base orientation or takes another one if it needs
 * clearly fewer U-turns. Pure geometric computation: safety validation stays with the planner.
 */
internal object MixedSweep {
    private val factory = GeometryFactory()

    /** Equivalent in mowing meters of a U-turn (stop, pivot, link): ≈ 10 s measured on the robot (pivots ≈ 27 °/s,
     * 1.5 s per stop, docs/TRACKING_EXECUTION.md), related to the mission score. */
    const val TURN_COST_M = 2.7
    /** Minimum gain (in passes, hence in U-turns) to reorient a band. */
    const val MIN_TURNS_SAVED = 3
    /** Minimum area of a reoriented band, in m². */
    const val MIN_AREA_M2 = 2.0
    /** Length change from one pass to the next that opens a new band. */
    const val WIDTH_CHANGE_M = 0.6
    const val WIDTH_CHANGE_RATIO = 0.4
    /** Extra link when a band leaves its neighbour's chain. */
    const val SWITCH_COST_M = 6.0

    /** Pass in the rotated frame: interval [low, high] at ordinate y. */
    data class Row(val low: Double, val high: Double, val y: Double) { val length get() = high - low }
    /** Band: consecutive passes of the same cell. [chainParent]: previous band
     * separated only by a width jump (same passage, nothing in between). */
    class Strip(val rows: MutableList<Row>, val chainParent: Int?)
    data class Layout(val strips: List<Strip>, val step: Double)

    private fun parts(geometry: JtsGeometry): List<JtsGeometry> =
        if (geometry.numGeometries == 1 && geometry.getGeometryN(0) === geometry) listOf(geometry)
        else (0 until geometry.numGeometries).flatMap { parts(geometry.getGeometryN(it)) }

    /**
     * Same passes as the historical sweep (same box, same step, same phase); a non-zero [jump] also cuts a cell when a
     * pass end moves by more than [jump] meters.
     */
    fun layout(rotated: JtsGeometry, spacing: Double, phase: Double, jump: Double?): Layout {
        val box = rotated.envelopeInternal
        val count = max(1, ceil(box.height / spacing).toInt())
        val step = box.height / count
        data class Interval(val row: Row, val strip: Int)
        val strips = mutableListOf<Strip>()
        var previous = emptyList<Interval>()
        for (index in 0 until count) {
            val y = box.minY + (index + phase) * step
            val cut = rotated.intersection(factory.createLineString(arrayOf(Coordinate(box.minX - 1, y), Coordinate(box.maxX + 1, y))))
            val rows = parts(cut).filterIsInstance<LineString>().filter { it.length > 0.01 }
                .sortedBy { it.envelopeInternal.minX }.map { Row(it.envelopeInternal.minX, it.envelopeInternal.maxX, y) }
            val matches = rows.map { row -> previous.indices.filter { k ->
                min(row.high, previous[k].row.high) - max(row.low, previous[k].row.low) > 1e-7 } }
            previous = rows.mapIndexed { i, row ->
                val parents = matches[i]
                val single = parents.size == 1 && matches.count { parents[0] in it } == 1
                val parent = if (single) previous[parents[0]] else null
                // Cut also when the length changes clearly (start of a widening): a narrow band then stays without the tip of its
                // wider neighbour.
                val jumped = parent != null && jump != null &&
                    (abs(row.low - parent.row.low) > jump || abs(row.high - parent.row.high) > jump ||
                        abs(row.length - parent.row.length) > max(WIDTH_CHANGE_M, WIDTH_CHANGE_RATIO * min(row.length, parent.row.length)))
                val strip = if (parent != null && !jumped) parent.strip
                    else strips.size.also { strips.add(Strip(mutableListOf(), if (jumped) parent!!.strip else null)) }
                strips[strip].rows.add(row)
                Interval(row, strip)
            }
        }
        return Layout(strips, step)
    }

    /** Area covered by a band (rotated frame), bounded by the domain. */
    fun area(strip: Strip, step: Double, rotated: JtsGeometry): JtsGeometry {
        val rows = strip.rows
        val half = step / 2
        val right = listOf(Point(rows.first().high, rows.first().y - half)) + rows.map { Point(it.high, it.y) } +
            Point(rows.last().high, rows.last().y + half)
        val left = listOf(Point(rows.last().low, rows.last().y + half)) + rows.reversed().map { Point(it.low, it.y) } +
            Point(rows.first().low, rows.first().y - half)
        val ring = right + left
        val outline = ring.filterIndexed { i, p -> i == 0 || p.distance(ring[i - 1]) > 1e-9 }
        val coordinates = (outline + outline.first()).map { Coordinate(it.x, it.y) }.toTypedArray()
        if (coordinates.size < 4) return factory.createPolygon()
        val polygon = factory.createPolygon(coordinates).let { if (it.isValid) it else it.buffer(0.0) }
        return polygon.intersection(rotated)
    }

    /** Orientations tried for a band: perpendicular to the base, long axis of the band, directions of its two longest
     * edges, then [searched]. Degrees in [0, 180). */
    fun alternatives(cell: JtsGeometry, base: Int, searched: List<Int> = emptyList()): List<Int> {
        fun normalize(angle: Int) = ((angle % 180) + 180) % 180
        val result = mutableListOf(normalize(base + 90))
        if (cell.isEmpty) return result
        // Supporting edge of the minimum width: passes along the length.
        val support = MinimumDiameter(cell).supportingSegment.coordinates
        if (support.size >= 2 && support[0].distance(support[1]) > 1e-6) {
            result.add(normalize(Math.toDegrees(atan2(support[1].y - support[0].y, support[1].x - support[0].x)).roundToInt()))
        }
        parts(cell).filterIsInstance<Polygon>().flatMap { polygon ->
            polygon.exteriorRing.coordinates.toList().zipWithNext()
        }.sortedByDescending { (a, b) -> a.distance(b) }.take(2).forEach { (a, b) ->
            result.add(normalize(Math.toDegrees(atan2(b.y - a.y, b.x - a.x)).roundToInt()))
        }
        result.addAll(searched.map(::normalize))
        return result.distinct().filter { it != normalize(base) }
    }

    /** Passes of [cell] (original frame) at orientation [angle]: one list of passes per sub-cell. */
    fun fill(cell: JtsGeometry, angle: Int, spacing: Double): List<List<List<Point>>> {
        if (cell.isEmpty || cell.area < 1e-6) return emptyList()
        val radians = Math.toRadians(angle.toDouble())
        val rotated = AffineTransformation.rotationInstance(-radians).transform(cell)
        val inverse = AffineTransformation.rotationInstance(radians)
        val layout = layout(rotated, spacing, 0.5, null)
        return layout.strips.map { strip -> strip.rows.map { row ->
            val line = factory.createLineString(arrayOf(Coordinate(row.low, row.y), Coordinate(row.high, row.y)))
            (inverse.transform(line) as LineString).coordinates.map { Point(it.x, it.y) }
        } }
    }

    /** Estimated cost of a zigzag pass sequence: length + U-turns. */
    fun cost(passes: List<List<Point>>): Double =
        passes.sumOf { it.zipWithNext().sumOf { (a, b) -> a.distance(b) } } + max(0, passes.size - 1) * TURN_COST_M
}
