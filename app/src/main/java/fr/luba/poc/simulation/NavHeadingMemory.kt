package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.abs

/**
 * Heading gap ([DeadReckoning.headingOffset]) confirmed at the end of a drive, offered to the next chained drive. It is
 * only taken over while the robot has stayed where it stopped: recent, published position and published heading
 * unchanged (a robot pushed, lifted or turned by hand, or a heading realigned by the robot, is confirmed again).
 * [position] and [reportedHeading] are the published values (point 8 cm behind the centre, raw robot heading);
 * [supportM]: straight line over which the gap was measured ([DeadReckoning.headingSupportM]).
 */
data class NavHeadingMemory(val offset: Double, val supportM: Double, val atMs: Long, val position: Point, val reportedHeading: Double) {
    /** Reason why the gap cannot be taken over at [nowMs] from the published [position] and [reportedHeading], null if it can. */
    fun refusal(nowMs: Long, position: Point?, reportedHeading: Double?): String? = when {
        supportM < MIN_SUPPORT_M -> tr("heading measured over %.2f m only".format(supportM), "cap mesuré sur %.2f m seulement".format(supportM))
        nowMs - atMs > MAX_AGE_MS -> tr("previous drive ended ${(nowMs - atMs) / 1000} s ago", "trajet précédent terminé il y a ${(nowMs - atMs) / 1000} s")
        position == null || reportedHeading == null || !reportedHeading.isFinite() -> tr("position or heading unknown", "position ou cap inconnu")
        position.distance(this.position) > MAX_MOVE_M -> tr("robot moved %.2f m since".format(position.distance(this.position)), "robot déplacé de %.2f m depuis".format(position.distance(this.position)))
        abs(wrap180(reportedHeading - this.reportedHeading)) > MAX_TURN_DEGREES ->
            tr("heading changed by %.0f° since".format(abs(wrap180(reportedHeading - this.reportedHeading))), "cap changé de %.0f° depuis".format(abs(wrap180(reportedHeading - this.reportedHeading))))
        else -> null
    }

    private fun wrap180(degrees: Double) = ((degrees + 540) % 360 + 360) % 360 - 180

    companion object {
        /**
         * Straight line the gap must have been measured over: a short drive confirmed at its end over 15 cm (10° off
         * once on the bench) would hand its error to the next one instead of letting it measure its own.
         */
        const val MIN_SUPPORT_M = 0.4
        /** Start of the recording included (up to 10 s), with the waits for the robot state around it. */
        const val MAX_AGE_MS = 60_000L
        /** Coasting after the stop (≈ 0.6 s of command delay at up to 0.2 m/s) and a position published up to 1 s late. */
        const val MAX_MOVE_M = 0.3
        /** Published heading noise of a robot at rest; beyond it the robot turned or realigned its heading. */
        const val MAX_TURN_DEGREES = 5.0
    }
}
