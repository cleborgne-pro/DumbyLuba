package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.SelfCheck

/**
 * Tracking stop on a protection raised by the robot during mowing, which the mission can wait for
 * ([ProtectionPause]): edge where it happened and original reason.
 */
class RouteProtection(val index: Int, message: String) : IllegalStateException(message)

/**
 * Fleeting protection during mowing (2026-10-15 at 09:18:33: self-check 41 on a single report, back to 10 the next
 * second; the mowing had stopped at 84 % and the robot had stayed 31 minutes in place). Tracking still stops (STOP,
 * blades OFF); the mission then waits for the robot to clear its protection by itself, checks it stays clear for
 * [STABLE_MS], then resumes through the same link as after leaving the route ([DriftRecovery]).
 *
 * Excluded: lock after STOP (self-check 11, to be unlocked), forbidden working hours of the Mammotion app
 * (self-check 23, lasting until the end of the period), robot state other than ready or manual, protection still
 * active after [WAIT_MS], more than [MAX_PAUSES] pauses per mission. Lift and bumper contact have their own stops,
 * unchanged.
 */
object ProtectionPause {
    const val MAX_PAUSES = 5
    const val WAIT_MS = 30_000L
    const val STABLE_MS = 3_000L

    /** Parses the reason in French or English (the gateway and the pilot phone may use different languages). */
    private val pattern = Regex("""^Protection / (?:état robot changé \(état|robot state changed \(state) (-?\d+|null), protection (-?\d+|null)\)$""")

    /** Tracking stop reason (Fold or gateway) for the state [robotState] and the self-check [selfCheck]. */
    fun reason(robotState: Int?, selfCheck: Long?) = tr("Protection / robot state changed (state $robotState, protection $selfCheck)",
        "Protection / état robot changé (état $robotState, protection $selfCheck)")

    /** Stop due to a protection the robot can clear by itself: non-nominal self-check, not locked, robot ready or moving. */
    fun pausable(reason: String): Boolean {
        val match = pattern.matchEntire(reason) ?: return false
        val state = match.groupValues[1].toIntOrNull() ?: return false
        val check = match.groupValues[2].toLongOrNull() ?: return false
        return state in setOf(11, 20) && !SelfCheck.nominal(check) && check != SelfCheck.LOCKED && !SelfCheck.forbiddenTime(check)
    }

    /** Robot able to resume: ready or manual, nominal self-check. */
    fun cleared(robotState: Int?, selfCheck: Long?): Boolean = robotState in setOf(11, 20) && SelfCheck.nominal(selfCheck)

    /** Stop reason kept, or null if one more pause is allowed ([count] pauses already made). */
    fun refusal(count: Int): String? = if (count >= MAX_PAUSES) tr("$MAX_PAUSES protection pauses already made, stop kept",
        "$MAX_PAUSES pauses sur protection déjà faites, arrêt maintenu") else null

    /**
     * Wait tracking: `true` = protection cleared and stable for [STABLE_MS]; `false` = keep waiting;
     * exception = stop kept (lock, timeout exceeded).
     */
    class Wait(private val startedAt: Long) {
        private var clearedSince = Long.MIN_VALUE

        fun update(now: Long, robotState: Int?, selfCheck: Long?): Boolean {
            check(selfCheck != SelfCheck.LOCKED && !SelfCheck.forbiddenTime(selfCheck)) { SelfCheck.refusal(selfCheck) }
            if (!cleared(robotState, selfCheck)) clearedSince = Long.MIN_VALUE
            else if (clearedSince == Long.MIN_VALUE) clearedSince = now
            if (clearedSince != Long.MIN_VALUE && now - clearedSince >= STABLE_MS) return true
            check(now - startedAt < WAIT_MS) {
                tr("protection still active after ${WAIT_MS / 1000} s (state $robotState, protection $selfCheck), stop kept",
                    "protection toujours active après ${WAIT_MS / 1000} s (état $robotState, protection $selfCheck), arrêt maintenu") }
            return false
        }
    }
}
