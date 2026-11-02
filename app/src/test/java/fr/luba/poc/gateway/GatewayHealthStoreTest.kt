package fr.luba.poc.gateway

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Gateway health and reachability seen from the pilot: freshness, loss, weak Wi-Fi reminder. */
class GatewayHealthStoreTest {
    private val t0 = 1_760_000_000_000L

    @After fun reset() { GatewayHealthStore.update(GatewayHealth(wifiRssiDbm = -50), t0); GatewayHealthStore.clear() }

    @Test fun `health frame received, gateway reached and state fresh for one minute`() {
        GatewayHealthStore.clear()
        assertNull(GatewayHealthStore.latest.value); assertNull(GatewayHealthStore.reach.value)
        val health = GatewayHealth(batteryPercent = 64, wifiRssiDbm = -58)
        GatewayHealthStore.update(health, t0)
        val received = GatewayHealthStore.latest.value!!
        assertEquals(health, received.health)
        assertEquals(GatewayReach(true, t0), GatewayHealthStore.reach.value)
        assertTrue(received.fresh(t0 + ReceivedHealth.STALE_MS))
        assertFalse(received.fresh(t0 + ReceivedHealth.STALE_MS + 1))
        // Phone clock slightly behind: tolerated up to 2 s.
        assertTrue(received.fresh(t0 - 2_000)); assertFalse(received.fresh(t0 - 2_001))
        assertFalse(GatewayHealthStore.reach.value!!.fresh(t0 + 61_000))
    }

    @Test fun `gateway unreachable, health state forgotten, cause kept`() {
        GatewayHealthStore.update(GatewayHealth(batteryPercent = 50), t0)
        GatewayHealthStore.unreachable("délai dépassé", t0 + 5_000)
        assertNull(GatewayHealthStore.latest.value)
        assertEquals(GatewayReach(false, t0 + 5_000, "délai dépassé"), GatewayHealthStore.reach.value)
        // Successful search (code accepted) without a health frame: reachable again.
        GatewayHealthStore.reached(t0 + 10_000)
        assertEquals(true, GatewayHealthStore.reach.value?.reachable)
        GatewayHealthStore.clear()
        assertNull(GatewayHealthStore.reach.value)
    }

    @Test fun `weak Wi-Fi recalled after the loss, with its age, for 15 minutes`() {
        GatewayHealthStore.update(GatewayHealth(wifiRssiDbm = -89), t0)
        // Next frame without a Wi-Fi measurement: the last known level stays.
        GatewayHealthStore.update(GatewayHealth(wifiRssiDbm = -1000), t0 + 1_000)
        GatewayHealthStore.unreachable("muette", t0 + 2_000)
        assertEquals(" · Wi-Fi de la passerelle -89 dBm : trop faible, rapprocher le robot du point d'accès ou ajouter un répéteur",
            GatewayHealthStore.weakWifiHint(t0 + 30_000))
        assertTrue(GatewayHealthStore.weakWifiHint(t0 + 2 * 60_000 + 5).contains("-89 dBm (il y a 2 min)"))
        assertEquals("", GatewayHealthStore.weakWifiHint(t0 + GatewayHealthStore.WIFI_HINT_MS + 1))
        // Wi-Fi fine: nothing to say.
        GatewayHealthStore.update(GatewayHealth(wifiRssiDbm = -69), t0 + 3_000)
        assertEquals("", GatewayHealthStore.weakWifiHint(t0 + 4_000))
        assertEquals("", GatewayHealthStore.wifiHint(-128, 0))
        assertTrue(GatewayHealthStore.wifiHint(-70, 0).isNotEmpty())
    }

    /** Fold test of 2026-11-02: the ESP32 got −70 dBm where the robot got −56 dBm: its antenna, not the distance. */
    @Test fun `weak gateway Wi-Fi where the robot receives well points to the gateway antenna`() {
        val far = GatewayHealthStore.wifiHint(-80, 0, robotDbm = -75)
        assertTrue(far, far.contains("rapprocher le robot du point d'accès"))
        val antenna = GatewayHealthStore.wifiHint(-70, 0, robotDbm = -56)
        assertTrue(antenna, antenna.contains("le robot capte -56 dBm") && antenna.contains("antenne de la passerelle"))
        GatewayHealthStore.update(GatewayHealth(wifiRssiDbm = -72), t0)
        GatewayHealthStore.robotWifi(-58, t0 + 10_000)
        assertTrue(GatewayHealthStore.weakWifiHint(t0 + 20_000).contains("antenne"))
        // Robot level read far from the gateway's (another place): the usual advice.
        GatewayHealthStore.robotWifi(-58, t0 + 120_000)
        assertTrue(GatewayHealthStore.weakWifiHint(t0 + 130_000).contains("point d'accès"))
    }
}
