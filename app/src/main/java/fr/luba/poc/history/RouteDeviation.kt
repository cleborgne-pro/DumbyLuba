package fr.luba.poc.history

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Who tracked the route of a history task: the measure that tells field runs apart (see `docs/AUTONOMOUS_GATEWAY.md`). */
enum class TrackingLink(private val labelEn: String, private val labelFr: String) {
    /** Pilot phone connected to the robot over Bluetooth, tracking loop on the pilot. */
    BLUETOOTH("Pilot phone, direct Bluetooth", "Téléphone pilote, Bluetooth direct"),
    /** Pilot phone driving the robot frame by frame through the gateway relay (Wi-Fi in the control loop). */
    RELAY("Pilot phone through the gateway relay", "Téléphone pilote via le relais de la passerelle"),
    /** Route handed to the gateway on the robot, which tracks it on its own (Wi-Fi outside the control loop). */
    GATEWAY("Gateway on the robot, autonomous tracking", "Passerelle sur le robot, suivi autonome");

    val label: String get() = tr(labelEn, labelFr)
}

/**
 * Route tracking quality of a task, measured on its recorded track: distance from each position recorded with the
 * blades running to the nearest cutting stripe of the route prepared at start, and holes in the received positions.
 *
 * [medianM], [p95M] and [maxM] cover the [samples] positions recorded while mowing; [offRoute] of them are further than
 * [SEARCH_M] from any stripe (obstacle bypass, route repaired after a contact) and count as [SEARCH_M]. [over30] is the
 * number of positions beyond the 30 cm tracking limit. [gaps] counts the holes of more than [GAP_MS] between two
 * positions off charge (Wi-Fi lost, link lost), [longestGapMs] is the longest one.
 */
data class RouteDeviation(
    val samples: Int, val medianM: Double, val p95M: Double, val maxM: Double, val over30: Int, val offRoute: Int,
    val gaps: Int, val longestGapMs: Long,
) {
    companion object {
        /** Positions further than this from every stripe are not looked up further (and count as this distance). */
        const val SEARCH_M = 3.0
        /** Tracking limit of the route followers (`MapNavFollower.MAX_DEVIATION_M`). */
        const val LIMIT_M = 0.30
        /** Stationary positions are kept every 10 s ([TrackSampler.KEEP_ALIVE_MS]): a longer hole means lost positions. */
        const val GAP_MS = 15_000L

        /** Null without a prepared route or without any position recorded while mowing. */
        fun of(track: TaskTrack): RouteDeviation? {
            val stripes = track.meta?.cutting.orEmpty().filter { it.size >= 2 }
            if (stripes.isEmpty()) return null
            val index = SegmentGrid(stripes)
            val distances = track.samples.filter { it.phase == TrackPhase.CUTTING }.map { index.distance(it.point, SEARCH_M) }.sorted()
            if (distances.isEmpty()) return null
            var gaps = 0
            var longest = 0L
            track.samples.zipWithNext { a, b ->
                if (a.phase == TrackPhase.CHARGING || b.phase == TrackPhase.CHARGING) return@zipWithNext
                val gap = b.atMs - a.atMs
                if (gap > GAP_MS) { gaps++; longest = max(longest, gap) }
            }
            fun quantile(q: Double) = distances[min(distances.size - 1, floor(q * (distances.size - 1) + 0.5).toInt())]
            return RouteDeviation(distances.size, quantile(0.5), quantile(0.95), distances.last(),
                distances.count { it > LIMIT_M }, distances.count { it >= SEARCH_M }, gaps, longest)
        }
    }
}

/** Uniform grid of route segments: nearest-segment distance without scanning the whole route at every position. */
internal class SegmentGrid(polylines: List<List<Point>>, private val cell: Double = 1.0) {
    private val ax = ArrayList<Double>(); private val ay = ArrayList<Double>()
    private val bx = ArrayList<Double>(); private val by = ArrayList<Double>()
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        polylines.forEach { line ->
            line.zipWithNext { a, b ->
                // Pieces no longer than a cell: a long diagonal stripe only fills the cells it crosses.
                val pieces = max(1, kotlin.math.ceil(a.distance(b) / cell).toInt())
                for (k in 0 until pieces) {
                    val s = Point(a.x + (b.x - a.x) * k / pieces, a.y + (b.y - a.y) * k / pieces)
                    val e = Point(a.x + (b.x - a.x) * (k + 1) / pieces, a.y + (b.y - a.y) * (k + 1) / pieces)
                    val i = ax.size
                    ax += s.x; ay += s.y; bx += e.x; by += e.y
                    for (cx in key(min(s.x, e.x))..key(max(s.x, e.x)))
                        for (cy in key(min(s.y, e.y))..key(max(s.y, e.y))) cells.getOrPut(pack(cx, cy)) { mutableListOf() } += i
                }
            }
        }
    }

    private fun key(v: Double) = floor(v / cell).toInt()
    private fun pack(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    /** Distance from [p] to the nearest segment, capped at [limit]. */
    fun distance(p: Point, limit: Double): Double {
        val cx = key(p.x); val cy = key(p.y)
        var best = limit
        // A segment at distance d lies at most floor(d / cell) + 1 cells away.
        val rings = floor(limit / cell).toInt() + 1
        for (r in 0..rings) {
            // Every segment closer than r cells has been seen once ring r - 1 is done.
            if (best <= (r - 1) * cell) break
            for (x in cx - r..cx + r) for (y in cy - r..cy + r) {
                if (max(kotlin.math.abs(x - cx), kotlin.math.abs(y - cy)) != r) continue
                cells[pack(x, y)]?.forEach { i -> best = min(best, segment(p, i)) }
            }
        }
        return best
    }

    private fun segment(p: Point, i: Int): Double {
        val dx = bx[i] - ax[i]; val dy = by[i] - ay[i]
        val len = dx * dx + dy * dy
        val t = if (len <= 0.0) 0.0 else (((p.x - ax[i]) * dx + (p.y - ay[i]) * dy) / len).coerceIn(0.0, 1.0)
        val ex = ax[i] + t * dx - p.x; val ey = ay[i] + t * dy - p.y
        return sqrt(ex * ex + ey * ey)
    }
}
