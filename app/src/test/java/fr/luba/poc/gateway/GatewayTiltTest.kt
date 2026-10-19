package fr.luba.poc.gateway

import fr.luba.poc.map.PhoneGravity
import org.junit.Assert.*
import org.junit.Test

class GatewayTiltTest {
    @Test fun tiltTravelsAtTheEndOfTheHealthFrameAndOldFramesStillDecode() {
        val health = GatewayHealth(batteryPercent = 80, charging = true, wifiRssiDbm = -55, screenOn = true)
        val plain = health.encode()
        assertEquals(36, plain.size)
        assertNull(GatewayHealth.decode(plain)!!.gravity)
        val tilted = health.copy(gravity = PhoneGravity(0.512, -1.25, 9.731), gravityAgeMs = 120).encode()
        assertEquals(52, tilted.size)
        // The first 36 bytes are the ones an older pilot (or the ESP32) knows.
        assertArrayEquals(plain, tilted.copyOf(36))
        val decoded = GatewayHealth.decode(tilted)!!
        assertEquals(0.512, decoded.gravity!!.x, 1e-9); assertEquals(-1.25, decoded.gravity!!.y, 1e-9); assertEquals(9.731, decoded.gravity!!.z, 1e-9)
        assertEquals(120, decoded.gravityAgeMs)
        assertEquals(health, decoded.copy(gravity = null, gravityAgeMs = 0))
    }

    @Test fun gravityFilterSmoothsVibrationsAndForgetsStaleReadings() {
        val filter = GravityFilter(tauMs = 1000.0, maxAgeMs = 2000)
        assertNull(filter.current(0))
        filter.add(0.0, 0.0, 9.8, 0)
        for (t in 1..200) filter.add(if (t % 2 == 0) 2.0 else -2.0, 0.0, 9.8, t * 10L)
        val (g, age) = filter.current(2000)!!
        assertTrue(kotlin.math.abs(g.x) < 0.2); assertEquals(9.8, g.z, 1e-6); assertEquals(0, age)
        assertNull(filter.current(4001))
        filter.add(1.0, 0.0, 9.7, 10_000)
        assertEquals(1.0, filter.current(10_000)!!.first.x, 1e-9)
    }
}
