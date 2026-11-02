package fr.luba.poc.diagnostics

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryStatsTest {
    @Test fun `counts reports and positions with their gaps`() {
        val stats = TelemetryStats()
        stats.report(null)
        stats.report(200)
        stats.report(400)
        stats.position(null)
        stats.position(1000)

        assertEquals(3, stats.reports)
        assertEquals(2, stats.reportGaps.count)
        assertEquals(400, stats.reportGaps.maxMs)
        assertEquals(2, stats.positions)
        assertEquals(1, stats.positionGaps.count)
    }

    @Test fun `tracks the lowest battery and the positioning changes`() {
        val stats = TelemetryStats()
        stats.reset(Telemetry(battery = 80))
        stats.observe(Telemetry(battery = 80, positionType = 1, rtkStatus = 4), Telemetry(battery = 78, positionType = 2, rtkStatus = 4))
        stats.observe(Telemetry(battery = 78, positionType = 2, rtkStatus = 4), Telemetry(battery = 79, positionType = 2, rtkStatus = 1))
        // Unknown before: not a change.
        stats.observe(Telemetry(), Telemetry(positionType = 5, rtkStatus = 5))

        assertEquals(78, stats.batteryMin)
        assertEquals(1, stats.positionTypeChanges)
        assertEquals(1, stats.rtkStatusChanges)
    }

    @Test fun `drift is measured from the start position, or the first one received`() {
        val stats = TelemetryStats()
        stats.reset(Telemetry(x = 1.0, y = 1.0))
        stats.observe(Telemetry(), Telemetry(x = 1.3, y = 1.4))
        assertEquals(0.5, stats.maxStationaryDriftM, 1e-9)

        stats.forgetDriftOrigin()
        stats.observe(Telemetry(), Telemetry(x = 5.0, y = 5.0))
        stats.observe(Telemetry(), Telemetry(x = 5.0, y = 5.1))
        // The new origin is (5, 5): its 0.1 m drift does not lower the maximum.
        assertEquals(0.5, stats.maxStationaryDriftM, 1e-9)
        stats.observe(Telemetry(), Telemetry(x = 5.0, y = 6.0))
        assertEquals(1.0, stats.maxStationaryDriftM, 1e-9)
    }

    @Test fun `reset starts a new measurement`() {
        val stats = TelemetryStats()
        stats.report(100)
        stats.position(100)
        stats.observe(Telemetry(positionType = 1), Telemetry(battery = 20, positionType = 2, x = 0.0, y = 0.0))
        stats.reset(Telemetry())

        assertEquals(0, stats.reports)
        assertEquals(0, stats.reportGaps.count)
        assertEquals(0, stats.positions)
        assertNull(stats.batteryMin)
        assertEquals(0, stats.positionTypeChanges)
        assertEquals(0.0, stats.maxStationaryDriftM, 0.0)
    }
}
