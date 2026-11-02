package fr.luba.poc.camera

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.RobotBench
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.withComposeTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Camera host without a Mammotion account: the obstacle photo gives up at once and the live view says why it is off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CameraHostTest {
    val compose = createComposeRule()

    @get:Rule val rules: RuleChain = withComposeTestActivity(compose)

    private val bench by lazy { RobotBench() }

    @After fun tearDown() = bench.close()

    private fun showHost() {
        val cloud = CloudViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            val ui by bench.ble.state.collectAsState()
            LubaTheme(false) { Box(Modifier.fillMaxSize()) { CameraHost(bench.ble, ui, cloud, CloudUi()) } }
        }
        compose.waitForIdle()
    }

    @Test fun `obstacle photo without an account fails at once with its reason`() {
        bench.ble.update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = ObstaclePhotoRequest(7, 1_000L, 1, 1.0, 2.0))) }
        showHost()
        compose.waitUntil(5_000) { bench.state.obstaclePhoto.request == null }

        assertNull(bench.state.obstaclePhoto.request)
        assertTrue(bench.state.obstaclePhoto.status, bench.state.obstaclePhoto.status.startsWith("Photo impossible : "))
    }

    @Test fun `live view without an account is not eligible and says why`() {
        showHost()

        assertFalse(LiveCamera.eligible)
        assertFalse(LiveCamera.relevant)
        assertEquals("Compte Mammotion non connecté", LiveCamera.blocked)
    }
}
