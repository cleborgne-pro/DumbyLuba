package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.CoverageMode
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import fr.luba.poc.map.RobotMap
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

/**
 * Task settings changed before "Route preview": the computation takes the settings shown, without the
 * "Map changed: recomputation required" refusal (callbacks of the map screen read in their latest composition).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class MapPreviewSettingsTest {
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val state = RobotUi(robotMap = RobotMap(listOf(zone), true, "test"), connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 80, x = 6.0, y = 4.0, heading = 0.0, robotState = 11))

    @Test fun `settings changed before the preview are the ones computed`() {
        var preview: Preview? = null
        // Created before the composition, as MainActivity does (BorderTypeRepository.init).
        fr.luba.poc.map.BorderTypeRepository.resetForTest()
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(state, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true,
                onPreview = { preview = it })
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("Tout ajouter").performClick()
        compose.onNodeWithText("Suivant").performClick()
        compose.waitForIdle()
        for ((tile, mode) in listOf("Damier" to CoverageMode.CHECKERBOARD, "Zigzag" to CoverageMode.LINES)) {
            preview = null
            compose.onNodeWithText(tile).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Aperçu du trajet").performClick()
            compose.waitUntil(120_000) { preview != null || compose.onAllNodesWithText("recalcul requis", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("no refusal after choosing $tile", 0, compose.onAllNodesWithText("recalcul requis", substring = true).fetchSemanticsNodes().size)
            assertEquals(mode, preview?.mode)
            // Back to the settings page for the next change.
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
        }
    }

    @Test fun `obstacle learned during the computation still refuses the route`() {
        var preview: Preview? = null
        val ui = androidx.compose.runtime.mutableStateOf(state)
        fr.luba.poc.map.BorderTypeRepository.resetForTest()
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(ui.value, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true,
                onPreview = { preview = it })
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("Tout ajouter").performClick()
        compose.onNodeWithText("Suivant").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aperçu du trajet").performClick()
        // Changed before the main thread runs again: the computation in progress started without it.
        ui.value = state.copy(obstacles = listOf(fr.luba.poc.obstacles.LocalObstacle("tree", fr.luba.poc.obstacles.ObstacleKind.TREE, 3.0, 3.0, 0.3, 0.2)))
        compose.waitUntil(120_000) { preview != null || compose.onAllNodesWithText("recalcul requis", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(null, preview)
        // Either check of the computation (after the coverage, or after the height passes) refuses it.
        compose.onNodeWithText("recalcul requis", substring = true).assertExists()
    }

    /** Cause of the refusal: two references to one local function are equal whatever they capture. */
    @Test fun `references to one local function are equal whatever they capture`() {
        fun reference(value: Int): () -> Int {
            fun local() = value
            return ::local
        }
        val first = reference(1); val second = reference(2)
        assertEquals(first, second)
        assertNotEquals(first(), second())
    }
}
