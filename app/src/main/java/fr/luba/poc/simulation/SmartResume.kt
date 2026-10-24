package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point

/**
 * Position in the prepared mowing edges ([MowingSimulation.edges]): edge [edge] and point on it ([point] null: start of
 * the edge). [edge] equal to the number of edges: end of the plan.
 */
data class PlanPos(val edge: Int, val point: Point? = null) {
    init { require(edge >= 0 && (point == null || (point.x.isFinite() && point.y.isFinite()))) }
}

/**
 * Portion `[from, to)` of the prepared edges still to cover. [blockedAt]: portion deferred after a blockage at that
 * position (robot pose), tried again once at the end of the mowing.
 */
data class MissionSpan(val from: PlanPos, val to: PlanPos, val blockedAt: Point? = null) {
    init { require(blockedAt == null || (blockedAt.x.isFinite() && blockedAt.y.isFinite())) }
}

/**
 * Tracking stop because the wheels turn without the robot moving (presumed blockage, no progress), with the edge where
 * it happened: the mission can defer the segment ([SmartResume.deferBlocked]). Still an [IllegalStateException]:
 * everywhere else, same stop as before.
 */
class RouteBlocked(val index: Int, message: String) : IllegalStateException(message)

/** Resume after recharge reordered: [checkpoint] with its new order, transits compared (m). */
data class SmartResumeChoice(val checkpoint: MissionCheckpoint, val entry: Point, val defaultTransitM: Double, val transitM: Double) {
    val savedM: Double get() = defaultTransitM - transitM
}

/**
 * Blockage handled: [checkpoint] whose remainder resumes at [target]. [deferredM]: cutting metres moved to the end of
 * the mowing (tried again there); [abandonedM]: cutting metres given up (blocked again at the same place).
 */
data class BlockageDeferral(val checkpoint: MissionCheckpoint, val target: Point, val deferredM: Double, val abandonedM: Double)

/**
 * Smart resume after a recharge or a blockage, on the saved progress of the mowing ([MissionCheckpoint]).
 *
 * The remainder of a mowing is an ordered list of portions of the prepared edges ([MissionSpan]): the portion in
 * progress (from the interrupted point), then the deferred ones. Reordering never removes a stripe: every cutting
 * metre still to mow stays in exactly one portion, so the resume leaves no unmowed area behind (only a blockage met
 * twice at the same place gives up its short portion, counted in [MissionCheckpoint.skippedMeters]).
 *
 * - After a recharge ([afterRecharge]): the robot leaves the base and no longer has to go back to the interrupted point
 *   first. The remainder is rotated at the entry of a block (start of a cell, after a prepared transit, or start of a
 *   deferred portion) when that reduces the total transit base → entry → … → base by at least [MIN_GAIN_M]. Cells are
 *   kept whole; the interrupted cell is finished at the end, starting again at its interrupted point.
 * - After a blockage ([deferBlocked]): the problematic segment (from the progress up to [CLEARANCE_M] past the blocked
 *   pose) is deferred to the end of the mowing and tried again there; the rest goes on straight away.
 */
object SmartResume {
    /** Minimum transit saving to reorder a resume after recharge. */
    const val MIN_GAIN_M = 2.0
    /** Resume after a blockage: at least this far from the blocked pose... */
    const val CLEARANCE_M = 1.5
    /** ...and at least this far along the stripes. */
    const val MIN_SKIP_M = 0.5
    /** Sampling step along the stripes when looking for the resume point after a blockage. */
    const val STEP_M = 0.25
    /** New blockage this close to a known one: same place. */
    const val SAME_SPOT_M = 1.5
    /** Blockages deferred or bypassed per mission; beyond, the stop is kept. */
    const val MAX_BLOCKAGES = 6
    /** Reordered remainders whose links are computed (most promising first, straight-line bound). */
    const val MAX_EVALUATIONS = 40
    /**
     * Back-off along the trail before leaving a blocked pose: far enough for a full pivot clear of the blocked place
     * ([spotHole]; corners sweep 0.43 m).
     */
    const val BACK_OFF_M = 0.5
    /** Portion shorter than this (cutting metres): already covered. */
    const val EMPTY_M = 0.05

