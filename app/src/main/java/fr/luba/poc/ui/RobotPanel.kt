package fr.luba.poc.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.Clock
import fr.luba.poc.EventHealth
import fr.luba.poc.auth.AccountMode
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.camera.CameraPage
import fr.luba.poc.gateway.GatewayConfig
import fr.luba.poc.gateway.GatewayHealthStrip
import fr.luba.poc.gateway.GatewayPanel
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.GatewayService
import fr.luba.poc.gateway.WirelessDebugGroup
import fr.luba.poc.gateway.WirelessDebugKeeper
import fr.luba.poc.gateway.rememberPilotGatewayHealth
import fr.luba.poc.gateway.rememberPilotGatewayReach
import fr.luba.poc.history.MowingHistoryPage
import fr.luba.poc.history.MowingHistoryRow
import fr.luba.poc.i18n.tr
import fr.luba.poc.manual.DrivenRecordPage
import fr.luba.poc.manual.JoystickPanel
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.SignalSurveyPage
import fr.luba.poc.protocol.ModelSupport
import fr.luba.poc.protocol.RobotErrorResponse
import fr.luba.poc.protocol.SelfCheck
import fr.luba.poc.relay.RelayPanel
import fr.luba.poc.relay.RelayService
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.NumericStyle
import fr.luba.poc.weather.WeatherDebugContent
import fr.luba.poc.weather.WeatherService
import fr.luba.poc.weather.robotGeo
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Pages of the Service tab; the key is kept in the saved state. [parent]: page the back action returns to
 * (the summary by default); the summary entries are grouped in [ServiceSection].
 */
enum class ServicePage(val key: String, private val titleEn: String, private val titleFr: String, val parent: String = "") {
    HUB("", "Service", "Service"),
    // Connection
    CONNECTION("Robot", "Bluetooth connection", "Connexion Bluetooth"),
    SETUP("Verification", "Check the setup", "Vérifier l'installation"),
    RELAY("Passerelle", "Wi-Fi gateway", "Passerelle Wi-Fi"),
    HOME_SERVER("Serveur", "Home server", "Serveur maison"),
    // Robot
    MANUAL("Pilotage", "Manual control", "Pilotage manuel"),
    DRIVEN_RECORD("EnregistrementPilote", "Record by driving", "Enregistrer en pilotant", parent = "Pilotage"),
    HEIGHT("Hauteur", "Cutting height", "Hauteur de coupe"),
    CAMERA("Camera", "Camera", "Caméra"),
    MAINTENANCE("Entretien", "Maintenance", "Entretien"),
    // Garden and history
    HISTORY("Historique", "Task history", "Historique des tâches"),
    OBSTACLES("Obstacles", "Obstacles", "Obstacles"),
    OBSTACLE_SIM("SimObstacle", "Obstacle simulation", "Simulation d'obstacle", parent = "Obstacles"),
    SIGNALS("Signaux", "Signal surveys", "Relevés de signaux"),
    WEATHER("Meteo", "Rain and weather", "Pluie et météo"),
    // Supervised tests
    WATCHDOG("Watchdog", "Command cutoff", "Coupure des commandes"),
    NAVIGATION("Navigation", "Navigation test", "Essai de navigation"),
    BORDER("Bordure", "Border following", "Suivi de bordure"),
    OBSTACLE_TESTS("EssaisObstacle", "Obstacle tests", "Essais d'obstacle"),
    BLADES("Lames", "Blade test", "Essai des lames"),
    /** Finishing of the gaps of a mowing, opened from its report (no entry in the summary). */
    MOW_DEBUG("TonteDebug", "Finishing the gaps", "Finition des manques"),
    // Diagnostics
    CAMPAIGN("Tests", "Bluetooth diagnostics", "Diagnostic Bluetooth"),
    TELEMETRY("Télémétrie", "Detailed telemetry", "Télémétrie détaillée"),
    SENSORS("Capteurs", "Sensors and perception", "Capteurs et perception"),
    LOGS("Logs", "Logs", "Journaux"),
    ADVANCED("Réglages", "Advanced settings", "Paramètres avancés");

    /** Page title in the current language. */
    val title: String get() = tr(titleEn, titleFr)

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: HUB }
}

/** Pages that command the robot: "robot not connected" banner and "mowing in progress" guard at the top. */
private val ROBOT_ESSAYS = setOf(ServicePage.MANUAL, ServicePage.HEIGHT, ServicePage.WATCHDOG, ServicePage.NAVIGATION, ServicePage.BORDER,
    ServicePage.OBSTACLE_TESTS, ServicePage.BLADES, ServicePage.MOW_DEBUG, ServicePage.CAMPAIGN)

/**
 * Service tab: a summary (robot card, then five groups by intent) and pages sharing one layout —
 * conditions, safety warning, tracking, settings (supervision last), results, notes; main action
 * fixed at the bottom. Robot commands and guards are unchanged; only the presentation is grouped.
 */
