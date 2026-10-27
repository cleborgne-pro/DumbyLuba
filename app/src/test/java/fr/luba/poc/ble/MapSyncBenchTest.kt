package fr.luba.poc.ble

import fr.luba.poc.map.AddedNoGo
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.NoGoOrigin
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeOffset
import fr.luba.poc.mapsync.ItemStatus
import fr.luba.poc.mapsync.MapSyncPlanner
import fr.luba.poc.mapsync.SyncPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sync with the Mammotion app end to end: real [RobotBle] (map reading, moves, recording mode of the tracking, BluFi
 * frames) against [SimRobot], which records its own published positions between "start drawing" and "end drawing" like
 * the firmware, leaves the recording state if it receives the manual mode, and serves its map over BLE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapSyncBenchTest {
    private val bench = RobotBench(Point(6.0, 4.0), 0.0)
    @After fun tearDown() = bench.close()

    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val lawn = MapShape(1L, 0, rect(0.0, 0.0, 12.0, 8.0))
    private val back = MapShape(2L, 0, rect(12.0, 0.0, 8.0, 8.0))
    private val bed = MapShape(3L, 1, rect(15.5, 3.5, 1.0, 1.0))
    private val base = Point(1.5, 4.0)
    private val map = RobotMap(listOf(lawn, back, bed), true, "Banc d'essai", MapStation(base, 180.0, "Transmise par le robot"))
    private val factory = GeometryFactory()

    private fun overlap(a: List<Point>, b: List<Point>): Double {
        fun poly(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(0.0)
        val pa = poly(a); val pb = poly(b)
        return pa.intersection(pb).area / pa.union(pb).area
    }

    private fun ready(vararg corrections: fr.luba.poc.map.MapCorrection) {
        bench.sim.dock = base; bench.sim.dockedHeading = 0.0
        bench.sim.loadMap(map)
        bench.installMap(map)
        bench.connect()
        assertTrue(bench.dump(), bench.state.mammotionMap.complete)
        assertTrue(bench.ble.saveMapCorrections(corrections.toList()))
    }

    private fun sync(): fr.luba.poc.mapsync.MapSyncRun {
        val items = runBlocking { bench.ble.mapSyncDifferences() }
        items.forEach { assertTrue("${it.title}: ${it.refusal}", it.possible) }
        assertTrue(bench.ble.mapSyncRefusal() ?: "", bench.ble.mapSync.start(items.map { it.key }.toSet()))
        bench.until(30 * 60_000, "end of the sync") { !bench.ble.mapSync.state.value.running }
        return bench.ble.mapSync.state.value
    }

    /** LUBA 2 footprint with its centre of rotation at [p], heading [headingDegrees]. */
    private fun chassis(p: Point, headingDegrees: Double): org.locationtech.jts.geom.Polygon {
        val robot = fr.luba.poc.obstacles.RobotEnvelope.LUBA2
        val c = Math.cos(Math.toRadians(headingDegrees)); val s = Math.sin(Math.toRadians(headingDegrees))
        val corners = listOf(robot.frontM to robot.halfWidthM, robot.frontM to -robot.halfWidthM, -robot.rearM to -robot.halfWidthM, -robot.rearM to robot.halfWidthM)
            .map { (f, l) -> Coordinate(p.x + f * c - l * s, p.y + f * s + l * c) }
        return factory.createPolygon((corners + corners.first()).toTypedArray())
    }

    /** Commands received by the robot between the start and the end of each recording. */
    private fun recordingLabels(): List<String> {
        val labels = bench.sim.trace.map { it.second }
        val start = labels.indexOfFirst { it.startsWith("draw-start") }
        val end = labels.indexOfLast { it.startsWith("draw-end") || it == "draw-cancel" }
        return if (start < 0 || end < start) emptyList() else labels.subList(start, end + 1)
    }

    @Test fun `a no-go zone drawn on the phone is driven around, recorded by the robot and leaves the phone`() {
        val tree = AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE)
        ready(tree)
        // Actual chassis of the simulated robot (not the pose the app believes): it never touches the tree. With the
        // heading confirmed again over the short link before the outline, it entered the zone on 7 draws of 60.
        val treeArea = factory.createPolygon((tree.points + tree.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        var closest = Double.POSITIVE_INFINITY
        bench.sim.onTick = { sim ->
            closest = minOf(closest, chassis(sim.position, sim.heading).distance(treeArea))
        }
        val run = sync()
        assertEquals("${run.message}\n${bench.dump()}", SyncPhase.DONE, run.phase)
        assertTrue("Chassis %.3f m from the tree".format(closest), closest > 0.0)
        assertEquals(listOf(ItemStatus.DONE), run.items.map { it.status })
        assertEquals(listOf("draw-start 1", "draw-end 1"), bench.sim.mapCommands)
        assertEquals(0, bench.sim.drawsBrokenByManual)
        assertEquals(0, bench.sim.drawsRefused)
        // Never the manual mode nor the blades while the robot records.
        assertTrue(recordingLabels().toString(), recordingLabels().none { it == "manual" || it.startsWith("blades-on") || it.startsWith("knife") })
        val created = bench.sim.storedMap().single { it.id !in setOf(1L, 2L, 3L) }
        assertEquals(1, created.type)
        assertTrue("Recorded no-go overlaps the wanted one: ${overlap(created.points, tree.points)}", overlap(created.points, tree.points) > 0.6)
        // The phone now reads it from the robot, and no longer holds the correction.
        assertTrue(bench.state.mammotionMap.shapes.any { it.id == created.id })
        assertTrue(bench.state.mapCorrections.isEmpty())
        assertTrue(runBlocking { bench.ble.mapSyncDifferences() }.isEmpty())
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
    }

    @Test fun `a corrected zone is recorded again, the former one deleted afterwards, and its lost no-go zone recorded again`() {
        val wider = ShapeOffset(1, 0L, "Back +0.3 m", 2L, MapCorrections.fingerprint(back), 0.3)
        bench.sim.deletedWith = mapOf(2L to listOf(3L))
        ready(wider)
        val wanted = MapCorrections.apply(map, listOf(wider)).map.shapes.single { it.id == 2L }.points
        val run = sync()
        assertEquals("${run.message}\n${bench.dump()}", SyncPhase.DONE, run.phase)
        assertEquals(listOf(ItemStatus.DONE, ItemStatus.DONE), run.items.map { it.status })
        assertTrue(run.items.last().item.recreated)
        assertEquals(listOf("draw-start 0", "draw-end 0", "delete 2", "draw-start 1", "draw-end 1"), bench.sim.mapCommands)
        val stored = bench.sim.storedMap()
        assertFalse(stored.any { it.id == 2L || it.id == 3L })
        val zone = stored.single { it.type == 0 && it.id != 1L }
        assertTrue("Recorded zone overlaps the corrected one: ${overlap(zone.points, wanted)}", overlap(zone.points, wanted) > 0.85)
        val noGo = stored.single { it.type == 1 }
        assertTrue("No-go recorded again: ${overlap(noGo.points, bed.points)}", overlap(noGo.points, bed.points) > 0.5)
        assertEquals(0, bench.sim.drawsBrokenByManual)
        assertTrue(bench.state.mapCorrections.isEmpty())
    }

    @Test fun `starting from the charging station, the robot leaves it and records the no-go zone`() {
        bench.sim.placeDocked(base, 0.0)
        ready(AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE))
        assertTrue(bench.sim.charging)
        val run = sync()
        assertEquals("${run.message}\n${bench.dump()}", SyncPhase.DONE, run.phase)
        assertEquals(listOf("draw-start 1", "draw-end 1"), bench.sim.mapCommands)
        assertTrue(bench.sim.storedMap().any { it.type == 1 && it.id != 3L })
        assertEquals(0, bench.sim.drawsBrokenByManual)
    }

    @Test fun `a firmware that keeps the element only after the explicit save gets it`() {
        bench.sim.saveNeeded = true
        ready(AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE))
        val run = sync()
        assertEquals("${run.message}\n${bench.dump()}", SyncPhase.DONE, run.phase)
        assertEquals(listOf("draw-start 1", "draw-end 1", "save"), bench.sim.mapCommands)
        assertTrue(bench.sim.storedMap().any { it.type == 1 && it.id !in setOf(3L) })
    }

    @Test fun `stop while the robot records abandons the recording and keeps the corrections`() {
        val tree = AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE)
        ready(tree)
        val items = runBlocking { bench.ble.mapSyncDifferences() }
        assertTrue(bench.ble.mapSync.start(items.map { it.key }.toSet()))
        bench.until(10 * 60_000, "recording under way") { bench.ble.mapSync.state.value.phase == SyncPhase.RECORDING && bench.sim.drawing != null && bench.sim.speed > 0.05 }
        bench.advance(3000)
        bench.ble.mapSync.stop()
        bench.advance(5000)
        assertEquals(SyncPhase.STOPPED, bench.ble.mapSync.state.value.phase)
        assertEquals(listOf("draw-start 1", "draw-cancel"), bench.sim.mapCommands)
        assertEquals(null, bench.sim.drawing)
        assertEquals(0.0, bench.sim.speed, 0.01)
        assertEquals(setOf(1L, 2L, 3L), bench.sim.storedMap().map { it.id }.toSet())
        assertEquals(listOf(tree), bench.state.mapCorrections)
    }

    @Test fun `an outline the robot leaves while recording is abandoned, nothing saved`() {
        ready(AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE))
        val items = runBlocking { bench.ble.mapSyncDifferences() }
        assertTrue(bench.ble.mapSync.start(items.map { it.key }.toSet()))
        bench.until(10 * 60_000, "recording under way") { bench.sim.drawing != null && bench.sim.speed > 0.1 }
        bench.advance(2000)
        // Robot pushed off its outline (slide, collision): no detour may be drawn into the element.
        bench.sim.shove(0.0, 0.8)
        bench.until(5 * 60_000, "end of the sync") { !bench.ble.mapSync.state.value.running }
        assertEquals(bench.dump(), SyncPhase.FAILED, bench.ble.mapSync.state.value.phase)
        assertTrue(bench.sim.mapCommands.toString(), bench.sim.mapCommands.last() == "draw-cancel")
        assertFalse(bench.sim.mapCommands.any { it.startsWith("draw-end") })
        assertEquals(1, bench.state.mapCorrections.size)
    }

    @Test fun `outlines too close to the charging station are not offered`() {
        ready(AddedNoGo(1, 0L, "Near the base", rect(2.6, 3.6, 0.6, 0.6), NoGoOrigin.SHAPE))
        val item = runBlocking { bench.ble.mapSyncDifferences() }.single()
        assertFalse(item.possible)
        assertTrue(item.refusal, item.refusal!!.contains("station"))
        assertTrue(MapSyncPlanner.STATION_CLEARANCE_M < 1.0)
    }
}
