package fr.luba.poc.history

import fr.luba.poc.i18n.tr
import org.json.JSONObject

/** Type of an event of the task journal. */
enum class TaskEventType(private val labelEn: String, private val labelFr: String, val incident: Boolean = false) {
    START("Start", "Départ"),
    RESUME("Resume", "Reprise"),
    RETURN("Return to base", "Retour à la base"),
    RECHARGE("Recharge", "Recharge"),
    OBSTACLE("Obstacle", "Obstacle", incident = true),
    CONTACT("Bumper contact", "Contact pare-chocs", incident = true),
    BYPASS("Obstacle bypassed", "Obstacle contourné"),
    BLOCKED("Blockage", "Blocage", incident = true),
    LIFT("Lift", "Soulèvement", incident = true),
    ERROR("Robot error", "Erreur robot", incident = true),
    STOP("STOP", "STOP", incident = true),
    LINK_LOST("Link lost", "Perte de liaison", incident = true),
    LINK_RESTORED("Link restored", "Liaison rétablie"),
    END("End", "Fin");

    val label: String get() = tr(labelEn, labelFr)

    companion object { fun of(name: String): TaskEventType? = entries.firstOrNull { it.name == name } }
}

/**
 * Timestamped event of a task (epoch ms): type, text as shown in the app log, robot position (map metres, NaN if
 * unknown), battery (-1 if unknown) and a few raw values ([data]: error code, phase, reason…).
 */
data class TaskEvent(val atMs: Long, val type: TaskEventType, val detail: String = "",
                     val x: Double = Double.NaN, val y: Double = Double.NaN, val battery: Int = -1,
                     val data: Map<String, String> = emptyMap())

/**
 * Turns the app log events ([fr.luba.poc.EventLog]) logged while a task runs into typed task events. The log keeps
 * every detail; the task journal keeps what a report, a coverage map or an incident box needs.
 */
object TaskEventClassifier {
    /** Kinds of difficulty ([fr.luba.poc.difficulty.DifficultyKind] codes) counted as a blockage. */
    private val BLOCKAGES = setOf("blocked", "no_progress", "slip")

    fun of(kind: String, fields: Map<String, Any?>): TaskEventType? = when (kind) {
        "robot_route_phase" -> when (fields["phase"]?.toString()) {
            "RETURNING" -> TaskEventType.RETURN
            "CHARGING" -> TaskEventType.RECHARGE
            "RESUMING" -> TaskEventType.RESUME
            else -> null
        }
        "route_contact" -> TaskEventType.CONTACT
        "route_obstacle_bypass" -> TaskEventType.BYPASS
        "difficulty" -> if (fields["kind"]?.toString() in BLOCKAGES) TaskEventType.BLOCKED else null
        "route_protection_pause" -> TaskEventType.BLOCKED
        "lift_detected" -> TaskEventType.LIFT
        "robot_error", "route_charge_incident" -> TaskEventType.ERROR
        "gateway_emergency_stop" -> TaskEventType.STOP
        "gateway_outage", "gateway_keepalive_lost", "nav_motion_starved" -> TaskEventType.LINK_LOST
        "gateway_resumed" -> TaskEventType.LINK_RESTORED
        "dock_resume_resumed" -> TaskEventType.RESUME
        "work_window_return" -> TaskEventType.RETURN
        else -> null
    }

    /** Raw values kept with the event: short scalars only (codes, phase, reason). */
    fun data(fields: Map<String, Any?>): Map<String, String> = fields.filterKeys { it in KEPT_FIELDS }
        .mapNotNull { (k, v) -> v?.toString()?.takeIf { it.length <= 160 }?.let { k to it } }.toMap()

    private val KEPT_FIELDS = setOf("phase", "code", "source", "reason", "kind", "leg", "attempt", "waitMs",
        "intentional", "outcome", "mode", "zones", "link", "progress")
}

/**
 * Journal of the running task: the typed events, without repeats (a route phase is recorded when it changes, the
 * same event within [REPEAT_MS] once) and at most [MAX_EVENTS] per run (the end is always kept).
 */
class TaskEventRecorder(val taskId: String) {
    private var lastPhase: String? = null
    private var last: TaskEvent? = null
    private var count = 0

    /**
     * Event to record for the app log event [kind], or null. [at] (epoch ms), [x], [y] and [battery] describe the robot
     * at that time.
     */
    fun fromLog(kind: String, text: String, fields: Map<String, Any?>, at: Long, x: Double?, y: Double?, battery: Int?): TaskEvent? {
        if (kind == "robot_route_phase") {
            val phase = fields["phase"]?.toString()
            val changed = phase != lastPhase
            lastPhase = phase
            if (!changed) return null
        }
        val type = TaskEventClassifier.of(kind, fields) ?: return null
        return record(TaskEvent(at, type, text, x ?: Double.NaN, y ?: Double.NaN, battery ?: -1, TaskEventClassifier.data(fields)))
    }

    /** Event signalled directly by the app (start, end, STOP, link lost, obstacle); null if repeated or over the cap. */
    fun record(event: TaskEvent): TaskEvent? {
        val previous = last
        if (previous != null && previous.type == event.type && previous.detail == event.detail && event.atMs - previous.atMs in 0..REPEAT_MS) return null
        if (count >= MAX_EVENTS && event.type != TaskEventType.END) return null
        last = event; count++
        return event
    }

    companion object {
        const val MAX_EVENTS = 1500
        const val REPEAT_MS = 2_000L
    }
}

object TaskEventJson {
    fun encode(e: TaskEvent): JSONObject = JSONObject().put("at", e.atMs).put("type", e.type.name).put("detail", e.detail)
        .apply {
            if (e.x.isFinite() && e.y.isFinite()) { put("x", e.x); put("y", e.y) }
            if (e.battery >= 0) put("battery", e.battery)
            if (e.data.isNotEmpty()) put("data", JSONObject(e.data as Map<*, *>))
        }

    fun decode(j: JSONObject): TaskEvent? {
        val type = TaskEventType.of(j.optString("type")) ?: return null
        val data = j.optJSONObject("data")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty()
        return TaskEvent(j.getLong("at"), type, j.optString("detail"), if (j.has("x")) j.getDouble("x") else Double.NaN,
            if (j.has("y")) j.getDouble("y") else Double.NaN, j.optInt("battery", -1), data)
    }
}
