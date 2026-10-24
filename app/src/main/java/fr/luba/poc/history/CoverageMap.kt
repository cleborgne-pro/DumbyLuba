package fr.luba.poc.history

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.map.excludes
import fr.luba.poc.planning.RasterCoverage
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import org.locationtech.jts.geom.Geometry as JtsGeometry

/** Real coverage of one zone of the task: measured area (cell centres inside the zone, no-go excluded) and mowed part. */
data class ZoneCoverage(val id: Long, val name: String, val areaM2: Double, val coveredM2: Double, val repeatedM2: Double) {
    val fraction: Double get() = if (areaM2 <= 0) 0.0 else (coveredM2 / areaM2).coerceIn(0.0, 1.0)
}

/**
 * Portion left unmowed, wide enough to be reached by a pass: [area] is a planner target (see
 * [fr.luba.poc.map.PaintedZone.prepare]), [zoneId] the zone holding most of it.
 */
data class CoverageGap(val zoneId: Long?, val areaM2: Double, val center: Point, val area: JtsGeometry) {
    /** Outer edges and holes of the gap, for display. */
    val outlines: List<List<Point>> get() = (0 until area.numGeometries).mapNotNull { area.getGeometryN(it) as? Polygon }.flatMap { p ->
        listOf(p.exteriorRing) + (0 until p.numInteriorRing).map { p.getInteriorRingN(it) }
    }.map { ring -> ring.coordinates.dropLast(1).map { Point(it.x, it.y) } }.filter { it.size >= 3 }
}

/**
 * Coverage actually mowed during a task: the cutting width swept around the positions recorded with the blades
 * running ([TrackPhase.CUTTING]), on the [RasterCoverage] grid restricted to the task zones minus the no-go zones.
 * Two positions are joined only within the same piece of track ([TaskTrackPieces.broken]): a link lost or a robot
 * carried away does not draw a stripe. Immutable snapshot; [CoverageBuilder] feeds it during the mowing.
 */
