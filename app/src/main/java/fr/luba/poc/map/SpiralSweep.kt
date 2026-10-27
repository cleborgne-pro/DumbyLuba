package fr.luba.poc.map

import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.TopologyException
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*
import fr.luba.poc.i18n.tr

/**
 * Direction of the Spiral mode ([CoverageMode.SPIRAL]): every chain driven from the edge inwards (as [SpiralSweep] builds
 * it), or from the centre outwards (the same rings, each chain reversed and checked again, its core pocket mowed before
 * it: [CoveragePlanner.plan]). Persisted by name.
 */
enum class SpiralDirection(private val en: String, private val fr: String) {
    FROM_EDGE("From the edge", "Depuis le bord"), FROM_CENTRE("From the centre", "Depuis le centre");
    /** Display label in the interface language. */
    val label: String get() = tr(en, fr)
}

/**
 * Spiral ([CoverageMode.SPIRAL]): passes parallel to the contour of the coverage area, joined into inward spirals.
 *
 * Each connected part of the area is made simply connected by slits (a narrow strip from every hole to the exterior or to
 * a hole already connected), then offset inwards ring after ring: ring `j` is the outline of
 * `opening(P - j × spacing, radius)` (erosion then dilation by the follow radius, so that every convex corner is an arc
 * the tracking follows without stopping; a reflex corner keeps its offset arc). Ring `j` is driven from its start `s_j`
 * around to `T` before it, then a slanted segment (along `T`, across one spacing) leads to `s_{j+1}`, the point of ring
 * `j + 1` nearest to `s_j`; the outer ring is driven whole, from `T` before `s_0`. Where the rings split into several
 * parts, the chain stops and each part starts its own chain (its own cell). Whatever the rings do not sweep (the core,
 * lobes too thin for the radius, sharp corners, refused rings) is returned as pockets, filled in zigzag by the caller.
 *
 * Every ring piece is checked by [build]'s `valid` (safe surface and chassis envelope, with the vertex before it); a
 * refused ring ends the chain on the previous ring driven whole, and the next ring starts a new chain. Pure geometry,
 * deterministic; a JTS failure returns null (the caller then mows the zone in zigzag).
 */
internal object SpiralSweep {
    /** 23 segments per quadrant: about 4° per vertex, like the arcs of the Turn Optimizer ([TurnOptimizer.ARC_STEP_DEGREES]). */
    const val QUADRANT_SEGMENTS = 23
    /** Smallest pocket filled in zigzag, m². */
    const val MIN_POCKET_M2 = 0.04
    /** Shortest pocket pass kept, m. */
    const val MIN_POCKET_PASS_M = 0.1
    /** Width of a slit: the cut minus this, so that the rings on both sides of it sweep it. */
    const val SLIT_NARROWING_M = 0.1
    /** Outer ring this far inside the coverage area: the rings stay strictly inside what their segments are checked against. */
    const val EDGE_M = 0.005
    /** A start is placed where the ring turns by at most this much within `T` on each side (straight stretch). */
    const val STRAIGHT_TURN_DEGREES = 15.0
    private const val MAX_LEVELS = 4000
    private val factory = GeometryFactory()

    /** Along-length `T` of the slanted link from one ring to the next: max(0.8 m, 4 spacings). */
    fun slantLength(spacing: Double): Double = max(0.8, 4 * spacing)

    /**
     * [chains]: spiral paths, outer end first. [pockets]: parts of the area left unswept, at least [MIN_POCKET_M2] each.
     * [rings]: rings driven; [splits]: rings whose next level splits into several parts; [refusedRings]: rings refused by
     * the check; [slitFailures]: parts whose holes could not be slit (left to the pockets). [parents]: for each chain, the
     * chain it continues inwards from (index in [chains]: the rings inside a split, a refused ring or a cut), null for the
     * outermost chain of a part.
     */
    class Result(val chains: List<List<Point>>, val pockets: List<Polygon>, val rings: Int, val splits: Int,
                 val refusedRings: Int, val slitFailures: Int, val parents: List<Int?> = List(chains.size) { null })

