package fr.luba.poc.simulation

/**
 * End of mowing without a needless recharge (request of 2026-10-15), same rule for the estimate, the simulation and
 * the actual mowing:
 *
 * - **at the return threshold**, the robot finishes the mowing then returns if the energy estimated for the rest of the
 *   route (remaining cutting, links and final return to base) lets it arrive with at least [ARRIVAL_FLOOR_PERCENT];
 *   otherwise it returns to recharge as before. During this end, the emergency return triggers at
 *   [ARRIVAL_FLOOR_PERCENT];
 * - **while charging**, it leaves before the resume threshold as soon as the battery covers the rest (leaving the base,
 *   link, remaining cutting and final return) arriving at the return threshold or above: a small remainder no longer
 *   waits for 80 or 90 %.
 *
 * The energy is the estimated duration over the autonomy (100 % in [autonomyMinutes]), increased by [ENERGY_MARGIN]:
 * pivots, slides and bypasses consume more than the planned route.
 */
object FinishPolicy {
    /** Minimum estimated battery on arrival at the base when the mowing ends below the return threshold. */
    const val ARRIVAL_FLOOR_PERCENT = 10
    /** Increase of the estimated energy. */
    const val ENERGY_MARGIN = 1.25

    /** Battery points needed for [seconds] of operation, margin included. */
    fun neededPercent(seconds: Double, autonomyMinutes: Double): Double {
        require(seconds.isFinite() && seconds >= 0 && autonomyMinutes.isFinite() && autonomyMinutes > 0)
        return seconds / 60.0 / autonomyMinutes * 100.0 * ENERGY_MARGIN
    }

    /** At the return threshold: the battery is enough to finish and return, estimated arrival at least [ARRIVAL_FLOOR_PERCENT]. */
    fun finishWithoutRecharge(battery: Double, neededPercent: Double): Boolean = battery - neededPercent >= ARRIVAL_FLOOR_PERCENT

    /** While charging: the battery covers the rest, arriving at the return threshold [low] or above. */
    fun earlyDeparture(battery: Double, neededPercent: Double, low: Double): Boolean = battery - neededPercent >= low

    /** Mammotion docking from the approach point (35 s on 2026-10-15 at 16:19), with margin. */
    const val DOCKING_SECONDS = 60.0

    /** Duration of the rest: [meters] at the estimate speed [speedMps], duration factor [timeFactor], docking included. */
    fun restSeconds(meters: Double, speedMps: Double, timeFactor: Double): Double {
        require(meters.isFinite() && meters >= 0 && speedMps.isFinite() && speedMps > 0 && timeFactor.isFinite() && timeFactor > 0)
        return meters / speedMps * timeFactor + DOCKING_SECONDS
    }

    /** Charge level sufficient to leave again: resume threshold, or less if the rest is short. */
    fun chargeTarget(neededPercent: Double, low: Double, high: Double): Double = minOf(high, low + neededPercent)
}
