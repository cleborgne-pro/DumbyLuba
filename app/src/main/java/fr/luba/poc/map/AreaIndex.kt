package fr.luba.poc.map

import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.Polygon
import kotlin.math.*

/** Fast inclusion test in a fixed polygonal area (polygons and holes).
 *
 * Equivalent to `PreparedGeometry.covers` on the area grown by [tolerance]: a point is covered if it is inside or
 * closer than [tolerance] to the edge; a segment is covered if it has no clear crossing of the edge and every portion
 * between two contacts stays covered. Edges are indexed in a grid, without a JTS allocation per query. Any edge that
 * clearly crosses the boundary is refused: the result is never more permissive than JTS beyond the tolerance.
 * Immutable instance, usable from several threads.
 */
class AreaIndex(area: JtsGeometry, private val tolerance: Double = 1e-5) {
    private val ax: DoubleArray
    private val ay: DoubleArray
    private val bx: DoubleArray
    private val by: DoubleArray
    private val minX: Double
    private val minY: Double
    private val cell: Double
    private val cols: Int
    private val rows: Int
    /** Edges closer than [tolerance] to each cell. */
    private val cells: Array<IntArray>
    /** Edges whose vertical extent overlaps each row (ray casting). */
    private val bands: Array<IntArray>
    /** Length of each edge (precomputed). */
    private val lengths: DoubleArray
    /** Cells without a nearby edge: 1 inside, 2 outside, 0 to test. */
    private val cellState: ByteArray
    private val maxXs: DoubleArray
    private val minXs: DoubleArray
    /** Edges of each row sorted by decreasing maximum / increasing minimum abscissa. */
    private val byMaxX: Array<IntArray>
    private val byMinX: Array<IntArray>
    val isEmpty: Boolean

