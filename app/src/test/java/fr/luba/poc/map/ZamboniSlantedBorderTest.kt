package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test

/**
 * Zamboni U-turns along a slanted border ([TurnOptimizer.TurnContext.alongBorder]): on a recorded boundary, a rolling
 * U-turn may follow the border into the band of its loop, so that the pass ends staggered by a slanted side roll
 * instead of pivoting, while the chassis keeps the set margin of the loop from the recorded boundary.
 */
class ZamboniSlantedBorderTest {
    private val robot = RobotEnvelope.LUBA2
    private val spacing = 0.25
    private val outerOffset = CoveragePlanner.RECORDED_OUTER_OFFSET_M

    /** 14 × 8 m, the right side slanted 40° from the normal of the horizontal passes (0.63 m of stagger every 3 rows). */
    private val trapezoid = listOf(Point(0.0, 0.0), Point(14.0, 0.0), Point(14.0 + 8.0 * Math.tan(Math.toRadians(40.0)), 8.0), Point(0.0, 8.0))
    private val recorded = org.locationtech.jts.geom.GeometryFactory().createPolygon((trapezoid + trapezoid.first())
        .map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray())

    private fun plan(mode: CoverageMode, blocks: ZamboniBlocks = ZamboniBlocks.LARGE, obliqueTurns: Boolean = true) =
        CoveragePlanner.plan(trapezoid, emptyList(), spacing, 0.6, mode, true, recordedBoundary = true,
            sweepOptions = SweepOptions(listOf(0)), continuous = true, cutWidthM = 0.4,
            zamboniOptions = ZamboniOptions(obliqueTurns, blocks))

    @Test fun uTurnsRollAlongTheSlantedSide() {
        val m = TurnOrder.step(spacing, TurnOptimizer.FOLLOW_RADIUS_M)
        val zigzag = plan(CoverageMode.LINES)
        val zamboni = plan(CoverageMode.ZAMBONI)
        val without = plan(CoverageMode.ZAMBONI, obliqueTurns = false)
        fun rolling(p: Preview) = PassOrderMetrics.order(p, 0, spacing, 2 * m + 2)
        val order = rolling(zamboni); val square = rolling(without)
        println("ZAMBONI slanted side 40°: ${order.rolling} of ${order.turns} U-turns without pivot " +
            "(${square.rolling} of ${square.turns} inside the area of the passes only), runs ${zigzag.runs.size} → ${zamboni.runs.size}")
        // Square side: every U-turn rolls; slanted side: they roll too along the border (15 of 27 when they had to stay in
        // the area of the passes, 2,160° pivoted).
        assertTrue("${order.rolling} of ${order.turns}", order.rolling >= 0.95 * order.turns)
        assertTrue("${order.rolling} against ${square.rolling}", order.rolling > square.rolling)
        // Same mowed surface as the zigzag within 0.2 point: the U-turns along the border sweep the pass ends they shorten.
        fun mowed(p: Preview) = PassOrderMetrics.swept(p.runs + p.borders, trapezoid, 0.4).area
        assertEquals(mowed(zigzag), mowed(zamboni), 0.002 * recorded.area)
    }

    @Test fun uTurnsAlongTheBorderKeepTheChassisInsideTheMargin() {
        val zamboni = plan(CoverageMode.ZAMBONI, ZamboniBlocks.ALL)
        // Measured from the recorded boundary, not from the physical area, which takes a tolerance around it.
        val band = TrajectoryEnvelope(recorded.buffer(-outerOffset, 16), robot)
        // Rolling parts of the runs (no vertex turning more than a pivot): the chassis at the margin of the loop or further.
        val rolled = zamboni.runs.flatMap { run -> run.windowed(3).filter { (a, b, c) ->
            val turn = Math.toDegrees(Math.abs(Math.atan2((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x),
                (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y))))
            turn in 1e-3..fr.luba.poc.simulation.PathFollower.CORNER_DEGREES
        } }
        assertTrue("rolling U-turns", rolled.isNotEmpty())
        rolled.forEach { assertNull(band.pathCollision(it)) }
    }
}
