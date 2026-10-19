package fr.luba.poc.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLayerChoiceTest {
    @Test fun roundTripsEveryChoice() {
        SurveySignal.entries.forEach { signal ->
            val choice = MapLayerChoice(signal, heatVisible = false, layersOpen = false, reliefShading = false)
            assertEquals(choice, MapLayerChoice.decode(choice.encode()))
        }
        val relief = MapLayerChoice(SurveySignal.RELIEF, heatVisible = true, layersOpen = true, reliefShading = false)
        assertEquals(relief, MapLayerChoice.decode(relief.encode()))
    }

    @Test fun missingOrUnknownValuesFallBackFieldByField() {
        assertEquals(MapLayerChoice(), MapLayerChoice.decode(null))
        assertEquals(MapLayerChoice(), MapLayerChoice.decode(""))
        assertEquals(MapLayerChoice(heatVisible = false), MapLayerChoice.decode("DISPARU;false"))
    }
}
