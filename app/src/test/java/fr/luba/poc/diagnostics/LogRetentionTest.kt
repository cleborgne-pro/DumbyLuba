package fr.luba.poc.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRetentionTest {
    private val root: File = Files.createTempDirectory("log-retention").toFile()

    private fun run(name: String, sizes: List<Int>, modifiedAt: Long = 0L): File = File(root, name).apply {
        mkdirs()
        sizes.forEachIndexed { index, size -> File(this, "%04d.jsonl".format(index)).apply { writeBytes(ByteArray(size)); setLastModified(modifiedAt) } }
        File(this, "campaign-x.json").writeText("{}")
    }
    private fun names(run: File) = run.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun aFullLaunchKeepsItsFirstAndLatestPartsInsteadOfFailing() {
        val current = run("current", listOf(10, 10, 10, 10, 10))
        assertEquals(20L, LogRetention.trimRun(current, 30))
        assertEquals(listOf("0000.jsonl", "0003.jsonl", "0004.jsonl", "campaign-x.json"), names(current))
        // Never the first nor the part being written, even over the limit.
        LogRetention.trimRun(current, 0)
        assertEquals(listOf("0000.jsonl", "0004.jsonl", "campaign-x.json"), names(current))
    }

    @Test fun otherLaunchesLoseTheirLogsOldestFirstButNotTheirReportsNorAnActiveLog() {
        val now = 100_000_000L
        val oldest = run("a", listOf(10, 10), modifiedAt = now - 3_000_000)
        val older = run("b", listOf(10), modifiedAt = now - 2_000_000)
        val active = run("cloud-c", listOf(10), modifiedAt = now - 60_000)
        val current = run("current", listOf(50), modifiedAt = now)
        assertEquals(20L, LogRetention.pruneOtherRuns(root, current, 10, nowMs = now))
        assertEquals(listOf("campaign-x.json"), names(oldest))
        assertEquals(listOf("0000.jsonl", "campaign-x.json"), names(older))
        assertTrue(File(active, "0000.jsonl").exists())
        assertTrue(File(current, "0000.jsonl").exists())
    }
}
