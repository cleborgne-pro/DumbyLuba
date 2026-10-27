package fr.luba.poc.protocol

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point

/**
 * Self-check 25 beyond the mapped zones.
 *
 * 2026-10-20: robot driven beyond a zone by "Move to a point", self-check 25 published there, move stopped and robot
 * stuck (joystick and routes refused). Beyond its map the robot raises this value without any contact; the user wants
 * to drive freely there. For blade-OFF moves only (joystick, move to a point), 25 is therefore ignored while the robot
 * stands beyond the mapped zones and its bumper / lift sensors are clear. A real contact stays handled as before: the
 * bumper sensors stop the joystick, the contact code (-1014) triggers the back-off and bypass of the move. Inside the
 * zones, and for mowing, 25 remains the collision lock ([CollisionLatch]).
 *
 * 2026-10-25, 15:33:37 (Aire de jeux border, Fold log): pivot at a corner of the border pass, robot centre 18 cm from a
 * no-go zone, rear of the chassis inside it; 25 raised without any contact code. Taken for a contact: estimated obstacle
 * saved in front of the robot, back-off refused (the no-go is right behind), joystick refused, ten "Unlock" attempts,
 * robot pushed by hand. At a no-go zone ([fr.luba.poc.simulation.PointPlanner.atExclusion]) the joystick ignores 25 as
 * beyond the map, and a route stops there without a contact ([atEdge]).
 */
object OffMapLatch {
    /** The robot centre within this distance inside the zone borders already counts as beyond (chassis across the border). */
    const val INSIDE_TOLERANCE_M = 0.35

    /** Distance from a section beyond the map ([fr.luba.poc.simulation.PointPlanner.OFF_MAP_CORRIDOR_M]) still counting as beyond. */
    const val SECTION_TOLERANCE_M = 0.9

    /** 25 ignored: robot [beyond] the mapped zones, bumper and lift sensors known and clear. */
    fun ignored(selfCheck: Long?, sensors: Int?, beyond: Boolean): Boolean =
        selfCheck == CollisionLatch.VALUE && beyond && sensors != null && sensors and 7 == 0

    /** Self-check allowing a blade-OFF move: nominal, or 25 [ignored] beyond the map. */
    fun movable(selfCheck: Long?, sensors: Int?, beyond: Boolean): Boolean =
        SelfCheck.nominal(selfCheck) || ignored(selfCheck, sensors, beyond)

    /**
     * Robot centre at an edge of its task area: beyond the mapped zones or at a no-go zone. 25 raised there without a
     * contact code means "outside the task area" ([SelfCheck.name]), not a collision.
     */
    fun atEdge(map: fr.luba.poc.map.RobotMap, p: Point?): Boolean =
        fr.luba.poc.simulation.PointPlanner.beyondMapped(map, p) || fr.luba.poc.simulation.PointPlanner.atExclusion(map, p)

    /** Robot at [p] on a section beyond the map of its move ([sections], gateway side: no map there). */
    fun onSections(p: Point?, sections: List<List<Point>>): Boolean =
        p != null && p.x.isFinite() && p.y.isFinite() && sections.any { s ->
            when {
                s.isEmpty() -> false
                s.size == 1 -> s[0].distance(p) <= SECTION_TOLERANCE_M
                else -> s.zipWithNext().any { (a, b) -> Geometry.distance(p, a, b) <= SECTION_TOLERANCE_M }
            }
        }
}
