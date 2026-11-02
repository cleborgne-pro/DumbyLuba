package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Telemetry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

/**
 * Why a stretch of route is driven slower than the set speed ([AdaptiveSpeed]). [percent]: share of the set forward
 * speed kept there.
 */
enum class SpeedReason(val percent: Int, private val labelEn: String, private val labelFr: String) {
    /** Bottleneck: the robot centre cannot keep [BORDER_CLEARANCE_M] from the outline (passage, narrow strip). */
    NARROW(60, "narrow passages", "passages étroits"),
    /** Within the transit band of a DANGER boundary portion (drop, road, water). */
    DANGER(60, "near Danger borders", "près des bordures Danger"),
    /** Difficult area of the difficulty heatmap (repeated contacts, slides, blockages): precision mode. */
    INCIDENT(70, "incident areas", "zones à incidents");

    val label: String get() = tr(labelEn, labelFr)
}

/**
 * Stretch of a route driven at [percent] % of the set speed, for [reason]. [from] and [to] are route positions: edge
 * index plus the fraction travelled along that edge ([AdaptiveSpeed.position]), so that a long transit edge is only
 * slowed where it crosses the area. Stretches may overlap: the strictest applies.
 */
data class SpeedCap(val from: Double, val to: Double, val percent: Int, val reason: SpeedReason? = null) {
    init { require(from.isFinite() && to.isFinite() && from >= 0.0 && from < to && percent in 1..100) }
}

/**
 * Adaptive speed: the set forward speed is lowered where precision matters more than pace, never raised.
 *
 * Two sources, combined by taking the lowest share:
 * - the route geometry, prepared once per route ([SpeedZones.caps]) and sent with the route to the gateway: bottlenecks,
 *   DANGER portions, difficult areas;
 * - the RTK solution, read live by every tracking ([rtkPercent]): float, single or stale positions.
 *
 * The tracking keeps its own limits (curves, corners, deviation governor, braking): this only lowers the ceiling they
 * start from. Never below [MIN_LINEAR] (unless the set speed is lower): slower, the LUBA 2 creeps and a slope reads
 * as a blockage (docs/DISTURBANCE_BENCH.md, point 6).
 */
object AdaptiveSpeed {
    /** Lowest forward command an adaptive limit goes down to (≈ 0.1 m/s), above the approach minimum (80). */
    const val MIN_LINEAR = 100
    /** RTK float: position good to a few decimetres. */
    const val RTK_FLOAT_PERCENT = 60
    /** RTK single or none, or no RTK report for [RTK_STALE_MS]: position good to a metre at best. */
    const val RTK_LOST_PERCENT = 50
    const val RTK_STALE_MS = 10_000L
    private const val RTK_FLOAT = 5

    /** Share of the set speed allowed by the RTK solution; 100 when FIX or when the robot does not report it. */
    fun rtkPercent(rtkStatus: Int?, rtkAgeMs: Long?): Int = when {
        rtkStatus == null -> 100
        rtkAgeMs != null && rtkAgeMs > RTK_STALE_MS -> RTK_LOST_PERCENT
        rtkStatus == Telemetry.RTK_FIX -> 100
        rtkStatus == RTK_FLOAT -> RTK_FLOAT_PERCENT
        else -> RTK_LOST_PERCENT
    }

    /** Route position of [point] on edge [index] of [path]: the index plus the fraction travelled along the edge. */
    fun position(path: List<SimEdge>, index: Int, point: Point): Double =
        index + PathFollower.progressOf(point, path[index]).coerceIn(0.0, 1.0)

    /** Share of the set speed at route [position]: the strictest stretch covering it; 100 outside them. */
    fun percentAt(caps: List<SpeedCap>, position: Double): Int =
        caps.minOfOrNull { if (position >= it.from && position < it.to) it.percent else 100 } ?: 100

