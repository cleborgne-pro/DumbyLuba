package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr

/**
 * Unlock after the robot's STOP button was pressed (self-check 11), and re-arming of another protection reported by
 * the robot (non-nominal self-check, for example 25).
 *
 * No "unlock" command is documented (PyMammotion, protos). In an emergency stop, the Mammotion app only offers error
 * acknowledgement and task cancellation; the LUBA 2 manual describes a physical unlock (Grass button then Start). Our
 * attempt therefore sends only commands without movement: zero speed, task cancel (todev_taskctrl type 1 action 4),
 * error re-read; then waits for a self-check 0 or 10 in a report received after the write.
 *
 * Tests of 2026-10-14: self-check 25 appeared at 10:10:01 after a bumper contact, a back-off and 20 cm of joystick;
 * it stayed for 20 min and the app offered no way out (joystick and routes refused, button reserved for 11). It
 * dropped back to 10 after a STOP written by the gateway. The same sequence without movement is therefore offered for
 * any non-nominal protection; the joystick and route guards are unchanged (no value is added to [SelfCheck.NOMINAL]).
 */
object StopUnlock {
    const val TIMEOUT_MS = 8000L
    val PHYSICAL: String get() = tr("On the robot: press the Grass button, then Start", "Sur le robot : appuyer sur le bouton Grass (herbe), puis sur Start")

    val PROTECTION_PHYSICAL: String get() = tr("On the robot: free the robot, press STOP then Start", "Sur le robot : dégager le robot, appuyer sur STOP puis sur Start")

    fun locked(selfCheck: Long?) = selfCheck == SelfCheck.LOCKED

    /** Known active protection of the robot (value present and non-nominal): 11 or other (25…). */
    fun blocked(selfCheck: Long?) = selfCheck != null && !SelfCheck.nominal(selfCheck)

    /** What to do on site if the robot refuses the remote attempt. */
    fun physical(selfCheck: Long?) = if (locked(selfCheck)) PHYSICAL else PROTECTION_PHYSICAL

    /** Refusal reason, or null if the attempt can start. */
    fun refusal(ready: Boolean, busy: Boolean, ageMs: Long?, selfCheck: Long?, sensors: Int?): String? = when {
        !ready -> tr("Bluetooth connection required", "Connexion Bluetooth requise")
        busy -> tr("First stop the running route, test or manual control", "Arrêter d'abord le trajet, l'essai ou le pilotage en cours")
        ageMs == null || ageMs !in 0..2000 -> tr("Robot state stale: wait for telemetry", "État robot périmé : attendre la télémétrie")
        SelfCheck.nominal(selfCheck) -> tr("Robot already unlocked", "Robot déjà déverrouillé")
        !blocked(selfCheck) -> tr("Self-check missing: wait for telemetry", "Self-check absent : attendre la télémétrie")
        sensors == null || sensors and 7 != 0 -> tr("Bumper or lift active: free the robot first", "Pare-chocs ou soulèvement actif : dégager le robot d'abord")
        else -> null
    }

    /** Unlock proven: fresh status report received after the write, with a nominal self-check. */
    fun confirmed(writtenAt: Long, deviceAt: Long, ageMs: Long, selfCheck: Long?) =
        writtenAt > 0 && deviceAt > writtenAt && ageMs in 0..2000 && SelfCheck.nominal(selfCheck)
}
