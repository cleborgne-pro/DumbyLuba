package fr.luba.poc.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLayerBarTest {
    private fun sample(wifi: Int? = null, ble: Int? = null, mobile: Int? = null, rtk: Int? = null, sats: Int? = null) =
        SignalSample(Point(0.0, 0.0), 0L, wifi, ble, mobile, rtk, sats)

    @Test fun `only measured layers are offered`() {
        val samples = listOf(sample(wifi = -60, rtk = 0, sats = 0), sample(ble = 0, mobile = null))
        // Zero RSSI = unknown (BLE missing); RTK and satellites at 0 are still readings.
        assertEquals(listOf(SurveySignal.WIFI, SurveySignal.RTK, SurveySignal.GPS), availableLayers(samples, terrain = false, current = SurveySignal.WIFI))
    }

    @Test fun `terrain offered only if the terrain can be computed`() {
        val samples = listOf(sample(wifi = -50))
        assertEquals(listOf(SurveySignal.WIFI, SurveySignal.SLOPE, SurveySignal.RELIEF), availableLayers(samples, terrain = true, current = SurveySignal.WIFI))
    }

    @Test fun `displayed layer stays offered even without readings`() {
        val samples = listOf(sample(wifi = -50))
        assertEquals(listOf(SurveySignal.WIFI, SurveySignal.MOBILE), availableLayers(samples, terrain = false, current = SurveySignal.MOBILE))
    }

    @Test fun `short layer labels`() {
        assertEquals(listOf("Wi-Fi", "RTK", "BLE", "Mobile", "Satellites", "Pente", "Relief"), SurveySignal.entries.map { it.shortLabel() })
    }
}
