package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in screenshots (-PuiShots=folder): visual check without a device.
 * No pixel assertions; the images are for reviewing the interface. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiScreenshotTest {
    private val out: String? = System.getProperty("uiShots")
    val compose = createComposeRule()
    // Opt-in checked before the Compose rule: no activity is launched when screenshots are
    // disabled (the fold variant does not include the Compose test manifest).
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
        compose.setContent { LubaTheme(dark) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    @Test fun lubaIcon() = shot("luba-icon") {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawLuba(center, size.minDimension / 1.2, 90.0)
            drawLuba(androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.1f), 60.0, 30.0)
        }
    }
    @Test fun navigationMap() = shot("navigation-map") {
        val plan = fr.luba.poc.diagnostics.MapNavPlanner.plan(map, Point(2.0, 3.0), 5.0, emptyList())
        MapLive(map = map, x = 2.6, y = 3.0, heading = 40.0, trail = plan.points.take(3), start = Point(2.0, 3.0),
            planned = plan.points, plannedBands = plan.bands, plannedBorders = plan.borders,
            modifier = Modifier.fillMaxSize())
    }
    @Test fun home() = shot("home") { HomeScreen(state(true), CloudUi(), map) }
    /** Charge read through the Mammotion account, without a BLE link. */
    @Test fun homeCharging() = shot("home-charging") {
        val api = Telemetry(battery = 64, chargeState = 1, lastDeviceAt = System.currentTimeMillis())
        HomeScreen(state(false), CloudUi(connected = true, telemetry = api), map, apiTelemetry = api)
    }
    @Test fun homeChargingDark() = shot("home-charging-dark", dark = true) {
        HomeScreen(state(true).let { it.copy(ageMs = 0, telemetry = it.telemetry.copy(battery = 18, chargeState = 1, lastDeviceAt = System.currentTimeMillis())) }, CloudUi(), map)
    }
    /** "Mammotion account" panel: status, task, maintenance, versions, history (demo data). */
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
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(androidx.compose.ui.unit.Dp(16f))) {
            fr.luba.poc.auth.AccountRobotPanel(CloudUi(connected = true, details = d, workRecords = records,
                firmwareCheck = fr.luba.poc.auth.FirmwareCheck("1.12.4", "1.13.0", true, false, null, tr("Border following improvements.", "Améliorations du suivi de bordure."))), {}, now)
        }
    }
    @Test fun homeOffline() = shot("home-offline") { HomeScreen(state(false), CloudUi(), map) }
    @Test fun homeDark() = shot("home-dark", dark = true) { HomeScreen(state(true), CloudUi(), map) }
    @Test fun mapScreen() = shot("map") {
        MapScreen(state(true), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {})
    }
    @Test fun mapDark() = shot("map-dark", dark = true) {
        MapScreen(state(true), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {})
    }
    @Test fun zoneSelectionAndTask() {
        shot("zones") {
            MapScreen(state(true), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true)
        }
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/zones-selected.png")
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/task.png")
        compose.onNodeWithText(tr("Advanced settings", "Réglages avancés")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/task-advanced.png")
    }
    @Test fun preview() {
        shot("preview-start") {
            MapScreen(state(true), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true)
        }
        // Two zones: Aire de jeux then Piscine.
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        for (name in listOf("Jardin d’Eden", "Figuier", "Enfer", "Fosse", "Potager")) compose.onNodeWithText(name, substring = true).performClick()
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/zones-order.png")
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        compose.onNodeWithText(tr("Route preview", "Aperçu du trajet")).performClick()
        compose.waitUntil(180_000) { compose.onAllNodesWithText(tr("Coverage", "Couverture")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/preview.png")
    }
    private val robot by lazy { fr.luba.poc.ble.RobotBle(androidx.test.core.app.ApplicationProvider.getApplicationContext()) }
    @Test fun service() {
        val current = androidx.compose.runtime.mutableStateOf("")
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { RobotPanel(robot, state(true), current.value, {}) } } }
        for (page in listOf("", "Robot", "Hauteur", "Lames", "Obstacles", "EssaisObstacle", "Navigation", "Tests", "Pilotage")) {
            current.value = page
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("$out/service-${page.ifBlank { "hub" }}.png")
        }
    }
    /** Blade test bench: the three tabs on a tall page (scrolling content fully visible). */
    @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    @Test fun blades() {
        val ui = state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-VS-DEMO", "00:00", -60), ageMs = 300,
            telemetry = state(true).telemetry.copy(bladeHeightMm = 60, robotState = 11, sensorStatus = 0, selfCheck = 0, cutterRpm = 0))
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { RobotPanel(robot, ui, "Lames", {}) } } }
        for ((tab, name) in listOf(tr("Robot stationary", "Robot immobile") to "static", tr("Moving", "En déplacement") to "moving")) {
            compose.onAllNodesWithText(tab)[0].performClick()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("$out/blades-$name.png")
        }
    }
    /** Obstacle test bench: four tabs on a tall page. */
    @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    @Test fun obstacles() {
        val obstacles = listOf(fr.luba.poc.obstacles.LocalObstacle("a", fr.luba.poc.obstacles.ObstacleKind.TREE, 4.5, 3.0, 0.3, 0.2, label = tr("tree", "arbre")),
            fr.luba.poc.obstacles.LocalObstacle("b", fr.luba.poc.obstacles.ObstacleKind.POLE, 1.0, 6.0, 0.1, 0.2, label = tr("pole", "poteau")))
        val ui = state(true).copy(selected = fr.luba.poc.ble.SeenRobot("Luba-VS-DEMO", "00:00", -60), ageMs = 300, obstacles = obstacles,
            lastBumperCode = -1014, lastBumperAt = 1_760_000_000_000,
            telemetry = state(true).telemetry.copy(robotState = 11, sensorStatus = 0, selfCheck = 0))
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { RobotPanel(robot, ui, "EssaisObstacle", {}) } } }
        for ((tab, name) in listOf(tr("Contact", "Contact") to "contact", tr("Learn", "Apprendre") to "learn", tr("Bypass", "Contournement") to "course")) {
            compose.onAllNodesWithText(tab)[0].performClick()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("$out/obstacles-$name.png")
        }
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
    @Test fun reliefHeatmap() = shot("relief-heatmap") {
        val (reliefMap, samples) = reliefSurvey
        val relief = ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!
        val wifi = signalReliefView(samples, SurveySignal.WIFI, relief, true)
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
            MapLive(reliefMap, modifier = Modifier.fillMaxWidth().height(380.dp), signalCells = wifi.cells, signal = wifi.signal, signalColor = wifi::color)
            SignalReliefControls(wifi, samples, {}, {}, PhoneMount.CALIBRATED, {}, {}, onHide = {},
                calibration = TiltCalibration(PhoneGravity(0.2, -0.1, 9.8), 0.0, 0), onCalibrate = {})
        }
    }
    @Test fun relief3d() = shot("relief-3d") {
        val (reliefMap, samples) = reliefSurvey
        val relief = ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!
        val wifi = signalReliefView(samples, SurveySignal.WIFI, relief, true)
        Box(Modifier.padding(16.dp)) { Relief3DContent(wifi, reliefMap, signalReliefLegend(wifi, samples), 5f) {} }
    }
    @Test fun account() = shot("account") {
        fr.luba.poc.ui.components.PushedPage("Compte Mammotion", {}, contentPadding = androidx.compose.ui.unit.Dp(0f)) {
            fr.luba.poc.auth.AccountScreen(fr.luba.poc.auth.CloudViewModel(androidx.test.core.app.ApplicationProvider.getApplicationContext()), CloudUi())
        }
    }
    @Test fun me() = shot("me") {
        MeScreen(CloudUi(), fr.luba.poc.ui.theme.ThemeMode.SYSTEM, {}, {}, {}, {}, {}, tr("Local alerts enabled", "Alertes locales activées"))
    }
}
