package fr.luba.poc

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventLogExportTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun export(log: EventLog, archive: Boolean, anonymize: Boolean): Pair<File, String> {
        val target = File(context.cacheDir, "export-${System.nanoTime()}")
        val latch = CountDownLatch(1); var result = ""
        log.export(Uri.fromFile(target), archive, anonymize) { result = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return target to result
    }
    private fun entries(zip: File): Map<String, String> = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.associate { it.name to input.readBytes().toString(Charsets.UTF_8) }
    }

    @Test fun anonymizedArchiveFiltersLogsReportsAndLeavesPhotosOut() {
        val photos = File(context.filesDir, "diagnostics/obstacle-photos").apply { mkdirs() }
        File(photos, "1_2_x_100_200.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val log = EventLog(context) { }
        log.append("connect", "Connecting Luba-VSABC1234 D4:3D:39:AA:BB:CC", mapOf("address" to "D4:3D:39:AA:BB:CC"))
        log.append("protobuf_decoded", "sys", mapOf("decoded" to "rtk {\n  mqtt_rtk_info {\n    latitude: 48.8566\n    rtk_base_num: \"RTKNB01\"\n  }\n}\n"))
        log.report("probe", mapOf("robot" to "Luba-VSABC1234", "outcome" to "PASSED"))

        val (zip, message) = export(log, archive = true, anonymize = true)
        val files = entries(zip)
        assertTrue(message, message.contains("anonymis") || message.contains("Anonymized"))
        assertFalse(files.keys.toString(), files.keys.any { it.endsWith(".jpg") })
        assertTrue(files.containsKey("ANONYMIZED.json"))
        val all = files.values.joinToString("\n")
        listOf("Luba-VSABC1234", "D4:3D:39", "48.8566", "RTKNB01").forEach { assertFalse(it, all.contains(it)) }
        assertTrue(all.contains("Luba-VS‹1›")); assertTrue(all.contains("PASSED"))

        val (raw, _) = export(log, archive = true, anonymize = false)
        val rawFiles = entries(raw)
        assertTrue(rawFiles.keys.any { it.endsWith(".jpg") })
        assertTrue(rawFiles.values.joinToString().contains("Luba-VSABC1234"))

        val (jsonl, _) = export(log, archive = false, anonymize = true)
        val lines = jsonl.readLines()
        assertEquals(2, lines.size); assertFalse(jsonl.readText().contains("D4:3D:39"))
        log.close()
    }

    @Test fun archiveIncludesTaskRecordsTracksAndEventsAnonymized() {
        val f = fr.luba.poc.history.MowingForecaster.forecast(100.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        val record = fr.luba.poc.history.MowingRecord("D4:3D:39:AA:BB:CC-1729000000000", "D4:3D:39:AA:BB:CC", "Luba-VSABC1234",
            1_729_000_000_000, forecast = f)
        fr.luba.poc.history.MowingHistoryStore(context.filesDir).save(listOf(record))
        val store = fr.luba.poc.history.TaskTrackStore(context.filesDir)
        store.append(record.id, listOf(fr.luba.poc.history.TrackSample(1_000, 1.0, 2.0, 0.0, fr.luba.poc.history.TrackPhase.CUTTING, 80)))
        store.appendEvents(record.id, listOf(fr.luba.poc.history.TaskEvent(1_500, fr.luba.poc.history.TaskEventType.LINK_LOST,
            "Perte BLE : Luba-VSABC1234 D4:3D:39:AA:BB:CC")))
        val log = EventLog(context) { }
        val (zip, message) = export(log, archive = true, anonymize = true)
        val files = entries(zip)
        assertTrue(message, message.contains("tâches") || message.contains("tasks"))
        assertTrue(files.keys.toString(), files.containsKey("tasks/history.json"))
        assertTrue(files.keys.toString(), files.keys.any { it.startsWith("tasks/") && it.endsWith("-mow.csv") })
        val tasks = files.filterKeys { it.startsWith("tasks/") }.values.joinToString("\n")
        assertTrue(tasks.contains("LINK_LOST")); assertTrue(tasks.contains("CUTTING"))
        listOf("Luba-VSABC1234", "D4:3D:39").forEach { assertFalse(it, tasks.contains(it)) }
        val (raw, _) = export(log, archive = true, anonymize = false)
        assertTrue(entries(raw).values.joinToString().contains("D4:3D:39:AA:BB:CC"))
        log.close()
    }

    /** The part stays open between lines: every line in order, a new part past 8 MB, the same lines in the external copy. */
    @Test fun linesKeepTheirOrderAcrossAPartRotationAndInTheExternalCopy() {
        val log = EventLog(context) { error -> throw AssertionError(error) }
        val padding = "x".repeat(64 * 1024)
        repeat(140) { log.append("probe", "line $it $padding") }
        val written = CountDownLatch(1)
        log.history { written.countDown() }   // same writer thread: runs after every line
        assertTrue(written.await(30, TimeUnit.SECONDS))
        log.close()
        fun lines(root: File?): List<String> = root?.walkTopDown().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
            .flatMap { it.readLines() }.map { org.json.JSONObject(it).getString("detail").substringBefore(" x") }.toList()
        val parts = File(context.filesDir, "diagnostics").walkTopDown().filter { it.extension == "jsonl" }.map { it.name }.toSet()
        assertEquals(setOf("0000.jsonl", "0001.jsonl"), parts)
        assertEquals(List(140) { "line $it" }, lines(File(context.filesDir, "diagnostics")))
        assertEquals(List(140) { "line $it" }, lines(context.getExternalFilesDir("diagnostics")))
    }
}
