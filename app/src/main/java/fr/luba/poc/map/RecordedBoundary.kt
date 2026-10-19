package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

/**
 * Boundaries recorded by the robot (zones, no-go): they describe the **outer side of the chassis**, not its center.
 * All center paths (border loops, passes, links) therefore stay at least half a width away from the boundary
 * ([centerInsetM]); the blades, narrower than the chassis, cannot reach the [bladeReachInsetM] band along that
 * boundary. During a manoeuvre (pivot at the end of a pass, tracking deviation), the chassis may overflow the boundary
 * by at most [PHYSICAL_TOLERANCE_M]: physical area of the footprint checks.
 *
 * Before 0.40.0, the boundary was read as the center trace: the allowed area overflowed by half a width outside the
 * zones and into the no-go zones, and no-go loops passed 8 cm from the boundary (chassis 18 cm inside).
 */
object RecordedBoundary {
    /** Default margin between the chassis and a no-go boundary, for its border loop (tests, surveys). */
    const val NO_GO_CLEARANCE_M = 0.05
    /** Chassis overflow tolerated beyond a recorded boundary during a manoeuvre or a tracking deviation. */
    const val PHYSICAL_TOLERANCE_M = 0.15
    private val factory = GeometryFactory()
    private fun polygon(points: List<Point>): Geometry {
        val ring = if (points.first() == points.last()) points else points + points.first()
        return factory.createPolygon(ring.map { Coordinate(it.x,it.y) }.toTypedArray())
    }
    /** Minimum distance from the robot center to a recorded boundary: half the chassis width. */
    fun centerInsetM(robot: RobotEnvelope): Double = robot.halfWidthM
    /** Band along a boundary that the blades cannot reach, chassis against the boundary. */
    fun bladeReachInsetM(robot: RobotEnvelope, cutWidthM: Double): Double = (robot.halfWidthM - cutWidthM / 2).coerceAtLeast(0.0)
    /**
     * Distance from the center to a no-go boundary for its border loop (pass [pass] counted from 0). Mowing applies the
     * outer loop margin there ([marginM]): same gap between the chassis and a no-go as with the boundary.
     */
    fun noGoLoopOffsetM(robot: RobotEnvelope, pass: Int = 0, spacingM: Double = 0.0, marginM: Double = NO_GO_CLEARANCE_M): Double =
        centerInsetM(robot) + marginM + pass * spacingM
    /** Area where the chassis may be during a manoeuvre: the zone, grown by [PHYSICAL_TOLERANCE_M]. */
    fun physicalArea(points: List<Point>, robot: RobotEnvelope): Geometry = polygon(points).buffer(PHYSICAL_TOLERANCE_M, 16)
    /** Area forbidden to the chassis even during a manoeuvre: the no-go zone, shrunk by [PHYSICAL_TOLERANCE_M]. */
    fun forbiddenArea(points: List<Point>, robot: RobotEnvelope): Geometry = polygon(points).buffer(-PHYSICAL_TOLERANCE_M, 16)
    /** Corner rounding radius of a border loop: the chassis pivot radius. */
    fun ringRoundingM(robot: RobotEnvelope): Double = robot.turnClearanceM
}