@Composable
fun RobotPanel(
    robot: RobotBle,
    state: RobotUi,
    tab: String,
    onTabChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    cloud: CloudViewModel? = null,
    cloudState: CloudUi = CloudUi(),
    /** Signal surveys: open the map with the layers, or prepare a dedicated survey route there. */
    onShowSignalsOnMap: () -> Unit = {},
    onSignalSurveyRoute: () -> Unit = {},
) {
    val page = ServicePage.of(tab)
    val back = { onTabChange(page.parent) }
    BackHandler(enabled = page != ServicePage.HUB, onBack = back)
    var refusal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.navTest.status) {
        if (state.navTest.status.startsWith(tr("Navigation refused", "Navigation refusée"))) refusal = state.navTest.status
    }
    LaunchedEffect(state.navTest.preview) {
        if (state.navTest.preview.startsWith(tr("Preview unavailable", "Aperçu impossible"))) refusal = state.navTest.preview
    }
    LaunchedEffect(state.teach.status) {
        if (state.teach.status.startsWith(tr("Test refused", "Test refusé"))) refusal = state.teach.status
    }
    refusal?.let { message ->
        // Closed with the header's cross; the footer keeps only the useful action.
        LubaDialog(onDismissRequest = { refusal = null }, title = tr("Action blocked", "Action bloquée"), content = {
            StatusNote(message, tone = NoteTone.WARNING)
            if (message.contains(tr("limit", "limite")) || message.contains("exclusion"))
                InfoNote(tr("Move the robot more than one meter away from the boundaries and exclusions with manual control, then try again.", "Placez le robot à plus d'un mètre des limites et des exclusions avec le pilotage manuel, puis recommencez."))
        }, footer = {
            if (message.contains(tr("locked after STOP", "verrouillé après STOP")))
                PrimaryPillButton(tr("Unlock", "Déverrouiller"), { refusal = null; robot.unlockAfterStop() }, Modifier.fillMaxWidth())
            else PrimaryPillButton(tr("Open manual control", "Ouvrir le pilotage manuel"), { refusal = null; onTabChange(ServicePage.MANUAL.key) }, Modifier.fillMaxWidth())
        })
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) robot.export(uri)
    }
    val archive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) robot.export(uri, archive = true)
    }
    val exportArchive = { archive.launch("luba-diagnostics-${Clock.now()}.zip") }
    val rawArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) robot.export(uri, archive = true, anonymize = false)
    }

    if (page == ServicePage.HUB) { ServiceHub(robot, state, modifier, onTabChange); return }

    // Guards shared by the tests: connection and no robot mission running.
    val busy = state.robotRoute.running || state.manualActive || state.mowing.active || state.navTest.running ||
        state.teach.running || state.campaign.running || state.bladeTestActive || state.mapLoading || state.silenceTest.running
    @Composable fun ColumnScope.requirements() {
        if (page in ROBOT_ESSAYS && !state.ready)
            StatusNote(tr("Robot not connected: this page unlocks as soon as it is (gateway or direct Bluetooth).", "Robot non connecté : cette page se débloque dès qu'il l'est (passerelle ou Bluetooth direct)."), tone = NoteTone.WARNING)
        if (page in ROBOT_ESSAYS && page != ServicePage.MANUAL && (state.mowing.active || state.robotRoute.running))
            SafetyNotice(tr("Mowing is in progress: finish it from the map before starting a test.", "Une tonte est en cours : terminez-la depuis la carte avant de lancer un essai."))
    }

    when (page) {
        ServicePage.CONNECTION -> ConnectionPage(robot, state, back) { onTabChange(ServicePage.RELAY.key) }
        ServicePage.SETUP -> SetupCheckPage(robot, state, back, onTabChange)
        ServicePage.RELAY -> PushedPage(page.title, back, modifier) {
            GatewayPanel(linkIdle = state.connection == ConnectionState.Disconnected, disconnect = robot::disconnect)
        }
        ServicePage.HOME_SERVER -> PushedPage(page.title, back, modifier) { RelayPanel() }
        ServicePage.MANUAL -> ManualPage(robot, state, back, modifier) { onTabChange(ServicePage.DRIVEN_RECORD.key) }
        ServicePage.DRIVEN_RECORD -> DrivenRecordPage(robot, state, back, modifier)
        ServicePage.HEIGHT -> BladesPage(robot, state, back, exportArchive, listOf(BladeBench.HEIGHT), page.title) { requirements() }
        ServicePage.CAMERA -> if (AccountMode.observe()) PushedPage(page.title, back, modifier) { InfoNote(AccountMode.message) }
            else if (cloud != null) CameraPage(robot, state, cloud, cloudState, back)
            else PushedPage(page.title, back, modifier) { InfoNote(tr("Mammotion account unavailable on this screen.", "Compte Mammotion indisponible sur cet écran.")) }
        ServicePage.MAINTENANCE -> MaintenancePage(state, back, modifier)
        ServicePage.HISTORY -> MowingHistoryPage(state.mowingHistory, state.mowingRecord, state.selected?.address,
            back, onOpen = { robot.showTaskDetail(it) }, modifier = modifier, loadTrack = robot::loadTaskTrack)
        ServicePage.OBSTACLES -> ObstacleListPage(robot, state, back) { onTabChange(ServicePage.OBSTACLE_SIM.key) }
        ServicePage.OBSTACLE_SIM -> ObstacleSimPage(state, back)
        ServicePage.SIGNALS -> SignalSurveyPage(state, back, onShowSignalsOnMap, onSignalSurveyRoute)
        ServicePage.WEATHER -> PushedPage(page.title, back, modifier) { WeatherDebugContent(robotGeo(state.telemetry)) }
        ServicePage.WATCHDOG -> WatchdogPage(robot, state, back) { requirements() }
        ServicePage.NAVIGATION -> NavigationPage(robot, state, back, busy) { requirements() }
        ServicePage.BORDER -> BorderDebugPage(robot, state, back) { requirements() }
        ServicePage.OBSTACLE_TESTS -> ObstaclesPage(robot, state, back) { requirements() }
        ServicePage.BLADES -> BladesPage(robot, state, back, exportArchive, listOf(BladeBench.STATIC, BladeBench.MOVING), page.title) { requirements() }
        ServicePage.MOW_DEBUG -> MowDebugPage(robot, state, back) { requirements() }
        ServicePage.CAMPAIGN -> CampaignPage(robot, state, back, exportArchive) { requirements() }
        ServicePage.TELEMETRY -> TelemetryPage(state, back, modifier)
        ServicePage.SENSORS -> SensorsPage(robot, state, back, exportArchive)
        ServicePage.LOGS -> PushedPage(page.title, back, modifier, bottomBar = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("JSONL log", "Journal JSONL"), { export.launch("luba-m1-${Clock.now()}.jsonl") }, Modifier.weight(1f), enabled = !state.campaign.running)
                PrimaryPillButton(tr("Export all (ZIP)", "Tout exporter (ZIP)"), exportArchive, Modifier.weight(1f), enabled = !state.campaign.running, icon = Icons.Rounded.IosShare)
            }
        }) {
            SettingsGroup(title = tr("Latest events", "Derniers événements")) {
                if (state.events.isEmpty()) InfoNote(tr("No events.", "Aucun événement."))
                LogLines(state.events.asReversed())
            }
            InfoNote(tr("Full log kept on the phone (frames, decoded messages, delays, tests); the latest 150 events are shown here. " +
                "The ZIP archive adds the test reports.",
                "Journal complet conservé sur le téléphone (trames, messages décodés, délais, essais) ; les 150 derniers événements s'affichent ici. " +
                "L'archive ZIP ajoute les rapports d'essais."))
            InfoNote(tr("Exports are anonymized and can be attached to a public ticket as is: robot name and serial number, " +
                "GPS position, Wi-Fi, IP and MAC addresses, account data and raw frames are removed, obstacle photos are left out. " +
                "The map stays in the robot's local frame.",
                "Les exports sont anonymisés et peuvent être joints tels quels à un ticket public : nom et numéro de série du robot, " +
                "position GPS, Wi-Fi, adresses IP et MAC, données du compte et trames brutes sont retirés, les photos d'obstacles sont écartées. " +
                "La carte reste dans le repère local du robot."))
            TextAction(tr("Raw archive (not anonymized, with photos)", "Archive brute (non anonymisée, avec photos)"),
                { rawArchive.launch("luba-diagnostics-raw-${Clock.now()}.zip") }, enabled = !state.campaign.running)
        }
        ServicePage.ADVANCED -> AdvancedPage(robot, state, back, modifier)
        ServicePage.HUB -> Unit
    }
}

