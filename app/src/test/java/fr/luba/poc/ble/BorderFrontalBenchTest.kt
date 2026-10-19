package fr.luba.poc.ble

import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.BorderPhase
import fr.luba.poc.simulation.DeadReckoning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Frontal border test with the simulated robot: positioning facing the lower zone boundary (y = 0), slow advance
 * to the pass end, visual verdict, then advance until contact with a wall placed 5 cm before the outline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BorderFrontalBenchTest {
    private val bench = RobotBench(Point(4.0, 2.5), 270.0)
    @After fun tearDown() = bench.close()

    /** Straight wall at [y] (giant tangent disc), along the lower boundary. */
    private fun wallAt(y: Double) { bench.sim.obstacle = Point(4.0, y - 1000.0) to 1000.0 }

    private fun reachPassEnd() {
        bench.installMap(); bench.connect()
        bench.ble.previewBorderFrontal(RobotBench.ZONE_ID)
        assertNotNull(bench.state.borderFrontal.preview, bench.state.borderFrontal.plan)
        val plan = bench.state.borderFrontal.plan!!
        assertEquals(0.0, plan.contact.y, 1e-9)
        bench.ble.startBorderFrontal(RobotBench.ZONE_ID)
        assertTrue(bench.state.borderFrontal.status, bench.state.borderFrontal.running)
        assertEquals(BorderPhase.POSITIONING, bench.state.borderFrontal.phase)
        bench.until(5 * 60_000, "stop at pass end") { bench.state.borderFrontal.phase == BorderPhase.PASS_END || !bench.state.borderFrontal.running }
        assertEquals(bench.state.borderFrontal.status, BorderPhase.PASS_END, bench.state.borderFrontal.phase)
    }

    @Test fun `pass end reached facing the outline, visual verdict recorded`() {
        wallAt(0.05)
        reachPassEnd()
        val ui = bench.state.borderFrontal
        val plan = ui.plan!!
        // Stops near the point where passes end, robot facing the outline.
        assertEquals(plan.plannedFrontGapM, requireNotNull(ui.passEndFrontGapM), 0.06)
        assertTrue(kotlin.math.abs(plan.lateral(bench.sim.position)) < 0.10)
        assertTrue(kotlin.math.abs(((bench.sim.heading - 270.0 + 540) % 360) - 180) < 12.0)
        bench.advance(3000)
        assertEquals(0.0, bench.sim.speed, 0.005)
        bench.ble.borderPassEndVerdict(true)
        bench.advance(1000)
        assertFalse(bench.state.borderFrontal.running)
        assertEquals("PASS", bench.state.borderFrontal.outcome)
        assertEquals("PASS", bench.state.borderFrontal.results.last().outcome)
        assertEquals(0, bench.sim.contacts)
    }

    @Test fun `advances until wall contact, edge measured without recording an obstacle`() {
        wallAt(0.05)
        reachPassEnd()
        bench.ble.continueBorderToEdge()
        bench.until(2 * 60_000, "contact") { !bench.state.borderFrontal.running }
        val ui = bench.state.borderFrontal
        assertEquals(ui.status, "CONTACT", ui.outcome)
        assertEquals(1, bench.sim.contacts)
        // The bench places the bumper 0.345 m from the published point, i.e. 8 cm closer to the center than the template:
        // the test (bumper 0.345 m from the center) therefore sees the wall 8 cm further away.
        assertEquals(0.05 - DeadReckoning.PUBLISHED_POINT_BEHIND_M, requireNotNull(ui.edgeFrontGapM), 0.04)
        assertTrue(ui.status, ui.status.contains("réserve en bout de passe"))
        assertTrue("No obstacle created on border contact", bench.state.obstacles.isEmpty())
        assertFalse(bench.state.navTest.running)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `without a physical edge, bumper stops 15 cm beyond the outline`() {
        reachPassEnd()
        bench.ble.continueBorderToEdge()
        bench.until(3 * 60_000, "boundary") { !bench.state.borderFrontal.running }
        val ui = bench.state.borderFrontal
        assertEquals(ui.status, "NO_EDGE", ui.outcome)
        assertTrue(requireNotNull(ui.liveFrontGapM) > -0.25)
        assertEquals(0, bench.sim.contacts)
    }

    @Test fun `STOP during positioning halts the movement`() {
        bench.installMap(); bench.connect()
        bench.ble.startBorderFrontal(RobotBench.ZONE_ID)
        bench.until(60_000, "moving") { bench.sim.speed > 0.05 }
        bench.ble.stopBorderFrontal()
        bench.advance(2000)
        assertFalse(bench.state.borderFrontal.running)
        assertFalse(bench.state.navTest.running)
        assertEquals("ABORTED", bench.state.borderFrontal.outcome)
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.01)
    }
}
