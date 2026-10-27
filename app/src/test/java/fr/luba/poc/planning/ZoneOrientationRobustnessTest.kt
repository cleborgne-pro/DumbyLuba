package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MissionCheckpointStore
import fr.luba.poc.simulation.PreviewJson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Memory of the mowed directions and saved previews against damaged files, retraced zones and future values. */
class ZoneOrientationRobustnessTest {
    @get:Rule val folder = TemporaryFolder()
    private val left = MapShape(1, 0, rectangle(0.0, 0.0, 12.0, 6.0), "Gauche")
    private val right = MapShape(2, 0, rectangle(12.0, 0.0, 12.0, 6.0), "Droite")
    private val map = RobotMap(listOf(left, right), true)
    private val preview = Preview(listOf(
        listOf(Point(1.0, 1.0), Point(11.0, 1.0)), listOf(Point(11.0, 2.0), Point(1.0, 2.0)),
        listOf(Point(13.0, 1.0), Point(23.0, 1.0)), listOf(Point(23.0, 2.0), Point(13.0, 2.0))),
        40.0, 30, 2, CoverageMode.LINES, zoneIds = listOf(1L, 2L), zoneAngles = listOf(30, 120),
        zonePhases = listOf(0.5, 0.2), zoneModes = listOf(CoverageMode.LINES, CoverageMode.LINES))
    private val file get() = File(folder.root, "zone-orientations.json")

    @Test fun damagedFilesReadAsEmptyOrKeepTheValidEntries() {
        for (content in listOf("", "   ", "null", "[]", "{}", """{"version":1}""", """{"version":1,"zones":[]}""",
                """{"version":1,"zones":{"4":{"directions":[35],"phaseIndex":1,"shapeKey":"k","mowedAt":5}""")) {
            file.writeText(content)
            assertTrue("'$content'", ZoneOrientationStore(folder.root).read().isEmpty())
        }
        file.writeText("""{"version":1,"zones":{
            "4":{"directions":[35],"phaseIndex":1,"shapeKey":"k","mowedAt":5},
            "5":{"directions":[35],"phaseIndex":3,"shapeKey":"k","mowedAt":5},
            "6":{"directions":[],"phaseIndex":0,"shapeKey":"k","mowedAt":5},
            "7":{"directions":[-5],"phaseIndex":0,"shapeKey":"k","mowedAt":5},
            "zone":{"directions":[35],"phaseIndex":0,"shapeKey":"k","mowedAt":5},
            "8":{"directions":[35],"phaseIndex":0,"mowedAt":5},
            "9":"broken"}}""")
        assertEquals(setOf(4L), ZoneOrientationStore(folder.root).read().keys)
        // A save after a damaged file replaces it; a leftover temporary file does not disturb the next save.
        File(folder.root, "zone-orientations.json.tmp").writeText("{half")
        val memory = ZoneProgress.update(emptyMap(), preview, map, 40.0, 7L)
        ZoneOrientationStore(folder.root).save(memory)
        assertEquals(memory, ZoneOrientationStore(folder.root).read())
    }

    @Test fun retracedZoneGetsANewEntryOnlyWhenMowedAgainAtHalf() {
        val memory = ZoneProgress.update(emptyMap(), preview, map, 40.0, 1L)
        assertTrue(memory.getValue(1L).matches(left))
        // Left zone retraced (wider): its old entry no longer matches.
        val retraced = left.copy(points = rectangle(0.0, 0.0, 12.0, 7.0))
        val newMap = map.copy(shapes = listOf(retraced, right))
        assertFalse(memory.getValue(1L).matches(retraced))
        // Mowed at 25 % on the new outline: the stale entry stays (and stays ignored by the planner).
        val partial = ZoneProgress.update(memory, preview, newMap, 5.0, 2L)
        assertEquals(memory.getValue(1L), partial.getValue(1L))
        assertFalse(partial.getValue(1L).matches(retraced))
        // Mowed again past half: the entry follows the new outline.
        val again = ZoneProgress.update(partial, preview, newMap, 40.0, 3L)
        assertTrue(again.getValue(1L).matches(retraced))
        assertEquals(3L, again.getValue(1L).mowedAtMillis)
        // A zone deleted from the map is not recorded and keeps no new entry.
        val withoutRight = ZoneProgress.update(emptyMap(), preview, map.copy(shapes = listOf(left)), 40.0, 4L)
        assertEquals(setOf(1L), withoutRight.keys)
    }

