package fr.luba.poc.simulation

/**
 * Monitoring of the recharge at the return threshold (30 to 60 min, robot stationary on the base, no motion command)
 * and of the departure checks at the resume threshold. A brief incident — late robot report, gateway Wi-Fi outage,
 * state or charge contact misread once — no longer stops the mission; only an incident lasting more than [graceMs]
 * stops it. The departure (resume threshold) still requires a fresh, nominal state and the charge contact.
 */
class ChargeWatch(private val graceMs: Long = GRACE_MS) {
    sealed interface Step {
        /** Nothing abnormal. */
        data object Nominal : Step
        /** Incident observed for the first time: to be logged. */
        data class Started(val reason: String) : Step
        /** Incident in progress, still tolerated. */
        data class Ongoing(val reason: String, val ms: Long) : Step
        /** Incident over after [ms]. */
        data class Cleared(val reason: String, val ms: Long) : Step
        /** Incident lasting more than [graceMs]: the mission stops. */
        data class Failed(val reason: String, val ms: Long) : Step
    }

    private var since = -1L
    private var reason: String? = null

    /** [issue]: incident observed at [now] (monotonic clock), null if everything is normal. */
    fun observe(now: Long, issue: String?): Step {
        val previous = reason
        if (issue == null) {
            if (previous == null) return Step.Nominal
            val ms = now - since
            since = -1L; reason = null
            return Step.Cleared(previous, ms)
        }
        if (previous == null) { since = now; reason = issue; return Step.Started(issue) }
        // Reason changing during the same incident (late report then contact read again): the duration keeps running.
        reason = issue
        val ms = now - since
        return if (ms > graceMs) Step.Failed(issue, ms) else Step.Ongoing(issue, ms)
    }

    companion object {
        /** Longest Wi-Fi outage recovered during the tests (access point change of 2026-10-14: 25 s), with margin. */
        const val GRACE_MS = 120_000L
    }
}
