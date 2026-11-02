package fr.luba.poc

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import fr.luba.poc.diagnostics.LogRetention
import fr.luba.poc.diagnostics.LogSanitizer
import fr.luba.poc.history.TaskExport
import fr.luba.poc.i18n.tr
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Aggregated health of the current launch, read from the event log: what the Service screen shows without opening the
 * raw JSONL. Counts are per launch; [lastLinkLossAt] is wall-clock (0 if none), [averagePreparationMs] averages the
 * successful route preparations that recorded their duration. Audit of 2026-10-30.
 */
data class EventHealth(val events: Int = 0, val stops: Int = 0, val failures: Int = 0, val linkLosses: Int = 0,
    val lastLinkLossAt: Long = 0L, val averagePreparationMs: Long? = null)

/**
 * Structured log with rotation, disk/queue limits and persistent atomic reports. [channel] names the writer when
 * several logs run in the same launch (the Mammotion account screen has its own): its folders are prefixed with it and
 * the external copies keep the last launches of each channel.
 */
class EventLog(private val context: Context, private val channel: String = "", private val onError: (String) -> Unit) {
    private val failed = AtomicBoolean(false)
    val healthy: Boolean get() = !failed.get()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4096))
    private val directory = File(context.filesDir, "diagnostics")
    private val runId = UUID.randomUUID().toString()
    private val folderName = if (channel.isEmpty()) runId else "$channel-$runId"
    private val run = File(directory, folderName)
    private var part = 0
    private var sequence = 0L
    private var written = 0L
    // Writer thread only: last aggregate and the log size it was computed from (no re-parse while nothing changed).
    private var healthCache: Pair<Long, EventHealth>? = null
    // Copy readable with `adb pull` (app-specific external folder): test analysis without a manual export.
    private val mirror: File? = try { context.getExternalFilesDir("diagnostics")?.let { File(it, folderName) } } catch (_: Exception) { null }
    private var mirrorFailed = false
    // Writer thread only: open streams of the current part and its external copy.
    private var partStream: FileOutputStream? = null
    private var partBytes = 0L
    private var mirrorStream: FileOutputStream? = null
    private var spaceCheckedAt = -SPACE_CHECK_BYTES
    init {
        submit {
            directory.mkdirs(); run.mkdirs()
            try {
                mirror?.let { dir ->
                    // Keep the last 5 non-empty launches of this channel; the current folder is created on the first write.
                    dir.parentFile?.listFiles().orEmpty().filter { it.isDirectory && it != dir && channelOf(it.name) == channel }
                        .also { all -> all.filter { (it.listFiles()?.isEmpty()) != false }.forEach { it.deleteRecursively() } }
                        .filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(KEPT_MIRRORED_RUNS).forEach { it.deleteRecursively() }
                }
            } catch (_: Exception) { /* pruning old runs is best effort: the log keeps working */ }
            LogRetention.pruneOtherRuns(directory, run, OTHER_RUNS_MAX_BYTES)
            val legacy = File(directory, "legacy")
            context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("m1-") && it.extension == "jsonl" }.forEach {
                legacy.mkdirs(); check(it.renameTo(File(legacy, it.name))) { tr("Old log migration failed", "Migration ancien journal impossible") }
            }
            directory.walkTopDown().filter { it.name.startsWith("campaign-") && it.extension == "json" }.forEach { file ->
                val report = JSONObject(file.readText())
                if (report.optString("outcome") == "RUNNING" && report.optString("processRunId") != processRunId) {
                    report.put("outcome", "INTERRUPTED_PROCESS_END_UNCONFIRMED")
                    report.put("recoveredAt", Clock.now())
                    atomicWrite(file, report.toString())
                }
            }
        }
    }
    private fun submit(block: () -> Unit) {
        if (executor.isShutdown) return
        try { executor.execute { try { block() } catch (error: Exception) { fault(error) } } }
        catch (error: Exception) { fault(error) }
    }
    private fun fault(error: Exception) {
        if (failed.compareAndSet(false, true)) onError(tr("Log: ${error.javaClass.simpleName} ${error.message}", "Journal : ${error.javaClass.simpleName} ${error.message}"))
    }
    fun append(kind: String, detail: String, fields: Map<String, Any?> = emptyMap()) {
        val seq = ++sequence // called from the main thread
        val line = JSONObject().put("schemaVersion", 2).put("appRunId", runId).put("sequence", seq)
            .put("timestamp", Clock.now()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("event", kind).put("detail", detail).put("fields", json(fields)).toString() + "\n"
        submit {
            // Free space read every SPACE_CHECK_BYTES rather than at each line (one statfs per line).
            if (written - spaceCheckedAt >= SPACE_CHECK_BYTES) { spaceCheckedAt = written; ensureSpace() }
            if (partStream != null && partBytes > PART_MAX_BYTES) nextPart()
            val bytes = line.toByteArray(Charsets.UTF_8)
            // The part stays open between lines (unbuffered: each line reaches the system as before, without an open and
            // a close per line); a write failure closes it so the next line reopens it.
            val stream = partStream ?: File(run, "%04d.jsonl".format(part)).let { file ->
                FileOutputStream(file, true).also { partStream = it; partBytes = file.length() }
            }
            try {
                stream.write(bytes); written += bytes.size; partBytes += bytes.size
                if (kind in SYNCED_KINDS) stream.fd.sync()
            } catch (error: Exception) { closeStreams(); throw error }
            // The external copy must never make the main log fail.
            try {
                if (mirror != null && !mirrorFailed) {
                    val copy = mirrorStream ?: FileOutputStream(File(mirror.apply { mkdirs() }, "%04d.jsonl".format(part)), true).also { mirrorStream = it }
                    copy.write(bytes)
                }
            } catch (_: Exception) { mirrorFailed = true; runCatching { mirrorStream?.close() }; mirrorStream = null }
        }
    }
    /**
     * Starts a new part; the launch folder and its external copy keep their latest parts within their limits (a full
     * log must not stop the robot, see [LogRetention]).
     */
    private fun nextPart() {
        closeStreams(); part++
        LogRetention.trimRun(run, RUN_MAX_BYTES)
        if (mirror != null && !mirrorFailed) LogRetention.trimRun(mirror, MIRROR_MAX_BYTES)
    }
    // usableSpace on purpose: a cheap, conservative guard (cache that Android could clear is not counted as free).
    /** Frees the logs of the other launches, then the old parts of this one, before failing for lack of disk space. */
    @SuppressLint("UsableSpace")
    private fun ensureSpace() {
        if (context.filesDir.usableSpace > MIN_FREE_BYTES) return
        LogRetention.pruneOtherRuns(directory, run, 0L)
        LogRetention.trimRun(run, PART_MAX_BYTES)
        check(context.filesDir.usableSpace > MIN_FREE_BYTES) { tr("Not enough disk space for the log", "Espace disque insuffisant pour le journal") }
    }
    private fun closeStreams() {
        runCatching { partStream?.close() }; partStream = null
        runCatching { mirrorStream?.close() }; mirrorStream = null
    }
    fun report(id: String, fields: Map<String, Any?>) {
        val content = json(fields).put("appRunId", runId).put("processRunId", processRunId).put("updatedAt", Clock.now()).toString(2)
        submit {
            atomicWrite(File(run, "campaign-$id.json"), content)
            try { mirror?.let { it.mkdirs(); File(it, "campaign-$id.json").writeText(content) } } catch (_: Exception) { /* the mirror copy is optional */ }
        }
    }
    fun history(done: (String) -> Unit) = submit {
        val reports = directory.walkTopDown().filter { it.name.startsWith("campaign-") && it.extension == "json" }
            .sortedByDescending { it.lastModified() }.take(10).map {
                val r = JSONObject(it.readText()); "${r.optString("id")} · ${r.optString("outcome")} · ${r.optString("step")}" }
        done(reports.joinToString("\n").ifBlank { tr("No campaign recorded", "Aucune campagne enregistrée") })
    }
    /**
     * Health of this launch (see [EventHealth]): counts of stop requests and failures, last link loss and average route
     * preparation time. Parsed on the writer thread, cached while the log does not grow. Audit of 2026-10-30.
     */
    fun health(done: (EventHealth) -> Unit) = submit {
        val files = run.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
        val size = files.sumOf { it.length() }
        healthCache?.takeIf { it.first == size }?.let { return@submit done(it.second) }
        var events = 0; var stops = 0; var failures = 0; var linkLosses = 0; var lastLinkLossAt = 0L
        var preparationTotal = 0.0; var preparationCount = 0
        files.forEach { file ->
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                    val entry = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    events++
                    val kind = entry.optString("event")
                    val at = entry.optLong("timestamp")
                    when (kind) {
                        "stop_request" -> stops++
                        "failure" -> {
                            failures++
                            linkLosses++
                            if (at > lastLinkLossAt) lastLinkLossAt = at
                        }
                        in LINK_LOSS_KINDS -> { linkLosses++; if (at > lastLinkLossAt) lastLinkLossAt = at }
                        "simulation_preparation" -> {
                            val fields = entry.optJSONObject("fields") ?: return@forEach
                            if (fields.optString("result") != "READY") return@forEach
                            val elapsed = fields.optDouble("elapsedMs", -1.0)
                            if (elapsed >= 0) { preparationTotal += elapsed; preparationCount++ }
                        }
                    }
                } }
            }
        }
        val result = EventHealth(events, stops, failures, linkLosses, lastLinkLossAt,
            if (preparationCount == 0) null else (preparationTotal / preparationCount).toLong())
        healthCache = size to result
        done(result)
    }
    /**
     * Exports the current launch's log (JSONL) or the whole diagnostics folder (ZIP), with the task records, their
     * tracks and typed events ([fr.luba.poc.history.TaskExport]). [anonymize] (default) filters
     * every file with [LogSanitizer] and leaves the obstacle photos out, so the file can go to a public ticket as is;
     * the raw export is for the user's own analysis.
     */
    fun export(uri: Uri, archive: Boolean, anonymize: Boolean = true, done: (String) -> Unit) = submit {
        try {
            val sanitizer = if (anonymize) LogSanitizer() else null
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                if (archive) ZipOutputStream(output).use { zip ->
                    val files = directory.walkTopDown().filter { it.isFile && it.extension in listOf("json", "jsonl", "jpg") }.toList()
                    val photos = files.count { it.extension == "jpg" }
                    files.filter { sanitizer == null || it.extension != "jpg" }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                        copy(file, zip, sanitizer); zip.closeEntry()
                    }
                    // Task records, tracks and typed events (history), filtered like the reports.
                    TaskExport.entries(context.filesDir).forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        val text = if (sanitizer != null && name.endsWith(".json")) sanitizer.document(content) else content
                        zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                    if (sanitizer != null) {
                        zip.putNextEntry(ZipEntry("ANONYMIZED.json")); zip.write(sanitizer.report(photos).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                } else run.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.forEach { file -> copy(file, output, sanitizer) }
            }
            done(when {
                archive && sanitizer != null -> tr("Anonymized archive exported (reports + kept logs + tasks, without photos)", "Archive anonymisée exportée (rapports + journaux conservés + tâches, sans photos)")
                archive -> tr("Raw archive exported (reports + kept logs + tasks + photos), not anonymized", "Archive brute exportée (rapports + journaux conservés + tâches + photos), non anonymisée")
                sanitizer != null -> tr("Anonymized JSONL log exported", "Journal JSONL anonymisé exporté")
                else -> tr("JSONL log exported", "Journal JSONL exporté")
            })
        } catch (error: Exception) { done(tr("Export failed: ${error.javaClass.simpleName}", "Échec export : ${error.javaClass.simpleName}")) }
    }
    private fun copy(file: File, output: OutputStream, sanitizer: LogSanitizer?) {
        when {
            sanitizer == null || file.extension == "jpg" -> file.inputStream().use { it.copyTo(output) }
            file.extension == "jsonl" -> file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { output.write((sanitizer.line(it) + "\n").toByteArray(Charsets.UTF_8)) }
            }
            else -> output.write(sanitizer.document(file.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
        }
    }
    private fun atomicWrite(file: File, content: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(file)) { tr("Atomic report not saved", "Rapport atomique non enregistré") }
    }
    fun close() { submit { closeStreams() }; executor.shutdown() }
    companion object {
        private val processRunId = UUID.randomUUID().toString()
        // Per launch: the internal log, its external copy (`adb pull`), and the logs of the previous launches together.
        private const val RUN_MAX_BYTES = 256L * 1024 * 1024
        private const val MIRROR_MAX_BYTES = 96L * 1024 * 1024
        private const val OTHER_RUNS_MAX_BYTES = 256L * 1024 * 1024
        private const val KEPT_MIRRORED_RUNS = 5
        private const val UUID_LENGTH = 36
        /** Channel of a launch folder: what precedes its UUID (empty for the robot log). */
        internal fun channelOf(folder: String): String = if (folder.length > UUID_LENGTH) folder.dropLast(UUID_LENGTH + 1) else ""
        private const val MIN_FREE_BYTES = 32L * 1024 * 1024
        private const val PART_MAX_BYTES = 8L * 1024 * 1024
        private const val SPACE_CHECK_BYTES = 256L * 1024
        private val SYNCED_KINDS = setOf("failure", "campaign_result", "case_result", "stop_request")
        // "failure" is counted with the link losses in its own branch; the others only add a link loss.
        private val LINK_LOSS_KINDS = setOf("gateway_outage", "gateway_keepalive_lost", "nav_motion_starved")
        fun json(map: Map<String, Any?>): JSONObject = JSONObject().also { result ->
            map.forEach { (key, value) -> result.put(key, when (value) {
                null -> JSONObject.NULL
                is Map<*, *> -> json(value.entries.associate { it.key.toString() to it.value })
                is List<*> -> org.json.JSONArray(value.map { if (it is Map<*, *>) json(it.entries.associate { e -> e.key.toString() to e.value }) else it ?: JSONObject.NULL })
                else -> value
            }) }
        }
    }
}
