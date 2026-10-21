package fr.luba.poc.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.Battery4Bar
import androidx.compose.material.icons.rounded.Battery6Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatteryUnknown
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.NumericStyle

/** Wi-Fi signal actually measured: Android reports -127 dBm when it is unknown (no link, not yet measured). */
val GatewayHealth.wifiKnown: Boolean get() = wifiRssiDbm in -126..-1

/** One line summarizing the gateway health: "78 % (charging) · Wi-Fi −65 dBm · 31 °C". */
fun GatewayHealth.summary(): String = buildList {
    if (batteryPercent >= 0) add("$batteryPercent %" + if (charging) tr(" (charging)", " (en charge)") else "")
    if (wifiKnown) add("Wi-Fi $wifiRssiDbm dBm" + if (wifiMbps > 0) " · $wifiMbps Mb/s" else "")
    if (batteryTempDeciC > 0) add("${batteryTempDeciC / 10} °C")
}.joinToString(" · ").ifEmpty { tr("unknown state", "état inconnu") }

/**
 * Gateway health on the pilot side ([pilot]: "Control via the gateway" role): last state received through the Wi-Fi
 * tunnel, robot link open or not (robot off: the gateway is still monitored). Null outside the pilot role or when stale.
 */
@Composable
fun rememberPilotGatewayHealth(pilot: Boolean): GatewayHealth? {
    if (!pilot) return null
    val received by GatewayHealthStore.latest.collectAsStateWithLifecycle()
    val now = rememberClock()
    return received?.takeIf { it.fresh(now) }?.health
}

/** Recent gateway reachability on the pilot side (null: unknown, outside the pilot role or too old). */
@Composable
fun rememberPilotGatewayReach(pilot: Boolean): GatewayReach? {
    if (!pilot) return null
    val reach by GatewayHealthStore.reach.collectAsStateWithLifecycle()
    val now = rememberClock()
    return reach?.takeIf { it.fresh(now) }
}

@Composable
private fun rememberClock(): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(2_000); value = System.currentTimeMillis() } }
    return now
}

/** Health of THIS phone, re-read every 5 s (display on the gateway itself). */
@Composable
fun rememberLocalGatewayHealth(): GatewayHealth {
    val context = androidx.compose.ui.platform.LocalContext.current
    val health by produceState(remember { GatewayHealthReader.read(context) }) {
        while (true) { kotlinx.coroutines.delay(GatewayProtocol.HEALTH_MS); value = GatewayHealthReader.read(context) }
    }
    return health
}

/** Gateway health details (Service → Gateway page): a titled group of values, warnings below. */
@Composable
fun GatewayHealthDetails(health: GatewayHealth?, title: String = tr("Gateway health", "Santé de la passerelle"), reach: GatewayReach? = null,
    version: String? = null) {
    SettingsGroup(title) {
        version?.let { gateway ->
            KeyValueRow(tr("Version", "Version"), tr("gateway $gateway · this phone ${fr.luba.poc.BuildConfig.VERSION_NAME}",
                "passerelle $gateway · ce téléphone ${fr.luba.poc.BuildConfig.VERSION_NAME}"))
            versionNote(gateway)?.let { InfoNote(it) }
            // Gateway phone older than the latest GitHub release (last check of this phone, Me → About).
            val update by fr.luba.poc.update.UpdateCheck.flow.collectAsState()
            fr.luba.poc.update.UpdateCheck.gatewayNote(gateway, update.latest)?.let { InfoNote(it) }
        }
        if (health == null) {
            StatusNote(gatewayReachText(reach), if (reach?.reachable == false) NoteTone.WARNING else NoteTone.INFO)
            return@SettingsGroup
        }
        GatewayHealthTiles(health)
        GatewayStatePills(health)
        health.warnings().forEach { StatusNote(it.replaceFirstChar(Char::uppercase), NoteTone.WARNING) }
    }
}

