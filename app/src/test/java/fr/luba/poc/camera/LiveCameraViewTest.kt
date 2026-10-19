package fr.luba.poc.camera

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live camera view (map, manual control) depending on the stream state: button to show it, loading until the first
 * frame, reason when no stream is possible, failure retried with a tap, closing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class LiveCameraViewTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private fun reset() {
        LiveCamera.relevant = false; LiveCamera.eligible = false; LiveCamera.owned = false; LiveCamera.streaming = false
        LiveCamera.status = ""; LiveCamera.blocked = ""; LiveCamera.dropOnDemand(); LiveCamera.closedByUser = false
        LiveCamera.retryRequested = false; CameraController.pageVisible = false
    }
    @Before fun setUp() = reset()
    @After fun tearDown() { reset(); LiveCamera.setVisible(compose.activity, true) }

    /** Infinite loading animations: manual clock. */
    private fun show(large: Boolean) {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
        compose.setContent { LubaTheme(false) { Column { LiveCameraButton(); LiveCameraView(large = large) } } }
        frames()
    }
    private fun frames(count: Int = 5) = repeat(count) { compose.mainClock.advanceTimeByFrame() }
    private fun change(block: () -> Unit) { compose.runOnUiThread { block(); Snapshot.sendApplyNotifications() }; frames() }

    @Test fun `robot stopped, view hidden then shown on demand, loading until the first frame`() {
        LiveCamera.eligible = true
        show(large = true)
        compose.onNodeWithContentDescription("Afficher la caméra").assertExists()
        compose.onNodeWithText("Afficher la caméra").performClick(); frames()
        assertTrue(LiveCamera.onDemand)
        compose.onNodeWithContentDescription("Caméra en cours de connexion").assertExists()
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Masquer la caméra")).fetchSemanticsNodes().size)
        // Close button of the large view: view closed, setting kept (robot stopped).
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Masquer la caméra"))[1].performClick(); frames()
        assertFalse(LiveCamera.onDemand); assertTrue(LiveCamera.visible)
        compose.onNodeWithText("Afficher la caméra").assertExists()
    }

    @Test fun `movement without connected account, reason shown and no retry`() {
        LiveCamera.relevant = true; LiveCamera.blocked = LiveCamera.blockedReason(connected = false, mobile = false)
        show(large = false)
        change { LiveCamera.retryRequested = false }
        compose.onNodeWithText("Compte Mammotion non connecté").assertExists()
        compose.onNodeWithText("Compte Mammotion non connecté").performClick(); frames()
        assertFalse(LiveCamera.retryRequested)
    }

    @Test fun `stream failure, the view says so and a tap retries the request`() {
        LiveCamera.eligible = true; LiveCamera.relevant = true
        LiveCamera.status = "Échec du flux vidéo : aucune image"
        show(large = false)
        // Screen open: an attempt is already allowed; it is consumed to see the effect of the tap.
        change { LiveCamera.retryRequested = false }
        compose.onNodeWithText("Vidéo indisponible · toucher pour relancer").performClick(); frames()
        assertTrue(LiveCamera.retryRequested)
        // New request in progress: back to loading.
        change { LiveCamera.status = "Demande du jeton" }
        compose.onNodeWithContentDescription("Caméra en cours de connexion").assertExists()
    }

    @Test fun `map button hides the automatic view during a movement`() {
        LiveCamera.eligible = true; LiveCamera.relevant = true
        show(large = false)
        compose.onNodeWithContentDescription("Masquer la caméra").performClick(); frames()
        assertFalse(LiveCamera.visible)
        compose.onNodeWithContentDescription("Afficher la caméra").performClick(); frames()
        assertTrue(LiveCamera.visible)
    }
}