    /**
     * Set forward command at [at] on edge [index] of [path]: [speedPercent] lowered by the slowed stretch there ([caps])
     * and by the RTK solution of [live] ([wallNow]: the clock of its report times); the set speed alone when the
     * adaptive speed is switched off ([enabled]: the pilot's switch, sent to the gateway with each leg).
     */
    fun setLinear(speedPercent: Int, caps: List<SpeedCap>, path: List<SimEdge>, index: Int, at: Point, live: Telemetry, wallNow: Long,
                  enabled: Boolean = SmartFeatures.adaptiveSpeed): Int =
        if (!enabled) PathFollower.linearFor(speedPercent)
        else linear(PathFollower.linearFor(speedPercent), min(percentAt(caps, position(path, index, at)),
            rtkPercent(live.rtkStatus, live.lastRtkAt?.let { wallNow - it })))

    /** Forward command limit: [linear] lowered to [percent] %, not below [MIN_LINEAR] unless [linear] already is. */
    fun linear(linear: Int, percent: Int): Int =
        if (percent >= 100) linear else max(min(linear, MIN_LINEAR), (linear * percent / 100.0).roundToInt())

    /** Preview line: "Slower: 12 m (narrow passages 4 m, near Danger borders 8 m)". */
    fun summary(slowed: Map<SpeedReason, Double>): String {
        val parts = slowed.entries.sortedBy { it.key.ordinal }.joinToString(", ") { (reason, m) -> "${reason.label} ${m.roundToInt()} m" }
        val total = slowed.values.sum().roundToInt()
        return tr("Slower: $total m ($parts)", "Plus lent : $total m ($parts)")
    }

    /** Simulated speed (m/s): [speedMps] lowered to [percent] % like [linear], not below the [MIN_LINEAR] speed. */
    fun speedMps(speedMps: Double, percent: Int): Double =
        if (percent >= 100) speedMps else max(min(speedMps, MIN_LINEAR * DeadReckoning.DEFAULT_METERS_PER_UNIT), speedMps * percent / 100.0)

    /** Metres of [path] driven slower, by reason (preview); overlaps of two reasons counted for each. */
    fun slowedMeters(path: List<SimEdge>, caps: List<SpeedCap>): Map<SpeedReason, Double> {
        fun metres(position: Double): Double {
            val k = position.toInt().coerceAtMost(path.size)
            return (0 until k).sumOf { path[it].meters } + if (k < path.size) (position - k) * path[k].meters else 0.0
        }
        return caps.filter { it.reason != null }.groupBy { requireNotNull(it.reason) }
            .mapValues { (_, list) -> list.sumOf { metres(it.to) - metres(it.from) } }
            .filterValues { it > 0.0 }
    }
}

/**
 * Areas of a mission where the route is driven slower ([AdaptiveSpeed]), built from the planner surfaces: [narrow]
 * bottlenecks of the centre surface, [danger] transit band of the DANGER portions, [incidents] difficult areas. Null
 * geometries: none of that kind. Immutable, shared between threads.
 */
class SpeedZones(narrow: Geometry?, danger: Geometry?, incidents: Geometry?) {
    private val zones: List<Pair<SpeedReason, PreparedGeometry>> = listOfNotNull(
        narrow?.takeUnless { it.isEmpty }?.let { SpeedReason.NARROW to PreparedGeometryFactory.prepare(it) },
        danger?.takeUnless { it.isEmpty }?.let { SpeedReason.DANGER to PreparedGeometryFactory.prepare(it) },
        incidents?.takeUnless { it.isEmpty }?.let { SpeedReason.INCIDENT to PreparedGeometryFactory.prepare(it) })
    private val factory = GeometryFactory()

    val isEmpty: Boolean get() = zones.isEmpty()

    /** Slowed stretches of [path]: the parts of its edges inside each area, started [LEAD_M] earlier to brake before. */
    fun caps(path: List<SimEdge>): List<SpeedCap> {
        if (zones.isEmpty() || path.isEmpty()) return emptyList()
        val starts = DoubleArray(path.size + 1).also { s -> path.forEachIndexed { i, e -> s[i + 1] = s[i] + e.meters } }
        fun position(metres: Double): Double {
            val m = metres.coerceIn(0.0, starts.last())
            var k = starts.binarySearch(m).let { if (it >= 0) it else -it - 2 }.coerceIn(0, path.size - 1)
            while (k < path.size - 1 && path[k].meters < 1e-9) k++
            return if (path[k].meters < 1e-9) k.toDouble() else k + ((m - starts[k]) / path[k].meters).coerceIn(0.0, 1.0)
        }
        return zones.flatMap { (reason, area) ->
            val stretches = mutableListOf<Pair<Double, Double>>()
            path.forEachIndexed { i, edge -> inside(edge, area).forEach { (t0, t1) ->
                stretches.add(starts[i] + t0 * edge.meters to starts[i] + t1 * edge.meters) } }
            merged(stretches.map { (a, b) -> (a - LEAD_M).coerceAtLeast(0.0) to b })
                .mapNotNull { (a, b) -> val from = position(a); val to = position(b)
                    if (to > from) SpeedCap(from, to, reason.percent, reason) else null }
        }.sortedBy { it.from }
    }

