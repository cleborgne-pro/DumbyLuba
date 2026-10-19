package fr.luba.poc.alerts

import fr.luba.poc.protocol.Telemetry

/**
 * Inputs of the "Prolonged RTK loss" alert ([AlertMonitor.tick]). Up to 0.55.0 the FIX was read from `pos_level = 0`
 * whereas the robot sends `rtk.status = 4` and `pos_level = 1` with a full FIX, and monitoring ran from connection:
 * the critical alert fired while the robot sat still on its base.
 */
object RtkAlertPolicy {
    /** Maximum age of the last RTK report for its FIX to count. */
    const val FRESH_MS = 5_000L
    /** Monitoring active: robot linked, moving (route, mowing, manual control, test), healthy link. */
    fun watched(ready: Boolean, moving: Boolean, linkFault: Boolean) = ready && moving && !linkFault
    /** Fresh FIX: last `rtk.status` = FIX, received at most [FRESH_MS] ago (monotonic clock). */
    fun freshFix(telemetry: Telemetry, rtkReportAt: Long?, now: Long) =
        telemetry.rtkFix && rtkReportAt?.let { now - it in 0..FRESH_MS } == true
}