/** Compact line (Home, Service): summary, shown as a warning if there is a point of attention. */
@Composable
fun GatewayHealthLine(health: GatewayHealth?, modifier: Modifier = Modifier) {
    if (health == null) return
    val warnings = health.warnings()
    Text(tr("Gateway: ", "Passerelle : ") + health.summary() + warnings.firstOrNull()?.let { " · ⚠ $it" }.orEmpty(),
        modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = if (warnings.isEmpty()) MammotionColors.TextSecondary else MammotionColors.Warning)
}

/** Gateway state when no recent health is available. */
fun gatewayReachText(reach: GatewayReach?): String = when {
    reach == null -> tr("No information received: gateway not contacted yet.", "Aucune information reçue : passerelle pas encore contactée.")
    reach.reachable -> tr("Gateway reachable; health state not received yet.", "Passerelle joignable ; état de santé pas encore reçu.")
    else -> tr("Gateway unreachable", "Passerelle injoignable") + reach.detail.takeIf { it.isNotBlank() }?.let { tr(": $it", " : $it") }.orEmpty()
}

/**
 * Different app versions on the two phones: they still work together (same protocol), but fixes on one side only.
 * Null when both run the same version.
 */
fun versionNote(gateway: String, pilot: String = fr.luba.poc.BuildConfig.VERSION_NAME): String? = if (gateway == pilot) null else tr(
    "Different app versions ($gateway on the gateway, $pilot here): compatible, but install the same version on both phones to get the same fixes.",
    "Versions différentes ($gateway sur la passerelle, $pilot ici) : compatibles, mais installez la même version sur les deux téléphones pour bénéficier des mêmes corrections.")

/**
 * Quality of the gateway Wi-Fi, for the bars and their colour. Same limit as [GatewayHealth.warnings]: from −70 dBm the
 * Redmi A1 shows latency peaks over 600 ms, hence "weak".
 */
enum class SignalQuality(private val labelEn: String, private val labelFr: String, val bars: Int) {
    EXCELLENT("excellent", "excellent", 4), GOOD("good", "bon", 3), FAIR("fair", "moyen", 2), WEAK("weak", "faible", 1),
    UNKNOWN("unknown", "inconnu", 0);

    val label: String get() = tr(labelEn, labelFr)

    companion object {
        fun of(dbm: Int): SignalQuality = when {
            dbm !in -126..-1 -> UNKNOWN
            dbm >= -55 -> EXCELLENT
            dbm >= -63 -> GOOD
            dbm > -70 -> FAIR
            else -> WEAK
        }
    }
}

/** Colour of a quality: green when good, neutral when fair, orange when weak. */
@Composable
private fun SignalQuality.tone(): Color = when (this) {
    SignalQuality.EXCELLENT, SignalQuality.GOOD -> MammotionColors.Green
    SignalQuality.FAIR -> MammotionColors.Info
    SignalQuality.WEAK -> MammotionColors.Warning
    SignalQuality.UNKNOWN -> MammotionColors.TextTertiary
}

/** Four rising bars, [bars] of them lit in [color] (signal strength). */
@Composable
fun SignalBars(bars: Int, color: Color, modifier: Modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
    val off = MammotionColors.Hairline
    Canvas(modifier) {
        val gap = size.width * 0.12f
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (0.35f + 0.65f * i / 3f)
            drawRoundRect(if (i < bars) color else off, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 3, w / 3))
        }
    }
}

private fun batteryIcon(h: GatewayHealth): ImageVector = when {
    h.batteryPercent < 0 -> Icons.Rounded.BatteryUnknown
    h.charging -> Icons.Rounded.BatteryChargingFull
    h.batteryPercent >= 90 -> Icons.Rounded.BatteryFull
    h.batteryPercent >= 60 -> Icons.Rounded.Battery6Bar
    h.batteryPercent >= 25 -> Icons.Rounded.Battery4Bar
    else -> Icons.Rounded.Battery2Bar
}

