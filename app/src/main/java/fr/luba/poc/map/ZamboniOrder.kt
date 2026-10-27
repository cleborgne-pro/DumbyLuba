package fr.luba.poc.map

import kotlin.math.min

/**
 * Zamboni order of the passes of one regular cell ([CoverageMode.ZAMBONI]): the passes, ranks `0 .. n-1` across the
 * cell, are driven so that two passes in a row are at least [TurnOrder.step] ranks apart (a U-turn of the follow radius
 * fits, driven without stopping) while the mowed front keeps advancing: a pass is allowed only once every pass more than
 * [window] ranks behind it is done. The unmowed part is then always one block with a ragged edge at most [window] ranks
 * wide, so a stop at any moment leaves no isolated stripe (unlike [TurnOrder.skip], which leaves the cell striped until
 * its last sweep).
 *
 * Exact dynamic programme over the states (first undone rank, done passes of the window, last pass), one layer per pass
 * driven, cheapest total [order] `transition` cost; a layer larger than [MAX_STATES_PER_LAYER] keeps its cheapest states
 * (deterministic beam). Moves from the last pass: ±m, ±(m+1), ±(m+2) ranks (rolling U-turns, m = [TurnOrder.step]) and
 * ±1 (pivot U-turn, last resort). A move whose U-turn cannot be driven may still be taken as a break (the run stops there
 * and the next pass starts a new one, reached by a transit) at `breakCost`, as the zigzag does where a U-turn does not fit.
 * Pure and deterministic.
 */
object ZamboniOrder {
    /** Widest window, in ranks. */
    const val MAX_WINDOW = 12
    /** Beam width of the dynamic programme. */
    const val MAX_STATES_PER_LAYER = 4096
    /** Rolling moves tried: m to m + [STEPS_ABOVE_M] ranks. */
    const val STEPS_ABOVE_M = 2

    /** Window in ranks for this spacing: twice the alternation step `k = m + 1`, at most [MAX_WINDOW]. */
    /** Measurement only (tests): number of passes and duration in nanoseconds of every search, from any thread. */
    @Volatile internal var observer: ((passes: Int, nanos: Long) -> Unit)? = null

    fun window(spacing: Double, radius: Double): Int = min(2 * (TurnOrder.step(spacing, radius) + 1), MAX_WINDOW)

    /**
     * Visit order found by [search]: rank of each pass driven, whether rank 0 is driven against the direction of the
     * first pass of the cell, total cost. Passes then alternate direction.
     */
    internal class Ranks(val ranks: IntArray, val firstReversed: Boolean, val cost: Double)

    /**
     * Passes of one cell in front-advancing order, directions alternating; null when the cell is not a regular ladder
     * ([TurnOrder.ladder]), has fewer than 2 m + 2 passes, needs a window larger than [MAX_WINDOW] (spacing too small for
     * the rolling moves to fit in it), or no complete sequence exists. [transition]: cost of the U-turn from the pass
     * ([aFrom] → [aEnd]) to the pass ([bStart] → [bTo]), null when it cannot be driven or is not verified; such a move is
     * then a break costing [breakCost] (infinite: never). [checkpoint] is called at every layer (cancellation).
     */
    fun <T> order(group: List<T>, spacing: Double, radius: Double, path: (T) -> List<Point>, with: (T, List<Point>) -> T,
                  transition: (aFrom: Point, aEnd: Point, bStart: Point, bTo: Point) -> Double?,
                  breakCost: Double = Double.POSITIVE_INFINITY, checkpoint: () -> Unit = {}): List<T>? {
        val base = TurnOrder.ladder(group.map(path), spacing) ?: return null
        val start = System.nanoTime()
        val found = search(base, spacing, radius, transition, breakCost, checkpoint)
        observer?.invoke(base.size, System.nanoTime() - start)
        if (found == null) return null
        return found.ranks.mapIndexed { t, q ->
            with(group[q], if (found.firstReversed xor (t % 2 == 1)) base[q].reversed() else base[q])
        }
    }

    /** Cheapest complete order of the ladder [base] (every pass turned the way of the first one), both first directions tried. */
    internal fun search(base: List<List<Point>>, spacing: Double, radius: Double,
                        transition: (Point, Point, Point, Point) -> Double?, breakCost: Double = Double.POSITIVE_INFINITY,
                        checkpoint: () -> Unit = {}): Ranks? {
        val n = base.size
        val m = TurnOrder.step(spacing, radius)
        val w = window(spacing, radius)
        if (m < 2 || w < m + STEPS_ABOVE_M || n < 2 * m + 2) return null
        val moves = IntArray(STEPS_ABOVE_M + 1) { m + it }.let { up -> up + up.map { -it } + intArrayOf(1, -1) }
        // Cost of one move by (from rank, its direction, to rank): its U-turn, else a break; NaN: neither.
        val broken = if (breakCost.isFinite()) breakCost else Double.NaN
        val costs = HashMap<Long, Double>()
        fun cost(a: Int, reversedA: Boolean, b: Int): Double = costs.getOrPut((a.toLong() * n + b) * 2 + if (reversedA) 1 else 0) {
            val pa = if (reversedA) base[a].reversed() else base[a]
            val pb = if (reversedA) base[b] else base[b].reversed()
            transition(pa[0], pa[1], pb[0], pb[1]) ?: broken
        }
        var best: Ranks? = null
        for (firstReversed in listOf(false, true)) {
            val found = layers(n, w, moves, firstReversed, ::cost, checkpoint) ?: continue
            if (best == null || found.cost < best.cost - 1e-9) best = found
        }
        return best
    }

