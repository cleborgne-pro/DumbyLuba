package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Station exit in a slow straight line, without pivot: from the base to the link point in front of it.
 *
 * Shared by the pilot (direct Bluetooth) and the gateway on the robot (autonomous tracking), so that the exit no
 * longer depends on Wi-Fi. The 10 cm lateral corridor is checked at every position. The published heading is only
 * checked while at rest on the base (it defines the axis): the LUBA 2 realigns its heading by itself after a few tens
 * of centimetres (jumps of 77–90° measured, ROBOT_ROUTE_BLE.md), which stopped the exit at about 20 cm. While moving,
 * the direction actually travelled is checked (beyond [TRACK_M]).
 */
class DockExit(private val base: Point, private val approach: Point) {
    private val length = base.distance(approach)
    private val ux = (approach.x - base.x) / length
    private val uy = (approach.y - base.y) / length
    /** Base → link point direction (map heading convention: 0° = +x). */
    val axis = Math.toDegrees(atan2(uy, ux))
    private var start: Point? = null
    /** First gap between the published heading and the axis observed while moving (robot realignment), null otherwise. */
    var headingJump: Double? = null; private set

    init { require(length > 0.2) { tr("Link point too close to the base", "Point de liaison trop proche de la base") } }

    /** [linear] (angular always zero), heading used for the envelope check, [done] at the link point. */
    data class Step(val linear: Int, val heading: Double, val done: Boolean)

    fun step(position: Point, reportedHeading: Double): Step {
        val origin = start ?: position.also { start = it }
        val along = (position.x - base.x) * ux + (position.y - base.y) * uy
        val lateral = abs((position.x - base.x) * uy - (position.y - base.y) * ux)
        check(lateral <= LATERAL_M) { tr("Deviation from the exit corridor >10 cm (%.0f cm)", "Écart au couloir de sortie >10 cm (%.0f cm)").format(lateral * 100) }
        if (along >= length - 0.05) return Step(0, axis, true)
        val moved = position.distance(origin)
        val heading = when {
            moved < START_M -> {
                check(abs(RouteRecharge.headingError(axis, reportedHeading)) <= START_HEADING_DEG) {
                    tr("Robot not aligned with the exit axis (heading %.0f°, axis %.0f°): straight-line exit refused",
                        "Robot non aligné sur l'axe de sortie (cap %.0f°, axe %.0f°) : sortie en ligne droite refusée").format(reportedHeading, axis) }
                reportedHeading
            }
            moved >= TRACK_M -> {
                val track = Math.toDegrees(atan2(position.y - origin.y, position.x - origin.x))
                check(abs(RouteRecharge.headingError(axis, track)) <= TRACK_HEADING_DEG) {
                    tr("Station exit deviated: direction traveled %.0f°, axis %.0f°",
                        "Sortie de station déviée : direction parcourue %.0f°, axe %.0f°").format(track, axis) }
                track
            }
            else -> axis
        }
        if (moved >= START_M && headingJump == null && abs(RouteRecharge.headingError(axis, reportedHeading)) > START_HEADING_DEG)
            headingJump = RouteRecharge.headingError(axis, reportedHeading)
        return Step(RouteRecharge.EXIT_LINEAR, heading, false)
    }

    companion object {
        /**
         * Reason of an envelope refusal during the exit, with the pose: [overlapping] when the chassis already
         * overlapped the free surface on its station and leaving straight ahead does not clear it.
         */
        fun envelopeRefusal(position: Point, base: Point, overlapping: Boolean): String {
            val where = "(%.2f m, %.2f m ; %.0f cm)".format(position.x, position.y, position.distance(base) * 100)
            return if (overlapping) tr("Station exit outside the safe envelope: the chassis on the base overlaps a no-go zone, an obstacle or the map edge, and leaving straight ahead does not clear it $where",
                "Sortie de station hors enveloppe sûre : le châssis sur la base chevauche une zone interdite, un obstacle ou le bord de la carte, et la sortie tout droit ne l'en dégage pas $where")
            else tr("Station exit outside the safe envelope: the next step leaves the free surface $where",
                "Sortie de station hors enveloppe sûre : le pas suivant quitte la surface libre $where")
        }
        const val LATERAL_M = 0.10
        /** Up to here the robot is still at rest on the base: its published heading must be the axis one. */
        const val START_M = 0.05
        const val START_HEADING_DEG = 10.0
        /** Distance travelled from which the measured direction (RTK noise ≈ 1–2 cm) is reliable. */
        const val TRACK_M = 0.25
        const val TRACK_HEADING_DEG = 15.0
    }
}
