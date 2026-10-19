package fr.luba.poc.obstacles

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import kotlin.math.hypot

/**
 * Bypass test around a saved obstacle (Service → Obstacle tests → Bypass): the robot starts from its position, reaches the
 * opposite side of the obstacle and comes back. The straight line crosses the obstacle: only the local route
 * computation (same reserves as transits) can avoid it.
 */
object ObstacleCourse {
    val FAR_LABEL: String get() = tr("Opposite side", "Côté opposé")
    val HOME_LABEL: String get() = tr("Back to start", "Retour au départ")
    /** Distance beyond the obstacle reserve for the opposite point. */
    const val BEYOND_M = 1.0
    /** Start far enough from the reserve for the bypass to be visible, close enough to stay short. */
    const val MIN_START_GAP_M = 0.5
    const val MAX_START_DISTANCE_M = 10.0

    /** Disc the robot reference point must not cross. */
    fun keepOutM(obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2): Double = obstacle.keepOutDisc(robot).radiusM

    /** Reason for refusing the start, or null. */
    fun startFailure(start: Point, obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2): String? {
        val distance = start.distance(Point(obstacle.x, obstacle.y))
        val keepOut = keepOutM(obstacle, robot)
        if (distance < keepOut + MIN_START_GAP_M) return tr("Robot too close to the obstacle: move it more than %.1f m from the center", "Robot trop près de l'obstacle : l'éloigner à plus de %.1f m du centre").format(keepOut + MIN_START_GAP_M)
        if (distance > MAX_START_DISTANCE_M) return tr("Obstacle %.1f m away: bring the robot within %.0f m", "Obstacle à %.1f m : approcher le robot à moins de %.0f m").format(distance, MAX_START_DISTANCE_M)
        return null
    }

    /** Opposite point: on the robot → obstacle axis, [BEYOND_M] beyond the reserve. */
    fun farSide(start: Point, obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2): Point {
        val dx = obstacle.x - start.x; val dy = obstacle.y - start.y
        val length = hypot(dx, dy)
        require(length > 1e-6) { tr("Robot at the center of the obstacle", "Robot au centre de l'obstacle") }
        val reach = keepOutM(obstacle, robot) + BEYOND_M
        return Point(obstacle.x + dx / length * reach, obstacle.y + dy / length * reach)
    }

    /** Minimum distance between the planned route and the obstacle centre. */
    fun plannedCenterDistance(points: List<Point>, obstacle: LocalObstacle): Double {
        val center = Point(obstacle.x, obstacle.y)
        if (points.size < 2) return points.firstOrNull()?.distance(center) ?: Double.POSITIVE_INFINITY
        return points.zipWithNext().minOf { (a, b) -> Geometry.distance(center, a, b) }
    }

    /** Minimum observed gap between the robot reference point and the obstacle surface (physical radius). */
    fun observedSurfaceGap(trail: List<Point>, obstacle: LocalObstacle): Double? =
        trail.minOfOrNull { it.distance(Point(obstacle.x, obstacle.y)) }?.let { it - obstacle.radiusM }

    /** Passed if the robot side (half-width) never reached the obstacle surface. */
    fun passed(surfaceGapM: Double?, robot: RobotEnvelope = RobotEnvelope.LUBA2): Boolean =
        surfaceGapM != null && surfaceGapM >= robot.halfWidthM
}
