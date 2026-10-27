package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZoneOrientationStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val zone = MapShape(4, 0, rectangle(0.0, 0.0, 10.0, 6.0), "Piscine")

    @Test fun memoryRoundTripsAndSurvivesARestart() {
        val store = ZoneOrientationStore(folder.root)
        assertTrue(store.read().isEmpty())
        val memory = mapOf(4L to ZoneOrientation(listOf(35), 1, ZoneOrientation.shapeKey(zone.points), 1000L),
            9L to ZoneOrientation(listOf(20, 110), 0, "abc", 2000L))
        store.save(memory)
        assertEquals(memory, ZoneOrientationStore(folder.root).read())
        store.save(memory - 9L)
        assertEquals(memory - 9L, ZoneOrientationStore(folder.root).read())
    }

    @Test fun aWriteBeforeTheFirstReadKeepsTheZonesOnFile() {
        val store = ZoneOrientationStore(folder.root)
        val onFile = mapOf(4L to ZoneOrientation(listOf(35), 1, ZoneOrientation.shapeKey(zone.points), 1000L),
            9L to ZoneOrientation(listOf(20, 110), 0, "abc", 2000L))
        store.save(onFile)
        // A mowing ended before the state had the file: only its zone in memory, zone 4 mowed again.
        val fresh = mapOf(4L to ZoneOrientation(listOf(125), 2, ZoneOrientation.shapeKey(zone.points), 3000L),
            7L to ZoneOrientation(listOf(60), 0, "def", 3000L))
        store.merge(fresh)
        assertEquals(mapOf(4L to fresh.getValue(4L), 7L to fresh.getValue(7L), 9L to onFile.getValue(9L)), ZoneOrientationStore(folder.root).read())
    }

    @Test fun corruptFileOrEntryIsIgnored() {
        java.io.File(folder.root, "zone-orientations.json").writeText("{not json")
        assertTrue(ZoneOrientationStore(folder.root).read().isEmpty())
        // One unreadable entry does not hide the others.
        java.io.File(folder.root, "zone-orientations.json").writeText(
            """{"version":1,"zones":{"4":{"directions":[35],"phaseIndex":1,"shapeKey":"k","mowedAt":5},"5":{"directions":[400],"phaseIndex":0,"shapeKey":"k","mowedAt":5}}}""")
        assertEquals(setOf(4L), ZoneOrientationStore(folder.root).read().keys)
    }

    @Test fun retracedZoneNoLongerMatchesItsEntry() {
        val entry = ZoneOrientation(listOf(35), 0, ZoneOrientation.shapeKey(zone.points), 1000L)
        assertTrue(entry.matches(zone))
        // Under a centimetre: same outline.
        assertTrue(entry.matches(zone.copy(points = zone.points.map { Point(it.x + 0.002, it.y) })))
        assertFalse(entry.matches(zone.copy(points = zone.points.map { Point(it.x * 1.1, it.y) })))
    }
}

class ZoneProgressTest {
    private val left = MapShape(1, 0, rectangle(0.0, 0.0, 12.0, 6.0), "Gauche")
    private val right = MapShape(2, 0, rectangle(12.0, 0.0, 12.0, 6.0), "Droite")
    private val map = RobotMap(listOf(left, right), true)
    /** Two passes of 10 m per zone, left zone first. */
    private val preview = Preview(listOf(
        listOf(Point(1.0, 1.0), Point(11.0, 1.0)), listOf(Point(11.0, 2.0), Point(1.0, 2.0)),
        listOf(Point(13.0, 1.0), Point(23.0, 1.0)), listOf(Point(23.0, 2.0), Point(13.0, 2.0))),
        40.0, 30, 2, CoverageMode.LINES, zoneIds = listOf(1L, 2L), zoneAngles = listOf(30, 120),
        zonePhases = listOf(0.5, 0.2), zoneModes = listOf(CoverageMode.LINES, CoverageMode.LINES))

    @Test fun cuttingMetresAreAttributedToTheirZoneInOrder() {
        assertEquals(mapOf(1L to 0.75, 2L to 0.0), ZoneProgress.shares(preview, map, 15.0))
        assertEquals(mapOf(1L to 1.0, 2L to 0.5), ZoneProgress.shares(preview, map, 30.0))
        assertEquals(mapOf(1L to 1.0, 2L to 1.0), ZoneProgress.shares(preview, map, 400.0))
        // Mission metres scaled to the preview (prepared mission of 20 m for a preview of 40 m).
        assertEquals(mapOf(1L to 1.0, 2L to 0.5), ZoneProgress.shares(preview, map, 15.0, totalMeters = 20.0))
    }

    @Test fun completeMowingRecordsEveryZone() {
        val memory = ZoneProgress.update(emptyMap(), preview, map, 40.0, 1000L)
        assertEquals(ZoneOrientation(listOf(30), 0, ZoneOrientation.shapeKey(left.points), 1000L), memory[1L])
        assertEquals(ZoneOrientation(listOf(120), 1, ZoneOrientation.shapeKey(right.points), 1000L), memory[2L])
    }

