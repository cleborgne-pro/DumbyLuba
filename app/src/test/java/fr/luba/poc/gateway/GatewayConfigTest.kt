package fr.luba.poc.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.relay.RelayConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistent gateway and relay settings: role, code, address, chosen robot, options. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayConfigTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("gateway", 0)

    @After fun reset() { GatewayOta.update(false) }

    @Test fun `first launch, direct Bluetooth and six-digit code drawn once`() {
        val first = GatewayConfig.read(context)
        assertEquals(GatewayRole.OFF, first.role)
        assertEquals("", first.host)
        assertEquals(GatewayProtocol.PORT, first.port)
        assertTrue(first.code, first.validCode())
        // The drawn code is kept: the gateway and the pilot must share it.
        assertEquals(first.code, GatewayConfig.read(context).code)
    }

    @Test fun `role, address and code read back after a restart, address trimmed`() {
        GatewayConfig.save(context, GatewayConfig(GatewayRole.CLIENT, "  192.168.0.42 ", 9000, "123456", "222333"))
        assertEquals(GatewayConfig(GatewayRole.CLIENT, "192.168.0.42", 9000, "123456", "222333"), GatewayConfig.read(context))
        GatewayConfig.save(context, GatewayConfig(GatewayRole.GATEWAY, "", GatewayProtocol.PORT, "654321"))
        assertEquals(GatewayRole.GATEWAY, GatewayConfig.read(context).role)
    }

    @Test fun `pilot code kept apart from this phone's own gateway code`() {
        val own = GatewayConfig.read(context).code
        GatewayConfig.save(context, GatewayConfig.read(context).copy(role = GatewayRole.CLIENT, pilotCode = "424242"))
        // Becoming a gateway later: the code shown is still this phone's own, not the one typed for the other gateway.
        GatewayConfig.save(context, GatewayConfig.read(context).copy(role = GatewayRole.GATEWAY))
        assertEquals(own, GatewayConfig.read(context).code)
        assertEquals("424242", GatewayConfig.read(context).pilotCode)
        assertTrue(GatewayConfig.read(context).paired)
        assertFalse(GatewayConfig(pilotCode = "12345").paired)
    }

    @Test fun `pilot of an older version, its single code carried over as the pilot code once`() {
        prefs.edit().putString("role", "CLIENT").putString("code", "135790").remove("pilotCode").commit()
        assertEquals("135790", GatewayConfig.read(context).pilotCode)
        // A gateway or direct phone of an older version has no pilot code.
        prefs.edit().putString("role", "GATEWAY").putString("code", "246801").remove("pilotCode").commit()
        assertEquals("", GatewayConfig.read(context).pilotCode)
        assertEquals("246801", GatewayConfig.read(context).code)
    }

    @Test fun `unknown role from another version, back to direct Bluetooth`() {
        prefs.edit().putString("role", "RELAY").putString("code", "000001").commit()
        val config = GatewayConfig.read(context)
        assertEquals(GatewayRole.OFF, config.role)
        assertEquals("000001", config.code)
    }

    @Test fun `code valid only with exactly six digits`() {
        for (good in listOf("000000", "123456")) assertTrue(good, GatewayConfig(code = good).validCode())
        for (bad in listOf("", "12345", "1234567", "12345a", " 123456", "١٢٣٤٥٦")) assertFalse(bad, GatewayConfig(code = bad).validCode())
    }

    @Test fun `robot chosen by the gateway saved then forgotten`() {
        assertNull(GatewayConfig.robot(context))
        GatewayConfig.saveRobot(context, "AA:BB:CC:DD:EE:FF", "Luba-VS123")
        assertEquals("AA:BB:CC:DD:EE:FF" to "Luba-VS123", GatewayConfig.robot(context))
        GatewayConfig.saveRobot(context, "11:22:33:44:55:66", null)
        assertEquals("11:22:33:44:55:66" to null, GatewayConfig.robot(context))
        GatewayConfig.saveRobot(context, null, null)
        assertNull(GatewayConfig.robot(context))
    }

    @Test fun `gateway screen off by default, option kept`() {
        assertFalse(GatewayConfig.keepScreen(context))
        GatewayConfig.setKeepScreen(context, true)
        assertTrue(GatewayConfig.keepScreen(context))
        // Old implicit key not carried over.
        prefs.edit().remove("keepScreenOn").putBoolean("keepScreen", true).commit()
        assertFalse(GatewayConfig.keepScreen(context))
    }

    @Test fun `silence and wireless debugging kept by default, choices kept`() {
        assertTrue(SilenceKeeper.keep(context))
        SilenceKeeper.setKeep(context, false)
        assertFalse(SilenceKeeper.keep(context))
        assertTrue(WirelessDebugKeeper.keep(context))
        WirelessDebugKeeper.setKeep(context, false)
        assertFalse(WirelessDebugKeeper.keep(context))
    }

    @Test fun `updates during mowing disabled by default, setting read back at startup`() {
        assertFalse(GatewayOta.load(context))
        assertFalse(GatewayOta.duringMowing)
        GatewayOta.set(context, true)
        assertTrue(GatewayOta.duringMowing)
        GatewayOta.update(false)
        // Restart: the saved value comes back into memory.
        assertTrue(GatewayOta.load(context))
        assertTrue(GatewayOta.duringMowing)
    }

    @Test fun `relay, identifier and code drawn once, address saved without trailing slash`() {
        val first = RelayConfig.read(context)
        assertEquals("", first.url)
        assertTrue(first.code.matches(Regex("[0-9]{6}")))
        assertEquals(first, RelayConfig.read(context))
        RelayConfig.save(context, first.copy(url = "http://192.168.0.10:8765/"))
        assertEquals("http://192.168.0.10:8765", RelayConfig.read(context).url)
        assertEquals(first.id, RelayConfig.read(context).id)
    }

    @Test fun `relay, invalid address or code refused without saving anything`() {
        val first = RelayConfig.read(context)
        for (bad in listOf(first.copy(url = "ftp://serveur"), first.copy(url = "http://serveur/chemin"), first.copy(url = "http://u:p@serveur"),
                first.copy(url = "https://serveur?code=1"), first.copy(url = "serveur:8765"), first.copy(url = "http://serveur", code = "12 456"))) {
            assertThrows(bad.url, IllegalArgumentException::class.java) { RelayConfig.save(context, bad) }
        }
        assertEquals(first, RelayConfig.read(context))
        RelayConfig(url = "https://maison.example:8765", code = "000123", id = "x").validate()
    }
}
