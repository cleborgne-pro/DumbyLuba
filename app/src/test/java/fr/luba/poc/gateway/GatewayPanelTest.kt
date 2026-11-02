package fr.luba.poc.gateway

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.withComposeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Gateway panel: the groups shown for each role of the phone, and the settings shared between them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h2400dp-xxhdpi")
class GatewayPanelTest {
    val compose = createComposeRule()

    @get:Rule val rules: RuleChain = withComposeTestActivity(compose)

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Group titles are shown in capitals. */
    private fun group(title: String) = title.uppercase()

    private fun show(config: GatewayConfig) {
        GatewayConfig.save(app, config)
        compose.setContent { LubaTheme(false) { Column(Modifier.verticalScroll(rememberScrollState())) { GatewayPanel(linkIdle = true) } } }
        compose.waitForIdle()
    }

    @Test fun `direct Bluetooth pilot - pairing and connection groups, no gateway routes`() {
        show(GatewayConfig())
        compose.onNodeWithText(group("Appairage")).assertExists()
        compose.onNodeWithText(group("Connexion à la passerelle")).assertExists()
        compose.onNodeWithText("Aucune passerelle appairée", substring = true).assertExists()
        compose.onNodeWithText(group("Trajets")).assertDoesNotExist()
        compose.onNodeWithText(group("État de la passerelle")).assertDoesNotExist()
    }

    @Test fun `pilot through a paired gateway - routes and updates groups`() {
        show(GatewayConfig(role = GatewayRole.CLIENT, host = "192.0.2.10", pilotCode = "123456"))
        compose.onNodeWithText("Passer par la passerelle").assertExists()
        compose.onNodeWithText(group("Trajets")).assertExists()
        compose.onNodeWithText(group("Mises à jour de la passerelle ESP32")).assertExists()
    }

    @Test fun `gateway phone - status and settings groups, no pairing`() {
        show(GatewayConfig(role = GatewayRole.GATEWAY, code = "654321"))
        compose.onNodeWithText(group("État de la passerelle")).assertExists()
        compose.onNodeWithText(group("Réglages de la passerelle")).assertExists()
        compose.onNodeWithText(group("Appairage")).assertDoesNotExist()
    }

    @Test fun `forgetting the gateway in the role group updates the pilot groups`() {
        show(GatewayConfig(role = GatewayRole.CLIENT, host = "192.0.2.10", pilotCode = "123456"))
        compose.onNodeWithText("Oublier cette passerelle").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(group("Trajets")).assertDoesNotExist()
        compose.onNodeWithText("Aucune passerelle appairée", substring = true).assertExists()
        assertEquals(GatewayRole.OFF, GatewayConfig.read(app).role)
    }
}
