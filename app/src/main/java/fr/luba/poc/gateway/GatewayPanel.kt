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
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Router
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

/**
 * What this phone is for, as offered to the user: it drives the robot (through the paired gateway when there is one and
 * it is turned on, otherwise over direct Bluetooth: roles CLIENT and OFF), or it lies on the robot (GATEWAY).
 */
private enum class PhoneUse { PILOT, GATEWAY }

private fun PhoneUse.short() = when (this) {
    PhoneUse.PILOT -> tr("Pilot", "Pilote")
    PhoneUse.GATEWAY -> tr("Gateway", "Passerelle")
}

/** Tone of a gateway message: failure or refusal as a warning, success in green (French or English wording). */
private fun messageTone(text: String) = when {
    text.startsWith("Échec") || text.contains("refusé") || text.contains("nécessaires") ||
        text.startsWith("Failed") || text.contains("refused") || text.contains("required") -> NoteTone.WARNING
    text.contains("jointe") || text.contains("reached") -> NoteTone.SUCCESS
    else -> NoteTone.INFO
}

/**
 * What this phone is for: pilot (through the paired gateway, or direct Bluetooth) or gateway lying on the robot.
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
        Segmented(PhoneUse.entries.map { it to it.short() }, if (config.role == GatewayRole.GATEWAY) PhoneUse.GATEWAY else PhoneUse.PILOT, { use ->
            message = ""
            when (use) {
                PhoneUse.GATEWAY -> if (config.role != GatewayRole.GATEWAY) becomeGateway()
                // Back to piloting: through the paired gateway if there is one, otherwise direct Bluetooth.
                PhoneUse.PILOT -> if (config.role == GatewayRole.GATEWAY) {
                    GatewayService.stop(context)
                    save(config.copy(role = if (config.paired) GatewayRole.CLIENT else GatewayRole.OFF))
                }
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
        if (message.isNotBlank() && config.role == GatewayRole.GATEWAY) StatusNote(message, messageTone(message))
    }
    // Pilot: the gateway is an option of piloting, not a separate role. Turned off, the pairing is kept.
    if (config.role != GatewayRole.GATEWAY) SettingsGroup(tr("Gateway on the robot", "Passerelle sur le robot")) {
        if (config.paired) {
            val through = config.role == GatewayRole.CLIENT
            ToggleRow(tr("Go through the gateway", "Passer par la passerelle"), through, { on -> message = ""; save(config.copy(role = if (on) GatewayRole.CLIENT else GatewayRole.OFF)) },
                subtitle = if (through) tr("Gateway ${config.host.ifBlank { "found on the Wi-Fi" }}; direct Bluetooth while it does not answer",
                    "Passerelle ${config.host.ifBlank { "trouvée sur le Wi-Fi" }} ; Bluetooth direct tant qu'elle ne répond pas")
                    else tr("Off: direct Bluetooth only, a few meters from the robot", "Désactivé : Bluetooth direct seulement, à quelques mètres du robot"),
                enabled = linkIdle && !busy)
            TextAction(tr("Forget this gateway", "Oublier cette passerelle"), {
                message = ""; save(config.copy(role = GatewayRole.OFF, host = "", pilotCode = ""))
            }, Modifier.fillMaxWidth(), enabled = linkIdle && !busy, danger = true)
        } else InfoNote(tr("No gateway paired: direct Bluetooth, a few meters from the robot. Pair the gateway below to control the robot across the garden.",
            "Aucune passerelle appairée : Bluetooth direct, à quelques mètres du robot. Appairez la passerelle ci-dessous pour piloter le robot dans tout le jardin."))
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
                var qr by remember { mutableStateOf(false) }
                GatewayPairing.ofThisGateway(context, status.address.takeIf { status.running })?.let { pairing ->
                    if (qr) QrImage(pairing.uri(), tr("Pairing QR code", "QR code d'appairage"), Modifier.fillMaxWidth(0.7f))
                    TextAction(if (qr) tr("Hide the QR code", "Masquer le QR code") else tr("Show the pairing QR code", "Afficher le QR code d'appairage"), { qr = !qr }, Modifier.fillMaxWidth())
                }
                KeyValueRow(tr("Battery exemption", "Exemption batterie"), if (exempt) tr("active", "active") else tr("missing", "absente"))
                if (!exempt) StatusNote(tr("Without a battery exemption, the gateway may be suspended when the screen is off.",
                    "Sans exemption batterie, la passerelle peut être suspendue écran éteint."), NoteTone.WARNING)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    if (status.running) SecondaryPillButton(tr("Stop", "Arrêter"), { GatewayService.stop(context) }, Modifier.weight(1f))
                    else PrimaryPillButton(tr("Start", "Démarrer"), { becomeGateway() }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                    SecondaryPillButton(tr("Battery", "Batterie"), { requestBatteryExemption(context) }, Modifier.weight(1f))
                }
            }
            GatewayHealthDetails(rememberLocalGatewayHealth(), tr("Health of this phone (reported to the pilot)", "Santé de ce téléphone (remontée au pilote)"))
            SettingsGroup(tr("Gateway settings", "Réglages de la passerelle")) {
                var keepScreen by remember { mutableStateOf(GatewayConfig.keepScreen(context)) }
                ToggleRow(tr("Keep the screen on while piloting", "Garder l'écran allumé pendant le pilotage"), keepScreen, { keepScreen = it; GatewayConfig.setKeepScreen(context, it) },
                    subtitle = tr("Off: saves the battery (~8 %/h with the screen on). Screen off, the Wi-Fi may lag by a few hundred ms; no effect on autonomous tracking, only noticeable for the sequences still driven from the pilot phone (bypass, docking).",
                        "Désactivé : économise la batterie (~8 %/h écran allumé). Écran éteint, le Wi-Fi peut prendre quelques centaines de ms de retard ; sans effet sur le suivi autonome, sensible seulement pour les séquences encore pilotées depuis le téléphone de pilotage (contournement, accostage)."))
                SilenceRow()
            }
            val events = status.lastEvents.takeLast(12).asReversed()
            if (events.isNotEmpty()) SettingsGroup(tr("Gateway log", "Journal de la passerelle")) { LogLines(events) }
        }
        GatewayRole.CLIENT, GatewayRole.OFF -> {
            var host by remember(config.role) { mutableStateOf(config.host) }
            var code by remember(config.role) { mutableStateOf(config.pilotCode) }
            LaunchedEffect(host, code) { kotlinx.coroutines.delay(400); save(config.copy(host = host.trim(), pilotCode = code)) }
            // Gateways announced on the Wi-Fi: listed by name so the pilot picks one instead of typing an address.
            var announced by remember { mutableStateOf<List<GatewayEndpoint>?>(null) }
            var discoverToken by remember { mutableStateOf(0) }
            LaunchedEffect(discoverToken) {
                announced = null
                announced = withContext(Dispatchers.IO) { runCatching { GatewayClient.discoverAll(context, 3000) }.getOrDefault(emptyList()) }
            }
            fun findAndTest(target: GatewayConfig) {
                busy = true; message = tr("Searching for the gateway…", "Recherche de la passerelle…")
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { runCatching { GatewayClient.find(context, target) } }
                    message = outcome.fold({ it }, { tr("Failed: ${it.message ?: it.javaClass.simpleName}", "Échec : ${it.message ?: it.javaClass.simpleName}") })
                    // Gateway reached with this code: piloting goes through it from now on.
                    if (outcome.isSuccess) GatewayConfig.read(context).takeIf { it.role == GatewayRole.OFF }?.let { GatewayConfig.save(context, it.copy(role = GatewayRole.CLIENT)) }
                    config = GatewayConfig.read(context); host = config.host; busy = false
                }
            }
            var scanning by remember { mutableStateOf(false) }
            if (scanning) QrScanDialog(onPairing = { pairing ->
                scanning = false
                val paired = GatewayPairing.apply(context, pairing)
                config = paired; host = paired.host; code = paired.pilotCode
                findAndTest(paired)
            }, onDismiss = { scanning = false })
            SettingsGroup(tr("Pairing", "Appairage")) {
                PrimaryPillButton(tr("Scan the gateway QR code", "Scanner le QR code de la passerelle"), { scanning = true }, Modifier.fillMaxWidth(),
                    enabled = linkIdle && !busy, icon = Icons.Rounded.QrCodeScanner)
                InfoNote(tr("The QR code is on the home screen of the gateway phone. It fills the address and the code, then tests the connection.",
                    "Le QR code est sur l'écran d'accueil du téléphone passerelle. Il remplit l'adresse et le code, puis teste la connexion."))
                SecondaryPillButton(tr("Pair the ESP32 (PWR button)", "Appairer l'ESP32 (bouton PWR)"), {
                    busy = true; message = tr("Searching for an ESP32 open for pairing…", "Recherche d'une ESP32 ouverte à l'appairage…")
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { runCatching { GatewayButtonPairing.pair(context) } }
                        message = outcome.fold({ tr("ESP32 gateway ${it.host} paired, code saved", "Passerelle ESP32 ${it.host} appairée, code enregistré") },
                            { tr("Failed: ${it.message ?: it.javaClass.simpleName}", "Échec : ${it.message ?: it.javaClass.simpleName}") })
                        config = GatewayConfig.read(context); host = config.host; code = config.pilotCode; busy = false
                    }
                }, Modifier.fillMaxWidth(), enabled = linkIdle && !busy, icon = Icons.Rounded.Router)
                InfoNote(tr("ESP32 gateway: briefly press its PWR button; once the LED double-flashes (about 20 s), tap this button within 3 minutes. No code to type.",
                    "Passerelle ESP32 : appuyez brièvement sur son bouton PWR ; dès que la LED clignote par deux (une vingtaine de secondes), touchez ce bouton dans les 3 minutes. Aucun code à saisir."))
            }
            SettingsGroup(tr("Gateways on this Wi-Fi", "Passerelles sur ce Wi-Fi")) {
                when {
                    announced == null -> { LubaProgress(Modifier.fillMaxWidth()); InfoNote(tr("Searching the Wi-Fi…", "Recherche sur le Wi-Fi…")) }
                    announced!!.isEmpty() -> InfoNote(tr("No gateway announced on this Wi-Fi. Check that the gateway phone is on the same Wi-Fi with the Gateway role started.",
                        "Aucune passerelle annoncée sur ce Wi-Fi. Vérifiez que le téléphone passerelle est sur le même Wi-Fi, rôle Passerelle démarré."))
                    else -> announced!!.forEach { endpoint ->
                        NavRow(GatewayProtocol.displayName(endpoint.name, endpoint.host), icon = Icons.Rounded.Router, subtitle = "${endpoint.host}:${endpoint.port}",
                            value = if (endpoint.host == host.trim()) tr("chosen", "choisie") else tr("Choose", "Choisir"), enabled = linkIdle && !busy,
                            onClick = { host = endpoint.host })
                    }
                }
                TextAction(tr("Search again", "Rechercher à nouveau"), { discoverToken++ }, Modifier.fillMaxWidth())
            }
            SettingsGroup(tr("Connection to the gateway", "Connexion à la passerelle")) {
                LubaTextField(host, { host = it }, tr("Gateway address", "Adresse de la passerelle"), enabled = linkIdle && !busy,
                    placeholder = tr("found automatically on the Wi-Fi", "trouvée automatiquement sur le Wi-Fi"))
                LubaTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) }, tr("Code shown on the gateway", "Code affiché sur la passerelle"), enabled = linkIdle && !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                SecondaryPillButton(if (busy) tr("Searching…", "Recherche…") else tr("Find and test", "Trouver et tester"), {
                    findAndTest(config.copy(host = host.trim(), pilotCode = code))
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
            if (config.role == GatewayRole.CLIENT) GatewayHealthDetails(rememberPilotGatewayHealth(pilot = true), reach = rememberPilotGatewayReach(pilot = true),
                version = GatewayHealthStore.gatewayVersion.collectAsStateWithLifecycle().value)
            if (config.role == GatewayRole.CLIENT) SettingsGroup(tr("Routes", "Trajets")) {
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
            if (config.role == GatewayRole.CLIENT) SettingsGroup(tr("ESP32 gateway updates", "Mises à jour de la passerelle ESP32")) {
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
    }
}

/**
 * Asks Android to exempt the app from battery optimization (system dialog for this app). Falls back to the list of
 * optimized apps if the dialog is not available on this phone.
 */
