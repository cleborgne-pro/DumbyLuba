package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [ZamboniOrder] on synthetic ladders with a synthetic U-turn cost (1 + 0.1 × gap for a gap of at least m ranks, 3 for
 * a gap of one rank, not drivable otherwise): every pass once, directions alternating, the window rule at every step,
 * rolling moves almost everywhere, null where the order does not apply, deterministic.
 */
class ZamboniOrderTest {
    private val radius = TurnOptimizer.FOLLOW_RADIUS_M

    /** Zigzag ladder of [n] passes [length] m long, [spacing] apart, like a sweep of a rectangle (directions alternating). */
    private fun ladder(n: Int, spacing: Double, length: Double = 6.0, stagger: (Int) -> Double = { 0.0 }): List<List<Point>> =
        (0 until n).map { k ->
            val y = k * spacing; val a = Point(stagger(k), y); val b = Point(length - stagger(k), y)
            if (k % 2 == 0) listOf(a, b) else listOf(b, a)
        }

    /** Gap in ranks between two passes of [spacing] from their lateral offset. */
    private fun gap(a: Point, b: Point, spacing: Double) = (abs(b.y - a.y) / spacing).roundToInt()

    private fun synthetic(spacing: Double, m: Int, calls: MutableList<Int>? = null) = { _: Point, aEnd: Point, bStart: Point, _: Point ->
        val g = gap(aEnd, bStart, spacing)
        calls?.add(g)
        when { g >= m -> 1 + 0.1 * g; g == 1 -> 3.0; else -> null }
    }

    private fun ranks(order: List<List<Point>>, spacing: Double) = order.map { (it[0].y / spacing).roundToInt() }

    @Test fun everyPassOnceAlternatingAndInsideTheWindow() {
        for (spacing in listOf(0.25, 0.2, 0.35, 0.15, 0.1)) for (n in listOf(20, 21, 37, 64)) {
            val m = TurnOrder.step(spacing, radius)
            val w = ZamboniOrder.window(spacing, radius)
            val passes = ladder(n, spacing)
            if (n < 2 * m + 2) continue
            val order = ZamboniOrder.order(passes, spacing, radius, { it }, { _, p -> p }, synthetic(spacing, m))
            assertNotNull("$spacing m, $n passes", order); order!!
            val r = ranks(order, spacing)
            assertEquals("$spacing m, $n passes: every rank once", (0 until n).toList(), r.sorted())
            // Directions alternate, each pass keeps its own segment.
            order.forEachIndexed { t, p ->
                assertEquals(passes[r[t]].toSet(), p.toSet())
                if (t > 0) assertTrue("$spacing m: direction at $t", (p[1].x - p[0].x) * (order[t - 1][1].x - order[t - 1][0].x) < 0)
            }
            // Window: every pass more than w ranks behind the one driven is done.
            val done = BooleanArray(n)
            var small = 0
            r.forEachIndexed { t, q ->
                for (behind in 0 until q - w) assertTrue("$spacing m, $n passes: rank $behind not done before $q", done[behind])
                done[q] = true
                if (t > 0 && abs(q - r[t - 1]) < m) small++
            }
            // At most 2 closing moves; 3 at 10 cm in a short cell, where the window (capped at 12 ranks) is under 2 k = 16.
            val closing = if (w < 2 * (m + 1) && n < 3 * w) 3 else 2
            assertTrue("$spacing m, $n passes: $small moves under $m ranks in $r", small <= closing)
            println("ZAMBONI order $spacing m (m=$m, W=$w), $n passes: ${r.take(15)} … ${r.takeLast(6)}, $small closing moves")
        }
    }

    /**
     * With this cost the cheapest order at 25 cm (m = 3, W = 8) goes up by three twice and back by four (1.33 per pass)
     * rather than the alternation +4, -3 of the design note (1.35 per pass): the mowed front advances by blocks of seven
     * ranks, every move rolling.
     */
    @Test fun firstRanksAt25CmGoUpByThreeAndBackByFour() {
        val order = ZamboniOrder.order(ladder(30, 0.25), 0.25, radius, { it }, { _, p -> p }, synthetic(0.25, 3))!!
        assertEquals(listOf(0, 3, 6, 2, 5, 1, 4, 7, 10, 13, 9, 12, 8, 11, 14), ranks(order, 0.25).take(15))
        assertEquals(8, ZamboniOrder.window(0.25, radius))
    }

    @Test fun staggeredEndsAreStillALadder() {
        val passes = ladder(24, 0.25) { k -> 0.1 * (k % 5) }
        assertNotNull(ZamboniOrder.order(passes, 0.25, radius, { it }, { _, p -> p }, synthetic(0.25, 3)))
    }

