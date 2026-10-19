package fr.luba.poc.planning

import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.hypot

/**
 * Slanted entry and exit of a no-go loop. The loop runs half a width plus the set margin from the outline: a pivot on
 * it makes the chassis corners sweep the pivot radius (43 cm), i.e. 17 cm of the margin (2026-10-15: entry of the loop
 * of a no-go zone set to 25 cm, corner 7.6 cm from the outline, bumper contact). The robot therefore pivots further
 * out, where the whole pivot keeps the margin, then joins the loop along a curve that departs from it progressively
 * (quadratic offset: tangent arrival, no pivot on the loop); the exit is symmetric.
 * The stripe travelled twice runs along the loop, on the outer side: nothing is cut closer to the no-go zone.
 */
internal class NoGoLoopLeads(holes: List<List<Point>>, private val robot: RobotEnvelope, private val marginM: Double) {
    /** Rotated loop ([loop], closed) extended with its entry and its exit. */
    class Leaded(val leadIn: List<Point>, val loop: List<Point>, val leadOut: List<Point>) {
        val path: List<Point> get() = leadIn + loop + leadOut
        /** Entry with the first loop segment (check of the tangent link). */
        val entryCheck: List<Point> get() = leadIn + loop.take(2)
        /** Last loop segment with the exit. */
        val exitCheck: List<Point> get() = loop.takeLast(2) + leadOut
    }

    private val factory = GeometryFactory()
    private val noGo: Geometry? = holes.filter { it.size >= 3 }.map { points ->
        val ring = if (points.first() == points.last()) points else points + points.first()
        factory.createPolygon(ring.map { Coordinate(it.x, it.y) }.toTypedArray()) as Geometry
    }.takeIf { it.isNotEmpty() }?.let { org.locationtech.jts.operation.union.UnaryUnionOp.union(it) }

    /** Distance from the centre to a no-go outline from which a full pivot keeps the margin. */
    val pivotClearanceM: Double get() = robot.turnClearanceM + marginM + 0.01

    private fun clearance(p: Point): Double = noGo?.distance(factory.createPoint(Coordinate(p.x, p.y))) ?: Double.POSITIVE_INFINITY

    /** null: no pivot of the loop cuts into the margin, or no entry possible further out (the loop stays as is). */
    fun lead(loop: List<Point>): Leaded? {
        if (noGo == null || loop.size < 4 || loop.first().distance(loop.last()) > 1e-8) return null
        val cumulative = DoubleArray(loop.size)
        for (i in 1 until loop.size) cumulative[i] = cumulative[i - 1] + loop[i - 1].distance(loop[i])
        val total = cumulative.last()
        if (total < 1e-6) return null
        // Loop already far enough out (next pass of a multiple loop): nothing to do.
        if (clearance(loop.first()) >= pivotClearanceM) return null
        fun at(arc: Double): Point {
            val s = ((arc % total) + total) % total
            var i = java.util.Arrays.binarySearch(cumulative, s).let { if (it >= 0) it else -it - 2 }.coerceIn(0, loop.size - 2)
            while (i < loop.size - 2 && cumulative[i + 1] - cumulative[i] < 1e-12) i++
            val length = cumulative[i + 1] - cumulative[i]
            val t = if (length < 1e-12) 0.0 else (s - cumulative[i]) / length
            val a = loop[i]; val b = loop[i + 1]
            return Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
        }
        // The loop surrounds the no-go zone: the outside is on the right of a counter-clockwise loop.
        val area = loop.zipWithNext().sumOf { (a, b) -> a.x * b.y - b.x * a.y }
        val side = if (area > 0) 1.0 else -1.0
        fun outward(arc: Double): Point {
            val a = at(arc - 0.1); val b = at(arc + 0.1)
            val dx = b.x - a.x; val dy = b.y - a.y; val norm = hypot(dx, dy)
            return if (norm < 1e-9) Point(0.0, 0.0) else Point(side * dy / norm, -side * dx / norm)
        }
        val steps = 8
        for (length in listOf(1.2, 1.6)) {
            if (2 * length > total * 0.9) continue
            for (offset in listOf(0.0, 0.05, 0.1, 0.15, 0.2, 0.3)) {
                val delta = (pivotClearanceM - clearance(loop.first())).coerceAtLeast(0.0) + 0.02 + offset
                fun shifted(arc: Double, distance: Double): Point {
                    val p = at(arc); val n = outward(arc); val ratio = distance / length
                    return Point(p.x + n.x * delta * ratio * ratio, p.y + n.y * delta * ratio * ratio)
                }
                val leadIn = (steps downTo 1).map { k -> shifted(-length * k / steps, length * k / steps) }
                val leadOut = (1..steps).map { k -> shifted(length * k / steps, length * k / steps) }
                if (clearance(leadIn.first()) >= pivotClearanceM && clearance(leadOut.last()) >= pivotClearanceM)
                    return Leaded(leadIn, loop, leadOut)
            }
        }
        return null
    }
}
