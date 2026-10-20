package fr.luba.poc.gateway

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/** One point checked on the gateway phone, with the action that fixes it ([fix], null when it is fine). */
data class PhoneCheck(val label: String, val ok: Boolean, val detail: String, val fix: PhoneFix? = null)

/** Fix offered for a [PhoneCheck]: the matching Android settings screen. */
enum class PhoneFix { BLUETOOTH, BATTERY, WIFI, DO_NOT_DISTURB }

/** What the phone mounted on the robot needs, from its current state. Pure: tested on the JVM. */
object GatewayPhoneChecks {
    fun list(bluetoothOn: Boolean, batteryExempt: Boolean, wifiConnected: Boolean, silence: Boolean, dndGranted: Boolean): List<PhoneCheck> = buildList {
        add(PhoneCheck(tr("Bluetooth", "Bluetooth"), bluetoothOn, if (bluetoothOn) tr("on", "activé")
            else tr("off: the gateway cannot reach the robot", "désactivé : la passerelle ne peut pas joindre le robot"), PhoneFix.BLUETOOTH.takeUnless { bluetoothOn }))
        add(PhoneCheck("Wi-Fi", wifiConnected, if (wifiConnected) tr("connected", "connecté")
            else tr("not connected: the pilot cannot reach this phone", "non connecté : le pilote ne peut pas joindre ce téléphone"), PhoneFix.WIFI.takeUnless { wifiConnected }))
        add(PhoneCheck(tr("Battery exemption", "Exemption batterie"), batteryExempt, if (batteryExempt) tr("active", "active")
            else tr("missing: the gateway may be suspended with the screen off", "absente : la passerelle peut être suspendue écran éteint"), PhoneFix.BATTERY.takeUnless { batteryExempt }))
        if (silence) add(PhoneCheck(tr("\"Do not disturb\"", "« Ne pas déranger »"), dndGranted, if (dndGranted) tr("allowed", "autorisé")
            else tr("not allowed: only the volumes are lowered", "non autorisé : seuls les volumes sont baissés"), PhoneFix.DO_NOT_DISTURB.takeUnless { dndGranted }))
    }

    fun read(context: Context): List<PhoneCheck> {
        val bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val wifi = GatewayHealthReader.read(context).wifiKnown
        return list(bluetooth, exempt, wifi, SilenceKeeper.keep(context), SilenceKeeper.policyGranted(context))
    }

    fun open(context: Context, fix: PhoneFix) {
        when (fix) {
            PhoneFix.BATTERY -> requestBatteryExemption(context)
            PhoneFix.BLUETOOTH -> runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            PhoneFix.WIFI -> runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            PhoneFix.DO_NOT_DISTURB -> runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
    }
}

/**
 * Home screen of the phone mounted on the robot (Gateway role): the pilot's pairing QR code and code, the state of the
 * service, of the robot and of the pilot, and what this phone still needs, each with its fix. The everyday screens
 * (map, mowing) belong to the pilot phone and stay reachable from the Service tab.
 */
@Composable
fun GatewayHomeScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MammotionColors
    val status by GatewayService.status.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf(GatewayPhoneChecks.read(context)) }
    // Back from an Android settings screen, or Bluetooth / Wi-Fi switched: read again every 2 s.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); checks = GatewayPhoneChecks.read(context) } }
    var showQr by remember { mutableStateOf(false) }
    val pairing = GatewayPairing.ofThisGateway(context, status.address.takeIf { status.running })
    Column(modifier.fillMaxSize().background(colors.Background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        ScreenTitle(tr("Gateway on the robot", "Passerelle sur le robot"), subtitle = status.message)
        if (!status.running) {
            StatusNote(tr("Gateway stopped: the pilot phone cannot reach the robot.", "Passerelle arrêtée : le téléphone de pilotage ne peut pas joindre le robot."), NoteTone.WARNING)
            PrimaryPillButton(tr("Start the gateway", "Démarrer la passerelle"), { GatewayService.start(context) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
        }
        SettingsGroup(tr("Pair the pilot phone", "Appairer le téléphone de pilotage")) {
            val connected = status.pilot != null
            if (connected && !showQr) {
                StatusNote(tr("Pilot connected: ${status.pilot}", "Pilote connecté : ${status.pilot}"), NoteTone.SUCCESS)
                TextAction(tr("Show the QR code", "Afficher le QR code"), { showQr = true }, Modifier.fillMaxWidth())
            } else if (pairing != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrImage(pairing.uri(), tr("Pairing QR code", "QR code d'appairage"), Modifier.widthIn(max = 260.dp).fillMaxWidth(0.8f))
                }
                InfoNote(tr("On the pilot phone: Service → Wi-Fi gateway → Scan the QR code. Or choose this gateway in its list and type the code.",
                    "Sur le téléphone de pilotage : Service → Passerelle Wi-Fi → Scanner le QR code. Ou choisissez cette passerelle dans sa liste et tapez le code."))
            } else if (status.running) InfoNote(tr("Waiting for the Wi-Fi address of this phone: connect it to the garden Wi-Fi.",
                "En attente de l'adresse Wi-Fi de ce téléphone : connectez-le au Wi-Fi du jardin."))
            CodeValue(tr("Gateway code", "Code de la passerelle"), GatewayConfig.read(context).code)
            status.address?.let { KeyValueRow(tr("Wi-Fi address", "Adresse Wi-Fi"), "$it:${GatewayProtocol.PORT}") }
        }
        SettingsGroup(tr("Status", "État")) {
            KeyValueRow("Robot", status.robot.label)
            KeyValueRow(tr("Pilot", "Pilote"), status.pilot ?: tr("none", "aucun"))
        }
        SettingsGroup(tr("This phone", "Ce téléphone")) {
            checks.forEach { check ->
                KeyValueRow(check.label, check.detail)
                check.fix?.let { fix -> TextAction(tr("Fix: ", "Corriger : ") + check.label, { GatewayPhoneChecks.open(context, fix) }, Modifier.fillMaxWidth()) }
            }
            if (checks.all { it.ok }) StatusNote(tr("This phone is ready to stay on the robot.", "Ce téléphone est prêt à rester sur le robot."), NoteTone.SUCCESS)
        }
        GatewayHealthDetails(rememberLocalGatewayHealth(), tr("Health of this phone (reported to the pilot)", "Santé de ce téléphone (remontée au pilote)"))
        InfoNote(tr("Lay this phone flat on top of the robot, screen up, firmly held and plugged into its charger if possible: its Bluetooth signal to the robot is also used to detect a fall.",
            "Posez ce téléphone à plat sur le robot, écran vers le haut, bien maintenu et si possible branché sur son chargeur : son signal Bluetooth vers le robot sert aussi à détecter une chute."))
        SecondaryPillButton(tr("Gateway settings", "Réglages de la passerelle"), onOpenSettings, Modifier.fillMaxWidth(), icon = Icons.Rounded.Settings)
    }
}
