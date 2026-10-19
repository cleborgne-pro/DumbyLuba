package fr.luba.poc.alerts

import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Notification shown in the panel: what Android displayed, at its time. Independent of Android for tests.
 * Unknown data stays null (displayed as "unknown").
 */
data class AlertEntry(val kind: AlertKind, val severity: Severity, val at: Long, val robotName: String, val detail: String, val count: Int = 1,
                      val state: String? = null, val battery: Int? = null, val position: String? = null) {
    /** One-line summary (Me → Notifications). */
    val headline get() = "${severity.label} · ${kind.title} · $robotName"
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("severity", severity.name).put("at", at).put("robotName", robotName)
        .put("detail", detail).put("count", count).put("state", state ?: JSONObject.NULL)
        .put("battery", battery ?: JSONObject.NULL).put("position", position ?: JSONObject.NULL)
    companion object {
        fun of(alert: RobotAlert) = AlertEntry(alert.kind, alert.severity, alert.at, alert.context.robotName, alert.detail, alert.count,
            alert.context.state, alert.context.battery, alert.context.position)
        fun fromJson(row: JSONObject) = AlertEntry(AlertKind.valueOf(row.getString("kind")), Severity.valueOf(row.getString("severity")),
            row.getLong("at"), row.getString("robotName"), row.getString("detail"), row.optInt("count", 1),
            if (row.isNull("state")) null else row.getString("state"),
            if (row.isNull("battery")) null else row.getInt("battery"),
            if (row.isNull("position")) null else row.getString("position"))

        /**
         * Line of the former text history (before version 0.55), always written in French: "LEVEL · title · robot",
         * "dd/MM HH:mm:ss · n événement(s)", state, battery, position, detail, action. The missing year is the most recent
         * one that does not put the alert in the future. Null if the line does not follow this format: it is ignored
         * rather than displayed wrongly. The French literals below are the stored format and must stay as they are.
         */
        fun parseLegacy(text: String, now: Long): AlertEntry? = runCatching {
            val lines = text.lines()
            val head = lines[0].split(" · ", limit = 3)
            val severity = Severity.valueOf(head[0])
            val kind = AlertKind.entries.first { it.titleFr == head[1] }
            val stamp = Regex("""^(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2}) · (\d+) événement""").find(lines[1])!!.groupValues
            val calendar = Calendar.getInstance(Locale.FRANCE).apply {
                timeInMillis = now
                set(get(Calendar.YEAR), stamp[2].toInt() - 1, stamp[1].toInt(), stamp[3].toInt(), stamp[4].toInt(), stamp[5].toInt())
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis > now) add(Calendar.YEAR, -1)
            }
            fun field(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            val state = field("État : ")
            val battery = field("Batterie : ")?.takeUnless { it == "inconnue" }?.substringBefore(' ')?.toIntOrNull()
            val position = field("Position : ")?.takeUnless { it == "inconnue" }
            val start = lines.indexOfFirst { it.startsWith("Position : ") } + 1
            val end = lines.indexOfLast { it.startsWith("Action : ") }.takeIf { it >= start } ?: lines.size
            AlertEntry(kind, severity, calendar.timeInMillis, head[2], lines.subList(start, end).joinToString("\n"), stamp[6].toInt(),
                state, battery, position)
        }.getOrNull()
    }
}

/** Short label of a level, as displayed in the app. */
val Severity.label get() = when (this) { Severity.INFO -> "Info"; Severity.WARNING -> tr("Warning", "Alerte"); Severity.CRITICAL -> tr("Critical", "Critique") }

/**
 * Android-independent format, shared by restoration and restart tests.
 * [entries]: the [MAX_ENTRIES] most recent notifications, newest first;
 * [seenAt]: time of the last notification seen in the panel (bell badge).
 */
data class AlertJournal(val entries: List<AlertEntry> = emptyList(), val records: Map<String, AlertRecord> = emptyMap(),
                        val pending: Map<String, RobotAlert> = emptyMap(), val seenAt: Long = 0) {
    fun encode(): String {
        val rows = JSONObject()
        records.forEach { (key, row) -> rows.put(key, JSONObject().put("severity", row.severity.name).put("at", row.soundedAt).put("count", row.count)) }
        return JSONObject().put("entries", JSONArray(latest(entries).map(AlertEntry::toJson))).put("seenAt", seenAt).put("records", rows)
            .put("pending", JSONArray(pending.values.map { alert -> JSONObject()
                .put("kind", alert.kind.name).put("severity", alert.severity.name).put("at", alert.at).put("detail", alert.detail).put("count", alert.count)
                .put("robotId", alert.context.robotId).put("robotName", alert.context.robotName).put("state", alert.context.state)
                .put("battery", alert.context.battery ?: JSONObject.NULL).put("position", alert.context.position ?: JSONObject.NULL).put("photo", alert.photo ?: JSONObject.NULL) })).toString()
    }
    companion object {
        const val MAX_ENTRIES = 50
        fun key(alert: RobotAlert) = "${alert.context.robotId}|${alert.kind.name}"
        /** Panel order: newest first, bounded; at equal times, arrival order is kept. */
        fun latest(entries: List<AlertEntry>) = entries.sortedByDescending { it.at }.take(MAX_ENTRIES)
        fun decode(text: String, now: Long = System.currentTimeMillis()): AlertJournal {
            val root = JSONObject(text)
            val entries = mutableListOf<AlertEntry>()
            val records = mutableMapOf<String, AlertRecord>()
            val pending = linkedMapOf<String, RobotAlert>()
            root.optJSONArray("entries")?.let { rows -> for(i in 0 until rows.length()) entries.add(AlertEntry.fromJson(rows.getJSONObject(i))) }
            // Older journal: free-text history, converted once then rewritten in the structured format.
            if (!root.has("entries")) root.optJSONArray("history")?.let { rows -> for(i in 0 until rows.length()) AlertEntry.parseLegacy(rows.getString(i), now)?.let(entries::add) }
            root.optJSONObject("records")?.let { rows -> rows.keys().forEach { key ->
                val row=rows.getJSONObject(key)
                records[key]=AlertRecord(Severity.valueOf(row.getString("severity")),row.getLong("at"),row.getInt("count"))
            } }
            root.optJSONArray("pending")?.let { rows -> for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                val alert=RobotAlert(AlertKind.valueOf(row.getString("kind")),Severity.valueOf(row.getString("severity")),row.getLong("at"),
                    AlertContext(row.getString("robotId"),row.getString("robotName"),row.getString("state"),if(row.isNull("battery")) null else row.getInt("battery"),if(row.isNull("position")) null else row.getString("position")),row.getString("detail"),row.getInt("count"),
                    if(row.isNull("photo")) null else row.getString("photo"))
                pending[key(alert)]=alert
            } }
            // Old journal without "seenAt": everything that existed is considered seen.
            val seenAt = if (root.has("seenAt")) root.getLong("seenAt") else entries.maxOfOrNull { it.at } ?: 0
            return AlertJournal(latest(entries),records,pending,seenAt)
        }
    }
}
