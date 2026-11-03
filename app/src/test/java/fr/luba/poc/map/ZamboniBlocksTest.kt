package fr.luba.poc.map

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/**
 * Zamboni blocks setting ([ZamboniBlocks]): the fewest passes of a block ordered in Zamboni, on synthetic ladders with
 * the synthetic U-turn cost of [ZamboniOrderTest], then through [CoveragePlanner.plan] on a zone whose no-go zone cuts
 * the sweep into blocks too small for the default.
 */
class ZamboniBlocksTest {
    private val radius = TurnOptimizer.FOLLOW_RADIUS_M

    private fun ladder(n: Int, spacing: Double): List<List<Point>> = (0 until n).map { k ->
        val a = Point(0.0, k * spacing); val b = Point(6.0, k * spacing)
        if (k % 2 == 0) listOf(a, b) else listOf(b, a)
    }

    private fun synthetic(spacing: Double, m: Int) = { _: Point, aEnd: Point, bStart: Point, _: Point ->
        val gap = (abs(bStart.y - aEnd.y) / spacing).roundToInt()
        when { gap >= m -> 1 + 0.1 * gap; gap == 1 -> 3.0; else -> null }
    }

    @Test fun fewestPassesPerSetting() {
        // 25 cm: rolling moves of 3 ranks; 35 cm: 2 ranks.
        assertEquals(listOf(8, 6, 4), ZamboniBlocks.entries.map { it.minPasses(TurnOrder.step(0.25, radius)) })
        assertEquals(listOf(6, 5, 3), ZamboniBlocks.entries.map { it.minPasses(TurnOrder.step(0.35, radius)) })
        assertEquals(ZamboniBlocks.LARGE, ZamboniOptions().blocks)
    }

    @Test fun smallBlocksOrderedOnlyWhenAllowed() {
        val spacing = 0.25
        val m = TurnOrder.step(spacing, radius)
        for (n in 4..8) {
            val passes = ladder(n, spacing)
            for (blocks in ZamboniBlocks.entries) {
                val order = ZamboniOrder.order(passes, spacing, radius, { it }, { _, p -> p }, synthetic(spacing, m), blocks = blocks)
                if (n < blocks.minPasses(m)) { assertNull("$n passes, $blocks", order); continue }
                assertNotNull("$n passes, $blocks", order); order!!
                val ranks = order.map { (it[0].y / spacing).roundToInt() }
                assertEquals("$n passes, $blocks: every pass once", (0 until n).toList(), ranks.sorted())
                // At least one rolling move: cheaper than the zigzag (every move one rank, 3 each).
                assertTrue("$n passes, $blocks: rolling move in $ranks", ranks.zipWithNext().any { (a, b) -> abs(b - a) >= m })
            }
        }
    }

    @Test fun plannerOrdersTheBlocksBesideANoGoZone() {
        // Horizontal passes (angle 0); the thin no-go zone splits the rows beside it into two blocks of about six passes.
        val zone = listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 6.0), Point(0.0, 6.0))
        val hole = listOf(Point(5.0, 2.9), Point(7.0, 2.9), Point(7.0, 3.1), Point(5.0, 3.1))
        fun plan(blocks: ZamboniBlocks) = CoveragePlanner.plan(zone, listOf(hole), 0.25, 0.6, CoverageMode.ZAMBONI, true,
            basePoint = Point(1.0, 1.0), sweepOptions = SweepOptions(listOf(0)), continuous = true, turnStyle = TurnStyle.LAWN,
            cutWidthM = 0.4, zamboniOptions = ZamboniOptions(blocks = blocks))
        val large = plan(ZamboniBlocks.LARGE); val all = plan(ZamboniBlocks.ALL)
        // The same passes, more of them in Zamboni order.
        assertEquals(large.passes.map { it.toSet() }.toSet(), all.passes.map { it.toSet() }.toSet())
        val reordered = large.zoneReorderedCells.single() to all.zoneReorderedCells.single()
        assertTrue("reordered blocks $reordered", reordered.second > reordered.first)
        println("ZAMBONI blocks beside a no-go zone: ${reordered.first} -> ${reordered.second} blocks in Zamboni order, " +
            "${large.runs.size} -> ${all.runs.size} runs")
    }
}
