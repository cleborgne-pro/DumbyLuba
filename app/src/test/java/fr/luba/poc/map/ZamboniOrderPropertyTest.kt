package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * QA property test of [ZamboniOrder] with random U-turn costs, random undrivable U-turns (breaks), staggered ends and
 * cells long enough to trigger the beam: whenever an order is returned it is a permutation of the ranks, directions
 * alternate, the window rule holds at every step, every move is one of the allowed ones (m, m+1, m+2, 1 ranks), its cost
 * is the sum of the transitions it drives, and the result is deterministic.
 */
class ZamboniOrderPropertyTest {
    private val radius = TurnOptimizer.FOLLOW_RADIUS_M

    private fun ladder(n: Int, spacing: Double, stagger: (Int) -> Double): List<List<Point>> = (0 until n).map { k ->
        val y = k * spacing; val a = Point(stagger(k), y); val b = Point(6.0 - stagger(k), y)
        if (k % 2 == 0) listOf(a, b) else listOf(b, a)
    }

    @Test(timeout = 600_000) fun randomCostsAndBreaksKeepEveryInvariant() {
        var returned = 0; var beamCells = 0
        for (seed in 0 until 120) {
            val rnd = Random(seed)
            val spacing = listOf(0.07, 0.08, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35)[seed % 8]
            val m = TurnOrder.step(spacing, radius); val w = ZamboniOrder.window(spacing, radius)
            val n = 2 * m + 2 + rnd.nextInt(0, if (seed % 10 == 0) 300 else 60)
            val staggers = DoubleArray(n) { rnd.nextDouble(0.0, 0.8) }
            val passes = ladder(n, spacing) { staggers[it] }
            // Random but fixed cost per (from end, to start): same input, same cost (the planner memoises).
            val table = HashMap<Pair<Point, Point>, Double?>()
            val breakCost = if (seed % 3 == 0) Double.POSITIVE_INFINITY else 60.0
            val transition = { _: Point, aEnd: Point, bStart: Point, _: Point ->
                table.getOrPut(aEnd to bStart) {
                    val gap = (abs(bStart.y - aEnd.y) / spacing).roundToInt()
                    val r = Random(seed * 1_000_003 + aEnd.hashCode() * 31 + bStart.hashCode())
                    if (gap < m && gap != 1) null else if (r.nextDouble() < 0.15) null else 1.0 + r.nextDouble() * 5 + 0.1 * gap
                }
            }
            val order = ZamboniOrder.order(passes, spacing, radius, { it }, { _, p -> p }, transition, breakCost)
            val again = ZamboniOrder.order(passes, spacing, radius, { it }, { _, p -> p }, transition, breakCost)
            assertEquals("seed $seed: deterministic", order, again)
            if (m < 2 || w < m + ZamboniOrder.STEPS_ABOVE_M) { assertNull("seed $seed: spacing $spacing", order); continue }
            if (order == null) { assertTrue("seed $seed: null only without breaks", breakCost.isInfinite()); continue }
            returned++
            if (n > 120) beamCells++
            val ranks = order.map { p -> passes.indexOfFirst { it.toSet() == p.toSet() } }
            assertEquals("seed $seed: permutation", (0 until n).toList(), ranks.sorted())
            val done = BooleanArray(n)
            ranks.forEachIndexed { t, q ->
                var f = 0; while (f < n && done[f]) f++
                assertTrue("seed $seed step $t: rank $q beyond first undone $f + W $w", q <= f + w)
                if (t > 0) {
                    val d = abs(q - ranks[t - 1])
                    assertTrue("seed $seed step $t: move of $d ranks (m=$m)", d == 1 || d in m..m + ZamboniOrder.STEPS_ABOVE_M)
                    val prev = order[t - 1]; val cur = order[t]
                    assertTrue("seed $seed step $t: direction", (cur[1].x - cur[0].x) * (prev[1].x - prev[0].x) < 0)
                    if (breakCost.isInfinite()) assertNotNull("seed $seed step $t: undrivable move without breaks",
                        transition(prev[0], prev[1], cur[0], cur[1]))
                }
                done[q] = true
            }
        }
        println("QA2 ZamboniOrder property: $returned orders checked, $beamCells long cells")
        assertTrue(returned > 40)
    }
}
