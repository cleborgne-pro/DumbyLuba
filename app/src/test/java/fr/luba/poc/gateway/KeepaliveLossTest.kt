package fr.luba.poc.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link keepalive during a Wi-Fi outage of the held gateway (recharge): awaited then dropped, without a failure. */
class KeepaliveLossTest {
    @Test fun onlyKeepalivesAreTolerated() {
        assertTrue(KeepaliveLoss.keepalive(false, "sync(2)"))
        assertTrue(KeepaliveLoss.keepalive(false, "RPT_KEEP"))
        assertTrue(KeepaliveLoss.keepalive(true, null))
        // 2026-10-25, 15:31:31: report subscription of a route start, unconfirmed in 1.5 s through the Redmi.
        assertTrue(KeepaliveLoss.keepalive(false, "ROUTE_REPORTS"))
        assertFalse(KeepaliveLoss.keepalive(false, "STOP linear=0 angular=0"))
        assertFalse(KeepaliveLoss.keepalive(false, "ROUTE_DOCK_ZERO"))
    }

    @Test fun waitsDuringTheOutageAndShortlyAfterTheResume() {
        assertTrue(KeepaliveLoss.waiting(true, started = 1000, now = 9000, outageSince = 2000, resumedAt = 0))
        assertTrue(KeepaliveLoss.waiting(true, started = 1000, now = 9500, outageSince = 0, resumedAt = 8000))
        assertFalse(KeepaliveLoss.waiting(true, started = 1000, now = 11_500, outageSince = 0, resumedAt = 8000))
        // Write sent after the resume: strict timeout.
        assertFalse(KeepaliveLoss.waiting(true, started = 9000, now = 9500, outageSince = 0, resumedAt = 8000))
        assertFalse(KeepaliveLoss.waiting(false, started = 1000, now = 9000, outageSince = 2000, resumedAt = 0))
    }

    @Test fun dropsOnlyAKeepaliveCaughtByAResume() {
        assertTrue(KeepaliveLoss.dropped(true, started = 1000, resumedAt = 8000))
        assertFalse(KeepaliveLoss.dropped(true, started = 9000, resumedAt = 8000))
        assertFalse(KeepaliveLoss.dropped(true, started = 1000, resumedAt = 0))
        assertFalse(KeepaliveLoss.dropped(false, started = 1000, resumedAt = 8000))
    }

    @Test fun keepaliveThroughTheGatewayToleratesWifiDelayDuringAMission() {
        // 2026-10-16: sync(2) during the recharge confirmed in 1.8 s by the gateway, wrongly a "GATT timeout" at 1.5 s.
        assertEquals(KeepaliveLoss.GATEWAY_KEEPALIVE_MS, KeepaliveLoss.timeoutMs(viaGateway = true, keepalive = true, operatorDriving = false, missionRunning = true))
        assertTrue(KeepaliveLoss.timeoutMs(true, true, false, true) > 1800)
        assertEquals(KeepaliveLoss.GATEWAY_KEEPALIVE_MS, KeepaliveLoss.timeoutMs(true, true, false, false))
    }

    @Test fun safetyWritesKeepTheStrictDelay() {
        // Setpoint, STOP, blades during a mission through the gateway, joystick, direct link: 1.5 s.
        assertEquals(KeepaliveLoss.STRICT_MS, KeepaliveLoss.timeoutMs(viaGateway = true, keepalive = false, operatorDriving = false, missionRunning = true))
        assertEquals(KeepaliveLoss.STRICT_MS, KeepaliveLoss.timeoutMs(true, true, operatorDriving = true, missionRunning = false))
        assertEquals(KeepaliveLoss.STRICT_MS, KeepaliveLoss.timeoutMs(viaGateway = false, keepalive = true, operatorDriving = false, missionRunning = true))
        assertEquals(KeepaliveLoss.STRICT_MS, KeepaliveLoss.timeoutMs(false, false, false, false))
        // Gateway without a mission: 3 s.
        assertEquals(KeepaliveLoss.GATEWAY_IDLE_MS, KeepaliveLoss.timeoutMs(true, false, false, false))
    }
}
