package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/**
 * 2026-10-14: bumper contacts along the border. The outer loop margin (between the side of the robot and the recorded
 * boundary) also applies to the no-go loops, and passes never get closer to a boundary than its loop.
 */
class NoGoOffsetTest {
    private val factory = GeometryFactory()
    private val robot = RobotEnvelope.LUBA2
    private val zone = listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 10.0), Point(0.0, 10.0))
    private val noGo = listOf(Point(5.0, 4.0), Point(7.0, 4.0), Point(7.0, 6.0), Point(5.0, 6.0))
    private fun polygon(points: List<Point>) = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun line(path: List<Point>) = factory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun toZone(path: List<Point>) = line(path).distance(polygon(zone).exteriorRing)
    private fun toNoGo(path: List<Point>) = line(path).distance(polygon(noGo))

    private fun plan(offset: Double, borders: Boolean) = CoveragePlanner.plan(zone, listOf(noGo), 0.25, 0.6, CoverageMode.LINES,
        borders, recordedBoundary = true, outerOffsetM = offset)

    /** Distance from the center to the boundary for a loop at margin [margin]: half width + margin. */
    private fun loopCenter(margin: Double) = RecordedBoundary.centerInsetM(robot) + margin

    @Test(timeout = 300000) fun `no-go loop follows the outer loop margin`() {
        for (margin in listOf(0.25, 0.5, CoveragePlanner.MAX_OUTER_OFFSET_M)) {
            val preview = plan(margin, true)
            assertEquals("margin $margin m", 1, preview.noGoLoops)
            val min = preview.borders.drop(preview.outerLoops).minOf(::toNoGo)
            assertTrue("margin $margin m: no-go loop at $min m", min >= loopCenter(margin) - 0.01)
            assertTrue("margin $margin m: no-go loop at $min m", min <= loopCenter(margin) + 0.05)
            val outerMin = preview.borders.take(preview.outerLoops).minOf(::toZone)
            assertTrue("margin $margin m: outer loop at $outerMin m", outerMin >= loopCenter(margin) - 0.01)
        }
        // Default app margin (5 cm): no-go loop unchanged since 0.40.0.
        val usual = plan(RecordedBoundary.NO_GO_CLEARANCE_M, true)
        assertEquals(RecordedBoundary.noGoLoopOffsetM(robot), usual.borders.drop(usual.outerLoops).minOf(::toNoGo), 0.01)
    }

    @Test(timeout = 300000) fun `passes get no closer to the boundaries than their loop`() {
        for (margin in listOf(0.05, 0.25, 0.5)) {
            val withLoops = plan(margin, true)
            assertTrue(withLoops.runs.isNotEmpty())
            val zoneMin = withLoops.runs.minOf(::toZone)
            val noGoMin = withLoops.runs.minOf(::toNoGo)
            assertTrue("margin $margin m, passes $zoneMin m from the boundary", zoneMin >= loopCenter(margin) + CoveragePlanner.ZIGZAG_LOOP_INSET_M - 0.01)
            assertTrue("margin $margin m, passes $noGoMin m from the no-go zone", noGoMin >= loopCenter(margin) + Geometry.CUT_WIDTH_M / 4 - 0.01)
            // Bumper at the pass end: at least the set margin between it and the no-go boundary.
            assertTrue("margin $margin m: bumper at ${noGoMin - robot.frontM} m", noGoMin - robot.frontM >= margin - 0.01)
            // Without a loop: loop distance + half a cut (the margin used to be ignored).
            val bare = plan(margin, false)
            assertTrue(bare.borders.isEmpty() && bare.runs.isNotEmpty())
            val bareMin = minOf(bare.runs.minOf(::toZone), bare.runs.minOf(::toNoGo))
            assertTrue("no loop, margin $margin m: passes at $bareMin m", bareMin >= loopCenter(margin) + CoveragePlanner.ZIGZAG_LOOP_INSET_M - 0.01)
        }
    }

    @Test(timeout = 300000) fun `two close no-go zones are bypassed together at the margin`() {
        // 60 cm between the two: a separate loop 51 cm from one would pass 9 cm from the other.
        val other = listOf(Point(7.6, 4.0), Point(9.0, 4.0), Point(9.0, 6.0), Point(7.6, 6.0))
        val margin = 0.25
        val preview = CoveragePlanner.plan(zone, listOf(noGo, other), 0.25, 0.6, CoverageMode.LINES, true,
            recordedBoundary = true, outerOffsetM = margin)
        val loops = preview.borders.drop(preview.outerLoops)
        assertEquals(1, loops.size)
        val both = polygon(noGo).union(polygon(other))
        val min = loops.minOf { line(it).distance(both) }
        assertTrue("loop $min m from the no-go zones", min >= loopCenter(margin) - 0.01)
        assertTrue(preview.runs.all { line(it).distance(both) >= loopCenter(margin) + Geometry.CUT_WIDTH_M / 4 - 0.01 })
    }
}
