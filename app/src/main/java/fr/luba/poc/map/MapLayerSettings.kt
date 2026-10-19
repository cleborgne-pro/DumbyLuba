package fr.luba.poc.map

import android.content.Context

/**
 * Map layer choice: heatmap layer (Wi-Fi, RTK, Slope, Terrain…), heatmap shown or hidden, layer bar open or folded,
 * terrain shading. Kept until the user changes it, between two openings of the map as between two launches (request
 * of 2026-10-15: the choice kept going back to Wi-Fi shown).
 */
data class MapLayerChoice(
    val signal: SurveySignal = SurveySignal.WIFI,
    val heatVisible: Boolean = true,
    val layersOpen: Boolean = true,
    val reliefShading: Boolean = true,
) {
    fun encode(): String = listOf(signal.name, heatVisible, layersOpen, reliefShading).joinToString(";")

    companion object {
        /** Missing or unreadable value (removed layer…): default setting field by field. */
        fun decode(raw: String?): MapLayerChoice {
            val parts = raw?.split(";").orEmpty()
            val default = MapLayerChoice()
            fun flag(i: Int, fallback: Boolean) = when (parts.getOrNull(i)) { "true" -> true; "false" -> false; else -> fallback }
            return MapLayerChoice(
                signal = SurveySignal.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: default.signal,
                heatVisible = flag(1, default.heatVisible),
                layersOpen = flag(2, default.layersOpen),
                reliefShading = flag(3, default.reliefShading),
            )
        }
    }
}

object MapLayerSettings {
    private const val KEY = "heatmap_choice"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("map_layers", Context.MODE_PRIVATE)
    fun read(context: Context): MapLayerChoice = MapLayerChoice.decode(prefs(context).getString(KEY, null))
    /** Writes only a choice different from the saved one. */
    fun save(context: Context, choice: MapLayerChoice) {
        val p = prefs(context)
        if (p.getString(KEY, null) != choice.encode()) p.edit().putString(KEY, choice.encode()).apply()
    }
}
