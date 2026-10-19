package fr.luba.poc.gateway

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class SilencePolicyTest {
    @Test fun loweredStreamsGoToTheirMinimumOnly() {
        val streams = listOf(SilencePolicy.Stream(AudioManager.STREAM_RING, 12, 0), SilencePolicy.Stream(AudioManager.STREAM_ALARM, 12, 1),
            SilencePolicy.Stream(AudioManager.STREAM_MUSIC, 0, 0), SilencePolicy.Stream(AudioManager.STREAM_VOICE_CALL, 1, 1))
        assertEquals(listOf(AudioManager.STREAM_RING to 0, AudioManager.STREAM_ALARM to 1), SilencePolicy.lowered(streams))
        assertTrue(SilencePolicy.lowered(listOf(SilencePolicy.Stream(AudioManager.STREAM_RING, 0, 0))).isEmpty())
    }

    @Test fun doNotDisturbIsImposedThenGivenBackOnlyIfTheAppSetIt() {
        assertEquals(true, SilencePolicy.dnd(active = true, granted = true, silentNow = false, ownedByApp = false))
        assertNull(SilencePolicy.dnd(active = true, granted = true, silentNow = true, ownedByApp = true))
        assertNull("no access, nothing", SilencePolicy.dnd(active = true, granted = false, silentNow = false, ownedByApp = false))
        assertEquals(false, SilencePolicy.dnd(active = false, granted = true, silentNow = true, ownedByApp = true))
        assertNull("\"Do not disturb\" set by the user: left alone", SilencePolicy.dnd(active = false, granted = true, silentNow = true, ownedByApp = false))
    }
}
