package fr.luba.poc.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import fr.luba.poc.i18n.tr

@Composable
private fun levelColor(level: RainLevel): Color = when (level) {
    RainLevel.UNKNOWN -> MammotionColors.TextTertiary
    RainLevel.DRY -> MammotionColors.Green
    RainLevel.WATCH, RainLevel.DRYING -> MammotionColors.Info
    RainLevel.IMMINENT -> MammotionColors.Warning
    RainLevel.RAIN -> MammotionColors.Danger
}

/** Position published by the robot, offered when setting the garden. */
fun robotGeo(t: fr.luba.poc.protocol.Telemetry): GardenLocation? =
    if (t.geoLat != null && t.geoLon != null) GardenLocation(t.geoLat, t.geoLon, t.geoSource ?: "robot") else null

private fun hhmm(sec: Long) = SimpleDateFormat("HH:mm", fr.luba.poc.i18n.I18n.locale).format(Date(sec * 1000))

/** Short weather state label, for the map status pill. */
internal fun weatherPillLabel(a: RainAssessment, urgent: Boolean): String = when {
    urgent && a.level < RainLevel.IMMINENT -> tr("Rain forecast", "Pluie prévue")
    a.level == RainLevel.UNKNOWN -> tr("Weather —", "Météo —")
    a.level == RainLevel.IMMINENT && a.etaMin != null -> tr("Rain ≈ ${a.etaMin} min", "Pluie ≈ ${a.etaMin} min")
    else -> a.level.title
}

/**
 * Weather segment of the map status pill (dry, monitoring, rain imminent, rain…), after a separator.
 * A tap opens the details. Absent while the garden position is not set or monitoring is off.
 */
@Composable
fun WeatherPillSegment(mowing: Boolean, telemetry: fr.luba.poc.protocol.Telemetry) {
    val w by WeatherService.flow.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    if (w.location == null || !w.monitoring) return
    val a = w.assessment
    val outlook = if (mowing) MowingRainCheck.check(a, w.forecast, System.currentTimeMillis(), 30) else null
    val urgent = mowing && (a.alertWhileMowing || outlook?.warn == true)
    val label = weatherPillLabel(a, urgent)
    Box(Modifier.width(1.dp).height(18.dp).background(MammotionColors.Hairline))
    Row(Modifier.clip(CircleShape).background(if (urgent) MammotionColors.Warning else Color.Transparent)
        .clickable(onClickLabel = tr("Garden weather details", "Détail de la météo du jardin")) { details = true }.padding(horizontal = 4.dp, vertical = 4.dp)
        .semantics(mergeDescendants = true) { contentDescription = tr("Weather: $label", "Météo : $label") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (a.level >= RainLevel.IMMINENT) Icons.Rounded.Thunderstorm else Icons.Rounded.WaterDrop, contentDescription = null,
            tint = if (urgent) Color.White else levelColor(a.level), modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (urgent) Color.White else MammotionColors.TextPrimary)
    }
    if (details) WeatherDetailsDialog(w, robotGeo(telemetry)) { details = false }
}

@Composable
private fun WeatherDetailsDialog(w: WeatherState, robotGeo: GardenLocation?, onDismiss: () -> Unit) {
    var setup by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    LubaDialog(onDismissRequest = onDismiss, title = tr("Garden weather", "Météo du jardin"), content = {
        WeatherDecisionGroup(w, now)
        SettingsGroup {
            NavRow(tr("Garden position", "Position du jardin"), value = w.location?.label ?: tr("not set", "non définie"), icon = Icons.Rounded.EditLocationAlt) { setup = true }
        }
        InfoNote(tr("Observed rain: RainViewer radar. Forecast: Open-Meteo. Observation mode: no command is sent to the robot.", "Pluie observée : radar RainViewer. Prévision : Open-Meteo. Mode observation : aucune commande n'est envoyée au robot."))
    }, footer = {
        SecondaryPillButton(if (w.loading) tr("Refreshing…", "Actualisation…") else tr("Refresh", "Actualiser"), WeatherService::refreshNow, Modifier.fillMaxWidth(), enabled = !w.loading)
    })
    if (setup) GardenLocationDialog(w.location, robotGeo) { setup = false }
}

