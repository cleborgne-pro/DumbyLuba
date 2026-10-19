package fr.luba.poc.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outage cause quoted in the pilot log (tests of 2026-10-14: the Fold's Wi-Fi was disabled, not the gateway). */
class PhoneWifiTest {
    @Test fun `disabled or disconnected wifi names the pilot phone`() {
        assertTrue(PhoneWifi.describe(false, false, 0).startsWith("Wi-Fi du téléphone désactivé"))
        assertTrue(PhoneWifi.describe(true, false, 0).startsWith("Wi-Fi du téléphone déconnecté"))
        assertEquals("Wi-Fi du téléphone connecté", PhoneWifi.describe(true, true, 0))
        assertEquals("état du Wi-Fi du téléphone inconnu", PhoneWifi.describe(null, null, 0))
        assertTrue(PhoneWifi.describe(true, false, 1_791_967_989_000).contains("depuis"))
    }
}
