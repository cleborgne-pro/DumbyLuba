package fr.luba.poc.diagnostics

import fr.luba.poc.Clock
import java.io.File

/**
 * Size limits of the event log folders, applied by deleting the oldest JSONL parts rather than by stopping the log.
 *
 * A full log used to make [fr.luba.poc.EventLog] fail, which stopped the robot and refused every start until the app
 * was relaunched (Fold test of 2026-11-02: the 256 MB per launch were reached after 4 to 6 h of connection). The first
 * part of a launch is kept (connection, robot, versions); the gap shows in the part numbers and the event sequence.
 * Campaign reports (`.json`) and photos are never deleted here.
 */
object LogRetention {
    /**
     * Deletes the oldest parts of the launch folder [run], except its first and its last (being written), until the
     * parts total at most [maxBytes]. Returns the number of bytes freed.
     */
    fun trimRun(run: File, maxBytes: Long): Long {
        val parts = parts(run)
        var total = parts.sumOf { it.length() }
        var freed = 0L
        for (part in parts.drop(1).dropLast(1)) {
            if (total <= maxBytes) break
            val size = part.length()
            if (part.delete()) { total -= size; freed += size }
        }
        return freed
    }

    /**
     * Deletes the JSONL parts of the launches in [root] other than [current], oldest launch first, until they total at
     * most [maxBytes]. A launch written during the last [activeMs] (another log of the same process, see
     * [fr.luba.poc.EventLog]) is left alone. Returns the number of bytes freed.
     */
    fun pruneOtherRuns(root: File, current: File, maxBytes: Long, nowMs: Long = Clock.now(), activeMs: Long = ACTIVE_MS): Long {
        val runs = root.listFiles().orEmpty().filter { it.isDirectory && it != current }
            .map { it to lastWrite(it) }.filter { (_, at) -> nowMs - at > activeMs }.sortedBy { (_, at) -> at }
        val parts = runs.flatMap { (run, _) -> parts(run) }
        var total = parts.sumOf { it.length() }
        var freed = 0L
        for (part in parts) {
            if (total <= maxBytes) break
            val size = part.length()
            if (part.delete()) { total -= size; freed += size }
        }
        return freed
    }

    private const val ACTIVE_MS = 10 * 60_000L

    private fun lastWrite(run: File): Long = parts(run).maxOfOrNull { it.lastModified() } ?: run.lastModified()

    private fun parts(run: File): List<File> =
        run.listFiles().orEmpty().filter { it.isFile && it.extension == "jsonl" }.sortedBy { it.name }
}
