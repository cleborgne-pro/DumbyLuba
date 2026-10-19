package fr.luba.poc.gateway

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short labels of the segmented control; the full role label is shown below. */
private fun GatewayRole.short() = when (this) {
    GatewayRole.OFF -> "Direct"
    GatewayRole.GATEWAY -> tr("Gateway", "Passerelle")
    GatewayRole.CLIENT -> tr("Pilot", "Pilote")
}

/** Tone of a gateway message: failure or refusal as a warning, success in green (French or English wording). */
private fun messageTone(text: String) = when {
    text.startsWith("Échec") || text.contains("refusé") || text.contains("nécessaires") ||
        text.startsWith("Failed") || text.contains("refused") || text.contains("required") -> NoteTone.WARNING
    text.contains("jointe") || text.contains("reached") -> NoteTone.SUCCESS
    else -> NoteTone.INFO
}

/**
 * Role choice: direct Bluetooth, gateway lying on the robot (Redmi), or control via the gateway (Fold).
 * [linkIdle]: role can only be changed with the robot disconnected; [disconnect] closes the current link (STOP then close).
 * Emits titled groups directly into the column of the calling page.
 */
@Composable
fun GatewayPanel(linkIdle: Boolean, disconnect: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by GatewayService.status.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(GatewayConfig.read(context)) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun save(value: GatewayConfig) { config = value; GatewayConfig.save(context, value) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) { save(config.copy(role = GatewayRole.GATEWAY)); GatewayService.start(context) }
        else message = tr("Bluetooth permissions are required for the gateway.", "Les permissions Bluetooth sont nécessaires à la passerelle.")
    }
    fun becomeGateway() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) +
            (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.launch(needed)
    }

    SettingsGroup(tr("Role of this phone", "Rôle de ce téléphone")) {
        Segmented(GatewayRole.entries.map { it to it.short() }, config.role, { role ->
            message = ""
            when (role) {
                GatewayRole.GATEWAY -> becomeGateway()
                else -> { if (config.role == GatewayRole.GATEWAY) GatewayService.stop(context); save(config.copy(role = role)) }
            }
        }, enabled = linkIdle && !busy)
        Text(config.role.label, style = MaterialTheme.typography.titleSmall)
        InfoNote(when (config.role) {
            GatewayRole.OFF -> tr("This phone connects directly to the robot over Bluetooth.", "Ce téléphone se connecte directement au robot en Bluetooth.")
            GatewayRole.GATEWAY -> tr("This phone, lying on the robot, holds the Bluetooth link; the pilot phone sends it the commands over Wi-Fi.",
                "Ce téléphone, posé sur le robot, tient le Bluetooth ; le téléphone de pilotage lui envoie les commandes en Wi-Fi.")
            GatewayRole.CLIENT -> tr("This phone sends the commands over Wi-Fi to the phone lying on the robot, which holds the Bluetooth link.",
                "Ce téléphone envoie les commandes en Wi-Fi au téléphone posé sur le robot, qui tient le Bluetooth.")
        })
        if (config.role != GatewayRole.OFF)
            InfoNote(tr("Pilot silent for more than ${GatewayProtocol.SILENCE_MS / 1000} s: the gateway stops the blades and the robot, " +
                "except during autonomous route tracking (the robot carries on alone, the pilot takes the link back when the Wi-Fi returns).",
                "Pilote muet plus de ${GatewayProtocol.SILENCE_MS / 1000} s : la passerelle coupe les lames et arrête le robot, " +
                "sauf pendant un suivi autonome de trajet (le robot continue seul, le pilote reprend la liaison au retour du Wi-Fi)."))
        if (!linkIdle) {
            // The robot accepts a single Bluetooth link: while this phone holds it, the gateway cannot see the robot.
            StatusNote(tr("This phone is connected to the robot: disconnect it to change role. As long as it stays connected, the gateway cannot see the robot.",
                "Ce téléphone est connecté au robot : déconnectez-le pour changer de rôle. Tant qu'il le reste, la passerelle ne peut pas voir le robot."),
                NoteTone.WARNING)
            SecondaryPillButton(tr("Disconnect the robot", "Déconnecter le robot"), disconnect, Modifier.fillMaxWidth())
        }
        if (message.isNotBlank() && config.role != GatewayRole.CLIENT) StatusNote(message, messageTone(message))
    }
    when (config.role) {
        GatewayRole.GATEWAY -> {
            val power = context.getSystemService(PowerManager::class.java)
            val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
            SettingsGroup(tr("Gateway status", "État de la passerelle")) {
                KeyValueRow("Service", status.message)
                KeyValueRow("Robot", status.robot.label)
                CodeValue(tr("Gateway code", "Code de la passerelle"), config.code)
                status.address?.let { KeyValueRow(tr("Wi-Fi address", "Adresse Wi-Fi"), "$it:${GatewayProtocol.PORT}") }
                KeyValueRow(tr("Battery exemption", "Exemption batterie"), if (exempt) tr("active", "active") else tr("missing", "absente"))
                if (!exempt) StatusNote(tr("Without a battery exemption, the gateway may be suspended when the screen is off.",
                    "Sans exemption batterie, la passerelle peut être suspendue écran éteint."), NoteTone.WARNING)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    if (status.running) SecondaryPillButton(tr("Stop", "Arrêter"), { GatewayService.stop(context) }, Modifier.weight(1f))
                    else PrimaryPillButton(tr("Start", "Démarrer"), { becomeGateway() }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                    SecondaryPillButton(tr("Battery", "Batterie"), { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.weight(1f))
                }
            }
            GatewayHealthDetails(rememberLocalGatewayHealth(), tr("Health of this phone (reported to the pilot)", "Santé de ce téléphone (remontée au pilote)"))
            SettingsGroup(tr("Gateway settings", "Réglages de la passerelle")) {
                var keepScreen by remember { mutableStateOf(GatewayConfig.keepScreen(context)) }
                ToggleRow(tr("Keep the screen on while piloting", "Garder l'écran allumé pendant le pilotage"), keepScreen, { keepScreen = it; GatewayConfig.setKeepScreen(context, it) },
                    subtitle = tr("Off: saves the battery (~8 %/h with the screen on). Screen off, the Wi-Fi may lag by a few hundred ms; no effect on autonomous tracking, only noticeable for the sequences still driven from the Fold (bypass, docking).",
                        "Désactivé : économise la batterie (~8 %/h écran allumé). Écran éteint, le Wi-Fi peut prendre quelques centaines de ms de retard ; sans effet sur le suivi autonome, sensible seulement pour les séquences encore pilotées depuis le Fold (contournement, accostage)."))
                SilenceRow()
            }
            val events = status.lastEvents.takeLast(12).asReversed()
            if (events.isNotEmpty()) SettingsGroup(tr("Gateway log", "Journal de la passerelle")) { LogLines(events) }
        }
        GatewayRole.CLIENT -> {
            var host by remember(config.role) { mutableStateOf(config.host) }
            var code by remember(config.role) { mutableStateOf(config.code) }
            LaunchedEffect(host, code) { kotlinx.coroutines.delay(400); save(config.copy(host = host.trim(), code = code)) }
            SettingsGroup(tr("Connection to the gateway", "Connexion à la passerelle")) {
                LubaTextField(host, { host = it }, tr("Gateway address", "Adresse de la passerelle"), enabled = linkIdle && !busy,
                    placeholder = tr("found automatically on the Wi-Fi", "trouvée automatiquement sur le Wi-Fi"))
                LubaTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) }, tr("Code shown on the gateway", "Code affiché sur la passerelle"), enabled = linkIdle && !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                PrimaryPillButton(if (busy) tr("Searching…", "Recherche…") else tr("Find and test", "Trouver et tester"), {
                    busy = true; message = tr("Searching for the gateway…", "Recherche de la passerelle…")
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching { GatewayClient.find(context, config.copy(host = host.trim(), code = code)) }.fold({ it }, { tr("Failed: ${it.message ?: it.javaClass.simpleName}", "Échec : ${it.message ?: it.javaClass.simpleName}") })
                        }
                        config = GatewayConfig.read(context); host = config.host; busy = false
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && code.length == 6, icon = Icons.Rounded.Search)
                if (busy) LubaProgress(Modifier.fillMaxWidth())
                if (message.isNotBlank()) StatusNote(message, messageTone(message))
                InfoNote(tr("Optional address: every gateway announced on the Wi-Fi is tried with this code, and the one that " +
                    "accepts it is kept. Robot not connected: new automatic attempt every 15 s (up to 1 min if the gateway " +
                    "cannot be found), even with the app in the background. The robot search, the connection and all " +
                    "missions then go through the gateway.",
                    "Adresse facultative : toutes les passerelles annoncées sur le Wi-Fi sont essayées avec ce code, et celle qui " +
                    "l'accepte est retenue. Robot non connecté : nouvelle tentative automatique toutes les 15 s (jusqu'à 1 min si la " +
                    "passerelle reste introuvable), même app en arrière-plan. La recherche du robot, la connexion et toutes les " +
                    "missions passent ensuite par la passerelle."))
            }
            // Status before the settings, as on the gateway side.
            GatewayHealthDetails(rememberPilotGatewayHealth(pilot = true), reach = rememberPilotGatewayReach(pilot = true))
            SettingsGroup(tr("Routes", "Trajets")) {
                var legs by remember { mutableStateOf(fr.luba.poc.autonomy.AutonomySettings.enabled(context)) }
                ToggleRow(tr("Hand routes over to the gateway", "Confier les trajets à la passerelle"), legs, { legs = it; fr.luba.poc.autonomy.AutonomySettings.set(context, it) },
                    subtitle = tr("Autonomous tracking by the phone lying on the robot", "Suivi autonome par le téléphone posé sur le robot"), enabled = linkIdle)
                InfoNote(if (legs) tr("Mowing, signal surveys, transits and returns: the gateway follows the route prepared here; a Wi-Fi outage does not stop the robot " +
                    "(automatic stop after ${fr.luba.poc.autonomy.GatewayAutonomy.ORPHAN_LIMIT_MS / 60_000} min without a pilot). Bypass and recharge are still driven from here; docking is left to the Mammotion return.",
                    "Tonte, relevé de signaux, transits et retours : la passerelle suit le trajet préparé ici ; une coupure Wi-Fi n'arrête pas le robot " +
                    "(arrêt automatique après ${fr.luba.poc.autonomy.GatewayAutonomy.ORPHAN_LIMIT_MS / 60_000} min sans pilote). Contournement et recharge restent pilotés d'ici ; l'accostage est confié au retour Mammotion.")
                    else tr("Routes driven frame by frame from here: a Wi-Fi outage of more than ${GatewayProtocol.SILENCE_MS / 1000} s stops the robot.",
                        "Trajets pilotés trame à trame d'ici : une coupure Wi-Fi de plus de ${GatewayProtocol.SILENCE_MS / 1000} s arrête le robot."))
            }
            SettingsGroup(tr("ESP32 gateway updates", "Mises à jour de la passerelle ESP32")) {
                var otaMowing by remember { mutableStateOf(GatewayOta.load(context)) }
                ToggleRow(tr("Updates during mowing", "Mises à jour pendant une tonte"), otaMowing, { otaMowing = it; GatewayOta.set(context, it) },
                    subtitle = if (otaMowing) tr("Accepted: robot stopped, gateway restarted, mowing resumed automatically",
                        "Acceptées : robot arrêté, passerelle redémarrée, tonte reprise automatiquement")
                        else tr("Refused while the robot is being driven", "Refusées tant que le robot est piloté"))
                InfoNote(if (otaMowing) tr("During mowing, an ESP32 gateway update (pio run -e ota -t upload) stops the robot " +
                    "(blades then speed), the gateway restarts and reconnects to the robot; mowing then resumes by itself from " +
                    "its saved progress (${GatewayOta.RESUME_WINDOW_MS / 60_000} min at most, otherwise the Resume button). With the robot stopped, " +
                    "the update is always accepted.",
                    "Pendant une tonte, une mise à jour de la passerelle ESP32 (pio run -e ota -t upload) arrête le robot " +
                    "(lames puis vitesse), la passerelle redémarre et se reconnecte au robot ; la tonte reprend ensuite d'elle-même depuis " +
                    "son avancement sauvegardé (${GatewayOta.RESUME_WINDOW_MS / 60_000} min au plus, sinon bouton Reprendre). Robot arrêté, " +
                    "la mise à jour est toujours acceptée.")
                    else tr("An update sent while piloting is refused by the gateway; it is accepted with the robot stopped. " +
                    "Setting sent to the gateway on every connection (ESP32 gateway only).",
                    "Une mise à jour envoyée pendant un pilotage est refusée par la passerelle ; elle est acceptée robot arrêté. " +
                    "Réglage transmis à la passerelle à chaque connexion (passerelle ESP32 seulement)."))
            }
        }
        GatewayRole.OFF -> Unit
    }
}

