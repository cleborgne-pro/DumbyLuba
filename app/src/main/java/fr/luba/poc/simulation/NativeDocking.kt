package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr

/**
 * Docking of a route handed over to the official Mammotion return (action 5) from the approach point in front of the
 * base. Only the state reports received after the request ([requestedAt], monotonic clock) count: RETURN state (14)
 * expected within [startTimeoutMs], then charge contact (1 or 2). A state leaving RETURN without charge contact within
 * the following [chargeGraceMs], or a wait beyond [dockTimeoutMs], is a failure.
 */
class NativeDocking(
    private val requestedAt: Long,
    private val startTimeoutMs: Long = 15_000,
    private val dockTimeoutMs: Long = 5 * 60_000,
    private val chargeGraceMs: Long = 5_000,
) {
    sealed interface Step {
        data object Waiting : Step
        data object Returning : Step
        data object Docked : Step
        data class Failed(val reason: String) : Step
    }

    private var returning = false
    private var leftAt = 0L

    fun observe(now: Long, deviceAt: Long, robotState: Int?, chargeState: Int?): Step {
        val fresh = deviceAt > requestedAt
        if (fresh && chargeState in CHARGING) return Step.Docked
        if (fresh && robotState == RETURNING_STATE) { returning = true; leftAt = 0L }
        else if (returning && fresh) {
            if (leftAt == 0L) leftAt = now
            if (now - leftAt > chargeGraceMs) return Step.Failed(tr("Mammotion docking interrupted (state $robotState) without charge contact",
                "Accostage Mammotion interrompu (état $robotState) sans contact de charge"))
        }
        if (!returning && now - requestedAt > startTimeoutMs) return Step.Failed(tr("Mammotion return not started by the robot (state $robotState)",
            "Retour Mammotion non démarré par le robot (état $robotState)"))
        if (now - requestedAt > dockTimeoutMs) return Step.Failed(tr("Mammotion docking too long: no charge contact within ${dockTimeoutMs / 60_000} min",
            "Accostage Mammotion trop long : aucun contact de charge en ${dockTimeoutMs / 60_000} min"))
        return if (returning) Step.Returning else Step.Waiting
    }

    companion object {
        const val RETURNING_STATE = 14
        /** Maximum wait for leaving the return state (14) once the charge contact is reported. */
        const val SETTLE_TIMEOUT_MS = 30_000L

        /**
         * Docking finished on the robot side: the charge contact may come one second before leaving the RETURN state
         * (2026-10-15, 13:59:18 → 13:59:19); the recharge, which requires a station state, waits for that exit.
         */
        fun settled(robotState: Int?): Boolean = robotState != null && robotState != RETURNING_STATE

        /** Official return already in progress or robot already charging (docking requested by the gateway without a pilot). */
        fun inProgress(robotState: Int?, chargeState: Int?): Boolean = robotState == RETURNING_STATE || chargeState in CHARGING
        private val CHARGING = setOf(1, 2)
    }
}