/**
 * Manual control, page shared by the Device tab (shortcut from the home screen and the map) and Service:
 * joysticks, entry to the dedicated "record by driving" page ([onOpenRecord]), then stopping a task started from the
 * Mammotion app.
 */
@Composable
fun ManualPage(robot: RobotBle, state: RobotUi, back: () -> Unit, modifier: Modifier = Modifier, onOpenRecord: () -> Unit = {}) {
    PushedPage(ServicePage.MANUAL.title, back, modifier) {
        if (!state.ready) StatusNote(tr("Robot not connected: control unlocks as soon as it is (gateway or direct Bluetooth).", "Robot non connecté : le pilotage se débloque dès qu'il l'est (passerelle ou Bluetooth direct)."), tone = NoteTone.WARNING)
        JoystickPanel(robot, state) {
            SettingsGroup(title = tr("Record by driving", "Enregistrer en pilotant")) {
                val trace = state.driven
                NavRow(if (trace != null) tr("Resume the recording", "Reprendre l'enregistrement") else tr("Record a zone, passage or no-go", "Enregistrer une zone, un passage ou une no-go"),
                    icon = Icons.Rounded.Gamepad,
                    subtitle = tr("Dedicated page: map following the robot, camera and joysticks; kept on this phone only",
                        "Page dédiée : carte qui suit le robot, caméra et joysticks ; gardé sur ce téléphone uniquement"),
                    value = trace?.let { "${it.kind.label} · ${MapCorrections.meters(it.lengthM)} m" }, onClick = onOpenRecord)
            }
        }
        SettingsGroup(title = tr("Mammotion task", "Tâche Mammotion")) {
            SecondaryPillButton(tr("Stop the Mammotion task", "Arrêter la tâche Mammotion"), robot::stopMammotionTask, Modifier.fillMaxWidth(),
                enabled = state.ready && !state.taskStopPending && !state.manualActive && !state.robotRoute.running && !state.navTest.running)
            if (state.taskStopStatus.isNotBlank()) StatusNote(state.taskStopStatus)
            else InfoNote(tr("Stops a task started from the Mammotion app before controlling the robot from here.", "Arrête une tâche lancée depuis l'app Mammotion avant de piloter le robot d'ici."))
        }
    }
}

