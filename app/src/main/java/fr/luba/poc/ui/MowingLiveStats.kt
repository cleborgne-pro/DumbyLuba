package fr.luba.poc.ui

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.RobotRouteUi
import fr.luba.poc.i18n.tr
import java.util.Locale

/**
 * Indicators of the bar during a real mowing: **cutting** progress (metres of stripes mowed, excluding moves with the
 * blades stopped), actual blade state and height reported by the robot, battery and duration.
 *
 * Before 0.41.0, the bar only showed "Mowed x %" computed on the number of route edges: moves counted as much as a
 * stripe, and the percentage dropped back to 0 after each bypass or route resume (the remaining route was renumbered).
 * Nothing showed whether the blades were spinning or at which height.
 */
object MowingLiveStats {
    data class Stats(
        /** Mowed fraction (0..1): cut metres for a mowing, edges travelled otherwise. */
        val fraction: Float?,
        /** "Mowed 42 %" for a mowing, "Route 42 %" for a move or a survey. */
        val progress: String?,
        val blades: String,
        /** null: blade state unknown. */
        val bladesOn: Boolean?,
        val height: String?,
        val battery: String?,
        val duration: String?,
        /** "Recharge 1/2": recharges started since the task began, over the planned total (null with no forecast and no recharge). */
        val recharges: String? = null,
    )

    /** Bits 9–11 of `sensorStatus`: 0 = blades stopped, 1 = blades spinning (blade bench). */
    fun bladeBits(sensorStatus: Int?): Int? = sensorStatus?.let { (it shr 9) and 7 }

    fun of(t: Telemetry, route: RobotRouteUi, elapsedSeconds: Double? = null, expectedSeconds: Double? = null,
           recharges: Int? = null, expectedRecharges: Int? = null): Stats {
        val cut = route.cutProgress
        val fraction = when {
            cut != null -> cut.coerceIn(0.0, 1.0).toFloat()
            route.count > 0 -> (route.index.toFloat() / route.count).coerceIn(0f, 1f)
            else -> null
        }
        val progress = fraction?.let { "${if (cut != null) tr("Mowed", "Tondu") else tr("Route", "Trajet")} ${(it * 100).toInt()} %" }
        val bits = bladeBits(t.sensorStatus)
        val rpm = t.cutterRpm?.takeIf { it > 0 }
        val (blades, on) = when (bits) {
            1 -> (if (rpm != null) tr("Blades ${thousands(rpm)} rpm", "Lames ${thousands(rpm)} tr/min") else tr("Blades running", "Lames en marche")) to true
            0 -> tr("Blades stopped", "Lames arrêtées") to false
            null -> tr("Blades?", "Lames ?") to null
            else -> tr("Blades (state $bits)", "Lames (état $bits)") to null
        }
        val reported = t.bladeHeightMm
        val wanted = route.heightMm.takeIf { route.cutProgress != null }
        val height = when {
            reported == null -> null
            wanted != null && wanted != reported -> tr("Height $reported → $wanted mm", "Hauteur $reported → $wanted mm")
            else -> tr("Height $reported mm", "Hauteur $reported mm")
        }
        val battery = t.battery?.let { tr("Battery $it %", "Batterie $it %") }
        val duration = elapsedSeconds?.let { spent ->
            fr.luba.poc.history.MowingText.duration(spent) + (expectedSeconds?.takeIf { it > 0 }?.let { " / ≈ ${fr.luba.poc.history.MowingText.duration(it)}" } ?: "")
        }
        return Stats(fraction, progress, blades, on, height, battery, duration, rechargeText(recharges ?: route.rechargeCycles, expectedRecharges))
    }

    /**
     * Recharge in progress or done over the total: the total is the forecast made at launch, raised if the task needs more.
     * Nothing with no forecast and no recharge (short task that needs none).
     */
    fun rechargeText(done: Int, expected: Int?): String? {
        val total = maxOf(done, expected ?: 0)
        if (total <= 0) return null
        return "Recharge ${done.coerceAtLeast(0)}/$total"
    }

    /**
     * Live end of the mowing ([fr.luba.poc.history.LiveEta]): "End ≈ 16:40 · 1 h 05 left · 1 recharge left";
     * "(forecast)" while the speed is not measured on this mowing yet.
     */
    fun etaText(eta: fr.luba.poc.history.LiveEta.Eta, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val end = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(java.time.Instant.ofEpochMilli(eta.endAtMs))
        val left = fr.luba.poc.history.MowingText.duration(eta.remainingSeconds)
        val recharges = when (eta.rechargesLeft) {
            0 -> null
            1 -> tr("1 recharge left", "1 recharge restante")
            else -> tr("${eta.rechargesLeft} recharges left", "${eta.rechargesLeft} recharges restantes")
        }
        return listOfNotNull(tr("End ≈ $end", "Fin ≈ $end"), tr("$left left", "reste $left"), recharges).joinToString(" · ") +
            if (eta.measuredSpeed) "" else tr(" (forecast)", " (prévision)")
    }

    private fun thousands(value: Int): String = String.format(fr.luba.poc.i18n.I18n.locale, "%,d", value).replace(' ', ' ').replace(' ', ' ')
}