    init {
        require(tolerance.isFinite() && tolerance >= 0.0)
        val xs1 = ArrayList<Double>(); val ys1 = ArrayList<Double>()
        val xs2 = ArrayList<Double>(); val ys2 = ArrayList<Double>()
        fun ring(coordinates: Array<org.locationtech.jts.geom.Coordinate>) {
            for (i in 0 until coordinates.size - 1) {
                val a = coordinates[i]; val b = coordinates[i + 1]
                if (a.x == b.x && a.y == b.y) continue
                xs1.add(a.x); ys1.add(a.y); xs2.add(b.x); ys2.add(b.y)
            }
        }
        fun visit(g: JtsGeometry) {
            if (g is Polygon) {
                if (g.isEmpty) return
                ring(g.exteriorRing.coordinates)
                for (k in 0 until g.numInteriorRing) ring(g.getInteriorRingN(k).coordinates)
            } else if (g.numGeometries > 1 || g.getGeometryN(0) !== g) {
                for (k in 0 until g.numGeometries) visit(g.getGeometryN(k))
            }
        }
        if (!area.isEmpty) visit(area)
        ax = xs1.toDoubleArray(); ay = ys1.toDoubleArray(); bx = xs2.toDoubleArray(); by = ys2.toDoubleArray()
        lengths = DoubleArray(ax.size) { hypot(bx[it] - ax[it], by[it] - ay[it]) }
        maxXs = DoubleArray(ax.size) { max(ax[it], bx[it]) }
        minXs = DoubleArray(ax.size) { min(ax[it], bx[it]) }
        isEmpty = ax.isEmpty()
        val pad = tolerance + 1e-9
        if (isEmpty) {
            minX = 0.0; minY = 0.0; cell = 1.0; cols = 1; rows = 1
            cells = arrayOf(IntArray(0)); bands = arrayOf(IntArray(0)); cellState = ByteArray(1) { 2 }
            byMaxX = bands; byMinX = bands
        } else {
            var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
            var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
            for (i in ax.indices) {
                x0 = min(x0, min(ax[i], bx[i])); x1 = max(x1, max(ax[i], bx[i]))
                y0 = min(y0, min(ay[i], by[i])); y1 = max(y1, max(ay[i], by[i]))
            }
            minX = x0 - pad; minY = y0 - pad
            val width = x1 - x0 + 2 * pad; val height = y1 - y0 + 2 * pad
            var size = sqrt(max(width * height, 1e-6) / ax.size).coerceIn(0.05, 5.0)
            while ((width / size + 1) * (height / size + 1) > 1_000_000) size *= 1.5
            cell = size
            cols = max(1, ceil(width / size).toInt()); rows = max(1, ceil(height / size).toInt())
            val cellLists = Array(cols * rows) { IntArrayBuilder() }
            val bandLists = Array(rows) { IntArrayBuilder() }
            for (i in ax.indices) {
                forCells(ax[i], ay[i], bx[i], by[i], pad) { c -> cellLists[c].add(i) }
                val r0 = row(min(ay[i], by[i])); val r1 = row(max(ay[i], by[i]))
                for (r in r0..r1) bandLists[r].add(i)
            }
            cells = Array(cellLists.size) { cellLists[it].toArray() }
            bands = Array(rows) { bandLists[it].toArray() }
            byMaxX = Array(rows) { r -> bands[r].sortedByDescending { maxXs[it] }.toIntArray() }
            byMinX = Array(rows) { r -> bands[r].sortedBy { minXs[it] }.toIntArray() }
            // No edge closer than the tolerance in the cell: all its points have
            // the same status as its center (no boundary crosses it).
            cellState = ByteArray(cols * rows) { c ->
                if (cells[c].isNotEmpty()) 0 else {
                    val x = minX + (c % cols + 0.5) * cell; val y = minY + (c / cols + 0.5) * cell
                    if (rayInside(x, y)) 1 else 2
                }
            }
        }
    }
    /** Parity of the number of crossings of a horizontal ray; the less loaded side
     * of the row is chosen (same result for a point off the boundary). */
    private fun rayInside(x: Double, y: Double): Boolean {
        val r = row(y)
        val right = byMaxX[r]; val left = byMinX[r]
        val rightCount = countAbove(right, maxXs, x); val leftCount = countBelow(left, minXs, x)
        var inside = false
        if (rightCount <= leftCount) {
            for (k in 0 until rightCount) { val i = right[k]
                if ((ay[i] > y) != (by[i] > y) && x < (bx[i] - ax[i]) * (y - ay[i]) / (by[i] - ay[i]) + ax[i]) inside = !inside }
        } else {
            for (k in 0 until leftCount) { val i = left[k]
                if ((ay[i] > y) != (by[i] > y) && x > (bx[i] - ax[i]) * (y - ay[i]) / (by[i] - ay[i]) + ax[i]) inside = !inside }
        }
        return inside
    }
    /** Edges of the row sorted by decreasing maximum / increasing minimum abscissa. */
    /** Number of edges (decreasing sort) whose maximum abscissa exceeds x. */
    private fun countAbove(order: IntArray, values: DoubleArray, x: Double): Int {
        var lo = 0; var hi = order.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (values[order[mid]] >= x) lo = mid + 1 else hi = mid }
        return lo
    }
    /** Number of edges (increasing sort) whose minimum abscissa is below x. */
    private fun countBelow(order: IntArray, values: DoubleArray, x: Double): Int {
        var lo = 0; var hi = order.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (values[order[mid]] <= x) lo = mid + 1 else hi = mid }
        return lo
    }
    /** Marks the edges already examined during a query (one buffer per thread,
     * shared by all indexes: a single ThreadLocal entry). */
    private class Marks { var seen = IntArray(64); var generation = 0 }
    private fun nextGeneration(): Marks {
        val m = MARKS.get()
        if (m.seen.size < ax.size) { m.seen = IntArray(max(ax.size, m.seen.size * 2)); m.generation = 0 }
        if (++m.generation == Int.MAX_VALUE) { m.seen.fill(0); m.generation = 1 }
        return m
    }
    private companion object { val MARKS: ThreadLocal<Marks> = ThreadLocal.withInitial { Marks() } }

    private class IntArrayBuilder {
        private var data = IntArray(4); private var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }
    private fun row(y: Double) = floor((y - minY) / cell).toInt().coerceIn(0, rows - 1)
    private fun col(x: Double) = floor((x - minX) / cell).toInt().coerceIn(0, cols - 1)

    /** Cells crossed by the segment grown by [pad], row by row. */
    private inline fun forCells(x1: Double, y1: Double, x2: Double, y2: Double, pad: Double, action: (Int) -> Unit) {
        val r0 = row(min(y1, y2) - pad); val r1 = row(max(y1, y2) + pad)
        for (r in r0..r1) {
            val bandLow = minY + r * cell - pad; val bandHigh = minY + (r + 1) * cell + pad
            var lo: Double; var hi: Double
            if (abs(y2 - y1) < 1e-15) { lo = min(x1, x2); hi = max(x1, x2) }
            else {
                val ta = ((bandLow - y1) / (y2 - y1)).coerceIn(0.0, 1.0)
                val tb = ((bandHigh - y1) / (y2 - y1)).coerceIn(0.0, 1.0)
                val xa = x1 + (x2 - x1) * ta; val xb = x1 + (x2 - x1) * tb
                lo = min(xa, xb); hi = max(xa, xb)
            }
            val c0 = col(lo - pad); val c1 = col(hi + pad)
            for (c in c0..c1) action(r * cols + c)
        }
    }

    private fun inGrid(x: Double, y: Double) =
        x >= minX && y >= minY && x <= minX + cols * cell && y <= minY + rows * cell

    private fun segmentDistanceSquared(px: Double, py: Double, i: Int): Double {
        val dx = bx[i] - ax[i]; val dy = by[i] - ay[i]
        val t = (((px - ax[i]) * dx + (py - ay[i]) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val qx = ax[i] + t * dx - px; val qy = ay[i] + t * dy - py
        return qx * qx + qy * qy
    }

    private fun pointSegmentSquared(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1; val d2 = dx * dx + dy * dy
        val t = if (d2 == 0.0) 0.0 else (((px - x1) * dx + (py - y1) * dy) / d2).coerceIn(0.0, 1.0)
        val qx = x1 + t * dx - px; val qy = y1 + t * dy - py
        return qx * qx + qy * qy
    }
    /** Minimum squared distance between two segments; 0 if they touch or cross. */
    private fun segmentDistanceSquared(ax1: Double, ay1: Double, ax2: Double, ay2: Double,
                                       bx1: Double, by1: Double, bx2: Double, by2: Double): Double {
        val d1 = (ax2 - ax1) * (by1 - ay1) - (ay2 - ay1) * (bx1 - ax1)
        val d2 = (ax2 - ax1) * (by2 - ay1) - (ay2 - ay1) * (bx2 - ax1)
        val d3 = (bx2 - bx1) * (ay1 - by1) - (by2 - by1) * (ax1 - bx1)
        val d4 = (bx2 - bx1) * (ay2 - by1) - (by2 - by1) * (ax2 - bx1)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0.0
        return min(min(pointSegmentSquared(ax1, ay1, bx1, by1, bx2, by2), pointSegmentSquared(ax2, ay2, bx1, by1, bx2, by2)),
            min(pointSegmentSquared(bx1, by1, ax1, ay1, ax2, ay2), pointSegmentSquared(bx2, by2, ax1, ay1, ax2, ay2)))
    }

    fun coversPoint(x: Double, y: Double): Boolean {
        if (isEmpty || !inGrid(x, y)) return false
        val c = row(y) * cols + col(x)
        when (cellState[c].toInt()) { 1 -> return true; 2 -> return false }
        val limit = tolerance * tolerance
        for (i in cells[c]) if (segmentDistanceSquared(x, y, i) <= limit) return true
        return rayInside(x, y)
    }
    fun covers(p: Point) = coversPoint(p.x, p.y)

    fun covers(a: Point, b: Point): Boolean = coversSegment(a.x, a.y, b.x, b.y)

    fun coversSegment(x1: Double, y1: Double, x2: Double, y2: Double): Boolean {
        val dx = x2 - x1; val dy = y2 - y1
        val length = hypot(dx, dy)
        if (length < 1e-12) return coversPoint(x1, y1)
        if (!coversPoint(x1, y1) || !coversPoint(x2, y2)) return false
        var contacts = DoubleArray(8); var count = 0
        fun contact(t: Double) {
            if (t <= 0.0 || t >= 1.0) return
            if (count == contacts.size) contacts = contacts.copyOf(count * 2)
            contacts[count++] = t
        }
        val tol = tolerance
        var crossed = false
        val m = nextGeneration(); val seen = m.seen; val generation = m.generation
        forCells(x1, y1, x2, y2, tol) { c ->
            if (crossed) return@forCells
            for (i in cells[c]) {
                if (seen[i] == generation) continue
                seen[i] = generation
                val ex = bx[i] - ax[i]; val ey = by[i] - ay[i]
                val edge = lengths[i]
                // Signed distances of the edge ends to the segment line, and vice versa.
                val dc = (dx * (ay[i] - y1) - dy * (ax[i] - x1)) / length
                val dd = (dx * (by[i] - y1) - dy * (bx[i] - x1)) / length
                val da = (ex * (y1 - ay[i]) - ey * (x1 - ax[i])) / edge
                val db = (ex * (y2 - ay[i]) - ey * (x2 - ax[i])) / edge
                if (((dc > tol && dd < -tol) || (dc < -tol && dd > tol)) &&
                    ((da > tol && db < -tol) || (da < -tol && db > tol))) { crossed = true; return@forCells }
                if (abs(dc) <= tol) contact(((ax[i] - x1) * dx + (ay[i] - y1) * dy) / (length * length))
                if (abs(dd) <= tol) contact(((bx[i] - x1) * dx + (by[i] - y1) * dy) / (length * length))
                if ((dc > 0) != (dd > 0) && dc != dd) {
                    val s = dc / (dc - dd)
                    if (s in 0.0..1.0) {
                        val px = ax[i] + s * ex; val py = ay[i] + s * ey
                        contact(((px - x1) * dx + (py - y1) * dy) / (length * length))
                    }
                }
            }
        }
        if (crossed) return false
        if (count == 0) return coversPoint(x1 + dx / 2, y1 + dy / 2)
        contacts.sort(0, count)
        var previous = 0.0
        for (k in 0..count) {
            val t = if (k == count) 1.0 else contacts[k]
            if (t - previous > 1e-12) {
                val m = (previous + t) / 2
                if (!coversPoint(x1 + dx * m, y1 + dy * m)) return false
            }
            previous = t
        }
        return true
    }

    /** Convex polygon (at least three vertices, any orientation) strictly inside the
     * area and at a distance strictly greater than [clearance] from any edge.
     * Exact Euclidean distance, independent of [tolerance]: equivalent to
     * `containsProperly` of the polygon grown by a disc of radius [clearance].
     */
    fun containsConvex(xs: DoubleArray, ys: DoubleArray, n: Int, clearance: Double): Boolean {
        if (isEmpty || n < 3) return false
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        var area2 = 0.0
        for (k in 0 until n) {
            x0 = min(x0, xs[k]); x1 = max(x1, xs[k]); y0 = min(y0, ys[k]); y1 = max(y1, ys[k])
            val j = (k + 1) % n
            area2 += xs[k] * ys[j] - xs[j] * ys[k]
        }
        if (!inGrid(x0 - clearance, y0 - clearance) || !inGrid(x1 + clearance, y1 + clearance)) return false
        val sign = if (area2 >= 0) 1.0 else -1.0
        val limit = clearance * clearance
        fun insideConvex(px: Double, py: Double): Boolean {
            for (k in 0 until n) {
                val j = (k + 1) % n
                if (sign * ((xs[j] - xs[k]) * (py - ys[k]) - (ys[j] - ys[k]) * (px - xs[k])) < 0) return false
            }
            return true
        }
        val m = nextGeneration(); val seen = m.seen; val generation = m.generation
        val r0 = row(y0 - clearance); val r1 = row(y1 + clearance)
        for (r in r0..r1) {
            val low = minY + r * cell - clearance; val high = minY + (r + 1) * cell + clearance
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (k in 0 until n) {
                val j = (k + 1) % n
                val sx = xs[k]; val sy = ys[k]; val ex = xs[j]; val ey = ys[j]
                val t0: Double; val t1: Double
                if (abs(ey - sy) < 1e-15) { if (sy < low || sy > high) continue; t0 = 0.0; t1 = 1.0 }
                else {
                    val ta = (low - sy) / (ey - sy); val tb = (high - sy) / (ey - sy)
                    t0 = max(0.0, min(ta, tb)); t1 = min(1.0, max(ta, tb))
                    if (t0 > t1) continue
                }
                val xa = sx + (ex - sx) * t0; val xb = sx + (ex - sx) * t1
                lo = min(lo, min(xa, xb)); hi = max(hi, max(xa, xb))
            }
            if (lo > hi) continue
            for (cc in col(lo - clearance)..col(hi + clearance)) for (i in cells[r * cols + cc]) {
                if (seen[i] == generation) continue
                seen[i] = generation
                if (insideConvex(ax[i], ay[i]) || insideConvex(bx[i], by[i])) return false
                for (k in 0 until n) {
                    val j = (k + 1) % n
                    if (segmentDistanceSquared(ax[i], ay[i], bx[i], by[i], xs[k], ys[k], xs[j], ys[j]) <= limit) return false
                }
            }
        }
        val c = row(ys[0]) * cols + col(xs[0])
        return when (cellState[c].toInt()) { 1 -> true; 2 -> false; else -> rayInside(xs[0], ys[0]) }
    }

    /** Closed disc of radius [radius] strictly inside (distance to the edge > radius). */
    fun containsDisc(x: Double, y: Double, radius: Double): Boolean {
        if (isEmpty || !inGrid(x - radius, y - radius) || !inGrid(x + radius, y + radius)) return false
        val limit = radius * radius
        val m = nextGeneration(); val seen = m.seen; val generation = m.generation
        for (r in row(y - radius)..row(y + radius)) for (c in col(x - radius)..col(x + radius)) for (i in cells[r * cols + c]) {
            if (seen[i] == generation) continue
            seen[i] = generation
            if (segmentDistanceSquared(x, y, i) <= limit) return false
        }
        val c = row(y) * cols + col(x)
        return when (cellState[c].toInt()) { 1 -> true; 2 -> false; else -> rayInside(x, y) }
    }

    /** Solid triangle (convex hull of three points, possibly degenerate). */
    fun covers(a: Point, b: Point, c: Point): Boolean {
        val area2 = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val scale = max(1e-12, max(a.distance(b), max(b.distance(c), a.distance(c))))
        // Hull reduced to a segment: a single check (same result as the three sides).
        if (a == b) return covers(a, c)
        if (b == c || a == c) return covers(a, b)
        if (!covers(a, b) || !covers(b, c) || !covers(c, a)) return false
        if (abs(area2) <= 1e-12 * scale) return true
        // Counter-clockwise: the inside is on the left of each side.
        val p = if (area2 > 0) arrayOf(a, b, c) else arrayOf(a, c, b)
        val tol = tolerance
        val sides = Array(3) { k ->
            val s = p[k]; val e = p[(k + 1) % 3]
            val len = hypot(e.x - s.x, e.y - s.y)
            doubleArrayOf(s.x, s.y, (e.x - s.x) / len, (e.y - s.y) / len)
        }
        val m = nextGeneration(); val seen = m.seen; val generation = m.generation
        // Triangle cells only: horizontal extent of each row.
        val r0 = row(minOf(a.y, b.y, c.y) - tol); val r1 = row(maxOf(a.y, b.y, c.y) + tol)
        for (r in r0..r1) {
            val low = minY + r * cell - tol; val high = minY + (r + 1) * cell + tol
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (k in 0 until 3) {
                val s = p[k]; val e = p[(k + 1) % 3]
                val t0: Double; val t1: Double
                if (abs(e.y - s.y) < 1e-15) { if (s.y < low || s.y > high) continue; t0 = 0.0; t1 = 1.0 }
                else {
                    val ta = ((low - s.y) / (e.y - s.y)); val tb = ((high - s.y) / (e.y - s.y))
                    t0 = max(0.0, min(ta, tb)); t1 = min(1.0, max(ta, tb))
                    if (t0 > t1) continue
                }
                val xa = s.x + (e.x - s.x) * t0; val xb = s.x + (e.x - s.x) * t1
                lo = min(lo, min(xa, xb)); hi = max(hi, max(xa, xb))
            }
            if (lo > hi) continue
            for (cc in col(lo - tol)..col(hi + tol)) for (i in cells[r * cols + cc]) {
                if (seen[i] == generation) continue
                seen[i] = generation
                // Part of the edge strictly inside the triangle shrunk by the tolerance.
                var from = 0.0; var to = 1.0
                for (k in 0 until 3) {
                    val side = sides[k]
                    val f0 = side[2] * (ay[i] - side[1]) - side[3] * (ax[i] - side[0]) - tol
                    val f1 = side[2] * (by[i] - side[1]) - side[3] * (bx[i] - side[0]) - tol
                    if (f0 <= 0 && f1 <= 0) { to = -1.0; break }
                    if (f0 < 0) from = max(from, f0 / (f0 - f1))
                    else if (f1 < 0) to = min(to, f0 / (f0 - f1))
                    if (to - from <= 1e-12) break
                }
                if (to - from > 1e-12) return false
            }
        }
        return true
    }
}
