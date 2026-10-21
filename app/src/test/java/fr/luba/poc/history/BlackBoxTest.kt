package fr.luba.poc.history

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Black box of the incidents: sliding memory, freeze window, grouping, storage and export. */
class BlackBoxTest {
    @get:Rule val folder = TemporaryFolder()

    private fun frame(at: Long, x: Double = at / 1000.0) = BlackBoxFrame(at, x, 0.0, 90.0, 300, -40, 0.3, 60, 13, 0, 4, 30, TrackLinkState.BLUETOOTH, "FOLLOWING", -70, null)

    @Test fun memoryKeepsTheLastMinuteAtTheFrameRate() {
        val r = BlackBoxRecorder()
        (0L..120_000L step 200).forEach { r.frame(frame(it)) }
        val box = r.freeze(120_000, listOf(TaskEventType.CONTACT), "Contact")
        // 45 s before the incident, one frame every 400 ms at most.
        assertTrue(box.frames.first().atMs >= 120_000 - BlackBoxRecorder.BEFORE_MS)
        assertTrue(box.frames.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= BlackBoxRecorder.FRAME_MS })
        assertEquals(113, box.frames.size)
        // Nothing older than a minute is kept.
        assertTrue(r.freeze(0, emptyList(), "").frames.isEmpty())
    }

    @Test fun freezeKeepsTheWindowAroundTheIncident() {
        val r = BlackBoxRecorder()
        listOf(10_000L, 20_000L, 52_000L, 54_000L, 56_000L).forEach { r.frame(frame(it)) }
        r.command(BlackBoxCommand(49_000, "NAV_MAP_MOVE", 300, -40)); r.command(BlackBoxCommand(51_500, "NAV_STOP", 0, 0))
        r.note(BlackBoxNote(51_000, "route_contact", "Contact bumper"))
        val box = r.freeze(52_000, listOf(TaskEventType.CONTACT, TaskEventType.STOP, TaskEventType.CONTACT), "Contact bumper")
        assertEquals(listOf(10_000L, 20_000L, 52_000L, 54_000L), box.frames.map { it.atMs })   // 55 s and later: after the window
        assertEquals(listOf("NAV_MAP_MOVE", "NAV_STOP"), box.commands.map { it.label })
        assertEquals(listOf(TaskEventType.CONTACT, TaskEventType.STOP), box.triggers)
        assertEquals(52_000L, box.frameAt(53_000)!!.atMs)
    }

    @Test fun incidentsCloseTogetherShareOneBoxAndBoxesAreCapped() {
        val t = BlackBoxTrigger()
        assertTrue(t.incident("A", TaskEvent(1_000, TaskEventType.LINK_LOST, "Perte BLE")))
        assertFalse(t.incident("A", TaskEvent(2_000, TaskEventType.STOP, "STOP")))
        assertEquals(1_000 + BlackBoxRecorder.AFTER_MS, t.dueAt())
        val p = t.take()!!
        assertEquals(listOf(TaskEventType.LINK_LOST, TaskEventType.STOP), p.types)
        assertEquals("Perte BLE", p.detail)
        assertNull(t.take())
        repeat(BlackBoxRecorder.MAX_BOXES - 1) { i -> assertTrue(t.incident("A", TaskEvent(100_000L * (i + 1), TaskEventType.BLOCKED))); t.take() }
        assertFalse(t.incident("A", TaskEvent(100_000_000, TaskEventType.BLOCKED)))
        assertTrue(t.incident("B", TaskEvent(100_000_000, TaskEventType.BLOCKED)))
    }

    @Test fun boxesRoundTripThroughTheStoreAndTheExport() {
        val box = BlackBox(52_000, listOf(TaskEventType.CONTACT), "Contact", listOf(frame(50_000), BlackBoxFrame(51_000)),
            listOf(BlackBoxCommand(51_500, "NAV_STOP", 0, 0), BlackBoxCommand(51_600, "BLADES_OFF")), listOf(BlackBoxNote(51_000, "route_contact", "Contact")))
        assertEquals(box, BlackBoxJson.decode(JSONObject(BlackBoxJson.encode(box).toString())).let { d ->
            // NaN never equals itself: compare the unknown frame field by field.
            assertTrue(d.frames[1].x.isNaN() && d.frames[1].speedMps.isNaN()); assertNull(d.frames[1].linear)
            d.copy(frames = listOf(d.frames[0], box.frames[1]))
        })
        val store = TaskTrackStore(folder.root)
        store.appendBox("R-1", box)
        store.appendBox("R-1", box.copy(atMs = 10_000))
        val track = store.read("R-1")!!
        assertEquals(listOf(10_000L, 52_000L), track.boxes.map { it.atMs })
        assertTrue(store.bytes("R-1") > 0)

        val f = MowingForecaster.forecast(100.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        MowingHistoryStore(folder.root).save(listOf(MowingRecord("R-1", "R", startedAt = 1_729_000_000_000, forecast = f)))
        val doc = JSONObject(TaskExport.entries(folder.root).toList().toMap().entries.single { it.key.endsWith("-mow.json") }.value)
        assertEquals(2, doc.getJSONArray("blackBoxes").length())
        assertEquals("NAV_STOP", doc.getJSONArray("blackBoxes").getJSONObject(1).getJSONArray("commands").getJSONArray(0).getString(1))

        store.prune(listOf("R-2"))
        assertNull(store.read("R-1"))
    }

    @Test fun boxTextsGiveTheStateAtTheIncidentAndRelativeTimes() {
        val box = BlackBox(52_000, listOf(TaskEventType.LINK_LOST, TaskEventType.STOP), "", (40_000L..55_000L step 1_000).map { frame(it) },
            listOf(BlackBoxCommand(51_500, "NAV_STOP", 0, 0)), listOf(BlackBoxNote(53_200, "failure", "Perte BLE inattendue")))
        assertEquals("Perte de liaison + STOP", BlackBoxText.title(box))
        assertTrue(BlackBoxText.summary(box), BlackBoxText.summary(box).startsWith("Bluetooth direct · commande 300/-40 · batterie 60 % · RTK 4 (30 sat.)"))
        val lines = BlackBoxText.frames(box)
        assertEquals(8, lines.size)
        assertTrue(lines.first(), lines.first().startsWith("−12,0 s · (40,00, 0,00) · 90° · cmd 300/-40"))
        assertEquals("−0,5 s · NAV_STOP 0/0", BlackBoxText.commands(box).single())
        assertEquals("+1,2 s · Perte BLE inattendue", BlackBoxText.notes(box).single())
        assertEquals("Incident 1 / 2 · Contact pare-chocs", ReplayText.incident(listOf(TaskEvent(1_000, TaskEventType.CONTACT), TaskEvent(9_000, TaskEventType.STOP)), 2_000))
        assertEquals("2 incidents", ReplayText.incident(listOf(TaskEvent(1_000, TaskEventType.CONTACT), TaskEvent(9_000, TaskEventType.STOP)), 0))
    }
}
