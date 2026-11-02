package fr.luba.poc.map

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.luba.poc.simulation.SmartFeatureFlags
import fr.luba.poc.simulation.SmartFeatures
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.weather.WeatherService
import fr.luba.poc.weather.WeatherState
import fr.luba.poc.withComposeTestActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task settings → Smart features: one switch per optional feature ([SmartFeaturesGroup]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartFeaturesGroupTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = withComposeTestActivity(compose)

    @After fun reset() { SmartFeatures.set(SmartFeatureFlags()); WeatherService.resetForTest() }

    @Test fun `each switch turns its feature off and the rain return needs the weather`() {
        WeatherService.setStateForTest(WeatherState(location = null))
        compose.setContent { LubaTheme { SmartFeaturesGroup() } }
        compose.onNodeWithText("Vitesse adaptative").performClick()
        compose.waitForIdle()
        assertFalse(SmartFeatures.adaptiveSpeed)
        assertTrue(SmartFeatures.returnLanes)
        compose.onNodeWithText("Voies de retour").performClick()
        compose.waitForIdle()
        assertFalse(SmartFeatures.returnLanes)
        // No garden position: the rain return cannot be switched on here.
        compose.onNodeWithText("Nécessite la surveillance de la pluie", substring = true).assertExists()
        compose.onNodeWithText("Retour à la base sur pluie confirmée").assertExists()
    }
}