    /** Fractions [t0, t1] of [edge] inside [area] (a degenerate edge: all of it or nothing). */
    private fun inside(edge: SimEdge, area: PreparedGeometry): List<Pair<Double, Double>> {
        val a = Coordinate(edge.a.x, edge.a.y)
        if (edge.meters < 1e-9) return if (area.intersects(factory.createPoint(a))) listOf(0.0 to 1.0) else emptyList()
        val line = factory.createLineString(arrayOf(a, Coordinate(edge.b.x, edge.b.y)))
        if (!area.intersects(line)) return emptyList()
        if (area.containsProperly(line)) return listOf(0.0 to 1.0)
        val part = try { area.geometry.intersection(line) } catch (_: RuntimeException) {
            // Degenerate intersection: the whole edge, on the safe side.
            return listOf(0.0 to 1.0)
        }
        return (0 until part.numGeometries).map { part.getGeometryN(it) }.mapNotNull { g ->
            val ts = g.coordinates.map { PathFollower.progressOf(Point(it.x, it.y), edge).coerceIn(0.0, 1.0) }
            (ts.minOrNull() ?: return@mapNotNull null) to (ts.maxOrNull() ?: return@mapNotNull null)
        }.filter { (t0, t1) -> t1 - t0 > 1e-9 }
    }

    /** Overlapping or touching metre ranges joined. */
    private fun merged(ranges: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        ranges.sortedBy { it.first }.forEach { r ->
            val last = out.lastOrNull()
            if (last != null && r.first <= last.second + 1e-6) out[out.size - 1] = last.first to maxOf(last.second, r.second)
            else out.add(r)
        }
        return out
    }

    companion object {
        /** Slowing starts this far before an area: from 0.48 to 0.29 m/s at the tracking's 0.25 m/s² deceleration. */
        const val LEAD_M = 0.5
        val NONE = SpeedZones(null, null, null)

        /** Zones of a planner terrain: bottlenecks of [free] around [comfort] ([PreferredArea.NARROW_M]), [danger], [incidents]. */
        fun of(free: Geometry, comfort: Geometry?, danger: Geometry?, incidents: Geometry?) =
            SpeedZones(bottlenecks(free, comfort, PreferredArea.NARROW_M), danger, incidents)
        /**
         * Bottlenecks: parts of the centre surface [free] farther than [narrowM] from the comfort surface [comfort]
         * (robot centre [BORDER_CLEARANCE_M] from the outline), as [PreferredArea.build] defines them. Null without
         * comfort surface (the whole map is narrow: no difference to make) or on a degenerate geometry.
         */
        fun bottlenecks(free: Geometry, comfort: Geometry?, narrowM: Double): Geometry? = try {
            comfort?.takeUnless { it.isEmpty }?.let { free.difference(it.buffer(narrowM, 4)) }?.takeUnless { it.isEmpty }
        } catch (_: RuntimeException) {
            // TopologyException on a degenerate surface: no bottleneck slowdown rather than no route.
            null
        }
    }
}

/**
 * Slowed stretches of the paths followed one after the other, computed once per path ([SpeedZones.caps]); none while
 * the adaptive speed is switched off ([SmartFeatures]).
 */
class PathSpeedCaps(private val zones: () -> SpeedZones) {
    private var last: Pair<List<SimEdge>, List<SpeedCap>>? = null
    fun of(path: List<SimEdge>): List<SpeedCap> = if (!SmartFeatures.adaptiveSpeed) emptyList()
        else last?.takeIf { it.first === path }?.second ?: zones().caps(path).also { last = path to it }
}