    // ───── Positions and portions ─────

    /** Distance from the start of the edge to the position (0 at the start, the edge length at most). */
    fun offset(edges: List<SimEdge>, pos: PlanPos): Double {
        if (pos.edge >= edges.size) return 0.0
        val e = edges[pos.edge]
        return pos.point?.let { e.a.distance(MissionProgress.project(e, it)) } ?: 0.0
    }

    /** Order of two positions along the plan (negative: [a] before [b]). */
    fun compare(edges: List<SimEdge>, a: PlanPos, b: PlanPos): Double =
        if (a.edge != b.edge) (a.edge - b.edge).toDouble() else offset(edges, a) - offset(edges, b)

    fun pointOf(edges: List<SimEdge>, pos: PlanPos): Point? {
        if (pos.edge >= edges.size) return edges.lastOrNull()?.b
        val e = edges[pos.edge]
        return pos.point?.let { MissionProgress.project(e, it) } ?: e.a
    }

    private fun at(e: SimEdge, offset: Double): Point =
        if (e.meters <= 0.0) e.a else Point(e.a.x + (e.b.x - e.a.x) * offset / e.meters, e.a.y + (e.b.y - e.a.y) * offset / e.meters)

    fun cuttingBefore(edges: List<SimEdge>, pos: PlanPos): Double = MissionProgress.cuttingMetersBefore(edges, pos.edge, pos.point)

    /** Cutting metres of a portion. */
    fun cutting(edges: List<SimEdge>, span: MissionSpan): Double = maxOf(0.0, cuttingBefore(edges, span.to) - cuttingBefore(edges, span.from))

    /** Portions still to cover, in order: the one in progress, then the deferred ones. */
    fun spans(checkpoint: MissionCheckpoint): List<MissionSpan> =
        listOf(MissionSpan(PlanPos(checkpoint.edge, checkpoint.point), checkpoint.activeEnd ?: PlanPos(checkpoint.edgeCount), checkpoint.activeBlocked)) +
            checkpoint.deferred

    fun remainingCutting(edges: List<SimEdge>, checkpoint: MissionCheckpoint): Double = spans(checkpoint).sumOf { cutting(edges, it) }

    /** Nothing left to mow in any portion. */
    fun mowingDone(edges: List<SimEdge>, checkpoint: MissionCheckpoint): Boolean =
        if (checkpoint.linear) MissionProgress.mowingDone(edges, checkpoint.edge, checkpoint.point)
        else spans(checkpoint).all { cutting(edges, it) < EMPTY_M }

    /**
     * Edges of a portion with the index of their prepared edge, without the leading and trailing transits (the links
     * between portions are computed again). [overlap]: a portion starting mid-edge (interrupted point) starts again
     * [MissionProgress.OVERLAP_M] before it.
     */
    fun spanEdges(edges: List<SimEdge>, span: MissionSpan, overlap: Boolean = true): List<Pair<Int, SimEdge>> {
        val out = mutableListOf<Pair<Int, SimEdge>>()
        val last = minOf(span.to.edge, edges.size - 1)
        for (i in span.from.edge..last) {
            if (i == span.to.edge && span.to.point == null) break
            val e = edges[i]
            var start = if (i == span.from.edge) offset(edges, span.from) else 0.0
            if (i == span.from.edge && span.from.point != null && overlap && e.cutting) start = maxOf(0.0, start - MissionProgress.OVERLAP_M)
            val end = if (i == span.to.edge) offset(edges, span.to) else e.meters
            if (end - start < 0.01) continue
            out += i to e.copy(a = at(e, start), b = at(e, end))
        }
        val first = out.indexOfFirst { it.second.cutting }
        if (first < 0) return emptyList()
        val lastCutting = out.indexOfLast { it.second.cutting }
        return out.subList(first, lastCutting + 1).toList()
    }