    @Test fun nullWhereTheOrderDoesNotApply() {
        val t = synthetic(0.25, 3)
        // Fewer than 2 m + 2 passes.
        assertNull(ZamboniOrder.order(ladder(7, 0.25), 0.25, radius, { it }, { _, p -> p }, t))
        assertNotNull(ZamboniOrder.order(ladder(8, 0.25), 0.25, radius, { it }, { _, p -> p }, t))
        // Irregular: one pass missing (two spacings between rows), the same direction twice, a polyline.
        val passes = ladder(20, 0.25)
        assertNull(ZamboniOrder.order(passes.filterIndexed { i, _ -> i != 6 }, 0.25, radius, { it }, { _, p -> p }, t))
        assertNull(ZamboniOrder.order(passes.mapIndexed { i, p -> if (i == 3) p.reversed() else p }, 0.25, radius, { it }, { _, p -> p }, t))
        assertNull(ZamboniOrder.order(passes.mapIndexed { i, p -> if (i == 3) listOf(p[0], Point(3.0, p[0].y), p[1]) else p }, 0.25, radius, { it }, { _, p -> p }, t))
        // 5 cm: the rolling moves (14 ranks and more) do not fit in the 12-rank window.
        assertNull(ZamboniOrder.order(ladder(80, 0.05), 0.05, radius, { it }, { _, p -> p }, synthetic(0.05, 14)))
        // No drivable rolling U-turn and no pivot either: no complete order.
        assertNull(ZamboniOrder.order(passes, 0.25, radius, { it }, { _, p -> p }, { _, _, _, _ -> null }))
    }

    @Test fun onlyPivotsGivesTheZigzagBackAndCostsAreAskedOncePerMove() {
        val calls = mutableListOf<Int>()
        val pivotsOnly = { a: Point, aEnd: Point, b: Point, bTo: Point -> synthetic(0.25, 3, calls)(a, aEnd, b, bTo)?.takeIf { gap(aEnd, b, 0.25) == 1 } }
        val n = 30
        val order = ZamboniOrder.order(ladder(n, 0.25), 0.25, radius, { it }, { _, p -> p }, pivotsOnly)!!
        assertEquals((0 until n).toList(), ranks(order, 0.25))
        // Memoised per (rank, direction, rank): at most 8 moves × 2 directions per rank.
        assertTrue("${calls.size} calls", calls.size <= n * 8 * 2)
    }

    @Test fun undrivableUTurnsBecomeBreaksWhenAllowed() {
        val passes = ladder(30, 0.25)
        // Rolling U-turns refused at the far end of every pass (x = 6): pivots there, rolling at the near end.
        val oneEnd = { a: Point, aEnd: Point, b: Point, bTo: Point -> synthetic(0.25, 3)(a, aEnd, b, bTo)?.takeIf { aEnd.x < 3 || gap(aEnd, b, 0.25) == 1 } }
        val order = ZamboniOrder.order(passes, 0.25, radius, { it }, { _, p -> p }, oneEnd, breakCost = 60.0)!!
        assertEquals((0 until 30).toList(), ranks(order, 0.25).sorted())
        // Nothing drivable at all: without breaks no order, with breaks an order made only of breaks.
        assertNull(ZamboniOrder.order(passes, 0.25, radius, { it }, { _, p -> p }, { _, _, _, _ -> null }))
        val broken = ZamboniOrder.order(passes, 0.25, radius, { it }, { _, p -> p }, { _, _, _, _ -> null }, breakCost = 60.0)!!
        assertEquals((0 until 30).toList(), ranks(broken, 0.25).sorted())
    }

    @Test fun deterministic() {
        val passes = ladder(41, 0.2, stagger = { k -> 0.05 * (k % 3) })
        val m = TurnOrder.step(0.2, radius)
        val a = ZamboniOrder.order(passes, 0.2, radius, { it }, { _, p -> p }, synthetic(0.2, m))
        val b = ZamboniOrder.order(passes, 0.2, radius, { it }, { _, p -> p }, synthetic(0.2, m))
        assertEquals(a, b)
    }

    @Test fun checkpointIsCalledAtEveryLayerAndCanCancel() {
        var layers = 0
        ZamboniOrder.order(ladder(30, 0.25), 0.25, radius, { it }, { _, p -> p }, synthetic(0.25, 3), checkpoint = { layers++ })
        assertEquals(2 * 29, layers)   // both first directions, one call per pass after the first
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            ZamboniOrder.order(ladder(30, 0.25), 0.25, radius, { it }, { _, p -> p }, synthetic(0.25, 3), checkpoint = { if (++calls > 5) error("cancelled") })
        }
    }

    @Test fun largeCellStaysUnderTheStateCapAndIsFast() {
        val start = System.nanoTime()
        val order = ZamboniOrder.order(ladder(400, 0.1), 0.1, radius, { it }, { _, p -> p }, synthetic(0.1, TurnOrder.step(0.1, radius)))
        val seconds = (System.nanoTime() - start) / 1e9
        assertNotNull(order)
        println("ZAMBONI order 400 passes at 10 cm (W=${ZamboniOrder.window(0.1, radius)}): %.2f s".format(seconds))
        assertTrue("%.2f s".format(seconds), seconds < 10)
    }

    @Test fun ladderIsTheRegularCheckOfTheLawnFriendlyOrder() {
        val passes = ladder(12, 0.25)
        val base = TurnOrder.ladder(passes, 0.25)!!
        assertTrue(base.all { it[1].x > it[0].x })
        // The one-in-three order is unchanged by the shared helper.
        val skipped = TurnOrder.skip(passes, 0.25, radius, { it }) { _, p -> p }
        assertEquals(listOf(0, 3, 6, 9, 10, 7, 4, 1, 2, 5, 8, 11), ranks(skipped, 0.25))
        assertNull(TurnOrder.ladder(passes.take(1), 0.25))
        assertNull(TurnOrder.ladder(passes.mapIndexed { i, p -> if (i == 5) listOf(p[0], Point(p[0].x, p[0].y + 0.1)) else p }, 0.25))
    }
}
