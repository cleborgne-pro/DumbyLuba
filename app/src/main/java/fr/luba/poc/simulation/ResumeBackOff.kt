package fr.luba.poc.simulation

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point

/**
 * "Unblock" before resuming a mowing (Mowing interrupted panel): straight back-off, heading unchanged, blades OFF,
 * towards a safe position a little before the blocking point.
 *
 * The back-off follows, in this order of preference, the recent actual trail ([recorded], mowing of this session), the
 * route mowed just before the stop ([planned], known from the save even after an app restart), otherwise a straight
 * line behind the robot; in all three cases each step is checked on the map (borders, no-go zones, physical obstacles)
 * and the back-off stops before the first blocked step ([ObstacleBypass.backOffCandidates]). The chosen point is the
 * nearest where the robot can pivot and travel with the mowing margins ([ObstacleBypass.backOff]), otherwise the
 * farthest allowing a physical pivot, otherwise the farthest admissible one.
 */
object ResumeBackOff {
    /** Back-off at most: "a little before" the blockage, without going far back along the trail. */
    const val MAX_M = 1.5

    /** Trail or route used as a guide only if the robot is still on it. */
    const val NEAR_M = 0.5

    /** Length of mowed route kept behind the stop point. */
    private const val PLANNED_M = 6.0

    enum class Guide { TRAIL, PLANNED, STRAIGHT }

    /** Prepared route travelled before [edge]/[point] (the last [PLANNED_M] metres), from the oldest to the stop point. */
    fun plannedBefore(edges: List<SimEdge>, edge: Int, point: Point?): List<Point> {
        if (edges.isEmpty() || edge !in 0..edges.size) return emptyList()
        val end = if (edge < edges.size) point?.let { MissionProgress.project(edges[edge], it) } ?: edges[edge].a else edges.last().b
        val out = ArrayDeque<Point>().apply { addFirst(end) }
        var length = 0.0
        var i = minOf(edge, edges.size) - 1
        if (edge < edges.size && edges[edge].a.distance(end) > 1e-6) { length += edges[edge].a.distance(end); out.addFirst(edges[edge].a) }
        while (i >= 0 && length < PLANNED_M) {
            val e = edges[i]
            if (out.first().distance(e.b) > 0.05) break // discontinuous route: stop here
            length += e.meters; out.addFirst(e.a); i--
        }
        return out.toList()
    }

    private fun distance(p: Point, line: List<Point>): Double = when {
        line.isEmpty() -> Double.POSITIVE_INFINITY
        line.size == 1 -> p.distance(line[0])
        else -> line.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }
    }

    /** Back-off guide and its polyline: actual trail, otherwise route mowed before the stop, otherwise straight line behind the robot. */
    fun guide(here: Point, headingDegrees: Double, recorded: List<Point>, planned: List<Point>): Pair<Guide, List<Point>> {
        val trail = recorded.takeLast(400)
        if (trail.isNotEmpty() && trail.last().distance(here) <= NEAR_M) return Guide.TRAIL to trail
        if (planned.size >= 2 && distance(here, planned) <= NEAR_M) return Guide.PLANNED to planned + here
        val rad = Math.toRadians(headingDegrees)
        val behind = MAX_M + 0.2
        return Guide.STRAIGHT to listOf(Point(here.x - behind * kotlin.math.cos(rad), here.y - behind * kotlin.math.sin(rad)), here)
    }

    /** Chosen back-off point, or null if no step behind the robot is admissible. */
    fun target(routes: TrajectoryPlanner, physical: TrajectoryPlanner, here: Point, headingDegrees: Double, trail: List<Point>): Point? =
        ObstacleBypass.backOff(routes, physical, here, headingDegrees, trail, MAX_M)
            ?: ObstacleBypass.backOffCandidates(physical, here, headingDegrees, trail, MAX_M).lastOrNull()
}