@Composable
private fun batteryTone(h: GatewayHealth): Color = when {
    h.batteryPercent < 0 -> MammotionColors.TextTertiary
    h.charging || h.batteryPercent >= 25 -> MammotionColors.Green
    h.batteryPercent >= 15 -> MammotionColors.Warning
    else -> MammotionColors.Danger
}

@Composable
private fun temperatureTone(h: GatewayHealth): Color = when {
    h.thermalStatus >= 2 || h.batteryTempDeciC >= 450 -> MammotionColors.Warning
    else -> MammotionColors.Info
}

/** Labels of the gateway health display. */
object GatewayHealthText {
    fun battery(h: GatewayHealth): String = if (h.batteryPercent < 0) "—" else "${h.batteryPercent} %"
    fun batteryLabel(h: GatewayHealth): String = when {
        h.batteryPercent < 0 -> tr("Battery", "Batterie")
        h.charging -> tr("Battery · plugged in", "Batterie · branchée")
        else -> tr("Battery", "Batterie")
    }
    fun wifi(h: GatewayHealth): String = if (h.wifiKnown) "${h.wifiRssiDbm} dBm" else "—"
    fun wifiLabel(h: GatewayHealth): String = "Wi-Fi · " + SignalQuality.of(h.wifiRssiDbm).label
    fun rate(h: GatewayHealth): String = if (h.wifiMbps > 0) "${h.wifiMbps} Mb/s" else "—"
    fun temperature(h: GatewayHealth, decimals: Boolean = true): String = when {
        h.batteryTempDeciC <= 0 -> "—"
        decimals -> "${MowingNum.one(h.batteryTempDeciC / 10.0)} °C"
        else -> "${Math.round(h.batteryTempDeciC / 10.0)} °C"
    }
    fun thermal(h: GatewayHealth): String = when (h.thermalStatus) {
        -1 -> tr("unknown", "inconnu"); 0 -> "normal"; 1 -> tr("light", "léger"); 2 -> tr("moderate", "modéré")
        3 -> tr("severe", "sévère"); else -> tr("critical", "critique")
    }
    /** Warning line: the first point of attention, with the number of others. */
    fun warning(h: GatewayHealth): String? = h.warnings().takeIf { it.isNotEmpty() }?.let { w ->
        w.first().replaceFirstChar(Char::uppercase) + if (w.size > 1) tr(" (+${w.size - 1} more)", " (+${w.size - 1} autre${if (w.size > 2) "s" else ""})") else ""
    }
}

/** One decimal in the interface language (decimal comma in French). */
private object MowingNum {
    fun one(v: Double): String = String.format(if (fr.luba.poc.i18n.I18n.french) java.util.Locale.FRANCE else java.util.Locale.ENGLISH, "%.1f", v)
}

/** Compact measure: icon (or bars) and value on one line, label below. */
@Composable
private fun Measure(value: String, label: String, modifier: Modifier = Modifier, leading: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading()
            Text(value, style = MaterialTheme.typography.titleSmall, color = MammotionColors.TextPrimary, maxLines = 1)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MammotionColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Gateway health in a card (Service → robot card): "GATEWAY ON THE ROBOT", then battery, Wi-Fi with its bars, rate and
 * temperature side by side, and the first point of attention in an orange band.
 */
@Composable
fun GatewayHealthStrip(health: GatewayHealth?, modifier: Modifier = Modifier) {
    if (health == null) return
    val quality = SignalQuality.of(health.wifiRssiDbm)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Text(tr("GATEWAY ON THE ROBOT", "PASSERELLE SUR LE ROBOT"), style = EyebrowStyle, color = MammotionColors.TextTertiary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Measure(GatewayHealthText.battery(health), GatewayHealthText.batteryLabel(health)) {
                Icon(batteryIcon(health), contentDescription = null, tint = batteryTone(health), modifier = Modifier.size(16.dp))
            }
            Measure(GatewayHealthText.wifi(health), GatewayHealthText.wifiLabel(health)) {
                SignalBars(quality.bars, quality.tone())
            }
            Measure(GatewayHealthText.rate(health), tr("Rate", "Débit")) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = MammotionColors.Info, modifier = Modifier.size(16.dp))
            }
            Measure(GatewayHealthText.temperature(health, decimals = false), tr("Temperature", "Température")) {
                Icon(Icons.Rounded.Thermostat, contentDescription = null, tint = temperatureTone(health), modifier = Modifier.size(16.dp))
            }
        }
        GatewayHealthText.warning(health)?.let { WarningBand(it) }
    }
}

