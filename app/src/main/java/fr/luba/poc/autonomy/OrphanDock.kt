package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.SelfCheck
import fr.luba.poc.simulation.SimEdge

/**
 * Docking by the gateway when the pilot is absent at the end of a return to base.
 *
 * Tests of 2026-10-14: the Fold's Wi-Fi dropped at 10:33 during the return (then Android disabled it until 12:07).
 * The route tracking handed to the Redmi carried on, but docking (official Mammotion return from the approach point) is a
 * pilot step: the robot stayed stopped in front of the base until the return was started by hand from the cloud at 11:52.
 *
 * Same conditions as the pilot's docking ([fr.luba.poc.ble.RobotBle] `dock()`): tracking finished normally and robot
 * stopped (stop confirmed), route ending at the approach point, robot at most [APPROACH_M] from that point on a fresh
 * position, nominal protection; blades OFF and manual mode left before the official return.
 */
object OrphanDock {
    /** Maximum deviation from the approach point: the pilot's one (`NATIVE_HANDOFF_APPROACH_M`). */
    const val APPROACH_M = 0.20
    const val MAX_POSITION_AGE_MS = 2000L
    /**
     * Start of the outcome text when the gateway requested docking: the returning pilot does not request it again.
     * Sent to the pilot and compared: never translated.
     */
    const val RESULT_PREFIX = "Accostage Mammotion demandé par la passerelle"

    /** Does the route tracking that just finished call for docking by the gateway? */
    fun wanted(spec: LegSpec, path: List<SimEdge>, outcome: LegOutcome, stopConfirmed: Boolean, pilotPresent: Boolean): Boolean {
        val approach = spec.baseApproach ?: return false
        return !pilotPresent && outcome == LegOutcome.DONE && stopConfirmed && !spec.dockExit && path.isNotEmpty() &&
            path.last().b.distance(approach) <= 1e-6
    }

    /** Refusal reason based on the robot view (shown in the gateway's own log), or null if docking can be requested. */
    fun refusal(view: RobotView, nowMs: Long, approach: Point): String? {
        val t = view.telemetry
        val x = t.x; val y = t.y
        return when {
            view.positionPrevious == 0L || nowMs - view.positionPrevious !in 0..MAX_POSITION_AGE_MS -> tr("robot position stale", "position du robot périmée")
            x == null || y == null -> tr("robot position missing", "position du robot absente")
            !SelfCheck.nominal(t.selfCheck) -> SelfCheck.refusal(t.selfCheck)
            t.sensorStatus == null || t.sensorStatus and 7 != 0 -> tr("bumper or lift active", "pare-chocs ou soulèvement actif")
            Point(x, y).distance(approach) > APPROACH_M ->
                tr("point in front of the base not reached (%.0f cm)", "point devant la base non atteint (%.0f cm)").format(Point(x, y).distance(approach) * 100)
            else -> null
        }
    }
}
