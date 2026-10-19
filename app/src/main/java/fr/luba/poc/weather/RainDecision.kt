package fr.luba.poc.weather

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt
import fr.luba.poc.i18n.tr

/**
 * Open-Meteo model forecast in 15-minute steps. Each value is the total (mm) of the 15 minutes before the timestamp.
 * It is a forecast: it complements the radar and does not replace an observation.
 */
data class PrecipForecast(val fetchedAtMs: Long, val steps: List<Step>) {
    data class Step(val endSec: Long, val mm: Double)

    /** Forecast total between now and now + [minutes] (started steps included). */
    fun nextMm(nowSec: Long, minutes: Int): Double =
        steps.filter { it.endSec > nowSec && it.endSec - 15 * 60 < nowSec + minutes * 60 }.sumOf { it.mm }

    /** Total observed by the model over the past [minutes]. */
    fun pastMm(nowSec: Long, minutes: Int): Double =
        steps.filter { it.endSec <= nowSec && it.endSec > nowSec - minutes * 60 }.sumOf { it.mm }

    /** Minutes before the first step forecast at [mm] or more, null if none within the horizon. */
    fun firstRainMin(nowSec: Long, mm: Double = 0.1): Int? =
        steps.firstOrNull { it.endSec > nowSec && it.mm >= mm }?.let { (((it.endSec - 15 * 60) - nowSec) / 60.0).roundToInt().coerceAtLeast(0) }

    companion object {
        fun parse(json: String, fetchedAtMs: Long): PrecipForecast {
            val m = JSONObject(json).getJSONObject("minutely_15")
            val t = m.getJSONArray("time"); val p = m.getJSONArray("precipitation")
            val steps = (0 until minOf(t.length(), p.length())).mapNotNull { i ->
                if (p.isNull(i)) null else Step(t.getLong(i), p.getDouble(i))
            }
            return PrecipForecast(fetchedAtMs, steps)
        }
    }
}

enum class RainLevel(private val en: String, private val fr: String) {
    UNKNOWN("Weather unavailable", "Météo indisponible"),
    DRY("Dry", "Sec"),
    WATCH("Monitoring", "Surveillance"),
    IMMINENT("Rain imminent", "Pluie imminente"),
    RAIN("Rain", "Pluie"),
    DRYING("End of rain", "Fin de pluie");

    /** Display label in the current language. */
    val title: String get() = tr(en, fr)
}

/** Robot rain sensor; not decoded yet, so always unknown. */
enum class RainSensor { UNKNOWN, DRY, WET }

data class RainInputs(
    val nowMs: Long,
    val radar: List<FrameStats>,
    val forecast: PrecipForecast?,
    val sensor: RainSensor = RainSensor.UNKNOWN,
)

data class RainAssessment(
    val level: RainLevel,
    val reason: String,
    val localDbz: Int? = null,
    val etaMin: Int? = null,
    val radarAgeMin: Int? = null,
    val decidedAtMs: Long = 0,
) {
    /** Situation that warrants a warning during mowing (observation mode: no command to the robot). */
    val alertWhileMowing: Boolean get() = level == RainLevel.RAIN || level == RainLevel.IMMINENT
}

/** Adjustable thresholds, to be calibrated on real tests. */
data class RainThresholds(
    /** Rain that interrupts: ≈ 1 mm/h on two consecutive frames… */
    val rainDbz: Int = 23,
    /** … or ≈ 2.7 mm/h on a single frame. */
    val heavyDbz: Int = 30,
    val imminentMin: Int = 20,
    val watchKm: Double = 15.0,
    val forecastWatchMm: Double = 0.2,
    val forecastImminentMm: Double = 0.5,
    /** Dry period required before considering a resume possible. */
    val dryingMin: Int = 30,
    /** The radar publishes every 10 min, a few minutes late. */
    val radarStaleMin: Int = 30,
    val forecastStaleMin: Int = 90,
)

/**
 * Rain decision based on the observed radar, the forecast and (later) the robot sensor.
 * Hysteresis: after rain, [RainThresholds.dryingMin] minutes without a local echo are needed to go back to dry,
 * to avoid flip-flopping on intermittent showers.
 */
class RainMonitor(private val th: RainThresholds = RainThresholds()) {
    private var lastRainMs: Long? = null
    var current: RainAssessment = RainAssessment(RainLevel.UNKNOWN, tr("Waiting for weather data", "En attente des données météo"))
        private set

