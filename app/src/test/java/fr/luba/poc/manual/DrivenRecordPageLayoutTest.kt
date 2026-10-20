package fr.luba.poc.manual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.camera.LiveCamera
import fr.luba.poc.map.DrivenKind
import fr.luba.poc.map.DrivenTrace
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.After
import org.junit.Assert.assertTrue
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

/**
 * The "record by driving" page fits on a folded Fold (412 dp wide; ≈ 800 dp left once the status bar and the global
 * STOP bar are taken): live camera at the top, joysticks and STOP fully on screen at the bottom, and a band of map in
 * between where the robot is followed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h800dp-xxhdpi")
class DrivenRecordPageLayoutTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val robot by lazy { RobotBle(ApplicationProvider.getApplicationContext()) }

    // Camera view shown at its full 16:9 size (stream impossible: static view, no endless animation).
    @Before fun showCamera() { LiveCamera.setVisible(context, true); LiveCamera.relevant = true; LiveCamera.eligible = false }
    @After fun reset() { LiveCamera.relevant = false; Snapshot.sendApplyNotifications() }

    private val lawn = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val map = RobotMap(listOf(lawn), true, "test")

    private fun state(driven: DrivenTrace? = null) = RobotUi(connection = ConnectionState.Receiving, ready = true,
        selected = SeenRobot("Luba-VSDEMO01", "AA:BB:CC:DD:EE:FF", -60), robotMap = map, mammotionMap = map, driven = driven,
        telemetry = Telemetry(battery = 87, x = 9.0, y = 4.0, heading = 120.0, rtkStatus = 4, positioningLevel = 4))

    private fun show(ui: RobotUi) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { DrivenRecordPage(robot, ui, back = {}) } } }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun assertFits() {
        val root = compose.onRoot().getBoundsInRoot()
        val top = compose.onNodeWithTag("driven-top").getBoundsInRoot()
        val deck = compose.onNodeWithTag("driven-deck").getBoundsInRoot()
        assertTrue("deck off screen: $deck in $root", deck.bottom <= root.bottom && deck.top >= root.top)
        assertTrue("map band too thin: ${deck.top - top.bottom}", deck.top - top.bottom >= 180.dp)
        // Camera view at the top, joysticks and STOP inside the deck.
        val camera = compose.onNodeWithText("Vidéo indisponible · toucher pour relancer", substring = true, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("camera outside the top block: $camera", camera.top >= top.top && camera.bottom <= top.bottom)
        for (description in listOf("Joystick avancer / reculer", "Joystick tourner")) {
            val pad = compose.onNodeWithContentDescription(description).getBoundsInRoot()
            assertTrue("$description outside the deck: $pad", pad.top >= deck.top && pad.bottom <= deck.bottom)
            assertTrue("$description too small: $pad", pad.bottom - pad.top >= 140.dp && pad.right - pad.left >= 120.dp)
        }
        val stop = compose.onNodeWithText("STOP").getBoundsInRoot()
        assertTrue("STOP outside the deck: $stop", stop.top >= deck.top && stop.bottom <= deck.bottom)
    }

    @Test fun beforeRecordingTheWholeLayoutFits() {
        show(state())
        assertFits()
        compose.onNodeWithText("Démarrer").assertIsEnabled()
        // Joysticks locked until supervision is confirmed.
        compose.onNodeWithText("Je supervise le robot").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("Je supervise le robot").assertDoesNotExist()
    }

    @Test fun whileRecordingTheWholeLayoutFits() {
        val trace = (0 until 40).map { Point(5.0 + it * 0.1, 4.0) }
        show(state(DrivenTrace(DrivenKind.ZONE, 1L, trace)))
        assertFits()
        compose.onNodeWithText("Terminer").assertExists()
        compose.onNodeWithContentDescription("Annuler l'enregistrement").assertExists()
    }
}
