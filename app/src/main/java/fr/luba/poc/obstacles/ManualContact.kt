package fr.luba.poc.obstacles

import fr.luba.poc.map.Point
import kotlin.math.cos
import kotlin.math.sin

/**
 * Obstacle met while driving the robot by hand (joystick), or pointed at on purpose from Service → Obstacle tests.
 *
 * Before 2026-10-22, an obstacle could only be learnt by the "slow approach" bench, itself locked until a stationary
 * contact had been validated on the same connection; a bumper contact during manual control only stopped the joystick
 * with a critical alert, and nothing was kept. Now the contact is logged on the map ("manual control" origin) and its
 * sheet opens at once: keep it as an obstacle (type, radius, margin) or dismiss it.
 */
object ManualContact {
    /** Window after a joystick stop on a collision protection, during which the late 1014 refines the same contact. */
    const val GRACE_MS = 3_000L

    /** Object radius assumed for a contact (same as an estimated contact in mission). */
    const val CONTACT_RADIUS_M = 0.2

    /** Placement distance of an obstacle pointed at without contact, from the robot reference point (m). */
    val AHEAD_RANGE_M = 0.0..4.0

    /**
     * Joystick stopped by a protection that may be a contact: collision lock (self-check 25, published before the 1014)
     * or bumper / lift alert bits.
     */
    fun suspected(selfCheck: Long?, sensors: Int?): Boolean =
        fr.luba.poc.protocol.CollisionLatch.latched(selfCheck) || TeachSafety.bumperHealthAlert(sensors)

    /**
     * Estimated object centre: in front of the bumper going forward, behind the chassis when reversing ([reversing]: last
     * non-zero joystick speed negative), on the robot when the heading is unknown (the contact stays visible and can be kept).
     */
    fun obstacleCenter(robot: Point, headingDeg: Double?, reversing: Boolean, radiusM: Double = CONTACT_RADIUS_M,
                       envelope: RobotEnvelope = RobotEnvelope.LUBA2): Point {
        if (headingDeg == null || !headingDeg.isFinite()) return robot
        val distance = if (reversing) -(envelope.rearM + radiusM) else envelope.frontM + radiusM
        return ahead(robot, headingDeg, distance)
    }

    /** Point [distanceM] along [headingDeg] (degrees, counter-clockwise around +x) from [origin]; negative = behind. */
    fun ahead(origin: Point, headingDeg: Double, distanceM: Double): Point {
        val radians = Math.toRadians(headingDeg)
        return Point(origin.x + distanceM * cos(radians), origin.y + distanceM * sin(radians))
    }

    /** Default distance of an obstacle pointed at in front of the robot: touching the bumper, centre one radius further. */
    fun defaultAheadM(radiusM: Double, envelope: RobotEnvelope = RobotEnvelope.LUBA2): Double = envelope.frontM + radiusM
}
