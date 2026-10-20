package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Departure of a move (move to a point, map route test, cutting test) from the charging station: a robot on its base,
 * or still inside the base safety zone, first leaves in a slow straight line to the link point in front of the base
 * ([DockExit], same exit as mowing routes), without any pivot near the station; the path to the destination is
 * computed from that link point.
 */
object BaseDeparture {
    /** Straight exit from [from] (robot position) along the base axis to [approach]. */
    data class Exit(val base: Point, val approach: Point, val from: Point) {
        val meters: Double get() = from.distance(approach)
    }

    /** Distance to the base point beyond which a charging robot is not on the configured base (as for routes). */
    const val CHARGING_BASE_M = 0.25
    /** Margin behind the base point still accepted in the exit corridor (robot pushed against the station). */
    private const val BEHIND_M = 0.10

    fun approach(base: MapBase): Point? = base.entryHeadingDegrees?.let { heading ->
        val axis = Math.toRadians(heading)
        Point(base.point.x - cos(axis) * base.approachDistanceM, base.point.y - sin(axis) * base.approachDistanceM)
    }

    /** True if [p] lies inside the base safety zone ([TrajectoryPlanner.baseSafetyZone]), axis known. */
    fun inSafetyZone(base: MapBase?, p: Point): Boolean =
        base?.entryHeadingDegrees != null && p.distance(base.point) < TrajectoryPlanner.baseSafetyRadius(base.approachDistanceM)

    /**
     * Exit required before a move starting at [position]: robot [charging] or inside the base safety zone. Null when
     * the robot can leave freely. Refused (IllegalArgumentException) when a clean straight exit is impossible.
     */
    fun required(base: MapBase?, position: Point, charging: Boolean): Exit? {
        if (base == null) {
            require(!charging) { tr("Robot charging but base position unknown: read the map again before moving", "Robot en charge mais position de la base inconnue : relire la carte avant de déplacer") }
            return null
        }
        if (!charging && !inSafetyZone(base, position)) return null
        val approach = requireNotNull(approach(base)) { tr("Unknown base axis: observe the robot while charging", "Axe de base inconnu : observer le robot en charge") }
        if (charging) require(position.distance(base.point) <= CHARGING_BASE_M) { tr("Charging observed far from the configured base", "Charge observée loin de la base configurée") }
        val length = base.point.distance(approach)
        val ux = (approach.x - base.point.x) / length; val uy = (approach.y - base.point.y) / length
        val along = (position.x - base.point.x) * ux + (position.y - base.point.y) * uy
        val lateral = abs((position.x - base.point.x) * uy - (position.y - base.point.y) * ux)
        require(lateral <= DockExit.LATERAL_M && along >= -BEHIND_M && along < length) {
            tr("Robot next to the base, outside its exit corridor: put it back on the base or move it away with the joystick",
                "Robot près de la base, hors de son couloir de sortie : le remettre sur la base ou l’éloigner au joystick")
        }
        return Exit(base.point, approach, position)
    }
}
