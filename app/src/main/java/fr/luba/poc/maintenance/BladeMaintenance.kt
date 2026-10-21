package fr.luba.poc.maintenance

import android.content.Context
import fr.luba.poc.auth.Maintenance
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Last blade change noted in the app: robot blade counter (`blade_used_time`, s) and date at that moment. */
data class BladeChange(val counterSeconds: Int, val at: Long)

enum class BladeLimitSource { LOCAL, ROBOT, NONE }

/**
 * Blade wear: [usedSeconds] since the last change noted here (or the robot's total counter), against [limitSeconds]:
 * the interval chosen in the app after a change, otherwise the threshold set in the Mammotion app
 * (`blade_used_warn_time`, 0 on the LUBA 2 of 2026-10-20), otherwise none.
 */
data class BladeStatus(val usedSeconds: Int, val limitSeconds: Int?, val source: BladeLimitSource, val changedAt: Long?) {
    val ratio: Double? get() = limitSeconds?.takeIf { it > 0 }?.let { usedSeconds.toDouble() / it }
    val due: Boolean get() = (ratio ?: 0.0) >= 1.0
    val usedHours: Double get() = usedSeconds / 3600.0
    val detail: String get() = limitSeconds?.let {
        tr("Blades used ${(usedSeconds / 3600.0).roundToInt()} h for a change every ${(it / 3600.0).roundToInt()} h",
            "Lames utilisées ${(usedSeconds / 3600.0).roundToInt()} h pour un changement toutes les ${(it / 3600.0).roundToInt()} h")
    } ?: tr("Blades used ${(usedSeconds / 3600.0).roundToInt()} h", "Lames utilisées ${(usedSeconds / 3600.0).roundToInt()} h")
}

object BladeWear {
    fun status(m: Maintenance, change: BladeChange?, intervalHours: Int): BladeStatus = when {
        // A counter lower than at the change: reset in the Mammotion app since then, it counts from that reset.
        change != null -> BladeStatus(if (m.bladeUsedSeconds >= change.counterSeconds) m.bladeUsedSeconds - change.counterSeconds else m.bladeUsedSeconds,
            intervalHours * 3600, BladeLimitSource.LOCAL, change.at)
        m.bladeWarnSeconds > 0 -> BladeStatus(m.bladeUsedSeconds, m.bladeWarnSeconds, BladeLimitSource.ROBOT, null)
        else -> BladeStatus(m.bladeUsedSeconds, null, BladeLimitSource.NONE, null)
    }
}

/**
 * Blade reminder settings, on the phone only (no command to the robot, its counter is never reset from here):
 * change interval in hours of blade rotation, and last change per robot (Bluetooth address).
 */
object BladeMaintenanceSettings {
    data class Values(val intervalHours: Int = DEFAULT_HOURS, val changes: Map<String, BladeChange> = emptyMap())

    const val DEFAULT_HOURS = 100
    val HOURS = 20..300
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow(Values())
    val flow: StateFlow<Values> get() = state

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("maintenance", Context.MODE_PRIVATE).also { p ->
            val changes = p.all.keys.filter { it.startsWith(COUNTER) }.mapNotNull { key ->
                val id = key.removePrefix(COUNTER)
                val at = p.getLong(AT + id, 0L)
                if (at > 0) id to BladeChange(p.getInt(key, 0), at) else null
            }.toMap()
            state.value = Values(p.getInt(INTERVAL, DEFAULT_HOURS).coerceIn(HOURS), changes)
        }
    }

    fun change(robotId: String?): BladeChange? = robotId?.let { state.value.changes[it] }
    val intervalHours: Int get() = state.value.intervalHours

    fun setInterval(hours: Int) {
        val clean = hours.coerceIn(HOURS)
        state.value = state.value.copy(intervalHours = clean)
        prefs?.edit()?.putInt(INTERVAL, clean)?.apply()
    }

    /** Blades replaced now: the robot counter read at this moment becomes the reference. */
    fun markChanged(robotId: String, counterSeconds: Int, now: Long) {
        state.value = state.value.copy(changes = state.value.changes + (robotId to BladeChange(counterSeconds, now)))
        prefs?.edit()?.putInt(COUNTER + robotId, counterSeconds)?.putLong(AT + robotId, now)?.apply()
    }

    private const val INTERVAL = "blade_interval_h"
    private const val COUNTER = "blade_counter_s:"
    private const val AT = "blade_changed_at:"
}
