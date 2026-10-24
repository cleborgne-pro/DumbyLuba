package fr.luba.poc.history

import fr.luba.poc.map.MowingEstimate

/**
 * Remaining time, end time and remaining recharges of a running mowing, recomputed from what this mowing actually
 * measured ([MowingRecord.totals]): cutting metres per active second (links, bypasses, pauses and incidents included),
 * battery points per active second and per charging second. Until enough is measured ([MIN_ACTIVE_S],
 * [MIN_DONE_M], [MIN_BATTERY_USED], [MIN_CHARGING_S]), the values frozen in the forecast at launch fill in. Same
 * recharge rule as the forecast and the robot ([MowingEstimate.fromDistance], [fr.luba.poc.simulation.FinishPolicy]).
 * Telemetry and task record only: identical whether the robot is driven directly or through the gateway.
 */
object LiveEta {
    const val MIN_ACTIVE_S = 180.0
    const val MIN_DONE_M = 10.0
    const val MIN_BATTERY_USED = 3.0
    const val MIN_CHARGING_S = 300.0

    data class Eta(
        val remainingSeconds: Double, val endAtMs: Long, val rechargesLeft: Int,
        /** Cutting speed measured on this mowing (false: forecast speed, start of the mowing). */
        val measuredSpeed: Boolean,
        /** Cut metres per active second used (transits and incidents included). */
        val cutMetresPerSecond: Double,
    )

    /**
     * [battery]: current level; [cutProgress]: share of the cutting metres done ([fr.luba.poc.simulation.RobotRouteUi.cutProgress]);
     * [charging]: robot on the base charging, up to [chargeTarget] (resume threshold by default). Null without a usable
     * forecast or cutting length.
     */
    fun of(record: MowingRecord, battery: Int?, cutProgress: Double?, nowMs: Long,
           charging: Boolean = false, chargeTarget: Int? = null): Eta? {
        val total = record.cuttingMeters.takeIf { it.isFinite() && it > 0 } ?: return null
        val f = record.forecast; val t = record.totals
        val done = ((cutProgress?.takeIf { it.isFinite() }?.times(total)) ?: record.doneMeters).coerceIn(0.0, total)
        val remaining = total - done
        // Forecast: the whole route (cutting and transits) at the planned speed, adjusted by the calibration.
        val forecastSeconds = f.travelSeconds * f.timeFactor
        val measured = t.activeSeconds >= MIN_ACTIVE_S && done >= MIN_DONE_M
        val speed = when {
            measured -> done / t.activeSeconds
            forecastSeconds > 0 -> total / forecastSeconds
            else -> return null
        }
        val autonomy = if (t.batteryUsed >= MIN_BATTERY_USED && t.activeSeconds >= MIN_ACTIVE_S) 100.0 / (t.batteryUsed / t.activeSeconds) / 60
            else f.autonomyMinutes
        val charge = if (t.chargingSeconds >= MIN_CHARGING_S && t.batteryCharged > 0) 100.0 / (t.batteryCharged / t.chargingSeconds) / 60
            else f.chargeMinutes
        if (!(speed.isFinite() && speed > 0 && autonomy.isFinite() && autonomy > 0 && charge.isFinite() && charge > 0)) return null
        val low = record.returnPercent.toDouble(); val high = record.resumePercent.toDouble()
        if (low >= high) return null
        val level = (battery ?: record.resumePercent).toDouble().coerceIn(0.0, 100.0)
        // On the base: the charge up to its target comes first, then the robot leaves with that level.
        val target = (chargeTarget ?: record.resumePercent).toDouble().coerceIn(0.0, 100.0)
        val waiting = if (charging && level < target) (target - level) * charge * 60 / 100 else 0.0
        val leaving = if (charging) maxOf(level, target) else level
        // Remaining cut metres at the measured cut speed: the estimate counts them at 1 m/s.
        val estimate = MowingEstimate.fromDistance(remaining / speed, 1.0, autonomy, charge, leaving, low, high)
        val seconds = waiting + estimate.totalSeconds
        return Eta(seconds, nowMs + (seconds * 1000).toLong(), estimate.recharges, measured, speed)
    }
}
