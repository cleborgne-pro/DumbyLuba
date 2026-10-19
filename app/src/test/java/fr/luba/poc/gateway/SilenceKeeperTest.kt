package fr.luba.poc.gateway

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Gateway phone kept silent: volumes at minimum, silent mode, "Do not disturb" taken then given back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilenceKeeperTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val reports = CopyOnWriteArrayList<String>()
    private lateinit var keeper: SilenceKeeper

    private fun role(role: GatewayRole) = GatewayConfig.save(context, GatewayConfig(role, code = "123456"))
    private fun loud() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Before fun setUp() {
        keeper = SilenceKeeper(context) { reports += it }
        loud()
    }

    @Test fun `gateway, sounds muted and Do not disturb enabled then given back when leaving the role`() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        role(GatewayRole.GATEWAY)
        keeper.evaluate()
        for (stream in SilencePolicy.STREAMS) assertEquals("stream $stream", audio.getStreamMinVolume(stream), audio.getStreamVolume(stream))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
        assertTrue(reports.single(), reports.single().startsWith("Son coupé : ") && reports.single().contains("« Ne pas déranger » activé"))
        // Already silent: nothing more to report.
        reports.clear(); keeper.evaluate()
        assertTrue(reports.isEmpty())
        // Role left: "Do not disturb" given back because the app had taken it; volumes left as they are.
        role(GatewayRole.CLIENT)
        loud()
        keeper.evaluate()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
        assertEquals(listOf("Maintien du silence arrêté : « Ne pas déranger » rendu"), reports)
        assertEquals(9, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun `Do not disturb chosen by the user never taken over by the app`() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        role(GatewayRole.OFF)
        keeper.evaluate()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
        assertTrue(reports.isEmpty())
    }

    @Test fun `without Do not disturb access, only volumes lowered`() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        role(GatewayRole.GATEWAY)
        keeper.evaluate()
        assertEquals(audio.getStreamMinVolume(AudioManager.STREAM_RING), audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertFalse(reports.single().contains("Ne pas déranger"))
    }

    @Test fun `keeping disabled or other role, nothing is touched`() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        role(GatewayRole.GATEWAY)
        SilenceKeeper.setKeep(context, false)
        keeper.evaluate()
        role(GatewayRole.CLIENT)
        SilenceKeeper.setKeep(context, true)
        keeper.evaluate()
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertTrue(reports.isEmpty())
    }

    @Test fun `volume raised by Android undone shortly after the system broadcast`() {
        role(GatewayRole.GATEWAY)
        keeper.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION").setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(12, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        // Periodic check (30 s) even without a broadcast.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