class CoverageMap internal constructor(
    val minX: Double, val minY: Double, val cellM: Double, val cols: Int, val rows: Int,
    private val state: ByteArray, private val zoneOf: IntArray, shapes: List<MapShape>, val cutWidthM: Double,
) {
    private val zoneIds: List<Long> = shapes.map { it.id }
    val zones: List<ZoneCoverage>
    val areaM2: Double
    val coveredM2: Double
    val repeatedM2: Double
    val fraction: Double get() = if (areaM2 <= 0) 0.0 else (coveredM2 / areaM2).coerceIn(0.0, 1.0)

    init {
        val target = LongArray(shapes.size); val covered = LongArray(shapes.size); val repeated = LongArray(shapes.size)
        var t = 0L; var c = 0L; var r = 0L
        for (i in state.indices) {
            val v = state[i].toInt()
            if (v and RasterCoverage.TARGET == 0) continue
            val z = zoneOf[i]
            t++; if (z >= 0) target[z]++
            if (v and RasterCoverage.COVERED != 0) { c++; if (z >= 0) covered[z]++ }
            if (v and RasterCoverage.REPEATED != 0) { r++; if (z >= 0) repeated[z]++ }
        }
        val unit = cellM * cellM
        zones = shapes.mapIndexed { i, s -> ZoneCoverage(s.id, s.name, target[i] * unit, covered[i] * unit, repeated[i] * unit) }
        areaM2 = t * unit; coveredM2 = c * unit; repeatedM2 = r * unit
    }

    fun zone(id: Long): ZoneCoverage? = zones.firstOrNull { it.id == id }

    /** Cell [index] belongs to the measured surface. */
    fun isTarget(index: Int): Boolean = state[index].toInt() and RasterCoverage.TARGET != 0
    fun isCovered(index: Int): Boolean = state[index].toInt() and RasterCoverage.COVERED != 0
    fun isRepeated(index: Int): Boolean = state[index].toInt() and RasterCoverage.REPEATED != 0
    fun index(p: Point): Int? {
        val c = kotlin.math.floor((p.x - minX) / cellM).toInt(); val r = kotlin.math.floor((p.y - minY) / cellM).toInt()
        return if (c in 0 until cols && r in 0 until rows) r * cols + c else null
    }
    /** Mowed according to the track at [p] (false outside the measured surface). */
    fun coveredAt(p: Point): Boolean = index(p)?.let { isTarget(it) && isCovered(it) } ?: false

    /**
     * Unmowed cells grouped in pieces (4-connectivity) after a morphological opening of [openingM]: the thin strips the
     * blades cannot reach along the boundaries, and the raster noise between two passes, are not gaps.
     */
    private fun components(openingM: Double, minAreaM2: Double): List<IntArray> {
        val n = cols * rows
        val open = BooleanArray(n) { isTarget(it) && !isCovered(it) }
        val k = kotlin.math.floor(openingM / cellM + 1e-9).toInt()
        val mask = if (k > 0) dilate(erode(open, k), k).also { for (i in 0 until n) it[i] = it[i] && open[i] } else open
        val seen = BooleanArray(n)
        val out = mutableListOf<IntArray>()
        val stack = IntArray(n)
        val minCells = kotlin.math.ceil(minAreaM2 / (cellM * cellM) - 1e-9).toInt().coerceAtLeast(1)
        for (start in 0 until n) {
            if (!mask[start] || seen[start]) continue
            var top = 0; stack[top++] = start; seen[start] = true
            val cells = ArrayList<Int>()
            while (top > 0) {
                val i = stack[--top]; cells += i
                val r = i / cols; val c = i % cols
                fun push(j: Int) { if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (c > 0) push(i - 1)
                if (c < cols - 1) push(i + 1)
                if (r > 0) push(i - cols)
                if (r < rows - 1) push(i + cols)
            }
            if (cells.size >= minCells) out += cells.toIntArray()
        }
        return out
    }

    /** Square erosion of half-size [k] cells (separable: rows then columns); outside the grid counts as empty. */
    private fun erode(mask: BooleanArray, k: Int): BooleanArray = morph(mask, k, erode = true)
    private fun dilate(mask: BooleanArray, k: Int): BooleanArray = morph(mask, k, erode = false)
    private fun morph(mask: BooleanArray, k: Int, erode: Boolean): BooleanArray {
        fun pass(src: BooleanArray, horizontal: Boolean): BooleanArray {
            val out = BooleanArray(src.size)
            val lines = if (horizontal) rows else cols; val length = if (horizontal) cols else rows
            for (line in 0 until lines) {
                fun at(i: Int) = if (horizontal) line * cols + i else i * cols + line
                // Running count of set cells in the window [i-k, i+k].
                var count = 0
                for (i in 0 until minOf(k, length)) if (src[at(i)]) count++
                for (i in 0 until length) {
                    val enter = i + k; val leave = i - k - 1
                    if (enter < length && src[at(enter)]) count++
                    if (leave >= 0 && src[at(leave)]) count--
                    val window = minOf(length - 1, i + k) - maxOf(0, i - k) + 1
                    out[at(i)] = if (erode) count == 2 * k + 1 && window == 2 * k + 1 else count > 0
                }
            }
            return out
        }
        return pass(pass(mask, true), false)
    }

    /**
     * Portions left unmowed at least [minAreaM2] large and wider than twice [openingM], largest first, as polygons in
     * map metres usable as planner targets.
     */
    fun gaps(minAreaM2: Double = MIN_GAP_M2, openingM: Double = GAP_OPENING_M): List<CoverageGap> =
        components(openingM, minAreaM2).map { cells ->
            val rects = ArrayList<JtsGeometry>()
            val zoneCount = HashMap<Int, Int>()
            val sorted = cells.sortedArray()
            var i = 0
            while (i < sorted.size) {
                // Run of consecutive cells on one row: one rectangle.
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1 && sorted[j + 1] / cols == sorted[i] / cols) j++
                val r = sorted[i] / cols; val c0 = sorted[i] % cols; val c1 = sorted[j] % cols
                rects += factory.toGeometry(org.locationtech.jts.geom.Envelope(minX + c0 * cellM, minX + (c1 + 1) * cellM,
                    minY + r * cellM, minY + (r + 1) * cellM))
                for (k in i..j) zoneOf[sorted[k]].takeIf { it >= 0 }?.let { zoneCount.merge(it, 1, Int::plus) }
                i = j + 1
            }
            val union = UnaryUnionOp.union(rects)
            val simple = TopologyPreservingSimplifier.simplify(union, cellM * 0.75).let { if (it.isValid) it else it.buffer(0.0) }
            val zone = zoneCount.maxByOrNull { it.value }?.key?.let { zoneIds[it] }
            val center = simple.interiorPoint.coordinate
            CoverageGap(zone, cells.size * cellM * cellM, Point(center.x, center.y), simple)
        }.sortedByDescending { it.areaM2 }

    /** Union of the [gaps]: a single planner target ("mow what was missed"). Empty geometry without gaps. */
    fun gapArea(minAreaM2: Double = MIN_GAP_M2, openingM: Double = GAP_OPENING_M): JtsGeometry =
        gaps(minAreaM2, openingM).map { it.area }.let { if (it.isEmpty()) factory.createPolygon() else UnaryUnionOp.union(it) }

    /**
     * Image of the layer, [cols] × [rows] ARGB pixels, first row at the top (largest y): [covered] colour for mowed
     * cells, [repeated] for cells mowed twice or more (null: same as covered), [gap] for the gaps (null: not drawn),
     * transparent elsewhere.
     */
    fun pixels(covered: Int, repeated: Int? = null, gap: Int? = null): IntArray {
        val out = IntArray(cols * rows)
        if (gap != null) components(GAP_OPENING_M, MIN_GAP_M2).forEach { cells -> cells.forEach { out[flip(it)] = gap } }
        for (i in state.indices) {
            if (!isTarget(i) || !isCovered(i)) continue
            out[flip(i)] = if (repeated != null && isRepeated(i)) repeated else covered
        }
        return out
    }

    private fun flip(index: Int): Int = (rows - 1 - index / cols) * cols + index % cols

    companion object {
        /** Grid cell: 10 cm, a quarter of the cutting width. */
        const val CELL_M = 0.10
        /** Largest grid (cells): the cell grows beyond it (huge maps). */
        const val MAX_CELLS = 4_000_000.0
        /** Smallest gap kept: half a square metre is worth a pass. */
        const val MIN_GAP_M2 = 0.25
        /** Opening radius: unmowed strips narrower than about 30 cm (unreachable edges) are not gaps. */
        const val GAP_OPENING_M = 0.10
        private val factory = GeometryFactory()

        /** Coverage of a recorded task on its own map (or [fallbackMap] for a track without context); null without zones. */
        fun of(track: TaskTrack, fallbackMap: RobotMap? = null, cutWidthM: Double = Geometry.CUT_WIDTH_M): CoverageMap? {
            val map = track.meta?.map ?: fallbackMap ?: return null
            val builder = CoverageBuilder(map, track.meta?.zoneIds.orEmpty(), cutWidthM)
            builder.addRun(track.samples)
            return builder.snapshot()
        }
    }
}

