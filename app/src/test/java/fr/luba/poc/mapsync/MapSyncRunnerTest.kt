package fr.luba.poc.mapsync

import fr.luba.poc.map.AddedZone
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync run against a simulated robot: order of the robot operations, element found after the reading, former element
 * deleted after the new one only, corrections removed, stop on the first failure with the recording abandoned.
 */
class MapSyncRunnerTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val lawn = MapShape(101L, 0, rect(0.0, 0.0, 20.0, 12.0), "Lawn")
    private val bed = MapShape(201L, 1, rect(14.0, 4.0, 2.0, 2.0), "Bed")

    /** Robot that records what it is asked to and keeps a log of the operations. */
    private class FakeRobot(var map: RobotMap, var corrections: List<MapCorrection>) : MapSyncPort {
        val calls = mutableListOf<String>()
        var failTrace: String? = null
        var refuse: String? = null
        var recordedOutline: List<Point>? = null
        var nextId = 900L
        /** Elements the robot removes together with a deleted one (parent zone). */
        var dropWith: Map<Long, List<Long>> = emptyMap()
        private var recordingType: Int? = null
        private var position = Point(10.0, 6.0)

        override fun refusal() = refuse
        override fun robotPosition() = position
        override suspend fun differences(): List<SyncItem> =
            MapSyncPlanner.differences(map, MapCorrections.apply(map, corrections), corrections)
        override fun recreate(shape: MapShape) = MapSyncPlanner.recreate(shape)
        override suspend fun driveTo(targets: List<Point>): String? { calls += "drive"; position = targets.first(); return null }
        override fun firstDrivable(paths: List<List<Point>>): Int? = if (paths.isEmpty()) null else 0
        override suspend fun driveExact(path: List<Point>): String? { calls += "join"; position = path.last(); return null }
        override suspend fun startRecording(kind: SyncKind): String? { calls += "start:${kind.type}"; recordingType = kind.type; return null }
        private var lastTrace: List<Point> = emptyList()
        override suspend fun driveTrace(trace: List<Point>, kind: SyncKind, progress: (Double) -> Unit): String? {
            calls += "trace"
            progress(0.5)
            failTrace?.let { return it }
            lastTrace = trace
            position = trace.last()
            return null
        }
        /** What the firmware stores for a driven centre trace: outer side of the chassis (zone), inner side (no-go). */
        private fun stored(trace: List<Point>, kind: SyncKind): List<Point> {
            if (kind == SyncKind.PASSAGE) return trace
            val factory = org.locationtech.jts.geom.GeometryFactory()
            val ring = (if (trace.first() == trace.last()) trace else trace + trace.first()).map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }
            val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
            val area = factory.createPolygon(ring.toTypedArray()).buffer(if (kind == SyncKind.ZONE) half else -half)
            return (area as org.locationtech.jts.geom.Polygon).exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
        }
        override suspend fun endRecording(kind: SyncKind): String? {
            calls += "end:${kind.type}"
            val outline = recordedOutline ?: stored(lastTrace, kind)
            val element = MapShape(nextId++, kind.type, outline)
            if (saveNeeded) unsaved = element else map = map.copy(shapes = map.shapes + element)
            recordingType = null
            return null
        }
        override fun cancelRecording() { calls += "cancel"; recordingType = null }
        /** Element kept by the robot only after the explicit save (firmware that needs it). */
        var saveNeeded = false
        private var unsaved: MapShape? = null
        override suspend fun saveRecording(): String? { calls += "save-map"; unsaved?.let { map = map.copy(shapes = map.shapes + it) }; unsaved = null; return null }
        override suspend fun deleteElement(kind: SyncKind, hash: Long): String? {
            calls += "delete:$hash"
            val gone = setOf(hash) + dropWith[hash].orEmpty()
            map = map.copy(shapes = map.shapes.filter { it.id !in gone })
            return null
        }
        override suspend fun readMap(): RobotMap { calls += "read"; return map }
        override fun corrections() = corrections
        override fun saveCorrections(corrections: List<MapCorrection>): Boolean { calls += "save:${corrections.size}"; this.corrections = corrections; return true }
        override fun stopMotion(reason: String) { calls += "stop" }
        override fun log(kind: String, text: String, fields: Map<String, Any?>) {}
    }

    private fun runToEnd(robot: FakeRobot, keys: Set<String>? = null): MapSyncRun = runBlocking {
        val runner = MapSyncRunner(robot, this)
        val chosen = keys ?: robot.differences().map { it.key }.toSet()
        runner.start(chosen)
        withTimeout(5000) { while (runner.state.value.running) delay(5) }
        runner.state.value
    }

    @Test fun aMissingZoneIsDrivenRecordedCheckedAndLeavesThePhone() {
        val zone = AddedZone(1, 0L, "DL zone", "Shed", rect(2.0, 2.0, 8.0, 6.0))
        val robot = FakeRobot(RobotMap(listOf(lawn), true, "BLE"), listOf(zone))
        val run = runToEnd(robot)
        assertEquals(SyncPhase.DONE, run.phase)
        assertEquals(ItemStatus.DONE, run.items.single().status)
        assertEquals(listOf("read", "drive", "join", "start:0", "trace", "end:0", "read", "save:0"), robot.calls)
        assertTrue(robot.map.shapes.any { it.id == 900L && it.type == 0 })
        assertTrue(robot.corrections.isEmpty())
    }

    @Test fun aCorrectedZoneIsRecordedFirstThenTheFormerOneIsDeleted() {
        val grown = ShapeOffset(4, 0L, "Lawn +0.5 m", 101L, MapCorrections.fingerprint(lawn), 0.5)
        val robot = FakeRobot(RobotMap(listOf(lawn, bed), true, "BLE"), listOf(grown))
        // The firmware also removes the no-go zone of the deleted zone: it is recorded again from its former outline.
        robot.dropWith = mapOf(101L to listOf(201L))
        val run = runToEnd(robot)
        assertEquals(run.message, SyncPhase.DONE, run.phase)
        val calls = robot.calls
        assertTrue(calls.indexOf("end:0") < calls.indexOf("delete:101"))
        assertTrue(calls.contains("start:1"))
        assertEquals(listOf(ItemStatus.DONE, ItemStatus.DONE), run.items.map { it.status })
        assertTrue(run.items.last().item.recreated)
        assertFalse(robot.map.shapes.any { it.id == 101L })
        assertTrue(robot.map.shapes.any { it.type == 1 })
        assertTrue(robot.corrections.isEmpty())
    }

    @Test fun anInterruptedOutlineAbandonsTheRecordingAndChangesNothingOnThePhone() {
        val zone = AddedZone(1, 0L, "DL zone", "Shed", rect(2.0, 2.0, 8.0, 6.0))
        val robot = FakeRobot(RobotMap(listOf(lawn), true, "BLE"), listOf(zone))
        robot.failTrace = "Bumper contact"
        val run = runToEnd(robot)
        assertEquals(SyncPhase.FAILED, run.phase)
        assertEquals(ItemStatus.FAILED, run.items.single().status)
        assertTrue(run.message.contains("Bumper contact"))
        assertTrue(robot.calls.containsAll(listOf("cancel", "stop")))
        assertFalse(robot.calls.any { it.startsWith("end") || it.startsWith("save") || it.startsWith("delete") })
        assertEquals(listOf(zone), robot.corrections)
    }

    @Test fun anElementOnlyKeptAfterAnExplicitSaveIsSavedThenFound() {
        val zone = AddedZone(1, 0L, "DL zone", "Shed", rect(2.0, 2.0, 8.0, 6.0))
        val robot = FakeRobot(RobotMap(listOf(lawn), true, "BLE"), listOf(zone))
        robot.saveNeeded = true
        val run = runToEnd(robot)
        assertEquals(run.message, SyncPhase.DONE, run.phase)
        assertEquals(listOf("read", "drive", "join", "start:0", "trace", "end:0", "read", "save-map", "read", "save:0"), robot.calls)
    }

    @Test fun anElementMissingAfterTheRecordingDeletesNothing() {
        val grown = ShapeOffset(4, 0L, "Lawn +0.5 m", 101L, MapCorrections.fingerprint(lawn), 0.5)
        val robot = FakeRobot(RobotMap(listOf(lawn), true, "BLE"), listOf(grown))
        robot.recordedOutline = rect(60.0, 60.0, 3.0, 3.0)
        val run = runToEnd(robot)
        assertEquals(SyncPhase.FAILED, run.phase)
        // Explicit save tried once, then nothing deleted and the corrections kept.
        assertTrue(robot.calls.contains("save-map"))
        assertFalse(robot.calls.any { it.startsWith("delete") || it.startsWith("save:") })
        assertTrue(robot.map.shapes.any { it.id == 101L })
    }

    @Test fun aRefusedStartTouchesNothing() {
        val robot = FakeRobot(RobotMap(listOf(lawn), true, "BLE"), emptyList())
        robot.refuse = "Battery ≤ 40 %"
        runBlocking {
            val runner = MapSyncRunner(robot, this)
            assertFalse(runner.start(setOf("create:1")))
            assertEquals(SyncPhase.FAILED, runner.state.value.phase)
            assertEquals("Battery ≤ 40 %", runner.state.value.message)
        }
        assertTrue(robot.calls.isEmpty())
    }
}