    /** States of one layer: done-pass window and last pass kept for the next layer, last pass and parent for the path back. */
    private class Layer(capacity: Int) {
        var size = 0
        var first = IntArray(capacity); var mask = IntArray(capacity); var last = IntArray(capacity)
        var cost = DoubleArray(capacity); var parent = IntArray(capacity)
        fun add(f: Int, m: Int, l: Int, c: Double, p: Int): Int {
            if (size == first.size) {
                val grown = first.size * 2
                first = first.copyOf(grown); mask = mask.copyOf(grown); last = last.copyOf(grown)
                cost = cost.copyOf(grown); parent = parent.copyOf(grown)
            }
            first[size] = f; mask[size] = m; last[size] = l; cost[size] = c; parent[size] = p
            return size++
        }
    }

    private fun layers(n: Int, w: Int, moves: IntArray, firstReversed: Boolean,
                       cost: (Int, Boolean, Int) -> Double, checkpoint: () -> Unit): Ranks? {
        // Mask bit i: rank f + 1 + i done; f: first undone rank. Rank 0 is driven first.
        var current = Layer(1).apply { add(1, 0, 0, 0.0, -1) }
        val lasts = ArrayList<IntArray>(n); val parents = ArrayList<IntArray>(n)
        lasts.add(intArrayOf(0)); parents.add(intArrayOf(-1))
        for (t in 1 until n) {
            checkpoint()
            // Direction of the pass driven at t - 1: alternating from the first one.
            val lastReversed = firstReversed xor ((t - 1) % 2 == 1)
            val next = Layer(minOf(64, current.size * moves.size))
            val index = HashMap<Long, Int>()
            for (s in 0 until current.size) {
                val f = current.first[s]; val mask = current.mask[s]; val last = current.last[s]
                for (d in moves) {
                    val q = last + d
                    if (q < f || q >= n || q > f + w) continue
                    if (q > f && (mask ushr (q - f - 1)) and 1 == 1) continue
                    val c = cost(last, lastReversed, q)
                    if (c.isNaN()) continue
                    var nf = f; var nm = mask
                    if (q == f) {
                        // Front advances past every pass already done ahead of it.
                        var g = f + 1; var mk = mask
                        while (g < n && mk and 1 == 1) { mk = mk ushr 1; g++ }
                        nf = g; nm = mk ushr 1
                    } else nm = mask or (1 shl (q - f - 1))
                    val total = current.cost[s] + c
                    val key = (nf.toLong() shl 24) or (nm.toLong() shl 6) or (q - nf + w + 1).toLong()
                    val known = index[key]
                    if (known == null) index[key] = next.add(nf, nm, q, total, s)
                    else if (total < next.cost[known] - 1e-12) { next.cost[known] = total; next.parent[known] = s }
                }
            }
            if (next.size == 0) return null
            current = if (next.size <= MAX_STATES_PER_LAYER) next else {
                // Beam: cheapest states, ties broken on (first undone rank, window, last rank).
                val kept = (0 until next.size).sortedWith(compareBy<Int> { next.cost[it] }.thenBy { next.first[it] }
                    .thenBy { next.mask[it] }.thenBy { next.last[it] }).take(MAX_STATES_PER_LAYER)
                Layer(kept.size).also { beam -> kept.forEach { i -> beam.add(next.first[i], next.mask[i], next.last[i], next.cost[i], next.parent[i]) } }
            }
            lasts.add(current.last.copyOf(current.size)); parents.add(current.parent.copyOf(current.size))
        }
        // Every pass done: cheapest final state (ties: first found, deterministic).
        var end = -1
        for (s in 0 until current.size) if (current.first[s] >= n && (end < 0 || current.cost[s] < current.cost[end] - 1e-12)) end = s
        if (end < 0) return null
        val total = current.cost[end]
        val ranks = IntArray(n)
        var s = end
        for (t in n - 1 downTo 0) { ranks[t] = lasts[t][s]; s = parents[t][s] }
        return Ranks(ranks, firstReversed, total)
    }
}
