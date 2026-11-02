@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.core.content.edit
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*

/**
 * Motion gains calibrated per robot (BLE address), reused at the start of the next mission.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class CalibrationStore(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private val state get() = host.state

    /** Motion gains calibrated per robot (BLE address), reused at the start of the next mission. */
    private val calibrationPreferences = host.getApplication<Application>().getSharedPreferences("motion_calibration", 0)
    private var calibrationSavedAt = 0L
    fun saved(): DeadReckoning.Calibration? {
        val id = state.value.selected?.address ?: return null
        val meters = calibrationPreferences.getString("mpu_$id", null)?.toDoubleOrNull() ?: return null
        val degrees = calibrationPreferences.getString("dpu_$id", null)?.toDoubleOrNull() ?: return null
        if (!meters.isFinite() || !degrees.isFinite()) return null
        return DeadReckoning.Calibration(meters, degrees).also {
            event("motion_calibration", tr("Robot calibration reused from the previous mission", "Étalonnage du robot repris de la mission précédente"),
                mapOf("metersPerUnit" to it.metersPerUnit, "degreesPerUnit" to it.degreesPerUnit))
        }
    }
    /** Stores the calibration reached (at most every 30 s, and at the end of a route with [force]). */
    fun save(reckoning: DeadReckoning, force: Boolean = false) {
        val calibration = reckoning.calibration ?: return
        val id = state.value.selected?.address ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - calibrationSavedAt < 30_000) return
        calibrationSavedAt = now
        calibrationPreferences.edit {
            putString("mpu_$id", calibration.metersPerUnit.toString()); putString("dpu_$id", calibration.degreesPerUnit.toString())
        }
    }
    /** Stores the calibration returned by the gateway at the end of autonomous tracking. */
    fun store(calibration: DeadReckoning.Calibration) {
        val id = state.value.selected?.address ?: return
        calibrationSavedAt = SystemClock.elapsedRealtime()
        calibrationPreferences.edit {
            putString("mpu_$id", calibration.metersPerUnit.toString()); putString("dpu_$id", calibration.degreesPerUnit.toString())
        }
    }
}
