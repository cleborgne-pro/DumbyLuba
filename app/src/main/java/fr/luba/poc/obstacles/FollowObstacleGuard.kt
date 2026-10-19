package fr.luba.poc.obstacles

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import kotlin.math.max

/**
 * Obstacle guard of the route tracking (Fold and gateway): distance from the obstacle centre to the robot's projected
 * segment below which tracking reacts. Rule shared by `RobotBle.followPath` and `autonomy.LegRunner`.
 *
 * - Escape out of a reserve ([escaping]): physical radius + turning envelope.
 * - Obstacle missing from the prepared route: full margin + envelope; it triggers a bypass.
 * - Obstacle already bypassed by the prepared route ([planned]): the route passes 5 cm from the full margin
 *   ([fr.luba.poc.simulation.ObstacleBypass.SLACK_M]); position or heading noise during a pivot was enough to stop
 *   the mowing (2026-10-14 at 14:36:57: robot 95 cm from the centre of a 0.2 + 0.3 m obstacle, guard at 93 cm,
 *   "Obstacle local dans l'enveloppe robot"). The guard tolerates [PLANNED_TOLERANCE_M] on the margin, never on the
 *   physical radius: the chassis sweep stays at least margin − 15 cm from the object.
 *
 * By construction the escape starts inside the reserve: [intrudes] only flags there the moves that bring the robot
 * closer to the obstacle (2026-10-15 at 09:06:51: robot 62 cm from the centre of a 0.2 m obstacle, escape reserve at
 * 63 cm, a 30 cm straight line moving away refused before the first command, mowing blocked).
 */
object FollowObstacleGuard {
    const val PLANNED_TOLERANCE_M = 0.15

    fun reachM(radiusM: Double, exclusionRadiusM: Double, planned: Boolean, escaping: Boolean,
               robot: RobotEnvelope = RobotEnvelope.LUBA2): Double = robot.turnClearanceM + when {
        escaping -> radiusM
        planned -> max(radiusM, exclusionRadiusM - PLANNED_TOLERANCE_M)
        else -> exclusionRadiusM
    }

    /**
     * The move [position] → [next] enters the tracking reserve of the obstacle centred at [center]. When escaping
     * ([escaping]), a translation moving the robot centre away from the obstacle is not flagged as long as the chassis
     * does not overlap the physical disc (centre beyond radius + half-width); an approach or an in-place pivot is still
     * checked against the escape reserve (physical radius + turning envelope).
     */
    fun intrudes(center: Point, position: Point, next: Point, radiusM: Double, exclusionRadiusM: Double, planned: Boolean,
                 escaping: Boolean, robot: RobotEnvelope = RobotEnvelope.LUBA2): Boolean {
        if (Geometry.distance(center, position, next) >= reachM(radiusM, exclusionRadiusM, planned, escaping, robot)) return false
        if (!escaping) return true
        val here = center.distance(position)
        // In-place pivot: the corners sweep the turning disc, check unchanged (physical radius + envelope).
        if (position.distance(next) < 1e-3) return true
        return center.distance(next) < here - 0.005 || here < radiusM + robot.halfWidthM
    }

    /**
     * Prepared route ([segments]) kept out of the tracking reserve of [obstacle] (margin and turning envelope, within
     * 1 cm): the obstacle counts as planned for the guard. Used for presumed obstacles, which may appear after the
     * preparation (recent contact during a mowing that is resumed afterwards).
     */
    fun keptOut(obstacle: LocalObstacle, segments: List<Pair<Point, Point>>, robot: RobotEnvelope = RobotEnvelope.LUBA2): Boolean {
        val center = Point(obstacle.x, obstacle.y)
        val reserve = obstacle.exclusionRadiusM + robot.turnClearanceM - 0.01
        return segments.none { (a, b) -> Geometry.distance(center, a, b) < reserve }
    }
}
