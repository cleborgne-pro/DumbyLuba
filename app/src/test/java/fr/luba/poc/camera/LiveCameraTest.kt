package fr.luba.poc.camera

import org.junit.Assert.*
import org.junit.Test

/** Live camera: stream requested only during a movement or manual control, view shown, stream possible. */
class LiveCameraTest {
    @Test fun streamIsWantedOnlyWhileMovingOrPiloting() {
        assertTrue(LiveCamera.wanted(visible = true, moving = true, manualPage = false, eligible = true))
        assertTrue(LiveCamera.wanted(visible = true, moving = false, manualPage = true, eligible = true))
        assertFalse(LiveCamera.wanted(visible = true, moving = false, manualPage = false, eligible = true))
    }

    @Test fun hiddenViewOrUnavailableStreamNeverStartsTheCamera() {
        assertFalse(LiveCamera.wanted(visible = false, moving = true, manualPage = true, eligible = true))
        // Mammotion account disconnected or robot on 4G (quota): no automatic stream.
        assertFalse(LiveCamera.wanted(visible = true, moving = true, manualPage = true, eligible = false))
    }

    @Test fun manualRequestStreamsWhileStoppedButNeverWithoutAStreamPossible() {
        assertTrue(LiveCamera.wanted(visible = true, moving = false, manualPage = false, eligible = true, onDemand = true))
        assertFalse(LiveCamera.wanted(visible = true, moving = false, manualPage = false, eligible = false, onDemand = true))
    }

    @Test fun blockedReasonExplainsWhyNoStreamIsPossible() {
        assertEquals("", LiveCamera.blockedReason(connected = true, mobile = false))
        assertTrue(LiveCamera.blockedReason(connected = false, mobile = false).contains("non connecté"))
        assertTrue(LiveCamera.blockedReason(connected = true, mobile = true).contains("4G"))
    }

    @Test fun viewShowsLoadingUntilTheFirstImageThenThePlayer() {
        assertEquals(LiveCamera.Phase.LOADING, LiveCamera.phase(eligible = true, streaming = false, pageVisible = false, firstFrame = false, status = ""))
        assertEquals(LiveCamera.Phase.LOADING, LiveCamera.phase(eligible = true, streaming = true, pageVisible = false, firstFrame = false, status = "Jeton reçu"))
        assertEquals(LiveCamera.Phase.PLAYER, LiveCamera.phase(eligible = true, streaming = true, pageVisible = false, firstFrame = true, status = "Caméra en direct"))
        assertEquals(LiveCamera.Phase.UNAVAILABLE, LiveCamera.phase(eligible = false, streaming = false, pageVisible = false, firstFrame = false, status = ""))
        assertEquals(LiveCamera.Phase.UNAVAILABLE, LiveCamera.phase(eligible = true, streaming = false, pageVisible = false, firstFrame = false, status = "Échec du flux vidéo : …"))
    }

    @Test fun fallbackThumbnailNeverDuplicatesTheMapView() {
        // Map view shown (player or loading): no bottom thumbnail.
        assertFalse(LiveCamera.fallbackThumbnail(streaming = true, pageVisible = false, embedded = 1, hosts = 1, owned = true))
        // View hidden on the map, live view stream stopping: no bottom thumbnail either.
        assertFalse(LiveCamera.fallbackThumbnail(streaming = true, pageVisible = false, embedded = 0, hosts = 1, owned = true))
        // Obstacle photo with the view hidden: the WebView must stay displayed to decode.
        assertTrue(LiveCamera.fallbackThumbnail(streaming = true, pageVisible = false, embedded = 0, hosts = 1, owned = false))
        // Outside the map, stream still open: bottom thumbnail; Camera page open: no.
        assertTrue(LiveCamera.fallbackThumbnail(streaming = true, pageVisible = false, embedded = 0, hosts = 0, owned = true))
        assertFalse(LiveCamera.fallbackThumbnail(streaming = true, pageVisible = true, embedded = 0, hosts = 0, owned = false))
        assertFalse(LiveCamera.fallbackThumbnail(streaming = false, pageVisible = false, embedded = 0, hosts = 0, owned = false))
    }

    private fun supervise(wanted: Boolean = true, playing: Boolean = false, busy: Boolean = false, photo: Boolean = false,
                          page: Boolean = false, failed: Boolean = false, retry: Boolean = false) =
        LiveCamera.supervise(wanted, playing, busy, photo, page, failed, retry)

