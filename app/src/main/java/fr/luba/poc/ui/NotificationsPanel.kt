package fr.luba.poc.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.alerts.AlertEntry
import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.alerts.NotificationUi
import fr.luba.poc.alerts.Severity
import fr.luba.poc.alerts.label
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.LubaDivider
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Notifications" panel: the latest notifications, from newest to oldest and grouped by day, and a ⚙ button that
 * opens their settings (Android permission, levels, reception test, clearing).
 * [now]: clock for the "Today" / "Yesterday" labels, fixed by the screenshots.
 */
@Composable
fun NotificationsDialog(alerts: NotificationUi, onDismiss: () -> Unit, onTest: (Severity) -> Unit, onSettings: () -> Unit,
                        onChannelSettings: (Severity) -> Unit = {}, onClear: () -> Unit = {},
                        now: Long = System.currentTimeMillis(), startInSettings: Boolean = false) {
    var configuring by rememberSaveable { mutableStateOf(startInSettings) }
    // "Seen" time at opening: new items stay marked while browsing, even once acknowledged.
    val seenAtOpening = remember { alerts.seenAt }
    LubaDialog(onDismissRequest = onDismiss, title = if (configuring) tr("Settings", "Réglages") else "Notifications",
        navigation = if (configuring) ({
            IconButton(onClick = { configuring = false }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back to notifications", "Retour aux notifications"), tint = MammotionColors.TextSecondary)
            }
        }) else null,
        actions = {
            if (!configuring) IconButton(onClick = { configuring = true }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = tr("Configure notifications", "Configurer les notifications"), tint = MammotionColors.TextSecondary)
            }
        },
        content = {
            if (configuring) NotificationSettings(alerts, onTest, onSettings, onChannelSettings, onClear)
            else NotificationFeed(alerts, seenAtOpening, now, onSettings)
        })
}

