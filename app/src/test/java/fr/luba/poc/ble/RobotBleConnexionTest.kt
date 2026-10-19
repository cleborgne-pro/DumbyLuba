package fr.luba.poc.ble

import fr.luba.poc.map.Point
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Connection to the simulated robot: link ready, report subscription, telemetry displayed, loss and reconnection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleConnexionTest {
    private val bench = RobotBench(Point(5.0, 4.0), 30.0)
    @After fun tearDown() = bench.close()

    @Test fun `connection up to displayed telemetry`() {
        val s = bench.state
        assertEquals(ConnectionState.Disconnected, s.connection)
        bench.ble.connect(bench.device)
        assertEquals(ConnectionState.Connecting, bench.state.connection)
        assertEquals(bench.device, bench.state.selected)
        bench.until(5000, "link ready") { bench.state.ready }
        bench.until(5000, "telemetry") { bench.state.connection == ConnectionState.Receiving }
        bench.until(3000, "position") { bench.state.telemetry.x != null }
        val t = bench.state.telemetry
        assertEquals(80, t.battery)
        assertEquals(11, t.robotState)
        assertEquals(4, t.rtkStatus)
        assertEquals(5.0 - 0.08 * kotlin.math.cos(Math.toRadians(30.0)), t.x!!, 0.05)
        assertEquals(30.0, t.heading!!, 5.0)
        assertEquals(512, bench.state.mtu)
        // Ready: sync, report subscription and error read sent to the robot.
        val sys = bench.sim.received.map { it.second }
        assertTrue(sys.any { it.hasNet() && it.net.todevBleSync == 2 })
        assertTrue(sys.any { it.hasSys() && it.sys.hasTodevReportCfg() })
        assertTrue(sys.any { it.hasSys() && it.sys.hasBidireCommCmd() })
        assertTrue("No movement command on connection", bench.sim.trace.none { it.second.startsWith("move") })
        assertTrue(bench.link.protocolErrors.isEmpty())
        // Maintenance: RSSI read, reports renewed.
        bench.advance(10_000)
        assertEquals(-55, bench.state.rssi)
        assertTrue(bench.state.commandsSent > 3)
        assertNotNull(bench.state.lastKnown)
    }
}
