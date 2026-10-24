package fr.luba.poc.map

import fr.luba.poc.simulation.MissionCheckpoint
import fr.luba.poc.simulation.MissionProgress
import fr.luba.poc.simulation.MissionResume
import fr.luba.poc.simulation.SimEdge

/**
 * Map preview of "Resume further on": stop point ([stop]), resume point ([target], [skipMeters] of cutting further on
 * the remaining stripes), skipped stripes ([skipped], not mowed) and stripes still to mow after the resume ([ahead]).
 * Recomputed on each slider move, without re-preparing the route.
 */
data class ResumeSkipView(
    val stop: Point, val target: Point, val resume: MissionResume, val skipMeters: Double, val maxMeters: Double,
    val skipped: List<Pair<Point, Point>>, val ahead: List<Pair<Point, Point>>,
) {
    companion object {
        /** Reserve left at the end of the last stripe: a resume must still mow something. */
        const val END_RESERVE_M = 0.5

        /** Maximum distance that can be skipped from the saved stop. */
        fun maxMeters(edges: List<SimEdge>, checkpoint: MissionCheckpoint): Double =
            maxOf(0.0, MissionProgress.remainingCuttingMeters(edges, checkpoint.edge, checkpoint.point) - END_RESERVE_M)

        fun of(edges: List<SimEdge>, checkpoint: MissionCheckpoint, meters: Double): ResumeSkipView? {
            // Remainder reordered by a smart resume (recharge, blockage): no skip along the prepared order.
            if (checkpoint.edge !in edges.indices || !checkpoint.linear) return null
            val max = maxMeters(edges, checkpoint)
            val skip = meters.coerceIn(0.0, max)
            val from = MissionResume(checkpoint.edge, checkpoint.point)
            val to = MissionProgress.skipAhead(edges, checkpoint.edge, checkpoint.point, skip) ?: return null
            val stop = checkpoint.point?.let { MissionProgress.project(edges[checkpoint.edge], it) } ?: edges[checkpoint.edge].a
            val target = to.point ?: edges[to.edge].a
            val ahead = edges.drop(to.edge).mapIndexedNotNull { k, e ->
                if (!e.cutting) null else if (k == 0) target to e.b else e.a to e.b
            }
            return ResumeSkipView(stop, target, to.copy(overlap = skip <= 0.0), skip, max,
                if (skip <= 0.0) emptyList() else MissionProgress.cuttingBetween(edges, from, to), ahead)
        }
    }
}
