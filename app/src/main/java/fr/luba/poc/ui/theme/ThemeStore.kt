package fr.luba.poc.ui.theme

import android.content.Context

/** Theme choice remembered between launches; [ThemeMode.SYSTEM] is the default. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui-theme", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = prefs.getString("mode", null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
        }
}