    @Test fun interruptedMowingRecordsOnlyTheZonesMowedAtHalfOrMore() {
        val previous = ZoneOrientation(listOf(75), 2, ZoneOrientation.shapeKey(right.points), 10L)
        val memory = ZoneProgress.update(mapOf(2L to previous), preview, map, 29.0, 1000L)
        assertEquals(listOf(30), memory[1L]!!.directions)
        // Right zone at 45 %: its previous entry is kept.
        assertEquals(previous, memory[2L])
        // Exactly half: recorded.
        assertEquals(listOf(120), ZoneProgress.update(mapOf(2L to previous), preview, map, 30.0, 1000L)[2L]!!.directions)
    }

    @Test fun resumedTaskCountsTheMetresOfTheWholeTask() {
        // A resume carries the metres done before the interruption: same shares as one run.
        val first = ZoneProgress.update(emptyMap(), preview, map, 12.0, 1000L)
        assertEquals(setOf(1L), first.keys)
        val resumed = ZoneProgress.update(first, preview, map, 40.0, 2000L)
        assertEquals(setOf(1L, 2L), resumed.keys)
        assertEquals(2000L, resumed[1L]!!.mowedAtMillis)
    }

    @Test fun checkerboardKeepsBothFamiliesAndShapeFollowingModesAreNotRecorded() {
        val checkerboard = preview.copy(mode = CoverageMode.CHECKERBOARD, zoneAngles = listOf(30, 75),
            zoneModes = listOf(CoverageMode.CHECKERBOARD, CoverageMode.CHECKERBOARD))
        assertEquals(listOf(75, 165), ZoneProgress.update(emptyMap(), checkerboard, map, 40.0, 1L)[2L]!!.directions)
        val optimized = preview.copy(mode = CoverageMode.OPTIMIZED, zoneModes = listOf(CoverageMode.OPTIMIZED, CoverageMode.OPTIMIZED))
        assertTrue(ZoneProgress.update(emptyMap(), optimized, map, 40.0, 1L).isEmpty())
        // Older preview without zone modes or phases: requested mode, centred rows.
        val older = preview.copy(zonePhases = emptyList(), zoneModes = emptyList())
        assertEquals(0, ZoneProgress.update(emptyMap(), older, map, 40.0, 1L)[2L]!!.phaseIndex)
    }

    @Test fun mergedZonesCarryTheZoneOfEachPathThroughHeightPassesAndReorderings() {
        // Zone B overlaps A by 0.2 m; B's loop runs along the shared side, inside A as well.
        val a = MapShape(1, 0, rectangle(0.0, 0.0, 10.0, 6.0), "A")
        val b = MapShape(2, 0, rectangle(9.8, 0.0, 10.0, 6.0), "B")
        val loop = listOf(Point(9.9, 3.0), Point(9.9, 0.1), Point(19.7, 0.1), Point(19.7, 5.9), Point(9.9, 5.9), Point(9.9, 3.0))
        val inA = Preview(listOf(listOf(Point(1.0, 1.0), Point(9.0, 1.0))), 8.0, 0, 0, zonePhases = listOf(0.5), zoneModes = listOf(CoverageMode.LINES))
        val inB = Preview(listOf(listOf(Point(11.0, 1.0), Point(19.0, 1.0))), 8.0, 90, 0, borders = listOf(loop), outerLoops = 1,
            zonePhases = listOf(0.5), zoneModes = listOf(CoverageMode.LINES))
        val merged = MultiZonePlanner.combine(listOf(a, b), listOf(inA, inB), CoverageMode.LINES)
        assertEquals(listOf(1L, 2L, 2L), merged.pathZoneIds)
        // The loop is B's own: B done only once its loop is.
        val twoZones = RobotMap(listOf(a, b), true)
        assertEquals(1.0, ZoneProgress.shares(merged, twoZones, 16.0).getValue(1L), 1e-9)
        assertEquals(8.0 / (8.0 + length(loop)), ZoneProgress.shares(merged, twoZones, 16.0).getValue(2L), 1e-9)
        // Three height passes, every other one reversed: B's loop opens the second pass.
        val expanded = CutPasses.expand(merged, listOf(60, 50, 40))
        assertEquals(listOf(1L, 2L, 2L, 2L, 2L, 1L, 1L, 2L, 2L), expanded.pathZoneIds)
        // Reordered and reversed paths keep their zone; a path that is not found leaves the zones unknown.
        val reordered = listOf(merged.cuttingPaths[2].reversed(), merged.cuttingPaths[0], merged.cuttingPaths[1])
        assertEquals(listOf(2L, 1L, 2L), merged.zonesOf(reordered))
        assertTrue(merged.zonesOf(reordered + listOf(listOf(Point(0.0, 0.0), Point(1.0, 0.0)))).isEmpty())
        // Zones that no longer match the paths are ignored (geometry).
        assertNull(merged.copy(pathZoneIds = listOf(1L, 2L)).cuttingPathZones)
    }
}
