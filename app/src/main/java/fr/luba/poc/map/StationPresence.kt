package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.components.CLOCK_LEAD_TOLERANCE_MS

/**
 * Robot relative to its charging station, read from the charging contact of the device report (`chargeState` 1 or 2
 * = contact established, as for docking detection). Decides which actions the "Charging station" dialogs offer:
 * leaving the station only makes sense from the base, recharging only away from it. Without a usable report (no link,
 * reading too old), both actions stay offered and the route itself checks the contact.
 */
enum class StationPresence {
    ON_BASE, AWAY, UNKNOWN;

    /** "Leave the station" is offered: robot on the base, or state unknown. */
    val canLeave: Boolean get() = this != AWAY
    /** "Recharge" is offered: robot away from the base, or state unknown. */
    val canRecharge: Boolean get() = this != ON_BASE

    companion object {
        fun of(chargeState: Int?): StationPresence = when (chargeState) {
            null -> UNKNOWN
            1, 2 -> ON_BASE
            else -> AWAY
        }

        /** Same, from a report no older than [maxAgeMs]: a frozen contact must not hide an action. */
        fun of(t: Telemetry?, maxAgeMs: Long, now: Long): StationPresence =
            if (t?.lastDeviceAt?.let { now - it in -CLOCK_LEAD_TOLERANCE_MS..maxAgeMs } == true) of(t.chargeState) else UNKNOWN
    }
}
