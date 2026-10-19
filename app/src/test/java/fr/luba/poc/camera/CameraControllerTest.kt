package fr.luba.poc.camera

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** WRAP_CONTENT (Compose AndroidView default): zero-height CSS viewport, invisible video. */
    @Test fun `the player fills the whole host screen area`() {
        val host = FrameLayout(context)
        CameraPlayerHost.claim(host)
        val view = host.getChildAt(0)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.height)
    }

    /**
     * Crashes of 2026-10-14 ("The specified child already has a parent"): two screens claim the single player in the same
     * composition. Each host has its own container; the player moves from one to the other without exception, never in both.
     */
    @Test fun `two hosts pass the player along without exception`() {
        val map = FrameLayout(context); val thumbnail = FrameLayout(context)
        CameraPlayerHost.claim(map)
        val player = map.getChildAt(0)
        CameraPlayerHost.claim(thumbnail)
        assertEquals(0, map.childCount)
        assertSame(player, thumbnail.getChildAt(0))
        CameraPlayerHost.claim(map)
        CameraPlayerHost.claim(map)
        assertEquals(1, map.childCount)
        assertEquals(0, thumbnail.childCount)
        assertSame(map, player.parent)
    }
}