    /** Checkpoint for the portions [spans] (first one in progress), progress counted from what remains. */
    fun withSpans(edges: List<SimEdge>, checkpoint: MissionCheckpoint, spans: List<MissionSpan>, skippedMeters: Double = checkpoint.skippedMeters): MissionCheckpoint {
        val kept = spans.filter { cutting(edges, it) >= EMPTY_M }
        if (kept.isEmpty()) return checkpoint.copy(edge = edges.size, point = null, activeEnd = null, deferred = emptyList(), activeBlocked = null,
            doneMeters = checkpoint.totalMeters, skippedMeters = skippedMeters)
        val head = kept.first()
        val end = head.to.takeUnless { it.edge >= edges.size }
        val next = checkpoint.copy(edge = head.from.edge, point = head.from.point, activeEnd = end, deferred = kept.drop(1),
            activeBlocked = head.blockedAt, skippedMeters = skippedMeters)
        val remaining = kept.sumOf { cutting(edges, it) }
        return next.copy(doneMeters = maxOf(0.0, MissionProgress.totalCuttingMeters(edges) - remaining))
    }

    /** Index of the portion containing [pos] (start included, end excluded), or -1. */
    fun spanIndex(edges: List<SimEdge>, spans: List<MissionSpan>, pos: PlanPos): Int =
        spans.indexOfFirst { s -> compare(edges, pos, s.from) >= -1e-6 && compare(edges, pos, s.to) < -1e-6 }

    /**
     * Remainder rotated at [entry] (inside portion [k]): from [entry] to the end, then what came before it. [blockedAt]
     * marks the moved part as deferred by a blockage.
     */
    fun rotate(spans: List<MissionSpan>, k: Int, entry: PlanPos, blockedAt: Point? = null): List<MissionSpan> {
        val sk = spans[k]
        val head = MissionSpan(entry, sk.to, sk.blockedAt)
        val moved = spans.take(k).map { if (blockedAt != null) it.copy(blockedAt = blockedAt) else it } +
            MissionSpan(sk.from, entry, blockedAt ?: sk.blockedAt)
        return listOf(head) + spans.drop(k + 1) + moved
    }

    /**
     * Progress measured at [position] on prepared edge [edge]. Monotonic inside the portion in progress; reaching a
     * deferred portion makes it the portion in progress (what was left of the previous one, beyond
     * [MissionProgress.OVERLAP_M], stays deferred).
     */
    fun advance(previous: MissionCheckpoint, edges: List<SimEdge>, edge: Int, position: Point, now: Long): MissionCheckpoint? {
        if (previous.linear) return MissionProgress.advance(previous, edges, edge, position, now)
        require(edge in edges.indices)
        val pos = PlanPos(edge, MissionProgress.project(edges[edge], position))
        val spans = spans(previous)
        val active = spans.first()
        val behind = compare(edges, pos, active.from)
        if (compare(edges, pos, active.to) < -1e-6) {
            if (behind > 0.01) return withSpans(edges, previous, listOf(active.copy(from = pos)) + spans.drop(1)).copy(updatedAt = now)
            // Overlap before the resume point: not a move back to another portion.
            if (behind > -(MissionProgress.OVERLAP_M + 0.05)) return null
        }
        val k = spanIndex(edges, spans, pos).takeIf { it > 0 } ?: return null
        val left = active.takeIf { cutting(edges, it) > MissionProgress.OVERLAP_M + 0.2 }
        val next = listOf(spans[k].copy(from = pos)) + spans.subList(1, k) + listOfNotNull(left) + spans.drop(k + 1)
        return withSpans(edges, previous, next).copy(updatedAt = now)
    }

    // ───── After a recharge ─────

    /**
     * Total transit of the remainder [spans] from [start] (heading [startHeading]) back to [approach] (heading
     * [approachHeading]): links between portions computed by [link] (null: impossible) and transits kept inside them.
     * [straight]: lower bound with straight links.
     */
    fun transit(edges: List<SimEdge>, spans: List<MissionSpan>, start: Point, startHeading: Double?, approach: Point, approachHeading: Double?,
                link: (Point, Double?, Point, Double?) -> Double?, straight: Boolean = false): Double {
        val pieces = spans.map { spanEdges(edges, it).map { p -> p.second } }.filter { it.isNotEmpty() }
        var meters = pieces.sumOf { piece -> piece.filterNot { it.cutting }.sumOf { it.meters } }
        var from = start; var heading = startHeading
        for (piece in pieces) {
            val first = piece.first()
            meters += if (straight) from.distance(first.a) else link(from, heading, first.a, headingOf(first)) ?: return Double.POSITIVE_INFINITY
            from = piece.last().b; heading = headingOf(piece.last())
        }
        meters += if (straight) from.distance(approach) else link(from, heading, approach, approachHeading) ?: return Double.POSITIVE_INFINITY
        return meters
    }

