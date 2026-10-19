package fr.luba.poc.weather

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.min
import fr.luba.poc.i18n.tr

/** Hourly garden weather (Open-Meteo, model): start time (s), temperature, dew point, humidity, rain, vapour pressure deficit. */
data class HourlyWeather(val timeSec: Long, val temperatureC: Double, val dewPointC: Double, val humidityPct: Double,
                         val precipitationMm: Double, val vpdKpa: Double) {
    /** Saturated air: dew or mist on the grass. */
    val saturated: Boolean get() = humidityPct >= GrassWetness.SATURATED_HUMIDITY || temperatureC - dewPointC <= GrassWetness.DEW_SPREAD_C

    companion object {
        /** Open-Meteo `hourly` response (`timeformat=unixtime`); an incomplete hour is ignored. */
        fun parse(json: String): List<HourlyWeather> {
            val h = JSONObject(json).getJSONObject("hourly")
            val t = h.getJSONArray("time")
            fun column(name: String) = h.optJSONArray(name)
            val temp = column("temperature_2m"); val dew = column("dew_point_2m"); val rh = column("relative_humidity_2m")
            val rain = column("precipitation"); val vpd = column("vapour_pressure_deficit")
            return (0 until t.length()).mapNotNull { i ->
                fun value(a: org.json.JSONArray?) = if (a == null || i >= a.length() || a.isNull(i)) null else a.getDouble(i)
                HourlyWeather(t.getLong(i), value(temp) ?: return@mapNotNull null, value(dew) ?: return@mapNotNull null,
                    value(rh) ?: return@mapNotNull null, value(rain) ?: 0.0, value(vpd) ?: 0.0)
            }.sortedBy { it.timeSec }
        }
    }
}

/**
 * Wet grass: index from 0 (dry) to 1 (wet), computed from the hourly garden weather.
 *
 * Mowing of 2026-10-15 in Jardin d'Eden: no rain for 3 days, but saturated air from 6:00 to 9:00 (98 % humidity, dew
 * point 0.3 °C from the temperature), hence dew; drying from 10:00 (wind, first sun). Pivot slides went from 20–45 cm
 * at 9:00 to about 14 cm at 10:37 (different versions: just an indication).
 *
 * - **Rain**: total of the last 72 hours weighted by age (half-life ≈ [RAIN_DECAY_H] × ln 2), full at [RAIN_FULL_MM].
 * - **Dew**: last saturated hour (humidity ≥ [SATURATED_HUMIDITY] % or dew point spread ≤ [DEW_SPREAD_C]), then drying
 *   measured by the vapour pressure deficit accumulated since (kPa·h), index divided by e every [DRYING_KPA_H].
 *
 * The index used is the larger of the two. It converts the measured slides to their dry-grass equivalent
 * ([slideFactor]), then predicts them for the current grass.
 */
data class GrassWetness(val index: Double, val rainMm: Double, val dewIndex: Double, val rainIndex: Double, val reason: String, val atSec: Long) {
    companion object {
        const val SATURATED_HUMIDITY = 96.0
        const val DEW_SPREAD_C = 0.6
        const val RAIN_DECAY_H = 12.0
        const val RAIN_FULL_MM = 2.0
        const val DRYING_KPA_H = 0.3
        /** Dew forgotten beyond this (previous night). */
        const val DEW_MEMORY_H = 18
        /**
         * Slide on wet grass relative to dry grass: 1 + [WET_SLIDE_GAIN] × index. Provisional value (on 2026-10-15, a
         * ratio of about 2 to 3 between 9:00 and 10:37), to be recalibrated on recorded mowings.
         */
        const val WET_SLIDE_GAIN = 1.0
        /** Older index: grass state unknown, no correction. */
        const val STALE_MS = 3 * 3600_000L

        /** Index at [nowSec] (current hour included), or null without weather covering that hour. */
        fun of(hours: List<HourlyWeather>, nowSec: Long): GrassWetness? {
            val past = hours.filter { it.timeSec <= nowSec }
            val current = past.lastOrNull()?.takeIf { nowSec - it.timeSec < 2 * 3600 } ?: return null
            val rain = past.filter { nowSec - it.timeSec <= 72 * 3600 }
                .sumOf { it.precipitationMm.coerceAtLeast(0.0) * exp(-((nowSec - it.timeSec) / 3600.0) / RAIN_DECAY_H) }
            val rainIndex = min(1.0, rain / RAIN_FULL_MM)
            val recent = past.filter { nowSec - it.timeSec <= DEW_MEMORY_H * 3600L }
            val lastSaturated = recent.indexOfLast { it.saturated }
            val dewIndex = when {
                lastSaturated < 0 -> 0.0
                lastSaturated == recent.lastIndex -> 1.0
                else -> exp(-recent.drop(lastSaturated + 1).sumOf { it.vpdKpa.coerceAtLeast(0.0) } / DRYING_KPA_H)
            }
            val index = maxOf(rainIndex, dewIndex)
            val reason = when {
                index < 0.1 -> tr("dry grass", "herbe sèche")
                rainIndex >= dewIndex -> tr("recent rain (%.1f mm weighted)", "pluie récente (%.1f mm pondérés)").format(fr.luba.poc.i18n.I18n.locale, rain)
                lastSaturated == recent.lastIndex -> tr("saturated air: dew", "air saturé : rosée")
                else -> tr("dew drying", "rosée en train de sécher")
            }
            return GrassWetness(index, rain, dewIndex, rainIndex, reason, nowSec)
        }

        /** Ratio slide / slide on dry grass for index [index]. */
        fun slideFactor(index: Double): Double = 1.0 + WET_SLIDE_GAIN * index.coerceIn(0.0, 1.0)
    }
}