@android.annotation.SuppressLint("BatteryLife")
fun requestBatteryExemption(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
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

/**
 * Confirmation of a pairing link opened from outside the app (another app read the QR code, shared link): nothing is
 * changed before the user accepts. The robot must be disconnected, as for any role change.
 */
@Composable
fun PairingConfirmDialog(pairing: GatewayPairing, linkIdle: Boolean, isGateway: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LubaDialog(onDismissRequest = onDismiss, title = tr("Use this gateway?", "Utiliser cette passerelle ?"), content = {
        Text(tr("Control the robot through the gateway ${pairing.label} (${pairing.host}).", "Piloter le robot via la passerelle ${pairing.label} (${pairing.host})."),
            style = MaterialTheme.typography.bodyLarge)
        InfoNote(tr("This phone becomes the pilot: the robot search, the connection and the missions go through the gateway mounted on the robot.",
            "Ce téléphone devient le pilote : la recherche du robot, la connexion et les missions passent par la passerelle posée sur le robot."))
        if (isGateway) StatusNote(tr("This phone is the gateway right now: it stops being one.", "Ce téléphone est actuellement la passerelle : il cesse de l'être."), NoteTone.WARNING)
        if (!linkIdle) StatusNote(tr("Disconnect the robot first (Service → Bluetooth connection).", "Déconnectez d'abord le robot (Service → Connexion Bluetooth)."), NoteTone.WARNING)
    }, footer = {
        PrimaryPillButton(tr("Use this gateway", "Utiliser cette passerelle"), {
            if (isGateway) GatewayService.stop(context)
            onConfirm()
        }, Modifier.fillMaxWidth(), enabled = linkIdle)
    })
}