    @Test fun streamStoppedWhileTheViewIsWantedIsRequestedAgain() {
        // 2026-10-15: stream stopped (photo finished, background, Camera page left) while the view was still wanted:
        // it stayed loading. The supervisor requests it again.
        assertEquals(LiveCamera.Supervision.REQUEST, supervise())
        assertEquals(LiveCamera.Supervision.NONE, supervise(wanted = false))
        assertEquals(LiveCamera.Supervision.NONE, supervise(busy = true))
    }

    @Test fun openStreamIsAdoptedByTheLiveViewInsteadOfBeingStopped() {
        assertEquals(LiveCamera.Supervision.ADOPT, supervise(playing = true))
        // Obstacle photo in progress or Camera page open: their stream is not taken.
        assertEquals(LiveCamera.Supervision.NONE, supervise(playing = true, photo = true))
        assertEquals(LiveCamera.Supervision.NONE, supervise(playing = true, page = true))
        assertEquals(LiveCamera.Supervision.NONE, supervise(photo = true))
    }

    @Test fun failureIsRetriedOnlyWhenARetryIsAllowed() {
        assertEquals(LiveCamera.Supervision.NONE, supervise(failed = true))
        assertEquals(LiveCamera.Supervision.REQUEST, supervise(failed = true, retry = true))
        assertTrue(LiveCamera.failed(playing = false, busy = false, status = "Échec du flux vidéo : 50504"))
        assertFalse(LiveCamera.failed(playing = false, busy = false, status = "Caméra arrêtée"))
        assertFalse(LiveCamera.failed(playing = false, busy = true, status = "Échec du flux vidéo : 50504"))
    }

    @Test fun retryIsAllowedWhenReturningToTheMapOrTappingTheView() {
        LiveCamera.retryRequested = false; LiveCamera.reconnects = 2
        LiveCamera.hostEnter()
        assertTrue(LiveCamera.retryRequested)
        assertEquals(0, LiveCamera.reconnects)
        LiveCamera.hostLeave()
        LiveCamera.retryRequested = false
        LiveCamera.retry()
        assertTrue(LiveCamera.retryRequested)
        LiveCamera.retryRequested = false
    }

    @Test fun frozenImageIsReplayedThenTheStreamRestartedThenAbandoned() {
        assertEquals(LiveCamera.Watch.OK, LiveCamera.watch(2_000, "CONNECTED", replayed = false, reconnects = 0))
        assertEquals(LiveCamera.Watch.REPLAY, LiveCamera.watch(7_000, "CONNECTED", replayed = false, reconnects = 0))
        assertEquals(LiveCamera.Watch.OK, LiveCamera.watch(7_000, "CONNECTED", replayed = true, reconnects = 0))
        assertEquals(LiveCamera.Watch.RESTART, LiveCamera.watch(13_000, "CONNECTED", replayed = true, reconnects = 0))
        assertEquals(LiveCamera.Watch.GIVE_UP, LiveCamera.watch(13_000, "CONNECTED", replayed = true, reconnects = LiveCamera.MAX_RECONNECTS))
    }

    @Test fun lostAgoraConnectionIsRestartedWithoutWaiting() {
        assertEquals(LiveCamera.Watch.RESTART, LiveCamera.watch(0, "DISCONNECTED", replayed = false, reconnects = 0))
        assertEquals(LiveCamera.Watch.RESTART, LiveCamera.watch(0, "NONE", replayed = false, reconnects = 1))
        // While reconnecting, Agora still gets its chance until the frozen-frame timeout.
        assertEquals(LiveCamera.Watch.OK, LiveCamera.watch(3_000, "RECONNECTING", replayed = false, reconnects = 0))
    }

    @Test fun playerHealthIsReadFromThePage() {
        val h = CameraHealth.parse("""{"at":1792056962118,"state":"CONNECTED","users":2,"shown":1}""")!!
        assertEquals(1792056962118L, h.lastFrameAt)
        assertEquals("CONNECTED", h.connection)
        assertEquals(2, h.users)
        assertEquals(-1L, CameraHealth.parse("""{"at":-1,"state":"NONE","users":0}""")!!.lastFrameAt)
        assertNull(CameraHealth.parse("null"))
        assertNull(CameraHealth.parse(null))
    }
}
