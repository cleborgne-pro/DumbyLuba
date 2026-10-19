package fr.luba.poc.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.camera.CameraWindow
import fr.luba.poc.camera.CameraWindowState
import fr.luba.poc.camera.rememberCameraWindowState
import org.junit.Assert.assertEquals
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

/** Floating camera window: dragging moves it, the handle resizes it, a single tap goes to the content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class CameraWindowGestureTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private lateinit var window: CameraWindowState
    private var taps = 0

    @Before fun clearSaved() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("camera_window", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show() = compose.setContent {
        Box(Modifier.fillMaxSize()) {
            window = rememberCameraWindowState("test", 168.dp)
            CameraWindow(window, Alignment.TopEnd, Modifier.padding(end = 60.dp, top = 72.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).testTag("video").clickable { taps++ })
            }
        }
    }

    @Test fun dragMovesTheWindowAndKeepsItOnScreen() {
        show()
        val start = compose.onNodeWithTag("video").getBoundsInRoot()
        compose.onNodeWithTag("video").performTouchInput { swipe(center, center + Offset(-100f, 200f), 300) }
        compose.waitForIdle()
        val moved = compose.onNodeWithTag("video").getBoundsInRoot()
        assertEquals(start.left.value - 100f, moved.left.value, 2f)
        assertEquals(start.top.value + 200f, moved.top.value, 2f)
        assertEquals("A drag does not reach the content", 0, taps)
        // Position saved.
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("camera_window", Context.MODE_PRIVATE)
        assertEquals(-100f, prefs.getFloat("test.dx", 0f), 2f)

        // Dragged far to the right and up: stuck to the edges, never off screen.
        compose.onNodeWithTag("video").performTouchInput { swipe(center, center + Offset(2000f, -2000f), 300) }
        compose.waitForIdle()
        val edge = compose.onNodeWithTag("video").getBoundsInRoot()
        assertEquals(400f - 4f, edge.right.value, 2f)
        assertEquals(4f, edge.top.value, 2f)
    }

    @Test fun handleResizesFromTheOppositeCornerAndTapReachesContent() {
        show()
        val start = compose.onNodeWithTag("video").getBoundsInRoot()
        compose.onNodeWithTag("video").performClick()
        compose.waitForIdle()
        assertEquals("A single tap goes to the content (restarts the stream)", 1, taps)

        // Bottom-left handle: dragged 80 dp to the left, the window widens, top-right corner fixed.
        compose.onNodeWithTag("video").performTouchInput { swipe(bottomLeft + Offset(8f, -8f), bottomLeft + Offset(-72f, -8f), 300) }
        compose.waitForIdle()
        val bigger = compose.onNodeWithTag("video").getBoundsInRoot()
        assertEquals(start.width.value + 80f, bigger.width.value, 3f)
        assertEquals(start.right.value, bigger.right.value, 1f)
        assertEquals(start.top.value, bigger.top.value, 1f)
        assertTrue(window.width > 168f)
    }
}
