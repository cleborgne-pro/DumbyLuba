package fr.luba.poc.planning

import fr.luba.poc.map.Point
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*

/** Mowed surface measured on a fine grid (cell centres), same temporal definition
 * as the vector oracle: a pass extending the previous one does not revisit
 * the disc of its starting point. Cost linear in mowed length, without polygon
 * unions; the expected deviation stays in the order of one cell along the edges.
 */
internal object RasterCoverage {
    const val TARGET: Int = 1
    const val COVERED: Int = 2
    const val REPEATED: Int = 4

    /**
     * Grid of cell states ([TARGET], [COVERED], [REPEATED] bits) over an envelope; shared by the planner measure and the
     * real coverage of a recorded task ([fr.luba.poc.history.CoverageMap]).
     */
    class Grid(val minX: Double, val minY: Double, val cell: Double, val cols: Int, val rows: Int) {
        val state = ByteArray(cols * rows)
        /** Cells swept again by a fresh visit (not the continuation of the previous pass). */
        var extraCells = 0L
            private set

        fun centerX(c: Int): Double = minX + (c + 0.5) * cell
        fun centerY(r: Int): Double = minY + (r + 0.5) * cell

        /** Calls [action] with the index of every cell whose centre lies inside [g] (even-odd rule over all rings). */
        fun scan(g: JtsGeometry, action: (Int) -> Unit) {
            val edges = ArrayList<DoubleArray>()
            fun ring(c: Array<org.locationtech.jts.geom.Coordinate>) {
                for (i in 0 until c.size - 1) if (c[i].y != c[i + 1].y) edges.add(doubleArrayOf(c[i].x, c[i].y, c[i + 1].x, c[i + 1].y))
            }
            fun visit(g: JtsGeometry) {
                if (g is Polygon) {
                    if (g.isEmpty) return
                    ring(g.exteriorRing.coordinates)
                    for (k in 0 until g.numInteriorRing) ring(g.getInteriorRingN(k).coordinates)
                } else for (k in 0 until g.numGeometries) if (g.getGeometryN(k) !== g) visit(g.getGeometryN(k))
            }
            visit(g)
            val crossings = DoubleArray(edges.size + 1)
            for (r in 0 until rows) {
                val y = centerY(r)
                var n = 0
                for (e in edges) if ((e[1] > y) != (e[3] > y)) crossings[n++] = e[0] + (e[2] - e[0]) * (y - e[1]) / (e[3] - e[1])
                crossings.sort(0, n)
                var k = 0
                while (k + 1 < n) {
                    val c0 = max(0, ceil((crossings[k] - minX) / cell - 0.5).toInt())
                    val c1 = min(cols - 1, floor((crossings[k + 1] - minX) / cell - 0.5).toInt())
                    for (c in c0..c1) action(r * cols + c)
                    k += 2
                }
            }
        }

        /** Target mask: the cells of [target] only are measured. */
        fun mask(target: JtsGeometry) = scan(target) { state[it] = TARGET.toByte() }

        /**
         * Sweeps the band of half-width [radius] from [a] to [b] (discs at both ends): target cells become covered, an
         * already covered cell becomes repeated, except around [a] when the pass [continuing] the previous one.
         * A zero-length segment only stamps the disc around [a].
         */
        fun sweep(a: Point, b: Point, radius: Double, continuing: Boolean) {
            val dx = b.x - a.x; val dy = b.y - a.y
            val length = hypot(dx, dy)
            val band = length > 1e-9
            val ux = if (band) dx / length else 0.0; val uy = if (band) dy / length else 0.0
            val r2 = radius * radius
            val rowLow = max(0, ceil((min(a.y, b.y) - radius - minY) / cell - 0.5).toInt())
            val rowHigh = min(rows - 1, floor((max(a.y, b.y) + radius - minY) / cell - 0.5).toInt())
            for (r in rowLow..rowHigh) {
                val y = centerY(r)
                var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
                for (p in arrayOf(a, b)) {
                    val h = r2 - (y - p.y) * (y - p.y)
                    if (h >= 0) { val s = sqrt(h); lo = min(lo, p.x - s); hi = max(hi, p.x + s) }
                }
                if (band) {
                    // Rectangular band: 0 <= projection <= length and |offset| <= radius, linear in x.
                    var sLo = Double.NEGATIVE_INFINITY; var sHi = Double.POSITIVE_INFINITY
                    fun limit(alpha: Double, beta: Double, low: Double, high: Double) {
                        if (abs(alpha) < 1e-15) { if (beta < low || beta > high) { sLo = 1.0; sHi = 0.0 } }
                        else {
                            val x1 = (low - beta) / alpha; val x2 = (high - beta) / alpha
                            sLo = max(sLo, min(x1, x2)); sHi = min(sHi, max(x1, x2))
                        }
                    }
                    limit(ux, -a.x * ux + (y - a.y) * uy, 0.0, length)
                    limit(-uy, a.x * uy + (y - a.y) * ux, -radius, radius)
                    if (sLo <= sHi) { lo = min(lo, sLo); hi = max(hi, sHi) }
                }
                if (lo > hi) continue
                val c0 = max(0, ceil((lo - minX) / cell - 0.5).toInt())
                val c1 = min(cols - 1, floor((hi - minX) / cell - 0.5).toInt())
                for (c in c0..c1) {
                    val index = r * cols + c
                    val value = state[index].toInt()
                    if (value and TARGET == 0) continue
                    if (value and COVERED != 0) {
                        val x = centerX(c)
                        val fresh = !continuing || (x - a.x) * (x - a.x) + (y - a.y) * (y - a.y) > r2
                        if (fresh) { extraCells++; state[index] = (value or REPEATED).toByte() }
                    } else state[index] = (value or COVERED).toByte()
                }
            }
        }

        companion object {
            /** Grid over [env] with cells of [requestedCell] at least, enlarged to stay within [maxCells]. */
            fun over(env: Envelope, requestedCell: Double, maxCells: Double = 8_000_000.0): Grid {
                val width = env.width; val height = env.height
                val cell = max(requestedCell, sqrt(width * height / maxCells))
                val cols = max(1, ceil(width / cell).toInt()); val rows = max(1, ceil(height / cell).toInt())
                return Grid(env.minX, env.minY, cell, cols, rows)
            }
        }
    }

    fun measure(mission: List<MissionLeg>, target: JtsGeometry, radius: Double, requestedCell: Double): MissionEvaluator.Coverage {
        require(requestedCell > 0 && radius > 0)
        val env = target.envelopeInternal
        if (target.isEmpty || env.isNull) return MissionEvaluator.Coverage(0.0, 0.0, 0.0)
        val grid = Grid.over(env, requestedCell)
        grid.mask(target)
        var previous: Point? = null
        for (leg in mission) {
            if (!leg.cutting) { if (length(leg.path) > 1e-9) previous = null; continue }
            for (i in 0 until leg.path.size - 1) {
                val a = leg.path[i]; val b = leg.path[i + 1]
                if (a.distance(b) <= 1e-9) continue
                grid.sweep(a, b, radius, previous == a)
                previous = b
            }
        }
        var covered = 0L; var repeated = 0L
        for (v in grid.state) {
            if (v.toInt() and COVERED != 0) covered++
            if (v.toInt() and REPEATED != 0) repeated++
        }
        val unit = grid.cell * grid.cell
        return MissionEvaluator.Coverage(min(covered * unit, target.area), repeated * unit, grid.extraCells * unit)
    }
}
