package fr.luba.poc.simulation

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.map.TurnOptimizer
import kotlin.math.abs

/**
 * Manoeuvres of a prepared route found from its edges alone (turn tracking plan, step C5,
 * docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): rolling U-turns and rounded loops of the Turn Optimizer, other curves
 * (outer lap, rounded borders). Logged with the route (`route_manoeuvres`), measured while it is driven
 * ([ManoeuvreStats], `route_curve_stats`) and by the execution bench, so that a field log compares directly with the
 * bench and the planned duration. No field is added to [SimEdge] nor to the gateway frames.
 *
 * Curved vertex: turn between [MIN_TURN_DEGREES] and [PathFollower.CORNER_DEGREES] (not a pivot corner) with one of its
 * edges shorter than [ARC_EDGE_M], same blade state. Consecutive curved vertices form an arc; arcs joined by one straight
 * tangent of at most [TANGENT_M] or by an inflection form one manoeuvre. Between two edges of at least [PASS_M]
 * (the passes): a turn of [U_DEGREES] or more is a rolling U ([Kind.U_TURN]), or a rounded loop ([Kind.LOOP]) when it
 * also turns the other way by [LOOP_REVERSE_DEGREES] or more. Every other arc turning [CURVE_DEGREES] or more is a curve.
 */
object RouteManoeuvres {
    const val MIN_TURN_DEGREES = 1.0
    const val ARC_EDGE_M = 0.1
    const val TANGENT_M = 2.5
    const val U_DEGREES = 150.0
    const val LOOP_REVERSE_DEGREES = 20.0
    const val PASS_M = 0.3
    const val CURVE_DEGREES = 20.0

    enum class Kind { U_TURN, LOOP, CURVE }

    /**
     * Edges [first]..[last] are the curved ones: the robot enters at the start of [first] and leaves at the end of
     * [last]. [degrees]: signed turn (+ left); [lengthM]: route length of the window.
     */
    data class Manoeuvre(val kind: Kind, val first: Int, val last: Int, val degrees: Double, val lengthM: Double) {
        val turning: Boolean get() = kind != Kind.CURVE
    }

    private fun signed(edges: List<SimEdge>, v: Int) =
        ((PathFollower.headingOf(edges[v + 1]) - PathFollower.headingOf(edges[v]) + 540) % 360) - 180

    /** Manoeuvres of [edges] in route order. */
    fun find(edges: List<SimEdge>): List<Manoeuvre> {
        fun curved(k: Int) = k < edges.size - 1 && abs(signed(edges, k)).let { it >= MIN_TURN_DEGREES && it <= PathFollower.CORNER_DEGREES } &&
            (edges[k].meters < ARC_EDGE_M || edges[k + 1].meters < ARC_EDGE_M) && edges[k].bladesOn == edges[k + 1].bladesOn
        // Arcs: runs of curved vertices v (between edges v and v + 1) turning the same way.
        data class Arc(val from: Int, val to: Int, val sum: Double)
        val arcs = mutableListOf<Arc>()
        var v = 0
        while (v < edges.size - 1) {
            if (!curved(v)) { v++; continue }
            val left = signed(edges, v) > 0
            var w = v; var sum = signed(edges, v)
            while (curved(w + 1) && (signed(edges, w + 1) > 0) == left && edges[w + 1].meters < ARC_EDGE_M) { w++; sum += signed(edges, w) }
            arcs += Arc(v, w, sum)
            v = w + 1
        }
        fun length(first: Int, last: Int) = (first..last).sumOf { edges[it].meters }
        val out = mutableListOf<Manoeuvre>()
        var k = 0
        while (k < arcs.size) {
            // Rounded loop: away the other way first, then around; a U: one arc, or two joined by a tangent.
            val a = arcs[k]
            // Joined: one tangent edge between them, or an inflection vertex (the turns of the two arcs cancel there).
            fun follows(prev: Arc, arc: Arc?) = arc?.takeIf { it.from - prev.to in 1..2 && (prev.to + 1..it.from).all { e -> edges[e].meters <= TANGENT_M } }
            val next = follows(a, arcs.getOrNull(k + 1))
            val joined = mutableListOf(a)
            if (next != null) { joined += next; follows(next, arcs.getOrNull(k + 2))?.let { joined += it } }
            // Longest prefix of the joined arcs (one to three) that makes a U or a loop between two passes.
            val found = (joined.size downTo 1).firstNotNullOfOrNull { n ->
                val parts = joined.take(n)
                val total = parts.sumOf { it.sum }
                val reverse = parts.filter { it.sum * total < 0 }.sumOf { abs(it.sum) }
                val to = parts.last().to
                val passes = edges[a.from].meters >= PASS_M && to + 1 < edges.size && edges[to + 1].meters >= PASS_M
                val kind = when {
                    !passes || abs(total) < U_DEGREES -> null
                    reverse >= LOOP_REVERSE_DEGREES -> Kind.LOOP
                    n <= 2 && parts.all { it.sum * total > 0 } -> Kind.U_TURN
                    else -> null
                }
                kind?.let { Manoeuvre(it, a.from + 1, to, total, length(a.from + 1, to)) to n }
            }
            if (found != null) { out += found.first; k += found.second; continue }
            if (abs(a.sum) >= CURVE_DEGREES) out += Manoeuvre(Kind.CURVE, a.from + 1, a.to, a.sum, length(a.from + 1, a.to))
            k++
        }
        return out
    }

