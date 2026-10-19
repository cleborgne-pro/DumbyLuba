package fr.luba.poc.gateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.theme.MammotionColors

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
fun GatewayHealthDetails(health: GatewayHealth?, title: String = tr("Gateway health", "Santé de la passerelle"), reach: GatewayReach? = null) {
    SettingsGroup(title) {
        if (health == null) {
            StatusNote(gatewayReachText(reach), if (reach?.reachable == false) NoteTone.WARNING else NoteTone.INFO)
            return@SettingsGroup
        }
        KeyValueRow(tr("Battery", "Batterie"), if (health.batteryPercent < 0) tr("unknown", "inconnue")
            else "${health.batteryPercent} %" + if (health.charging) tr(" · plugged in", " · branchée") else tr(" · on battery", " · sur batterie"))
        if (health.batteryTempDeciC > 0) KeyValueRow(tr("Battery temperature", "Température batterie"), "${health.batteryTempDeciC / 10.0} °C")
        KeyValueRow("Wi-Fi", (if (health.wifiKnown) "${health.wifiRssiDbm} dBm" else tr("unknown", "inconnu")) +
            (if (health.wifiMbps > 0) " · ${health.wifiMbps} Mb/s" else ""))
        KeyValueRow(tr("Thermal", "Thermique"), when (health.thermalStatus) {
            -1 -> tr("unknown", "inconnu"); 0 -> "normal"; 1 -> tr("light", "léger"); 2 -> tr("moderate", "modéré")
            3 -> tr("severe", "sévère"); else -> tr("critical", "critique")
        })
        KeyValueRow(tr("Screen", "Écran"), if (health.screenOn) tr("on", "allumé") else tr("off", "éteint"))
        KeyValueRow(tr("Battery saver", "Économiseur d'énergie"), if (health.powerSave) tr("on", "actif") else tr("off", "inactif"))
        KeyValueRow(tr("Battery exemption", "Exemption batterie"), if (health.batteryExempt) tr("yes", "oui") else tr("no", "non"))
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