    private fun headingOf(e: SimEdge): Double? =
        if (e.meters < 1e-6) null else Math.toDegrees(kotlin.math.atan2(e.b.y - e.a.y, e.b.x - e.a.x))

    /** Possible entries of a reordered remainder: block starts (cutting edge after a transit) and deferred portion starts. */
    fun entries(edges: List<SimEdge>, spans: List<MissionSpan>): List<Pair<Int, PlanPos>> {
        val out = mutableListOf<Pair<Int, PlanPos>>()
        spans.forEachIndexed { k, s ->
            if (k > 0) out += k to s.from
            val last = minOf(s.to.edge, edges.size) - 1
            for (i in (s.from.edge + 1)..last) if (edges[i].cutting && !edges[i - 1].cutting) out += k to PlanPos(i)
        }
        return out
    }

    /** Link length on the navigation [routes] (null: no link), for [afterRecharge]. */
    fun linkMeters(routes: TrajectoryPlanner): (Point, Double?, Point, Double?) -> Double? = { a, ha, b, hb ->
        runCatching { routes.between(a, b, ha, hb).zipWithNext().sumOf { (p, q) -> p.distance(q) } }.getOrNull()
    }

    /** Plans with successive passes of different heights keep their order (a lower pass never before a higher one). */
    fun singleHeight(edges: List<SimEdge>): Boolean = edges.filter { it.cutting }.mapNotNull { it.heightMm }.distinct().size <= 1

    /**
     * Resume after a recharge from [start] (in front of the base, heading [startHeading]): order of the remainder that
     * minimises the total transit, or null if the usual resume at the interrupted point is as good (gain below
     * [MIN_GAIN_M]), impossible or not applicable (successive height passes).
     */
    fun afterRecharge(edges: List<SimEdge>, checkpoint: MissionCheckpoint, start: Point, startHeading: Double?, approach: Point, approachHeading: Double?,
                      link: (Point, Double?, Point, Double?) -> Double?): SmartResumeChoice? {
        if (checkpoint.edgeCount != edges.size || !singleHeight(edges) || mowingDone(edges, checkpoint)) return null
        val spans = spans(checkpoint).filter { cutting(edges, it) >= EMPTY_M }
        if (spans.isEmpty()) return null
        val cache = HashMap<List<Double>, Double?>()
        val cached: (Point, Double?, Point, Double?) -> Double? = { a, ha, b, hb ->
            cache.getOrPut(listOf(a.x, a.y, ha ?: Double.NaN, b.x, b.y, hb ?: Double.NaN)) { if (a.distance(b) < 0.02) 0.0 else link(a, ha, b, hb) }
        }
        val default = transit(edges, spans, start, startHeading, approach, approachHeading, cached)
        val candidates = entries(edges, spans).map { (k, entry) -> rotate(spans, k, entry) }
            .map { it to transit(edges, it, start, startHeading, approach, approachHeading, cached, straight = true) }
            .filter { it.second < default - MIN_GAIN_M }.sortedBy { it.second }
        var best: Pair<List<MissionSpan>, Double>? = null
        for ((order, bound) in candidates.take(MAX_EVALUATIONS)) {
            if (bound >= (best?.second ?: (default - MIN_GAIN_M))) break
            val meters = transit(edges, order, start, startHeading, approach, approachHeading, cached)
            if (meters < (best?.second ?: (default - MIN_GAIN_M))) best = order to meters
        }
        val (order, meters) = best ?: return null
        val next = withSpans(edges, checkpoint, order)
        val entry = pointOf(edges, order.first().from) ?: return null
        return SmartResumeChoice(next, entry, default, meters)
    }

