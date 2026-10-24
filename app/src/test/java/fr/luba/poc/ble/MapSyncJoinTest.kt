package fr.luba.poc.ble

import fr.luba.poc.map.AddedNoGo
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.NoGoOrigin
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Join onto the outline of [MapSyncBenchTest] under several draws of the RTK and heading noise of [SimRobot]. The join
 * is a straight line of 23° from the approach point, ending half a chassis + 15 cm from the no-go zone: driven as an
 * arc (misalignment under the 25° pivot threshold), it swung the chassis towards the zone and the envelope check
 * refused it three times ("Raccord au contour interrompu", 8 draws out of 40, and the occasional failure of
 * [MapSyncBenchTest]). Seeds that failed then. Only the join is checked: the success rate of the recording of the
 * outline itself is measured by [MapSyncSweepTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class MapSyncJoinTest(private val seed: Long) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "seed={0}")
        fun seeds(): List<Array<Any>> = listOf(3L, 18L, 20L, 31L).map { arrayOf<Any>(it) }
    }

    private val bench = RobotBench(Point(6.0, 4.0), 0.0, seed = seed)
    @After fun tearDown() = bench.close()

    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))

    @Test fun `the join onto the outline is driven to its end`() {
        val base = Point(1.5, 4.0)
        val map = RobotMap(listOf(MapShape(1L, 0, rect(0.0, 0.0, 12.0, 8.0)), MapShape(2L, 0, rect(12.0, 0.0, 8.0, 8.0)),
            MapShape(3L, 1, rect(15.5, 3.5, 1.0, 1.0))), true, "Banc d'essai", MapStation(base, 180.0, "Transmise par le robot"))
        bench.sim.dock = base; bench.sim.dockedHeading = 0.0
        bench.sim.loadMap(map)
        bench.installMap(map)
        bench.connect()
        assertTrue(bench.ble.saveMapCorrections(listOf(AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE))))
        val items = runBlocking { bench.ble.mapSyncDifferences() }
        assertTrue(bench.ble.mapSync.start(items.map { it.key }.toSet()))
        // Join done once the robot is asked to record (or the sync ended before).
        bench.until(10 * 60_000, "join done") { bench.sim.mapCommands.isNotEmpty() || !bench.ble.mapSync.state.value.running }
        val run = bench.ble.mapSync.state.value
        assertFalse("${run.message}\n${bench.dump()}", run.message.contains("Raccord au contour") || run.message.contains("Join onto the outline"))
        assertTrue("${run.message}\n${bench.dump()}", bench.sim.mapCommands.firstOrNull()?.startsWith("draw-start") == true)
    }
}
