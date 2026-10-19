package fr.luba.poc.history

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class TaskTrackTest {
    @get:Rule val folder = TemporaryFolder()

    private fun s(at: Long, x: Double, y: Double = 0.0, heading: Double = 0.0, phase: TrackPhase = TrackPhase.CUTTING, battery: Int = 80) =
        TrackSample(at, x, y, heading, phase, battery)

    @Test fun storeAppendsAcrossResumesAndReadsBack() {
        val store = TaskTrackStore(folder.root)
        store.append("AA-1", listOf(s(1_000, 1.0), s(2_000, 1.5, heading = 90.0)))
        store.append("AA-1", listOf(s(3_000, 2.0, phase = TrackPhase.CHARGING, battery = -1)))
        val track = requireNotNull(store.read("AA-1"))
        assertNull(track.meta)
        assertEquals(3, track.samples.size)
        assertEquals(1.5, track.samples[1].x, 1e-6)
        assertEquals(90.0, track.samples[1].heading, 1e-6)
        assertEquals(TrackPhase.CHARGING, track.samples[2].phase)
        assertEquals(-1, track.samples[2].battery)
        assertNull(store.read("AA-2"))
    }

    @Test fun truncatedLastSampleIsIgnored() {
        val store = TaskTrackStore(folder.root)
        store.append("AA-1", listOf(s(1_000, 1.0), s(2_000, 2.0)))
        val file = File(folder.root, "task-tracks").listFiles()!!.single { it.extension == "trk" }
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }
        assertEquals(listOf(1.0), store.read("AA-1")!!.samples.map { it.x })
    }

    @Test fun metaKeepsMapStationAndPlannedRoute() {
        val store = TaskTrackStore(folder.root)
        val map = RobotMap(listOf(MapShape(7, 0, listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 5.0)), "Pelouse")), true, "BLE",
            MapStation(Point(1.0, 2.0), 45.0, "robot"))
        val meta = TrackMeta(map, cutting = listOf(listOf(Point(1.0, 1.0), Point(4.0, 1.0))), transit = listOf(listOf(Point(1.0, 2.0), Point(1.0, 1.0))))
        store.writeMeta("AA-1", meta)
        assertTrue(store.hasMeta("AA-1"))
        val read = requireNotNull(store.read("AA-1")).meta!!
        assertEquals(map.shapes, read.map.shapes)
        assertEquals(map.station, read.map.station)
        assertEquals(meta.cutting, read.cutting)
        assertEquals(meta.transit, read.transit)
        assertEquals(meta.cutting, TrackJson.decode(JSONObject(TrackJson.encode("x", meta).toString())).cutting)
    }

    @Test fun pruneKeepsOnlyListedTasks() {
        val store = TaskTrackStore(folder.root)
        store.append("AA-1", listOf(s(1_000, 1.0)))
        store.append("AA-2", listOf(s(1_000, 1.0)))
        store.writeMeta("AA-2", TrackMeta(RobotMap()))
        store.prune(listOf("AA-2"))
        assertNull(store.read("AA-1"))
        assertNotNull(store.read("AA-2")!!.meta)
        assertEquals(1, store.read("AA-2")!!.samples.size)
    }

    @Test fun keptTracksAreTheMostRecentTasks() {
        val f = MowingForecaster.forecast(100.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        val history = (1..60).map { MowingRecord("R-$it", "R", startedAt = it.toLong(), forecast = f) }
        val kept = TaskTrackStore.kept(history)
        assertEquals(TaskTrackStore.MAX_TRACKS, kept.size)
        assertTrue("R-60" in kept && "R-21" in kept && "R-20" !in kept)
    }

    @Test fun samplerKeepsMovesTurnsPhaseChangesAndKeepAlive() {
        val sampler = TrackSampler()
        assertTrue(sampler.accept(s(0, 0.0)))
        assertFalse(sampler.accept(s(200, 0.02)))                      // 2 cm: ignored
        assertTrue(sampler.accept(s(400, 0.10)))                       // 10 cm
        assertFalse(sampler.accept(s(600, 0.12, heading = 3.0)))       // small heading change
        assertTrue(sampler.accept(s(800, 0.12, heading = 10.0)))       // turn on the spot
        assertTrue(sampler.accept(s(1_000, 0.12, heading = 10.0, phase = TrackPhase.RETURN)))
        assertFalse(sampler.accept(s(5_000, 0.12, heading = 10.0, phase = TrackPhase.RETURN)))
        assertTrue(sampler.accept(s(11_000, 0.12, heading = 10.0, phase = TrackPhase.RETURN)))
        assertTrue(sampler.accept(s(11_200, 0.12, heading = 16.0, phase = TrackPhase.RETURN)))
        assertFalse(sampler.accept(s(11_400, Double.NaN)))
    }

    @Test fun samplerHandlesHeadingWrapAround() {
        val sampler = TrackSampler()
        assertTrue(sampler.accept(s(0, 0.0, heading = 358.0)))
        assertFalse(sampler.accept(s(200, 0.0, heading = 2.0)))        // 4° across 0
        assertTrue(sampler.accept(s(400, 0.0, heading = 6.0)))         // 8°
    }

    @Test fun piecesSplitByPhaseContinuouslyAndByGaps() {
        val samples = listOf(
            s(0, 0.0), s(1_000, 0.3), s(2_000, 0.6, phase = TrackPhase.TRAVEL), s(3_000, 0.9, phase = TrackPhase.TRAVEL),
            s(4_000, 10.0, phase = TrackPhase.TRAVEL),                 // 9 m jump: gap
            s(200_000, 10.3, phase = TrackPhase.TRAVEL),               // 196 s without position: gap
        )
        val pieces = TaskTrackPieces.of(samples)
        assertEquals(listOf(TrackPhase.CUTTING, TrackPhase.TRAVEL, TrackPhase.TRAVEL, TrackPhase.TRAVEL), pieces.map { it.phase })
        assertEquals(listOf(0 to 2, 2 to 3, 4 to 4, 5 to 5), pieces.map { it.from to it.to })
        // Activity change: the mowing piece ends on the first point of the travel.
        assertEquals(Point(0.6, 0.0), pieces[0].points.last())
        assertTrue(TaskTrackPieces.of(emptyList()).isEmpty())
    }

    @Test fun poseIsInterpolatedButNotAcrossGaps() {
        val track = TaskTrack(null, listOf(s(1_000, 0.0, heading = 350.0), s(3_000, 1.0, heading = 10.0), s(4_000, 9.0)))
        assertEquals(-1, track.indexAt(500))
        assertEquals(0, track.indexAt(1_000))
        assertEquals(1, track.indexAt(3_500))
        assertEquals(2, track.indexAt(9_000))
        val mid = track.poseAt(2_000)!!
        assertEquals(0.5, mid.x, 1e-9)
        assertEquals(0.0, ((mid.heading % 360) + 360) % 360, 1e-9)    // 350 → 10 through 0
        assertEquals(1.0, track.poseAt(3_500)!!.x, 1e-9)               // 8 m jump: no interpolation
        assertEquals(0.0, track.poseAt(0)!!.x, 1e-9)
    }

    @Test fun distanceSkipsGaps() {
        assertEquals(1.0, distance(listOf(s(0, 0.0), s(1_000, 1.0), s(2_000, 9.0))), 1e-9)
    }
}
