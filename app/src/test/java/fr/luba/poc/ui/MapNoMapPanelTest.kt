package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.mapFetchAllowed
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Empty map on the pilot: the robot map reading is offered on the map itself, through the gateway when the pilot
 * goes through it, and stays disabled while the robot is not linked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h800dp-xxhdpi")
class MapNoMapPanelTest {
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val gatewayRobot = RobotUi(connection = ConnectionState.Receiving, ready = true, viaGateway = true,
        telemetry = Telemetry(battery = 80, x = 1.0, y = 1.0, heading = 0.0, robotState = 11))

    private fun show(state: RobotUi, onFetch: () -> Unit = {}, onAccount: () -> Unit = {}) {
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(state, SimulationSession(), onFetchMap = onFetch, onOpenRobot = {}, onOpenAccount = onAccount, onBack = {})
        } } }
        compose.waitForIdle()
    }

    @Test fun `pilot through the gateway without map, reading triggered from the map`() {
        var fetched = 0
        show(gatewayRobot, onFetch = { fetched++ })
        compose.onNodeWithText("Aucune carte sur ce téléphone").assertExists()
        compose.onNodeWithText("Robot connecté par la passerelle : la passerelle lit sa carte en Bluetooth.").assertExists()
        compose.onNodeWithText("Lire par la passerelle").assertIsEnabled().performClick()
        assertEquals(1, fetched)
    }

    @Test fun `robot not linked, reading disabled, account map offered`() {
        var account = 0
        show(RobotUi(), onAccount = { account++ })
        compose.onNodeWithText("Lire la carte du robot").assertIsNotEnabled()
        compose.onNodeWithText("Carte du compte").performClick()
        assertEquals(1, account)
    }

    @Test fun `reading in progress, panel shows the progress`() {
        show(gatewayRobot.copy(mapLoading = true))
        compose.onNodeWithText("Lecture…").assertIsNotEnabled()
    }

    @Test fun `reading allowed only with a linked and idle robot`() {
        assertTrue(mapFetchAllowed(gatewayRobot, false))
        assertFalse(mapFetchAllowed(gatewayRobot, true))
        assertFalse(mapFetchAllowed(gatewayRobot.copy(ready = false), false))
        assertFalse(mapFetchAllowed(gatewayRobot.copy(mapLoading = true), false))
        assertFalse(mapFetchAllowed(gatewayRobot.copy(manualActive = true), false))
    }
}
