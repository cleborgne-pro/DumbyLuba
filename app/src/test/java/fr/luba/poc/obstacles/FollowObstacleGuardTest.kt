package fr.luba.poc.obstacles

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FollowObstacleGuardTest {
    /** Obstacle saved on 2026-10-14 at 13:17 (radius 0.2 m, margin 0.3 m) and robot pose at 14:36:57 (heading 216°, pivot). */
    private val obstacle = Point(-19.472710529913954, -8.80011018966526)
    private val position = Point(-18.5808, -9.1266)

    private fun distanceAhead(heading: Double, reach: Double = 0.25): Double {
        val rad = Math.toRadians(heading)
        return Geometry.distance(obstacle, position, Point(position.x + reach * cos(rad), position.y + reach * sin(rad)))
    }

    @Test fun plannedObstacleToleratesPivotNoiseButNotItsPhysicalRadius() {
        val planned = FollowObstacleGuard.reachM(0.2, 0.5, planned = true, escaping = false)
        val added = FollowObstacleGuard.reachM(0.2, 0.5, planned = false, escaping = false)
        assertEquals(0.5 + RobotEnvelope.LUBA2.turnClearanceM, added, 1e-9)
        assertEquals(0.35 + RobotEnvelope.LUBA2.turnClearanceM, planned, 1e-9)
        // Pivot of 2026-10-14: mowing stopped with the full margin, continued with the tolerance.
        val seen = distanceAhead(216.0)
        assertTrue("$seen", seen < added)
        assertTrue("$seen", seen > planned)
        // Robot actually heading for the object: stop kept.
        val towards = Math.toDegrees(kotlin.math.atan2(obstacle.y - position.y, obstacle.x - position.x))
        assertTrue(distanceAhead(towards, reach = 0.6) < planned)
        // The tolerance never goes below the physical radius; the escape keeps the physical radius.
        assertEquals(0.3 + RobotEnvelope.LUBA2.turnClearanceM, FollowObstacleGuard.reachM(0.3, 0.35, planned = true, escaping = false), 1e-9)
        assertEquals(0.2 + RobotEnvelope.LUBA2.turnClearanceM, FollowObstacleGuard.reachM(0.2, 0.5, planned = true, escaping = true), 1e-9)
    }

    @Test fun presumedObstacleCountsAsPlannedOnlyWhenTheRouteAlreadyAvoidsIt() {
        val touched = LocalObstacle("presume-a", ObstacleKind.OTHER, -19.55, -13.47, 0.2, 0.3)
        // Resume at 14:28:35: route prepared before the contact, straight onto the object.
        val resumed = listOf(Point(-19.12, -13.80) to Point(-19.21, -13.65), Point(-19.21, -13.65) to Point(-19.40, -13.40))
        assertFalse(FollowObstacleGuard.keptOut(touched, resumed))
        // Route prepared with the obstacle: centre 5 cm beyond the tracking reserve.
        val keep = touched.exclusionRadiusM + RobotEnvelope.LUBA2.turnClearanceM + 0.05
        assertTrue(FollowObstacleGuard.keptOut(touched, listOf(Point(-25.0, -13.47 + keep) to Point(-15.0, -13.47 + keep))))
    }

    /**
     * 2026-10-15 at 09:06:51: robot stopped 62 cm from the centre of a 0.2 m obstacle (margin 0.153 m near the docking
     * corridor), 30 cm straight-line escape moving away from it (heading 238°).
     */
    @Test fun escapeMovingAwayFromTheObstacleIsAllowedButApproachAndPivotAreNot() {
        val center = Point(1.6227098745739885, 0.4706412615117156)
        val robot = Point(0.9984, 0.4482)
        val away = Point(0.8402518555542677, 0.1932702755495707)
        fun intrudes(next: Point, escaping: Boolean = true, planned: Boolean = true) =
            FollowObstacleGuard.intrudes(center, robot, next, 0.2, 0.353, planned, escaping)
        assertTrue("inside the escape reserve", center.distance(robot) < FollowObstacleGuard.reachM(0.2, 0.353, planned = true, escaping = true))
        assertFalse(intrudes(away))
        // Towards the obstacle, or in-place pivot inside the reserve: refused.
        assertTrue(intrudes(Point(robot.x + 0.25, robot.y)))
        assertTrue(intrudes(robot))
        // Not escaping: the tracking reserve applies as before.
        assertTrue(intrudes(away, escaping = false))
        // Chassis on the physical disc: even moving away is refused.
        val touching = Point(center.x - 0.4, center.y)
        assertTrue(FollowObstacleGuard.intrudes(center, touching, Point(touching.x - 0.25, touching.y), 0.2, 0.353, true, true))
    }
}
