package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max

/** Memory of the approach lanes used by the last returns to a base ([ReturnLanes]). */
interface ReturnLaneHistory {
    /** Lanes of the last returns to the base [key], oldest first. */
    fun recent(key: String): List<Int>
    /** A return to the base [key] by [choice] is planned. */
    fun record(key: String, choice: ReturnLaneChoice)

    /** History kept in memory only (tests, one session). */
    class InMemory : ReturnLaneHistory {
        private val lanes = mutableMapOf<String, List<Int>>()
        override fun recent(key: String): List<Int> = lanes[key].orEmpty()
        override fun record(key: String, choice: ReturnLaneChoice) {
            lanes[key] = (recent(key) + choice.lane).takeLast(ReturnLanes.KEEP)
        }
    }
}

/**
 * Return planned by [lane] (0: direct): [edges] up to the approach point, [extraM] metres longer than the direct return,
 * [incidentM] metres of it inside the difficult areas.
 */
data class ReturnLaneChoice(val lane: Int, val edges: List<SimEdge>, val extraM: Double, val incidentM: Double) {
    /** Event log line and its fields. */
    val logText: String get() = if (lane == 0) tr("Return to base by the direct way", "Retour à la base par le chemin direct")
        else tr("Return to base by lane $lane (+${"%.1f".format(Locale.ROOT, extraM)} m)", "Retour à la base par la voie $lane (+${"%.1f".format(Locale.ROOT, extraM)} m)")
    val fields: Map<String, Any?> get() = mapOf("lane" to lane, "extraM" to extraM, "incidentM" to incidentM)
}

/**
 * Several return routes to the base, alternated so that the robot does not wear ruts in the lawn in front of it.
 *
 * Every return ends at the same approach point, in front of the base, where the official docking takes over (project
 * rule). Before it, the returns used to share the shortest path, so the last metres before the approach point were driven
 * by every return of every mowing. A lane passes through a via point [VIA_DISTANCE_M] in front of the approach point, on
 * the docking axis shifted sideways by one of [OFFSETS_M], and reaches the approach point from there: the returns spread
 * over 3.2 m of width there instead of one wheel track.
 *
 * Choice ([choose]): the shortest of the direct return and the lanes, each charged [WEAR_M] per use among the last
 * [RECENT] returns to this base and [INCIDENT_WEIGHT] per metre inside the difficult areas of the difficulty heatmap;
 * a lane longer than the direct return by more than [MAX_EXTRA_M] or [MAX_EXTRA_SHARE] is not offered. Same checks as
 * any return: safe surface, chassis, base safety zone.
 */
object ReturnLanes {
    const val VIA_DISTANCE_M = 3.0
    val OFFSETS_M = listOf(-0.8, 0.8, -1.6, 1.6)
    const val MAX_EXTRA_M = 3.0
    const val MAX_EXTRA_SHARE = 0.15
    /** Cost of one recent use of a lane, in metres of route: about the extra length of the nearest lanes. */
    const val WEAR_M = 4.0
    const val INCIDENT_WEIGHT = 2.0
    const val RECENT = 6
    /** Returns remembered per base. */
    const val KEEP = 12

    /** Base identity of the history: station and approach point to the decimetre (a moved base starts afresh). */
    fun key(base: Point, approach: Point): String =
        "%.1f;%.1f;%.1f;%.1f".format(Locale.ROOT, base.x, base.y, approach.x, approach.y)

    /** Via point of [lane] (1 to [OFFSETS_M] size) in front of [approach], [base] behind it. */
    fun via(base: Point, approach: Point, lane: Int): Point {
        require(lane in 1..OFFSETS_M.size)
        val length = max(base.distance(approach), 1e-9)
        val ux = (approach.x - base.x) / length; val uy = (approach.y - base.y) / length
        val offset = OFFSETS_M[lane - 1]
        return Point(approach.x + ux * VIA_DISTANCE_M - uy * offset, approach.y + uy * VIA_DISTANCE_M + ux * offset)
    }

    /**
     * Return from [start] ([heading]) to [approach], arriving with [axis]: the direct return when [history] is null or
     * the return lanes are switched off ([SmartFeatures]), otherwise the lane chosen by [choose], recorded in [history]. [link] plans the first part (from the robot, escape
     * included); the via → approach part is a plain checked link.
     */
    fun returning(routes: TrajectoryPlanner, start: Point, heading: Double, base: Point, approach: Point, axis: Double,
                  history: ReturnLaneHistory?, link: (Point, Double, Point, Double?) -> List<SimEdge>): ReturnLaneChoice {
        val direct = link(start, heading, approach, axis)
        if (history == null || !SmartFeatures.returnLanes) return ReturnLaneChoice(0, direct, 0.0, 0.0)
        val directM = direct.sumOf { it.meters }
        val candidates = listOf(ReturnLaneChoice(0, direct, 0.0, incidentMeters(routes, direct))) +
            (1..OFFSETS_M.size).mapNotNull { lane -> lane(routes, start, heading, via(base, approach, lane), approach, axis, link)?.let { edges ->
                ReturnLaneChoice(lane, edges, edges.sumOf { it.meters } - directM, incidentMeters(routes, edges)) } }
        val key = key(base, approach)
        return choose(candidates, history.recent(key), directM).also { history.record(key, it) }
    }

    /** Cheapest candidate: length, recent wear and incidents; lanes too long are left out. */
    fun choose(candidates: List<ReturnLaneChoice>, recent: List<Int>, directM: Double): ReturnLaneChoice {
        val last = recent.takeLast(RECENT)
        return candidates.filter { it.lane == 0 || it.extraM <= max(MAX_EXTRA_M, MAX_EXTRA_SHARE * directM) }
            .minWith(compareBy<ReturnLaneChoice> { it.extraM + WEAR_M * last.count { lane -> lane == it.lane } + INCIDENT_WEIGHT * it.incidentM }
                .thenBy { it.lane })
    }

    /** Lane through [via]: null when either part has no safe link or the whole return fails the route checks. */
    private fun lane(routes: TrajectoryPlanner, start: Point, heading: Double, via: Point, approach: Point, axis: Double,
                     link: (Point, Double, Point, Double?) -> List<SimEdge>): List<SimEdge>? = try {
        if (!routes.safe(via, via)) null else {
            val first = link(start, heading, via, null)
            val last = first.lastOrNull { it.meters > 1e-8 }
            val arrival = last?.let { Math.toDegrees(atan2(it.b.y - it.a.y, it.b.x - it.a.x)) } ?: heading
            val second = routes.between(via, approach, arrival, axis)
            routes.requirePath(second, arrival, axis)
            val edges = first + second.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-8 }.map { (a, b) -> SimEdge(a, b, false) }
            RobotRoute.requireOutsideBaseZone(routes, edges)
            edges
        }
    } catch (_: IllegalArgumentException) {
        // No safe lane through this via point: the other lanes and the direct return remain.
        null
    }

    private fun incidentMeters(routes: TrajectoryPlanner, edges: List<SimEdge>): Double =
        AdaptiveSpeed.slowedMeters(edges, routes.speedZones.caps(edges))[SpeedReason.INCIDENT] ?: 0.0
}
