package fr.luba.poc.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherPillLabelTest {
    @Test fun `short label of the weather pill`() {
        assertEquals("Sec", weatherPillLabel(RainAssessment(RainLevel.DRY, ""), urgent = false))
        assertEquals("Pluie ≈ 12 min", weatherPillLabel(RainAssessment(RainLevel.IMMINENT, "", etaMin = 12), urgent = true))
        assertEquals("Pluie imminente", weatherPillLabel(RainAssessment(RainLevel.IMMINENT, ""), urgent = false))
        assertEquals("Pluie prévue", weatherPillLabel(RainAssessment(RainLevel.DRY, ""), urgent = true))
        assertEquals("Météo —", weatherPillLabel(RainAssessment(RainLevel.UNKNOWN, ""), urgent = false))
        assertEquals("Pluie", weatherPillLabel(RainAssessment(RainLevel.RAIN, ""), urgent = true))
    }
}
