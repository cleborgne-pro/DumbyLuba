package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.simulation.FinishPolicy
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimPhase
import kotlin.math.max

/** A recharge of the simplified estimate: run time done when it starts, and its charging time. */
data class PlannedRecharge(val atSeconds: Double, val chargeSeconds: Double)

/** Simplified estimate when the engine cannot work out the returns to the station. */
data class MowingEstimate(val totalSeconds: Double, val recharges: Int) {
    companion object {
        /** The prepared engine includes links, pivots, returns, docking and recharges. */
        fun fromSimulation(engine: MowingSimulation, checkActive: () -> Unit = {}): MowingEstimate {
            var state = engine.start()
            repeat(10080) {
                checkActive()
                if (state.phase == SimPhase.COMPLETE) return MowingEstimate(state.elapsed,state.cycles)
                check(state.phase != SimPhase.BLOCKED) { state.log.lastOrNull() ?: tr("Route estimate blocked", "Estimation du parcours bloquée") }
                state = engine.advance(state,60.0,simulateSignals=false)
            }
            error(tr("Estimate longer than seven days", "Estimation supérieure à sept jours"))
        }

        fun fromDistance(meters: Double, speedMps: Double, autonomyMinutes: Double, chargeMinutes: Double, battery: Double, lowerPercent: Double = 20.0, upperPercent: Double = 90.0): MowingEstimate {
            val schedule = rechargeSchedule(meters, speedMps, autonomyMinutes, chargeMinutes, battery, lowerPercent, upperPercent)
            return MowingEstimate(meters / speedMps + schedule.sumOf { it.chargeSeconds }, schedule.size)
        }

        /**
         * Recharges of [fromDistance] in order: run time already done when each one starts and its charging time. Lets
         * an interrupted mowing be compared with the recharges actually due at its progress.
         */
        fun rechargeSchedule(meters: Double, speedMps: Double, autonomyMinutes: Double, chargeMinutes: Double, battery: Double,
                             lowerPercent: Double = 20.0, upperPercent: Double = 90.0): List<PlannedRecharge> {
            require(meters.isFinite() && meters >= 0 && speedMps.isFinite() && speedMps > 0)
            require(autonomyMinutes.isFinite() && autonomyMinutes > 0 && chargeMinutes.isFinite() && chargeMinutes > 0)
            require(battery.isFinite() && battery in 0.0..100.0)
            require(lowerPercent.isFinite() && upperPercent.isFinite() && lowerPercent >= 0 && upperPercent <= 100 && lowerPercent < upperPercent)
            // Same consumption as the simulation: 100 % over the configured autonomy.
            val secondsPerPercent = autonomyMinutes * 60 / 100
            val travel = meters / speedMps
            // Same rule as the robot ([FinishPolicy]): at the low threshold, finish without recharging if the remainder
            // allows it; otherwise recharge up to the high threshold, or less when the remainder is short.
            fun needed(seconds: Double) = FinishPolicy.neededPercent(seconds, autonomyMinutes)
            var b = battery; var remaining = travel
            val recharges = ArrayList<PlannedRecharge>()
            while (true) {
                val usable = max(0.0, b - lowerPercent) * secondsPerPercent
                if (remaining <= usable) break
                remaining -= usable; b = minOf(b, lowerPercent)
                if (FinishPolicy.finishWithoutRecharge(b, needed(remaining))) break
                if (recharges.size >= MAX_RECHARGES) break
                val target = max(b, FinishPolicy.chargeTarget(needed(remaining), lowerPercent, upperPercent))
                recharges += PlannedRecharge(travel - remaining, (target - b) * chargeMinutes * 60 / 100)
                b = target
            }
            return recharges
        }
        private const val MAX_RECHARGES = 1000
    }
}
