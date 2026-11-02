package fr.luba.poc.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bilingual rule (CONTRIBUTING.md): every text a user reads goes through `tr("English", "Français")`, or through a
 * label pair chosen with [I18n.french] for enums; texts that cross the gateway link stay in French and are translated
 * at display by `GatewayWireText`. A French literal assigned directly to a text shown on screen would be invisible in
 * English, which no screenshot test would catch.
 *
 * The check is textual on purpose: it runs on the JVM in milliseconds and fails with the file and line to fix.
 */
class BilingualTextGuardTest {
    private val sources = File("src/main/java")

    /** Show calls that take a plain literal instead of a `tr(…)` expression: `Text("Arrêt")`, `text = "Arrêt"`. */
    private val shownLiteral = Regex(
        """\b(?:Text|text|label|title|message|summary|detail|contentDescription|placeholder|hint|toast)\s*(?:=|\()\s*"((?:\\.|[^"\\])*)"""",
    )
    private val frenchOnly = Regex("[éèêëàâäùûüôöîïçœ]")
    private val trPair = Regex("""\btr\(\s*"((?:\\.|[^"\\])*)"\s*,\s*"((?:\\.|[^"\\])*)"""", RegexOption.DOT_MATCHES_ALL)

    @Test fun noFrenchLiteralIsShownDirectly() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val offenders = mutableListOf<String>()
        files().forEach { file ->
            stripComments(file.readText()).lineSequence().forEachIndexed { index, line ->
                shownLiteral.findAll(line).forEach { match ->
                    val literal = match.groupValues[1]
                    if (frenchOnly.containsMatchIn(literal)) offenders += "${file.path}:${index + 1} — $literal"
                }
            }
        }
        assertEquals("French text shown without tr(\"English\", \"Français\"):\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test fun englishArgumentCarriesNoFrenchAccent() {
        val offenders = mutableListOf<String>()
        files().forEach { file ->
            val text = stripComments(file.readText())
            trPair.findAll(text).forEach { match ->
                val english = match.groupValues[1]
                if (frenchOnly.containsMatchIn(english)) {
                    offenders += "${file.path}:${text.take(match.range.first).count { it == '\n' } + 1} — $english"
                }
            }
        }
        assertEquals("tr() called with a French text as the English argument:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    private fun files(): List<File> = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    /** Comments quote the rule itself ("a French literal shown here"): ignore them so that documentation is free. */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")
}
