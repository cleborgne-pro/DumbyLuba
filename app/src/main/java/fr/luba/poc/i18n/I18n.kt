package fr.luba.poc.i18n

import java.util.Locale

/**
 * Interface language: the choice made in Me → Language ([AppLanguage], stored by [LanguageStore]); with "System",
 * English by default and French when the device language is French.
 *
 * Every user-facing text is written in both languages next to each other with [tr], including messages built outside
 * the UI layer (planner refusals, tracking stops, gateway errors), which are plain Kotlin and tested on the JVM.
 * The language is read on each call, so a change (in-app choice or device language) applies when the screen is recreated.
 *
 * Tests: the `dumbyluba.lang` system property forces the language (`fr` for unit tests, `en` for documentation
 * screenshots, see `app/build.gradle.kts`); [override] forces it inside a single test.
 */
object I18n {
    /** Language forced by a test (`"en"` or `"fr"`); null: system property, then the user's choice, then device language. */
    @Volatile var override: String? = null

    /** Language chosen in the app; [AppLanguage.SYSTEM] follows the device language. */
    @Volatile var chosen: AppLanguage = AppLanguage.SYSTEM

    val language: String get() = override ?: System.getProperty("dumbyluba.lang")?.takeIf { it.isNotBlank() }
        ?: chosen.code ?: Locale.getDefault().language

    val french: Boolean get() = language == "fr"

    /** Locale for numbers and dates shown to the user: French formatting in French, British (decimal point) otherwise. */
    val locale: Locale get() = if (french) Locale.FRANCE else Locale.UK
}

/** User-facing text in the current language: [en] by default, [fr] on a French device. */
fun tr(en: String, fr: String): String = if (I18n.french) fr else en

/** Interface language offered in Me → Language. Language names are written in their own language. */
enum class AppLanguage(val code: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    FRENCH("fr");

    val label: String get() = when (this) {
        SYSTEM -> tr("System", "Système")
        ENGLISH -> "English"
        FRENCH -> "Français"
    }

    companion object {
        /** Stored name back to the choice; unknown or missing: [SYSTEM]. */
        fun fromName(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
