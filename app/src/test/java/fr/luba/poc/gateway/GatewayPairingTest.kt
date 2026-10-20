package fr.luba.poc.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pairing link carried by the gateway's QR code, the QR code itself and the gateway phone checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayPairingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `pairing link written then read back, name encoded`() {
        val pairing = GatewayPairing("192.168.0.10", 8766, "012345", "Redmi A1")
        val uri = pairing.uri()
        assertEquals("dumbyluba://pair?v=2&host=192.168.0.10&port=8766&code=012345&name=Redmi%20A1", uri)
        assertEquals(pairing, GatewayPairing.parse(uri))
        assertEquals("Redmi A1", pairing.label)
        assertEquals("192.168.0.10", pairing.copy(name = "").label)
    }

    @Test fun `other QR codes and malformed links refused`() {
        for (bad in listOf(null, "", "https://example.com", "WIFI:S:home;T:WPA;P:secret;;",
                "dumbyluba://pair?host=192.168.0.10&code=12345", "dumbyluba://pair?host=192.168.0.10&code=abcdef",
                "dumbyluba://pair?code=123456", "dumbyluba://pair?host=evil host&code=123456",
                "dumbyluba://pair?host=192.168.0.10&port=70000&code=123456", "dumbyluba://other?host=192.168.0.10&code=123456"))
            assertNull(bad, GatewayPairing.parse(bad))
    }

    @Test fun `default port, unknown parameters ignored, name cleaned and bounded`() {
        val pairing = GatewayPairing.parse("DUMBYLUBA://pair?code=999999&host=gw.local&future=1&name=" + "<b>" + "x".repeat(60))!!
        assertEquals(GatewayProtocol.PORT, pairing.port)
        assertEquals("gw.local", pairing.host)
        assertEquals(40, pairing.name.length)
        assertFalse(pairing.name.contains('<'))
    }

    @Test fun `applying a pairing makes this phone a pilot of that gateway, its own code kept`() {
        val own = GatewayConfig.read(context).code
        val config = GatewayPairing.apply(context, GatewayPairing("192.168.0.20", 9000, "654321", "Pixel"))
        assertEquals(GatewayRole.CLIENT, config.role)
        assertEquals(config, GatewayConfig.read(context))
        assertEquals("192.168.0.20", config.host)
        assertEquals(9000, config.port)
        assertEquals("654321", config.pilotCode)
        assertEquals(own, config.code)
    }

    @Test fun `gateway QR code readable by the pilot's decoder`() {
        val text = GatewayPairing("192.168.1.42", 8766, "424242", "Galaxy A14").uri()
        val modules = QrMatrix.encode(text)
        // Rendered like the screen: 4-module quiet zone, 6 px per module, black on white.
        val scale = 6; val quiet = 4
        val side = (modules.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in modules.indices) for (x in modules.indices) if (modules[y][x])
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
        assertEquals(text, QrMatrix.decode(RGBLuminanceSource(side, side, pixels)))
        assertEquals(text, GatewayPairing.parse(text)?.uri())
    }

    @Test fun `gateway phone checks name what is missing and how to fix it`() {
        val ready = GatewayPhoneChecks.list(bluetoothOn = true, batteryExempt = true, wifiConnected = true, silence = true, dndGranted = true)
        assertTrue(ready.all { it.ok && it.fix == null })
        val missing = GatewayPhoneChecks.list(bluetoothOn = false, batteryExempt = false, wifiConnected = false, silence = true, dndGranted = false)
        assertEquals(listOf(PhoneFix.BLUETOOTH, PhoneFix.WIFI, PhoneFix.BATTERY, PhoneFix.DO_NOT_DISTURB), missing.map { it.fix })
        // Silence turned off: "Do not disturb" no longer matters.
        assertTrue(GatewayPhoneChecks.list(true, true, true, silence = false, dndGranted = false).none { it.fix == PhoneFix.DO_NOT_DISTURB })
    }
}