/** Gateway phone kept silent: volumes at minimum and "Do not disturb", restored as soon as Android changes them. */
@Composable
private fun SilenceRow() {
    val context = LocalContext.current
    var keep by remember { mutableStateOf(SilenceKeeper.keep(context)) }
    var granted by remember { mutableStateOf(SilenceKeeper.policyGranted(context)) }
    LaunchedEffect(Unit) { while (true) { granted = SilenceKeeper.policyGranted(context); kotlinx.coroutines.delay(2_000) } }
    ToggleRow(tr("Keep this phone silent", "Garder ce téléphone silencieux"), keep, { keep = it; SilenceKeeper.setKeep(context, it) },
        subtitle = when {
            !keep -> tr("Sound left as is", "Son laissé libre")
            granted -> tr("Ringer, notifications, alarms and media at minimum, \"Do not disturb\": restored at once if they change",
                "Sonnerie, notifications, alarmes et médias au minimum, « Ne pas déranger » : remis aussitôt s'ils changent")
            else -> tr("Volumes at minimum only: no \"Do not disturb\" access", "Volumes au minimum seulement : accès « Ne pas déranger » absent")
        })
    if (keep && !granted) {
        LogLines(listOf("adb shell cmd notification allow_dnd ${context.packageName}"), title = tr("Or from the computer", "Ou depuis l'ordinateur"))
        TextAction(tr("Allow \"Do not disturb\"", "Autoriser « Ne pas déranger »"), { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
    }
    val events by SilenceKeeper.lastEvents.collectAsStateWithLifecycle()
    LogLines(events.asReversed())
}

/** Phone wireless debugging: turned back on by itself when Android turns it off (Wi-Fi loss, restart). Group placed at the end of the Gateway page. */
@Composable
fun WirelessDebugGroup() {
    val context = LocalContext.current
    var keep by remember { mutableStateOf(WirelessDebugKeeper.keep(context)) }
    var enabled by remember { mutableStateOf(WirelessDebugKeeper.enabled(context)) }
    val canWrite = remember { WirelessDebugKeeper.canWrite(context) }
    LaunchedEffect(Unit) { while (true) { enabled = WirelessDebugKeeper.enabled(context); kotlinx.coroutines.delay(2_000) } }
    SettingsGroup(tr("Wireless debugging", "Débogage sans fil")) {
        ToggleRow(tr("Keep wireless debugging on", "Garder le débogage sans fil actif"), keep, { keep = it; WirelessDebugKeeper.setKeep(context, it) }, enabled = canWrite,
            subtitle = when {
                !canWrite -> tr("Permission missing", "Permission absente")
                enabled -> tr("On", "Actif") + if (keep) tr(" · turned back on automatically if turned off", " · rallumé automatiquement s'il est coupé") else ""
                keep -> tr("Off: turned back on as soon as the Wi-Fi is connected", "Coupé : rallumé dès que le Wi-Fi est connecté")
                else -> tr("Off", "Coupé")
            })
        if (!canWrite) LogLines(listOf("adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"), title = tr("Allow from the computer", "Autoriser depuis l'ordinateur"))
        val events by WirelessDebugKeeper.lastEvents.collectAsStateWithLifecycle()
        LogLines(events.asReversed())
    }
}
