package fr.luba.poc.history

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.RobotRouteUi
import fr.luba.poc.ui.Format
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Live tracking of the route: lateral deviation of the robot from the edge being driven and waypoint aimed at.
 * [RobotRouteUi.planned] holds the start then the end of every edge, [RobotRouteUi.index] the 1-based number of the
 * edge being driven; both are published by the direct tracking and by the gateway progress, so the view is the same
 * in both modes.
 */
object LiveTracking {
    data class Tracking(
        /** Signed distance from the edge (m): positive with the robot on the left of the driving direction. */
        val lateralM: Double,
        /** Waypoint aimed at: end of the edge being driven, its 1-based number and the total. */
        val target: Point, val targetNumber: Int, val count: Int,
        val distanceToTargetM: Double,
    )

    fun of(route: RobotRouteUi, x: Double?, y: Double?): Tracking? {
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return null
        val planned = route.planned
        val i = route.index.coerceAtLeast(1)
        if (i >= planned.size) return null
        val a = planned[i - 1]; val b = planned[i]
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        val p = Point(x, y)
        val lateral = if (length < 1e-6) 0.0 else (dx * (y - a.y) - dy * (x - a.x)) / length
        return Tracking(lateral, b, i, planned.size - 1, p.distance(b))
    }

    /** "Deviation 4 cm left · waypoint 12/240 in 3.2 m". */
    fun text(t: Tracking): String {
        val cm = Format.num(abs(t.lateralM) * 100, 0)
        val side = when {
            abs(t.lateralM) < 0.005 -> tr("on the line", "sur la ligne")
            t.lateralM > 0 -> tr("$cm cm left", "$cm cm à gauche")
            else -> tr("$cm cm right", "$cm cm à droite")
        }
        return tr("Deviation $side", "Écart $side") + " · " +
            tr("waypoint ${t.targetNumber}/${t.count} in ${Format.num(t.distanceToTargetM, 1)} m",
                "point ${t.targetNumber}/${t.count} à ${Format.num(t.distanceToTargetM, 1)} m")
    }
}
