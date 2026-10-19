package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.MissionCheckpoint
import fr.luba.poc.simulation.RobotRouteUi
import fr.luba.poc.simulation.SimulationDialog
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.simulation.SimulationUi
import fr.luba.poc.ui.theme.LubaTheme
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

/** Opt-in screenshots (-PuiShots=folder) of the map screens: visual review, no pixel assertions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsCarteTest {
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
    private fun state(connected: Boolean = true) = RobotUi(robotMap = map,
        connection = if (connected) ConnectionState.Receiving else ConnectionState.Disconnected, ready = connected,
        telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4,
            lastReportAt = if (connected) 1_792_480_000_000L else null))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(dark) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        capture(name)
    }
    private fun capture(name: String) { compose.waitForIdle(); compose.onRoot().captureRoboImage("$out/$name.png") }
    @Composable private fun screen(ui: RobotUi = state(), openMowing: Boolean = false, openSurvey: Boolean = false) =
        MapScreen(ui, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = openMowing,
            openSurvey = openSurvey)

    @Test fun map() = shot("carte-map") { screen() }
    @Test fun mapDark() = shot("carte-map-dark", dark = true) { screen() }
    /** Compact map bottom: heatmap layers bar, gateway link and weather in the top pill. */
    @Test fun heatmapLayers() = heatmapShot("carte-heatmap-calques", dark = false)
    @Test fun heatmapLayersDark() = heatmapShot("carte-heatmap-calques-dark", dark = true)
    private fun heatmapShot(name: String, dark: Boolean) {
        fr.luba.poc.weather.WeatherService.setStateForTest(fr.luba.poc.weather.WeatherState(
            location = fr.luba.poc.weather.GardenLocation(47.0, -1.5, "test"),
            assessment = fr.luba.poc.weather.RainAssessment(fr.luba.poc.weather.RainLevel.DRY, tr("No rain", "Aucune pluie"))))
        val zone = map.shapes.first { it.kind == ShapeKind.ZONE }
        val samples = zone.points.mapIndexed { i, p -> SignalSample(p, i.toLong(), wifi = -50 - i % 30, ble = -70, mobile = null,
            rtkLevel = if (i % 3 == 0) 0 else 1, satellites = 20 + i % 8) }
        val ui = state().copy(viaGateway = true, signalSurvey = SignalSurvey(mapKey = SignalCoverage.mapKey(map), samples = samples))
        try { shot(name, dark) { screen(ui) } }
        finally { fr.luba.poc.weather.WeatherService.setStateForTest(fr.luba.poc.weather.WeatherState()) }
    }
    /** Mammotion account map (robot not connected): the continuous map stays overlaid, in the same frame. */
    @Test fun heatmapCloud() {
        val zone = map.shapes.first { it.kind == ShapeKind.ZONE }
        val samples = zone.points.mapIndexed { i, p -> SignalSample(p, i.toLong(), wifi = -50 - i % 30, ble = null, mobile = null,
            rtkLevel = 0, satellites = 24) }
        val ui = state(false).copy(signalSurvey = SignalSurvey(mapKey = "carte-bluetooth", samples = samples, live = true))
        shot("carte-heatmap-compte") { MapScreen(ui, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, cloud = true) }
    }
    @Test fun mapOffline() = shot("carte-map-offline") { screen(state(false)) }

    @Test fun layers() {
        shot("carte-map") { screen() }
        compose.onAllNodesWithText(tr("Map", "Carte"))[0].performClick()
        capture("carte-layers")
    }
    @Test fun status() {
        shot("carte-map") { screen() }
        compose.onNodeWithText("87 %", substring = true).performClick()
        capture("carte-status")
    }
    @Test fun station() {
        shot("carte-map") { screen() }
        compose.onNodeWithContentDescription(tr("Charging station", "Station de charge")).performClick()
        capture("carte-station")
    }
    @Test fun stationDark() {
        shot("carte-map-dark", dark = true) { screen() }
        compose.onNodeWithContentDescription(tr("Charging station", "Station de charge")).performClick()
        capture("carte-station-dark")
    }
    @Test fun point() {
        shot("carte-map") { screen() }
        compose.onNodeWithContentDescription(tr("Move to a point", "Déplacer à un point")).performClick()
        capture("carte-point")
    }
    @Test fun locked() = shot("carte-locked") {
        screen(state().let { it.copy(telemetry = it.telemetry.copy(selfCheck = 11L)) })
    }
    @Test fun routeRunning() = shot("carte-route-running") {
        screen(state().copy(robotRoute = RobotRouteUi(running = true, started = true, status = tr("Mowing · zone 1/2", "Tonte · zone 1/2"), index = 42, count = 100)))
    }
    private fun checkpointState(): RobotUi {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE }.take(2).map { it.id }
        val checkpoint = MissionCheckpoint("robot", "map", "plan", 10, Preview(emptyList(), 1000.0, 0, 0, zoneIds = zones),
            true, 60, 20, 90, doneMeters = 420.0, totalMeters = 1000.0, startedAt = 1_760_000_000_000,
            interruption = tr("Bluetooth connection lost", "Connexion Bluetooth perdue"))
        return state().copy(missionCheckpoint = checkpoint)
    }
    /** Short screen: protection alert and resume stacked up to the right bar, narrowed to its left. */
    @Config(sdk = [35], qualifiers = "w412dp-h640dp-xxhdpi")
    @Test fun lockedCheckpointShort() = shot("carte-locked-checkpoint-bas") {
        screen(checkpointState().let { it.copy(telemetry = it.telemetry.copy(selfCheck = 11L)) })
    }
    @Test fun checkpoint() {
        shot("carte-checkpoint") { screen(checkpointState()) }
        compose.onNodeWithText(tr("Discard", "Abandonner")).performClick()
        capture("carte-checkpoint-discard")
    }
    @Test fun zonesAndTask() {
        shot("carte-zones") { screen(openMowing = true) }
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        capture("carte-zones-selected")
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        capture("carte-task")
        compose.onNodeWithText(tr("Advanced settings", "Réglages avancés")).performClick()
        capture("carte-task-advanced")
    }
    /** Dedicated survey route, opened from Service → Signal surveys: full loop or grid only. */
    @Test fun surveyTask() {
        shot("carte-zones-releve") { screen(openSurvey = true) }
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        capture("carte-task-survey")
    }
    @Test fun taskDark() {
        shot("carte-zones-dark", dark = true) { screen(openMowing = true) }
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        capture("carte-task-dark")
    }
    @Test fun preview() {
        shot("carte-preview-start") { screen(openMowing = true) }
        // Two zones: Aire de jeux then Piscine.
        compose.onNodeWithText(tr("Add all", "Tout ajouter")).performClick()
        for (name in listOf("Jardin d’Eden", "Figuier", "Enfer", "Fosse", "Potager")) compose.onNodeWithText(name, substring = true).performClick()
        compose.onNodeWithText(tr("Next", "Suivant")).performClick()
        compose.onNodeWithText(tr("Route preview", "Aperçu du trajet")).performClick()
        capture("carte-preview-computing")
        compose.waitUntil(180_000) { compose.onAllNodesWithText(tr("Coverage", "Couverture")).fetchSemanticsNodes().isNotEmpty() }
        capture("carte-preview")
    }
    @Test fun simulationDialog() = shot("carte-simulation") {
        SimulationDialog(SimulationUi(), null, null, map, false, map.shapes.filter { it.kind == ShapeKind.ZONE }.take(2).map { it.id }.toSet(), {}, true,
            0f, {}, 100f, {}, 60f, {}, 30f, {}, false, {}, {}, {}, {}, {}, {})
    }

    /** Full Map sheet (background, displayed elements, heatmap, source, legend) on a very tall screen. */
    @Config(sdk = [35], qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun mapSettings() = settingsShot("carte-reglages", dark = false)
    @Config(sdk = [35], qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun mapSettingsDark() = settingsShot("carte-reglages-dark", dark = true)
    private fun settingsShot(name: String, dark: Boolean) = shot(name, dark) {
        androidx.compose.material3.Surface(color = fr.luba.poc.ui.theme.MammotionColors.Background) {
            MapSettingsContent(MapSource.ROBOT, "7 zones · 26 zones interdites",
                MapHeatChoice(listOf(SurveySignal.WIFI, SurveySignal.RTK, SurveySignal.BLE, SurveySignal.GPS, SurveySignal.RELIEF),
                    SurveySignal.WIFI, visible = true, barOpen = true),
                mapLoading = false, fetchEnabled = true, accountEnabled = true, demoEnabled = true,
                onSignal = {}, onHeatVisible = {}, onLayerBar = {}, onFetch = {}, onAccount = {}, onToggleDemo = {}, onHistory = {}, onCalibration = {})
        }
    }

    /** Obstacles (saved, presumed, contact), difficulties and mowing in progress: route to do in white, done in green. */
    @Test fun obstaclesAndRoute() = obstaclesShot("carte-obstacles-trajet", dark = false)
    @Test fun obstaclesAndRouteDark() = obstaclesShot("carte-obstacles-trajet-dark", dark = true)
    private fun obstaclesShot(name: String, dark: Boolean) {
        val zone = map.shapes.filter { it.kind == ShapeKind.ZONE }.maxBy { z -> (z.points.maxOf { it.x } - z.points.minOf { it.x }) * (z.points.maxOf { it.y } - z.points.minOf { it.y }) }
        val minX = zone.points.minOf { it.x }; val maxX = zone.points.maxOf { it.x }
        val minY = zone.points.minOf { it.y }; val maxY = zone.points.maxOf { it.y }
        val c = Point((minX + maxX) / 2, (minY + maxY) / 2)
        val passes = (0 until 12).flatMap { i ->
            val y = c.y - 6 + i * 0.5
            if (i % 2 == 0) listOf(Point(c.x - 8, y), Point(c.x + 8, y)) else listOf(Point(c.x + 8, y), Point(c.x - 8, y))
        }
        val events = listOf(
            fr.luba.poc.difficulty.DifficultyEvent("e1", 1L, fr.luba.poc.difficulty.DifficultyKind.CONTACT, c.x + 4, c.y + 3),
            fr.luba.poc.difficulty.DifficultyEvent("e2", 2L, fr.luba.poc.difficulty.DifficultyKind.SLIP, c.x - 5, c.y + 4))
        val ui = state().copy(
            obstacles = listOf(fr.luba.poc.obstacles.LocalObstacle("o1", fr.luba.poc.obstacles.ObstacleKind.OTHER, c.x + 1, c.y + 7, 0.3, 0.4)),
            difficulties = fr.luba.poc.difficulty.DifficultyUi(events = events,
                presumed = listOf(fr.luba.poc.obstacles.LocalObstacle("p1", fr.luba.poc.obstacles.ObstacleKind.OTHER, c.x - 4, c.y + 8, 0.6, 0.4)),
                cells = (0 until 3).map { k -> fr.luba.poc.difficulty.DifficultyCell(Point(c.x - 5 + k, c.y + 4), 0.5 + k * 0.5, 2, fr.luba.poc.difficulty.DifficultyKind.SLIP) }),
            robotRoute = RobotRouteUi(running = true, started = true, status = tr("Mowing · zone 1/1", "Tonte · zone 1/1"), index = 10, count = 24,
                planned = passes, trail = passes.take(11)))
        MapExtraLayers.toggle(MapExtraLayer.DIFFICULTIES)
        try { shot(name, dark) { screen(ui) } } finally { MapExtraLayers.toggle(MapExtraLayer.DIFFICULTIES) }
    }
}
