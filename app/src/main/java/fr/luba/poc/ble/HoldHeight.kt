package fr.luba.poc.ble

import fr.luba.poc.protocol.CutHeightRange
import fr.luba.poc.protocol.Telemetry

/**
 * Cutting height carried by a manual-mode command that is not meant to change it (DumbyLuba#2).
 *
 * `mow_ctrl_by_hand` always carries `cut_knife_height`, and the firmware applies it in manual mowing mode (state 20).
 * The joystick, the slow approach, the stationary bumper test, their exits from manual mode, the STOP and the moves with
 * blades stopped (move to a point, obstacle bypass, frontal border test, map sync) only use that mode so that the robot
 * publishes its bumper contact. Until 2026-10-27 they sent the last height requested by the app in this session, 60 mm
 * after a connection: a LUBA 3 whose deck sat elsewhere moved it at the first joystick gesture.
 *
 * They now send the height the robot reports, so the deck stays where it is. Fallback when it is unknown or outside the
 * range of the connected robot: the last height explicitly requested in this session (height setting, blade test,
 * route, cutting test), else 60 mm, brought into that range. While a height setting owns the deck ([adjusting]: height
 * adjustment, blade test, route or navigation test), its target stays the one sent.
 */
object HoldHeight {
    fun mm(telemetry: Telemetry, requestedMm: Int, range: CutHeightRange, adjusting: Boolean = false): Int {
        val held = if (adjusting) requestedMm else telemetry.bladeHeightMm?.takeIf { it in range } ?: range.clamp(requestedMm)
        // Protocol bound of `Commands.manualMow`: never an exception, whatever the model or the report.
        return CutHeightRange.ANY.clamp(held)
    }
}
