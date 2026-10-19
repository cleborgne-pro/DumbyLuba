package fr.luba.poc.i18n

import android.content.Context

/** Interface language chosen in the app, remembered between launches; [AppLanguage.SYSTEM] is the default. */
object LanguageStore {
    private const val FILE = "ui-language"
    private const val KEY = "language"

    /** Loads the stored choice into [I18n.chosen]; called when the process starts, before any text is built. */
    fun init(context: Context) {
        I18n.chosen = AppLanguage.fromName(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    }

    /** Stores and applies the choice; texts already displayed change when the screen is recreated. */
    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, language.name).apply()
        I18n.chosen = language
    }
}
