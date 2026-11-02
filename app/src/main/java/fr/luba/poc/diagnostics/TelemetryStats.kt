package fr.luba.poc.diagnostics

import fr.luba.poc.protocol.Telemetry
import kotlin.math.hypot

/**
 * Telemetry quality since the last [reset]: report and position cadence, lowest battery, positioning changes and the
 * largest position drift from the first position. Fed by every accepted robot report, read by the diagnostic campaign
 * (the robot stays still during it, so the drift measures the positioning noise).
 */
class TelemetryStats {
    var reports = 0L; private set
    var positions = 0L; private set
    var reportGaps = TimingStats(); private set
    var positionGaps = TimingStats(); private set
    var batteryMin: Int? = null; private set
    var positionTypeChanges = 0L; private set
    var rtkStatusChanges = 0L; private set
    var maxStationaryDriftM = 0.0; private set
    private var driftOrigin: Pair<Double, Double>? = null

    /** New measurement starting from [start]: its battery is the first minimum, its position the drift origin. */
    fun reset(start: Telemetry) {
        reports = 0; positions = 0
        reportGaps = TimingStats(); positionGaps = TimingStats()
        batteryMin = start.battery
        positionTypeChanges = 0; rtkStatusChanges = 0; maxStationaryDriftM = 0.0
        driftOrigin = if (start.x != null && start.y != null) start.x to start.y else null
    }

    /** New connection: the drift restarts from its first position, the counters keep running. */
    fun forgetDriftOrigin() {
        driftOrigin = null
    }

    /** Accepted report; [gapMs]: time since the previous one, null for the first report of the connection. */
    fun report(gapMs: Long?) {
        reports++
        gapMs?.let(reportGaps::add)
    }

    /** Report carrying a position; [gapMs]: time since the previous position, null for the first one. */
    fun position(gapMs: Long?) {
        positions++
        gapMs?.let(positionGaps::add)
    }

    /** Changes from [previous] to [current]: battery minimum, positioning type and RTK status, drift. */
    fun observe(previous: Telemetry, current: Telemetry) {
        current.battery?.let { batteryMin = minOf(batteryMin ?: it, it) }
        if (previous.positionType != null && current.positionType != previous.positionType) positionTypeChanges++
        if (previous.rtkStatus != null && current.rtkStatus != previous.rtkStatus) rtkStatusChanges++
        val x = current.x ?: return
        val y = current.y ?: return
        val origin = driftOrigin ?: (x to y).also { driftOrigin = it }
        maxStationaryDriftM = maxOf(maxStationaryDriftM, hypot(x - origin.first, y - origin.second))
    }
}
