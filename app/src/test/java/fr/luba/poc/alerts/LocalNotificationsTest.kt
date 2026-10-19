package fr.luba.poc.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android publication of local alerts (channels, repeat suppression, escalation, permission and muted levels) and
 * panel journal. Synchronous publishing thread and virtual clock: no real waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalNotificationsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val shadow get() = shadowOf(manager)
    private val scope = CoroutineScope(Job())
    private var now = 1_791_660_000_000L
    private fun notifications() = LocalNotifications(app, scope, Dispatchers.Unconfined) { now }
    private fun alert(kind: AlertKind = AlertKind.BLOCKED, severity: Severity = Severity.CRITICAL, robot: String = "r1", detail: String = "Translation nulle") =
        RobotAlert(kind, severity, now, AlertContext(robot, "Luba $robot", "trajet", 42, "1,0 ; 2,0"), detail)
    private fun child(robot: String = "r1", kind: AlertKind = AlertKind.BLOCKED): Notification? = shadow.getNotification("robot-alert:$robot:${kind.name}", 1)
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.body() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    private fun Notification.silent() = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    @After fun stop() = scope.cancel()

    @Test fun `three channels by increasing importance level`() {
        notifications()
        val expected = mapOf(Severity.INFO to NotificationManager.IMPORTANCE_LOW, Severity.WARNING to NotificationManager.IMPORTANCE_DEFAULT,
            Severity.CRITICAL to NotificationManager.IMPORTANCE_HIGH)
        for ((level, importance) in expected) {
            val channel = manager.getNotificationChannel(LocalNotifications.channelId(level))!!
            assertEquals(importance, channel.importance); assertEquals("Robot · ${level.name}", channel.name.toString())
        }
    }

    @Test fun `alert published with its context and group summary`() {
        val n = notifications()
        n.emit(alert())
        val shown = child()!!
        assertEquals(LocalNotifications.channelId(Severity.CRITICAL), shown.channelId)
        assertEquals("CRITICAL · Robot bloqué", shown.title())
        assertEquals("Luba r1 · Translation nulle", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(shown.body().contains("Batterie : 42 % (dernière mesure)")); assertTrue(shown.body().contains("Action : " + AlertKind.BLOCKED.action))
        assertFalse("first occurrence makes a sound", shown.silent())
        assertNotNull("robot summary", shadow.getNotification("robot-alert:r1", 0))
        val s = n.state.value
        assertTrue(s.enabled); assertTrue(s.permission); assertNull(s.problem)
        assertEquals(1, s.entries.size); assertEquals(1, s.unseen); assertEquals(AlertKind.BLOCKED, s.entries[0].kind)
    }

    @Test fun `repeat updated silently then audibly after 15 min`() {
        val n = notifications()
        n.emit(alert()); now += 60_000; n.emit(alert())
        assertEquals("a single notification per robot and type", 2, shadow.allNotifications.size)
        assertTrue(child()!!.silent()); assertTrue(child()!!.body().contains("2 événement(s)"))
        assertEquals("every occurrence stays in the panel", 2, n.state.value.entries.size)
        now += AlertDedup.COOLDOWN_MS
        n.emit(alert())
        assertFalse(child()!!.silent()); assertTrue(child()!!.body().contains("3 événement(s)"))
    }

    @Test fun `immediate escalation to the critical channel and severity kept`() {
        val n = notifications()
        n.emit(alert(AlertKind.BATTERY, Severity.WARNING))
        assertEquals(LocalNotifications.channelId(Severity.WARNING), child(kind = AlertKind.BATTERY)!!.channelId)
        now += 1_000; n.emit(alert(AlertKind.BATTERY, Severity.CRITICAL))
        val escalated = child(kind = AlertKind.BATTERY)!!
        assertEquals(LocalNotifications.channelId(Severity.CRITICAL), escalated.channelId); assertFalse(escalated.silent())
        // Falls back to "warning" within 15 min: the incident stays critical, no new sound.
        now += 1_000; n.emit(alert(AlertKind.BATTERY, Severity.WARNING))
        assertEquals(LocalNotifications.channelId(Severity.CRITICAL), child(kind = AlertKind.BATTERY)!!.channelId)
        assertTrue(child(kind = AlertKind.BATTERY)!!.silent())
    }

    @Test fun `robots and types kept separate`() {
        val n = notifications()
        n.emit(alert(robot = "r1")); n.emit(alert(robot = "r2")); n.emit(alert(AlertKind.LIFTED, robot = "r1"))
        assertNotNull(child("r1")); assertNotNull(child("r2")); assertNotNull(child("r1", AlertKind.LIFTED))
        assertFalse(child("r2")!!.silent()); assertFalse(child("r1", AlertKind.LIFTED)!!.silent())
        assertNotNull(shadow.getNotification("robot-alert:r2", 0))
    }

    @Test fun `permission denied, alert kept then published when permission returns`() {
        shadow.setNotificationsEnabled(false)
        val n = notifications()
        n.emit(alert()); n.emit(alert())
        assertTrue(shadow.allNotifications.isEmpty())
        val s = n.state.value
        assertFalse(s.enabled); assertFalse(s.permission); assertEquals("visible in the panel", 2, s.entries.size)
        // App restart while denied: the pending alert survives.
        val restarted = notifications()
        shadow.setNotificationsEnabled(true)
        restarted.refresh()
        val shown = child()!!
        assertTrue(shown.body().contains("2 événement(s)")); assertFalse(shown.silent())
        assertTrue(restarted.state.value.enabled)
        // Published only once.
        restarted.refresh(); assertEquals(2, shadow.allNotifications.size)
    }

    @Test fun `level muted in Android without blocking the others`() {
        notifications()
        manager.getNotificationChannel(LocalNotifications.channelId(Severity.WARNING))!!.importance = NotificationManager.IMPORTANCE_NONE
        val n = notifications()
        n.emit(alert(AlertKind.BUMPER, Severity.WARNING)); n.emit(alert(AlertKind.LIFTED, Severity.CRITICAL))
        assertNull(child(kind = AlertKind.BUMPER)); assertNotNull(child(kind = AlertKind.LIFTED))
        val s = n.state.value
        assertFalse(s.enabled); assertTrue(s.permission)
        assertEquals(mapOf(Severity.INFO to true, Severity.WARNING to false, Severity.CRITICAL to true), s.channels)
        assertEquals(2, s.entries.size)
        // Channel re-enabled: the pending alert goes out on return from settings.
        manager.getNotificationChannel(LocalNotifications.channelId(Severity.WARNING))!!.importance = NotificationManager.IMPORTANCE_DEFAULT
        n.refresh()
        assertNotNull(child(kind = AlertKind.BUMPER)); assertTrue(n.state.value.enabled)
    }

    @Test fun `seen, cleared and journal reloaded on restart`() {
        val n = notifications()
        n.emit(alert()); now += 1_000; n.emit(alert(AlertKind.LIFTED))
        assertEquals(2, n.state.value.unseen)
        n.markSeen()
        assertEquals(0, n.state.value.unseen); assertEquals(now, n.state.value.seenAt)
        now += 1_000; n.emit(alert(AlertKind.BUMPER, Severity.WARNING))
        val restarted = notifications().state.value
        assertEquals(listOf(AlertKind.BUMPER, AlertKind.LIFTED, AlertKind.BLOCKED), restarted.entries.map { it.kind })
        assertEquals(1, restarted.unseen)
        // Clearing the list keeps repeat suppression: the next repeat stays silent.
        n.clearHistory()
        assertTrue(n.state.value.entries.isEmpty()); assertTrue(notifications().state.value.entries.isEmpty())
        now += 1_000; n.emit(alert())
        assertTrue(child()!!.silent()); assertEquals(1, n.state.value.entries.size)
    }

    @Test fun `unreadable journal reported without blocking alerts`() {
        app.getSharedPreferences("robot_local_alerts_v1", Context.MODE_PRIVATE).edit().putString("journal", "{pas du json").commit()
        val n = notifications()
        assertEquals("Journal d’alertes illisible : JSONException", n.state.value.problem)
        n.emit(alert())
        assertNotNull(child()); assertEquals(1, n.state.value.entries.size)
    }

    @Test fun `attached photo shown large if the file exists`() {
        val n = notifications()
        val file = java.io.File(app.cacheDir, "obstacle.jpg")
        file.outputStream().use { android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        n.emit(alert(AlertKind.OBSTACLE, Severity.WARNING).copy(photo = file.path))
        assertEquals(Notification.BigPictureStyle::class.java.name, child(kind = AlertKind.OBSTACLE)!!.extras.getString(Notification.EXTRA_TEMPLATE))
        // Photo deleted meanwhile: detailed text instead.
        n.emit(alert(AlertKind.OBSTACLE, Severity.WARNING, robot = "r2").copy(photo = java.io.File(app.cacheDir, "absente.jpg").path))
        assertEquals(Notification.BigTextStyle::class.java.name, child("r2", AlertKind.OBSTACLE)!!.extras.getString(Notification.EXTRA_TEMPLATE))
    }
}
