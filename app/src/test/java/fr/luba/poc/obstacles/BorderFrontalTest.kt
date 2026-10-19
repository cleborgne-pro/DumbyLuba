package fr.luba.poc.obstacles

import fr.luba.poc.map.CoverageMode
import fr.luba.poc.map.CoveragePlanner
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RecordedBoundary
import fr.luba.poc.map.RobotMap
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class BorderFrontalTest {
    private val robot = RobotEnvelope.LUBA2
    private val zone = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0))
    private val noGo = listOf(Point(8.0, 8.0), Point(12.0, 8.0), Point(12.0, 12.0), Point(8.0, 12.0))
    private val map = RobotMap(listOf(MapShape(1, 0, zone, "Pelouse"), MapShape(2, 1, noGo, "Massif")), true)
    private val factory = GeometryFactory()
    private fun polygon(points: List<Point>) = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())

    @Test fun `outlines offered zones then no-go zones`() {
        val choices = BorderFrontal.choices(map)
        assertEquals(listOf(1L, 2L), choices.map { it.shapeId })
        assertEquals(listOf(BorderKind.OUTER, BorderKind.NO_GO), choices.map { it.kind })
        assertEquals("Pelouse", choices.first().label)
    }

    @Test fun `outer border closest point and normal pointing inwards`() {
        val plan = BorderFrontal.plan(map, 1, Point(10.0, 2.0), 0.05)
        assertEquals(10.0, plan.contact.x, 1e-9); assertEquals(0.0, plan.contact.y, 1e-9)
        assertEquals(0.0, plan.normalX, 1e-9); assertEquals(1.0, plan.normalY, 1e-9)
        assertEquals(270.0, plan.headingDegrees, 1e-9)
        // Loop: half-width + margin; passes half a cut behind.
        assertEquals(robot.halfWidthM + 0.05 + CoveragePlanner.ZIGZAG_LOOP_INSET_M, plan.passEndM, 1e-9)
        // The robot is longer than it is wide: at the end of a pass, the front is further from the outline than the
        // side on the loop, but the corners sweep more during the pivot.
        assertEquals(plan.passEndM - robot.frontM, plan.plannedFrontGapM, 1e-9)
        assertTrue(plan.plannedFrontGapM > 0.05)
        assertEquals(plan.passEndM - robot.turnClearanceM, plan.plannedPivotGapM, 1e-9)
        assertTrue(plan.plannedPivotGapM < plan.plannedFrontGapM)
        assertEquals(plan.passEndM + BorderFrontal.SLOW_RUN_M, plan.approach.y, 1e-9)
        assertEquals(plan.approachM + BorderFrontal.ENTRY_RUN_M, plan.entry.y, 1e-9)
    }

    @Test fun `no-go zone normal pointing outwards and pivot reserve`() {
        val plan = BorderFrontal.plan(map, 2, Point(10.0, 6.0), 0.05)
        assertEquals(10.0, plan.contact.x, 1e-9); assertEquals(8.0, plan.contact.y, 1e-9)
        assertEquals(-1.0, plan.normalY, 1e-9)
        assertEquals(90.0, plan.headingDegrees, 1e-9)
        assertEquals(maxOf(robot.halfWidthM + 0.05 + 0.10, robot.turnClearanceM + 0.05 + 0.01), plan.passEndM, 1e-9)
    }

    /** The test's end of pass is the mowing planner's, for the limit as for a no-go zone. */
    @Test(timeout = 300000) fun `end of pass identical to the mowing planner`() {
        val preview = CoveragePlanner.plan(zone, listOf(noGo), 0.25, 0.6, CoverageMode.LINES, true, recordedBoundary = true, outerOffsetM = 0.05)
        val ends = preview.runs.flatMap { listOf(it.first(), it.last()) }
        val outer = polygon(zone).exteriorRing
        val hole = polygon(noGo)
        fun point(p: Point) = factory.createPoint(Coordinate(p.x, p.y))
        val toOuter = ends.minOf { point(it).distance(outer) }
        val toNoGo = ends.minOf { point(it).distance(hole) }
        assertEquals(BorderFrontal.passEndM(BorderKind.OUTER, 0.05), toOuter, 0.03)
        assertEquals(BorderFrontal.passEndM(BorderKind.NO_GO, 0.05), toNoGo, 0.03)
    }

    @Test fun `point away from the ends of the side`() {
        // Robot near a corner: target point brought back to half a width + 30 cm from the corner, to stay frontal.
        val plan = BorderFrontal.plan(map, 1, Point(0.8, 0.9), 0.05)
        val margin = robot.halfWidthM + 0.30
        assertTrue(plan.contact.x >= margin - 1e-9 || plan.contact.y >= margin - 1e-9)
        assertTrue(plan.contact.x == 0.0 || plan.contact.y == 0.0)
    }

    @Test fun `outline without a long enough side refused`() {
        val tiny = RobotMap(listOf(MapShape(1, 0, zone), MapShape(3, 1, listOf(Point(5.0, 5.0), Point(5.8, 5.0), Point(5.8, 5.8), Point(5.0, 5.8)))), true)
        val error = runCatching { BorderFrontal.plan(tiny, 3, Point(5.4, 3.0), 0.05) }.exceptionOrNull()
        assertNotNull(error); assertTrue(error!!.message!!.contains("Aucun côté"))
        assertNotNull(runCatching { BorderFrontal.plan(map, 99, Point(5.0, 5.0), 0.05) }.exceptionOrNull())
    }

    @Test fun `measurements along the normal`() {
        val plan = BorderFrontal.plan(map, 1, Point(10.0, 2.0), 0.05)
        val center = Point(10.05, 0.40)
        assertEquals(0.40, plan.centerGap(center), 1e-9)
        assertEquals(0.40 - robot.frontM, plan.frontGap(center), 1e-9)
        assertEquals(0.05, kotlin.math.abs(plan.lateral(center)), 1e-9)
        assertEquals(-RecordedBoundary.PHYSICAL_TOLERANCE_M, plan.frontGap(plan.limit), 1e-9)
        // 2 cm/s, position 400 ms old, 600 ms delay: 2 cm more travelled.
        assertEquals(0.48, BorderFrontal.predictedCenterGap(0.50, 400, 600, 20, 0.001), 1e-9)
    }

    @Test(timeout = 300000) fun `move to a point ends facing the outline`() {
        val plan = BorderFrontal.plan(map, 1, Point(10.0, 5.0), 0.05)
        val nav = BorderFrontal.navPlan(map, Point(10.0, 5.0), plan, emptyList())
        assertEquals(plan.approach, nav.points.last())
        assertEquals(plan.entry, nav.points[nav.points.size - 2])
        assertEquals(nav.points.size, nav.labels.size)
        assertEquals(BorderFrontal.APPROACH_LABEL, nav.labels.last())
        assertTrue(nav.physicalGuard)
        // Last straight line perpendicular to the outline, towards it.
        val a = nav.points[nav.points.size - 2]; val b = nav.points.last()
        assertEquals(0.0, b.x - a.x, 1e-9); assertTrue(b.y < a.y)
    }

    @Test fun `test line blocked by an obstacle or another no-go zone`() {
        val plan = BorderFrontal.plan(map, 1, Point(10.0, 2.0), 0.05)
        assertNull(BorderFrontal.lineFailure(map, plan, emptyList()))
        val tree = LocalObstacle("t", ObstacleKind.TREE, 10.0, 0.9, 0.2, 0.1)
        assertNotNull(BorderFrontal.lineFailure(map, plan, listOf(tree)))
        val near = RobotMap(map.shapes + MapShape(4, 1, listOf(Point(10.1, 0.5), Point(11.0, 0.5), Point(11.0, 1.0), Point(10.1, 1.0))), true)
        assertNotNull(BorderFrontal.lineFailure(near, plan, emptyList()))
    }

    @Test fun `summary actual reserve at the end of a pass`() {
        val plan = BorderFrontal.plan(map, 1, Point(10.0, 2.0), 0.05)
        // Physical edge 5 cm before the outline: the end-of-pass bumper reserve shrinks by as much.
        val text = BorderFrontal.summary(plan, plan.plannedFrontGapM, 0.05)
        assertTrue(text, text.contains("bord à 5 cm avant le contour"))
        assertTrue(text, text.contains("pare-chocs %.0f cm".format((plan.plannedFrontGapM - 0.05) * 100)))
        assertTrue(BorderFrontal.summary(plan, null, -0.10).contains("Bord à 10 cm au-delà du contour"))
    }
}