    fun update(input: RainInputs): RainAssessment {
        val nowSec = input.nowMs / 1000
        val frames = input.radar.sortedBy { it.timeSec }
        val latest = frames.lastOrNull()
        val ageMin = latest?.let { ((nowSec - it.timeSec) / 60.0).roundToInt() }
        val radarOk = latest != null && ageMin!! <= th.radarStaleMin
        val forecast = input.forecast?.takeIf { (input.nowMs - it.fetchedAtMs) / 60_000 <= th.forecastStaleMin }
        // The arrival is estimated from the frame time: its age is subtracted.
        val approach = if (radarOk) RadarAnalysis.approach(frames)?.let { a -> a.copy(etaMin = a.etaMin?.let { (it - ageMin!!).coerceAtLeast(0) }) } else null
        val local = if (radarOk) latest!!.localDbz else null
        val previousLocal = if (radarOk) frames.getOrNull(frames.size - 2)?.localDbz else null
        val next30 = forecast?.nextMm(nowSec, 30) ?: 0.0
        val next60 = forecast?.nextMm(nowSec, 60) ?: 0.0
        fun mmh(dbz: Int) = "%.1f mm/h".format(fr.luba.poc.i18n.I18n.locale, Reflectivity.rainRateMmH(dbz.toDouble()))

        val raining = local != null && (local >= th.heavyDbz || local >= th.rainDbz &&
            ((previousLocal ?: 0) >= RadarAnalysis.RAIN_DBZ || input.sensor == RainSensor.WET))
        if (raining) lastRainMs = latest!!.timeSec * 1000

        val result = when {
            !radarOk && forecast == null -> RainAssessment(RainLevel.UNKNOWN,
                if (latest == null) tr("Radar and forecast unavailable", "Radar et prévision indisponibles") else tr("Radar out of date (frame from $ageMin min ago), forecast unavailable", "Radar périmé (image de il y a $ageMin min), prévision indisponible"))
            raining -> RainAssessment(RainLevel.RAIN, tr("${Reflectivity.label(local).replaceFirstChar { it.uppercase() }} on the garden (≈ ${mmh(local!!)})", "${Reflectivity.label(local).replaceFirstChar { it.uppercase() }} sur le jardin (≈ ${mmh(local!!)})"), local)
            local != null -> RainAssessment(RainLevel.WATCH, tr("Weak radar echo on the garden (≈ ${mmh(local)}), to be confirmed", "Écho radar faible sur le jardin (≈ ${mmh(local)}), à confirmer"), local)
            approach != null && approach.approaching && approach.etaMin != null && approach.etaMin <= th.imminentMin ->
                RainAssessment(RainLevel.IMMINENT, tr("Rain %.0f km away, approaching at %.0f km/h: ≈ %d min (radar estimate)", "Pluie à %.0f km, se rapproche à %.0f km/h : ≈ %d min (estimation radar)")
                    .format(fr.luba.poc.i18n.I18n.locale, approach.distanceKm, approach.speedKmH, approach.etaMin), etaMin = approach.etaMin)
            radarOk && next30 >= th.forecastImminentMm && (latest!!.nearestKm ?: Double.MAX_VALUE) <= th.watchKm ->
                RainAssessment(RainLevel.IMMINENT, tr("Forecast %.1f mm over 30 min and rain %.0f km away", "Prévision %.1f mm sur 30 min et pluie à %.0f km")
                    .format(fr.luba.poc.i18n.I18n.locale, next30, latest.nearestKm), etaMin = forecast?.firstRainMin(nowSec))
            lastRainMs?.let { input.nowMs - it < th.dryingMin * 60_000L } == true ->
                RainAssessment(RainLevel.DRYING, tr("Rain ended ${(input.nowMs - lastRainMs!!) / 60_000} min ago, resume advised after ${th.dryingMin} min", "Pluie terminée depuis ${(input.nowMs - lastRainMs!!) / 60_000} min, reprise conseillée après ${th.dryingMin} min"))
            input.sensor == RainSensor.WET -> RainAssessment(RainLevel.WATCH, tr("Sensor wet but radar dry: monitoring", "Capteur humide mais radar sec : surveillance"))
            approach != null && approach.distanceKm <= th.watchKm -> RainAssessment(RainLevel.WATCH,
                if (approach.approaching && approach.etaMin != null) tr("Rain %.0f km away, may arrive in ≈ %d min", "Pluie à %.0f km, arrivée possible dans ≈ %d min").format(fr.luba.poc.i18n.I18n.locale, approach.distanceKm, approach.etaMin)
                else tr("Rain %.0f km from the garden", "Pluie à %.0f km du jardin").format(fr.luba.poc.i18n.I18n.locale, approach.distanceKm), etaMin = approach.etaMin)
            next60 >= th.forecastWatchMm -> RainAssessment(RainLevel.WATCH,
                tr("Forecast %.1f mm within the hour", "Prévision %.1f mm dans l'heure").format(fr.luba.poc.i18n.I18n.locale, next60), etaMin = forecast?.firstRainMin(nowSec))
            !radarOk -> RainAssessment(RainLevel.WATCH, tr("Radar unavailable; dry forecast only", "Radar indisponible ; prévision sèche seulement"))
            else -> RainAssessment(RainLevel.DRY,
                latest!!.nearestKm?.let { tr("No rain on the garden (nearest %.0f km away)", "Pas de pluie sur le jardin (la plus proche à %.0f km)").format(fr.luba.poc.i18n.I18n.locale, it) }
                    ?: tr("No rain within %.0f km", "Pas de pluie à moins de %.0f km").format(fr.luba.poc.i18n.I18n.locale, RadarAnalysis.SEARCH_RADIUS_M / 1000))
        }
        current = result.copy(radarAgeMin = ageMin, decidedAtMs = input.nowMs)
        return current
    }
}