    /** Planned duration of [m] (curves and exits of the tracking, [TurnOptimizer.drive]) at [speedMps]. */
    fun plannedSeconds(edges: List<SimEdge>, m: Manoeuvre, speedMps: Double): Double =
        TurnOptimizer.drive(listOf(edges[m.first].a) + (m.first..m.last).map { edges[it].b }, speedMps).durationS

    /** Short text of the manoeuvres for the route log: kind, edge range, degrees, metres and planned seconds. */
    fun describe(edges: List<SimEdge>, manoeuvres: List<Manoeuvre>, speedMps: Double, limit: Int = 120): String =
        manoeuvres.take(limit).joinToString(";") { m ->
            "%s %d-%d %.0f° %.2f m %.1f s".format(java.util.Locale.ROOT, m.kind.name, m.first, m.last, m.degrees, m.lengthM, plannedSeconds(edges, m, speedMps))
        } + if (manoeuvres.size > limit) ";…" else ""
}

/**
 * Measured manoeuvres while a route is driven (turn tracking plan, step C5): fed with the followed edge and the measured
 * position (local tracking and gateway progress alike), it returns the summary of a manoeuvre when the robot leaves it
 * (`route_curve_stats`): duration, median speed between positions and largest distance to the manoeuvre's edges.
 */
class ManoeuvreStats(private val edges: List<SimEdge>, manoeuvres: List<RouteManoeuvres.Manoeuvre> = RouteManoeuvres.find(edges)) {
    data class Summary(val manoeuvre: RouteManoeuvres.Manoeuvre, val durationS: Double, val medianSpeedMps: Double, val maxDeviationM: Double)

    private val pending = ArrayDeque(manoeuvres.filter { it.turning || abs(it.degrees) >= 45.0 })
    private var enteredMs = -1L
    private var last: Pair<Long, Point>? = null
    private val speeds = mutableListOf<Double>()
    private var worst = 0.0

    /** [index]: edge followed in [edges]; [position]: measured centre (null: unknown). */
    fun update(nowMs: Long, index: Int, position: Point?): Summary? {
        while (pending.isNotEmpty() && enteredMs < 0 && index > pending.first().last) pending.removeFirst()
        val m = pending.firstOrNull() ?: return null
        if (enteredMs < 0) { if (index < m.first) return null; enteredMs = nowMs }
        if (position != null && last?.second != position) {
            last?.let { (at, p) -> if (nowMs > at) speeds += p.distance(position) / ((nowMs - at) / 1000.0) }
            last = nowMs to position
            worst = maxOf(worst, (maxOf(m.first - 1, 0)..minOf(m.last + 1, edges.lastIndex)).minOf { Geometry.distance(position, edges[it].a, edges[it].b) })
        }
        if (index <= m.last) return null
        val summary = Summary(m, (nowMs - enteredMs) / 1000.0, speeds.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }, worst)
        pending.removeFirst(); enteredMs = -1L; last = null; speeds.clear(); worst = 0.0
        return summary
    }
}
