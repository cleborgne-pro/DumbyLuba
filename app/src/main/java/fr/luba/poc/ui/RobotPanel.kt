package fr.luba.poc.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.diagnostics.CampaignConfig
import fr.luba.poc.diagnostics.NavConfig
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleIncident
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.obstacles.TeachObstacle
import fr.luba.poc.protocol.RobotErrorResponse
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.util.UUID
import kotlinx.coroutines.delay

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
    MOW_DEBUG("TonteDebug", "Trial mowing (painted zone)", "Tonte d'essai (zone peinte)"),
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
    cloud: fr.luba.poc.auth.CloudViewModel? = null,
    cloudState: fr.luba.poc.auth.CloudUi = fr.luba.poc.auth.CloudUi(),
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
            StatusNote(message, NoteTone.WARNING)
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
    val exportArchive = { archive.launch("luba-diagnostics-${System.currentTimeMillis()}.zip") }
    val rawArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) robot.export(uri, archive = true, anonymize = false)
    }

    if (page == ServicePage.HUB) { ServiceHub(robot, state, modifier, onTabChange); return }

    // Guards shared by the tests: connection and no robot mission running.
    val busy = state.robotRoute.running || state.manualActive || state.mowing.active || state.navTest.running ||
        state.teach.running || state.campaign.running || state.bladeTestActive || state.mapLoading || state.silenceTest.running
    @Composable fun ColumnScope.requirements() {
        if (page in ROBOT_ESSAYS && !state.ready)
            StatusNote(tr("Robot not connected: this page unlocks as soon as it is (gateway or direct Bluetooth).", "Robot non connecté : cette page se débloque dès qu'il l'est (passerelle ou Bluetooth direct)."), NoteTone.WARNING)
        if (page in ROBOT_ESSAYS && page != ServicePage.MANUAL && (state.mowing.active || state.robotRoute.running))
            SafetyNotice(tr("Mowing is in progress: finish it from the map before starting a test.", "Une tonte est en cours : terminez-la depuis la carte avant de lancer un essai."))
    }

    when (page) {
        ServicePage.CONNECTION -> ConnectionPage(robot, state, back) { onTabChange(ServicePage.RELAY.key) }
        ServicePage.SETUP -> SetupCheckPage(robot, state, back, onTabChange)
        ServicePage.RELAY -> PushedPage(page.title, back, modifier) {
            fr.luba.poc.gateway.GatewayPanel(linkIdle = state.connection == ConnectionState.Disconnected, disconnect = robot::disconnect)
        }
        ServicePage.HOME_SERVER -> PushedPage(page.title, back, modifier) { fr.luba.poc.relay.RelayPanel() }
        ServicePage.MANUAL -> ManualPage(robot, state, back, modifier) { onTabChange(ServicePage.DRIVEN_RECORD.key) }
        ServicePage.DRIVEN_RECORD -> fr.luba.poc.manual.DrivenRecordPage(robot, state, back, modifier)
        ServicePage.HEIGHT -> BladesPage(robot, state, back, exportArchive, listOf(BladeBench.HEIGHT), page.title) { requirements() }
        ServicePage.CAMERA -> if (fr.luba.poc.auth.AccountMode.observe()) PushedPage(page.title, back, modifier) { InfoNote(fr.luba.poc.auth.AccountMode.message) }
            else if (cloud != null) fr.luba.poc.camera.CameraPage(robot, state, cloud, cloudState, back)
            else PushedPage(page.title, back, modifier) { InfoNote(tr("Mammotion account unavailable on this screen.", "Compte Mammotion indisponible sur cet écran.")) }
        ServicePage.MAINTENANCE -> MaintenancePage(state, back, modifier)
        ServicePage.HISTORY -> fr.luba.poc.history.MowingHistoryPage(state.mowingHistory, state.mowingRecord, state.selected?.address,
            back, onOpen = { robot.showTaskDetail(it) }, modifier = modifier, loadTrack = robot::loadTaskTrack)
        ServicePage.OBSTACLES -> ObstacleListPage(robot, state, back) { onTabChange(ServicePage.OBSTACLE_SIM.key) }
        ServicePage.OBSTACLE_SIM -> ObstacleSimPage(state, back)
        ServicePage.SIGNALS -> fr.luba.poc.map.SignalSurveyPage(state, back, onShowSignalsOnMap, onSignalSurveyRoute)
        ServicePage.WEATHER -> PushedPage(page.title, back, modifier) { fr.luba.poc.weather.WeatherDebugContent(fr.luba.poc.weather.robotGeo(state.telemetry)) }
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
                SecondaryPillButton(tr("JSONL log", "Journal JSONL"), { export.launch("luba-m1-${System.currentTimeMillis()}.jsonl") }, Modifier.weight(1f), enabled = !state.campaign.running)
                PrimaryPillButton(tr("Export all (ZIP)", "Tout exporter (ZIP)"), exportArchive, Modifier.weight(1f), enabled = !state.campaign.running, icon = Icons.Rounded.IosShare)
            }
        }) {
            SettingsGroup(tr("Latest events", "Derniers événements")) {
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
                { rawArchive.launch("luba-diagnostics-raw-${System.currentTimeMillis()}.zip") }, enabled = !state.campaign.running)
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
        if (!state.ready) StatusNote(tr("Robot not connected: control unlocks as soon as it is (gateway or direct Bluetooth).", "Robot non connecté : le pilotage se débloque dès qu'il l'est (passerelle ou Bluetooth direct)."), NoteTone.WARNING)
        fr.luba.poc.manual.JoystickPanel(robot, state) {
            SettingsGroup(tr("Record by driving", "Enregistrer en pilotant")) {
                val trace = state.driven
                NavRow(if (trace != null) tr("Resume the recording", "Reprendre l'enregistrement") else tr("Record a zone, passage or no-go", "Enregistrer une zone, un passage ou une no-go"),
                    icon = Icons.Rounded.Gamepad,
                    subtitle = tr("Dedicated page: map following the robot, camera and joysticks; kept on this phone only",
                        "Page dédiée : carte qui suit le robot, caméra et joysticks ; gardé sur ce téléphone uniquement"),
                    value = trace?.let { "${it.kind.label} · ${fr.luba.poc.map.MapCorrections.meters(it.lengthM)} m" }, onClick = onOpenRecord)
            }
        }
        SettingsGroup(tr("Mammotion task", "Tâche Mammotion")) {
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
        if (!state.ready) StatusNote(tr("Robot not connected: last values received.", "Robot non connecté : dernières valeurs reçues."), NoteTone.WARNING)
        val t = state.telemetry
        SettingsGroup(tr("Last robot error", "Dernière erreur robot")) {
            val report = state.lastRobotError
            if (report == null) StatusNote(tr("No error since the app was opened.", "Aucune erreur depuis l'ouverture de l'application."), NoteTone.SUCCESS)
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
        SettingsGroup(tr("Status", "État")) {
            KeyValueRow(tr("Robot state (code)", "État robot (code)"), t.robotState?.toString() ?: "—")
            KeyValueRow(tr("Self-check", "Autotest"), t.selfCheck?.let { v -> fr.luba.poc.protocol.SelfCheck.name(v)?.let { "$v · $it" } ?: v.toString() } ?: "—")
            KeyValueRow(tr("Sensors (code)", "Capteurs (code)"), t.sensorStatus?.toString() ?: "—")
            KeyValueRow(tr("Blades", "Lames"), t.sensorStatus?.let { if ((it shr 9) and 7 == 0) tr("stopped", "arrêtées") else tr("non-zero state", "état non nul") } ?: tr("unknown", "inconnu"))
        }
        SettingsGroup("Position") {
            KeyValueRow(tr("RTK status · quality", "RTK état · qualité"), "${t.rtkStatus ?: "—"} · ${t.positioningLevel ?: "—"}")
            KeyValueRow(tr("Local position", "Position locale"), "${t.x?.let { Format.num(it, 3) } ?: "—"} / ${t.y?.let { Format.num(it, 3) } ?: "—"} m")
            KeyValueRow(tr("Heading · type", "Cap · type"), "${t.heading?.let { "${Format.num(it, 1)}°" } ?: "—"} · ${t.positionType ?: "—"}")
            KeyValueRow(tr("Position rate", "Fréquence position"), "${Format.num(t.positionRate, 2)} Hz")
        }
        SettingsGroup(tr("Reception", "Réception")) {
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
        SettingsGroup(tr("Bluetooth link", "Liaison Bluetooth")) {
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
        SettingsGroup(tr("Test limits", "Limites des essais")) {
            KeyValueRow(tr("Minimum battery", "Batterie minimale"), "30 %")
            InfoNote(tr("Bluetooth diagnostics keep the robot stationary; the navigation test, the command cutoff and trial mowing make it move.", "Le diagnostic Bluetooth laisse le robot immobile ; l'essai de navigation, la coupure des commandes et la tonte d'essai le font rouler."))
        }
        // Development tool: only once the adb permission has been granted (otherwise it can do nothing).
        if (fr.luba.poc.gateway.WirelessDebugKeeper.canWrite(androidx.compose.ui.platform.LocalContext.current)) fr.luba.poc.gateway.WirelessDebugGroup()
    }
}

/** Summary: robot card (opens the connection), then five groups by intent, from everyday use to diagnostics. */
@Composable
private fun ServiceHub(robot: RobotBle, state: RobotUi, modifier: Modifier, open: (String) -> Unit) {
    val colors = MammotionColors
    val context = androidx.compose.ui.platform.LocalContext.current
    val gateway by fr.luba.poc.gateway.GatewayService.status.collectAsStateWithLifecycle()
    val relay by fr.luba.poc.relay.RelayService.status.collectAsStateWithLifecycle()
    val role = remember { fr.luba.poc.gateway.GatewayConfig.read(context).role }
    val pilot = role == fr.luba.poc.gateway.GatewayRole.CLIENT
    val link = linkStatus(state, role, gateway, fr.luba.poc.gateway.rememberPilotGatewayReach(pilot))
    val connected = state.connection != ConnectionState.Disconnected
    // Robot reachable (gateway or direct Bluetooth): the entries that command or read it unlock.
    val ready = state.ready
    val robotName = link.robotName?.takeIf { it.isNotBlank() } ?: state.selected?.name?.takeIf { it.isNotBlank() }
        ?: state.lastKnown?.robotName?.takeIf { it.isNotBlank() }
    val model = RobotModel.detect(robotName)
    @Composable fun Entry(page: ServicePage, icon: androidx.compose.ui.graphics.vector.ImageVector, subtitle: String,
                          value: String? = null, enabled: Boolean = true) =
        NavRow(page.title, icon = icon, subtitle = subtitle, value = value, enabled = enabled) { open(page.key) }
    Column(modifier.fillMaxSize().background(colors.Background).verticalScroll(rememberScrollState()).padding(Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        ScreenTitle("Service", subtitle = tr("Connection, settings, tests and diagnostics", "Connexion, réglages, essais et diagnostics"))
        // Robot card: a tap opens the Bluetooth connection.
        Surface(onClick = { open(ServicePage.CONNECTION.key) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = colors.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.Hairline)) {
            Column {
            Row(Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                Box(Modifier.size(width = 84.dp, height = 56.dp).background(colors.SurfaceMuted, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(model.imageRes()), contentDescription = model.label,
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
                state.telemetry.battery?.let { Text("$it %", style = fr.luba.poc.ui.theme.NumericStyle.copy(fontSize = 18.sp)) }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
            }
            // Gateway on the robot (pilot role): its health under the robot, separated by a hairline.
            fr.luba.poc.gateway.rememberPilotGatewayHealth(pilot)?.let { health ->
                androidx.compose.material3.HorizontalDivider(color = colors.Hairline)
                fr.luba.poc.gateway.GatewayHealthStrip(health, Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md))
            }
            }
        }
        // Read-only mode of a model that is not validated (docs/features/supported-models.md).
        fr.luba.poc.protocol.ModelSupport.readOnlyNote(robotName, model.label)?.let { StatusNote(it, NoteTone.WARNING) }
        // Owner of such a model: the three supervised tests that can validate it.
        ModelTrialGroup(robot, state, model.label, open)
        // Groups by intent; one-line subtitles, status on the right only when useful.
        SettingsGroup(tr("Connection", "Connexion")) {
            Entry(ServicePage.CONNECTION, Icons.Rounded.Bluetooth, tr("Search, connect, disconnect", "Rechercher, connecter, déconnecter"))
            Entry(ServicePage.SETUP, Icons.Rounded.Checklist, tr("Wi-Fi, gateway, robot and safety, link by link", "Wi-Fi, passerelle, robot et sécurité, maillon par maillon"))
            Entry(ServicePage.RELAY, Icons.Rounded.Router, tr("Relay phone placed on the robot", "Téléphone relais posé sur le robot"),
                value = when (role) {
                    fr.luba.poc.gateway.GatewayRole.OFF -> tr("pilot · direct", "pilote · direct")
                    fr.luba.poc.gateway.GatewayRole.GATEWAY -> if (gateway.running) tr("gateway", "passerelle") else tr("stopped", "arrêtée")
                    fr.luba.poc.gateway.GatewayRole.CLIENT -> tr("pilot · gateway", "pilote · passerelle")
                })
            Entry(ServicePage.HOME_SERVER, Icons.Rounded.Dns, tr("Robot status on a local web page", "État du robot sur une page web locale"),
                value = if (relay.running) tr("active", "actif") else null)
        }
        SettingsGroup("Robot") {
            Entry(ServicePage.MANUAL, Icons.Rounded.Gamepad, tr("Joysticks, blades stopped", "Joysticks, lames arrêtées"), enabled = ready)
            Entry(ServicePage.HEIGHT, Icons.Rounded.Height, tr("Setpoint and deck sequences", "Consigne et séquences du plateau"), enabled = ready,
                value = state.telemetry.bladeHeightMm?.let { "$it mm" })
            // Video only goes through the Mammotion cloud: no entry in local mode.
            if (!fr.luba.poc.auth.AccountMode.observe()) Entry(ServicePage.CAMERA, Icons.Rounded.Videocam, tr("Live video through the Mammotion account", "Vidéo en direct par le compte Mammotion"))
            Entry(ServicePage.MAINTENANCE, Icons.Rounded.Build, tr("Blade wear, mileage, battery cycles", "Usure des lames, kilométrage, cycles batterie"))
        }
        SettingsGroup(tr("Garden and history", "Jardin et historique")) {
            fr.luba.poc.history.MowingHistoryRow(state.mowingHistory) { open(ServicePage.HISTORY.key) }
            Entry(ServicePage.OBSTACLES, Icons.Rounded.Block, tr("Saved, encountered, photos", "Enregistrés, rencontrés, photos"),
                value = if (state.obstacles.isEmpty()) tr("none", "aucun") else "${state.obstacles.size}")
            Entry(ServicePage.SIGNALS, Icons.Rounded.WifiTethering, tr("Wi-Fi, RTK and Bluetooth on the map", "Wi-Fi, RTK et Bluetooth sur la carte"),
                value = state.signalSurvey.samples.size.takeIf { it > 0 && state.signalSurvey.live }?.let { Format.plural(it, tr("sample", "mesure"), tr("samples", "mesures")) })
            WeatherHubRow { open(ServicePage.WEATHER.key) }
        }
        SettingsGroup(tr("Supervised tests", "Essais supervisés")) {
            Entry(ServicePage.WATCHDOG, Icons.Rounded.TimerOff, tr("Stops on its own if the app goes silent", "Arrêt seul si l'application se tait"), enabled = ready,
                value = when { state.silenceTest.running -> tr("in progress", "en cours"); state.motorWatchdog?.demonstrated == true -> tr("demonstrated", "démontré"); state.motorWatchdog != null -> tr("not demonstrated", "non démontré"); else -> tr("to test", "à tester") })
            Entry(ServicePage.NAVIGATION, Icons.Rounded.Route, tr("Short route, blades stopped", "Parcours court, lames arrêtées"), enabled = ready,
                value = if (state.navTest.running && !state.navTest.tonte && state.navTest.courseObstacleId == null) tr("in progress", "en cours") else null)
            Entry(ServicePage.BORDER, Icons.Rounded.Timeline, tr("One border, deviation measured", "Une bordure, écart mesuré"), enabled = ready)
            Entry(ServicePage.OBSTACLE_TESTS, Icons.Rounded.Sensors, tr("Bumper, learn, bypass", "Pare-chocs, apprendre, contourner"), enabled = ready,
                value = if (state.teach.running || state.navTest.courseObstacleId != null && state.navTest.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.BLADES, Icons.Rounded.ContentCut, tr("Discs while stationary, then cutting on the move", "Disques à l'arrêt, puis coupe en roulant"), enabled = ready,
                value = if (state.bladeTestActive || state.navTest.running && state.navTest.tonte) tr("in progress", "en cours") else null)
            Entry(ServicePage.MOW_DEBUG, Icons.Rounded.Brush, tr("Real mowing of a finger-painted zone", "Vraie tonte d'une zone peinte au doigt"), enabled = ready,
                value = if (state.robotRoute.running) tr("in progress", "en cours") else null)
        }
        SettingsGroup(tr("Diagnostics", "Diagnostic")) {
            Entry(ServicePage.CAMPAIGN, Icons.AutoMirrored.Rounded.FactCheck, tr("Link, interruptions, reconnections", "Liaison, coupures, reconnexions"), enabled = ready,
                value = if (state.campaign.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.TELEMETRY, Icons.Rounded.Insights, tr("Errors, codes, position, reception", "Erreurs, codes, position, réception"))
            Entry(ServicePage.SENSORS, Icons.Rounded.Radar, tr("Ultrasonic sensors, vision (exploration)", "Ultrasons, vision (exploration)"), enabled = ready,
                value = if (state.sensorProbe.running) tr("in progress", "en cours") else null)
            Entry(ServicePage.LOGS, Icons.Rounded.Description, tr("View and export", "Consulter et exporter"))
            Entry(ServicePage.ADVANCED, Icons.Rounded.Tune, tr("Link delays and test limits", "Délais de liaison et limites des essais"))
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
        if (permissionStatus.isNotBlank()) StatusNote(permissionStatus, NoteTone.WARNING)
        val gatewayRole = remember { fr.luba.poc.gateway.GatewayConfig.read(context).role }
        if (gatewayRole == fr.luba.poc.gateway.GatewayRole.GATEWAY)
            GatewayRobotNote()
        if (gatewayRole == fr.luba.poc.gateway.GatewayRole.CLIENT)
            InfoNote(tr("Search and connection through the gateway (Service → Wi-Fi gateway).", "Recherche et connexion par la passerelle (Service → Passerelle Wi-Fi)."))
        // Direct Bluetooth and a gateway announced on the Wi-Fi: offer to go through it instead of taking the robot's only link.
        val seen = state.gatewaySeen
        if (gatewayRole == fr.luba.poc.gateway.GatewayRole.OFF && seen != null && disconnected) {
            StatusNote(tr("A DumbyLuba gateway is announced on this Wi-Fi ($seen).", "Une passerelle DumbyLuba est annoncée sur ce Wi-Fi ($seen)."), NoteTone.INFO)
            PrimaryPillButton(tr("Use this gateway", "Utiliser cette passerelle"), {
                val config = fr.luba.poc.gateway.GatewayConfig.read(context)
                fr.luba.poc.gateway.GatewayConfig.save(context, config.copy(role = fr.luba.poc.gateway.GatewayRole.CLIENT, host = seen))
                openGateway()
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Router)
        }
        // Result of the last search or connection (refusal, gateway unreachable, robot not found…);
        // the default waiting message adds nothing here and is not shown.
        val lastStatus = state.status.takeIf { it.isNotBlank() && it != IDLE_STATUS }
        // List hidden once connected if there is nothing to show (no "No robot found" with a connected robot).
        if (disconnected || state.devices.isNotEmpty()) SettingsGroup(tr("Detected robots", "Robots détectés")) {
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
        SettingsGroup(tr("Link", "Liaison")) {
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

private fun durationLabel(seconds: Int) = if (seconds < 120) "$seconds s" else "${seconds / 60} min"

/**
 * "Command cutoff" test: hold-to-run button. Slow straight-line move, then no movement command at all;
 * telemetry tells whether the firmware stops the robot on its own.
 */
@Composable
private fun WatchdogPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    var linear by remember { mutableIntStateOf(30) }
    var confirmed by remember { mutableStateOf(false) }
    OnLeaveScreen(robot) { robot.stopSilenceTest(tr("Left the screen", "Sortie de l'écran")) }
    val test = state.silenceTest
    val enabled = confirmed && state.ready && !state.robotRoute.running && !state.manualActive && !state.mowing.active &&
        !state.navTest.running && !state.campaign.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading
    PushedPage(ServicePage.WATCHDOG.title, back, bottomBar = {
        HoldToRunButton(tr("Hold for the test (≈ 10 s)", "Maintenir pour l'essai (≈ 10 s)"), tr("Release = immediate STOP", "Relâcher = STOP immédiat"), test.running, enabled || test.running,
            onStart = { robot.startSilenceTest(linear) }, onStop = { robot.stopSilenceTest(tr("Button released", "Bouton relâché")) },
            onCancel = { robot.stopSilenceTest(tr("Gesture interrupted by the system", "Geste interrompu par le système")) })
    }) {
        header()
        SafetyNotice(tr("The robot really moves, then the app deliberately stops sending it commands. " +
            "It may keep going straight for up to ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_DRIFT_M, 1)} m: " +
            "${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.CLEARANCE_M, 1)} m clear in front of it, no people or animals, physical stop within reach.",
            "Le robot roule réellement, puis l'application cesse volontairement de lui envoyer des commandes. " +
            "Il peut continuer tout droit jusqu'à ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_DRIFT_M, 1)} m : " +
            "${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.CLEARANCE_M, 1)} m dégagés devant lui, personne ni animal, arrêt physique à portée de main."))
        SettingsGroup(tr("Last result", "Dernier résultat")) {
            val result = state.motorWatchdog
            if (result == null) StatusNote(tr("No test for this robot: the robot stopping on its own when the app goes silent is not demonstrated.", "Aucun essai pour ce robot : l'arrêt seul du robot quand l'application se tait n'est pas démontré."), NoteTone.WARNING)
            else {
                StatusNote(if (result.demonstrated) tr("Motor watchdog demonstrated", "Watchdog moteur démontré") else when (result.outcome) {
                    fr.luba.poc.diagnostics.MotorWatchdogResult.ROBOT_STOPPED -> tr("Stops on its own, but too slowly", "Arrêt seul, mais trop lent")
                    fr.luba.poc.diagnostics.MotorWatchdogResult.STILL_MOVING -> tr("Does not stop on its own: app STOP required", "Pas d'arrêt seul : STOP de l'application nécessaire")
                    else -> tr("Inconclusive test", "Essai non concluant")
                }, if (result.demonstrated) NoteTone.SUCCESS else NoteTone.DANGER)
                Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                KeyValueRow("Date", Format.time(result.at))
                KeyValueRow("Firmware", result.firmware ?: tr("unknown", "inconnu"))
                KeyValueRow(tr("Commanded speed", "Vitesse commandée"), tr("${result.linearRaw} units", "${result.linearRaw} unités"))
                result.boundMs?.let { KeyValueRow(tr("Stop at the latest", "Arrêt au plus tard"), tr("${Format.num(it / 1000.0, 1)} s after the last command", "${Format.num(it / 1000.0, 1)} s après la dernière commande")) }
                result.driftM?.let { KeyValueRow(tr("Distance after the last moving position", "Distance après la dernière position en mouvement"), "${Format.num(it * 100, 0)} cm") }
                result.speedMps?.let { KeyValueRow(tr("Observed speed", "Vitesse observée"), "${Format.num(it, 2)} m/s") }
            }
        }
        SettingsGroup(tr("Test settings", "Réglages de l'essai")) {
            // Default "No test": already stated by the last result.
            if (test.status.isNotBlank() && test.status != fr.luba.poc.ble.SilenceTestUi().status)
                StatusNote(test.status, if (test.running) NoteTone.WARNING else NoteTone.INFO)
            if (test.running) KeyValueRow("Phase", test.phase)
            test.driftM?.let { KeyValueRow(tr("Deviation since the cutoff", "Écart depuis la coupure"), "${Format.num(it * 100, 0)} cm") }
            SliderRow(tr("Forward speed", "Vitesse d'avance"), linear.toFloat(), { linear = it.toInt() }, 10f..120f, valueLabel = tr("$linear units", "$linear unités"), steps = 10, enabled = !test.running)
            LubaCheckRow(tr("I am supervising on site, ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.CLEARANCE_M, 1)} m clear in front of the robot", "Je supervise sur place, ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.CLEARANCE_M, 1)} m dégagés devant le robot"), confirmed, { confirmed = it }, enabled = !test.running)
        }
        InfoNote(tr("Sequence: 3 s of slow forward movement with the blades stopped, then no movement command at all (no speed, no STOP). " +
            "Stopping on its own is recorded after three stationary positions; otherwise the app sends STOP after " +
            "${fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_SILENCE_MS / 1000} s or ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_DRIFT_M * 100, 0)} cm. " +
            "Positions at 1 Hz: delay bounded to the nearest second. Demonstrated if the stop happens within " +
            "${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.ACCEPTABLE_BOUND_MS / 1000.0, 1)} s.",
            "Déroulé : 3 s d'avance lente lames arrêtées, puis plus aucune commande de mouvement (ni vitesse, ni STOP). " +
            "Arrêt seul constaté après trois positions immobiles ; sinon STOP de l'application après " +
            "${fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_SILENCE_MS / 1000} s ou ${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.MAX_DRIFT_M * 100, 0)} cm. " +
            "Positions à 1 Hz : délai borné à la seconde près. Démontré si l'arrêt intervient en " +
            "${Format.num(fr.luba.poc.diagnostics.CommandSilenceProbe.ACCEPTABLE_BOUND_MS / 1000.0, 1)} s au plus."))
        InfoNote(tr("Simulates a frozen app with Bluetooth kept up. A complete loss of link (phone off, out of range) is not covered: observe it separately, physical stop within reach.", "Simule une application figée, Bluetooth maintenu. Une perte complète de liaison (téléphone éteint, hors de portée) n'est pas couverte : à observer à part, arrêt physique à portée."))
    }
}

@Composable
private fun NavigationPage(robot: RobotBle, state: RobotUi, back: () -> Unit, busy: Boolean, header: @Composable ColumnScope.() -> Unit) {
    var angular by remember { mutableStateOf(300) }
    var automatic by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(300) }
    var radius by remember { mutableStateOf(5f) }
    var confirmed by remember { mutableStateOf(false) }
    var cameraReset by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.ready, state.robotMap.complete, radius) {
        while (true) {
            if (state.ready) robot.previewNavTest(radius.toDouble())
            delay(2000)
        }
    }
    OnLeaveScreen(robot) { robot.stopNavTest(tr("Left the Navigation screen", "Sortie écran Navigation"), userInitiated = true) }
    state.navTest.interruption?.let { reason ->
        LubaDialog(onDismissRequest = robot::acknowledgeNavInterruption, title = tr("Navigation interrupted", "Navigation interrompue"), content = {
            Text(reason)
            Text(tr("The robot received STOP. Distance covered: ${Format.num(state.navTest.observedMeters, 2)} m.", "Le robot a reçu STOP. Distance parcourue : ${Format.num(state.navTest.observedMeters, 2)} m."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }, footer = {
            PrimaryPillButton(tr("Got it", "Compris"), robot::acknowledgeNavInterruption, Modifier.fillMaxWidth())
        })
    }
    val t = state.telemetry
    val enabled = !state.robotRoute.running && !state.manualActive && !state.mowing.active && confirmed && state.ready &&
        !state.campaign.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading
    fun config(auto: Boolean) = NavConfig(linearSpeed = fr.luba.poc.simulation.PathFollower.linearFor(state.trajectorySpeedPercent), angularSpeed = angular, tonte = false,
        heightMm = t.bladeHeightMm ?: 60, maxDurationMs = duration * 1000L, maxRadiusM = radius.toDouble(), automatic = auto)
    PushedPage(ServicePage.NAVIGATION.title, back, bottomBar = {
        if (automatic) {
            if (state.navTest.running) StopAction({ robot.stopNavTest(tr("STOP manual run", "STOP marche manuel"), userInitiated = true) }, Modifier.align(Alignment.End))
            else PrimaryPillButton(tr("Start · ${durationLabel(duration)} · blades stopped", "Démarrer · ${durationLabel(duration)} · lames arrêtées"), { robot.startNavTest(config(true)) }, Modifier.fillMaxWidth(),
                enabled = enabled, icon = Icons.Rounded.PlayArrow)
        } else HoldToRunButton(tr("Hold to drive (${durationLabel(duration)} max)", "Maintenir pour rouler (${durationLabel(duration)} max)"), tr("Release to stop", "Relâcher pour arrêter"), state.navTest.running, enabled,
            onStart = { robot.startNavTest(config(false)) }, onStop = { robot.stopNavTest(tr("Button released", "Bouton relâché"), userInitiated = true) },
            onCancel = { robot.stopNavTest(tr("Gesture interrupted by the system (finger left the button or navigation gesture)", "Geste interrompu par le système (doigt sorti du bouton ou geste de navigation)")) })
    }) {
        header()
        SafetyNotice(tr("The robot really moves. Clear area, no people or animals, physical stop within reach. Automatic stop if tracking deviates by more than 30 cm.", "Le robot roule réellement. Zone dégagée, personne ni animal, arrêt physique accessible. Arrêt automatique si le suivi s'écarte de plus de 30 cm."))
        SettingsGroup(tr("Planned route", "Parcours prévu")) {
            Box(Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
                MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                    trail = state.navTest.trail.map { Point(it.x, it.y) }, start = state.navTest.trail.firstOrNull()?.let { Point(it.x, it.y) },
                    obstacles = state.obstacles, planned = state.navTest.planned, plannedBands = state.navTest.plannedBands,
                    plannedBorders = state.navTest.plannedBorders, interactive = true, cameraReset = cameraReset, modifier = Modifier.fillMaxSize())
                MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (t.x == null || t.y == null) StatusNote(tr("Robot position unavailable: test impossible.", "Position du robot indisponible : essai impossible."), NoteTone.WARNING)
            if (state.navTest.status.isNotBlank()) StatusNote(state.navTest.status,
                when { state.navTest.outcome in setOf("ABORTED", "FAILED") -> NoteTone.DANGER; state.navTest.running -> NoteTone.WARNING; else -> NoteTone.INFO })
            if (!state.navTest.running && state.navTest.preview.isNotBlank()) InfoNote(state.navTest.preview)
            KeyValueRow("Segment", if (state.navTest.running) "${state.navTest.segmentIndex}/${state.navTest.segmentCount}" else "—")
            KeyValueRow(tr("Distance covered", "Distance parcourue"), "${Format.num(state.navTest.observedMeters, 2)} m")
            KeyValueRow(tr("Observed stop delay", "Délai d'arrêt observé"), state.navTest.stopDelayMs?.let { "$it ms" } ?: "—")
            TextAction(tr("Refresh the planned route", "Actualiser le parcours prévu"), { robot.previewNavTest(radius.toDouble()) }, Modifier.offset(x = (-12).dp), enabled = state.ready && !state.navTest.running)
        }
        SettingsGroup(tr("Test settings", "Réglages de l'essai")) {
            Text(tr("Maximum duration", "Durée maximale"), style = MaterialTheme.typography.bodyMedium)
            Segmented(listOf(60, 120, 300, 600).map { it to durationLabel(it) }, duration, { duration = it }, enabled = !state.navTest.running)
            SliderRow(tr("Forward speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
                valueLabel = "${state.trajectorySpeedPercent} %")
            SliderRow(tr("Maximum rotation speed", "Vitesse de rotation maximale"), angular.toFloat(), { angular = it.toInt() }, 60f..450f,
                valueLabel = "≈ ${(angular * 0.12).toInt()} °/s", steps = 38, enabled = !state.navTest.running)
            SliderRow(tr("Maximum radius around the start", "Rayon maximal autour du départ"), radius, { radius = it }, 2f..15f,
                valueLabel = "${Format.num(radius.toDouble())} m", steps = 12, enabled = !state.navTest.running)
            ToggleRow(tr("Automatic start", "Démarrage automatique"), automatic, { automatic = it }, subtitle = tr("Without holding the button", "Sans maintenir le bouton"), enabled = !state.navTest.running)
            LubaCheckRow(tr("I am supervising on site, area clear", "Je supervise sur place, zone dégagée"), confirmed, { confirmed = it }, enabled = !state.navTest.running)
        }
        if (state.navTest.results.isNotEmpty()) SettingsGroup(tr("Results", "Résultats")) {
            state.navTest.results.asReversed().forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
        }
        InfoNote(tr("Three short stripes, a nearby border and exclusion if reachable, starting from the actual position with a 0.9 m margin. " +
            "Speeds in robot units, not calibrated: start low.",
            "Trois bandes courtes, une bordure et une exclusion proches si accessibles, départ à la position réelle avec une marge de 0,9 m. " +
            "Vitesses en unités du robot, non étalonnées : commencez bas."))
    }
}

@Composable
private fun CampaignPage(robot: RobotBle, state: RobotUi, back: () -> Unit, exportArchive: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    var duration by remember { mutableStateOf(30) }
    var zeros by remember { mutableStateOf(true) }
    var cycles by remember { mutableStateOf(true) }
    var exception by remember { mutableStateOf(true) }
    var watchdog by remember { mutableStateOf(true) }
    var confirmed by remember { mutableStateOf(false) }
    val running = state.campaign.running
    PushedPage(ServicePage.CAMPAIGN.title, back, bottomBar = {
        PrimaryPillButton(tr("Run diagnostics", "Lancer le diagnostic"), { robot.startCampaign(CampaignConfig(duration, zeros, cycles, watchdog, exception = exception)) },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow,
            enabled = confirmed && state.ready && !state.robotRoute.running && !state.manualActive && !running && !state.mowing.active &&
                !state.navTest.running && !state.teach.running && !state.bladeTestActive)
    }) {
        header()
        SafetyNotice(tr("Robot stationary, area clear, blades stopped. Link, zero commands, interruptions and reconnections follow one another " +
            "in a single visit (about 2 min), then monitoring.",
            "Robot immobile, zone dégagée, lames arrêtées. Liaison, commandes nulles, coupures et reconnexions s'enchaînent " +
            "en une seule visite (environ 2 min), puis surveillance."))
        SettingsGroup(tr("Tested cases", "Cas testés")) {
            LubaCheckRow(tr("30 s of zero commands at 5 Hz", "30 s de commandes nulles à 5 Hz"), zeros, { zeros = it }, enabled = !running)
            LubaCheckRow(tr("3 STOP / reconnection cycles", "3 cycles STOP / reconnexion"), cycles, { cycles = it }, enabled = !running)
            LubaCheckRow(tr("Hide reports (monitoring)", "Masquer les rapports (surveillance)"), watchdog, { watchdog = it }, enabled = !running)
            LubaCheckRow(tr("Local error → STOP → reconnection", "Erreur locale → STOP → reconnexion"), exception, { exception = it }, enabled = !running)
        }
        SettingsGroup(tr("Test settings", "Réglages de l'essai")) {
            Text(tr("Final monitoring", "Surveillance finale"), style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
            ChipChoice(listOf(1, 5, 10, 30, 60, 0).map { it to if (it == 0) tr("Unlimited", "Illimitée") else "$it min" }, duration, { duration = it }, enabled = !running)
            LubaCheckRow(tr("Robot stationary, blades stopped: I am supervising", "Robot immobile, lames arrêtées : je supervise"), confirmed, { confirmed = it }, enabled = !running)
        }
        SettingsGroup(tr("Results", "Résultats")) {
            KeyValueRow(tr("Status", "Statut"), "${state.campaign.outcome} · ${Format.duration(state.campaign.elapsedMs)}")
            if (state.campaign.step.isNotBlank()) KeyValueRow(tr("Current case", "Cas en cours"), state.campaign.step)
            if (state.campaign.summary.isNotBlank()) Text(state.campaign.summary, style = MaterialTheme.typography.bodySmall)
            if (state.diagnosticSummary.isNotBlank()) Text(state.diagnosticSummary, style = MaterialTheme.typography.bodySmall)
            state.campaign.results.forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
            SecondaryPillButton(tr("Export (ZIP)", "Exporter (ZIP)"), exportArchive, Modifier.fillMaxWidth(), enabled = !running, icon = Icons.Rounded.IosShare)
        }
        SettingsGroup(tr("Tests without robot", "Tests sans robot")) {
            KeyValueRow(tr("Passed", "Réussis"), "${state.localResults.count { it.outcome == "PASS LOCAL" }}/${state.localResults.size}")
            state.localResults.forEach { ResultRow(it.outcome, it.title) }
            SecondaryPillButton(tr("Run tests without robot", "Lancer les tests sans robot"), robot::runLocalChecks, Modifier.fillMaxWidth(), enabled = !running)
        }
        SettingsGroup(tr("Diagnostics history", "Historique des diagnostics")) {
            if (state.history.isNotBlank()) LogLines(state.history.lines().filter { it.isNotBlank() })
            TextAction(if (state.history.isBlank()) tr("Show the history", "Afficher l'historique") else tr("Refresh the history", "Actualiser l'historique"), robot::refreshHistory)
        }
        InfoNote(tr("A successful diagnostic validates neither a movement, nor mechanical stopping, nor a real radio loss.", "Un diagnostic réussi ne valide ni un déplacement, ni l'arrêt mécanique, ni une vraie perte radio."))
    }
}

/** Raw data entry (stream, field, report): name and counter, then abbreviated value. */
@Composable
private fun DataEntry(title: String, detail: String, maxLines: Int = 3) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary,
            maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Decoded states of the bumper and the 4 ultrasonic sensors (`sensor_status`, 3 bits each), live and during the capture. */
@Composable
private fun SensorSlotsGroup(sensorStatus: Int?, probe: fr.luba.poc.diagnostics.SensorProbeSnapshot, bumperRest: Int = 0) {
    SettingsGroup(tr("Ultrasonic sensors and bumper (decoded sensor_status)", "Ultrasons et bumper (sensor_status décodé)")) {
        fr.luba.poc.diagnostics.SensorSlot.entries.forEach { slot ->
            val live = sensorStatus?.let(slot::raw)
            val captured = probe.slots.firstOrNull { it.slot == slot }
            val history = captured?.takeIf { it.current != null }?.let { tr(" · max ${it.max} · ${it.changes} change(s)", " · max ${it.max} · ${it.changes} changement(s)") } ?: ""
            KeyValueRow(slot.label, fr.luba.poc.diagnostics.SensorSlot.stateLabel(live) + history)
        }
        InfoNote(tr("0 = OK, 1 = warning, 2 to 7 = error or obstacle (Mammotion app decoding via PyMammotion).", "0 = OK, 1 = alerte, 2 à 7 = erreur ou obstacle (décodage de l'app Mammotion via PyMammotion)."))
        if (bumperRest != 0) InfoNote(tr("Bumper at $bumperRest since the connection, robot at rest, no contact: taken as this robot's resting value and ignored by the safety checks until it changes. Contacts (code 1014, self-check 25) still stop the robot.",
            "Pare-chocs à $bumperRest depuis la connexion, robot au repos, sans contact : pris comme valeur de repos de ce robot et ignoré par les contrôles de sécurité tant qu'il ne change pas. Les contacts (code 1014, self-check 25) arrêtent toujours le robot."))
    }
}

@Composable
private fun SensorVisionGroup(vision: fr.luba.poc.diagnostics.ProbeVision) {
    SettingsGroup(tr("Camera perception (vision stream)", "Perception caméra (flux vision)")) {
        KeyValueRow(tr("Obstacle points (report / tunnel)", "Points d'obstacle (rapport / tunnel)"), "${vision.reportPoints} / ${vision.tunnelPoints}")
        KeyValueRow(tr("Visual odometry (VIO)", "Odométrie visuelle (VIO)"), vision.vio.toString())
        KeyValueRow(tr("Vision statistics", "Statistiques vision"), vision.statistics.toString())
        listOf(tr("Latest points", "Derniers points") to vision.lastPoints, tr("Latest VIO", "Dernière VIO") to vision.lastVio, tr("Latest statistics", "Dernières statistiques") to vision.lastStatistics)
            .filter { it.second.isNotBlank() }.forEach { (label, text) -> DataEntry(label, text, maxLines = 4) }
    }
}

@Composable
private fun SensorsPage(robot: RobotBle, state: RobotUi, back: () -> Unit, exportArchive: () -> Unit) {
    val probe = state.sensorProbe
    PushedPage(ServicePage.SENSORS.title, back, bottomBar = {
        if (probe.running) PrimaryPillButton(tr("Stop the capture", "Arrêter la capture"), robot::stopSensorProbe, Modifier.fillMaxWidth())
        else PrimaryPillButton(tr("Start the capture", "Démarrer la capture"), robot::startSensorProbe, Modifier.fillMaxWidth(), enabled = state.ready, icon = Icons.Rounded.PlayArrow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Reset", "Remettre à zéro"), robot::resetSensorProbe, Modifier.weight(1f), enabled = !probe.running)
            SecondaryPillButton(tr("Export", "Exporter"), exportArchive, Modifier.weight(1f), icon = Icons.Rounded.IosShare)
        }
    }) {
        SafetyNotice(tr("No movement command: the capture only adds the vision streams to the telemetry (and, on request, firmware debugging). " +
            "Robot stationary, blades stopped, under supervision: place an obstacle in front of it, then move it.",
            "Aucune commande de mouvement : la capture ajoute seulement les flux vision à la télémétrie (et, sur demande, le débogage firmware). " +
            "Robot immobile, lames arrêtées, sous supervision : placez un obstacle devant lui puis déplacez-le."))
        InfoNote(tr("With the robot stopped, the firmware probably publishes neither ultrasonic nor perception data. The capture keeps running in the background: " +
            "start it, then run a route (signal survey, manual control with the joystick) and pass an obstacle in front of the robot before coming back here.",
            "Robot à l'arrêt, le firmware ne publie sans doute ni ultrasons ni perception. La capture continue en arrière-plan : " +
            "démarrez-la, puis lancez un trajet (relevé de signaux, pilotage manuel au joystick) et faites passer un obstacle devant le robot, avant de revenir ici."))
        if (!state.ready) StatusNote(tr("Robot not connected: connect it over Bluetooth to start the capture.", "Robot non connecté : connectez-le en Bluetooth pour lancer la capture."), NoteTone.WARNING)
        SettingsGroup(tr("Result", "Résultat")) {
            KeyValueRow("Verdict", probe.verdict)
            KeyValueRow(tr("Messages received", "Messages reçus"), probe.messages.toString())
            KeyValueRow(tr("Robot states (sys_status × reports)", "États du robot (sys_status × rapports)"), probe.robotStatesLabel)
            KeyValueRow(tr("Perception (PEPT / DEV_PERCEPTION type)", "Perception (type PEPT / DEV_PERCEPTION)"), "${probe.peptPayloads} / ${probe.perceptionMessages}")
            KeyValueRow(tr("Fields unknown to the schema", "Champs inconnus du schéma"), probe.unknown.size.toString())
        }
        SettingsGroup(tr("Firmware debugging", "Débogage firmware")) {
            ToggleRow(tr("Robot debug reports", "Rapports de débogage du robot"), probe.debugEnabled, robot::setSensorProbeDebug, enabled = probe.running && state.ready,
                subtitle = tr("debug_enable=1 during the capture (reset to 0 when it stops): the firmware can then push module/key/value reports.", "debug_enable=1 le temps de la capture (remis à 0 à l'arrêt) : le firmware peut alors pousser des rapports module/clé/valeur."))
            if (probe.debugEntries.isEmpty()) InfoNote(tr("No debug report received.", "Aucun rapport de débogage reçu."))
            probe.debugEntries.takeLast(40).forEach { DataEntry("${it.source} · ${it.key} ×${it.count}", it.value) }
        }
        SettingsGroup(tr("Perception bridge (ZMQ, Wi-Fi)", "Pont perception (ZMQ, Wi-Fi)")) {
            val z = probe.zmq
            ToggleRow(tr("Robot DDS → ZMQ bridge", "Pont DDS → ZMQ du robot"), z.enabled, robot::setSensorProbeZmq, enabled = probe.running && state.ready,
                subtitle = tr("Mammotion app debug command: the robot republishes perception_post_result on its port 5555, " +
                    "listened to here over Wi-Fi (phone on the same network as the robot). Turned off when the capture stops.",
                    "Commande de débogage de l'app Mammotion : le robot republie perception_post_result sur son port 5555, " +
                    "écouté ici en Wi-Fi (téléphone sur le même réseau que le robot). Désactivé à l'arrêt de la capture."))
            KeyValueRow(tr("Status", "État"), z.status.ifEmpty { tr("Inactive", "Inactif") })
            if (z.network.isNotEmpty()) KeyValueRow(tr("Robot network", "Réseau du robot"), z.network)
            if (z.peer.isNotEmpty()) KeyValueRow(tr("Robot socket", "Socket du robot"), z.peer)
            KeyValueRow(tr("Messages received", "Messages reçus"), if (z.messages == 0L) "0" else tr("${z.messages} · ${z.bytes} B · ${z.minSize}..${z.maxSize} B", "${z.messages} · ${z.bytes} o · ${z.minSize}..${z.maxSize} o"))
            z.samples.forEach { DataEntry("Message", it, maxLines = 3) }
        }
        PerceptionMapGroup(state.perceptionMap)
        SensorSlotsGroup(state.telemetry.sensorStatus, probe, state.bumperRest)
        SensorVisionGroup(probe.vision)
        SettingsGroup(tr("Vision, SLAM and camera", "Vision, SLAM et caméra")) {
            val e = probe.extras
            KeyValueRow(tr("Vision state (tunnel 26, index 16)", "État vision (tunnel 26, index 16)"), e.visionState?.toString() ?: "—")
            KeyValueRow(tr("Localisation fusion", "Fusion de localisation"), e.fuseStatus?.toString() ?: "—")
            KeyValueRow(tr("Visual SLAM (vslam_status)", "SLAM visuel (vslam_status)"), e.vslamStatus?.toString() ?: "—")
            KeyValueRow(tr("VIO survival", "Survie VIO"), e.vioSurvivalM?.let { "%.2f m".format(it) } ?: "—")
            KeyValueRow("FPV (fpv_flag · Wi-Fi · 4G)", e.fpvFlag?.let { "$it · ${e.fpvWifi} · ${e.fpvMobile}" } ?: "absent")
            KeyValueRow(tr("Perception obstacles (PEPT)", "Obstacles de perception (PEPT)"), e.obstacleMessages.toString())
            if (e.lastObstacles.isNotBlank()) DataEntry(tr("Latest obstacles", "Derniers obstacles"), e.lastObstacles, maxLines = 4)
        }
        if (probe.arrays.isNotEmpty()) SettingsGroup(tr("Raw tunnels (varying indices)", "Tunnels bruts (indices qui varient)")) {
            probe.arrays.forEach {
                DataEntry(tr("${it.name} · ${it.length} values ×${it.count}", "${it.name} · ${it.length} valeurs ×${it.count}"), if (it.varying.isEmpty()) tr("No index varies", "Aucun indice ne varie") else it.varying.joinToString("  "), maxLines = 4)
            }
            InfoNote(tr("Move the obstacle closer, then away: an index that follows the distance will appear here (position and heading also vary when moving).", "Approchez puis éloignez l'obstacle : un indice qui suit la distance apparaîtra ici (la position et le cap varient aussi en mouvement)."))
        }
        SettingsGroup(tr("Sensor code (sensor_status)", "Code capteurs (sensor_status)")) {
            if (probe.sensorValues.isEmpty()) InfoNote(tr("No value received.", "Aucune valeur reçue."))
            probe.sensorValues.forEach { KeyValueRow("${it.value}", "${it.bits} · ×${it.count}") }
        }
        SettingsGroup(tr("Streams received (${probe.streams.size})", "Flux reçus (${probe.streams.size})")) {
            if (probe.streams.isEmpty()) InfoNote(tr("No stream received.", "Aucun flux reçu."))
            probe.streams.forEach { DataEntry("${it.key} ×${it.count}", it.lastSummary) }
        }
        if (probe.unknown.isNotEmpty()) SettingsGroup(tr("Unknown fields", "Champs inconnus")) {
            probe.unknown.forEach { DataEntry("${it.path} ×${it.count}", it.lastFields, maxLines = Int.MAX_VALUE) }
        }
        if (probe.keywordHits.isNotEmpty()) SettingsGroup(tr("Fields suggesting ultrasonic / distance", "Champs évoquant ultrason / distance")) {
            LogLines(probe.keywordHits)
        }
        if (probe.lastPerception.isNotBlank()) SettingsGroup(tr("Last perception message", "Dernier message de perception")) {
            LogLines(listOf(probe.lastPerception))
        }
    }
}

@Composable
private fun WeatherHubRow(onClick: () -> Unit) {
    val w by fr.luba.poc.weather.WeatherService.flow.collectAsStateWithLifecycle()
    NavRow(ServicePage.WEATHER.title, icon = Icons.Rounded.Umbrella, subtitle = tr("Radar, alert during mowing", "Radar, alerte pendant la tonte"),
        value = if (w.location == null) tr("to set up", "à configurer") else w.assessment.level.title, onClick = onClick)
}

/** Gateway phone: status of the robot link held by the service, and releasing the robot. */
@Composable
private fun GatewayRobotNote() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val gateway by fr.luba.poc.gateway.GatewayService.status.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf("") }
    InfoNote(tr("This phone is the gateway placed on the robot: it holds the Bluetooth link (reconnected automatically) " +
        "and the pilot phone connects to it over Wi-Fi.",
        "Ce téléphone est la passerelle posée sur le robot : il tient la liaison Bluetooth (reconnectée automatiquement) " +
        "et le téléphone de pilotage s'y branche en Wi-Fi."))
    SettingsGroup(tr("Gateway link", "Liaison de la passerelle")) {
        if (gateway.running) KeyValueRow("Robot", gateway.robot.label)
        else StatusNote(tr("Gateway stopped: start it in Service → Wi-Fi gateway.", "Passerelle arrêtée : la démarrer dans Service → Passerelle Wi-Fi."), NoteTone.WARNING)
        if (gateway.robot.address != null) {
            SecondaryPillButton(tr("Release the robot", "Libérer le robot"), {
                refused = fr.luba.poc.gateway.GatewayService.releaseRobot(context).orEmpty()
            }, Modifier.fillMaxWidth())
            InfoNote(tr("Hands the robot back to the Mammotion app or to direct Bluetooth (no more automatic reconnection).", "Rend le robot à l'app Mammotion ou au Bluetooth direct (plus de reconnexion automatique)."))
        }
        if (refused.isNotBlank()) StatusNote(refused, NoteTone.WARNING)
    }
}
