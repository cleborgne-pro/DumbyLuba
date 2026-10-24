package fr.luba.poc.history

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test fun `report shows the Mowing Score once the track is loaded`() {
        val done = record("t1", reason = "Tonte terminée · contact de charge confirmé")
        compose.setContent { LubaTheme(false) { MowingReportDialog(done, listOf(done), {}, {}, loadTrack = { null }) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("/ 100", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // No track: coverage estimated on the route (100 %), 2 obstacles = 8 points.
        compose.onNodeWithText("92 / 100").assertExists()
        compose.onNodeWithText("2 incidents (obstacles seulement) · −8 / 20").assertExists()
    }

    @Test fun `history compares two mowings side by side`() {
        val a = record("a", startedAt = 1_760_000_000_000L)
        val b = record("b", outcome = MowingOutcome.INTERRUPTED, startedAt = 1_760_100_000_000L)
        compose.setContent { LubaTheme(false) { MowingHistoryPage(listOf(b, a), null, "AA", {}, {}, loadTrack = { null }) } }
        compose.onNodeWithText("Comparer deux tontes").performScrollTo().performClick()
        compose.onNodeWithText("Choisir la première tonte").assertExists()
        compose.onAllNodes(hasText("Tonte · Pelouse"))[0].performClick()
        compose.onNodeWithText("Choisir la deuxième tonte").assertExists()
        compose.onAllNodes(hasText("Tonte · Pelouse"))[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("/ 100", substring = true)).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("92 / 100").assertExists()
        compose.onNodeWithText("68 / 100").assertExists()                 // 40 % of the route: −24, obstacles −8
        compose.onNodeWithText("Interrompue").assertExists()
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

    @Test fun `mowing detail shows the real coverage of each zone and its gaps`() {
        val mowing = record("m1")
        val zones = listOf(fr.luba.poc.map.MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 4.0), Point(0.0, 4.0)), "Pelouse"),
            fr.luba.poc.map.MapShape(2, 0, listOf(Point(12.0, 0.0), Point(16.0, 0.0), Point(16.0, 4.0), Point(12.0, 4.0)), "Fond"))
        val map = fr.luba.poc.map.RobotMap(zones, true, "test")
        // Two stripes in Pelouse, nothing in Fond.
        val samples = (0..96).map { i -> TrackSample(mowing.startedAt + i * 1_000L, 0.2 + i * 0.1, 0.3, 0.0, TrackPhase.CUTTING, 90) } +
            (0..96).map { i -> TrackSample(mowing.startedAt + 100_000L + i * 1_000L, 9.8 - i * 0.1, 0.65, 180.0, TrackPhase.CUTTING, 90) }
        val track = TaskTrack(TrackMeta(map, zoneIds = listOf(1L, 2L)), samples)
        compose.setContent { LubaTheme(false) { TaskDetailPage(mowing, listOf(mowing), map, { track }, {}, {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Couverture réelle", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Fond").performScrollTo().assertExists()
        compose.onNodeWithText("0 % · 0 m² sur 16 m²").performScrollTo().assertExists()
        compose.onNodeWithText("Manques de 0,25 m² ou plus").performScrollTo().assertExists()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("au total", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `black box of an incident opens and the replay jumps between incidents`() {
        val mowing = record("m2")
        val t0 = mowing.startedAt
        val samples = (0..120).map { i -> TrackSample(t0 + i * 1_000L, i * 0.05, 0.0, 0.0, TrackPhase.CUTTING, 90) }
        val events = listOf(TaskEvent(t0, TaskEventType.START, "Tonte"), TaskEvent(t0 + 30_000, TaskEventType.CONTACT, "Contact bumper (1005)"),
            TaskEvent(t0 + 90_000, TaskEventType.LINK_LOST, "Perte BLE"))
        val box = BlackBox(t0 + 30_000, listOf(TaskEventType.CONTACT, TaskEventType.STOP), "Contact bumper (1005)",
            (0..10).map { i -> BlackBoxFrame(t0 + 20_000 + i * 1_000L, 1.0, 0.0, 0.0, 300, -40, 0.3, 90, 13, 0, 4, 30, TrackLinkState.BLUETOOTH, "FOLLOWING") },
            listOf(BlackBoxCommand(t0 + 29_500, "NAV_STOP", 0, 0)), listOf(BlackBoxNote(t0 + 30_100, "route_contact", "Contact bumper : arrêt")))
        val track = TaskTrack(null, samples, events, listOf(box))
        compose.setContent { LubaTheme(false) { TaskDetailPage(mowing, listOf(mowing), null, { track }, {}, {}) } }
        compose.waitForIdle()
        // Replay at the end: the last incident is current.
        compose.onNodeWithText("Incident 2 / 2 · Perte de liaison").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Incident précédent").performClick()   // to the link loss
        compose.onNodeWithText("Incident 2 / 2 · Perte de liaison").assertExists()
        compose.onNodeWithContentDescription("Incident précédent").performClick()
        compose.onNodeWithText("Incident 1 / 2 · Contact pare-chocs").assertExists()
        val title = "${TaskText.clock(t0 + 30_000)} · Contact pare-chocs + STOP"
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.onNodeWithText("−0,5 s · NAV_STOP 0/0").performScrollTo().assertExists()
        compose.onNodeWithText("+0,1 s · Contact bumper : arrêt").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Incident suivant").performScrollTo().performClick()
        compose.onNodeWithText("Incident 2 / 2 · Perte de liaison").assertExists()
        compose.onNodeWithText("Voir dans le replay").performScrollTo().performClick()
        compose.onNodeWithText("Incident 1 / 2 · Contact pare-chocs").performScrollTo().assertExists()
    }

    /** Lawn 10 m × 6 m mowed in stripes, 2 m × 1.5 m left unmowed in the middle. */
    private fun holedLawn(at: Long): TaskTrack {
        val lawn = fr.luba.poc.map.MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 6.0), Point(0.0, 6.0)), "Pelouse")
        val samples = mutableListOf<TrackSample>()
        var y = 0.2; var right = true; var t = at
        while (y <= 5.8) {
            for (i in 0..96) {
                val x = if (right) 0.2 + i * 0.1 else 9.8 - i * 0.1
                val skip = x in 4.0..6.0 && y in 2.0..3.5
                samples += TrackSample(t, x, y, 0.0, if (skip) TrackPhase.TRAVEL else TrackPhase.CUTTING); t += 1_000
            }
            right = !right; y += 0.35
        }
        return TaskTrack(TrackMeta(fr.luba.poc.map.RobotMap(listOf(lawn), true, "test"), zoneIds = listOf(1L)), samples)
    }

    @Test fun `report offers the finishing of the gaps`() {
        val done = record("t1", reason = "Tonte terminée · contact de charge confirmé")
        val track = holedLawn(done.startedAt)
        val asked = CopyOnWriteArrayList<fr.luba.poc.planning.FinishingOffer>()
        compose.setContent { LubaTheme(false) { MowingReportDialog(done, listOf(done), {}, {}, loadTrack = { track }, onFinishing = { asked += it }) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Préparer la finition")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("en 1 manque", substring = true).assertExists()
        compose.onNodeWithText("Préparer la finition").performScrollTo().performClick()
        assertEquals(1, asked.size)
        assertEquals("t1", asked.single().taskId)
        assertTrue(asked.single().areaM2 > 1.0)
    }

    @Test fun `finishing without the robot of the mowing asks to connect it`() {
        val done = record("t1")
        val track = holedLawn(done.startedAt)
        compose.setContent { LubaTheme(false) { MowingReportDialog(done, listOf(done), {}, {}, loadTrack = { track }) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Reste à tondre")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Préparer la finition").assertDoesNotExist()
        compose.onNodeWithText("Connectez le robot de cette tonte", substring = true).performScrollTo().assertExists()
    }

    @Test fun `interrupted mowing offers no finishing`() {
        val stopped = record("t2", outcome = MowingOutcome.INTERRUPTED, reason = "STOP manuel")
        val track = holedLawn(stopped.startedAt)
        compose.setContent { LubaTheme(false) { MowingReportDialog(stopped, listOf(stopped), {}, {}, loadTrack = { track }, onFinishing = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("/ 100", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reste à tondre").assertDoesNotExist()
    }
}