/** Weather warning for a mowing of a given duration (before the start or in progress). */
data class MowingRainOutlook(val warn: Boolean, val message: String, val firstRainMin: Int? = null)

object MowingRainCheck {
    /** Forecast total from which rain is announced over the mowing duration. */
    const val TOTAL_MM = 0.2
    /** 15-min step considered rainy. */
    const val STEP_MM = 0.1
    const val FORECAST_STALE_MIN = 90

    /**
     * [durationMin]: estimated mowing duration (null if unknown: the coming hour is checked).
     * Observed rain (radar) takes precedence over the model forecast.
     */
    fun check(assessment: RainAssessment, forecast: PrecipForecast?, nowMs: Long, durationMin: Int?): MowingRainOutlook {
        val nowSec = nowMs / 1000
        val span = (durationMin ?: 60).coerceAtLeast(15)
        val spanLabel = if (durationMin == null) tr("within the hour", "dans l'heure") else tr("during the mowing (≈ ${duration(span)})", "pendant la tonte (≈ ${duration(span)})")
        when (assessment.level) {
            RainLevel.RAIN -> return MowingRainOutlook(true, tr("It is raining on the garden: ${assessment.reason.replaceFirstChar { it.lowercase() }}", "Il pleut sur le jardin : ${assessment.reason.replaceFirstChar { it.lowercase() }}"), 0)
            RainLevel.IMMINENT -> return MowingRainOutlook(true, tr("Rain imminent: ${assessment.reason.replaceFirstChar { it.lowercase() }}", "Pluie imminente : ${assessment.reason.replaceFirstChar { it.lowercase() }}"), assessment.etaMin)
            RainLevel.DRYING -> return MowingRainOutlook(true, tr("Recent rain: the grass is probably wet (${assessment.reason.replaceFirstChar { it.lowercase() }})", "Pluie récente : l'herbe est probablement mouillée (${assessment.reason.replaceFirstChar { it.lowercase() }})"))
            else -> {}
        }
        val f = forecast?.takeIf { (nowMs - it.fetchedAtMs) / 60_000 <= FORECAST_STALE_MIN }
            ?: return MowingRainOutlook(false, tr("Weather forecast unavailable: rain not checked $spanLabel", "Prévision météo indisponible : pluie non vérifiée $spanLabel"))
        val total = f.nextMm(nowSec, span)
        val first = f.steps.firstOrNull { it.endSec > nowSec && it.endSec - 15 * 60 < nowSec + span * 60 && it.mm >= STEP_MM }
        val horizonEnd = f.steps.maxOfOrNull { it.endSec } ?: nowSec
        val partial = if (horizonEnd < nowSec + span * 60) tr(" (forecast available until ${hhmm(horizonEnd)})", " (prévision disponible jusqu'à ${hhmm(horizonEnd)})") else ""
        if (total >= TOTAL_MM || first != null) {
            val inMin = first?.let { (((it.endSec - 15 * 60) - nowSec) / 60).toInt().coerceAtLeast(0) }
            val start = when { inMin == null -> tr("Rain forecast", "Pluie prévue"); inMin <= 0 -> tr("Rain forecast from now", "Pluie prévue dès maintenant"); else -> tr("Rain forecast in ≈ ${duration(inMin)} (around ${hhmm(nowSec + inMin * 60)})", "Pluie prévue dans ≈ ${duration(inMin)} (vers ${hhmm(nowSec + inMin * 60)})") }
            return MowingRainOutlook(true, tr("$start: %.1f mm $spanLabel$partial", "$start : %.1f mm $spanLabel$partial").format(fr.luba.poc.i18n.I18n.locale, total), inMin)
        }
        val radarNote = if (assessment.level == RainLevel.WATCH) " · ${assessment.reason}" else ""
        return MowingRainOutlook(false, tr("No rain forecast $spanLabel$partial$radarNote", "Pas de pluie prévue $spanLabel$partial$radarNote"))
    }

    fun duration(min: Int): String = if (min < 60) "$min min" else "${min / 60} h${(min % 60).takeIf { it > 0 }?.let { "%02d".format(it) } ?: ""}"
    private fun hhmm(sec: Long) = java.text.SimpleDateFormat("HH:mm", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(sec * 1000))
}
