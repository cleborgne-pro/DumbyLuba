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
 * Names: table of the Mammotion app (`BlockErrorBeanChangeUtils`), as listed by Mammotion-HA (`SELF_CHECK_STATES`):
 * the three values observed above match it (11 emergency stop, 23 non-working hours, 25 outside the task area, which
 * is what [OffMapLatch] found on 2026-10-20). 41 is "weak satellite signal": on 2026-10-15 it showed on a single report
 * after a pivot; on 2026-10-22 it lasted and the robot stayed stuck, the app advising "STOP then Start", useless
 * for a positioning condition.
 *
 * Positioning conditions ([positioning]: 40, 41, 42, 99): nothing to unlock, the robot refuses to work until its
 * positioning comes back. The joystick (blades OFF, driver watching the robot) stays allowed, to bring it under open
 * sky; the mission waits up to [fr.luba.poc.simulation.ProtectionPause.POSITIONING_WAIT_MS] before giving up.
 *
 * Any other value, or a missing value, is still treated as an active protection.
 */
object SelfCheck {
    val NOMINAL: Set<Long> = setOf(0L, 10L)
    const val LOCKED = 11L
    const val FORBIDDEN_TIME = 23L
    const val WEAK_SATELLITE = 41L

    /** RTK station not ready (40), weak satellite signal (41), positioning failure (42), weak RTK signal (99). */
    val POSITIONING: Set<Long> = setOf(40L, WEAK_SATELLITE, 42L, 99L)

    /** Positioning condition: the robot refuses to work until its positioning comes back; nothing to unlock. */
    fun positioning(value: Long?): Boolean = value != null && value in POSITIONING

    /** Blade-OFF joystick allowed: nominal, or positioning condition (driving does not need RTK, bumper guards kept). */
    fun joystickAllowed(value: Long?): Boolean = nominal(value) || positioning(value)

    /** Name of the condition in the Mammotion app, null if not listed. */
    fun name(value: Long?): String? = when (value) {
        11L -> tr("emergency stop", "arrêt d'urgence")
        12L -> tr("stop button", "bouton STOP")
        13L -> tr("safety key missing", "clé de sécurité absente")
        14L -> tr("lifted", "soulevé")
        15L -> tr("tilted", "incliné")
        16L -> tr("tipped over", "renversé")
        17L -> tr("bumper missing", "pare-chocs absent")
        20L -> tr("rain", "pluie")
        21L -> tr("low battery", "batterie faible")
        23L -> tr("non-working hours", "hors plage horaire")
        24L -> tr("RTK station moved", "station RTK déplacée")
        25L -> tr("outside the task area", "hors de la zone de travail")
        26L -> tr("wheel motor error", "erreur moteur de roue")
        27L -> tr("blade motor overheated", "moteur de coupe en surchauffe")
        29L -> tr("charging station moved", "base de charge déplacée")
        30L -> tr("no task area", "aucune zone de travail")
        31L -> tr("night protection", "protection de nuit")
        34L -> tr("rain protection", "protection pluie")
        40L -> tr("RTK station not ready", "station RTK pas prête")
        41L -> tr("weak satellite signal", "signal satellite faible")
        42L -> tr("positioning failure", "défaillance du positionnement")
        43L -> tr("iNavi service unavailable", "service iNavi indisponible")
        99L -> tr("weak RTK signal", "signal RTK faible")
        else -> null
    }

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
        in POSITIONING -> tr("Positioning not available: ${name(value)} (self-check $value). Nothing to unlock: the robot " +
            "refuses to work until its positioning comes back; wait, or drive it under open sky with the joystick (blades OFF)",
            "Positionnement indisponible : ${name(value)} (self-check $value). Rien à déverrouiller : le robot " +
            "refuse de travailler tant que son positionnement n'est pas revenu ; attendre, ou l'amener à découvert au joystick (lames OFF)")
        else -> name(value)?.let { tr("Protection / self-check active: $it ($value)", "Protection / self-check actif : $it ($value)") }
            ?: tr("Protection / self-check active ($value)", "Protection / self-check actif ($value)")
    }
}
