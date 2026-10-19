package fr.luba.poc.autonomy

import android.content.Context

/** Pilot setting: hand routes over to the gateway when it can follow them (default), or follow them here frame by frame. */
object AutonomySettings {
    private const val PREFS = "autonomy"
    private const val KEY = "legs_on_gateway"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, enabled).apply()
    }
}
