package fr.luba.poc.camera

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Shared video player ([CameraController]) driven as by the Agora page: loading, channel joined once per token, frames,
 * photo, stop and reload; then live view ([LiveCamera]): camera button, on-demand view, hidden view kept.
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val credentials = CameraCredentials("app", "canal", "jeton", 42)
    private lateinit var view: WebView

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun lastJs() = shadowOf(view).lastEvaluatedJavascript
    /** JavaScript bridge of the page ("LubaCamera"), called as the Agora player would. */
    private fun page(method: String, vararg args: Any) {
        val bridge = shadowOf(view).getJavascriptInterface("LubaCamera")
        bridge.javaClass.declaredMethods.single { it.name == method }.apply { isAccessible = true }.invoke(bridge, *args)
        idle()
    }

    @Before fun setUp() {
        view = CameraController.webView(context)
        CameraController.stop()
        // Following page loads succeed: browser callbacks replayed on the next main-thread pass.
        shadowOf(view).performSuccessfulPageLoadClientCallbacks()
        CameraController.reload()
        CameraController.target = 1
        resetLive()
    }

    @After fun tearDown() { CameraController.stop(); resetLive() }

    private fun resetLive() {
        LiveCamera.relevant = false; LiveCamera.eligible = false; LiveCamera.owned = false; LiveCamera.streaming = false
        LiveCamera.dropOnDemand(); LiveCamera.closedByUser = false; LiveCamera.retryRequested = false
        repeat(LiveCamera.hosts) { LiveCamera.hostLeave() }; repeat(LiveCamera.embedded) { LiveCamera.detach() }
        LiveCamera.setVisible(context, true)
    }

    @Test fun `channel joined only once per token, and only once the page is loaded`() {
        assertFalse(CameraController.ready)
        assertEquals("SDK vidéo en chargement", CameraController.status)
        CameraController.start(credentials, 5)
        assertEquals(0L, CameraController.joinedSession)
        idle()
        assertTrue(CameraController.ready)
        CameraController.start(credentials, 5)
        assertEquals(5L, CameraController.joinedSession)
        assertTrue(lastJs(), lastJs().startsWith("start({") && lastJs().contains("\"target\":1"))
        CameraController.replay()
        CameraController.start(credentials, 5)
        assertEquals("replay()", lastJs())
        CameraController.select(2)
        assertEquals("select(2)", lastJs())
        assertEquals(2, CameraController.target)
    }

    @Test fun `player state reported by the page`() {
        idle()
        page("sdk", true)
        assertEquals(true, CameraController.sdkLoaded)
        page("status", "x".repeat(300))
        assertEquals(200, CameraController.status.length)
        page("users", "1, 2,x,")
        assertEquals(listOf(1, 2), CameraController.users)
        page("stats", "s".repeat(200))
        assertEquals(160, CameraController.stats.length)
        val left = CameraController.peerLeft
        page("peerLeft")
        assertEquals(left + 1, CameraController.peerLeft)
        CameraController.start(credentials, 6)
        assertNull(CameraController.firstFrameAt)
        page("frame", 2)
        val first = CameraController.firstFrameAt!!
        page("frame", 1)
        assertEquals(first, CameraController.firstFrameAt)
        assertEquals(setOf(1, 2), CameraController.framedUids)
        // Stop: channel left, stream state forgotten.
        CameraController.photoOwned = true
        CameraController.stop()
        assertEquals("stop()", lastJs())
        assertNull(CameraController.firstFrameAt)
        assertEquals(emptySet<Int>(), CameraController.framedUids)
        assertEquals(0L, CameraController.joinedSession)
        assertFalse(CameraController.photoOwned)
    }

    @Test fun `photo of the displayed frame, missing without frame, abandoned on stop`() {
        var shot: CameraSnapshot? = CameraSnapshot(ByteArray(0), null, null)
        GlobalScope.launch(Dispatchers.Unconfined) { shot = CameraController.snapshot(2) }
        assertNull("player not loaded", shot)
        idle()

        fun request(): Int { val js = lastJs(); assertTrue(js, js.startsWith("snapshot(") && js.endsWith(", 2)")); return js.removePrefix("snapshot(").substringBefore(',').toInt() }
        var done = false
        GlobalScope.launch(Dispatchers.Unconfined) { shot = CameraController.snapshot(2); done = true }
        page("snapshot", request(), android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.DEFAULT), 640, 360, 1_000.0, 350.0)
        assertTrue(done)
        assertArrayEquals(byteArrayOf(1, 2, 3), shot!!.jpeg)
        assertEquals(1_000L, shot!!.frameAt); assertEquals(350L, shot!!.delayMs)

        // Frame tracking impossible (-1) and unknown delay (0): not filled in.
        done = false
        GlobalScope.launch(Dispatchers.Unconfined) { shot = CameraController.snapshot(2); done = true }
        page("snapshot", request(), "AAEC", 1, 1, -1.0, 0.0)
        assertNull(shot!!.frameAt); assertNull(shot!!.delayMs)
        // No decoded frame: no photo.
        done = false
        GlobalScope.launch(Dispatchers.Unconfined) { shot = CameraController.snapshot(2); done = true }
        page("snapshot", request(), "", 0, 0, 0.0, 0.0)
        assertTrue(done); assertNull(shot)
        // Stop or reload during the wait: the request ends without a photo.
        for (end in listOf({ CameraController.stop() }, { CameraController.reload() })) {
            shot = CameraSnapshot(ByteArray(0), null, null); done = false
            idle()
            GlobalScope.launch(Dispatchers.Unconfined) { shot = CameraController.snapshot(2); done = true }
            end()
            assertTrue(done); assertNull(shot)
        }
    }

    @Test fun `stream health read from the page, unreadable ignored`() {
        var health: CameraHealth? = CameraHealth(0, "", 0)
        GlobalScope.launch(Dispatchers.Unconfined) { health = CameraController.health() }
        assertNull(health)
        idle()
        GlobalScope.launch(Dispatchers.Unconfined) { health = CameraController.health() }
        assertEquals("health()", lastJs())
        shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("{\"at\":1234.0,\"state\":\"CONNECTED\",\"users\":2}")
        assertEquals(CameraHealth(1234, "CONNECTED", 2), health)
        GlobalScope.launch(Dispatchers.Unconfined) { health = CameraController.health() }
        shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue("null")
        assertNull(health)
    }

    @Test fun `player reload, channel to be joined again`() {
        idle()
        page("sdk", false)
        CameraController.start(credentials, 9)
        CameraController.reload()
        assertFalse(CameraController.ready)
        assertNull(CameraController.sdkLoaded)
        assertEquals(0L, CameraController.joinedSession)
        assertTrue(shadowOf(view).lastLoadDataWithBaseURL.baseUrl.startsWith("https://"))
    }

    // ---- Live view ----

    @Test fun `robot stopped, camera button opens then closes the on-demand view without changing the setting`() {
        LiveCamera.eligible = true
        assertFalse(LiveCamera.shown)
        LiveCamera.toggle(context)
        assertTrue(LiveCamera.onDemand); assertTrue(LiveCamera.shown); assertTrue(LiveCamera.retryRequested)
        LiveCamera.toggle(context)
        assertFalse(LiveCamera.onDemand); assertFalse(LiveCamera.shown)
        assertTrue(LiveCamera.visible); assertTrue(LiveCamera.closedByUser)
    }

    @Test fun `automatic view hidden during a movement, choice kept until the next press`() {
        LiveCamera.eligible = true; LiveCamera.relevant = true
        assertTrue(LiveCamera.shown)
        LiveCamera.toggle(context)
        assertFalse(LiveCamera.visible); assertFalse(LiveCamera.shown)
        // Read again on the next launch.
        LiveCamera::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
        LiveCamera.setVisible(context, false)
        LiveCamera.load(context)
        assertFalse(LiveCamera.visible)
        assertFalse(LiveCamera.wanted(LiveCamera.visible, moving = true, manualPage = false, eligible = true))
        LiveCamera.toggle(context)
        assertTrue(LiveCamera.visible); assertFalse("movement: no manual request", LiveCamera.onDemand)
        assertTrue(context.getSharedPreferences("live_camera", 0).getBoolean("visible", false))
    }

    @Test fun `automatic stream still open after the movement, view kept during the stop`() {
        LiveCamera.eligible = true
        LiveCamera.owned = true; LiveCamera.streaming = true
        assertTrue(LiveCamera.shown)
        LiveCamera.streaming = false
        assertFalse(LiveCamera.shown)
    }

    @Test fun `host screens and thumbnails counted without going below zero`() {
        LiveCamera.retryRequested = false
        LiveCamera.hostEnter()
        assertEquals(1, LiveCamera.hosts); assertTrue("back on the map: new attempt allowed", LiveCamera.retryRequested)
        LiveCamera.hostLeave(); LiveCamera.hostLeave()
        assertEquals(0, LiveCamera.hosts)
        LiveCamera.attach(); LiveCamera.detach(); LiveCamera.detach()
        assertEquals(0, LiveCamera.embedded)
        LiveCamera.reconnects = 2
        LiveCamera.retry()
        assertEquals(0, LiveCamera.reconnects)
    }
}
