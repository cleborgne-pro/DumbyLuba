package fr.luba.poc.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.diagnostics.CheckFix
import fr.luba.poc.diagnostics.CheckItem
import fr.luba.poc.diagnostics.CheckLevel
import fr.luba.poc.diagnostics.SetupCheck
import fr.luba.poc.gateway.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Robot side of the check, from the current state. */
fun RobotUi.setupRobot() = SetupCheck.Robot(ready = ready, viaGateway = viaGateway, rssi = rssi, charging = telemetry.chargeState in 1..2,
    name = selected?.name ?: lastKnown?.robotName, watchdogTested = motorWatchdog != null, watchdogDemonstrated = motorWatchdog?.demonstrated == true)

/**
 * Service → Check the setup: runs the checks of this phone's role (pilot through a gateway, direct Bluetooth, gateway
 * mounted on the robot) and lists each link with its fix. Nothing is sent to the robot: through a gateway, a search
 * is only asked when the robot is not linked (a linked robot is checked from the live link).
 */
@Composable
fun SetupCheckPage(robot: RobotBle, state: RobotUi, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val role = rememberGatewayRole()
    val latest by rememberUpdatedState(state)
    var items by remember { mutableStateOf<List<CheckItem>?>(null) }
    var running by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(0) }
    val gatewayStatus by GatewayService.status.collectAsState()
    LaunchedEffect(token, role) {
        running = true
        items = when (role) {
            GatewayRole.GATEWAY -> SetupCheck.gateway(GatewayPhoneChecks.read(context), gatewayStatus.running,
                gatewayStatus.robot.ready, gatewayStatus.robot.label, gatewayStatus.pilot)
            GatewayRole.CLIENT -> {
                val config = GatewayConfig.read(context)
                val announced = withContext(Dispatchers.IO) { runCatching { GatewayClient.discoverAll(context, 3000).size }.getOrDefault(0) }
                val s = latest
                val linked = s.ready && s.viaGateway
                var error: String? = null
                val probe = when {
                    linked -> null
                    !config.validPilotCode() -> { error = tr("no gateway paired: scan its QR code", "aucune passerelle appairée : scannez son QR code"); null }
                    s.connection != ConnectionState.Disconnected -> { error = tr("connection in progress: check again in a moment", "connexion en cours : vérifiez de nouveau dans un instant"); null }
                    else -> withContext(Dispatchers.IO) { runCatching { GatewayClient.probe(context, config) } }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }.getOrNull()
                }
                val live = GatewayHealthStore.latest.value?.takeIf { it.fresh() }?.health
                SetupCheck.pilot(PhoneWifi.connected, announced, probe, error, latest.setupRobot(), live,
                    GatewayHealthStore.gatewayVersion.value, fr.luba.poc.BuildConfig.VERSION_NAME)
            }
            GatewayRole.OFF -> {
                val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                val granted = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                val on = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
                // Robot not linked: a fresh search (no automatic connection), then the verdict on what it found.
                if (granted && on && latest.connection == ConnectionState.Disconnected && !latest.scanning) {
                    robot.scan(autoConnect = false)
                    delay(500)
                    var waited = 0
                    while (latest.scanning && waited < 20_000) { delay(500); waited += 500 }
                }
                val seen = latest.gatewaySeen != null ||
                    withContext(Dispatchers.IO) { runCatching { GatewayClient.discoverAll(context, 2000).isNotEmpty() }.getOrDefault(false) }
                SetupCheck.direct(on, granted, latest.devices, seen, latest.setupRobot())
            }
        }
        running = false
        // First-mowing list: the check counts as done once it finds nothing to fix (pilot or direct role).
        if (role != GatewayRole.GATEWAY && items?.none { it.level == CheckLevel.FAILED } == true)
            fr.luba.poc.onboarding.SetupProgress.mark(context, fr.luba.poc.onboarding.SetupStep.SETUP_CHECKED)
    }
    PushedPage(ServicePage.SETUP.title, back, bottomBar = {
        PrimaryPillButton(if (running) tr("Checking…", "Vérification…") else tr("Check again", "Vérifier de nouveau"), { token++ }, Modifier.fillMaxWidth(),
            enabled = !running, icon = Icons.Rounded.Refresh)
    }) {
        InfoNote(when (role) {
            GatewayRole.CLIENT -> tr("Pilot through a gateway: Wi-Fi, gateway, robot, placement and safety, link by link.", "Pilote via une passerelle : Wi-Fi, passerelle, robot, placement et sécurité, maillon par maillon.")
            GatewayRole.GATEWAY -> tr("Phone mounted on the robot: what it needs to hold the robot for the pilot.", "Téléphone posé sur le robot : ce dont il a besoin pour tenir le robot pour le pilote.")
            GatewayRole.OFF -> tr("Direct Bluetooth from this phone: permission, robot in range, then safety.", "Bluetooth direct depuis ce téléphone : permission, robot à portée, puis sécurité.")
        })
        if (running) LubaProgress(Modifier.fillMaxWidth())
        val list = items
        if (list != null) {
            val failed = list.count { it.level == CheckLevel.FAILED }
            val warned = list.count { it.level == CheckLevel.WARNING }
            StatusNote(when {
                failed > 0 -> tr("$failed point(s) to fix, $warned to watch", "$failed point(s) à corriger, $warned à surveiller")
                warned > 0 -> tr("Working, $warned point(s) to watch", "Fonctionnel, $warned point(s) à surveiller")
                else -> tr("Everything is in place", "Tout est en place")
            }, when { failed > 0 -> NoteTone.DANGER; warned > 0 -> NoteTone.WARNING; else -> NoteTone.SUCCESS })
            SettingsGroup(tr("Checks", "Vérifications")) {
                list.forEach { item -> CheckRow(item) { fix -> applyFix(context, fix, latest, open) } }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem, onFix: (CheckFix) -> Unit) {
    val colors = MammotionColors
    val (icon, tint) = when (item.level) {
        CheckLevel.OK -> Icons.Rounded.CheckCircle to colors.Green
        CheckLevel.WARNING -> Icons.Rounded.Warning to colors.Warning
        CheckLevel.FAILED -> Icons.Rounded.Error to colors.Danger
        CheckLevel.SKIPPED -> Icons.Rounded.RemoveCircleOutline to colors.TextTertiary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
            item.fix?.let { fix -> TextAction(fixLabel(fix), { onFix(fix) }) }
        }
    }
}

private fun fixLabel(fix: CheckFix) = when (fix) {
    CheckFix.PAIR_GATEWAY -> tr("Open the gateway page", "Ouvrir la page passerelle")
    CheckFix.OPEN_CONNECTION -> tr("Open the Bluetooth connection", "Ouvrir la connexion Bluetooth")
    CheckFix.BLUETOOTH_SETTINGS -> tr("Bluetooth settings", "Réglages Bluetooth")
    CheckFix.WIFI_SETTINGS -> tr("Wi-Fi settings", "Réglages Wi-Fi")
    CheckFix.BATTERY -> tr("Exempt from battery optimization", "Exempter de l'optimisation batterie")
    CheckFix.DO_NOT_DISTURB -> tr("Allow \"Do not disturb\"", "Autoriser « Ne pas déranger »")
    CheckFix.COMMAND_CUTOFF -> tr("Test the command cutoff", "Tester la coupure des commandes")
    CheckFix.USE_GATEWAY -> tr("Use this gateway", "Utiliser cette passerelle")
}

private fun applyFix(context: android.content.Context, fix: CheckFix, state: RobotUi, open: (String) -> Unit) {
    fun settings(action: String) = runCatching { context.startActivity(Intent(action)) }
    when (fix) {
        CheckFix.PAIR_GATEWAY -> open(ServicePage.RELAY.key)
        CheckFix.OPEN_CONNECTION -> open(ServicePage.CONNECTION.key)
        CheckFix.COMMAND_CUTOFF -> open(ServicePage.WATCHDOG.key)
        CheckFix.BLUETOOTH_SETTINGS -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)
        CheckFix.WIFI_SETTINGS -> settings(Settings.ACTION_WIFI_SETTINGS)
        CheckFix.DO_NOT_DISTURB -> settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        CheckFix.BATTERY -> requestBatteryExemption(context)
        CheckFix.USE_GATEWAY -> {
            // Role change only with the robot disconnected, as on the gateway page.
            val config = GatewayConfig.read(context)
            if (state.connection == ConnectionState.Disconnected)
                GatewayConfig.save(context, config.copy(role = GatewayRole.CLIENT, host = state.gatewaySeen ?: config.host))
            open(ServicePage.RELAY.key)
        }
    }
}