/** Rain decision state, its reason, the measurements and any read error. */
@Composable
private fun WeatherDecisionGroup(w: WeatherState, now: Long, title: String? = null) {
    SettingsGroup(title) {
        KeyValueRow(tr("State", "État"), w.assessment.level.title, valueColor = levelColor(w.assessment.level))
        Text(w.assessment.reason, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        WeatherForecastRows(w, now)
        w.error?.let { StatusNote(it, NoteTone.DANGER) }
    }
}

@Composable
private fun WeatherForecastRows(w: WeatherState, now: Long) {
    KeyValueRow(tr("Rain on the garden", "Pluie sur le jardin"), w.assessment.localDbz?.let { "≈ %.1f mm/h (radar)".format(fr.luba.poc.i18n.I18n.locale, Reflectivity.rainRateMmH(it.toDouble())) } ?: tr("none (radar)", "aucune (radar)"))
    w.frames.lastOrNull { it.complete }?.stats?.nearestKm?.let { KeyValueRow(tr("Nearest rain", "Pluie la plus proche"), "%.0f km".format(fr.luba.poc.i18n.I18n.locale, it)) }
    w.forecast?.let { f ->
        val s = now / 1000
        KeyValueRow(tr("Forecast within the hour", "Prévue dans l'heure"), "%.1f mm".format(fr.luba.poc.i18n.I18n.locale, f.nextMm(s, 60)))
        KeyValueRow(tr("Forecast over 3 h", "Prévue sur 3 h"), "%.1f mm".format(fr.luba.poc.i18n.I18n.locale, f.nextMm(s, 180)))
        KeyValueRow(tr("First rain forecast", "Première pluie prévue"), f.firstRainMin(s)?.let { tr("in ≈ ${MowingRainCheck.duration(it)}", "dans ≈ ${MowingRainCheck.duration(it)}") }
            ?: tr("none until ${f.steps.maxOfOrNull { it.endSec }?.let(::hhmm) ?: "—"}", "aucune d'ici ${f.steps.maxOfOrNull { it.endSec }?.let(::hhmm) ?: "—"}"))
    }
    // Wet grass: stronger pivot slides expected (slide map rescaled).
    w.wetness?.let { g ->
        KeyValueRow(tr("Grass", "Herbe"), tr("%s · slides ×%.1f", "%s · glissements ×%.1f").format(fr.luba.poc.i18n.I18n.locale, g.reason, GrassWetness.slideFactor(g.index)))
    }
    KeyValueRow(tr("Updated", "Actualisé"), w.radarFetchedAtMs?.let { tr("${(now - it) / 60_000} min ago", "il y a ${(now - it) / 60_000} min") } ?: "—")
}

/**
 * Warning on the start screen: rain observed or forecast during the estimated mowing duration.
 * Nothing is shown if the garden position is not set or monitoring is disabled.
 */
@Composable
fun MowingRainWarning(durationSeconds: Double?) {
    val w by WeatherService.flow.collectAsStateWithLifecycle()
    if (w.location == null || !w.monitoring) return
    val outlook = MowingRainCheck.check(w.assessment, w.forecast, System.currentTimeMillis(), durationSeconds?.let { (it / 60).roundToInt() })
    if (outlook.warn) SafetyNotice(outlook.message) else InfoNote(outlook.message)
}

/** Garden position entry: phone (approx.), robot, or pasted coordinates. */
@Composable
fun GardenLocationDialog(current: GardenLocation?, robotGeo: GardenLocation?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(current?.label ?: "") }
    // Result of the last source used (phone, robot) and its tone.
    var status by remember { mutableStateOf<Pair<String, NoteTone>?>(null) }
    fun usePhone() {
        status = tr("Locating the phone…", "Localisation du téléphone…") to NoteTone.INFO
        phoneLocation(context) { loc ->
            status = if (loc == null) tr("Phone position unavailable (location turned off?)", "Position du téléphone indisponible (localisation désactivée ?)") to NoteTone.WARNING
            else { text = GardenLocation(loc.latitude, loc.longitude, "").label; tr("Phone position (± ${loc.accuracy.roundToInt()} m): check then save", "Position du téléphone (± ${loc.accuracy.roundToInt()} m) : vérifiez puis enregistrez") to NoteTone.SUCCESS }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) usePhone() else status = tr("Location permission denied", "Autorisation de localisation refusée") to NoteTone.WARNING
    }
    val parsed = parseLatLon(text)
    LubaDialog(onDismissRequest = onDismiss, title = tr("Garden position", "Position du jardin"), content = {
        InfoNote(tr("Only the latitude and longitude, rounded to 4 decimals, are sent to Open-Meteo; RainViewer only receives radar tile numbers.", "Seules la latitude et la longitude, arrondies à 4 décimales, sont envoyées à Open-Meteo ; RainViewer ne reçoit que des numéros de tuiles radar."))
        SettingsGroup(tr("Coordinates", "Coordonnées")) {
            LubaTextField(text, { text = it; status = null }, "Latitude, longitude", placeholder = "48.8566, 2.3522")
            if (text.isNotBlank() && parsed == null) StatusNote(tr("Expected format: 48.8566, 2.3522", "Format attendu : 48.8566, 2.3522"), NoteTone.WARNING)
            else Text(tr("Paste from a map, or use the position of the phone (in the garden) or of the robot.", "Coller depuis une carte, ou reprendre la position du téléphone (dans le jardin) ou du robot."), style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        SettingsGroup("Sources") {
            SecondaryPillButton(tr("Current phone position", "Position actuelle du téléphone"), {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) usePhone()
                else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }, Modifier.fillMaxWidth())
            if (robotGeo != null) SecondaryPillButton(robotGeo.source.takeIf { it != "robot" }?.let { tr("Position published by the robot (${gardenSourceLabel(it)})", "Position publiée par le robot (${gardenSourceLabel(it)})") }
                ?: tr("Position published by the robot", "Position publiée par le robot"),
                { text = robotGeo.label; status = tr("Robot position: ${robotGeo.label}", "Position du robot : ${robotGeo.label}") to NoteTone.SUCCESS }, Modifier.fillMaxWidth())
        }
        status?.let { (message, tone) -> StatusNote(message, tone) }
    }, footer = {
        PrimaryPillButton(tr("Save", "Enregistrer"), {
            parsed?.let { (lat, lon) -> WeatherService.setLocation(GardenLocation(lat, lon, "manuelle")) }
            onDismiss()
        }, Modifier.fillMaxWidth(), enabled = parsed != null)
        if (current != null) TextAction(tr("Clear the position", "Effacer la position"), { WeatherService.setLocation(null); onDismiss() }, Modifier.fillMaxWidth(), danger = true)
    })
}

/** "48.8566, 2.3522", "48,8566 2,3522" (decimal comma) or "48.8566;2.3522". */
fun parseLatLon(text: String): Pair<Double, Double>? {
    val number = if (text.contains('.')) Regex("-?\\d+(?:\\.\\d+)?") else Regex("-?\\d+(?:,\\d+)?")
    val nums = number.findAll(text).map { it.value.replace(',', '.').toDouble() }.toList()
    if (nums.size != 2) return null
    val (lat, lon) = nums
    return if (lat in -85.0..85.0 && lon in -180.0..180.0) lat to lon else null
}

private fun phoneLocation(context: Context, done: (Location?) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    try {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
            ?: return done(null)
        LocationManagerCompat.getCurrentLocation(lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { loc ->
            done(loc ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time })
        }
    } catch (_: SecurityException) { done(null) }
}

/** Service page "Rain and weather": sources, freshness, decision and its reason (observation mode). */
@Composable
fun WeatherDebugContent(robotGeo: GardenLocation?) {
    val w by WeatherService.flow.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    SafetyNotice(tr("Observation mode: rain triggers a warning before mowing and an alert during it, without automatic stop or return of the robot.", "Mode observation : la pluie déclenche un avertissement avant la tonte et une alerte pendant, sans arrêt ni retour automatique du robot."))
    WeatherDecisionGroup(w, now, tr("Decision", "Décision"))
    SettingsGroup(tr("Settings", "Réglages")) {
        NavRow(tr("Garden position", "Position du jardin"), value = w.location?.label ?: tr("not set", "non définie"), icon = Icons.Rounded.EditLocationAlt) { setup = true }
        ToggleRow(tr("Monitor rain", "Surveiller la pluie"), w.monitoring, WeatherService::setMonitoring, icon = Icons.Rounded.NotificationsActive,
            subtitle = tr("Refreshed every 5 min, state on the map, warning before and during mowing", "Actualisation toutes les 5 min, état sur la carte, avertissement avant et pendant la tonte"))
        SecondaryPillButton(if (w.loading) tr("Refreshing…", "Actualisation…") else tr("Refresh now", "Actualiser maintenant"), WeatherService::refreshNow, Modifier.fillMaxWidth(),
            enabled = !w.loading && w.location != null && w.monitoring)
    }
    if (w.frames.isNotEmpty()) SettingsGroup("Radar (RainViewer, 10 min)") {
        for (f in w.frames.takeLast(7).reversed()) KeyValueRow(hhmm(f.timeSec) + if (f.complete) "" else tr(" (incomplete)", " (incomplète)"),
            buildString {
                append(f.stats.localDbz?.let { tr("garden $it dBZ", "jardin $it dBZ") } ?: tr("garden dry", "jardin sec"))
                f.stats.nearestKm?.let { append(tr(" · rain %.0f km away", " · pluie à %.0f km").format(fr.luba.poc.i18n.I18n.locale, it)) }
                if (f.stats.coverage10Km > 0) append(" · %d %% < 10 km".format(fr.luba.poc.i18n.I18n.locale, (f.stats.coverage10Km * 100).roundToInt()))
            })
        RadarAnalysis.approach(w.frames.filter { it.complete }.map { it.stats })?.let { ap ->
            KeyValueRow(tr("Estimated movement", "Déplacement estimé"), if (ap.frames < 3) tr("not enough frames", "pas assez d'images") else
                "%.0f km/h %s".format(fr.luba.poc.i18n.I18n.locale, kotlin.math.abs(ap.speedKmH), if (ap.approaching) tr("towards the garden", "vers le jardin") else tr("moving away", "en s'éloignant")))
        }
    }
    SettingsGroup(tr("Robot sensor", "Capteur du robot")) {
        KeyValueRow(tr("Rain sensor", "Capteur de pluie"), tr("not read yet", "pas encore lu"))
        InfoNote(tr("The robot's rain sensor is not read yet: the decision relies on the radar and the forecast.", "Le capteur de pluie du robot n'est pas encore lu : la décision repose sur le radar et la prévision."))
    }
    InfoNote(tr("Observed rain: RainViewer (last 2 hours). Forecast: Open-Meteo (model, 8 h). Rain arrival extrapolated from the latest frames: an estimate, thresholds to be calibrated on real tests.", "Pluie observée : RainViewer (2 dernières heures). Prévision : Open-Meteo (modèle, 8 h). Arrivée de la pluie extrapolée des dernières images : estimation, seuils à calibrer sur des essais réels."))
    if (setup) GardenLocationDialog(w.location, robotGeo) { setup = false }
}

/** Display text of a stored [GardenLocation.source] ("manuelle", "station RTK", "robot"; stored values stay in French). */
internal fun gardenSourceLabel(source: String): String = when (source) {
    "manuelle" -> tr("manual", "manuelle")
    "station RTK" -> tr("RTK station", "station RTK")
    else -> source
}
