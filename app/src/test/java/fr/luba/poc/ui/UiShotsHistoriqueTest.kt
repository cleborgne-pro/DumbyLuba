package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.history.MowingForecaster
import fr.luba.poc.history.MowingOutcome
import fr.luba.poc.history.MowingRecord
import fr.luba.poc.history.MowingTotals
import fr.luba.poc.history.TaskDetailPage
import fr.luba.poc.history.TaskKind
import fr.luba.poc.history.TaskTrack
import fr.luba.poc.history.TrackMeta
import fr.luba.poc.history.TrackPhase
import fr.luba.poc.history.TrackSample
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
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

/** Opt-in screenshots (-PuiShots=folder) of the map's History button and of a task detail (replay). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsHistoriqueTest {
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
    private val forecast = MowingForecaster.forecast(400.0, 0.3, 60.0, 30.0, 90.0, 20.0, 90.0, null)
    private val zone by lazy { map.shapes.first { it.kind == ShapeKind.ZONE && it.points.size >= 3 } }
    private val mow by lazy { MowingRecord("AA-1000", "AA", "Luba", 1_760_000_000_000, 1_760_003_600_000, MowingOutcome.COMPLETE,
        tr("Mowing finished · charging contact confirmed", "Tonte terminée · contact de charge confirmé"), listOf(zone.name), 120.0, "Zigzag", plannedMeters = 400.0, cuttingMeters = 300.0,
        doneMeters = 300.0, forecast = forecast, totals = MowingTotals(3000.0, 600.0, 45.0, 10.0), recharges = 1, batteryStart = 90, batteryEnd = 55) }
    private val survey by lazy { mow.copy(id = "AA-500", startedAt = 1_759_900_000_000, endedAt = 1_759_901_000_000, kind = TaskKind.SURVEY,
        outcome = MowingOutcome.INTERRUPTED, reason = "STOP", doneMeters = 120.0) }

    /** Synthetic zigzag within the zone's footprint: mowing passes, link with blades stopped, return. */
    private val track: TaskTrack by lazy {
        val xs = zone.points.map { it.x }; val ys = zone.points.map { it.y }
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        val w = (xs.max() - xs.min()) * 0.3; val h = (ys.max() - ys.min()) * 0.3
        val samples = mutableListOf<TrackSample>()
        var t = mow.startedAt
        fun go(to: Point, phase: TrackPhase) {
            val from = samples.lastOrNull()?.point ?: to
            val steps = maxOf(1, (from.distance(to) / 0.1).toInt())
            val heading = Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x))
            for (i in 1..steps) { t += 330; samples += TrackSample(t, from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps,
                heading, phase, 90 - samples.size / 200) }
        }
        go(Point(cx - w, cy - h), TrackPhase.TRAVEL)
        for (row in 0..8) {
            val y = cy - h + row * 2 * h / 8
            go(Point(if (row % 2 == 0) cx - w else cx + w, y), TrackPhase.CUTTING)
            go(Point(if (row % 2 == 0) cx + w else cx - w, y), TrackPhase.CUTTING)
        }
        go(Point(cx, cy), TrackPhase.TRAVEL)
        go(map.station!!.point, TrackPhase.RETURN)
        TaskTrack(TrackMeta(map, cutting = emptyList(), transit = emptyList()), samples)
    }

    private fun state() = RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4),
        mowingHistory = listOf(mow, survey))

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        capture(name)
    }
    private fun capture(name: String) { compose.waitForIdle(); compose.onRoot().captureRoboImage("$out/$name.png") }

    @Test fun historySheet() {
        var opened: String? = null
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(state(), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, onOpenTask = { opened = it })
        } } }
        compose.onNodeWithText(tr("History", "Historique")).performClick()
        compose.onNodeWithText(tr("Latest tasks", "Dernières tâches")).assertExists()
        capture("historique-liste")
        compose.onNodeWithText(tr("Signal survey · ${zone.name}", "Relevé des signaux · ${zone.name}")).performClick()
        compose.waitForIdle()
        check(opened == survey.id) { "Tâche ouverte : $opened" }
    }

    @Test fun detail() = shot("historique-detail") {
        TaskDetailPage(mow, listOf(mow, survey), null, loadTrack = { track }, onExcluded = {}, onBack = {})
    }

    @Test fun detailReplayMidway() {
        compose.mainClock.autoAdvance = false
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            TaskDetailPage(mow, listOf(mow, survey), null, loadTrack = { track }, onExcluded = {}, onBack = {})
        } } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(tr("Play the route", "Lire le trajet")).performClick()
        // ×60: 2 s of frames replay 2 min of the task (5 min), in the middle of the zigzag.
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription(tr("Pause the replay", "Mettre la relecture en pause")).assertExists()
        capture("historique-relecture")
    }

    @Test fun surveyWithoutTrack() = shot("historique-sans-piste") {
        TaskDetailPage(survey, listOf(mow, survey), map, loadTrack = { null }, onExcluded = {}, onBack = {})
    }
}