@Composable
private fun TelemetryPage(state: RobotUi, back: () -> Unit, modifier: Modifier) {
    PushedPage(ServicePage.TELEMETRY.title, back, modifier) {
        if (!state.ready) StatusNote(tr("Robot not connected: last values received.", "Robot non connecté : dernières valeurs reçues."), tone = NoteTone.WARNING)
        val t = state.telemetry
        SettingsGroup(title = tr("Last robot error", "Dernière erreur robot")) {
            val report = state.lastRobotError
            if (report == null) StatusNote(tr("No error since the app was opened.", "Aucune erreur depuis l'ouverture de l'application."), tone = NoteTone.SUCCESS)
            else {
                val info = report.info
                val serious = info.response == RobotErrorResponse.STOP || info.response == RobotErrorResponse.SAFETY
                KeyValueRow(tr("Code · time", "Code · heure"), "${info.code} · ${Format.time(report.receivedAt)}")
                KeyValueRow(tr("Meaning", "Signification"), info.label, valueColor = if (serious) MammotionColors.Danger else MammotionColors.TextPrimary)
                KeyValueRow(tr("Category", "Catégorie"), info.category.label)
                KeyValueRow(tr("App response", "Réponse de l'application"), when (info.response) {
                    RobotErrorResponse.LOG -> tr("log only", "journal seulement")
                    RobotErrorResponse.WARN -> tr("alert without stopping", "alerte sans arrêt")
                    RobotErrorResponse.STOP -> tr("movement stopped and alert", "arrêt des déplacements et alerte")
                    RobotErrorResponse.SAFETY -> tr("lift / bumper safety", "sécurité soulèvement / pare-chocs")
                })
                KeyValueRow(tr("Advice", "Conseil"), info.advice)
            }
        }
        SettingsGroup(title = tr("Status", "État")) {
            KeyValueRow(tr("Robot state (code)", "État robot (code)"), t.robotState?.toString() ?: "—")
            KeyValueRow(tr("Self-check", "Autotest"), t.selfCheck?.let { v -> SelfCheck.name(v)?.let { "$v · $it" } ?: v.toString() } ?: "—")
            KeyValueRow(tr("Sensors (code)", "Capteurs (code)"), t.sensorStatus?.toString() ?: "—")
            KeyValueRow(tr("Blades", "Lames"), t.sensorStatus?.let { if ((it shr 9) and 7 == 0) tr("stopped", "arrêtées") else tr("non-zero state", "état non nul") } ?: tr("unknown", "inconnu"))
        }
        SettingsGroup(title = "Position") {
            KeyValueRow(tr("RTK status · quality", "RTK état · qualité"), "${t.rtkStatus ?: "—"} · ${t.positioningLevel ?: "—"}")
            KeyValueRow(tr("Local position", "Position locale"), "${t.x?.let { Format.num(it, 3) } ?: "—"} / ${t.y?.let { Format.num(it, 3) } ?: "—"} m")
            KeyValueRow(tr("Heading · type", "Cap · type"), "${t.heading?.let { "${Format.num(it, 1)}°" } ?: "—"} · ${t.positionType ?: "—"}")
            KeyValueRow(tr("Position rate", "Fréquence position"), "${Format.num(t.positionRate, 2)} Hz")
        }
        SettingsGroup(title = tr("Reception", "Réception")) {
            KeyValueRow(tr("Last telemetry", "Dernière télémétrie"), t.lastReportAt?.let(Format::time) ?: tr("never", "jamais"))
            KeyValueRow(tr("Last state", "Dernier état"), t.lastDeviceAt?.let(Format::time) ?: tr("never", "jamais"))
            KeyValueRow(tr("Last position", "Dernière position"), t.lastPositionAt?.let(Format::time) ?: tr("never", "jamais"))
            KeyValueRow(tr("Age of the last report", "Âge du dernier rapport"), state.ageMs?.let { "${Format.num(it / 1000.0)} s" } ?: "—")
            KeyValueRow(tr("Reports · commands · interruptions", "Rapports · commandes · coupures"), "${t.reports} · ${state.commandsSent} · ${state.interruptions}")
        }
    }
}

