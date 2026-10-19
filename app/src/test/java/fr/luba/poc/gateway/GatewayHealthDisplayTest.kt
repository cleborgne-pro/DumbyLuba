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
}
