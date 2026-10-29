package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr

/**
 * Resting value of the bumper slot of `sensor_status` (bits 0–2) on a validated model, learnt on each connection.
 *
 * These bits are a health state (0 OK, 1 alert, 2–7 error or obstacle), never a contact: the LUBA 2 reports a contact
 * through code 1014 and self-check 25 ([CollisionLatch]), still enforced everywhere. A LUBA 2 AWD 3000X (`Luba-VP`,
 * firmware 1.30.31.19) reads 1 in every report, docked and while it drives itself back to its base, bumper free and no
 * error in its history ([DumbyLuba#1](https://github.com/cleborgne-pro/DumbyLuba/issues/1), logs of 2026-10-20 and
 * 2026-10-28): every guard took it for an alert, and joystick, routes, tests and mowing stayed refused for good.
 *
 * Rule: when the slot has read [ModelTrial.BUMPER_REST_ALERT] in every device report since the connection and a report
 * arrives with the robot ready ([READY]), no collision lock, no contact code received on this connection and no motion
 * driven by the app, that value becomes the resting value of this connection: the guards see the slot cleared
 * ([clear]). As soon as the slot reads another value (0, or an error/obstacle value 2–7), the resting value is
 * forgotten for the rest of the connection: an alert that appears afterwards (during a move in particular) stops and
 * refuses as before, until a new connection. The values 2–7 are never learnt. Unvalidated models keep their own rule
 * (the model trial, [ModelTrial.sensors]).
 */
class BumperRest {
    /** Resting value learnt on this connection: 0 (none) or [ModelTrial.BUMPER_REST_ALERT]. */
    var value: Int = 0
        private set

    /** The slot read [ModelTrial.BUMPER_REST_ALERT] in every report since the connection, without lock or contact. */
    private var steady = true

    enum class Change { LEARNT, FORGOTTEN }

    /** New connection: nothing learnt. */
    fun reset() { value = 0; steady = true }

    /**
     * Observes the `sensor_status` of a new device report. [idle]: no motion driven by the app (joystick, route, test);
     * [contactSeen]: a contact code (1014) received on this connection. Returns the change of the resting value, if any.
     */
    fun observe(sensors: Int?, robotState: Int?, selfCheck: Long?, idle: Boolean, contactSeen: Boolean): Change? {
        val slot = sensors?.let { it and 7 }
        if (slot != ModelTrial.BUMPER_REST_ALERT || CollisionLatch.latched(selfCheck) || contactSeen) {
            steady = false
            if (value == 0 || slot == value) return null
            value = 0
            return Change.FORGOTTEN
        }
        if (value != 0 || !steady || robotState != READY || !idle) return null
        value = slot
        return Change.LEARNT
    }

    companion object {
        /** MODE_READY: robot stopped and available (also its state on the base). */
        const val READY = 11

        /** [sensors] with the bumper slot cleared when it holds the resting value [rest] (other bits unchanged). */
        fun clear(rest: Int, sensors: Int?): Int? =
            if (sensors != null && rest != 0 && sensors and 7 == rest) sensors and 7.inv() else sensors

        /**
         * `sensor_status` seen by the safety guards on [name]: the resting value [rest] of this connection cleared on a
         * validated model; on another model the model trial rule ([ModelTrial.sensors]), unchanged.
         */
        fun guard(name: String?, sensors: Int?, rest: Int, now: Long): Int? =
            if (ModelSupport.motionValidated(name)) clear(rest, sensors) else ModelTrial.sensors(name, sensors, now)

        /**
         * Refusal or stop reason for a bumper slot that is not 0 in the guarded [sensors] (null: unknown). On a
         * validated model ([validated]), an alert (1) that remains was not learnt on this connection (it appeared afterwards,
         * or it was there from the connection with a collision lock, a contact or the robot not ready): the reason says
         * how to make it the resting value.
         */
        fun refusal(sensors: Int?, validated: Boolean = true): String {
            val slot = sensors?.let { it and 7 } ?: return tr("Bumper health alert", "Alerte de santé bumper")
            return when {
                slot == ModelTrial.BUMPER_REST_ALERT && validated -> tr(
                    "Bumper health alert (1), not taken as the resting value of this connection: check that the bumper moves freely. If the robot reports it all the time, disconnect and reconnect it while it is stopped and ready: a value present from the connection is taken as its resting value",
                    "Alerte de santé bumper (1), non retenue comme valeur de repos de cette connexion : vérifier que le pare-chocs bouge librement. Si le robot la signale en permanence, le déconnecter puis le reconnecter à l'arrêt, robot prêt : une valeur présente dès la connexion est prise comme sa valeur de repos")
                slot == ModelTrial.BUMPER_REST_ALERT -> tr("Bumper health alert (1)", "Alerte de santé bumper (1)")
                else -> tr("Bumper error or obstacle ($slot): free the bumper and check it", "Erreur ou obstacle pare-chocs ($slot) : dégager le pare-chocs et le vérifier")
            }
        }
    }
}
