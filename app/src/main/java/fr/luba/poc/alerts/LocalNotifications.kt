package fr.luba.poc.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import fr.luba.poc.MainActivity
import fr.luba.poc.R
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State of the Notifications panel. [entries]: newest first; [seenAt]: time of the last notification seen (panel
 * opened); [channels]: level → Android channel enabled; [permission]: app notifications allowed.
 */
data class NotificationUi(val enabled: Boolean = false, val entries: List<AlertEntry> = emptyList(), val problem: String? = null,
                          val seenAt: Long = 0, val channels: Map<Severity, Boolean> = emptyMap(), val permission: Boolean = enabled) {
    /** Notifications received since the panel was last opened. */
    val unseen get() = entries.count { it.at > seenAt }
}

/**
 * Android notifications and persistent local journal. No network and no robot command.
 * [dispatcher] (publishing thread) and [clock] (anti-repeat) are only replaced by tests.
 */
class LocalNotifications(private val context: Context, scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO,
                         private val clock: () -> Long = System::currentTimeMillis) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("robot_local_alerts_v1", Context.MODE_PRIVATE)
    private val records = mutableMapOf<String, AlertRecord>()
    private val pending = linkedMapOf<String, RobotAlert>()
    private val entries = mutableListOf<AlertEntry>()
    private var seenAt = 0L
    private val mutable = MutableStateFlow(NotificationUi())
    val state = mutable.asStateFlow()
    private val work = Channel<() -> Unit>(Channel.UNLIMITED)
    init {
        Severity.entries.forEach {
            manager.createNotificationChannel(NotificationChannel(channel(it), "Robot · ${it.name}", when(it) {
                Severity.INFO -> NotificationManager.IMPORTANCE_LOW
                Severity.WARNING -> NotificationManager.IMPORTANCE_DEFAULT
                Severity.CRITICAL -> NotificationManager.IMPORTANCE_HIGH
            }).apply { description = tr("DumbyLuba local alerts · ${it.name}", "Alertes locales DumbyLuba · ${it.name}") })
        }
        runCatching {
            val saved = AlertJournal.decode(preferences.getString("journal", "{}")!!)
            entries.addAll(saved.entries)
            seenAt = saved.seenAt
            records.putAll(saved.records)
            pending.putAll(saved.pending)
        }.onFailure { mutable.value = mutable.value.copy(problem = tr("Unreadable alert journal: ${it.javaClass.simpleName}", "Journal d’alertes illisible : ${it.javaClass.simpleName}")) }
        scope.launch(dispatcher) {
            for(action in work) runCatching(action).onFailure { mutable.value = mutable.value.copy(problem=tr("Alert journal failure: ${it.javaClass.simpleName}", "Échec du journal d’alertes : ${it.javaClass.simpleName}")) }
        }
        refresh()
    }
    fun emit(alert: RobotAlert) { work.trySend { process(alert) } }
    private fun process(alert: RobotAlert) {
        val key = key(alert)
        val old = pending[key]
        val combined = alert.copy(count = (old?.count ?: 0) + alert.count,
            severity = if (old != null && old.severity > alert.severity) old.severity else alert.severity)
        pending[key] = combined
        entries.add(0, AlertEntry.of(combined))
        AlertJournal.latest(entries).let { entries.clear(); entries.addAll(it) }
        persist() // Written before publishing: a refused permission does not lose the incident.
        deliver(key, combined)
        refreshState()
    }
    fun refresh() { work.trySend { flush() } }
    /** Panel opened: the displayed notifications are seen, the bell badge goes off. */
    fun markSeen() { work.trySend {
        val latest = entries.maxOfOrNull { it.at } ?: return@trySend
        if (latest <= seenAt) return@trySend
        seenAt = latest; persist(); refreshState()
    } }
    /** Clears the panel list; anti-repeat records and alerts waiting for permission are kept. */
    fun clearHistory() { work.trySend { entries.clear(); persist(); refreshState() } }
    private fun flush() {
        pending.toMap().forEach { (key, alert) -> deliver(key, alert) }
        refreshState()
    }
    private fun deliver(key: String, alert: RobotAlert) {
        val previous = records[key]
        val decision = AlertDedup.decide(previous, alert.severity, clock())
        val level = decision.record.severity
        if (!enabled(level)) return
        val count = (previous?.count ?: 0) + alert.count
        val body = format(alert.copy(count = count, severity = level))
        val intent = Intent(context, MainActivity::class.java).putExtra("local_alerts", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val tag = "robot-alert:${alert.context.robotId}:${alert.kind.name}"
        val group = "robot-alert:${alert.context.robotId}"
        // Attached photo (obstacle): thumbnail in the collapsed notification, large image once expanded.
        val picture = alert.photo?.let(::loadPicture)
        val notification = Notification.Builder(context, channel(level))
            .setSmallIcon(R.drawable.ic_robot_alert)
            .setContentTitle("${level.name} · ${alert.kind.title}")
            .setContentText("${alert.context.robotName} · ${alert.detail}")
            .setStyle(if (picture != null) Notification.BigPictureStyle().bigPicture(picture).setSummaryText(alert.detail)
                else Notification.BigTextStyle().bigText(body))
            .apply { if (picture != null) setLargeIcon(picture) }
            .setWhen(alert.at).setShowWhen(true).setContentIntent(open).setAutoCancel(true)
            .setGroup(group)
            // OnlyAlertOnce is not enough after the user dismissed the notification.
            .setGroupAlertBehavior(if(decision.audible) Notification.GROUP_ALERT_CHILDREN else Notification.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(!decision.audible)
            .setCategory(Notification.CATEGORY_ERROR).build()
        runCatching {
            // Android keeps the channel of an existing notification: recreate it on escalation.
            if (previous != null && level != previous.severity) manager.cancel(tag, 1)
            manager.notify(tag, 1, notification)
            records[key] = decision.record.copy(count = count)
            pending.remove(key)
            persist()
            manager.notify(group, 0, Notification.Builder(context, channel(Severity.INFO))
                .setSmallIcon(R.drawable.ic_robot_alert).setContentTitle("DumbyLuba · ${alert.context.robotName}")
                .setContentText(tr("Robot alerts — open to view the log", "Alertes robot — ouvrir pour consulter le journal"))
                .setGroup(group).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true).setContentIntent(open).setAutoCancel(true).build())
        }.onFailure { mutable.value = mutable.value.copy(problem = tr("Android display failed: ${it.javaClass.simpleName}", "Affichage Android impossible : ${it.javaClass.simpleName}")) }
    }
    /** Attached image downscaled (at most [PICTURE_MAX_SIDE] px), null if the file no longer exists or is unreadable. */
    private fun loadPicture(path: String): android.graphics.Bitmap? = runCatching {
        val file = java.io.File(path)
        if (!file.isFile) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PICTURE_MAX_SIDE) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
    private fun enabled(level: Severity): Boolean = manager.areNotificationsEnabled() && manager.getNotificationChannel(channel(level))?.importance != NotificationManager.IMPORTANCE_NONE
    private fun refreshState() {
        val channels = Severity.entries.associateWith(::enabled)
        mutable.value = mutable.value.copy(enabled = channels.values.all { it }, entries = entries.toList(), seenAt = seenAt,
            channels = channels, permission = manager.areNotificationsEnabled())
    }
    private fun persist() {
        val json = AlertJournal(entries.toList(), records, pending, seenAt).encode()
        if (!preferences.edit().putString("journal", json).commit()) mutable.value = mutable.value.copy(problem = tr("Alert journal not saved", "Journal d’alertes non enregistré"))
    }
    private fun key(alert: RobotAlert) = AlertJournal.key(alert)
    private fun channel(level: Severity) = channelId(level)
    companion object {
        /** Android channel of a level, opened from the panel settings. */
        fun channelId(level: Severity) = "robot_local_${level.name.lowercase()}_v1"
        const val PICTURE_MAX_SIDE = 1024
        fun format(alert: RobotAlert): String {
            val time = SimpleDateFormat("dd/MM HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(Date(alert.at))
            // The French text keeps the layout of the pre-0.55 history, which AlertEntry.parseLegacy still reads.
            return tr("${alert.severity.name} · ${alert.kind.title} · ${alert.context.robotName}\n$time · ${alert.count} event(s)\nState: ${alert.context.state}\nBattery: ${alert.context.battery?.let { "$it % (last reading)" } ?: "unknown"}\nPosition: ${alert.context.position ?: "unknown"}\n${alert.detail}\nAction: ${alert.kind.action}",
                "${alert.severity.name} · ${alert.kind.title} · ${alert.context.robotName}\n$time · ${alert.count} événement(s)\nÉtat : ${alert.context.state}\nBatterie : ${alert.context.battery?.let { "$it % (dernière mesure)" } ?: "inconnue"}\nPosition : ${alert.context.position ?: "inconnue"}\n${alert.detail}\nAction : ${alert.kind.action}")
        }
    }
}
