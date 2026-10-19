package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr

/**
 * `dev.self_check_status` (field 12): semantics not documented upstream (PyMammotion does not interpret it).
 *
 * Only 0 and 10 allow movement. Observed on LUBA 2 (firmware 1.30.29.26) on 2026-10-11: change 10 → 11 at 16:38,
 * when the robot writes error 1008 "Robot is stuck" in its history; afterwards every speed command is refused by
 * the robot (manual mode accepted, 0 m travelled, error 2800 "Stop button triggered"). 11 is therefore an active
 * protection, even when `sys_status` is 11 (READY). The user confirms pressing the robot's STOP button: 11 = locked
 * after STOP (unlock: [StopUnlock]).
 *
 * 25: collision lock, published with the bumper contact ([CollisionLatch]).
 *
 * 23: forbidden time slot set in the Mammotion app (2026-10-15: change 10 → 23 at exactly 21:00:00, robot still on
 * charge, without command or sensor; the remote unlock has no effect). The robot refuses to work until the end of
 * that slot; nothing to unlock.
 *
 * Any other value, or a missing value, is still treated as an active protection.
 */
object SelfCheck {
    val NOMINAL: Set<Long> = setOf(0L, 10L)
    const val LOCKED = 11L
    const val FORBIDDEN_TIME = 23L

    /** Forbidden time slot of the Mammotion app: wait for its end or change it in that app. */
    fun forbiddenTime(value: Long?): Boolean = value == FORBIDDEN_TIME

    fun nominal(value: Long?): Boolean = value != null && value in NOMINAL

    /** Displayed refusal reason, with the raw value and what to do when known. */
    fun refusal(value: Long?): String = when (value) {
        LOCKED -> tr("Robot locked after STOP (self-check 11): tap \"Unlock\" on the map, or Grass then Start on the robot", "Robot verrouillé après STOP (self-check 11) : toucher « Déverrouiller » sur la carte, ou sur le robot Grass puis Start")
        FORBIDDEN_TIME -> tr("Outside the time slot set in the Mammotion app (self-check 23): the robot refuses to work " +
            "until the end of that slot; turn it off in the Mammotion app and use the DumbyLuba working hours",
            "Hors de la plage horaire réglée dans l'app Mammotion (self-check 23) : le robot refuse de travailler " +
            "jusqu'à la fin de cette plage ; la désactiver dans l'app Mammotion et utiliser celle de DumbyLuba")
        CollisionLatch.VALUE -> tr("Collision lock after a contact (self-check 25): tap \"Unlock\" on the map (30 cm back-off, blades OFF), or free the robot then STOP and Start", "Verrou de collision après un contact (self-check 25) : toucher « Déverrouiller » sur la carte (recul de 30 cm, lames OFF), ou dégager le robot puis STOP et Start")
        null -> tr("Protection / self-check unknown (no telemetry)", "Protection / self-check inconnu (télémétrie absente)")
        else -> tr("Protection / self-check active ($value)", "Protection / self-check actif ($value)")
    }
}
