package fr.luba.poc.mowing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import fr.luba.poc.i18n.tr

/**
 * DumbyLuba working hours, deliberately simple: a single window per day, from [startMinutes] to [endMinutes]
 * (minutes since midnight, end excluded), never crossing midnight. Outside it: no mowing starts, a mowing in progress
 * stops and the robot returns to base, a robot left outside is brought back ([fr.luba.poc.ble.RobotBle]).
 * The Mammotion app has its own working hours, enforced by the robot itself (self-check 23, [fr.luba.poc.protocol.SelfCheck]).
 */
data class WorkWindow(val enabled: Boolean = false, val startMinutes: Int = DEFAULT_START, val endMinutes: Int = DEFAULT_END) {
    /** Work allowed at [minuteOfDay] (0 to 1439): always when the working hours are disabled. */
    fun allows(minuteOfDay: Int): Boolean = !enabled || minuteOfDay in startMinutes until endMinutes

    fun allows(time: LocalTime): Boolean = allows(time.hour * 60 + time.minute)

    val label: String get() = "${format(startMinutes)} – ${format(endMinutes)}"

    /** Reason for refusing a start at [time], or null when work is allowed. */
    fun refusal(time: LocalTime): String? = if (allows(time)) null
        else tr("outside the working hours ($label), adjustable in Task settings", "hors de la plage horaire de travail ($label), réglable dans Paramètres de la tâche")

    /** Valid window: bounds within the day, at least [MIN_SPAN] minutes, 15-minute steps. */
    fun sanitized(): WorkWindow {
        val start = (startMinutes.coerceIn(0, DAY - MIN_SPAN) / STEP) * STEP
        val end = (endMinutes.coerceIn(start + MIN_SPAN, DAY) / STEP) * STEP
        return copy(startMinutes = start, endMinutes = end.coerceAtLeast(start + MIN_SPAN))
    }

    companion object {
        const val DAY = 24 * 60
        const val STEP = 15
        const val MIN_SPAN = 60
        const val DEFAULT_START = 9 * 60
        const val DEFAULT_END = 20 * 60

        fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}

/** Setting shared by the task screen and the robot driver, kept between launches. */
object WorkWindowSetting {
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow(WorkWindow())
    val flow: StateFlow<WorkWindow> get() = state
    val value: WorkWindow get() = state.value

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("work_window", Context.MODE_PRIVATE).also {
            state.value = WorkWindow(it.getBoolean("enabled", false), it.getInt("start", WorkWindow.DEFAULT_START),
                it.getInt("end", WorkWindow.DEFAULT_END)).sanitized()
        }
    }

    fun set(window: WorkWindow) {
        val clean = window.sanitized()
        state.value = clean
        prefs?.edit()?.putBoolean("enabled", clean.enabled)?.putInt("start", clean.startMinutes)?.putInt("end", clean.endMinutes)?.apply()
    }
}
