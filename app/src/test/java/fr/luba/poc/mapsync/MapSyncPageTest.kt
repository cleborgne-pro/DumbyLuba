package fr.luba.poc.mapsync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.map.AddedNoGo
import fr.luba.poc.map.AddedZone
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.NoGoOrigin
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sync page: differences listed with their tick, start refused with its reason, live state with STOP. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
class MapSyncPageTest {
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val raw = RobotMap(listOf(MapShape(101L, 0, rect(0.0, 0.0, 20.0, 12.0), "Lawn")), true, "BLE")
    private val corrections: List<MapCorrection> = listOf(
        AddedZone(1, 0L, "DL zone", "Shed", rect(0.0, 14.0, 8.0, 6.0)),
        AddedNoGo(2, 0L, "Tree", rect(4.0, 4.0, 1.0, 1.0), NoGoOrigin.SHAPE))
    private val items = MapSyncPlanner.differences(raw, MapCorrections.apply(raw, corrections), corrections)

    @Before fun clearSelection() { MapSyncScreen.excluded = emptySet(); MapSyncScreen.focused = null }

    private fun show(run: MapSyncRun = MapSyncRun(), refusal: String? = null, onStart: (Set<String>) -> Unit = {}, onStop: () -> Unit = {}) {
        compose.setContent { LubaTheme(false) {
            MapSyncContent(raw, items, null, run, refusal, 0.24, Point(10.0, 6.0), 90.0, emptyList(), {}, onStart, onStop, {})
        } }
    }

    @Test fun everyDifferenceIsListedAndTheChosenOnesAreStarted() {
        var started: Set<String>? = null
        show(onStart = { started = it })
        assertEquals(2, items.size)
        items.forEach { compose.onNodeWithTag("sync-item-${it.key}").assertExists() }
        compose.onNodeWithTag("sync-start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("sync-confirm").performClick()
        assertEquals(items.map { it.key }.toSet(), started)
    }

    @Test fun anUntickedElementIsLeftOut() {
        var started: Set<String>? = null
        MapSyncScreen.excluded = setOf(items.first().key)
        show(onStart = { started = it })
        compose.onNodeWithTag("sync-start").performScrollTo().performClick()
        compose.onNodeWithTag("sync-confirm").performClick()
        assertEquals(setOf(items.last().key), started)
    }

    @Test fun theStartIsRefusedWhileTheRobotCannotDoIt() {
        show(refusal = "Connect the robot first")
        compose.onNodeWithTag("sync-start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aRunningSyncShowsItsStateAndStops() {
        var stopped = false
        val run = MapSyncRun(SyncPhase.RECORDING, items.map { SyncRunItem(it, if (it == items.first()) ItemStatus.ACTIVE else ItemStatus.PENDING) },
            currentKey = items.first().key, message = SyncPhase.RECORDING.label, progress = 0.4)
        show(run = run, onStop = { stopped = true })
        compose.onNodeWithTag("sync-run").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("sync-start").fetchSemanticsNodes().size)
        compose.onNodeWithTag("sync-stop").performClick()
        assertEquals(true, stopped)
        capture("map-sync-running")
    }

    @Test fun listScreenshot() {
        MapSyncScreen.focused = items.first().key
        show()
        capture("map-sync-list")
    }

    /** Opt-in screenshot (-PuiShots=folder): visual review, no pixel assertions. */
    private fun capture(name: String) {
        val out = System.getProperty("uiShots") ?: return
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }
}
