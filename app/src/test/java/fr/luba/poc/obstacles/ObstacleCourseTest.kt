package fr.luba.poc.obstacles

import fr.luba.poc.diagnostics.ObstacleCoursePlanner
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.junit.Assert.*
import org.junit.Test

class ObstacleCourseTest {
    private val tree = LocalObstacle("t", ObstacleKind.TREE, 10.0, 10.0, 0.3, 0.2)
    private val map = RobotMap(listOf(MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0)))), true)

    @Test fun `opposite point on the axis beyond the reserve`() {
        val start = Point(6.0, 10.0)
        val far = ObstacleCourse.farSide(start, tree)
        assertEquals(10.0 + ObstacleCourse.keepOutM(tree) + ObstacleCourse.BEYOND_M, far.x, 1e-9)
        assertEquals(10.0, far.y, 1e-9)
        assertEquals(0.5 + RobotEnvelope.LUBA2.turnClearanceM, ObstacleCourse.keepOutM(tree), 1e-9)
    }

    @Test fun `start refused too close or too far`() {
        assertNotNull(ObstacleCourse.startFailure(Point(10.5, 10.0), tree))
        assertNotNull(ObstacleCourse.startFailure(Point(10.0, 21.0), tree))
        assertNull(ObstacleCourse.startFailure(Point(6.0, 10.0), tree))
    }

    @Test fun `round trip bypasses the obstacle without crossing its reserve`() {
        val start = Point(6.0, 10.0)
        val far = ObstacleCourse.farSide(start, tree)
        val plan = ObstacleCoursePlanner.plan(map, start, far, start, listOf(tree))
        assertEquals(start, plan.points.first()); assertEquals(start, plan.points.last())
        assertTrue(far in plan.points)
        assertEquals(ObstacleCourse.FAR_LABEL, plan.labels[plan.points.indexOf(far)])
        assertEquals(ObstacleCourse.HOME_LABEL, plan.labels.last())
        assertEquals(plan.points.size, plan.labels.size)
        assertTrue(plan.physicalGuard)
        // The route passes outside the reserve (radius + margin), so the robot side does not touch the obstacle.
        assertTrue(ObstacleCourse.plannedCenterDistance(plan.points, tree) >= tree.exclusionRadiusM - 1e-6)
        assertTrue(plan.points.size > 3)
        val back = ObstacleCoursePlanner.plan(map, far, start, start, listOf(tree), includeFar = false)
        assertEquals(start, back.points.last()); assertFalse(ObstacleCourse.FAR_LABEL in back.labels)
    }

    @Test fun `observed gap and verdict`() {
        assertNull(ObstacleCourse.observedSurfaceGap(emptyList(), tree))
        val gap = ObstacleCourse.observedSurfaceGap(listOf(Point(6.0, 10.0), Point(10.0, 11.0)), tree)!!
        assertEquals(0.7, gap, 1e-9)
        assertTrue(ObstacleCourse.passed(gap)); assertFalse(ObstacleCourse.passed(0.2)); assertFalse(ObstacleCourse.passed(null))
    }

    @Test fun `live bumper preconditions`() {
        val ok = TeachSafety.checks("Luba-VS-1", 100, 80, 11, 0, 0, false, false, false, false)
        assertTrue(ok.all { it.second })
        assertNull(TeachSafety.startFailure("Luba-VS-1", 100, 80, 11, 0, 0, false, false, false, false))
        val bumper = TeachSafety.checks("Luba-VS-1", 100, 80, 11, 2, 0, false, false, false, false)
        assertEquals(listOf("Pare-chocs sans alerte"), bumper.filterNot { it.second }.map { it.first })
        assertEquals("Alerte de santé bumper", TeachSafety.startFailure("Luba-VS-1", 100, 80, 11, null, 0, false, false, false, false))
        assertEquals("Lames non confirmées OFF par la télémétrie", TeachSafety.startFailure("Luba-VS-1", 100, 80, 11, 512, 0, false, false, false, false))
    }
}
