package fr.luba.poc.map

import kotlin.math.floor

/**
 * **Edge** tool of the correction editor: a drawn line whose first and last points snap to the boundary replaces the
 * part of the boundary between them. By default, the shorter part is replaced; [replace] with `otherSide` replaces the
 * other one.
 */
object EdgeRedraw {
    /** Position on the boundary: segment [segment] (from point i to point i+1), fraction [t], projected point. */
    data class Anchor(val segment: Int, val t: Double, val point: Point) {
        fun position(): Double = segment + t
    }

    fun project(ring: List<Point>, p: Point): Anchor {
        var best = Anchor(0, 0.0, ring.first())
        var bestDistance = Double.MAX_VALUE
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0.0, 1.0)
            val q = Point(a.x + t * dx, a.y + t * dy)
            val d = q.distance(p)
            if (d < bestDistance) { bestDistance = d; best = Anchor(i, t, q) }
        }
        return best
    }

    /** Part of the boundary followed in point order, from [from] to [to] (ends included). */
    fun arc(ring: List<Point>, from: Anchor, to: Anchor): List<Point> {
        val n = ring.size
        val s1 = from.position()
        val span = ((to.position() - s1) % n + n) % n
        val out = mutableListOf(from.point)
        var k = floor(s1).toInt() + 1
        repeat(n) {
            val idx = ((k % n) + n) % n
            val forward = ((idx - s1) % n + n) % n
            if (forward > 0 && forward < span) out.add(ring[idx])
            k++
        }
        out.add(to.point)
        return out
    }

    private fun length(points: List<Point>): Double = points.zipWithNext { a, b -> a.distance(b) }.sum()

    /**
     * Boundary where the line [line] (at least two points, ends snapped to the boundary) replaces the shorter part of the
     * boundary [ring] between its ends, or the other one if [otherSide]. Null if the ends coincide.
     */
    fun replace(ring: List<Point>, line: List<Point>, otherSide: Boolean = false): List<Point>? {
        if (ring.size < 3 || line.size < 2) return null
        val a = project(ring, line.first())
        val b = project(ring, line.last())
        if (a.point.distance(b.point) < 0.05) return null
        val ab = arc(ring, a, b)
        val ba = arc(ring, b, a)
        // Keep the longer arc: the line replaces the shorter arc.
        val keepBa = (length(ba) >= length(ab)) != otherSide
        val inner = line.subList(1, line.size - 1)
        val result = if (keepBa) {
            // a → line → b → kept arc from b to a
            listOf(a.point) + inner + ba.dropLast(1)
        } else {
            // b → reversed line → a → kept arc from a to b
            listOf(b.point) + inner.reversed() + ab.dropLast(1)
        }
        return dedupe(result).takeIf { it.size >= 3 }
    }

    /** Coinciding consecutive points (less than 1 mm) removed, ends included. */
    fun dedupe(points: List<Point>): List<Point> {
        val out = mutableListOf<Point>()
        for (p in points) if (out.isEmpty() || out.last().distance(p) > 1e-3) out.add(p)
        while (out.size > 1 && out.first().distance(out.last()) <= 1e-3) out.removeAt(out.size - 1)
        return out
    }
}

/** Point-by-point editing: move, insert, delete, and readable handles on a dense boundary. */
object PointEditing {
    fun move(points: List<Point>, index: Int, to: Point): List<Point> = points.toMutableList().also { it[index] = to }

    /** Inserts a point in the middle of segment [index] → [index]+1. */
    fun insertAfter(points: List<Point>, index: Int): List<Point> {
        val a = points[index]
        val b = points[(index + 1) % points.size]
        return points.toMutableList().also { it.add(index + 1, Point((a.x + b.x) / 2, (a.y + b.y) / 2)) }
    }

    fun remove(points: List<Point>, index: Int): List<Point>? =
        if (points.size <= 3) null else points.toMutableList().also { it.removeAt(index) }

    /**
     * Indices of the points that get a handle: a Mammotion point about every 50 cm would make the handles unreadable. A
     * point is kept only at least [minSpacing] (pixels) away from the previously kept one and from the first; all points
     * are kept at high zoom. [screen] gives the on-screen position of each point.
     */
    fun handles(points: List<Point>, minSpacing: Double, screen: (Point) -> Point): List<Int> {
        if (points.isEmpty()) return emptyList()
        val s = points.map(screen)
        val kept = mutableListOf(0)
        for (i in 1 until s.size) {
            if (s[i].distance(s[kept.last()]) >= minSpacing && s[i].distance(s[0]) >= minSpacing) kept.add(i)
        }
        return kept
    }

    /** Point closest to [p] among [candidates], within [reach] (same units as the points). */
    fun nearest(points: List<Point>, candidates: List<Int>, p: Point, reach: Double): Int? =
        candidates.filter { points[it].distance(p) <= reach }.minByOrNull { points[it].distance(p) }
}
