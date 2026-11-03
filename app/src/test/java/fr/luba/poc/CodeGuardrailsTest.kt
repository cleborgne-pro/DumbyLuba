package fr.luba.poc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules of [docs/CODE_GUIDELINES.md](../../../../../../../docs/CODE_GUIDELINES.md) checked on the app sources.
 *
 * Textual on purpose, like `BilingualTextGuardTest`: it runs in milliseconds and names the file and line to fix.
 * Rules with existing exceptions are ratchets: the count may go down, never up. When it goes down, lower the
 * baseline in the same change (the test says so), so that the gain cannot be lost again.
 */
class CodeGuardrailsTest {
    private val sources = File("src/main/java")

    private val files: List<File> by lazy {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isDirectory)
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    /** Code without comments: rules quoted in KDoc do not count. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { match -> "\n".repeat(match.value.count { it == '\n' }) }
        .replace(Regex("//[^\n]*"), "")

    private fun occurrences(pattern: Regex, keep: (File) -> Boolean = { true }): List<String> = files.filter(keep).flatMap { file ->
        code(file).lineSequence().withIndex().flatMap { (index, line) ->
            pattern.findAll(line).map { "${file.relativeTo(sources).path}:${index + 1}" }
        }.toList()
    }

    private fun ratchet(rule: String, found: List<String>, baseline: Int) {
        assertTrue("$rule: ${found.size} occurrences, baseline $baseline. Remove the new ones:\n" + found.joinToString("\n"), found.size <= baseline)
        assertEquals("$rule: ${found.size} occurrences, fewer than the baseline: lower it to ${found.size} in CodeGuardrailsTest", baseline, found.size)
    }

    @Test fun nonNullAssertionsOnlyGoDown() = ratchet("Non-null assertions (!!)", occurrences(Regex("!!")), NON_NULL_ASSERTIONS)

    @Test fun threadSleepOnlyGoesDown() = ratchet("Thread.sleep in the app", occurrences(Regex("""\bThread\.sleep\(""")), THREAD_SLEEPS)

    @Test fun noEmptyCatchBlock() {
        // Raw text: a comment inside the block explains why the failure is ignored, and makes it acceptable.
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> EMPTY_CATCH.containsMatchIn(line) }.map { (index, _) -> "${file.relativeTo(sources).path}:${index + 1}" }
        }
        assertEquals("Empty catch blocks: catch the narrowest exception, log it with event(…) on a safety path, or say why in a comment inside the block:\n" +
            found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun timeComesFromClock() {
        val found = occurrences(Regex("""\bSystem\.currentTimeMillis\(""")) { it.name != "Clock.kt" }
        assertEquals("Use Clock.now() / Clock.elapsed() instead of System.currentTimeMillis():\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun noGlobalScope() {
        val found = occurrences(Regex("""\bGlobalScope\b"""))
        assertEquals("GlobalScope outlives its owner: use viewModelScope or a scope owned by the class:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun testsRunOnTheCheckedVariant() {
        // The checks and deployments test the fold variant only: a test skipped outside debug never runs (63 Compose
        // tests from 2026-10-29 to 2026-10-31). Compose tests wrap their rule in withComposeTestActivity instead.
        val tests = File("src/test/java")
        val found = tests.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CodeGuardrailsTest.kt" }.flatMap { file ->
            code(file).lineSequence().withIndex().filter { (_, line) -> BUILD_TYPE_GATE.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(tests).path}:${index + 1}" }
        }.toList()
        assertEquals("Tests skipped by build type (only fold is tested): use withComposeTestActivity for Compose tests:\n" +
            found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun noInlineFullyQualifiedProjectNames() {
        val found = files.flatMap { file ->
            code(file).lineSequence().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("import ") && !line.trimStart().startsWith("package ") }
                .filter { (_, line) -> INLINE_NAME.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(sources).path}:${index + 1}" }.toList()
        }
        assertEquals("Import the class or function instead of writing fr.luba.poc.x.Y inline:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun inlineLibraryNamesOnlyGoDown() {
        val found = files.flatMap { file ->
            code(file).lineSequence().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("import ") && !line.trimStart().startsWith("package ") }
                .flatMap { (index, line) -> INLINE_LIBRARY_NAME.findAll(line).map { "${file.relativeTo(sources).path}:${index + 1}" } }.toList()
        }
        ratchet("Inline library names (kotlinx.coroutines.Dispatchers…), import them", found, INLINE_LIBRARY_NAMES)
    }

    @Test fun filesStayUnderTheirSize() {
        // Import lines do not count: replacing inline names by imports must not hit the ceilings.
        val sizes = files.associate { file -> file.relativeTo(sources).path to file.readLines().count { !it.startsWith("import ") } }
        val tooLong = sizes.filter { (path, lines) -> lines > (LARGE_FILES[path] ?: MAX_FILE_LINES) }
            .map { (path, lines) -> "$path: $lines lines, limit ${LARGE_FILES[path] ?: MAX_FILE_LINES}" }
        assertEquals("Files over their size limit: split by responsibility (docs/CODE_GUIDELINES.md, Size):\n" + tooLong.joinToString("\n"),
            emptyList<String>(), tooLong)
        val shrunk = LARGE_FILES.filter { (path, ceiling) -> (sizes[path] ?: 0) < ceiling }
            .map { (path, ceiling) -> "$path: ${sizes[path] ?: 0} lines, ceiling $ceiling" }
        assertEquals("These large files shrank: lower their ceiling in CodeGuardrailsTest.LARGE_FILES (remove the entry under " +
            "$MAX_FILE_LINES lines):\n" + shrunk.joinToString("\n"), emptyList<String>(), shrunk)
    }

    @Test fun functionsStayUnderTheirSize() {
        val lengths = files.flatMap { file ->
            functionLengths(code(file).lines()).map { (name, lines) -> "${file.relativeTo(sources).path}#$name" to lines }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, lines) -> lines.max() }
        val tooLong = lengths.filter { (key, lines) -> lines > (LONG_FUNCTIONS[key] ?: MAX_FUNCTION_LINES) }
            .map { (key, lines) -> "$key: $lines lines, limit ${LONG_FUNCTIONS[key] ?: MAX_FUNCTION_LINES}" }
        assertEquals("Functions over their size limit: split them (docs/CODE_GUIDELINES.md, Size):\n" + tooLong.joinToString("\n"),
            emptyList<String>(), tooLong)
        val shrunk = LONG_FUNCTIONS.filter { (key, ceiling) -> (lengths[key] ?: 0) < ceiling }
            .map { (key, ceiling) -> "$key: ${lengths[key] ?: 0} lines, ceiling $ceiling" }
        assertEquals("These long functions shrank: lower their ceiling in CodeGuardrailsTest.LONG_FUNCTIONS (remove the entry " +
            "under $MAX_FUNCTION_LINES lines):\n" + shrunk.joinToString("\n"), emptyList<String>(), shrunk)
    }

    /**
     * Name and length of each function: from its `fun` line to the last line indented deeper, or to its closing
     * bracket at the same indentation. Indentation rather than braces: braces inside strings do not count. The line
     * closing a parameter list written over several lines (`) {`, `): Int =`) is not the end: the body follows it.
     */
    private fun functionLengths(lines: List<String>): List<Pair<String, Int>> = lines.withIndex().mapNotNull { (start, line) ->
        val match = FUN_DECLARATION.find(line) ?: return@mapNotNull null
        val indent = match.groupValues[1].length
        var end = start
        for (next in start + 1 until lines.size) {
            val text = lines[next]
            if (text.isBlank()) continue
            if (text.length - text.trimStart().length <= indent) {
                val trimmed = text.trimStart()
                if (trimmed.first() in "})]") end = next
                if (trimmed.startsWith(")") && !trimmed.endsWith("}") && (trimmed.endsWith("{") || "=" in trimmed)) continue
                break
            }
            end = next
        }
        match.groupValues[2] to end - start + 1
    }

