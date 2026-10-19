package fr.luba.poc.manual

import android.content.Context
import android.content.SharedPreferences

/** Maximum speed for manual control (joystick), kept from one opening and one launch to the next. */
object ManualSpeed {
    const val DEFAULT_PERCENT = 30
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 100
    private const val FILE = "manual_drive"
    private const val KEY = "max_speed_percent"

    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Saved value clamped to the slider range; the default when nothing is saved. */
    fun read(prefs: SharedPreferences): Int = sanitize(prefs.getInt(KEY, DEFAULT_PERCENT))

    fun save(prefs: SharedPreferences, percent: Int) {
        val value = sanitize(percent)
        if (prefs.getInt(KEY, Int.MIN_VALUE) != value) prefs.edit().putInt(KEY, value).apply()
    }

    fun sanitize(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}