    // ───── After a blockage ─────

    /**
     * First position of the remainder, after the progress, at least [CLEARANCE_M] from [blocked] and [MIN_SKIP_M] of
     * stripes further on: (portion index, position).
     */
    fun clearEntry(edges: List<SimEdge>, spans: List<MissionSpan>, blocked: Point): Pair<Int, PlanPos>? {
        var along = 0.0
        spans.forEachIndexed { k, s ->
            for ((i, e) in spanEdges(edges, s, overlap = false)) {
                if (!e.cutting) continue
                val base = edges[i].a.distance(e.a)
                var d = 0.0
                while (d < e.meters - EMPTY_M) {
                    if (along + d >= MIN_SKIP_M && at(e, d).distance(blocked) >= CLEARANCE_M)
                        return k to PlanPos(i, at(edges[i], base + d))
                    d += STEP_M
                }
                along += e.meters
            }
        }
        return null
    }

    /**
     * Blockage at [blocked] while mowing the portion in progress: the segment from the progress to [clearEntry] is
     * deferred to the end of the mowing ([MissionSpan.blockedAt]); when the portion in progress is already such a retry
     * blocked again at the same place, that segment is given up instead (the whole retried portion if no point of it
     * is far enough; the result may then have nothing left to mow). Null: no remainder far enough from the blockage
     * (the stop is kept).
     */
    fun deferBlocked(edges: List<SimEdge>, checkpoint: MissionCheckpoint, blocked: Point): BlockageDeferral? {
        require(blocked.x.isFinite() && blocked.y.isFinite()) { tr("Invalid blockage position", "Position de blocage invalide") }
        if (checkpoint.edgeCount != edges.size) return null
        val spans = spans(checkpoint)
        if (checkpoint.activeBlocked?.let { it.distance(blocked) < SAME_SPOT_M } == true) {
            // Retry blocked again: the rest of the retried portion up to the clear point is given up (all of it if none).
            val active = spans.first()
            val clear = clearEntry(edges, listOf(active), blocked)?.second
            val head = clear?.let { listOf(MissionSpan(it, active.to)) }.orEmpty()
            val given = clear?.let { cutting(edges, MissionSpan(active.from, it)) } ?: cutting(edges, active)
            val next = withSpans(edges, checkpoint, head + spans.drop(1), skippedMeters = checkpoint.skippedMeters + given)
            val target = (clear ?: spans.getOrNull(1)?.from)?.let { pointOf(edges, it) } ?: blocked
            return BlockageDeferral(next, target, 0.0, given)
        }
        val (k, entry) = clearEntry(edges, spans, blocked) ?: return null
        val target = pointOf(edges, entry) ?: return null
        val movedM = (spans.take(k) + MissionSpan(spans[k].from, entry)).sumOf { cutting(edges, it) }
        return BlockageDeferral(withSpans(edges, checkpoint, rotate(spans, k, entry, blocked)), target, movedM, 0.0)
    }

    /** Deferred portions blocked within [SAME_SPOT_M] of [spot] given up (bypass impossible): new checkpoint and metres given up. */
    fun abandonNear(edges: List<SimEdge>, checkpoint: MissionCheckpoint, spot: Point): Pair<MissionCheckpoint, Double> {
        val spans = spans(checkpoint)
        val (dropped, kept) = spans.drop(1).partition { it.blockedAt?.let { b -> b.distance(spot) < SAME_SPOT_M } == true }
        if (dropped.isEmpty()) return checkpoint to 0.0
        val meters = dropped.sumOf { cutting(edges, it) }
        return withSpans(edges, checkpoint, listOf(spans.first()) + kept, checkpoint.skippedMeters + meters) to meters
    }

    /** Blockage stops from the tracking (Fold or gateway), in both languages: the wheels turn but the robot does not move. */
    fun blockage(reason: String?): Boolean {
        val text = reason ?: return false
        return BLOCKAGE_PREFIXES.any { text.startsWith(it) }
    }
    private val BLOCKAGE_PREFIXES = listOf("Presumed blockage", "Blocage présumé", "No progress for", "Absence de progression")
}