    @Test fun interruptedResumedAndRetracedMemoryThroughThePlanner() {
        val shape = rectangle(0.0, 0.0, 14.0, 9.0)
        val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65)
        val base = request(shape, config = config)
        val automatic = RoutePlanning.calculate(base, CoverageMode.LINES).selected
        val p = automatic.preview!!
        val total = p.cuttingPaths.sumOf { path -> path.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
        val each = base.copy(config = config.copy(orientation = OrientationMode.EACH_SESSION))
        // Interrupted at 40 %: nothing remembered, the next session is the automatic plan.
        val interrupted = ZoneProgress.update(emptyMap(), p, base.map, 0.4 * total, 1L)
        assertTrue(interrupted.isEmpty())
        assertEquals(automatic.planHash, RoutePlanning.calculate(each.copy(orientationMemory = interrupted), CoverageMode.LINES).selected.planHash)
        // Resumed to the end (cumulative metres of the task): remembered, the next session moves.
        val resumed = ZoneProgress.update(interrupted, p, base.map, total, 2L)
        val next = RoutePlanning.calculate(each.copy(orientationMemory = resumed), CoverageMode.LINES).selected
        assertTrue(next.diagnostics.toString(), next.valid)
        assertNotEquals(automatic.planHash, next.planHash)
        // Same memory on a retraced zone: ignored, automatic plan of the new outline.
        val retracedShape = rectangle(0.0, 0.0, 14.0, 9.5)
        val retraced = request(retracedShape, config = config)
        val retracedAuto = RoutePlanning.calculate(retraced, CoverageMode.LINES).selected
        assertEquals(retracedAuto.planHash, RoutePlanning.calculate(retraced.copy(config = each.config, orientationMemory = resumed),
            CoverageMode.LINES).selected.planHash)
    }

    @Test fun borderLoopAlongAnOverlappingNeighbourCountsForItsOwnZone() {
        // Recorded zones often overlap by a few centimetres. Zone B (x 9.8..19.8) overlaps zone A (x 0..10) by 0.2 m;
        // B's border loop starts in the middle of its right side, so the middle of its length is on its left side,
        // inside A as well.
        val a = MapShape(1, 0, rectangle(0.0, 0.0, 10.0, 6.0), "A")
        val b = MapShape(2, 0, rectangle(9.8, 0.0, 10.0, 6.0), "B")
        val loop = listOf(Point(19.7, 3.0), Point(19.7, 5.9), Point(9.9, 5.9), Point(9.9, 0.1), Point(19.7, 0.1), Point(19.7, 3.0))
        val p = Preview(listOf(
            listOf(Point(1.0, 1.0), Point(9.0, 1.0)), listOf(Point(9.0, 2.0), Point(1.0, 2.0)),
            listOf(Point(11.0, 1.0), Point(19.0, 1.0)), listOf(Point(19.0, 2.0), Point(11.0, 2.0)), loop),
            0.0, 0, 1, CoverageMode.LINES, zoneIds = listOf(1L, 2L), zoneAngles = listOf(0, 90),
            zonePhases = listOf(0.5, 0.5), zoneModes = listOf(CoverageMode.LINES, CoverageMode.LINES))
        val twoZones = RobotMap(listOf(a, b), true)
        val loopMeters = loop.zipWithNext().sumOf { (u, v) -> u.distance(v) }
        // Interrupted after A and B's passes, before B's loop: B is mowed at 16 / (16 + 31.2) = 34 %.
        val shares = ZoneProgress.shares(p, twoZones, 32.0)
        assertEquals(16.0 / (16.0 + loopMeters), shares.getValue(2L), 1e-6)
        assertEquals(1.0, shares.getValue(1L), 1e-6)
        assertFalse("B recorded at 34 %", 2L in ZoneProgress.update(emptyMap(), p, twoZones, 32.0, 1L))
    }

    @Test fun savedPreviewWithFutureOrDamagedZoneFieldsIsRefusedNotMisread() {
        val json = PreviewJson.encode(preview)
        json.put("zoneModes", org.json.JSONArray(listOf("LINES", "ZAMBONI_FROM_THE_FUTURE")))
        val modes = runCatching { PreviewJson.decode(org.json.JSONObject(json.toString())) }
        println("ORIENTATION checkpoint unknown zone mode: ${modes.exceptionOrNull() ?: modes.getOrNull()?.zoneModes}")
        modes.onSuccess { assertTrue(it.zoneModes.none { m -> m.name.startsWith("ZAMBONI_FROM") }) }
        json.put("zoneModes", org.json.JSONArray(listOf("LINES", "LINES"))).put("zonePhases", org.json.JSONArray(listOf(0.5, 7.0)))
        assertTrue(runCatching { PreviewJson.decode(org.json.JSONObject(json.toString())) }.isFailure)
        // Through the checkpoint store: a save carrying a future zone mode reads as no checkpoint, never as an exception
        // nor as a resume with another pattern.
        val store = MissionCheckpointStore(folder.root)
        store.save(fr.luba.poc.simulation.MissionCheckpoint("AA:BB", "map", "plan", 4, preview, true, 45, 20, 90,
            totalMeters = 40.0, startedAt = 1L))
        assertEquals(preview, store.read("AA:BB")!!.preview)
        val saved = folder.root.listFiles()!!.single { it.name.startsWith("mission-checkpoint-") }
        saved.writeText(saved.readText().replace("\"zoneModes\":[\"LINES\",\"LINES\"]", "\"zoneModes\":[\"LINES\",\"SPIRAL_FROM_THE_FUTURE\"]"))
        assertTrue("SPIRAL_FROM_THE_FUTURE" in saved.readText())
        val read = runCatching { store.read("AA:BB") }
        assertTrue(read.exceptionOrNull()?.toString(), read.isSuccess)
        println("ORIENTATION checkpoint with a future zone mode: ${read.getOrNull()?.preview?.zoneModes ?: "checkpoint dropped"}")
        read.getOrNull()?.let { assertTrue(it.preview.zoneModes.none { m -> m.name.startsWith("SPIRAL_FROM") }) }
    }
}
