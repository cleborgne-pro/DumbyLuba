package fr.luba.poc.diagnostics

/**
 * Move to a point tracked by the gateway: a stop for route deviation or envelope outside the corridor is recovered
 * as in local tracking (STOP, fresh position, new path from the actual position). Any other safety stop (robot state,
 * progress, link, unconfirmed stop…) ends the move.
 *
 * The reasons arrive as text from the gateway, in the gateway phone's language: both languages are recognised.
 */
object RemoteNavOutcome {
    private val prefixes = listOf("Écart au trajet", "Route deviation", "Enveloppe robot hors du couloir", "Robot envelope outside the safe corridor")

    fun recoverable(reason: String): Boolean = prefixes.any { reason.startsWith(it) }
}
