package fr.luba.poc.ble

import fr.luba.poc.map.AddedNoGo
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.NoGoOrigin
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.mapsync.SyncPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Success rate of the sync of [MapSyncBenchTest] (one no-go zone, robot at (6, 4)) over many draws of the [SimRobot]
 * noise. Not part of the normal run (skipped without MAPSYNC_SWEEP): the simulator is not reproducible draw by draw
 * (thread timing counts too), so only a rate over many draws means something.
 *
 *   MAPSYNC_SWEEP=121-420 [MAPSYNC_SWEEP_OUT=file] ./gradlew :app:testDebugUnitTest --tests '*MapSyncSweepTest' --rerun
 *
 * One line per draw (phase, robot map commands, final message) then a summary. Measured on 2026-10-24 over 300 draws:
 * 53 failures (17.7 %) before the outline was faced in place, 35 (11.7 %) after, all safe aborts (nothing recorded).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapSyncSweepTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))

    @Test fun successRateOverManyDraws() {
        val range = System.getenv("MAPSYNC_SWEEP")
        assumeTrue("MAPSYNC_SWEEP=<first>-<last> not set", range != null)
        val (first, last) = range!!.split("-").map { it.trim().toLong() }
        val out = File(System.getenv("MAPSYNC_SWEEP_OUT") ?: "build/mapsync-sweep.txt").also { it.parentFile?.mkdirs(); it.writeText("") }
        val failures = mutableMapOf<String, Int>()
        for (seed in first..last) {
            val bench = RobotBench(Point(6.0, 4.0), 0.0, seed = seed)
            try {
                val base = Point(1.5, 4.0)
                val map = RobotMap(listOf(MapShape(1L, 0, rect(0.0, 0.0, 12.0, 8.0)), MapShape(2L, 0, rect(12.0, 0.0, 8.0, 8.0)),
                    MapShape(3L, 1, rect(15.5, 3.5, 1.0, 1.0))), true, "Banc d'essai", MapStation(base, 180.0, "Transmise par le robot"))
                bench.sim.dock = base; bench.sim.dockedHeading = 0.0
                bench.sim.loadMap(map); bench.installMap(map); bench.connect()
                bench.ble.saveMapCorrections(listOf(AddedNoGo(1, 0L, "Tree", rect(7.0, 3.0, 1.0, 1.0), NoGoOrigin.SHAPE)))
                val items = runBlocking { bench.ble.mapSyncDifferences() }
                bench.ble.mapSync.start(items.map { it.key }.toSet())
                bench.until(30 * 60_000, "end of the sync") { !bench.ble.mapSync.state.value.running }
                val run = bench.ble.mapSync.state.value
                if (run.phase != SyncPhase.DONE) failures.merge(run.message.substringAfter(": ").substringBefore(" ;").take(60), 1, Int::plus)
                out.appendText("seed=$seed phase=${run.phase} cmds=${bench.sim.mapCommands} msg=${run.message.replace('\n', ' ')}\n")
            } catch (t: Throwable) {
                failures.merge("exception", 1, Int::plus)
                out.appendText("seed=$seed exception ${t.message?.lineSequence()?.firstOrNull()}\n")
            } finally { bench.close() }
        }
        out.appendText("draws=${last - first + 1} failures=${failures.values.sum()} $failures\n")
        println(out.readLines().last())
    }
}
