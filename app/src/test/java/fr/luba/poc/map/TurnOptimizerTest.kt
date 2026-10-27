package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class TurnOptimizerTest {
    private val factory = GeometryFactory()
    private val open = TurnOptimizer.TurnContext(0.4, 0.3, drivable = { true })

    /** Pass A along +x ending at (10, 0); pass B back along −x starting at (10 + [stagger], [lateral]). */
    private fun candidates(lateral: Double, context: TurnOptimizer.TurnContext = open, stagger: Double = 0.0) =
        TurnOptimizer.candidates(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0 + stagger, lateral), Point(0.0, lateral), context)

    private fun List<TurnPlan>.of(kind: TurnKind) = single { it.kind == kind }

    /** Physical area ending [reserve] beyond the pass ends (x = 10), wide sideways. */
    private fun bounded(reserve: Double): TurnOptimizer.TurnContext {
        val area = factory.createPolygon(arrayOf(Coordinate(-5.0, -5.0), Coordinate(10.0 + reserve, -5.0), Coordinate(10.0 + reserve, 6.0),
            Coordinate(-5.0, 6.0), Coordinate(-5.0, -5.0)))
        val envelope = TrajectoryEnvelope(area)
        val roomy = TrajectoryEnvelope(area.buffer(-TurnOptimizer.RISK_MARGIN_M))
        return TurnOptimizer.TurnContext(0.4, 0.3, drivable = { envelope.pathCollision(it) == null }, roomy = { roomy.pathCollision(it) == null })
    }

    @Test fun `neighbouring passes keep the pivots, the U is too tight to be followed and reversing is not offered`() {
        val all = candidates(0.4)
        val pivots = all.of(TurnKind.PIVOTS)
        assertTrue(pivots.possible)
        assertEquals(180.0, pivots.pivotDegrees, 1e-6)
        assertEquals(listOf(Point(10.0, 0.0), Point(10.0, 0.4)), pivots.points)
        assertFalse(all.of(TurnKind.U_ARC).possible)
        assertFalse(all.of(TurnKind.MULTI_POINT).possible)
        // Open ground: the rounded loop fits but is longer and turns almost twice as much.
        val loop = all.of(TurnKind.LOOP)
        assertTrue(loop.refusal, loop.possible)
        assertEquals(0.0, loop.pivotDegrees, 1e-6)
        assertTrue(loop.durationS > pivots.durationS)
        assertEquals(TurnKind.PIVOTS, TurnOptimizer.best(all, TurnStyle.FAST)?.kind)
        assertEquals(TurnKind.PIVOTS, TurnOptimizer.best(all, TurnStyle.LAWN)?.kind)
    }

    @Test fun `passes two follow radii apart get a rolling U-turn when the lawn is spared`() {
        val all = candidates(0.7)
        val u = all.of(TurnKind.U_ARC)
        assertTrue(u.refusal, u.possible)
        assertEquals(0.0, u.pivotDegrees, 1e-6)
        assertEquals(0.0, u.trimA, 1e-9)
        // Half circle of radius 0.35 m: every vertex turns 4°, no stop for the tracking.
        assertEquals(46, u.points.size)
        u.points.forEach { assertEquals(0.35, it.distance(Point(10.0, 0.35)), 1e-9) }
        // Wider passes: two quarter circles and a straight line, the tip still 0.35 m beyond the pass end.
        val wide = candidates(1.0).of(TurnKind.U_ARC)
        assertTrue(wide.refusal, wide.possible)
        assertEquals(10.35, wide.points.maxOf { it.x }, 1e-9)
        val pivots = all.of(TurnKind.PIVOTS)
        assertTrue(u.wear < pivots.wear / 2)
        // Curve speed of the tracking: about as long as the two pivots and their 0.7 m link (within 15 %).
        assertTrue(kotlin.math.abs(u.durationS - pivots.durationS) < pivots.durationS * 0.15)
        assertEquals(TurnKind.U_ARC, TurnOptimizer.best(all, TurnStyle.LAWN)?.kind)
        // Fast: duration first, the lawn only breaks near ties (here the U wins by its 112° less in place).
        val fast = TurnOptimizer.best(all, TurnStyle.FAST)!!
        assertEquals(all.filter { it.possible }.minOf { it.score(TurnStyle.FAST) }, fast.score(TurnStyle.FAST), 1e-9)
        assertTrue(fast.durationS <= pivots.durationS * 1.15)
    }

    @Test fun `at the pivot reserve of the boundary the U starts earlier and its arc sweeps the shortened pass ends`() {
        val reserve = RobotEnvelope.LUBA2.turnClearanceM + 0.02
        val all = candidates(0.7, bounded(reserve))
        assertTrue(all.of(TurnKind.PIVOTS).possible)
        val u = all.of(TurnKind.U_ARC)
        assertTrue(u.refusal, u.possible)
        // The arc reaches 0.35 m beyond its start plus half a chassis: the passes give up about 20 cm.
        assertTrue("trim ${u.trimA}", u.trimA in 0.15..0.41)
        assertEquals(u.trimA, u.trimB, 1e-9)
        assertTrue("tip ${u.points.maxOf { it.x }}", u.points.all { it.x <= 10.0 + 0.2 })
        // Shortened pass parts are not driven twice: no slower than the pivots there.
        assertTrue("${u.durationS} vs ${all.of(TurnKind.PIVOTS).durationS}", u.durationS < all.of(TurnKind.PIVOTS).durationS * 1.1)
        // Too little room even with the longest trim for the loop between neighbouring passes.
        assertFalse(candidates(0.4, bounded(reserve)).of(TurnKind.LOOP).let { "${it.trimA} ${it.refusal}" }, candidates(0.4, bounded(reserve)).of(TurnKind.LOOP).possible)
    }

    @Test fun `staggered pass ends tilt the straight line of the U`() {
        // Pass B ends 0.3 m before pass A: the straight line of the U tilts, nothing is shortened in open ground.
        val u = candidates(0.8, stagger = -0.3).of(TurnKind.U_ARC)
        assertTrue(u.refusal, u.possible)
        assertEquals(0.0, u.trimA, 1e-9)
        assertEquals(Point(10.0, 0.0), u.points.first())
        assertEquals(0.0, Point(9.7, 0.8).distance(u.points.last()), 1e-9)
        assertEquals(0.0, u.pivotDegrees, 1e-6)
        // Passes closer than two follow radii: no U, whatever the stagger.
        val close = candidates(0.6, stagger = -0.3).of(TurnKind.U_ARC)
        assertFalse(close.possible)
        assertNotNull(close.refusal)
    }

    @Test fun `a tight manoeuvre loses against a roomier one`() {
        val tight = TurnPlan(TurnKind.U_ARC, emptyList(), durationS = 10.0, wear = 50.0, tight = true)
        val pivots = TurnPlan(TurnKind.PIVOTS, emptyList(), durationS = 12.0, wear = 180.0)
        assertTrue(tight.score(TurnStyle.LAWN) > pivots.score(TurnStyle.LAWN) - 1e-9 || tight.score(TurnStyle.FAST) > pivots.score(TurnStyle.FAST))
        assertEquals(TurnKind.PIVOTS, TurnOptimizer.best(listOf(tight, pivots), TurnStyle.FAST)?.kind)
    }
}
