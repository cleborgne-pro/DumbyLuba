package fr.luba.poc.protocol

/**
 * LUBA 2 collision lock: `self_check_status = 25`.
 *
 * Mowing of 2026-10-15 at 11:00:06 (Fold log `2d75d7fd`), robot in manual mode (state 20) on the return route: the
 * status report announcing self-check 25 is the one of the bumper contact. Code -1014 ("collision sensor triggered
 * during manual mowing") only arrives with the next read of the error history, polled every 500 ms (robot timestamp
 * 11:00:07, counter 875 → 876). Tracking therefore took the contact for an unknown protection: stop, passive 30 s wait,
 * robot left against the post. The lock never cleared (still active 10 min later), neither on zero speed nor on the
 * task cancel of the unlock. Also seen on 2026-10-14 (after a contact and some joystick, 20 min) and on 2026-10-13 (a
 * single report, during a pivot right next to a border).
 *
 * Chosen behaviour, without relaxing any other guard:
 * - a non-nominal protection (other than the STOP lock, 11) during tracking stops the robot at once, then waits for
 *   the contact code for at most [CONTACT_GRACE_MS]: a received contact goes through the usual back-off and bypass;
 * - the straight back-off along the trace, blades OFF, is allowed under the collision lock ([reverseAllowed]) when it
 *   follows a contact: it is the only way to move away from the object, and the robot remains free to refuse it;
 * - the rest of the route resumes only once the self-check is back to 0 or 10 ([CLEAR_WAIT_MS]).
 */
object CollisionLatch {
    const val VALUE = 25L

    /** Wait for the contact code after the protection appears (errors read every 500 ms, slow writes through the gateway). */
    const val CONTACT_GRACE_MS = 2_000L

    /** Wait for the lock to clear after the back-off. */
    const val CLEAR_WAIT_MS = 10_000L

    /** Unlock back-off from the map, along the travelled trace (the bypass backs off at least 30 cm). */
    const val UNLOCK_BACK_OFF_M = 0.30

    /** Protection that may announce a bumper contact: robot ready or manual, non-nominal self-check other than 11. */
    fun mayAnnounceContact(robotState: Int?, selfCheck: Long?): Boolean =
        robotState in setOf(11, 20) && selfCheck != null && !SelfCheck.nominal(selfCheck) && selfCheck != SelfCheck.LOCKED

    /** Known collision lock (25). */
    fun latched(selfCheck: Long?): Boolean = selfCheck == VALUE

    /**
     * State allowed during a straight back-off with blades OFF: ready or manual, nominal self-check; or, right after a
     * contact ([afterContact]), the collision lock (25).
     */
    fun reverseAllowed(robotState: Int?, selfCheck: Long?, afterContact: Boolean): Boolean =
        robotState in setOf(11, 20) && (SelfCheck.nominal(selfCheck) || (afterContact && latched(selfCheck)))
}
