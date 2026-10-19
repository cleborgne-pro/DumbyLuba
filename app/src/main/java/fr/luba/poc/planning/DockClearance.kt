package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.MapBase
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Docking corridor and learnt obstacles: the robot must always be able to reach the approach point in front of the
 * base, pivot there and then reverse into the station. The safety margin of a learnt obstacle too close to this
 * corridor (contact while leaving the base, for example) is reduced just enough to free the pivot at the approach
 * point; the physical radius of the obstacle is never reduced. If the object itself prevents this pivot, the base is
 * really unreachable: the computation is refused with a message naming the obstacle.
 *
 * Same rule for the computation (mission navigation, border loop) and for the guards of the actual route tracking
 * (Fold and gateway), which receive the obstacle with the adjusted margin.
 */
object DockClearance {
    /** Clearance kept between the pivot envelope and the reserve (polygonal outlines of the holes). */
    const val SLACK_M = 0.02

    /** Base → approach point corridor, or null if the base axis is unknown. */
    fun corridor(base: Point, entryHeadingDegrees: Double?, approachDistanceM: Double): Pair<Point, Point>? =
        entryHeadingDegrees?.let { heading ->
            val angle = Math.toRadians(heading)
            base to Point(base.x - cos(angle) * approachDistanceM, base.y - sin(angle) * approachDistanceM)
        }

    fun corridor(base: MapBase): Pair<Point, Point>? = corridor(base.point, base.entryHeadingDegrees, base.approachDistanceM)

    /** Distance from the obstacle centre to the docking corridor. */
    fun distance(obstacle: LocalObstacle, corridor: Pair<Point, Point>): Double =
        Geometry.distance(Point(obstacle.x, obstacle.y), corridor.first, corridor.second)

    /** Minimum distance between the obstacle centre and the corridor to pivot with the margin [marginM]. */
    private fun required(obstacle: LocalObstacle, marginM: Double, robot: RobotEnvelope) =
        obstacle.radiusM + marginM + robot.turnClearanceM + SLACK_M

    /**
     * Obstacles whose margin overlaps the corridor: margin reduced to the largest clearance compatible with the pivot
     * at the approach point (never negative, never increased). The others are returned as is.
     */
    fun fit(obstacles: List<LocalObstacle>, corridor: Pair<Point, Point>?, robot: RobotEnvelope = RobotEnvelope.LUBA2): List<LocalObstacle> {
        if (corridor == null) return obstacles
        return obstacles.map { obstacle ->
            val margin = distance(obstacle, corridor) - obstacle.radiusM - robot.turnClearanceM - SLACK_M
            // Margin sufficient: unchanged. Object itself too close: nothing to reduce (see [requireReachable]).
            if (margin >= obstacle.marginM || margin < 0) obstacle
            else obstacle.copy(marginM = (floor(margin * 1000) / 1000).coerceIn(0.0, obstacle.marginM))
        }
    }

    fun fit(obstacles: List<LocalObstacle>, base: MapBase, robot: RobotEnvelope = RobotEnvelope.LUBA2): List<LocalObstacle> =
        fit(obstacles, corridor(base), robot)

    /**
     * Obstacles for the pass planner (map preview, painted zone, obstacle simulation): same adjusted margin as the
     * navigation, otherwise the links to the base are refused there ("Aucune entrée sûre accessible", "Aucun ordre
     * complet accessible").
     */
    fun planning(obstacles: List<LocalObstacle>, base: MapBase?, robot: RobotEnvelope = RobotEnvelope.LUBA2): List<PlanningObstacle> =
        (base?.let { fit(obstacles, it, robot) } ?: obstacles).map { PlanningObstacle(Point(it.x, it.y), it.exclusionRadiusM) }

    /** Explicit refusal: obstacle whose physical radius alone prevents the pivot at the approach point. */
    fun requireReachable(obstacles: List<LocalObstacle>, base: MapBase, robot: RobotEnvelope = RobotEnvelope.LUBA2) {
        val corridor = corridor(base) ?: return
        obstacles.filter { distance(it, corridor) < required(it, 0.0, robot) }.minByOrNull { distance(it, corridor) }?.let { obstacle ->
            val name = obstacle.label.ifBlank { tr("Saved obstacle", "Obstacle enregistré") }
            throw IllegalArgumentException(tr("$name ${(distance(obstacle, corridor) * 100).roundToInt()} cm from the base docking corridor " +
                "(at least ${(required(obstacle, 0.0, robot) * 100).roundToInt()} cm needed to pivot at the approach point): " +
                "delete it or correct its position in Service → Obstacles.",
                "$name à ${(distance(obstacle, corridor) * 100).roundToInt()} cm du couloir d'accostage de la base " +
                "(${(required(obstacle, 0.0, robot) * 100).roundToInt()} cm au moins pour pivoter au point d'approche) : " +
                "supprimez-le ou corrigez sa position dans Service → Obstacles."))
        }
    }
}