/** Link settings and tools for the development phone (wireless debugging). */
@Composable
private fun AdvancedPage(robot: RobotBle, state: RobotUi, back: () -> Unit, modifier: Modifier) {
    PushedPage(ServicePage.ADVANCED.title, back, modifier) {
        SafetyNotice(tr("An app crash does not guarantee that STOP is sent: keep the physical stop within reach.", "Un plantage de l'application ne garantit pas l'envoi de STOP : gardez l'arrêt physique accessible."))
        SettingsGroup(title = tr("Bluetooth link", "Liaison Bluetooth")) {
            SliderRow(tr("Telemetry validity delay", "Délai de validité de la télémétrie"), state.timeoutSeconds.toFloat(), { robot.setTimeout(it.toInt()) },
                valueRange = 5f..30f, valueLabel = "${state.timeoutSeconds} s", steps = 24, enabled = state.connection == ConnectionState.Disconnected)
            if (state.connection != ConnectionState.Disconnected) InfoNote(tr("Can be changed with the robot disconnected.", "Modifiable robot déconnecté."))
            LubaDivider()
            KeyValueRow(tr("GATT delay", "Délai GATT"), tr("1.5 s", "1,5 s"))
            KeyValueRow(tr("Connection", "Connexion"), "15 s")
            KeyValueRow(tr("BLE sync", "Synchronisation BLE"), "5 s")
            KeyValueRow(tr("Report renewal", "Renouvellement des rapports"), "8 s")
            KeyValueRow(tr("Requested reports", "Rapports demandés"), tr("1 s, 200 ms during routes and tests (actual rate measured)", "1 s, 200 ms pendant les trajets et les essais (fréquence réelle mesurée)"))
        }
        SettingsGroup(title = tr("Test limits", "Limites des essais")) {
            KeyValueRow(tr("Minimum battery", "Batterie minimale"), "30 %")
            InfoNote(tr("Bluetooth diagnostics keep the robot stationary; the navigation test, the command cutoff and trial mowing make it move.", "Le diagnostic Bluetooth laisse le robot immobile ; l'essai de navigation, la coupure des commandes et la tonte d'essai le font rouler."))
        }
        // Development tool: only once the adb permission has been granted (otherwise it can do nothing).
        if (WirelessDebugKeeper.canWrite(LocalContext.current)) WirelessDebugGroup()
    }
}

