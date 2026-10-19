package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Telemetry

/** Precondition of a cutting test, shown live in the Blades panel. */
data class BladeCheck(val label: String, val ok: Boolean, val detail: String)

/** Guard of a supervised manual test. Never used to bypass the firmware. */
object BladeSafety {
    /** Screen cleanup does not own the blades of a real mission. */
    fun shouldStopTestOnExit(routeRunning: Boolean, testActive: Boolean, testId: String): Boolean =
        !routeRunning && (testActive || testId.isNotBlank())

    /** All preconditions, in [requireReady] order: the first failing one is the refusal reason. */
    fun checks(name: String?, stateAgeMs: Long?, battery: Int?, robotState: Int?,
               sensors: Int?, selfCheck: Long?, campaignRunning: Boolean, heightMm: Int): List<BladeCheck> = listOf(
        BladeCheck("Robot LUBA 2", name?.startsWith("Luba-VS", ignoreCase = true) == true, tr("Test reserved for a LUBA 2 identified as Luba-VS", "Test réservé au LUBA 2 identifié Luba-VS")),
        BladeCheck(tr("BLE diagnostics stopped", "Diagnostic BLE arrêté"), !campaignRunning, tr("Stop the BLE campaign before this test", "Arrêter la campagne BLE avant ce test")),
        BladeCheck(tr("Height 25–70 mm", "Hauteur 25–70 mm"), heightMm in 25..70, tr("Height outside the 25–70 mm range", "Hauteur hors plage 25–70 mm")),
        BladeCheck(tr("Fresh robot state (≤ 2 s)", "État robot frais (≤ 2 s)"), stateAgeMs != null && stateAgeMs in 0..2000, tr("Robot state missing or older than 2 s", "État robot absent ou âgé de plus de 2 s")),
        BladeCheck(tr("Battery > 30 %", "Batterie > 30 %"), battery != null && battery > 30, tr("Battery unknown or ≤30 %", "Batterie inconnue ou ≤30 %")),
        BladeCheck(tr("Robot ready (state 11)", "Robot prêt (état 11)"), robotState == 11, tr("Robot not ready (MODE_READY=11 expected)", "Robot non prêt (MODE_READY=11 attendu)")),
        BladeCheck(tr("No protection active", "Aucune protection active"), fr.luba.poc.protocol.SelfCheck.nominal(selfCheck), fr.luba.poc.protocol.SelfCheck.refusal(selfCheck)),
        BladeCheck(tr("Bumper free, blades OFF", "Bumper libre, lames OFF"), sensors != null && sensors and 7 == 0 && (sensors shr 9) and 7 == 0, tr("Bumper not free or blades not confirmed OFF", "Bumper non libre ou lames non confirmées OFF")),
    )

    fun requireReady(name: String?, stateAgeMs: Long?, battery: Int?, robotState: Int?,
        sensors: Int?, selfCheck: Long?, campaignRunning: Boolean, heightMm: Int) {
        checks(name, stateAgeMs, battery, robotState, sensors, selfCheck, campaignRunning, heightMm)
            .firstOrNull { !it.ok }?.let { throw IllegalArgumentException(it.detail) }
    }
}

/** Height adjustment test sequences: a chain of setpoints, each confirmed by the robot. */
object HeightSequence {
    const val MIN_MM = 25
    const val MAX_MM = 70

    /** Round trip of [deltaMm] from the current height (downwards if going up leaves the range). */
    fun roundTrip(currentMm: Int, deltaMm: Int = 10): List<Int> {
        require(currentMm in MIN_MM..MAX_MM && deltaMm in 1..(MAX_MM - MIN_MM))
        val away = if (currentMm + deltaMm <= MAX_MM) currentMm + deltaMm else currentMm - deltaMm
        return listOf(away.coerceIn(MIN_MM, MAX_MM), currentMm)
    }

    /** Full travel: minimum, maximum, then back to the starting height. */
    fun extremes(currentMm: Int): List<Int> {
        require(currentMm in MIN_MM..MAX_MM)
        val order = if (currentMm - MIN_MM <= MAX_MM - currentMm) listOf(MIN_MM, MAX_MM) else listOf(MAX_MM, MIN_MM)
        return (order + currentMm).fold(mutableListOf<Int>()) { out, mm -> if (out.lastOrNull() != mm) out.add(mm); out }
            .let { if (it.first() == currentMm) it.drop(1) else it }
    }

    /**
     * Height [targetMm] reported by a message received after [sinceWallMs]: end of motor movement
     * (`toapp_knife_status_change`, `bidire_knife_height_report`) or current height of `report.work`.
     * A target that is only announced (movement in progress) is not a reached height.
     */
    fun reached(t: Telemetry, targetMm: Int, sinceWallMs: Long): Boolean {
        val driver = (t.lastDriverHeightAt ?: 0) > sinceWallMs && t.driverHeightMm == targetMm && t.heightMoving != true
        val work = (t.lastWorkHeightAt ?: 0) > sinceWallMs && t.workHeightMm == targetMm &&
            (t.heightMoving != true || t.heightTargetMm == targetMm)
        return driver || work
    }

    /** Height reported after [sinceWallMs] that differs from [targetMm], or movement towards another target. */
    fun departed(t: Telemetry, targetMm: Int, sinceWallMs: Long): Int? = when {
        t.heightMoving == true && t.heightTargetMm != null && t.heightTargetMm != targetMm && (t.lastDriverHeightAt ?: 0) > sinceWallMs -> t.heightTargetMm
        (t.lastWorkHeightAt ?: 0) > sinceWallMs && t.workHeightMm != null && t.workHeightMm != targetMm -> t.workHeightMm
        else -> null
    }

    /** Timeout for the robot's feedback: the height motor travels about 1 mm/s. */
    fun timeoutMs(fromMm: Int?, toMm: Int): Long =
        if (fromMm == null) 60_000 else (20_000L + kotlin.math.abs(toMm - fromMm) * 1_000L).coerceIn(30_000L, 90_000L)
}
