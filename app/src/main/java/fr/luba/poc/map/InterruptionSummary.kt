package fr.luba.poc.map

import fr.luba.poc.i18n.tr

/**
 * Why a mowing stopped, as a short sentence for the resume panel; the full technical message stays available
 * ("Details"). On 2026-10-15 at 17:36 the panel showed "Trajet interrompu : Protection / état robot changé
 * (état 20, protection 25) : protection toujours active après 30 s (état 11, protection 25), arrêt maintenu".
 *
 * The stored reason may be in French (older records, French device) or in English: the prefix and the keywords below
 * match both languages, with the English texts emitted by the tracking layer.
 */
object InterruptionSummary {
    data class Summary(val label: String, val detail: String?)

    private val PREFIXES = listOf("Trajet interrompu : ", "Route interrupted: ")

    fun of(reason: String?): Summary {
        if (reason.isNullOrBlank()) return Summary(tr("App closed or phone restarted during mowing", "Application fermée ou téléphone redémarré pendant la tonte"), null)
        val detail = PREFIXES.fold(reason) { text, prefix -> text.removePrefix(prefix) }.trim()
        fun has(vararg words: String) = words.any { detail.contains(it, ignoreCase = true) }
        val label = when {
            has("Recul impossible", "Back-off impossible", "could not back off") -> tr("Obstacle contact: the robot could not back off", "Contact avec un obstacle : le robot n'a pas pu reculer")
            has("protection 25", "verrou de collision", "collision lock", "Contact bumper", "Contact pare-chocs", "Bumper contact",
                "contournements déjà effectués", "bypasses already done", "bypasses already made") ->
                tr("Obstacle contact", "Contact avec un obstacle")
            has("Soulèvement", "soulevé", "sans progression", "Plage trop longue", "bloquée depuis", "Lift", "lifted",
                "without progress", "Range too long", "blocked for") -> tr("Robot stuck: the wheels turn without moving forward", "Robot bloqué : les roues tournent sans avancer")
            has("Perte passerelle", "Passerelle injoignable", "Suivi perdu", "Wi-Fi", "passerelle", "Gateway lost",
                "Gateway unreachable", "Tracking lost", "gateway") -> tr("Link lost with the gateway", "Liaison perdue avec la passerelle")
            has("Perte BLE", "BLE interrompu", "timeout GATT", "GATT", "télémétrie", "périmé", "BLE lost", "BLE interrupted",
                "telemetry", "stale") -> tr("Link lost with the robot", "Liaison perdue avec le robot")
            has("STOP manuel", "Manual STOP") -> tr("Stopped with the STOP button", "Arrêtée avec le bouton STOP")
            has("Écart au trajet", "Enveloppe robot", "Sortie de trajectoire", "Dérive", "Route deviation", "Robot envelope",
                "Trajectory exit", "Route exit", "Drift") -> tr("Robot left its route", "Robot sorti de sa trajectoire")
            has("Batterie", "batterie", "Battery", "battery") -> tr("Battery too low", "Batterie trop faible")
            has("Protection", "protection") -> tr("Robot protection active", "Protection du robot active")
            else -> detail.split(" : ", ": ").first().take(80)
        }
        return Summary(label, detail.takeIf { it != label })
    }
}
