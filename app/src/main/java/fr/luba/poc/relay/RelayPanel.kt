package fr.luba.poc.relay

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens

/** Relay to the home server (read-only web page): emits its titled groups into the calling page. */
@Composable
fun RelayPanel() {
    val context = LocalContext.current
    val status by RelayService.status.collectAsStateWithLifecycle()
    val initial = remember { RelayConfig.read(context) }
    var url by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.url) }
    var code by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.code) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(url, code, status.running) {
        if (!status.running) {
            kotlinx.coroutines.delay(400)
            runCatching { RelayConfig.save(context, RelayConfig(url.trim(), code, initial.id)) }
        }
    }
    val power = context.getSystemService(PowerManager::class.java)
    fun start() {
        try {
            RelayConfig.save(context, RelayConfig(url.trim(), code, initial.id))
            context.startForegroundService(Intent(context, RelayService::class.java)); error = ""
        }
        catch (e: Exception) { error = tr("Relay refused: ${e.javaClass.simpleName}", "Relais refusé : ${e.javaClass.simpleName}") }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) grants[Manifest.permission.BLUETOOTH_CONNECT] == true && grants[Manifest.permission.BLUETOOTH_SCAN] == true
            else grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (bluetoothGranted) start() else error = tr("Bluetooth permissions are required.", "Les permissions Bluetooth sont nécessaires.")
    }
    val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    SettingsGroup(tr("Relay status", "État du relais")) {
        InfoNote(tr("Independent of the robot gateway: this phone sends the robot state it sees (position, map, battery) " +
            "to the home server, viewable in a browser. No command goes through it.",
            "Indépendant de la passerelle robot : ce téléphone envoie l’état du robot qu’il voit (position, carte, batterie) " +
            "au serveur maison, consultable dans un navigateur. Aucune commande ne passe par là."))
        KeyValueRow(tr("Status", "État"), status.message)
        status.lastSentAt?.let { KeyValueRow(tr("Last sent", "Dernier envoi"), java.text.DateFormat.getTimeInstance().format(java.util.Date(it))) }
        if (status.running) {
            KeyValueRow(tr("Battery exemption", "Exemption batterie"), if (exempt) tr("active", "active") else tr("missing", "absente"))
            if (!exempt) StatusNote(tr("Battery exemption missing: set it up before a test with the screen off.", "Exemption batterie absente : à configurer avant un essai écran éteint."), NoteTone.WARNING)
        }
    }
    SettingsGroup(tr("Relay settings", "Réglages du relais")) {
        LubaTextField(url, { url = it }, tr("Home server", "Serveur maison"), enabled = !status.running, placeholder = "http://192.168.1.10:8765")
        LubaTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) }, tr("Six-digit code", "Code à six chiffres"), enabled = !status.running,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        InfoNote(tr("Same code on the server and its web page; address and code are saved automatically.", "Même code sur le serveur et sa page web ; adresse et code sont mémorisés automatiquement."))
        InfoNote(tr("Connect the robot (Service → Bluetooth connection) and read its map. Bluetooth stays active with the screen off; local tests stop in the background.",
            "Connectez le robot (Service → Connexion Bluetooth) et lisez sa carte. Le Bluetooth reste actif écran éteint ; les essais locaux s’arrêtent en arrière-plan."))
        if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
        val toggle = {
            if (status.running) context.stopService(Intent(context, RelayService::class.java))
            else permissions.launch(if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) +
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            Unit
        }
        if (status.running) SecondaryPillButton(tr("Stop the relay and Bluetooth", "Arrêter le relais et le Bluetooth"), toggle, Modifier.fillMaxWidth())
        else PrimaryPillButton(tr("Start the relay", "Démarrer le relais"), toggle, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            TextAction(tr("Battery optimization", "Optimisation batterie"), { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.weight(1f))
            TextAction(tr("Android settings", "Réglages Android"), { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.weight(1f))
        }
    }
}
