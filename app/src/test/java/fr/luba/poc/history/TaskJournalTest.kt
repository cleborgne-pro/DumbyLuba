package fr.luba.poc.history

import fr.luba.poc.map.RobotMap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Task journal: richer samples (format 2), compression at the end of a run, size bounds, typed events, export. */
class TaskJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun s(at: Long, x: Double, phase: TrackPhase = TrackPhase.CUTTING) =
        TrackSample(at, x, 0.0, 0.0, phase, 80, requestedLinear = 300, requestedAngular = -40, speedMps = 0.29,
            rtkStatus = 4, satellites = 31, link = TrackLinkState.GATEWAY)

    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
    private fun files() = File(folder.root, "task-tracks").listFiles().orEmpty().map { it.name.substringAfter('.') }.sorted()

    @Test fun samplesKeepCommandSpeedRtkAndLink() {
        val store = TaskTrackStore(folder.root)
        store.append("AA-1", listOf(s(1_000, 1.0), TrackSample(2_000, 2.0, 0.0, 0.0, TrackPhase.TRAVEL)))
        val (a, b) = store.read("AA-1")!!.samples
        assertEquals(300, a.requestedLinear); assertEquals(-40, a.requestedAngular)
        assertEquals(0.29, a.speedMps, 1e-6); assertEquals(4, a.rtkStatus); assertEquals(31, a.satellites)
        assertEquals(TrackLinkState.GATEWAY, a.link)
        // Unknown values stay unknown.
        assertNull(b.requestedLinear); assertNull(b.requestedAngular); assertTrue(b.speedMps.isNaN())
        assertEquals(-1, b.rtkStatus); assertEquals(-1, b.satellites); assertEquals(TrackLinkState.UNKNOWN, b.link)
    }

    @Test fun sealCompressesRunsAndResumeAppendsAfterThem() {
        val store = TaskTrackStore(folder.root)
        val first = (0 until 2_000).map { s(1_000L + it * 1_000, it * 0.1) }
        store.append("AA-1", first)
        val raw = store.bytes("AA-1")
        val before = store.read("AA-1")!!.samples
        store.seal("AA-1")
        assertEquals(listOf("trkz"), files())
        assertTrue("compressed ${store.bytes("AA-1")} vs raw $raw", store.bytes("AA-1") * 2 < raw)
        // Resume: new raw run, read after the sealed one, merged at the next seal.
        store.append("AA-1", listOf(s(5_000_000, 0.0)))
        assertEquals(listOf("trk", "trkz"), files())
        assertEquals(2_001, store.read("AA-1")!!.samples.size)
        store.seal("AA-1")
        assertEquals(listOf("trkz"), files())
        val samples = store.read("AA-1")!!.samples
        assertEquals(2_001, samples.size); assertEquals(5_000_000, samples.last().atMs)
        assertEquals(before, samples.dropLast(1))
    }

    @Test fun crashBetweenSealAndRawDeletionDoesNotDuplicate() {
        val store = TaskTrackStore(folder.root)
        store.append("AA-1", listOf(s(1_000, 1.0), s(2_000, 2.0)))
        val raw = File(folder.root, "task-tracks/${key("AA-1")}.trk")
        val copy = raw.readBytes()
        store.seal("AA-1")
        raw.writeBytes(copy) // raw file left behind
        assertEquals(listOf(1_000L, 2_000L), store.read("AA-1")!!.samples.map { it.atMs })
    }

    @Test fun formatOneTracksAreStillReadAppendedAndSealed() {
        val dir = File(folder.root, "task-tracks").apply { mkdirs() }
        DataOutputStream(File(dir, "${key("AA-1")}.trk").outputStream()).use { out ->
            out.writeInt(0x4C544B31); out.writeInt(1)
            listOf(1_000L to 1f, 2_000L to 2f).forEach { (at, x) ->
                out.writeLong(at); out.writeFloat(x); out.writeFloat(0f); out.writeFloat(90f); out.writeByte(0); out.writeByte(70)
            }
        }
        val store = TaskTrackStore(folder.root)
        assertEquals(listOf(1.0, 2.0), store.read("AA-1")!!.samples.map { it.x })
        // A run started in format 1 keeps its layout until it is sealed.
        store.append("AA-1", listOf(s(3_000, 3.0)))
        assertEquals(listOf(1.0, 2.0, 3.0), store.read("AA-1")!!.samples.map { it.x })
        assertNull(store.read("AA-1")!!.samples.last().requestedLinear)
        store.seal("AA-1")
        val sealed = store.read("AA-1")!!.samples
        assertEquals(listOf(1.0, 2.0, 3.0), sealed.map { it.x }); assertEquals(70, sealed.first().battery)
    }

    @Test fun rawTrackStopsGrowingAtItsLimit() {
        val store = TaskTrackStore(folder.root)
        var accepted = 0
        while (store.append("AA-1", (0 until 20_000).map { s(accepted * 20_000L + it, 0.0) })) accepted++
        assertTrue(accepted > 0)
        assertTrue(store.bytes("AA-1") < TaskTrackStore.MAX_RAW_BYTES + 1024 * 1024)
        assertEquals(accepted * 20_000, store.read("AA-1")!!.samples.size)
    }

    @Test fun pruneDropsOldestTracksBeyondTheDirectoryBudget() {
        val store = TaskTrackStore(folder.root)
        // Incompressible-sized raw tracks of about 11 MB each: 64 MB holds 5 of them.
        val batch = (0 until 350_000).map { s(it.toLong(), it.toDouble()) }
        val ids = (1..7).map { "AA-$it" }
        ids.forEach { store.append(it, batch) }
        store.prune(ids.reversed()) // most recent first
        val kept = ids.filter { store.read(it) != null }
        assertEquals(listOf("AA-3", "AA-4", "AA-5", "AA-6", "AA-7"), kept)
    }

    @Test fun eventsAreAppendedReadInOrderAndPruned() {
        val store = TaskTrackStore(folder.root)
        store.writeMeta("AA-1", TrackMeta(RobotMap()))
        store.appendEvents("AA-1", listOf(TaskEvent(2_000, TaskEventType.RECHARGE, "Recharge", 1.0, 2.0, 18, mapOf("phase" to "CHARGING"))))
        store.appendEvents("AA-1", listOf(TaskEvent(1_000, TaskEventType.START, "Tonte")))
        val track = store.read("AA-1")!!
        assertEquals(listOf(TaskEventType.START, TaskEventType.RECHARGE), track.events.map { it.type })
        val recharge = track.events[1]
        assertEquals(1.0, recharge.x, 1e-9); assertEquals(18, recharge.battery); assertEquals("CHARGING", recharge.data["phase"])
        assertTrue(track.events[0].x.isNaN())
        // Events alone are enough to keep a task.
        store.appendEvents("AA-2", listOf(TaskEvent(1_000, TaskEventType.STOP, "STOP")))
        assertEquals(1, store.read("AA-2")!!.events.size)
        store.prune(listOf("AA-1"))
        assertNull(store.read("AA-2"))
    }

    @Test fun classifierTypesTheLogEventsOfATask() {
        val c = TaskEventClassifier
        assertEquals(TaskEventType.RECHARGE, c.of("robot_route_phase", mapOf("phase" to "CHARGING")))
        assertEquals(TaskEventType.RETURN, c.of("robot_route_phase", mapOf("phase" to "RETURNING")))
        assertNull(c.of("robot_route_phase", mapOf("phase" to "FOLLOWING")))
        assertEquals(TaskEventType.CONTACT, c.of("route_contact", emptyMap()))
        assertEquals(TaskEventType.BLOCKED, c.of("difficulty", mapOf("kind" to "blocked")))
        assertNull(c.of("difficulty", mapOf("kind" to "deviation")))
        assertEquals(TaskEventType.LINK_LOST, c.of("gateway_outage", emptyMap()))
        assertEquals(TaskEventType.LINK_RESTORED, c.of("gateway_resumed", emptyMap()))
        assertEquals(TaskEventType.STOP, c.of("gateway_emergency_stop", emptyMap()))
        assertEquals(TaskEventType.LIFT, c.of("lift_detected", emptyMap()))
        assertEquals(TaskEventType.ERROR, c.of("robot_error", emptyMap()))
        assertNull(c.of("telemetry", emptyMap()))
        assertEquals(mapOf("code" to "1005"), c.data(mapOf("code" to 1005, "x" to 1.0, "stack" to "…", "reason" to null)))
    }

    @Test fun recorderKeepsPhaseChangesOnlyAndDropsRepeats() {
        val r = TaskEventRecorder("AA-1")
        fun phase(at: Long, p: String) = r.fromLog("robot_route_phase", "status $at", mapOf("phase" to p), at, 1.0, 2.0, 50)?.type
        assertNull(phase(0, "FOLLOWING"))
        assertEquals(TaskEventType.RETURN, phase(10_000, "RETURNING"))
        assertNull(phase(11_000, "RETURNING"))
        assertNull(phase(12_000, "DOCKING"))
        assertEquals(TaskEventType.RECHARGE, phase(13_000, "CHARGING"))
        assertNull(phase(60_000, "CHARGING"))                 // status refreshed while charging
        assertEquals(TaskEventType.RESUME, phase(900_000, "RESUMING"))
        val e = r.record(TaskEvent(1_000_000, TaskEventType.STOP, "STOP"))
        assertNotNull(e)
        assertNull(r.record(TaskEvent(1_001_000, TaskEventType.STOP, "STOP")))     // same STOP within 2 s
        assertNotNull(r.record(TaskEvent(1_004_000, TaskEventType.STOP, "STOP")))
        assertEquals(2.0, r.fromLog("route_contact", "Contact", emptyMap(), 2_000_000, 1.0, 2.0, 50)!!.y, 1e-9)
    }

    @Test fun recorderCapsEventsButKeepsTheEnd() {
        val r = TaskEventRecorder("AA-1")
        repeat(TaskEventRecorder.MAX_EVENTS) { assertNotNull(r.record(TaskEvent(it * 10_000L, TaskEventType.OBSTACLE, "o"))) }
        assertNull(r.record(TaskEvent(Long.MAX_VALUE / 2, TaskEventType.LINK_LOST, "lost")))
        assertNotNull(r.record(TaskEvent(Long.MAX_VALUE / 2 + 10_000, TaskEventType.END, "end")))
    }

    @Test fun exportWritesRecordsEventsAndPositions() {
        val f = MowingForecaster.forecast(100.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        val record = MowingRecord("D4:3D:39:AA:BB:CC-1729000000000", "D4:3D:39:AA:BB:CC", "Luba-VSABC1234", 1_729_000_000_000, forecast = f,
            outcome = MowingOutcome.COMPLETE)
        MowingHistoryStore(folder.root).save(listOf(record))
        val store = TaskTrackStore(folder.root)
        store.append(record.id, listOf(s(1_000, 1.0), TrackSample(2_000, 2.0, 0.5, Double.NaN, TrackPhase.CHARGING)))
        store.appendEvents(record.id, listOf(TaskEvent(1_500, TaskEventType.RECHARGE, "Recharge", battery = 19)))
        val entries = TaskExport.entries(folder.root).toList().toMap()
        assertEquals(3, entries.size)
        assertTrue(entries.containsKey("tasks/history.json"))
        val doc = JSONObject(entries.entries.single { it.key.endsWith("-mow.json") }.value)
        assertEquals("RECHARGE", doc.getJSONArray("events").getJSONObject(0).getString("type"))
        assertEquals(2, doc.getInt("positions"))
        val csv = entries.entries.single { it.key.endsWith(".csv") }.value.trim().lines()
        assertEquals(TaskExport.CSV_HEADER, csv[0])
        assertEquals("1000,1.000,0.000,0.0,CUTTING,80,300,-40,0.290,4,31,GATEWAY", csv[1])
        assertEquals("2000,2.000,0.500,,CHARGING,,,,,,,UNKNOWN", csv[2])
        assertTrue(TaskExport.entries(File(folder.root, "empty")).toList().isEmpty())
    }
}