    /**
     * Choice of [CoveragePlanner.plan] for one zone: the spiral [result] (null: not buildable), the area its paths and the
     * zigzag's sweep over [target] m² (2 cm raster), and whether the spiral was [kept].
     */
    internal class Decision(val result: Result?, val spiralCovered: Double?, val zigzagCovered: Double?, val target: Double, val kept: Boolean)
    /** Measurement only (tests): every [Decision], from any thread. */
    @Volatile internal var observer: ((Decision) -> Unit)? = null

    /**
     * Spiral chains of [inset] (the area where pass centres may lie), [spacing] between rings, [cutWidth] of the blades,
     * convex corners at [radius]; the first chain starts nearest to [start]. Null when no chain fits (empty or too narrow
     * area, every outer ring refused) or the geometry fails (JTS): the caller then mows in zigzag.
     * [checkpoint] is called at every ring level (cancellation) and may throw.
     */
    fun build(inset: JtsGeometry, spacing: Double, cutWidth: Double, radius: Double, start: Point?,
              valid: (List<Point>) -> Boolean, checkpoint: () -> Unit = {}): Result? {
        require(spacing > 0 && cutWidth > 0 && radius > 0)
        return try {
            val chains = mutableListOf<List<Point>>()
            val parents = mutableListOf<Int?>()
            var rings = 0; var splits = 0; var refused = 0; var slitFailures = 0
            var hint = start
            for (component in polygons(inset)) {
                val parts = slit(component, cutWidth - SLIT_NARROWING_M)
                if (parts == null) { slitFailures++; continue }
                for (part in parts) {
                    val built = spiral(part, spacing, radius, hint, valid, checkpoint)
                    val offset = chains.size
                    parents += built.parents.map { it?.plus(offset) }
                    chains += built.chains; rings += built.rings; splits += built.splits; refused += built.refused
                    built.chains.lastOrNull()?.let { hint = it.last() }
                }
            }
            if (chains.isEmpty()) return null
            Result(chains, pockets(inset, chains, spacing, cutWidth), rings, splits, refused, slitFailures, parents)
        } catch (e: TopologyException) { null } catch (e: IllegalArgumentException) { null }
    }

    /**
     * Zigzag passes of a [pocket], one list per cell, in the direction (its long axis, or one of every 30°) that needs
     * the fewest cells and U-turns ([MixedSweep.cost], [MixedSweep.SWITCH_COST_M] per extra cell) among those whose passes
     * all pass [valid] (otherwise the one losing the least pass length). A pocket with branches (the core of an L or a T)
     * then has each of its cells filled again in its own direction when that costs less. Passes shorter than
     * [MIN_POCKET_PASS_M] are left out; passes refused by [valid] are dropped.
     */
    fun fill(pocket: Polygon, spacing: Double, valid: (List<Point>) -> Boolean): List<List<List<Point>>> {
        val best = bestFill(pocket, spacing, valid) ?: return emptyList()
        return best.cells.flatMap { cell ->
            if (cell.size < 3) return@flatMap listOf(cell)
            val region = try {
                factory.createMultiLineString(cell.map { p -> factory.createLineString(p.map { Coordinate(it.x, it.y) }.toTypedArray()) }.toTypedArray())
                    .buffer(spacing / 2, 2).intersection(pocket)
            } catch (e: TopologyException) { return@flatMap listOf(cell) }
            val refills = polygons(region).map { bestFill(it, spacing, valid) }

            if (refills.isEmpty() || refills.any { it == null || it.lost > 1e-6 }) return@flatMap listOf(cell)
            val cost = refills.sumOf { it!!.cost } + (refills.size - 1) * MixedSweep.SWITCH_COST_M
            if (cost < MixedSweep.cost(cell) - 1e-9) refills.flatMap { it!!.cells } else listOf(cell)
        }
    }

