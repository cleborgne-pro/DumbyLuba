package fr.luba.poc.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Margin between the side of the robot and the recorded boundary for an outer loop, kept between two launches. The
 * recorded boundary is the outer side of the robot: its center passes half a width further in.
 * [label] is evaluated at display time (interface language).
 */
open class OffsetSetting(val defaultM: Double, private val key: String, private val label: () -> String) {
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow(defaultM)
    val flow: StateFlow<Double> get() = state

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("planner", Context.MODE_PRIVATE).also {
            state.value = sanitize(it.getFloat(key, defaultM.toFloat()).toDouble())
        }
    }
    val meters: Double get() = state.value
    fun set(value: Double) {
        val clean = sanitize(value)
        state.value = clean
        prefs?.edit()?.putFloat(key, clean.toFloat())?.apply()
    }
    fun sanitize(value: Double): Double =
        if (!value.isFinite()) defaultM else ((value * 100).roundToInt() / 100.0).coerceIn(MIN_M, CoveragePlanner.MAX_OUTER_OFFSET_M)

    /** Self-contained slider: adds neither state nor computation to the map screen. */
    @Composable
    fun Slider(enabled: Boolean) {
        val value by state.collectAsState()
        fr.luba.poc.ui.components.SliderRow(label(), value.toFloat(), { set(it.toDouble()) },
            MIN_M.toFloat()..CoveragePlanner.MAX_OUTER_OFFSET_M.toFloat(),
            valueLabel = tr("${(value * 100).roundToInt()} cm from the edge", "${(value * 100).roundToInt()} cm entre le robot et la limite"), steps = 18, enabled = enabled)
    }

    companion object { const val MIN_M = 0.05 }
}

/**
 * Mowing: 5 cm between the side of the robot and the boundary (center at ≈ 31 cm). Up to 0.39, the setting (25 cm)
 * measured the distance of the center: new key, the old value no longer has the same meaning.
 */
object OuterOffsetSetting : OffsetSetting(0.05, "outer_clearance_m", { tr("Outer margin", "Marge du tour extérieur") }) {
    const val DEFAULT_M = 0.05
    const val MIN_M = OffsetSetting.MIN_M
}

/** Signal survey: outer loop shifted inwards so as not to brush the edges and to drive fast. */
object SurveyOffsetSetting : OffsetSetting(0.10, "survey_outer_clearance_m", { tr("Edge margin", "Marge du tour depuis les bords") }) {
    const val DEFAULT_M = 0.10
}
