package fr.luba.poc.map

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The "stop" metric of a pass order (docs/path-planning/PATTERNS.md, section 4.3), pure geometry on the swept union of
 * the cutting paths driven in order: what a stop at [stopPercent] % of the cutting metres leaves behind. Plus the pass
 * ranks of each run, read back from the geometry, for the front width and the rolling share of the U-turns.
 */
internal object PassOrderMetrics {
    private val factory = GeometryFactory()

    private fun polygon(points: List<Point>) = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun length(path: List<Point>) = path.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    /** The first [stopPercent] % of the metres of [paths], driven in order (the last path cut where the stop falls). */
    fun prefix(paths: List<List<Point>>, stopPercent: Double): List<List<Point>> {
        var left = paths.sumOf(::length) * stopPercent / 100
        val out = mutableListOf<List<Point>>()
        for (path in paths) {
            if (left <= 1e-9) break
            val total = length(path)
            if (total <= left) { out += path; left -= total; continue }
            val cut = mutableListOf(path[0])
            for ((a, b) in path.zipWithNext()) {
                val d = a.distance(b)
                if (d >= left) { val t = left / d; cut += Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)); break }
                cut += b; left -= d
            }
            out += cut; left = 0.0
        }
        return out.filter { it.size >= 2 && length(it) > 1e-6 }
    }

    /** Swept union of [paths] (cut [cut] wide) inside [zone]. */
    fun swept(paths: List<List<Point>>, zone: List<Point>, cut: Double): JtsGeometry {
        if (paths.isEmpty()) return factory.createPolygon()
        val swaths = paths.map { factory.createLineString(it.map { p -> Coordinate(p.x, p.y) }.toTypedArray()).buffer(cut / 2, 8) }
        return UnaryUnionOp.union(swaths).intersection(polygon(zone))
    }

    private fun components(geometry: JtsGeometry): List<JtsGeometry> =
        (0 until geometry.numGeometries).map { geometry.getGeometryN(it) }.filter { it is Polygon && it.area > 1e-6 }

    /** Area of the largest connected piece of what is mowed at [stopPercent] %, divided by all that is mowed then. */
    fun contiguousShare(paths: List<List<Point>>, zone: List<Point>, cut: Double, stopPercent: Double): Double {
        val union = swept(prefix(paths, stopPercent), zone, cut)
        if (union.area < 1e-9) return 1.0
        return components(union).maxOf { it.area } / union.area
    }

    /**
     * Separate unmowed pieces (0.05 m² or more) left at [stopPercent] % inside the surface that the whole order mows; the
     * cells not started yet are part of them.
     */
    fun unmowedFragments(paths: List<List<Point>>, zone: List<Point>, cut: Double, stopPercent: Double): Int {
        val all = swept(paths, zone, cut)
        val left = all.difference(swept(prefix(paths, stopPercent), zone, cut))
        return components(left).count { it.area >= 0.05 }
    }

    /**
     * Largest gap, over time, between the most advanced pass rank done and the first rank not done yet behind it, the
     * front advancing towards the higher ranks or towards the lower ones (a cell driven from its other end), the
     * smaller of the two.
     */
    fun frontWidthRanks(order: List<Int>): Int = minOf(upwards(order), upwards(order.map { -it }))

    private fun upwards(order: List<Int>): Int {
        val done = HashSet<Int>()
        var first = order.minOrNull() ?: return 0
        var high = Int.MIN_VALUE; var width = 0
        for (q in order) {
            done += q; high = maxOf(high, q)
            while (first in done) first++
            width = maxOf(width, high - first)
        }
        return width
    }

    /** U-turns and visit order of the regular cells (at least `minPasses` passes) of a plan. */
    class Order(val turns: Int, val rolling: Int, val frontWidth: Int, val regularCells: Int, val regularPasses: Int)

    /**
     * Cutting runs of [preview] in mowing order, read back cell by cell ([Preview.runCellIds]): their segments parallel
     * to [angle] longer than [minPass] are passes (shortened by the U-turns, still on their row), ranked across the cell
     * by lateral offset; a U-turn joins two passes of one run, rolling when [TurnOptimizer.drive] finds no pivot in
     * place between them.
     */
    fun order(preview: Preview, angle: Int, spacing: Double, minPasses: Int, speedMps: Double = 0.3, minPass: Double = 0.2): Order {
        val d = Point(cos(Math.toRadians(angle.toDouble())), sin(Math.toRadians(angle.toDouble())))
        val normal = Point(-d.y, d.x)
        fun passes(run: List<Point>) = run.indices.toList().zipWithNext().filter { (i, j) ->
            val a = run[i]; val b = run[j]; val len = a.distance(b)
            len >= minPass && abs(((b.x - a.x) * d.x + (b.y - a.y) * d.y) / len) > 0.999
        }
        fun offset(p: Point) = p.x * normal.x + p.y * normal.y
        val driven = preview.cuttingPaths.mapNotNull { path ->
            val index = preview.runs.indexOfFirst { it == path || it.reversed() == path }
            if (index < 0) null else path to (preview.runCellIds.getOrNull(index) ?: index)
        }
        // Rows of each cell: distinct offsets of its passes.
        val rows = driven.groupBy({ it.second }, { it.first }).mapValues { (_, runs) ->
            runs.flatMap { run -> passes(run).map { (i, _) -> offset(run[i]) } }.sorted()
                .fold(mutableListOf<Double>()) { acc, o -> if (acc.isEmpty() || o - acc.last() > 0.3 * spacing) acc.add(o); acc }
        }
        val regular = rows.filterValues { it.size >= minPasses }
        val visits = HashMap<Int, MutableList<Int>>()
        var turns = 0; var rolling = 0
        for ((run, cell) in driven) {
            val cellRows = regular[cell] ?: continue
            val segments = passes(run)
            segments.forEach { (i, _) -> visits.getOrPut(cell) { mutableListOf() } += cellRows.indexOfFirst { abs(it - offset(run[i])) <= 0.3 * spacing } }
            for (k in 0 until segments.size - 1) {
                turns++
                if (TurnOptimizer.drive(run.subList(segments[k].first, segments[k + 1].second + 1), speedMps).pivotDegrees < 1e-6) rolling++
            }
        }
        return Order(turns, rolling, visits.values.maxOfOrNull { frontWidthRanks(it) } ?: 0, regular.size, regular.values.sumOf { it.size })
    }
}