/**
 * Incremental real coverage: each position received during the mowing sweeps the cutting width since the previous
 * one (blades running at both ends, same piece of track). [zoneIds]: task zones; empty or unknown, all the zones of
 * [map]. Not thread-safe: fed and read from one thread.
 */
class CoverageBuilder(map: RobotMap, zoneIds: Collection<Long> = emptyList(), val cutWidthM: Double = Geometry.CUT_WIDTH_M,
                      cellM: Double = CoverageMap.CELL_M) {
    private val shapes: List<MapShape>
    private val grid: RasterCoverage.Grid?
    private val zoneOf: IntArray
    private var last: TrackSample? = null
    private var sweptTo: Point? = null
    private val radius = cutWidthM / 2

    init {
        require(cutWidthM > 0 && cellM > 0)
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        shapes = zones.filter { it.id in zoneIds }.ifEmpty { zones }
        val target = target(map, shapes)
        val env = target.envelopeInternal
        if (target.isEmpty || env.isNull) { grid = null; zoneOf = IntArray(0) }
        else {
            grid = RasterCoverage.Grid.over(env, cellM, CoverageMap.MAX_CELLS)
            grid.mask(target)
            zoneOf = IntArray(grid.cols * grid.rows) { -1 }
            shapes.forEachIndexed { z, s -> grid.scan(polygon(s.points)) { zoneOf[it] = z } }
        }
    }

    /** Next position of the current track. */
    fun add(sample: TrackSample) {
        val previous = last
        last = sample
        val g = grid ?: return
        if (sample.phase != TrackPhase.CUTTING) { sweptTo = null; return }
        if (previous != null && previous.phase == TrackPhase.CUTTING && sample.atMs >= previous.atMs && !TaskTrackPieces.broken(previous, sample)) {
            g.sweep(previous.point, sample.point, radius, sweptTo == previous.point)
        } else g.sweep(sample.point, sample.point, radius, false)
        sweptTo = sample.point
    }

    /** Positions of another piece of track (saved before a resume): swept on their own, the current track is kept. */
    fun addRun(samples: List<TrackSample>) {
        val keepLast = last; val keepSwept = sweptTo
        last = null; sweptTo = null
        samples.forEach(::add)
        last = keepLast; sweptTo = keepSwept
    }

    /** Immutable copy of the coverage so far; null when the task has no zone to measure. */
    fun snapshot(): CoverageMap? {
        val g = grid ?: return null
        return CoverageMap(g.minX, g.minY, g.cell, g.cols, g.rows, g.state.copyOf(), zoneOf, shapes, cutWidthM)
    }

    private companion object {
        val factory = GeometryFactory()
        fun polygon(points: List<Point>): JtsGeometry =
            factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).let { if (it.isValid) it else it.buffer(0.0) }

        /** Task zones minus the no-go zones. */
        fun target(map: RobotMap, zones: List<MapShape>): JtsGeometry {
            if (zones.isEmpty()) return factory.createPolygon()
            val area = UnaryUnionOp.union(zones.map { polygon(it.points) })
            val noGo = map.shapes.filter { it.excludes && it.points.size >= 3 }.map { polygon(it.points) }
            return if (noGo.isEmpty()) area else area.difference(UnaryUnionOp.union(noGo))
        }
    }
}
