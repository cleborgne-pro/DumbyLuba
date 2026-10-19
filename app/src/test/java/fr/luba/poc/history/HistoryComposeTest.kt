package fr.luba.poc.history

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.luba.poc.map.Point
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Task history through the UI: list, end-of-mowing report, exclusion from estimates, detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class HistoryComposeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val forecast = MowingForecaster.forecast(1080.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
    private fun record(id: String, kind: TaskKind = TaskKind.MOW, outcome: MowingOutcome = MowingOutcome.COMPLETE, startedAt: Long = 1_760_000_000_000L,
                       reason: String? = null, excluded: Boolean = false) =
        MowingRecord(id, "AA", startedAt = startedAt, endedAt = if (outcome == MowingOutcome.RUNNING) null else startedAt + 3_600_000,
            outcome = outcome, reason = reason, zoneNames = listOf("Pelouse"), cuttingMeters = 1000.0,
            doneMeters = if (outcome == MowingOutcome.COMPLETE) 1000.0 else 400.0, forecast = forecast,
            totals = MowingTotals(activeSeconds = 3_000.0, chargingSeconds = 600.0, batteryUsed = 60.0, batteryCharged = 20.0),
            recharges = 1, obstacles = 2, resumes = 1, batteryStart = 95, batteryEnd = 40, excluded = excluded, kind = kind, mode = "Zigzag")

    @Test fun `end-of-mowing report, exclude the mowing from estimates`() {
        val excluded = CopyOnWriteArrayList<Boolean>()
        val done = record("t1", reason = "Tonte terminée · contact de charge confirmé")
        compose.setContent { LubaTheme(false) { MowingReportDialog(done, listOf(done), { excluded += it }, {}) } }
        compose.onNodeWithText("Tonte terminée").assertExists()
        compose.onNodeWithText("2 obstacles contournés · 1 reprise").assertExists()
        compose.onNodeWithText("95 % → 40 %").performScrollTo().assertExists()
        val toggle = compose.onNodeWithText("Utiliser pour les estimations").performScrollTo()
        toggle.assertIsOn().assertIsEnabled()
        toggle.performClick()
        assertEquals(listOf(true), excluded)
    }

    @Test fun `report of an interrupted or running mowing`() {
        val stopped = record("t2", outcome = MowingOutcome.INTERRUPTED, reason = "STOP manuel", excluded = true)
        compose.setContent { LubaTheme(false) { MowingReportDialog(stopped, listOf(stopped), {}, {}) } }
        compose.onNodeWithText("Tonte interrompue à 40 % : STOP manuel").assertExists()
        compose.onNodeWithText("Utiliser pour les estimations").performScrollTo().assertIsOff()
        compose.onNodeWithText("Cette tonte est écartée des estimations.").performScrollTo().assertExists()
    }

    @Test fun `running mowing cannot be excluded until it is finished`() {
        val running = record("t3", outcome = MowingOutcome.RUNNING)
        compose.setContent { LubaTheme(false) { MowingReportDialog(running, listOf(running), {}, {}) } }
        compose.onNodeWithText("Tonte en cours").assertExists()
        compose.onNodeWithText("Utiliser pour les estimations").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `task list shows finished tasks only and the tapped task opens`() {
        val opened = CopyOnWriteArrayList<String>()
        val history = listOf(record("a", startedAt = 1_760_100_000_000L), record("b", TaskKind.SURVEY, MowingOutcome.INTERRUPTED),
            record("c", outcome = MowingOutcome.RUNNING))
        compose.setContent { LubaTheme(false) { MowingHistoryPage(history, history.last(), "AA", {}, { opened += it }) } }
        compose.onNodeWithText("TÂCHES · 2").assertExists()
        compose.onNodeWithText("EN COURS").assertExists()
        compose.onNodeWithText("Relevé des signaux · Pelouse").performScrollTo().performClick()
        assertEquals(listOf("b"), opened)
        assertEquals(2, compose.onAllNodes(hasText("Tonte · Pelouse")).fetchSemanticsNodes().size)
    }

    @Test fun `empty history and hub entry`() {
        compose.setContent { LubaTheme(false) {
            androidx.compose.foundation.layout.Column {
                MowingHistoryRow(listOf(record("x", outcome = MowingOutcome.RUNNING))) {}
                MowingHistoryRow(listOf(record("y", TaskKind.SURVEY))) {}
                MowingHistoryPage(emptyList(), null, null, {}, {})
            }
        } }
        compose.onNodeWithText("Rapports et trajets réels").assertExists()
        compose.onNodeWithText("Dernière : relevé des signaux · ${MowingText.date(1_760_000_000_000L)}").assertExists()
        compose.onNodeWithText("Aucune tâche enregistrée. Chaque tonte, relevé des signaux ou parcours lames arrêtées apparaît ici avec son rapport et son trajet réel.").assertExists()
    }

    @Test fun `survey detail, loaded route and report specific to blades-stopped tasks`() {
        val loaded = CopyOnWriteArrayList<String>()
        val survey = record("s1", TaskKind.SURVEY, reason = "Trajet terminé")
        val track = TaskTrack(null, listOf(TrackSample(survey.startedAt, 1.0, 1.0, 0.0, TrackPhase.TRAVEL, 90),
            TrackSample(survey.startedAt + 1_000, 1.5, 1.0, 0.0, TrackPhase.TRAVEL, 90)))
        compose.setContent { LubaTheme(false) {
            TaskDetailPage(survey, listOf(survey), fr.luba.poc.map.RobotMap(listOf(fr.luba.poc.map.MapShape(1, 0,
                listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 5.0))))), { loaded += it; track }, {}, {})
        } }
        compose.waitForIdle()
        assertEquals(listOf("s1"), loaded)
        compose.onNodeWithText("Relevé des signaux terminé").assertExists()
        compose.onNodeWithText("Trajet lames arrêtées : il n'ajuste pas les estimations de durée des tontes.").performScrollTo().assertExists()
        compose.onNodeWithText("Obstacles contournés").performScrollTo().assertExists()
    }
}
