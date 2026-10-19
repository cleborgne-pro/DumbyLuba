package fr.luba.poc.simulation

/**
 * Automatic resume of a mowing whose link with the gateway drops during the recharge cycle: return at the return
 * threshold, docking through the official Mammotion return and charging, up to leaving the base (robot off the base,
 * which can no longer start again from there: manual resume).
 *
 * Test of 2026-10-16, 16:28:54: robot docking with Mammotion (state 14) in front of the base, gateway Wi-Fi at
 * -73 dBm, gateway silent for 2 s. The link was kept neither during docking nor between two tracking runs: loss
 * declared, mowing interrupted at 60 %, and nothing restarted it once the robot was docked (charge contact at
 * 16:29:05) and charged.
 *
 * The link is now kept during docking as during charging, and if it drops anyway the mowing resumes by itself, from
 * its saved progress, as soon as the robot is charging on the base and the link is back. The resume follows the usual
 * path ([MissionCheckpoint]): charge up to the resume threshold if needed, leave the base, link to the interrupted
 * point. A refusal (working hours, map, position…) leaves the resume manual.
 */
object DockResume {
    /** Resume window: docking (5 min at most), gateway restarts and link stabilisation. */
    const val WINDOW_MS = 15 * 60_000L
    const val MAX_ATTEMPTS = 3
    /** Link ready for at least this delay before restarting (state and position reports back). */
    const val SETTLE_MS = 8_000L
    /** Last robot report no older than this delay. */
    const val FRESH_REPORT_MS = 2_000L

    /** Interruption resumed by itself: recharge cycle of a tracked mowing, progress saved. */
    fun wanted(rechargeCycle: Boolean, routeRunning: Boolean, hasCheckpoint: Boolean): Boolean =
        rechargeCycle && routeRunning && hasCheckpoint

    /**
     * Failure coming from the link with the gateway and not from the robot: Wi-Fi outage in progress ([outage]) or a
     * link-lost message (French or English). A protection, a contact or a docking refused by the robot are not resumed.
     */
    fun linkCause(message: String?, outage: Boolean): Boolean {
        if (outage) return true
        val text = message ?: return false
        return LINK_MESSAGES.any { text.contains(it, ignoreCase = true) }
    }

    /** Robot on the base, docking finished: charge contact and state other than Mammotion return. */
    fun docked(robotState: Int?, chargeState: Int?): Boolean = chargeState in CHARGING && NativeDocking.settled(robotState)

    private val CHARGING = setOf(1, 2)
    private val LINK_MESSAGES = listOf("Wi-Fi de la passerelle", "Liaison perdue", "BLE interrompu", "Liaison BLE interrompue", "Liaison fermée",
        "gateway Wi-Fi", "Link lost", "BLE interrupted", "BLE link interrupted", "Link closed")
}
