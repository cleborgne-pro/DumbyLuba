package fr.luba.poc.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.gateway.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app goes once the assistant is closed. */
enum class WizardExit { HOME, DEMO_MAP, SETUP_CHECK }

/** Pages of the assistant; the path depends on the use chosen on [WELCOME]. */
enum class WizardPage { WELCOME, PILOT_PERMISSIONS, PILOT_GATEWAY, PILOT_DONE, GATEWAY_PERMISSIONS, GATEWAY_PHONE, DIRECT_PERMISSIONS }

/** Permissions needed to search for and connect to the robot over Bluetooth (location for the scan up to Android 11). */
fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Bluetooth plus notifications (Android 13+), asked in one go. */
fun setupPermissions(): Array<String> = bluetoothPermissions() + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

private fun granted(context: Context, permissions: Array<String>) = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/**
 * First-launch assistant: what this phone is for (pilot, phone mounted on the robot, test near the robot, demo), then
 * the permissions and the pairing of that path, each page with one main action. Every page can be skipped; the same
 * settings stay in Service → Wi-Fi gateway. Nothing is sent to the robot.
 */
@Composable
fun SetupWizard(onExit: (WizardExit) -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(WizardPage.WELCOME) }
    fun finish(exit: WizardExit) { SetupProgress.finishWizard(context); onExit(exit) }
    BackHandler(enabled = page != WizardPage.WELCOME) { page = WizardPage.WELCOME }
    val colors = MammotionColors
    Column(Modifier.fillMaxSize().background(colors.Background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        when (page) {
            WizardPage.WELCOME -> {
                ScreenTitle(tr("Welcome to DumbyLuba", "Bienvenue dans DumbyLuba"),
                    subtitle = tr("What is this phone for?", "À quoi sert ce téléphone ?"))
                InfoNote(tr("The robot's Bluetooth only reaches a few meters: to control it across the garden, a second phone (or an ESP32) lies on the robot and relays over Wi-Fi.",
                    "Le Bluetooth du robot ne porte qu'à quelques mètres : pour le piloter dans tout le jardin, un second téléphone (ou un ESP32) posé sur le robot relaie en Wi-Fi."))
                Choice(Icons.Rounded.Smartphone, tr("I control the robot with this phone", "Je pilote le robot avec ce téléphone"),
                    tr("Your everyday phone; it talks to the gateway lying on the robot", "Votre téléphone de tous les jours ; il parle à la passerelle posée sur le robot")) { page = WizardPage.PILOT_PERMISSIONS }
                Choice(Icons.Rounded.Router, tr("This phone goes on the robot", "Ce téléphone va sur le robot"),
                    tr("An old Android phone that holds the robot's Bluetooth (gateway)", "Un ancien téléphone Android qui tient le Bluetooth du robot (passerelle)")) { page = WizardPage.GATEWAY_PERMISSIONS }
                Choice(Icons.Rounded.Bluetooth, tr("Just a test next to the robot", "Juste un essai à côté du robot"),
                    tr("Direct Bluetooth, a few meters away; a gateway can be added later", "Bluetooth direct, à quelques mètres ; une passerelle pourra être ajoutée plus tard")) { page = WizardPage.DIRECT_PERMISSIONS }
                Choice(Icons.Rounded.Map, tr("Explore without a robot", "Découvrir sans robot"),
                    tr("Demo map: routes, simulation, nothing is sent", "Carte de démonstration : trajets, simulation, rien n'est envoyé")) { finish(WizardExit.DEMO_MAP) }
                SafetyNotice(tr("Unofficial, experimental app driving a dangerous machine: blades stopped, robot in sight and STOP within reach for every first test.",
                    "Application non officielle et expérimentale qui pilote une machine dangereuse : lames arrêtées, robot en vue et STOP à portée pour chaque premier essai."))
                TextAction(tr("Skip, I will set it up later", "Passer, je réglerai plus tard"), { finish(WizardExit.HOME) }, Modifier.fillMaxWidth())
            }
            WizardPage.PILOT_PERMISSIONS -> PermissionsPage(tr("Allow Bluetooth and notifications", "Autoriser le Bluetooth et les notifications"),
                tr("Bluetooth to find the robot next to you, notifications for the alerts (robot stuck, battery, link lost).",
                    "Le Bluetooth pour trouver le robot à côté de vous, les notifications pour les alertes (robot bloqué, batterie, liaison perdue)."),
                onBack = { page = WizardPage.WELCOME }) { page = WizardPage.PILOT_GATEWAY }
            WizardPage.PILOT_GATEWAY -> PilotGatewayPage(onBack = { page = WizardPage.PILOT_PERMISSIONS }, onPaired = { page = WizardPage.PILOT_DONE },
                onDirect = { finish(WizardExit.HOME) })
            WizardPage.PILOT_DONE -> {
                ScreenTitle(tr("Paired", "Appairé"), subtitle = tr("The robot connects through the gateway on its own.", "Le robot se connecte par la passerelle tout seul."))
                StatusNote(tr("Next: check the setup (Wi-Fi, placement of the gateway, safety), then follow the list \"Before the first mowing\" on the home screen.",
                    "Ensuite : vérifiez l'installation (Wi-Fi, placement de la passerelle, sécurité), puis suivez la liste « Avant la première tonte » de l'accueil."), NoteTone.SUCCESS)
                PrimaryPillButton(tr("Check the setup", "Vérifier l'installation"), { finish(WizardExit.SETUP_CHECK) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Checklist)
                SecondaryPillButton(tr("Go to the home screen", "Aller à l'accueil"), { finish(WizardExit.HOME) }, Modifier.fillMaxWidth())
            }
            WizardPage.GATEWAY_PERMISSIONS -> PermissionsPage(tr("Allow Bluetooth and notifications", "Autoriser le Bluetooth et les notifications"),
                tr("This phone will hold the robot's Bluetooth link; a permanent notification shows the gateway is running.",
                    "Ce téléphone tiendra la liaison Bluetooth du robot ; une notification permanente montre que la passerelle tourne."),
                onBack = { page = WizardPage.WELCOME }) {
                GatewayConfig.save(context, GatewayConfig.read(context).copy(role = GatewayRole.GATEWAY))
                GatewayService.start(context)
                page = WizardPage.GATEWAY_PHONE
            }
            WizardPage.GATEWAY_PHONE -> GatewayPhonePage(onBack = { page = WizardPage.GATEWAY_PERMISSIONS }) { finish(WizardExit.HOME) }
            WizardPage.DIRECT_PERMISSIONS -> PermissionsPage(tr("Allow Bluetooth and notifications", "Autoriser le Bluetooth et les notifications"),
                tr("Close the Mammotion app first: the robot accepts only one Bluetooth link. The app then connects on its own to the only robot in range.",
                    "Fermez d'abord l'app Mammotion : le robot n'accepte qu'une liaison Bluetooth. L'application se connecte ensuite seule au seul robot à portée."),
                onBack = { page = WizardPage.WELCOME }) { finish(WizardExit.HOME) }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MammotionColors
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.Hairline)) {
        Row(Modifier.padding(Dimens.Lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Box(Modifier.size(44.dp).background(colors.GreenSoft, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.Ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
        }
    }
}

/** One permission request for the path, then [onNext]; a refusal is explained and the path can go on. */
@Composable
private fun PermissionsPage(title: String, why: String, onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(context, bluetoothPermissions())) onNext() else refused = true
    }
    BackRow(onBack)
    ScreenTitle(title)
    InfoNote(why)
    if (refused) StatusNote(tr("Bluetooth refused: the robot cannot be found. Allow it in the Android settings (Android 8 to 11: location on), or continue and allow it later.",
        "Bluetooth refusé : le robot ne peut pas être trouvé. Autorisez-le dans les paramètres Android (Android 8 à 11 : localisation activée), ou continuez et autorisez-le plus tard."), NoteTone.WARNING)
    PrimaryPillButton(tr("Allow", "Autoriser"), {
        if (granted(context, setupPermissions())) onNext() else launcher.launch(setupPermissions())
    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check)
    if (refused) SecondaryPillButton(tr("Continue anyway", "Continuer quand même"), onNext, Modifier.fillMaxWidth())
}

/** Pilot: pairing by QR code (tested at once), or direct Bluetooth for now. */
@Composable
private fun PilotGatewayPage(onBack: () -> Unit, onPaired: () -> Unit, onDirect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    if (scanning) QrScanDialog(onPairing = { pairing ->
        scanning = false; testing = true; failure = null
        val config = GatewayPairing.apply(context, pairing)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { GatewayClient.find(context, config) } }
            testing = false
            outcome.onSuccess { onPaired() }.onFailure { failure = it.message ?: it.javaClass.simpleName }
        }
    }, onDismiss = { scanning = false })
    BackRow(onBack)
    ScreenTitle(tr("Pair the gateway", "Appairer la passerelle"))
    InfoNote(tr("On the phone that goes on the robot, install DumbyLuba and choose \"This phone goes on the robot\": its screen shows a QR code. Scan it from here.",
        "Sur le téléphone qui va sur le robot, installez DumbyLuba et choisissez « Ce téléphone va sur le robot » : son écran affiche un QR code. Scannez-le d'ici."))
    PrimaryPillButton(if (testing) tr("Testing the connection…", "Test de la connexion…") else tr("Scan the gateway QR code", "Scanner le QR code de la passerelle"),
        { scanning = true }, Modifier.fillMaxWidth(), enabled = !testing, icon = Icons.Rounded.QrCodeScanner)
    if (testing) LubaProgress(Modifier.fillMaxWidth())
    failure?.let {
        StatusNote(tr("Paired, but the gateway does not answer yet: $it. Both phones must be on the same Wi-Fi; the app keeps trying on its own.",
            "Appairé, mais la passerelle ne répond pas encore : $it. Les deux téléphones doivent être sur le même Wi-Fi ; l'application réessaie seule."), NoteTone.WARNING)
        SecondaryPillButton(tr("Continue", "Continuer"), onPaired, Modifier.fillMaxWidth())
    }
    TextAction(tr("No gateway yet: direct Bluetooth for now", "Pas encore de passerelle : Bluetooth direct pour l'instant"), {
        val config = GatewayConfig.read(context)
        if (config.role == GatewayRole.CLIENT) GatewayConfig.save(context, config.copy(role = GatewayRole.OFF))
        onDirect()
    }, Modifier.fillMaxWidth())
}

/** Gateway phone: battery exemption and "Do not disturb", read again every 2 s (back from the Android screens). */
@Composable
private fun GatewayPhonePage(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(false) }
    var dnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            dnd = SilenceKeeper.policyGranted(context)
            kotlinx.coroutines.delay(2_000)
        }
    }
    BackRow(onBack)
    ScreenTitle(tr("Prepare this phone", "Préparer ce téléphone"), subtitle = tr("It stays on the robot, screen off", "Il reste sur le robot, écran éteint"))
    SettingsGroup {
        KeyValueRow(tr("Battery exemption", "Exemption batterie"), if (exempt) tr("active", "active") else tr("missing", "absente"))
        if (!exempt) {
            InfoNote(tr("Without it, Android may suspend the gateway with the screen off.", "Sans elle, Android peut suspendre la passerelle écran éteint."))
            PrimaryPillButton(tr("Exempt from battery optimization", "Exempter de l'optimisation batterie"), { requestBatteryExemption(context) }, Modifier.fillMaxWidth())
        }
        KeyValueRow(tr("\"Do not disturb\"", "« Ne pas déranger »"), if (dnd) tr("allowed", "autorisé") else tr("optional", "facultatif"))
        if (!dnd) {
            InfoNote(tr("Keeps the phone on the robot silent (calls, notifications). Optional.", "Garde le téléphone posé sur le robot silencieux (appels, notifications). Facultatif."))
            SecondaryPillButton(tr("Allow \"Do not disturb\"", "Autoriser « Ne pas déranger »"),
                { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }, Modifier.fillMaxWidth())
        }
    }
    InfoNote(tr("Next: the home screen shows the QR code to scan from your phone. Then lay this phone flat on top of the robot, screen up, held firmly.",
        "Ensuite : l'écran d'accueil affiche le QR code à scanner depuis votre téléphone. Posez alors ce téléphone à plat sur le robot, écran vers le haut, bien maintenu."))
    PrimaryPillButton(tr("Show the QR code", "Afficher le QR code"), onDone, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCode)
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    TextAction(tr("← Back", "← Retour"), onBack)
}