/** Orange band with a warning icon. */
@Composable
private fun WarningBand(text: String) {
    Row(Modifier.fillMaxWidth().background(MammotionColors.WarningSoft, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MammotionColors.Warning, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MammotionColors.Warning)
    }
}

/** Large tile: icon (or bars), value, label and an optional gauge (0..1). */
@Composable
private fun HealthTile(value: String, label: String, tone: Color, modifier: Modifier = Modifier, gauge: Float? = null, leading: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxHeight(), shape = MaterialTheme.shapes.medium, color = MammotionColors.SurfaceMuted) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            leading()
            Text(value, style = NumericStyle, color = MammotionColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            gauge?.let { g ->
                Box(Modifier.fillMaxWidth().height(4.dp).background(MammotionColors.Hairline, CircleShape)) {
                    Box(Modifier.fillMaxWidth(g.coerceIn(0f, 1f)).height(4.dp).background(tone, CircleShape))
                }
            }
        }
    }
}

/** Battery, Wi-Fi, temperature and heating of the gateway phone in a 2 × 2 grid. */
@Composable
fun GatewayHealthTiles(health: GatewayHealth) {
    val quality = SignalQuality.of(health.wifiRssiDbm)
    val battery = batteryTone(health)
    val heat = temperatureTone(health)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            HealthTile(GatewayHealthText.battery(health), GatewayHealthText.batteryLabel(health), battery, Modifier.weight(1f),
                gauge = health.batteryPercent.takeIf { it >= 0 }?.let { it / 100f }) {
                Icon(batteryIcon(health), contentDescription = null, tint = battery, modifier = Modifier.size(20.dp))
            }
            HealthTile(GatewayHealthText.wifi(health), GatewayHealthText.wifiLabel(health) + (if (health.wifiMbps > 0) " · ${health.wifiMbps} Mb/s" else ""),
                quality.tone(), Modifier.weight(1f), gauge = quality.bars / 4f) {
                SignalBars(quality.bars, quality.tone(), Modifier.size(width = 24.dp, height = 18.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            HealthTile(GatewayHealthText.temperature(health), tr("Battery temperature", "Température batterie"), heat, Modifier.weight(1f)) {
                Icon(Icons.Rounded.Thermostat, contentDescription = null, tint = heat, modifier = Modifier.size(20.dp))
            }
            HealthTile(GatewayHealthText.thermal(health).replaceFirstChar(Char::uppercase), tr("Heating", "Échauffement"), heat, Modifier.weight(1f)) {
                Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = heat, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** States of the gateway phone that matter for a long tracking: screen, battery saver, battery exemption. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GatewayStatePills(health: GatewayHealth) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        StatePill(if (health.screenOn) tr("Screen on", "Écran allumé") else tr("Screen off", "Écran éteint"), MammotionColors.Info)
        StatePill(if (health.powerSave) tr("Battery saver on", "Économiseur actif") else tr("Battery saver off", "Économiseur inactif"),
            if (health.powerSave) MammotionColors.Warning else MammotionColors.Green)
        StatePill(if (health.batteryExempt) tr("Battery exemption", "Exemption batterie") else tr("No battery exemption", "Sans exemption batterie"),
            if (health.batteryExempt) MammotionColors.Green else MammotionColors.Warning)
    }
}

@Composable
private fun StatePill(text: String, color: Color) {
    Row(Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
