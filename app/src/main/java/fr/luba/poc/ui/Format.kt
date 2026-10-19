package fr.luba.poc.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatting of numbers, durations and timestamps, following the interface language ([fr.luba.poc.i18n.I18n.locale]). */
object Format {
    /** Number with the decimal separator of the interface language (0.5 in English, 0,5 in French). */
    fun num(value: Double, decimals: Int = 1): String = String.format(fr.luba.poc.i18n.I18n.locale, "%.${decimals}f", value)

    /** Minimal plural: "1 zone" / "3 zones". */
    fun plural(count: Int, singular: String, plural: String): String =
        "$count ${if (if (fr.luba.poc.i18n.I18n.french) count <= 1 else count == 1) singular else plural}"

    /** Duration hh:mm:ss. */
    fun duration(ms: Long): String = "%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

    /** Short timestamp hh:mm:ss.SSS. */
    fun time(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    /** Short date and time: 16/10/2026 14:32. */
    fun dateTime(epochMs: Long): String =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
}
