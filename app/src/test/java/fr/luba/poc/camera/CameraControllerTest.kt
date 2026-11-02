package fr.luba.poc.camera

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private fun playerPage() = context.assets.open("camera/agora.html").bufferedReader().use { it.readText() }

    /** Regression of 2026-10-30: the vendored SDK, relative to the page's fake HTTPS origin, was never served. */
    @Test fun `every script of the player page is served from the app assets`() {
        val scripts = Regex("""<script src="([^"]+)"""").findAll(playerPage()).map { it.groupValues[1] }.toList()
        assertTrue("no script tag in agora.html", scripts.isNotEmpty())
        for (src in scripts) {
            val response = cameraPageAsset(context, CAMERA_PAGE_ORIGIN + src)
            assertNotNull(src, response?.data)
            assertEquals("text/javascript", response!!.mimeType)
            val head = response.data.bufferedReader().use { it.readText().take(200) }
            assertTrue(src, head.contains("AgoraInc"))
        }
    }

    @Test fun `only files of the camera folder are served, and only under the page origin`() {
        assertNull(cameraPageAsset(context, "https://download.agora.io/sdk/release/AgoraRTC_N-4.24.3.js"))
        assertNull(cameraPageAsset(context, "wss://1-2-3-4.edge.agora.io:4706"))
        for (name in listOf("../AndroidManifest.xml", "..", "missing.js", "sub/agora.html", "agora.html.bak")) {
            assertEquals(name, 404, cameraPageAsset(context, CAMERA_PAGE_ORIGIN + name)!!.statusCode)
        }
    }

    /** Agora edge servers listen on their own ports and the SDK falls back to its SD-RTN domains and a blob worker. */
    @Test fun `the player page policy lets the Agora SDK reach its edge servers`() {
        val policy = Regex("""Content-Security-Policy" content="([^"]+)"""").find(playerPage())!!.groupValues[1]
        val directives = policy.split(';').map { it.trim().split(' ') }.associate { it.first() to it.drop(1) }
        val connect = directives.getValue("connect-src")
        for (source in listOf("wss://*.agora.io:*", "https://*.agora.io:*", "wss://*.sd-rtn.com:*", "https://*.sd-rtn.com:*")) {
            assertTrue(source, source in connect)
        }
        assertEquals(listOf("blob:"), directives["worker-src"])
        assertEquals(listOf("'self'", "'unsafe-inline'"), directives["script-src"])
    }
}