    /** Cells of one fill, pass length dropped by the check, cost. */
    private class Fill(val cells: List<List<List<Point>>>, val lost: Double, val cost: Double)

    /** Cheapest fill of [area] over the directions of [fill]. */
    private fun bestFill(area: Polygon, spacing: Double, valid: (List<Point>) -> Boolean): Fill? {
        val support = try { MinimumDiameter(area).supportingSegment.coordinates } catch (e: IllegalArgumentException) { emptyArray() }
        val axis = if (support.size < 2 || support[0].distance(support[1]) < 1e-9) 0
            else Math.floorMod(Math.toDegrees(atan2(support[1].y - support[0].y, support[1].x - support[0].x)).roundToInt(), 180)
        var best: Fill? = null
        fun length(pass: List<Point>) = pass.zipWithNext().sumOf { (a, b) -> a.distance(b) }
        for (angle in (listOf(axis) + (0 until 180 step 30)).distinct()) {
            val raw = try { MixedSweep.fill(area, angle, spacing) } catch (e: TopologyException) { continue } catch (e: IllegalArgumentException) { continue }
                .map { cell -> cell.filter { pass -> length(pass) >= MIN_POCKET_PASS_M } }
            val cells = raw.map { cell -> cell.filter(valid) }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) continue
            val lost = (raw.sumOf { cell -> cell.sumOf(::length) } - cells.sumOf { cell -> cell.sumOf(::length) }).let { if (it > 1e-6) it else 0.0 }
            val cost = cells.sumOf { MixedSweep.cost(it) } + (cells.size - 1) * MixedSweep.SWITCH_COST_M
            val current = best
            if (current == null || lost < current.lost - 1e-9 || lost <= current.lost + 1e-9 && cost < current.cost - 1e-9) best = Fill(cells, lost, cost)
        }
        return best
    }

    private fun polygons(geometry: JtsGeometry): List<Polygon> =
        if (geometry is Polygon) listOf(geometry).filter { !it.isEmpty && it.area > 1e-6 }
        else (0 until geometry.numGeometries).flatMap { i -> geometry.getGeometryN(i).let { if (it === geometry) emptyList() else polygons(it) } }

    /** Buffers without the input simplification of JTS (up to 1 % of the distance, which can cut a reflex corner of the
     * boundary by a few millimetres and put a ring outside the area). */
    private val exact = BufferParameters(QUADRANT_SEGMENTS).apply { simplifyFactor = 0.0 }
    private fun offset(geometry: JtsGeometry, distance: Double): JtsGeometry = BufferOp.bufferOp(geometry, distance, exact)
    private fun opening(geometry: JtsGeometry, radius: Double): JtsGeometry = offset(offset(geometry, -radius), radius)

    /**
     * [polygon] made simply connected: each hole is joined by a strip [width] wide to the exterior or to a hole already
     * joined, along the shortest segment that stays inside the polygon. Null when a hole cannot be joined.
     */
    private fun slit(polygon: Polygon, width: Double): List<Polygon>? {
        if (polygon.numInteriorRing == 0) return listOf(polygon)
        if (width <= 0.01) return null
        val prepared = PreparedGeometryFactory.prepare(polygon)
        val holes = (0 until polygon.numInteriorRing).map { polygon.getInteriorRingN(it) }
        val joined = mutableListOf<JtsGeometry>(polygon.exteriorRing)
        val pending = holes.indices.toMutableList()
        var shape: JtsGeometry = polygon
        val flat = BufferParameters(QUADRANT_SEGMENTS, BufferParameters.CAP_FLAT)
        fun segment(a: Coordinate, b: Coordinate) = factory.createLineString(arrayOf(Coordinate(a), Coordinate(b)))
        while (pending.isNotEmpty()) {
            var best: Pair<Int, Array<Coordinate>>? = null
            var bestLength = Double.POSITIVE_INFINITY
            for (h in pending) for (target in joined) {
                val ring = holes[h]
                val candidates = mutableListOf(DistanceOp.nearestPoints(ring, target))
                // The shortest segment may cross another hole: samples along the hole as well.
                val coordinates = ring.coordinates
                val step = max(1, coordinates.size / 24)
                for (i in 0 until coordinates.size - 1 step step) {
                    candidates += DistanceOp.nearestPoints(factory.createPoint(coordinates[i]), target)
                }
                for (pair in candidates) {
                    val length = pair[0].distance(pair[1])
                    if (length < 1e-9 || length >= bestLength) continue
                    if (!prepared.covers(segment(pair[0], pair[1]))) continue
                    best = h to pair; bestLength = length
                }
            }
            val (hole, pair) = best ?: return null
            // Extended past both ends so that the strip opens the hole and the exterior completely.
            val dx = pair[1].x - pair[0].x; val dy = pair[1].y - pair[0].y; val length = hypot(dx, dy)
            val ux = dx / length * 0.05; val uy = dy / length * 0.05
            val cut = segment(Coordinate(pair[0].x - ux, pair[0].y - uy), Coordinate(pair[1].x + ux, pair[1].y + uy))
            shape = shape.difference(BufferOp.bufferOp(cut, width / 2, flat))
            joined += holes[hole]; pending.remove(hole)
        }
        val parts = polygons(shape)
        return parts.takeIf { list -> list.isNotEmpty() && list.all { it.numInteriorRing == 0 } }
    }

    /** One closed ring, counter-clockwise, as an arc-length parametrised loop. */
    private class Ring(points: List<Point>) {
        val points: List<Point>
        val cumulative: DoubleArray
        val length: Double
        init {
            val open = points.filterIndexed { i, p -> i == 0 || p.distance(points[i - 1]) > 1e-9 }
                .let { if (it.size > 1 && it.first().distance(it.last()) < 1e-9) it.dropLast(1) else it }
            this.points = open
            cumulative = DoubleArray(open.size + 1)
            for (i in open.indices) cumulative[i + 1] = cumulative[i] + open[i].distance(open[(i + 1) % open.size])
            length = cumulative[open.size]
        }
        private fun wrap(u: Double) = ((u % length) + length) % length
        private fun segmentAt(u: Double): Int {
            var low = 0; var high = points.size - 1
            while (low < high) { val mid = (low + high + 1) / 2; if (cumulative[mid] <= u) low = mid else high = mid - 1 }
            return low
        }
        fun at(position: Double): Point {
            val u = wrap(position)
            val i = segmentAt(u)
            val a = points[i]; val b = points[(i + 1) % points.size]
            val span = cumulative[i + 1] - cumulative[i]
            val t = if (span < 1e-12) 0.0 else ((u - cumulative[i]) / span).coerceIn(0.0, 1.0)
            return Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
        }
        /** Arc-length position of the point of the ring nearest to [p]. */
        fun project(p: Point): Double {
            var best = Double.POSITIVE_INFINITY; var position = 0.0
            for (i in points.indices) {
                val a = points[i]; val b = points[(i + 1) % points.size]
                val dx = b.x - a.x; val dy = b.y - a.y; val d2 = dx * dx + dy * dy
                val t = if (d2 < 1e-18) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / d2).coerceIn(0.0, 1.0)
                val distance = p.distance(Point(a.x + t * dx, a.y + t * dy))
                if (distance < best - 1e-12) { best = distance; position = cumulative[i] + t * sqrt(d2) }
            }
            return position
        }
        /** Path from [from] forwards over [span] metres of the ring. */
        fun walk(from: Double, span: Double): List<Point> {
            val start = wrap(from); val end = start + span
            val out = mutableListOf(at(start))
            var i = segmentAt(start) + 1
            while (true) {
                val position = cumulative[i % points.size] + (i / points.size) * length
                if (position >= end - 1e-9) break
                if (position > start + 1e-9) out.add(points[i % points.size])
                i++
            }
            out.add(at(end))
            return out.filterIndexed { k, p -> k == 0 || p.distance(out[k - 1]) > 1e-9 }
        }
        /** Absolute turn, in degrees, at every vertex. */
        fun turns(): DoubleArray = DoubleArray(points.size) { i ->
            val a = points[(i - 1 + points.size) % points.size]; val b = points[i]; val c = points[(i + 1) % points.size]
            val h1 = atan2(b.y - a.y, b.x - a.x); val h2 = atan2(c.y - b.y, c.x - b.x)
            abs(Math.toDegrees(((h2 - h1 + 3 * PI) % (2 * PI)) - PI))
        }
    }

    private fun ring(polygon: Polygon): Ring {
        val coordinates = polygon.exteriorRing.coordinates
        val ordered = if (Orientation.isCCW(coordinates)) coordinates.toList() else coordinates.reversed()
        return Ring(ordered.map { Point(it.x, it.y) })
    }

    /** Turn of [ring] within `slant` on each side of a position: where a ring start (and its slanted link) may go. */
    private class Straightness(val ring: Ring, slant: Double) {
        private val n = ring.points.size
        // Vertices over three laps, with the cumulative turn, to read the turn within any window by two binary searches.
        private val positions = DoubleArray(3 * n) { k -> ring.cumulative[k % n] + (k / n - 1) * ring.length }
        private val sums = ring.turns().let { turns -> DoubleArray(3 * n + 1).also { s -> for (k in 0 until 3 * n) s[k + 1] = s[k] + turns[k % n] } }
        private val half = slant
        private fun before(x: Double): Int {
            var low = 0; var high = 3 * n
            while (low < high) { val mid = (low + high) / 2; if (positions[mid] < x) low = mid + 1 else high = mid }
            return low
        }
        fun straight(u: Double): Boolean {
            val w = ((u % ring.length) + ring.length) % ring.length
            return sums[before(w + half + 1e-9)] - sums[before(w - half - 1e-9)] <= STRAIGHT_TURN_DEGREES
        }
        /** Straight stretches as (first, last) positions sampled every [step], the last one possibly past the length (wrap). */
        fun stretches(step: Double): List<Pair<Double, Double>> {
            val count = max(1, ceil(ring.length / step).toInt())
            val flags = BooleanArray(count) { straight(it * ring.length / count) }
            if (flags.all { it }) return listOf(0.0 to ring.length)
            val first = flags.indexOfFirst { !it }
            val out = mutableListOf<Pair<Double, Double>>()
            var k = 0
            while (k < count) {
                val i = (first + k) % count
                if (!flags[i]) { k++; continue }
                var j = k
                while (j + 1 < count && flags[(first + j + 1) % count]) j++
                val u0 = (first + k).toDouble() * ring.length / count
                val u1 = (first + j).toDouble() * ring.length / count
                out += u0 to u1
                k = j + 1
            }
            return out
        }
    }

    /**
     * Start of the outer ring: in the middle fifth of a straight stretch (at most [STRAIGHT_TURN_DEGREES] of turn within
     * [slant] on each side), so that the next starts, the points of the inner rings nearest to it, stay on straight parts
     * as long as possible; among the stretches at least half as long as the longest one (a short edge stops being straight
     * after a few rings), the one nearest to [hint] (the longest without one). The point nearest to [hint] when the ring
     * has no straight stretch.
     */
    private fun startOn(ring: Ring, hint: Point?, slant: Double): Double {
        val all = Straightness(ring, slant).stretches(max(0.05, ring.length / 2000))
        if (all.isEmpty()) return hint?.let { ring.project(it) } ?: 0.0
        val longest = all.maxOf { it.second - it.first }
        val stretches = all.filter { it.second - it.first >= longest / 2 - 1e-9 }
        fun band(s: Pair<Double, Double>): Pair<Double, Double> {
            val middle = (s.first + s.second) / 2; val width = (s.second - s.first) / 10
            return (middle - width) to (middle + width)
        }
        if (hint == null) return stretches.maxBy { it.second - it.first }.let { (it.first + it.second) / 2 }
        var best = 0.0; var bestDistance = Double.POSITIVE_INFINITY
        for (s in stretches) {
            val (low, high) = band(s)
            // Point of the band nearest to the hint, by sampling (the band is short and straight).
            for (k in 0..10) {
                val u = low + (high - low) * k / 10
                val distance = ring.at(u).distance(hint)
                if (distance < bestDistance - 1e-9) { best = u; bestDistance = distance }
            }
        }
        return best
    }

    /** Start of an inner ring: the point nearest to [previous] (the start of the ring outside it), or the nearest straight
     * point of the ring within [reach] of it when that one is not on a straight stretch. */
    private fun nextStart(ring: Ring, previous: Point, slant: Double, reach: Double): Double {
        val projected = ring.project(previous)
        val straightness = Straightness(ring, slant)
        if (straightness.straight(projected)) return projected
        val step = max(0.05, ring.length / 2000)
        var best = projected; var bestDistance = reach
        var u = 0.0
        while (u < ring.length) {
            if (straightness.straight(u)) { val d = ring.at(u).distance(previous); if (d < bestDistance - 1e-9) { best = u; bestDistance = d } }
            u += step
        }
        return best
    }

    private class Node(val polygon: Polygon) { val children = mutableListOf<Node>() }
    private class Built(val chains: List<List<Point>>, val rings: Int, val splits: Int, val refused: Int, val parents: List<Int?>)

    /** Spiral chains of one simply connected [part]. */
    private fun spiral(part: Polygon, spacing: Double, radius: Double, start: Point?, valid: (List<Point>) -> Boolean,
                       checkpoint: () -> Unit): Built {
        checkpoint()
        // Ring levels: each part of level j + 1 lies inside one part of level j (opening is monotonic, erosion nested).
        var eroded = offset(part, -EDGE_M)
        val roots = polygons(opening(eroded, radius)).map(::Node)
        var frontier = roots
        val orphans = mutableListOf<Node>()
        var level = 1
        while (frontier.isNotEmpty() && level < MAX_LEVELS) {
            checkpoint()
            eroded = offset(eroded, -spacing)
            if (eroded.isEmpty) break
            val next = polygons(opening(eroded, radius)).map(::Node)
            for (node in next) {
                val inside = node.polygon.interiorPoint
                frontier.firstOrNull { it.polygon.covers(inside) }?.children?.add(node) ?: orphans.add(node)
            }
            frontier = next
            level++
        }
        val chains = mutableListOf<List<Point>>()
        val parents = mutableListOf<Int?>()
        var rings = 0; var splits = 0; var refused = 0
        // Ring to start a chain from, hint for its start, chain it continues inwards from.
        val starts = ArrayDeque((roots + orphans).map { Triple(it, start, null as Int?) })
        while (starts.isNotEmpty()) {
            checkpoint()
            val (first, hint, parent) = starts.removeFirst()
            val line = mutableListOf(first)
            while (line.last().children.size == 1) line.add(line.last().children[0])
            val built = chain(line.map { ring(it.polygon) }, hint, spacing, valid)
            rings += built.driven
            built.path?.let { chains.add(it); parents.add(parent) }
            val end = built.path?.last() ?: hint
            val own = if (built.path != null) chains.lastIndex else parent
            val refusedAt = built.refusedAt
            if (refusedAt != null) {
                refused++
                val resume = if (refusedAt + 1 < line.size) listOf(line[refusedAt + 1]) else line[refusedAt].children
                resume.forEach { starts.addLast(Triple(it, end, own)) }
            } else if (built.cutAt != null) starts.addLast(Triple(line[built.cutAt], end, own))
            else {
                val children = line.last().children
                if (children.size > 1) splits++
                children.forEach { starts.addLast(Triple(it, end, own)) }
            }
        }
        return Built(chains, rings, splits, refused, parents)
    }

    /**
     * One spiral: [path] (null when even the outer ring is refused), [refusedAt] the first refused ring, [driven] the
     * number of rings driven, [cutAt] the first ring left to a new chain because its start is too far from the previous
     * one for a slanted link (more than two spacings: the previous ring had no straight stretch near it).
     */
    private class Chain(val path: List<Point>?, val refusedAt: Int?, val driven: Int, val cutAt: Int?)

    private fun chain(all: List<Ring>, hint: Point?, spacing: Double, valid: (List<Point>) -> Boolean): Chain {
        val slant = slantLength(spacing)
        val first = startOn(all[0], hint, slant)
        val found = mutableListOf(first)
        for (j in 1 until all.size) {
            val next = nextStart(all[j], all[j - 1].at(found[j - 1]), slant, 2 * spacing)
            if (all[j].at(next).distance(all[j - 1].at(found[j - 1])) > 2 * spacing) break
            found += next
        }
        val rings = all.take(found.size)
        val cutAt = if (found.size < all.size) found.size else null
        val starts = found.toDoubleArray()
        fun along(j: Int) = min(slant, rings[j].length / 3)
        /** Ring [j]: whole when [closed] (and always for the outer ring), otherwise up to `T` before its start then slanted
         * to the start of the next ring. */
        fun piece(j: Int, closed: Boolean): List<Point> {
            val ring = rings[j]
            val from = if (j == 0) starts[0] - along(0) else starts[j]
            val path = ring.walk(from, if (closed || j == 0) ring.length else ring.length - along(j))
            return if (closed) path else path + rings[j + 1].at(starts[j + 1])
        }
        val pieces = mutableListOf<List<Point>>()
        fun withBefore(k: Int, path: List<Point>) = if (k == 0) path else listOf(pieces[k - 1][pieces[k - 1].size - 2]) + path
        var refusedAt: Int? = null
        for (j in rings.indices) {
            val path = piece(j, j == rings.lastIndex)
            if (valid(withBefore(j, path))) pieces.add(path) else { refusedAt = j; break }
        }
        if (refusedAt != null) {
            // The chain ends on the last ring accepted, driven whole.
            while (pieces.isNotEmpty()) {
                val k = pieces.lastIndex
                val closed = piece(k, true)
                if (valid(withBefore(k, closed))) { pieces[k] = closed; break }
                pieces.removeAt(k)
            }
        }
        if (pieces.isEmpty()) return Chain(null, refusedAt, 0, cutAt)
        val path = pieces.first().toMutableList()
        pieces.drop(1).forEach { piece -> piece.forEach { p -> if (p.distance(path.last()) > 1e-9) path.add(p) } }
        return Chain(path, refusedAt, pieces.size, cutAt)
    }

    /**
     * Parts of [inset] the chains leave unswept (blades [cutWidth] wide), at least [MIN_POCKET_M2] each. Gaps less than two
     * spacings apart are merged (closing), so that the gaps left one after the other along the bisector of a sharp corner
     * or between two parts of a split are filled by a few long passes rather than one detour each.
     */
    private fun pockets(inset: JtsGeometry, chains: List<List<Point>>, spacing: Double, cutWidth: Double): List<Polygon> {
        // Simplified by 5 mm and buffered with few segments: a pocket only needs its rough shape.
        val lines = chains.filter { it.size >= 2 }.map { path ->
            DouglasPeuckerSimplifier.simplify(factory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray()), 0.005)
        }
        val swept = factory.createMultiLineString(lines.filterIsInstance<LineString>().toTypedArray()).buffer(cutWidth / 2, 4)
        val left = inset.difference(swept)
        val merged = try { left.buffer(spacing, 4).buffer(-spacing, 4).intersection(inset) } catch (e: TopologyException) { left }
        return polygons(merged).filter { it.area >= MIN_POCKET_M2 }
    }
}