@Composable
private fun NotificationFeed(alerts: NotificationUi, seenAt: Long, now: Long, onSettings: () -> Unit) {
    if (!alerts.enabled) {
        StatusNote(if (alerts.permission) tr("Some alert levels are turned off in Android", "Certains niveaux d'alerte sont coupés dans Android") else tr("Notifications blocked: alerts stay visible here", "Notifications bloquées : les alertes restent visibles ici"),
            NoteTone.WARNING)
        if (!alerts.permission) SecondaryPillButton(tr("Allow notifications", "Autoriser les notifications"), onSettings, Modifier.fillMaxWidth())
    }
    alerts.problem?.let { StatusNote(it, NoteTone.DANGER) }
    if (alerts.entries.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = Dimens.Lg * 2), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = MammotionColors.TextTertiary, modifier = Modifier.size(36.dp))
            Text(tr("No notifications", "Aucune notification"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Robot alerts will appear here, most recent at the top.", "Les alertes du robot apparaîtront ici, la plus récente en haut."), style = MaterialTheme.typography.bodySmall,
                color = MammotionColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    // The journal is already sorted; sorting here protects the displayed order from any late entry.
    NotificationDays.group(alerts.entries.sortedByDescending { it.at }, now).forEach { (day, rows) ->
        SettingsGroup(day) {
            rows.forEachIndexed { index, entry ->
                if (index > 0) LubaDivider()
                val key = entry.at * 31 + entry.kind.ordinal
                NotificationRow(entry, fresh = entry.at > seenAt, expanded = expanded == key,
                    onClick = { expanded = if (expanded == key) null else key })
            }
        }
    }
    InfoNote(tr("The last ${fr.luba.poc.alerts.AlertJournal.MAX_ENTRIES} notifications are kept on this phone. Tap a notification for its details.", "Les ${fr.luba.poc.alerts.AlertJournal.MAX_ENTRIES} dernières notifications sont conservées sur ce téléphone. Touchez une notification pour son détail."))
}

/** Notification row: level badge, title, time, robot and reason; expanded: state, battery, position and action. */
@Composable
private fun NotificationRow(entry: AlertEntry, fresh: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val colors = MammotionColors
    val (icon, tint, background) = severityStyle(entry)
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
        .animateContentSize().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).background(background, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = entry.severity.label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.kind.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (entry.count > 1) Surface(shape = CircleShape, color = colors.SurfaceMuted) {
                        Text("×${entry.count}", style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    if (fresh) Box(Modifier.size(8.dp).background(colors.Danger, CircleShape).semantics { contentDescription = tr("New", "Nouvelle") })
                }
                Text(NotificationDays.time(entry.at), style = MaterialTheme.typography.labelMedium, color = colors.TextTertiary)
            }
            Text("${entry.robotName} · ${entry.detail}", style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            if (expanded) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Detail(tr("Level", "Niveau"), entry.severity.label)
                Detail(tr("State", "État"), entry.state ?: tr("unknown", "inconnu"))
                Detail(tr("Battery", "Batterie"), entry.battery?.let { tr("$it % (last reading)", "$it % (dernière mesure)") } ?: tr("unknown", "inconnue"))
                Detail("Position", entry.position ?: tr("unknown", "inconnue"))
                Text(entry.kind.action, style = MaterialTheme.typography.bodySmall, color = colors.TextPrimary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextTertiary, modifier = Modifier.width(68.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    }
}

@Composable
private fun NotificationSettings(alerts: NotificationUi, onTest: (Severity) -> Unit, onSettings: () -> Unit,
                                 onChannelSettings: (Severity) -> Unit, onClear: () -> Unit) {
    SettingsGroup(tr("Permission", "Autorisation")) {
        StatusNote(when {
            alerts.enabled -> tr("Local alerts enabled", "Alertes locales activées")
            alerts.permission -> tr("Notifications allowed, some levels turned off", "Notifications autorisées, certains niveaux coupés")
            else -> tr("Notifications blocked", "Notifications bloquées")
        }, if (alerts.enabled) NoteTone.SUCCESS else NoteTone.WARNING)
        alerts.problem?.let { StatusNote(it, NoteTone.DANGER) }
        SecondaryPillButton(if (alerts.permission) tr("Android settings", "Réglages Android") else tr("Allow notifications", "Autoriser les notifications"), onSettings, Modifier.fillMaxWidth())
    }
    SettingsGroup(tr("Alert levels", "Niveaux d'alerte")) {
        Severity.entries.reversed().forEach { level ->
            NavRow(level.label, icon = severityStyle(level).first, subtitle = channelHint(level), enabled = alerts.permission,
                value = when (alerts.channels[level]) { true -> tr("On", "Activé"); false -> tr("Off", "Coupé"); null -> null },
                onClick = { onChannelSettings(level) })
        }
    }
    SettingsGroup(tr("Test reception (no movement)", "Tester la réception (sans mouvement)")) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            Severity.entries.forEach { level -> TestPill(level.label, { onTest(level) }, Modifier.weight(1f)) }
        }
    }
    InfoNote(tr("A foreground service keeps the Bluetooth link in the background: automatic missions go on, " +
        "the joystick and hold-to-run tests stop. The notification's STOP stops the robot; removing the app from recent tasks disconnects it.",
        "Un service de premier plan garde la liaison Bluetooth en arrière-plan : les missions automatiques continuent, " +
        "le joystick et les essais à maintien s'arrêtent. Le STOP de la notification arrête le robot ; retirer l'application des tâches récentes le déconnecte."))
    TextAction(tr("Clear the notification list", "Effacer la liste des notifications"), onClear, Modifier.fillMaxWidth(), enabled = alerts.entries.isNotEmpty(), danger = true)
}

private fun channelHint(level: Severity) = when (level) {
    Severity.CRITICAL -> tr("Robot stuck, lifted, link lost · sound and banner", "Robot bloqué, soulevé, liaison perdue · son et bannière")
    Severity.WARNING -> tr("Battery, obstacle, rain · sound", "Batterie, obstacle, pluie · son")
    Severity.INFO -> tr("Mowing finished, link restored · silent", "Tonte terminée, liaison rétablie · silencieuse")
}

@Composable
private fun severityStyle(entry: AlertEntry) =
    if (entry.kind == AlertKind.MOWING_REPORT) Triple(Icons.Rounded.TaskAlt, MammotionColors.GreenDeep, MammotionColors.GreenSoft)
    else severityStyle(entry.severity)

@Composable
private fun severityStyle(level: Severity): Triple<ImageVector, Color, Color> = when (level) {
    Severity.CRITICAL -> Triple(Icons.Rounded.ErrorOutline, MammotionColors.Danger, MammotionColors.DangerSoft)
    Severity.WARNING -> Triple(Icons.Rounded.WarningAmber, MammotionColors.Warning, MammotionColors.WarningSoft)
    Severity.INFO -> Triple(Icons.Rounded.Info, MammotionColors.Info, MammotionColors.InfoSoft)
}

/** Compact secondary pill: three short actions fit on one modal row. */
@Composable
private fun TestPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 44.dp), shape = CircleShape,
        color = MammotionColors.SurfaceMuted, contentColor = MammotionColors.TextPrimary,
        border = BorderStroke(1.dp, MammotionColors.Outline)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

/** Day grouping and time labels of the panel, without Compose for the tests. */
object NotificationDays {
    fun time(at: Long): String = SimpleDateFormat("HH:mm", fr.luba.poc.i18n.I18n.locale).format(Date(at))
    /** "Today", "Yesterday", otherwise "Monday 13 October" (year added outside the current year), in the UI language. */
    fun label(at: Long, now: Long): String {
        val day = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = at }
        val today = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = now }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(day, today)) return tr("Today", "Aujourd'hui")
        if (same(day, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return tr("Yesterday", "Hier")
        val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEEE d MMMM" else "EEEE d MMMM yyyy"
        return SimpleDateFormat(pattern, if (I18n.french) Locale.FRANCE else Locale.UK).format(Date(at))
    }
    /** Consecutive groups per day, in the received order (most recent first). */
    fun group(entries: List<AlertEntry>, now: Long): List<Pair<String, List<AlertEntry>>> {
        val groups = mutableListOf<Pair<String, MutableList<AlertEntry>>>()
        entries.forEach { entry ->
            val label = label(entry.at, now)
            if (groups.lastOrNull()?.first == label) groups.last().second.add(entry) else groups.add(label to mutableListOf(entry))
        }
        return groups
    }
}
