package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.components.PushedPage
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in screenshots (-PuiShots=folder) of the "home" group: home, Me, account, terrain, weather, notifications. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsAccueilTest {
    private val out: String? = System.getProperty("uiShots")
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(out != null); base.evaluate() } }
    }).around(compose)

    private val map: RobotMap by lazy {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        RobotMap(shapes, true, "reference-station", MapStation(Point(0.142, 1.286), 152.86, "Transmise par le robot"))
    }
    private fun state(connected: Boolean) = RobotUi(robotMap = map,
        connection = if (connected) ConnectionState.Receiving else ConnectionState.Disconnected, ready = connected,
        selected = if (connected) fr.luba.poc.ble.SeenRobot("Luba-VS-DEMO", "00:00", -60) else null,
        telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(dark) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MammotionColors.Background) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Modal: capture of all windows (the dialog is a separate window). */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun dialogShot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(dark) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MammotionColors.Background) { content() } } }
        compose.waitForIdle()
        captureScreenRoboImage("$out/$name.png")
    }

    private val reliefSurvey: Pair<RobotMap, List<SignalSample>> by lazy {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(24.0, 0.0), Point(24.0, 18.0), Point(0.0, 18.0)), "Pelouse")
        val reliefMap = RobotMap(listOf(zone), true)
        val plan = MultiZonePlanner.plan(listOf(zone), emptyList(), 2.0, 0.6, CoverageMode.SURVEY_GRID, true)
        val engine = fr.luba.poc.simulation.MowingSimulation(fr.luba.poc.simulation.TrajectoryPlanner(reliefMap, 0.6, emptyList()), Point(4.0, 4.0), plan, reliefMap,
            fr.luba.poc.simulation.SimConfig(autonomyMinutes = 600.0, baseEntryHeadingDegrees = 0.0, instantCharging = true))
        var sim = engine.start()
        repeat(500) { sim = engine.advance(sim, 30.0) }
        reliefMap to sim.signalSamples
    }

    // Home
    @Test fun home() = shot("home") { HomeScreen(state(true), CloudUi(), map) }
    @Test fun homeDark() = shot("home-dark", dark = true) { HomeScreen(state(true), CloudUi(), map) }
    @Test fun homeLuba1() = shot("home-luba1") { HomeScreen(state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-X7Q2B1", "00:00", -60)), CloudUi(), map) }
    @Test fun homeLuba3Dark() = shot("home-luba3-dark", dark = true) { HomeScreen(state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-VA1B2C3", "00:00", -60)), CloudUi(), map) }
    @Test fun homeLubaMini() = shot("home-luba-mini") { HomeScreen(state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-MN8F3K", "00:00", -60)), CloudUi(), map) }
    @Test fun homeLubaMini2Dark() = shot("home-luba-mini2-dark", dark = true) { HomeScreen(state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-LA8F3K", "00:00", -60)), CloudUi(), map) }
    @Test fun homeOffline() = shot("home-offline") { HomeScreen(state(false).copy(robotMap = RobotMap()), CloudUi(), RobotMap()) }
    @Test fun homeCharging() = shot("home-charging") {
        val api = Telemetry(battery = 64, chargeState = 1, lastDeviceAt = System.currentTimeMillis())
        HomeScreen(state(false), CloudUi(connected = true, telemetry = api), map, apiTelemetry = api)
    }
    @Test fun homeChargingDark() = shot("home-charging-dark", dark = true) {
        HomeScreen(state(true).let { it.copy(ageMs = 0, telemetry = it.telemetry.copy(battery = 18, chargeState = 1, lastDeviceAt = System.currentTimeMillis())) }, CloudUi(), map)
    }

    // Me
    @Test fun me() = shot("me") { MeScreen(CloudUi(), ThemeMode.SYSTEM, {}, {}, {}, {}, {}, tr("Local alerts enabled", "Alertes locales activées")) }
    @Test fun meDark() = shot("me-dark", dark = true) {
        MeScreen(CloudUi(connected = true, accountEmail = "jardin@example.fr", map = map), ThemeMode.DARK, {}, {}, {}, {}, {}, tr("Notifications blocked · allow", "Notifications bloquées · autoriser"))
    }

    // Account
    @Test fun account() = shot("account") {
        PushedPage("Compte Mammotion", {}, contentPadding = 0.dp) {
            fr.luba.poc.auth.AccountScreen(fr.luba.poc.auth.CloudViewModel(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                CloudUi(accountSaved = true, events = listOf(tr("10:02 Sign-in refused: wrong password", "10:02 Connexion refusée : mot de passe incorrect"), tr("10:01 Reading robots", "10:01 Lecture des robots"))))
        }
    }
    @Test fun accountDark() = shot("account-dark", dark = true) {
        PushedPage("Compte Mammotion", {}, contentPadding = 0.dp) {
            fr.luba.poc.auth.AccountScreen(fr.luba.poc.auth.CloudViewModel(androidx.test.core.app.ApplicationProvider.getApplicationContext()), CloudUi())
        }
    }
    @Test fun accountRobot() = shot("account-robot") {
        val now = System.currentTimeMillis()
        val d = fr.luba.poc.auth.RobotDetails(sysStatus = 13, chargeState = 0, battery = 64, updatedAt = now - 12_000,
            task = fr.luba.poc.auth.TaskProgress(42, 380, 60, 25, 55, 0.4),
            rtk = fr.luba.poc.auth.RtkDetails(4, 1, 19, 14, 12, 9, 88, 92, false, true),
            network = fr.luba.poc.auth.NetworkDetails(1, -61, null, null, null), firmware = "1.12.4",
            maintenance = fr.luba.poc.auth.Maintenance(123_456, 412_000, 31, 61_200, 72_000),
            base = fr.luba.poc.auth.BaseStation("1.2.3.4", 1, 1))
        val records = listOf(fr.luba.poc.auth.WorkRecord("Avant", 2, 5, 412.5, 100, 95.0, now - 86_400_000, now - 80_000_000),
            fr.luba.poc.auth.WorkRecord(tr("Back lawn", "Arrière"), 1, 4, 120.0, 40, 38.0, now - 3 * 86_400_000L, null))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            fr.luba.poc.auth.AccountRobotPanel(CloudUi(connected = true, details = d, workRecords = records,
                accountReadErrors = listOf(tr("History: timed out", "Historique : délai dépassé")),
                firmwareCheck = fr.luba.poc.auth.FirmwareCheck("1.12.4", "1.13.0", true, false, null, tr("Border following improvements.", "Améliorations du suivi de bordure."))), {}, now)
        }
    }

    // Terrain: settings placed in a group, as in the map's "Signals and terrain" panel.
    @Test fun reliefHeatmap() = shot("relief-heatmap") {
        val (reliefMap, samples) = reliefSurvey
        val relief = ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!
        val wifi = signalReliefView(samples, SurveySignal.WIFI, relief, true)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MapLive(reliefMap, modifier = Modifier.fillMaxWidth().height(300.dp), signalCells = wifi.cells, signal = wifi.signal, signalColor = wifi::color)
            SettingsGroup(tr("Measured heatmap · ${samples.size} readings", "Heatmap mesurée · ${samples.size} relevés")) {
                SignalReliefControls(wifi, samples, {}, {}, PhoneMount.CALIBRATED, {}, {}, onHide = {},
                    calibration = TiltCalibration(PhoneGravity(0.2, -0.1, 9.8), 0.0, 0), onCalibrate = {})
            }
        }
    }
    @Test fun reliefHeatmapDark() = shot("relief-heatmap-dark", dark = true) {
        val (_, samples) = reliefSurvey
        val relief = ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!
        val view = signalReliefView(samples, SurveySignal.RELIEF, relief, true)
        Column(Modifier.padding(16.dp)) {
            SettingsGroup(tr("Measured heatmap", "Heatmap mesurée")) { SignalReliefControls(view, samples, {}, {}, PhoneMount.AUTO, {}, {}) }
        }
    }
    @Test fun relief3d() = dialogShot("relief-3d") {
        val (reliefMap, samples) = reliefSurvey
        val relief = ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!
        val wifi = signalReliefView(samples, SurveySignal.WIFI, relief, true)
        Relief3DDialog(wifi, reliefMap, signalReliefLegend(wifi, samples)) {}
    }
    @Test fun tiltCalibration() = dialogShot("tilt-calibration") {
        TiltCalibrationDialog(MountEstimate(0.0, MountSource.MANUAL), TiltCalibration(PhoneGravity(0.2, -0.1, 9.8), 0.0, 1_760_000_000_000), {}, {}, {})
    }

    // Weather
    @Test fun gardenLocation() = dialogShot("garden-location") {
        fr.luba.poc.weather.GardenLocationDialog(fr.luba.poc.weather.GardenLocation(48.8566, 2.3522, "manuelle"),
            fr.luba.poc.weather.GardenLocation(48.85, 2.35, "robot")) {}
    }
    @Test fun weatherService() = shot("weather-service") {
        PushedPage(tr("Rain and weather", "Pluie et météo"), {}) { fr.luba.poc.weather.WeatherDebugContent(null) }
    }

    // Notifications (activity modal)
    private val shotNow = java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply { set(2026, 9, 15, 18, 30, 0) }.timeInMillis
    private fun ago(minutes: Long) = shotNow - minutes * 60_000
    private val shotEntries = listOf(
        fr.luba.poc.alerts.AlertEntry(fr.luba.poc.alerts.AlertKind.MOWING_REPORT, fr.luba.poc.alerts.Severity.INFO, ago(12), "Luba-VS-DEMO",
            tr("Front lawn mowed in 1 h 42 (estimated 1 h 35), one recharge", "Pelouse avant tondue en 1 h 42 (estimé 1 h 35), une recharge"), state = "Charge", battery = 34, position = "x=0,14 m, y=1,29 m"),
        fr.luba.poc.alerts.AlertEntry(fr.luba.poc.alerts.AlertKind.OBSTACLE, fr.luba.poc.alerts.Severity.WARNING, ago(95), "Luba-VS-DEMO",
            tr("Bumper contact at x=8.2 m, y=4.1 m; bypassed", "Contact pare-chocs à x=8,2 m, y=4,1 m ; contourné"), 3, tr("Mowing", "Tonte"), 61, tr("x=8.20 m, y=4.10 m", "x=8,20 m, y=4,10 m")),
        fr.luba.poc.alerts.AlertEntry(fr.luba.poc.alerts.AlertKind.RAIN, fr.luba.poc.alerts.Severity.WARNING, ago(160), "Luba-VS-DEMO",
            tr("Rain expected in 20 min (1.2 mm/h)", "Pluie prévue dans 20 min (1,2 mm/h)")),
        fr.luba.poc.alerts.AlertEntry(fr.luba.poc.alerts.AlertKind.BLOCKED, fr.luba.poc.alerts.Severity.CRITICAL, ago(60 * 22), "Luba-VS-DEMO",
            tr("No progress towards the point for 15 s", "Aucune progression vers le point depuis 15 s"), 1, tr("Stopped", "Arrêté"), 48, tr("x=12.40 m, y=6.05 m", "x=12,40 m, y=6,05 m")),
        fr.luba.poc.alerts.AlertEntry(fr.luba.poc.alerts.AlertKind.TEST, fr.luba.poc.alerts.Severity.INFO, ago(60 * 50), "Luba-VS-DEMO",
            tr("Local test requested — no robot command", "Test local demandé — aucune commande robot")),
    )
    private val shotChannels = fr.luba.poc.alerts.Severity.entries.associateWith { true }
    @Test fun notifications() = dialogShot("notifications") {
        fr.luba.poc.ui.NotificationsDialog(fr.luba.poc.alerts.NotificationUi(enabled = true, entries = shotEntries, seenAt = ago(100),
            channels = shotChannels), {}, {}, {}, now = shotNow)
    }
    @Test fun notificationsDark() = dialogShot("notifications-dark", dark = true) {
        fr.luba.poc.ui.NotificationsDialog(fr.luba.poc.alerts.NotificationUi(enabled = true, entries = shotEntries, seenAt = ago(100),
            channels = shotChannels), {}, {}, {}, now = shotNow)
    }
    @Test fun notificationsEmpty() = dialogShot("notifications-empty") {
        fr.luba.poc.ui.NotificationsDialog(fr.luba.poc.alerts.NotificationUi(enabled = false, permission = false), {}, {}, {}, now = shotNow)
    }
    @Test fun notificationsSettings() = dialogShot("notifications-settings") {
        fr.luba.poc.ui.NotificationsDialog(fr.luba.poc.alerts.NotificationUi(enabled = false, entries = shotEntries, permission = true,
            channels = shotChannels + (fr.luba.poc.alerts.Severity.INFO to false)), {}, {}, {}, now = shotNow, startInSettings = true)
    }
    @Test fun notificationsSettingsDark() = dialogShot("notifications-settings-dark", dark = true) {
        fr.luba.poc.ui.NotificationsDialog(fr.luba.poc.alerts.NotificationUi(enabled = false, problem = tr("Alert channel disabled in Android", "Canal d'alerte désactivé dans Android")),
            {}, {}, {}, now = shotNow, startInSettings = true)
    }
}
