package fr.luba.poc.simulation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Switches of the optional route features, kept between launches ([SmartFeatures]). */
data class SmartFeatureFlags(
    /** [AdaptiveSpeed]: slower in narrow passages, near Danger borders, in incident areas and with a degraded RTK. */
    val adaptiveSpeed: Boolean = true,
    /** [ReturnLanes]: returns to the base alternated between approach lanes. */
    val returnLanes: Boolean = true,
)

/**
 * Switches of the adaptive speed and of the return lanes (Map → Mow → Task settings → Smart features), read by the
 * pilot when it prepares and tracks a route. The gateway does not read its own: the pilot sends the adaptive speed
 * switch with each leg ([fr.luba.poc.autonomy.LegSpec.adaptiveSpeed]). The rain return has its own switch in the
 * weather settings ([fr.luba.poc.weather.WeatherState.rainReturn]). Both features are on until switched off.
 */
object SmartFeatures {
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(SmartFeatureFlags())
    val flow: StateFlow<SmartFeatureFlags> get() = state
    val adaptiveSpeed: Boolean get() = state.value.adaptiveSpeed
    val returnLanes: Boolean get() = state.value.returnLanes

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("smart_features", Context.MODE_PRIVATE).also {
            state.value = SmartFeatureFlags(it.getBoolean(ADAPTIVE_SPEED, true), it.getBoolean(RETURN_LANES, true))
        }
    }

    fun set(flags: SmartFeatureFlags) {
        state.value = flags
        prefs?.edit { putBoolean(ADAPTIVE_SPEED, flags.adaptiveSpeed); putBoolean(RETURN_LANES, flags.returnLanes) }
    }

    private const val ADAPTIVE_SPEED = "adaptive_speed"
    private const val RETURN_LANES = "return_lanes"
}