/** Summary: robot card (opens the connection), then five groups by intent, from everyday use to diagnostics. */
@Composable
private fun ServiceHub(robot: RobotBle, state: RobotUi, modifier: Modifier, open: (String) -> Unit) {
    val colors = MammotionColors
    val context = LocalContext.current
    val gateway by GatewayService.status.collectAsStateWithLifecycle()
    val relay by RelayService.status.collectAsStateWithLifecycle()
    val role = remember { GatewayConfig.read(context).role }
    val pilot = role == GatewayRole.CLIENT
    val link = linkStatus(state, role, gateway, rememberPilotGatewayReach(pilot))
    val connected = state.connection != ConnectionState.Disconnected
    // Robot reachable (gateway or direct Bluetooth): the entries that command or read it unlock.
    val ready = state.ready
    val robotName = link.robotName?.takeIf { it.isNotBlank() } ?: state.selected?.name?.takeIf { it.isNotBlank() }
        ?: state.lastKnown?.robotName?.takeIf { it.isNotBlank() }
    val model = RobotModel.detect(robotName)
    @Composable fun Entry(page: ServicePage, icon: ImageVector, subtitle: String,
                          value: String? = null, enabled: Boolean = true) =
        NavRow(page.title, icon = icon, subtitle = subtitle, value = value, enabled = enabled) { open(page.key) }
    Column(modifier.fillMaxSize().background(colors.Background).verticalScroll(rememberScrollState()).padding(Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        ScreenTitle("Service", subtitle = tr("Connection, settings, tests and diagnostics", "Connexion, réglages, essais et diagnostics"))
        // Robot card: a tap opens the Bluetooth connection.
        Surface(onClick = { open(ServicePage.CONNECTION.key) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = colors.Surface, border = BorderStroke(1.dp, colors.Hairline)) {
            Column {
            Row(Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                Box(Modifier.size(width = 84.dp, height = 56.dp).background(colors.SurfaceMuted, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                    Image(painterResource(model.imageRes()), contentDescription = model.label,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(link.robotName ?: if (connected || link.connected) model.label else tr("No robot connected", "Aucun robot connecté"), style = MaterialTheme.typography.titleMedium)
                    if (model.named && link.robotName != null) Text(model.label, style = MaterialTheme.typography.bodySmall, color = colors.TextTertiary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (link.connected) colors.Green else if (connected || link.connecting) colors.Warning else colors.TextTertiary, CircleShape))
                        Text(link.summary, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                    }
                }
                state.telemetry.battery?.let { Text("$it %", style = NumericStyle.copy(fontSize = 18.sp)) }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
            }
            // Gateway on the robot (pilot role): its health under the robot, separated by a hairline.
            rememberPilotGatewayHealth(pilot)?.let { health ->
                HorizontalDivider(color = colors.Hairline)
                GatewayHealthStrip(health, Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md))
            }
            }
        }
        // Read-only mode of a model that is not validated (docs/features/supported-models.md).
        ModelSupport.readOnlyNote(robotName, model.label)?.let { StatusNote(it, tone = NoteTone.WARNING) }
        // Owner of such a model: the three supervised tests that can validate it.
        ModelTrialGroup(robot, state, model.label, open)
        // Groups by intent; one-line subtitles, status on the right only when useful.
        SettingsGroup(title = tr("Connection", "Connexion")) {
            Entry(ServicePage.CONNECTION, Icons.Rounded.Bluetooth, tr("Search, connect, disconnect", "Rechercher, connecter, déconnecter"))
            Entry(ServicePage.SETUP, Icons.Rounded.Checklist, tr("Wi-Fi, gateway, robot and safety, link by link", "Wi-Fi, passerelle, robot et sécurité, maillon par maillon"))
            Entry(ServicePage.RELAY, Icons.Rounded.Router, tr("Relay phone placed on the robot", "Téléphone relais posé sur le robot"),
                value = when (role) {
                    GatewayRole.OFF -> tr("pilot · direct", "pilote · direct")
                    GatewayRole.GATEWAY -> if (gateway.running) tr("gateway", "passerelle") else tr("stopped", "arrêtée")
                    GatewayRole.CLIENT -> tr("pilot · gateway", "pilote · passerelle")
                })
            Entry(ServicePage.HOME_SERVER, Icons.Rounded.Dns, tr("Robot status on a local web page", "État du robot sur une page web locale"),
                value = if (relay.running) tr("active", "actif") else null)
        }
        SettingsGroup(title = "Robot") {
            Entry(ServicePage.MANUAL, Icons.Rounded.Gamepad, tr("Joysticks, blades stopped", "Joysticks, lames arrêtées"), enabled = ready)
            Entry(ServicePage.HEIGHT, Icons.Rounded.Height, tr("Setpoint and deck sequences", "Consigne et séquences du plateau"), enabled = ready,
                value = state.telemetry.bladeHeightMm?.let { "$it mm" })
            // Video only goes through the Mammotion cloud: no entry in local mode.
            if (!AccountMode.observe()) Entry(ServicePage.CAMERA, Icons.Rounded.Videocam, tr("Live video through the Mammotion account", "Vidéo en direct par le compte Mammotion"))
            Entry(ServicePage.MAINTENANCE, Icons.Rounded.Build, tr("Blade wear, mileage, battery cycles", "Usure des lames, kilométrage, cycles batterie"))
        }
        SettingsGroup(title = tr("Garden and history", "Jardin et historique")) {
            MowingHistoryRow(state.mowingHistory) { open(ServicePage.HISTORY.key) }
            Entry(ServicePage.OBSTACLES, Icons.Rounded.Block, tr("Saved, encountered, photos", "Enregistrés, rencontrés, photos"),
                value = if (state.obstacles.isEmpty()) tr("none", "aucun") else "${state.obstacles.size}")
            Entry(ServicePage.SIGNALS, Icons.Rounded.WifiTethering, tr("Wi-Fi, RTK and Bluetooth on the map", "Wi-Fi, RTK et Bluetooth sur la carte"),
                value = state.signalSurvey.samples.size.takeIf { it > 0 && state.signalSurvey.live }?.let { Format.plural(it, tr("sample", "mesure"), tr("samples", "mesures")) })
            WeatherHubRow { open(ServicePage.WEATHER.key) }
        }
        SettingsGroup(title = tr("Supervised tests", "Essais supervisés")) {
            Entry(ServicePage.WATCHDOG, Icons.Rounded.TimerOff, tr("Stops on its own if the app goes silent", "Arrêt seul si l'application se tait"), enabled = ready,
                value = when { state.silenceTest.running -> tr("in progress", "en cours"); state.motorWatchdog?.demonstrated == true -> tr("demonstrated", "démontré"); state.motorWatchdog != null -> tr("not demonstrated", "non démontré"); else -> tr("to test", "à tester") })
            Entry(ServicePage.NAVIGATION, Icons.Rounded.Route, tr("Short route, blades stopped", "Parcours court, lames arrêtées"), enabled = ready,
                value = if (state.navTest.running && !state.navTest.tonte && state.navTest.courseObstacleId == null) tr("in progress", "en cours") else null)
            Entry(ServicePage.BORDER, Icons.Rounded.Timeline, tr("One border, deviation measured", "Une bordure, écart mesuré"), enabled = ready)
            Entry(ServicePage.OBSTACLE_TESTS, Icons.Rounded.Sensors, tr("Bumper, learn, bypass", "Pare-chocs, apprendre, contourner"), enabled = ready,
                value = if (state.teach.running || state.navTest.courseObstacleId != null && state.navTest.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.BLADES, Icons.Rounded.ContentCut, tr("Discs while stationary, then cutting on the move", "Disques à l'arrêt, puis coupe en roulant"), enabled = ready,
                value = if (state.bladeTestActive || state.navTest.running && state.navTest.tonte) tr("in progress", "en cours") else null)
        }
        SettingsGroup(title = tr("Diagnostics", "Diagnostic")) {
            Entry(ServicePage.CAMPAIGN, Icons.AutoMirrored.Rounded.FactCheck, tr("Link, interruptions, reconnections", "Liaison, coupures, reconnexions"), enabled = ready,
                value = if (state.campaign.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.TELEMETRY, Icons.Rounded.Insights, tr("Errors, codes, position, reception", "Erreurs, codes, position, réception"))
            Entry(ServicePage.SENSORS, Icons.Rounded.Radar, tr("Ultrasonic sensors, vision (exploration)", "Ultrasons, vision (exploration)"), enabled = ready,
                value = if (state.sensorProbe.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.LOGS, Icons.Rounded.Description, tr("View and export", "Consulter et exporter"))
            Entry(ServicePage.ADVANCED, Icons.Rounded.Tune, tr("Link delays and test limits", "Délais de liaison et limites des essais"))
        }
        EventHealthGroup(robot)
    }
}

/**
 * Aggregate health of the launch (audit of 2026-10-30): stop requests, failures, last link loss and average route
 * preparation, read from the event log every 30 s instead of scrolling the raw JSONL. A failure leaves the line in
 * warning tone; a healthy launch stays quiet.
 */
@Composable
private fun EventHealthGroup(robot: RobotBle) {
    var health by remember { mutableStateOf<EventHealth?>(null) }
    LaunchedEffect(robot) {
        while (true) {
            val next = suspendCancellableCoroutine<EventHealth> { continuation ->
                robot.eventHealth { if (continuation.isActive) continuation.resume(it) }
            }
            health = next
            delay(30_000)
        }
    }
    val current = health ?: return
    SettingsGroup(title = tr("This launch", "Ce lancement")) {
        KeyValueRow(tr("Events", "Événements"), current.events.toString())
        KeyValueRow(tr("STOP requests", "Demandes de STOP"), current.stops.toString())
        KeyValueRow(tr("Failures", "Échecs"), current.failures.toString())
        if (current.lastLinkLossAt > 0) KeyValueRow(tr("Last link loss", "Dernière perte de liaison"), Format.time(current.lastLinkLossAt))
        current.averagePreparationMs?.let { KeyValueRow(tr("Average preparation", "Préparation moyenne"), "${Format.num(it / 1000.0)} s") }
        if (current.failures > 0) {
            StatusNote(tr("${current.failures} failure(s) this launch, link losses included: see Logs for the detail.",
                "${current.failures} échec(s) sur ce lancement, pertes de liaison comprises : voir Journaux pour le détail."), tone = NoteTone.WARNING)
        } else {
            StatusNote(tr("No failure recorded since launch.", "Aucun échec enregistré depuis le lancement."), tone = NoteTone.INFO)
        }
    }
}

/** Initial status message of [RobotUi] (no search or connection made yet). */
private val IDLE_STATUS = RobotUi().status

private fun connectionShort(state: RobotUi) = when {
    state.ready -> tr("Connected", "Connecté")
    state.connection == ConnectionState.Disconnected -> tr("Disconnected", "Déconnecté")
    else -> tr("Connecting…", "Connexion…")
}

@Composable
private fun ConnectionPage(robot: RobotBle, state: RobotUi, back: () -> Unit, openGateway: () -> Unit) {
    val context = LocalContext.current
    var permissionStatus by remember { mutableStateOf("") }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { permissionStatus = ""; robot.scan(autoConnect = true) }
        else permissionStatus = tr("Allow Bluetooth in the Android settings (Android 8 to 11: location turned on).", "Autorisez le Bluetooth dans les paramètres Android (Android 8 à 11 : localisation activée).")
    }
    val disconnected = state.connection == ConnectionState.Disconnected
    // Search with automatic connection if a single robot is found (logic in RobotBle).
    val startScan = {
        permissions.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    // Automatic search when the page opens and no robot is connected.
    LaunchedEffect(Unit) {
        if (disconnected && !state.scanning && !state.campaign.running) startScan()
    }
    PushedPage(ServicePage.CONNECTION.title, back, bottomBar = {
        if (disconnected) PrimaryPillButton(if (state.scanning) tr("Searching…", "Recherche…") else tr("Search for robots", "Rechercher les robots"), startScan, Modifier.fillMaxWidth(), enabled = !state.scanning && !state.campaign.running, icon = Icons.AutoMirrored.Rounded.BluetoothSearching)
        else SecondaryPillButton(tr("Disconnect (stop the robot)", "Déconnecter (arrêt du robot)"), robot::disconnect, Modifier.fillMaxWidth())
    }) {
        if (permissionStatus.isNotBlank()) StatusNote(permissionStatus, tone = NoteTone.WARNING)
        val gatewayRole = remember { GatewayConfig.read(context).role }
        if (gatewayRole == GatewayRole.GATEWAY)
            GatewayRobotNote()
        if (gatewayRole == GatewayRole.CLIENT)
            InfoNote(tr("Search and connection through the gateway (Service → Wi-Fi gateway).", "Recherche et connexion par la passerelle (Service → Passerelle Wi-Fi)."))
        // Direct Bluetooth and a gateway announced on the Wi-Fi: offer to go through it instead of taking the robot's only link.
        val seen = state.gatewaySeen
        if (gatewayRole == GatewayRole.OFF && seen != null && disconnected) {
            StatusNote(tr("A DumbyLuba gateway is announced on this Wi-Fi ($seen).", "Une passerelle DumbyLuba est annoncée sur ce Wi-Fi ($seen)."), tone = NoteTone.INFO)
            PrimaryPillButton(tr("Use this gateway", "Utiliser cette passerelle"), {
                val config = GatewayConfig.read(context)
                GatewayConfig.save(context, config.copy(role = GatewayRole.CLIENT, host = seen))
                openGateway()
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Router)
        }
        // Result of the last search or connection (refusal, gateway unreachable, robot not found…);
        // the default waiting message adds nothing here and is not shown.
        val lastStatus = state.status.takeIf { it.isNotBlank() && it != IDLE_STATUS }
        // List hidden once connected if there is nothing to show (no "No robot found" with a connected robot).
        if (disconnected || state.devices.isNotEmpty()) SettingsGroup(title = tr("Detected robots", "Robots détectés")) {
            if (lastStatus != null) StatusNote(lastStatus)
            if (state.scanning && state.devices.isEmpty()) LubaProgress(Modifier.fillMaxWidth())
            if (state.devices.isEmpty()) InfoNote(if (state.scanning) tr("Searching…", "Recherche en cours…") else tr("No robot found. Move closer to the robot, then start the search.", "Aucun robot détecté. Approchez-vous du robot puis lancez la recherche."))
            state.devices.forEach { device ->
                NavRow(device.name, icon = Icons.Rounded.SmartToy,
                    subtitle = "${device.address} · ${device.rssi?.let { "$it dBm" } ?: tr("paired, signal unknown", "appairé, signal inconnu")}",
                    value = if (state.selected?.address == device.address && !disconnected) connectionShort(state) else tr("Connect", "Connecter"),
                    onClick = { if (disconnected && !state.campaign.running) robot.connect(device) })
            }
        } else if (lastStatus != null) StatusNote(lastStatus)
        val t = state.telemetry
        SettingsGroup(title = tr("Link", "Liaison")) {
            KeyValueRow(tr("Status", "État"), state.connection.label)
            KeyValueRow("Transport", if (!state.ready) "—" else if (state.viaGateway) tr("Wi-Fi gateway → Bluetooth", "Passerelle Wi-Fi → Bluetooth") else tr("Direct Bluetooth", "Bluetooth direct"))
            KeyValueRow("Robot", state.selected?.name ?: "—")
            KeyValueRow(tr("Bluetooth identifier", "Identifiant Bluetooth"), state.selected?.address ?: "—")
            KeyValueRow("Signal · MTU", "${state.rssi?.let { "$it dBm" } ?: "—"} · ${state.mtu}")
            KeyValueRow(tr("Battery", "Batterie"), t.battery?.let { "$it %" } ?: "—")
            KeyValueRow("Firmware", t.firmware ?: "—")
        }
    }
}

@Composable
private fun WeatherHubRow(onClick: () -> Unit) {
    val w by WeatherService.flow.collectAsStateWithLifecycle()
    NavRow(ServicePage.WEATHER.title, icon = Icons.Rounded.Umbrella, subtitle = tr("Radar, alert during mowing", "Radar, alerte pendant la tonte"),
        value = if (w.location == null) tr("to set up", "à configurer") else w.assessment.level.title, onClick = onClick)
}

/** Gateway phone: status of the robot link held by the service, and releasing the robot. */
@Composable
private fun GatewayRobotNote() {
    val context = LocalContext.current
    val gateway by GatewayService.status.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf("") }
    InfoNote(tr("This phone is the gateway placed on the robot: it holds the Bluetooth link (reconnected automatically) " +
        "and the pilot phone connects to it over Wi-Fi.",
        "Ce téléphone est la passerelle posée sur le robot : il tient la liaison Bluetooth (reconnectée automatiquement) " +
        "et le téléphone de pilotage s'y branche en Wi-Fi."))
    SettingsGroup(title = tr("Gateway link", "Liaison de la passerelle")) {
        if (gateway.running) KeyValueRow("Robot", gateway.robot.label)
        else StatusNote(tr("Gateway stopped: start it in Service → Wi-Fi gateway.", "Passerelle arrêtée : la démarrer dans Service → Passerelle Wi-Fi."), tone = NoteTone.WARNING)
        if (gateway.robot.address != null) {
            SecondaryPillButton(tr("Release the robot", "Libérer le robot"), {
                refused = GatewayService.releaseRobot(context).orEmpty()
            }, Modifier.fillMaxWidth())
            InfoNote(tr("Hands the robot back to the Mammotion app or to direct Bluetooth (no more automatic reconnection).", "Rend le robot à l'app Mammotion ou au Bluetooth direct (plus de reconnexion automatique)."))
        }
        if (refused.isNotBlank()) StatusNote(refused, tone = NoteTone.WARNING)
    }
}
