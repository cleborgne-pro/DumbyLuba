package fr.luba.poc.ble

import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.RoutePhase
import fr.luba.poc.simulation.MissionCheckpointStore
import fr.luba.poc.simulation.SmartResume
import fr.luba.poc.history.MowingOutcome
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Blockage while mowing: instead of stopping the mowing, the blocked position is memorised and the problematic
 * segment is bypassed (wheels spinning on soft ground: estimated obstacle, back-off, detour) or, when the bypass is
 * impossible (object too large), deferred to the end of the mowing and tried again there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleBlockageTest {
    private val bench = RobotBench(Point(5.0, 4.0), 0.0)
    @After fun tearDown() { bench.close() }

    /** Middle of a long stripe in the second half of the plan, still ahead of the robot. */
    private fun spotAhead(engine: MowingSimulation): Point {
        val stripes = engine.edges.withIndex().filter { it.value.cutting && it.value.meters > 3.0 }
        val e = stripes[stripes.size * 3 / 5].value
        return Point((e.a.x + e.b.x) / 2, (e.a.y + e.b.y) / 2)
    }

    private val seen = linkedSetOf<String>()
    private fun watch() { bench.sim.onTick = { bench.state.events.takeLast(20).forEach { e ->
        if (Regex("locage|reporté|abandon|Bloqué|contourn|réparé|Contact").containsMatchIn(e)) seen += e } } }
    private fun seen(text: String) = seen.count { it.contains(text) }

    @Test fun `wheels spinning on soft ground, place memorised and bypassed, mowing finished`() {
        val engine = bench.mowingUnderway()
        val spot = spotAhead(engine)
        bench.sim.mud = spot to 0.2
        watch()
        bench.until(40 * 60_000, "blockage") { seen("Blocage présumé") > 0 }
        bench.until(5 * 60_000, "bypass") { seen("Trajet restant réparé autour de l'obstacle") > 0 }
        assertTrue(bench.state.robotRoute.running)
        // Position memorised: blockage on the map, estimated obstacle in front of the robot.
        val blocked = bench.state.difficulties.events.filter { it.kind == DifficultyKind.BLOCKED }
        assertTrue(blocked.isNotEmpty())
        bench.until(60 * 60_000, "end of mowing") { !bench.state.robotRoute.running }
        assertEquals(seen.joinToString("\n"), RoutePhase.COMPLETE, bench.state.robotRoute.phase)
        bench.assertNativeDockOnlyAtApproach(engine)
        assertEquals(0, bench.sim.contacts)
    }

    @Test fun `deferred segment kept across an interruption, resumed from the base and mowed`() {
        val engine = bench.mowingUnderway()
        bench.until(30 * 60_000, "progress") { (bench.state.missionCheckpoint?.progress ?: 0.0) > 0.3 }
        val blocked = bench.sim.position
        bench.ble.stop()
        bench.until(5000, "closing") { bench.state.connection == ConnectionState.Disconnected }
        val saved = requireNotNull(bench.state.missionCheckpoint)
        val store = MissionCheckpointStore(bench.app.filesDir)
        bench.awaitReal(10_000, "progress written") { store.read(saved.robotId)?.updatedAt == saved.updatedAt }
        // Blockage at the stop position: segment deferred to the end ([SmartResume.deferBlocked]), as the mission does.
        val deferral = requireNotNull(SmartResume.deferBlocked(engine.edges, saved, blocked))
        bench.close()
        store.save(deferral.checkpoint)
        val second = RobotBench(RobotBench.BASE, RobotBench.DOCKED_HEADING)
        try {
            second.sim.placeDocked(RobotBench.BASE, RobotBench.DOCKED_HEADING)
            second.installMap()
            second.connect()
            val restored = requireNotNull(second.state.missionCheckpoint)
            assertEquals(deferral.checkpoint.deferred, restored.deferred)
            assertTrue(second.ble.resumeRobotRoute(second.resumeEngine()))
            val mark = second.sim.track.size
            second.until(60 * 60_000, "end of mowing") { !second.state.robotRoute.running }
            assertEquals(second.dump(), RoutePhase.COMPLETE, second.state.robotRoute.phase)
            assertNull(second.state.missionCheckpoint)
            assertEquals(MowingOutcome.COMPLETE, second.state.mowingReport?.outcome)
            // Deferred segment mowed during the resume: the robot went back over the blocked place.
            assertTrue(second.sim.track.drop(mark).any { it.distance(blocked) < 0.2 })
            second.assertNativeDockOnlyAtApproach(engine)
        } finally { second.close() }
    }
}
