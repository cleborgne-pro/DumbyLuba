package fr.luba.poc.simulation

import fr.luba.poc.map.AreaIndex
import fr.luba.poc.map.Point
import kotlin.math.max
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Target distance between the centre trajectory and the drawn outline (zones, exclusions) in transit. */
const val BORDER_CLEARANCE_M = 0.5
/** Direct hop already safe kept as is: moving away and back would cost at least 2 × 50 cm. */
const val SHORT_HOP_M = 2 * BORDER_CLEARANCE_M

/**
 * Preferred surface for transits, always included in the planner's safe surface:
 * it replaces no check (segments, footprint, exclusions), it only guides the choice.
 */
internal class PreferredArea(val area: Geometry, private val comfort: Geometry? = null) {
    private val index = AreaIndex(area, 0.00001)
    private fun covers(a: Point, b: Point) = if (a.distance(b) < 1e-9) index.covers(a) else index.covers(a, b)
    val routes = ShortestRoutes(area, ::covers)
    private val comfortIndex by lazy { comfort?.let { AreaIndex(it, 0.00001) } }

    /**
     * Crossing surface ([comfort] given): the same surface with its bottlenecks shrunk by each of [CENTRING_INSETS_M],
     * the comfort surface kept whole. The shortest path hugs the corners of the surface it runs in: through a narrow
     * passage it grazed one side, at the safety margin; in a shrunk bottleneck it keeps close to the middle.
     */
    private val centred: List<PreferredArea> by lazy {
        val kept = comfort ?: return@lazy emptyList()
        CENTRING_INSETS_M.mapNotNull { inset ->
            try {
                val shrunk = area.buffer(-inset, 4).union(kept)
                if (shrunk.isEmpty) null else PreferredArea(shrunk)
            } catch (_: RuntimeException) {
                // Degenerate geometry: this inset is skipped, the plain crossing stays available.
                null
            }
        }
    }

    /**
     * Shortest path from [a] to [b] in this surface; through a bottleneck, the one of the most shrunk surface
     * ([centred]) that still connects them within [CENTRING_EXTRA_M] (or 5 %) of extra length.
     */
    fun between(a: Point, b: Point): List<Point> {
        val plain = routes.between(a, b)
        val inComfort = comfortIndex ?: return plain
        if (plain.zipWithNext().all { (p, q) -> if (p.distance(q) < 1e-9) inComfort.covers(p) else inComfort.covers(p, q) }) return plain
        val limit = plain.length() + max(CENTRING_EXTRA_M, 0.05 * plain.length())
        for (surface in centred) {
            val path = try { surface.routes.between(a, b) } catch (_: IllegalArgumentException) { continue }
            if (path.length() <= limit) return path
        }
        return plain
    }

    private fun List<Point>.length() = zipWithNext().sumOf { (p, q) -> p.distance(q) }
    private val factory = GeometryFactory()
    private val attached = object : LinkedHashMap<Point, Point?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Point, Point?>?) = size > 4096
    }

    /** Attachment point: the point itself if it is inside the surface, otherwise the nearest point of the
     * surface (perpendicular exit from the edge), if the exit segment is safe ([safe]). */
    fun attach(point: Point, safe: (Point, Point) -> Boolean): Point? {
        if (index.covers(point)) return point
        synchronized(attached) { if (attached.containsKey(point)) return attached[point] }
        val nearest = DistanceOp(area, factory.createPoint(Coordinate(point.x, point.y))).nearestPoints()[0]
        val q = Point(nearest.x, nearest.y)
        val length = q.distance(point)
        // Slight push inwards: the attachment point must be strictly inside the surface.
        val inside = if (length > 1e-9) Point(q.x + (q.x - point.x) / length * 0.02, q.y + (q.y - point.y) / length * 0.02) else q
        val result = listOf(inside, q).firstOrNull { index.covers(it) && safe(point, it) }
        synchronized(attached) { attached[point] = result }
        return result
    }

    /** Distance from [point] to the surface (0 inside): what the centre lacks to be [BORDER_CLEARANCE_M] from the outline. */
    fun deficit(point: Point): Double =
        if (index.covers(point)) 0.0 else DistanceOp(area, factory.createPoint(Coordinate(point.x, point.y))).distance()

    companion object {
        /** Components too small to hold a route: ignored (avoids geometry slivers). */
        private const val MIN_COMPONENT_M2 = 0.02
        /** Beyond this distance from the comfort surface, a point of the safe surface is in a bottleneck. */
        const val NARROW_M = BORDER_CLEARANCE_M + 0.25
        /** Insets of the bottlenecks tried to centre a transit, widest first ([centred]). */
        val CENTRING_INSETS_M = listOf(0.3, 0.2, 0.12, 0.06)
        /** Extra length accepted for a centred transit through a bottleneck. */
        const val CENTRING_EXTRA_M = 0.5

        /**
         * Comfort: safe surface at [BORDER_CLEARANCE_M] from the drawn outline. Crossing: comfort plus the
         * bottlenecks (parts of the safe surface far from any comfort surface) and their surroundings,
         * to get through a narrow passage without allowing travel along the edges of large surfaces.
         */
        fun build(drawn: Geometry, free: Geometry): Pair<PreferredArea, PreferredArea?>? = try {
            val inner = drawn.buffer(-BORDER_CLEARANCE_M, 4).intersection(free)
            val comfort = UnaryUnionOp.union((0 until inner.numGeometries).map { inner.getGeometryN(it) }
                .filter { it is Polygon && it.area >= MIN_COMPONENT_M2 })
            if (comfort == null || comfort.isEmpty) null else {
                val narrow = free.difference(comfort.buffer(NARROW_M, 4))
                val crossing = if (narrow.isEmpty) null
                    else comfort.union(narrow.buffer(NARROW_M + 0.05, 4).intersection(free))
                PreferredArea(comfort) to crossing?.let { PreferredArea(it, comfort) }
            }
        } catch (_: RuntimeException) {
            // Degenerate geometry (TopologyException…): no preference, shortest safe path.
            null
        }
    }
}
