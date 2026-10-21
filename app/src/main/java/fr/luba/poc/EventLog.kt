package fr.luba.poc

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import fr.luba.poc.diagnostics.LogSanitizer
import fr.luba.poc.i18n.tr
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Structured log with rotation, disk/queue limits and persistent atomic reports. */
class EventLog(private val context: Context, private val onError: (String) -> Unit) {
    private val failed = AtomicBoolean(false)
    val healthy: Boolean get() = !failed.get()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4096))
    private val directory = File(context.filesDir, "diagnostics")
    private val runId = UUID.randomUUID().toString()
    private val run = File(directory, runId)
    private var part = 0
    private var sequence = 0L
    private var written = 0L
    // Copy readable with `adb pull` (app-specific external folder): test analysis without a manual export.
    private val mirror: File? = try { context.getExternalFilesDir("diagnostics")?.let { File(it, runId) } } catch (_: Exception) { null }
    private var mirrored = 0L
    init {
        submit {
            directory.mkdirs(); run.mkdirs()
            try {
                mirror?.let { dir ->
                    // Keep the last 5 non-empty launches; the current folder is created on the first write.
                    dir.parentFile?.listFiles().orEmpty().filter { it.isDirectory && it != dir }
                        .also { all -> all.filter { (it.listFiles()?.isEmpty()) != false }.forEach { it.deleteRecursively() } }
                        .filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(5).forEach { it.deleteRecursively() }
                }
            } catch (_: Exception) { }
            val legacy = File(directory, "legacy")
            context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("m1-") && it.extension == "jsonl" }.forEach {
                legacy.mkdirs(); check(it.renameTo(File(legacy, it.name))) { tr("Old log migration failed", "Migration ancien journal impossible") }
            }
            directory.walkTopDown().filter { it.name.startsWith("campaign-") && it.extension == "json" }.forEach { file ->
                val report = JSONObject(file.readText())
                if (report.optString("outcome") == "RUNNING" && report.optString("processRunId") != processRunId) {
                    report.put("outcome", "INTERRUPTED_PROCESS_END_UNCONFIRMED")
                    report.put("recoveredAt", System.currentTimeMillis())
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
            .put("timestamp", System.currentTimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("event", kind).put("detail", detail).put("fields", json(fields)).toString() + "\n"
        submit {
            check(written < 256L * 1024 * 1024 && context.filesDir.usableSpace > 32L * 1024 * 1024) { tr("Log limit reached / not enough disk space", "Limite journal / espace disque insuffisant") }
            var file = File(run, "%04d.jsonl".format(part))
            if (file.length() > 8L * 1024 * 1024) { part++; file = File(run, "%04d.jsonl".format(part)) }
            val bytes = line.toByteArray(Charsets.UTF_8)
            FileOutputStream(file, true).use { stream ->
                stream.write(bytes); written += bytes.size
                if (kind in listOf("failure", "campaign_result", "case_result", "stop_request")) stream.fd.sync()
            }
            // The external copy must never make the main log fail.
            try {
                if (mirror != null && mirrored < MIRROR_MAX_BYTES) { mirror.mkdirs(); File(mirror, file.name).appendBytes(bytes); mirrored += bytes.size }
            } catch (_: Exception) { mirrored = MIRROR_MAX_BYTES }
        }
    }
    fun report(id: String, fields: Map<String, Any?>) {
        val content = json(fields).put("appRunId", runId).put("processRunId", processRunId).put("updatedAt", System.currentTimeMillis()).toString(2)
        submit {
            atomicWrite(File(run, "campaign-$id.json"), content)
            try { mirror?.let { it.mkdirs(); File(it, "campaign-$id.json").writeText(content) } } catch (_: Exception) { }
        }
    }
    fun history(done: (String) -> Unit) = submit {
        val reports = directory.walkTopDown().filter { it.name.startsWith("campaign-") && it.extension == "json" }
            .sortedByDescending { it.lastModified() }.take(10).map {
                val r = JSONObject(it.readText()); "${r.optString("id")} · ${r.optString("outcome")} · ${r.optString("step")}" }
        done(reports.joinToString("\n").ifBlank { tr("No campaign recorded", "Aucune campagne enregistrée") })
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
                    fr.luba.poc.history.TaskExport.entries(context.filesDir).forEach { (name, content) ->
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
    private fun copy(file: File, output: java.io.OutputStream, sanitizer: LogSanitizer?) {
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
    fun close() { executor.shutdown() }
    companion object {
        private val processRunId = UUID.randomUUID().toString()
        private const val MIRROR_MAX_BYTES = 96L * 1024 * 1024
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
