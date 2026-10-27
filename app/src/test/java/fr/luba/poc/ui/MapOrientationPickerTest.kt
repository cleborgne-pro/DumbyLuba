package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import fr.luba.poc.map.RobotMap
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * Manual direction chosen on the map ([fr.luba.poc.map.OrientationPicker]), on a folded Fold: guide map shown in Manual
 * only, a finger drawn across it sets the angle, and "Route preview" computes the route at that angle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w344dp-h882dp-xxhdpi")
class MapOrientationPickerTest {
    private val out: String? = System.getProperty("uiShots")
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val state = RobotUi(robotMap = RobotMap(listOf(zone), true, "test"), connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 80, x = 6.0, y = 4.0, heading = 0.0, robotState = 11))

    @Test fun `finger drawn on the map sets the manual angle used by the preview`() {
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
        compose.onNodeWithText("Zigzag").performClick()
        compose.onNodeWithText("Automatique").performClick()
        compose.waitForIdle()
        assertEquals("no guide map outside Manual", 0, compose.onAllNodesWithText("Un doigt : direction", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Manuelle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("orientation-map").performScrollTo()
        // Up to the right on the screen, as many pixels across as up: 45° on the map.
        compose.onNodeWithTag("orientation-map").performTouchInput {
            swipe(Offset(centerX - 150f, centerY + 150f), Offset(centerX + 150f, centerY - 150f), durationMillis = 400)
        }
        compose.waitForIdle()
        out?.let { compose.onRoot().captureRoboImage("$it/task-orientation-map.png") }
        // Angle on the map and on the slider.
        assertTrue(compose.onAllNodesWithText("45°").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("Aperçu du trajet").performClick()
        // Reading the screen in the condition lets the computation's main-thread steps run; a refusal is shown in the message below.
        runCatching { compose.waitUntil(60_000) { compose.onAllNodesWithText("Démarrer").fetchSemanticsNodes(); preview != null } }
        val texts = compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }.map { it.text }
        assertEquals(texts.joinToString(" | "), listOf(45), preview?.zoneAngles)
    }
}
