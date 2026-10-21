package fr.luba.poc.gateway

import fr.luba.poc.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android reports -127 dBm when the Wi-Fi signal is unknown: it must not be shown as a measurement. */
class GatewayHealthDisplayTest {
    @After fun restore() { I18n.override = null }

    @Test fun unknownWifiSignalIsNotDisplayed() {
        assertFalse(GatewayHealth(wifiRssiDbm = -127).wifiKnown)
        assertFalse(GatewayHealth().wifiKnown)
        assertTrue(GatewayHealth(wifiRssiDbm = -65).wifiKnown)
        I18n.override = "en"
        assertEquals("78 % · Wi-Fi -65 dBm", GatewayHealth(batteryPercent = 78, wifiRssiDbm = -65).summary())
        assertEquals("78 %", GatewayHealth(batteryPercent = 78, wifiRssiDbm = -127).summary())
    }

    @Test fun wifiQualityUsesTheWarningLimit() {
        assertEquals(SignalQuality.EXCELLENT, SignalQuality.of(-50))
        assertEquals(SignalQuality.GOOD, SignalQuality.of(-63))
        assertEquals(SignalQuality.FAIR, SignalQuality.of(-69))
        // −70 dBm: the same value raises the "Wi-Fi weak" warning.
        assertEquals(SignalQuality.WEAK, SignalQuality.of(-70))
        assertTrue(GatewayHealth(wifiRssiDbm = -70).warnings().any { "Wi-Fi" in it })
        assertEquals(SignalQuality.UNKNOWN, SignalQuality.of(-127))
        assertEquals(listOf(4, 3, 2, 1, 0), SignalQuality.entries.map { it.bars })
    }

    @Test fun healthTextsOfTheTilesAndTheStrip() {
        I18n.override = "fr"
        val h = GatewayHealth(batteryPercent = 93, batteryTempDeciC = 186, wifiRssiDbm = -72, wifiMbps = 65, thermalStatus = 0, powerSave = true)
        assertEquals("93 %", GatewayHealthText.battery(h))
        assertEquals("Wi-Fi · faible", GatewayHealthText.wifiLabel(h))
        assertEquals("65 Mb/s", GatewayHealthText.rate(h))
        assertEquals("18,6 °C", GatewayHealthText.temperature(h))
        assertEquals("19 °C", GatewayHealthText.temperature(h, decimals = false))
        assertEquals("normal", GatewayHealthText.thermal(h))
        assertEquals("Wi-Fi de la passerelle faible (-72 dBm) (+1 autre)", GatewayHealthText.warning(h))
        assertEquals(null, GatewayHealthText.warning(GatewayHealth(batteryPercent = 80, wifiRssiDbm = -50)))
        assertEquals("—", GatewayHealthText.battery(GatewayHealth()))
        assertEquals("Batterie · branchée", GatewayHealthText.batteryLabel(GatewayHealth(batteryPercent = 40, charging = true)))
    }
}
