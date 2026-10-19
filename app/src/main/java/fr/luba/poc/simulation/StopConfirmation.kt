package fr.luba.poc.simulation

import fr.luba.poc.map.Point

/**
 * Physical stop confirmed by two positions **published after the STOP takes effect**, at most [STILL_M] apart.
 *
 * Mowing of 2026-10-10 (15:27, battery return at 20 %): the check required a new position 600 ms after the STOP.
 * The LUBA 2 only publishes its position at 1 Hz and reacts ≈ 0.6 s after the command: the next position had not
 * arrived yet, and the previous one showed the robot in full deceleration (25 cm travelled); stop "Arrêt physique
 * non confirmé" without a real fault.
 *
 * [observe]: `true` = stationary confirmed; `false` = no confirmation within [timeoutMs];
 * `null` = wait for the next position.
 */
class StopConfirmation(private val stopAt: Long, private val commandDelayMs: Long = DeadReckoning.ROUTE_COMMAND_DELAY_MS,
                       private val timeoutMs: Long = TIMEOUT_MS) {
    companion object {
        /** LUBA 2 deceleration after the STOP takes effect. */
        const val SETTLE_MS = 300L
        /** Tolerated gap between two stationary positions (RTK noise). */
        const val STILL_M = 0.03
        /** Two positions at 1 Hz after delay and deceleration, plus one as margin if the robot was still sliding. */
        const val TIMEOUT_MS = 5000L
    }
    private var reference: Point? = null
    private var referenceAt = Long.MIN_VALUE
    /** Last movement measured between two positions after the stop (log). */
    var lastMoveM = Double.NaN
        private set

    fun observe(now: Long, fixAt: Long, position: Point): Boolean? {
        require(position.x.isFinite() && position.y.isFinite())
        if (fixAt >= stopAt + commandDelayMs + SETTLE_MS && fixAt != referenceAt) {
            reference?.let { previous ->
                lastMoveM = previous.distance(position)
                if (lastMoveM <= STILL_M) return true
            }
            reference = position; referenceAt = fixAt
        }
        return if (now - stopAt >= timeoutMs) false else null
    }
}
