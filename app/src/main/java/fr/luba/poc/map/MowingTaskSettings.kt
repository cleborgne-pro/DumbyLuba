package fr.luba.poc.map

import android.content.Context
import fr.luba.poc.protocol.CutHeightRange
import kotlin.math.roundToInt

/**
 * Last settings of the mowing task (Map → Mow → Task settings), restored on the next opening and after an app restart:
 * cutting height, passes of decreasing height ([CutPasses]), task speed, route mode, pass spacing, perimeter and
 * no-go zone loops, border order, recharge thresholds. The outer loop offset has its own saved setting
 * ([OuterOffsetSetting]), and so does the travel speed (`RobotBle`).
 * Values read back are clamped to the slider ranges: an old or corrupted file cannot impose anything else.
 */
data class MowingTaskSettings(
    val heightMm: Float = 60f,
    val speedMps: Float = 0.3f,
    val mode: CoverageMode = CoverageMode.LINES,
    val spacingM: Float = 0.25f,
    val borderPasses: Int = 1,
    val noGoPasses: Int = 1,
    val bordersFirst: Boolean = true,
    val nearbyNoGo: Boolean = true,
    val chargeLow: Float = 20f,
    val chargeHigh: Float = 90f,
    /** Successive passes down to [heightMm] (1 = a single pass), the first one at [firstPassHeightMm]. */
    val heightPasses: Int = 1,
    val firstPassHeightMm: Float = 70f,
) {
    /** Pass heights, from the first to the last ([heightMm]), within the cutting heights of the robot. */
    fun passHeights(range: CutHeightRange = CutHeightRange.STANDARD): List<Int> =
        CutPasses.heights(heightMm.roundToInt(), firstPassHeightMm.roundToInt(), heightPasses, range)

    fun sanitized(): MowingTaskSettings {
        val default = MowingTaskSettings()
        fun Float.within(range: ClosedFloatingPointRange<Float>, fallback: Float) = if (isFinite()) coerceIn(range) else fallback
        val low = chargeLow.within(CHARGE_RANGE.start..MAX_RETURN_PERCENT, default.chargeLow).roundToInt().toFloat()
        val high = chargeHigh.within(CHARGE_RANGE, default.chargeHigh).roundToInt().toFloat()
        val chargeValid = high - low >= MIN_CHARGE_GAP
        val height = heightMm.within(HEIGHT_RANGE, default.heightMm).roundToInt()
        val passes = heightPasses.coerceIn(1, CutPasses.maxPasses(height, CutHeightRange.ANY))
        return copy(
            heightMm = height.toFloat(),
            heightPasses = passes,
            firstPassHeightMm = firstPassHeightMm.within(HEIGHT_RANGE, default.firstPassHeightMm).roundToInt()
                .coerceIn(CutPasses.firstRange(height, passes, CutHeightRange.ANY)).toFloat(),
            speedMps = (speedMps.within(SPEED_RANGE, default.speedMps) * 100).roundToInt() / 100f,
            mode = if (mode in MOWING_MODES) mode else default.mode,
            spacingM = (spacingM.within(SPACING_RANGE, default.spacingM) * 100).roundToInt() / 100f,
            borderPasses = borderPasses.coerceIn(0, MAX_PASSES),
            noGoPasses = noGoPasses.coerceIn(0, MAX_PASSES),
            chargeLow = if (chargeValid) low else default.chargeLow,
            chargeHigh = if (chargeValid) high else default.chargeHigh,
        )
    }

    companion object {
        /** Every model (H versions included): the screen then brings the heights back into the connected robot's range. */
        val HEIGHT_RANGE = CutHeightRange.ANY.floatRange()
        val SPEED_RANGE = 0.2f..0.6f
        val SPACING_RANGE = 0.05f..0.35f
        /** Recharge thresholds: same bounds as the slider (return ≤ 80 %, at least 5 points apart). */
        val CHARGE_RANGE = 10f..100f
        const val MAX_RETURN_PERCENT = 80f
        const val MIN_CHARGE_GAP = 5f
        const val MAX_PASSES = 4
        /** Offered mowing modes; survey routes are not remembered as a mowing mode. */
        val MOWING_MODES = setOf(CoverageMode.LINES, CoverageMode.OPTIMIZED, CoverageMode.CHECKERBOARD)
    }
}

/** Storage of the last mowing settings ("mowing_task" preferences). */
class MowingTaskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mowing_task", Context.MODE_PRIVATE)

    fun read(): MowingTaskSettings {
        val d = MowingTaskSettings()
        return MowingTaskSettings(
            heightMm = prefs.getFloat("height_mm", d.heightMm),
            speedMps = prefs.getFloat("speed_mps", d.speedMps),
            mode = runCatching { CoverageMode.valueOf(prefs.getString("mode", d.mode.name) ?: d.mode.name) }.getOrDefault(d.mode),
            spacingM = prefs.getFloat("spacing_m", d.spacingM),
            borderPasses = prefs.getInt("border_passes", d.borderPasses),
            noGoPasses = prefs.getInt("no_go_passes", d.noGoPasses),
            bordersFirst = prefs.getBoolean("borders_first", d.bordersFirst),
            nearbyNoGo = prefs.getBoolean("nearby_no_go", d.nearbyNoGo),
            chargeLow = prefs.getFloat("charge_low", d.chargeLow),
            chargeHigh = prefs.getFloat("charge_high", d.chargeHigh),
            heightPasses = prefs.getInt("height_passes", d.heightPasses),
            firstPassHeightMm = prefs.getFloat("first_pass_height_mm", d.firstPassHeightMm),
        ).sanitized()
    }

    fun write(settings: MowingTaskSettings) {
        val s = settings.sanitized()
        prefs.edit()
            .putFloat("height_mm", s.heightMm).putFloat("speed_mps", s.speedMps).putString("mode", s.mode.name)
            .putFloat("spacing_m", s.spacingM).putInt("border_passes", s.borderPasses).putInt("no_go_passes", s.noGoPasses)
            .putBoolean("borders_first", s.bordersFirst).putBoolean("nearby_no_go", s.nearbyNoGo)
            .putFloat("charge_low", s.chargeLow).putFloat("charge_high", s.chargeHigh)
            .putInt("height_passes", s.heightPasses).putFloat("first_pass_height_mm", s.firstPassHeightMm)
            .apply()
    }
}