    private companion object {
        val EMPTY_CATCH = Regex("""catch\s*\([^)]*\)\s*\{\s*}""")
        /** A class (`kotlinx.coroutines.Dispatchers`) or a function call (`kotlin.math.abs(`) written with its package. */
        val INLINE_LIBRARY_NAME = Regex(
            """(?<![\w.`])(?:(?:kotlinx|androidx|android|java|javax)(?:\.[a-z][a-z0-9_]*)+\.[A-Z]|""" +
                """(?:kotlinx|androidx|android|java|javax|kotlin)(?:\.[a-z][a-z0-9_]*)*\.[a-z]\w*\s*\()""",
        )
        val BUILD_TYPE_GATE = Regex("""\bBUILD_TYPE\s*[!=]=""")
        /** A project class (`fr.luba.poc.map.RobotMap`) or function call (`fr.luba.poc.i18n.tr(`), sub-packages included. */
        val INLINE_NAME = Regex("""(?<![\w.])fr\.luba\.poc(?:\.[a-z]\w*)+\.(?:[A-Z]|[a-z]\w*\s*\()""")
        val FUN_DECLARATION = Regex(
            """^(\s*)(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|open|abstract|@\w+(?:\([^)]*\))?)\s+)*""" +
                """fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(`[^`]+`|\w+)""",
        )

        const val NON_NULL_ASSERTIONS = 0
        const val THREAD_SLEEPS = 7
        const val INLINE_LIBRARY_NAMES = 17

        /** Limit of a new file (lines, imports excluded). */
        const val MAX_FILE_LINES = 800

        /** Limit of a new function (lines). */
        const val MAX_FUNCTION_LINES = 150

        /** Functions already longer ("file#name", longest of that name in the file): their length is a ceiling. */
        val LONG_FUNCTIONS = mapOf(
            "fr/luba/poc/ble/RouteOrchestrator.kt#startRoute" to 167,
            "fr/luba/poc/map/CoveragePlanner.kt#plan" to 638,
            "fr/luba/poc/map/MapScreen.kt#MapScreenContent" to 543,
            "fr/luba/poc/map/MapCorrectionEditor.kt#MapCorrectionEditor" to 432,
            "fr/luba/poc/ble/NavigationRunner.kt#runMapNavTest" to 347,
            "fr/luba/poc/planning/AutomaticPlanner.kt#plan" to 264,
            "fr/luba/poc/autonomy/LegRunner.kt#followPath" to 241,
            "fr/luba/poc/map/BorderTypeEditor.kt#BorderTypeEditor" to 235,
            "fr/luba/poc/ble/RouteOrchestrator.kt#followPath" to 223,
            "fr/luba/poc/planning/LocalMowingOrder.kt#schedule" to 215,
        )

        /** Files already larger: their size is a ceiling, lowered as they shrink. */
        val LARGE_FILES = mapOf(
            "fr/luba/poc/ble/RobotBle.kt" to 1976,
            "fr/luba/poc/ble/RouteOrchestrator.kt" to 1920,
            "fr/luba/poc/simulation/Simulation.kt" to 879,
            "fr/luba/poc/ble/NavigationRunner.kt" to 917,
        )
    }
}
