package fr.luba.poc.map

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.i18n.tr
import fr.luba.poc.simulation.SmartFeatures
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.weather.WeatherService

/**
 * Switches of the three optional features, shared by all tasks: adaptive speed and return lanes ([SmartFeatures]), rain
 * return ([WeatherService.setRainReturn], also in the weather settings). A change applies to the next route prepared.
 */
@Composable
internal fun SmartFeaturesGroup() {
    val flags by SmartFeatures.flow.collectAsStateWithLifecycle()
    val weather by WeatherService.flow.collectAsStateWithLifecycle()
    SettingsGroup(title = tr("Smart features", "Fonctions intelligentes")) {
        ToggleRow(tr("Adaptive speed", "Vitesse adaptative"), flags.adaptiveSpeed, { SmartFeatures.set(flags.copy(adaptiveSpeed = it)) },
            icon = Icons.Rounded.Speed,
            subtitle = tr("Slower in narrow passages, near Danger borders, in incident areas and with a degraded RTK",
                "Plus lent dans les passages étroits, près des bordures Danger, dans les zones à incidents et avec un RTK dégradé"))
        ToggleRow(tr("Return lanes", "Voies de retour"), flags.returnLanes, { SmartFeatures.set(flags.copy(returnLanes = it)) },
            icon = Icons.Rounded.Home,
            subtitle = tr("Returns to the base alternated between several lanes, against ruts", "Retours à la base alternés entre plusieurs voies, contre les ornières"))
        ToggleRow(tr("Return to base on confirmed rain", "Retour à la base sur pluie confirmée"), weather.rainReturn, WeatherService::setRainReturn,
            icon = Icons.Rounded.Umbrella, enabled = weather.monitoring && weather.location != null,
            subtitle = if (weather.monitoring && weather.location != null) tr("Resumed after 30 min of dry weather, twice per mowing at most",
                "Reprise après 30 min de temps sec, deux fois par tonte au plus")
            else tr("Needs rain monitoring and the garden position (Service → Rain and weather)",
                "Nécessite la surveillance de la pluie et la position du jardin (Service → Pluie et météo)"))
    }
}
