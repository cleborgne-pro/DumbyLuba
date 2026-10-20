package fr.luba.poc.gateway

import fr.luba.poc.BuildConfig
import org.junit.Assert.*
import org.junit.Test

/** Gateway naming on the Wi-Fi, app version announced in WELCOME and version messages. */
class GatewayDiscoveryTextTest {
    @Test fun `mDNS name keeps the common prefix and adds the phone model, at most 63 bytes`() {
        assertEquals("DumbyLuba passerelle · Redmi A1", GatewayProtocol.serviceName("Redmi A1"))
        assertEquals(GatewayProtocol.SERVICE_NAME, GatewayProtocol.serviceName(null))
        assertEquals(GatewayProtocol.SERVICE_NAME, GatewayProtocol.serviceName("  "))
        val long = GatewayProtocol.serviceName("X".repeat(80))
        assertTrue(long.startsWith(GatewayProtocol.SERVICE_NAME))
        assertTrue(long.toByteArray().size <= 63)
        // Characters that are not allowed in a DNS-SD label are dropped.
        assertEquals("DumbyLuba passerelle · Pixel 8", GatewayProtocol.serviceName("Pixel 8\n"))
    }

    @Test fun `announced gateway shown by model, ESP32 recognized, older name shown by address`() {
        assertEquals("Redmi A1", GatewayProtocol.displayName("DumbyLuba passerelle · Redmi A1", "192.168.0.10"))
        assertEquals("Redmi A1", GatewayProtocol.displayName("DumbyLuba passerelle · Redmi A1 (2)", "192.168.0.10"))
        assertEquals("ESP32", GatewayProtocol.displayName("DumbyLuba passerelle ESP32", "192.168.0.11"))
        assertEquals("192.168.0.12", GatewayProtocol.displayName("DumbyLuba passerelle", "192.168.0.12"))
    }

    @Test fun `app version announced after the capabilities, ignored by the capability parser`() {
        val welcome = GatewayProtocol.Frame(GatewayProtocol.WELCOME, GatewayProtocol.welcome())
        assertEquals(BuildConfig.VERSION_NAME, GatewayProtocol.appVersion(welcome))
        assertEquals(setOf(GatewayProtocol.CAP_AUTONOMY, GatewayProtocol.CAP_DOCK_EXIT), GatewayProtocol.capabilities(welcome))
        // Older gateway or ESP32: no version line.
        val esp = GatewayProtocol.Frame(GatewayProtocol.WELCOME, "DumbyLuba passerelle\ncaps=ota".toByteArray())
        assertNull(GatewayProtocol.appVersion(esp))
        assertEquals(setOf("ota"), GatewayProtocol.capabilities(esp))
        val bare = GatewayProtocol.Frame(GatewayProtocol.WELCOME, GatewayProtocol.welcome(emptyList(), appVersion = "1.6.0"))
        assertEquals("1.6.0", GatewayProtocol.appVersion(bare))
        assertTrue(GatewayProtocol.capabilities(bare).isEmpty())
    }

    @Test fun `protocol mismatch names both versions, different app versions only advised`() {
        val text = GatewayProtocol.versionMismatch(3)
        assertTrue(text, text.contains("v3") && text.contains("v${GatewayProtocol.VERSION}") && text.contains(BuildConfig.VERSION_NAME))
        assertNull(versionNote("1.6.0", "1.6.0"))
        val note = versionNote("1.5.0", "1.6.0")!!
        assertTrue(note, note.contains("1.5.0") && note.contains("1.6.0"))
    }
}
