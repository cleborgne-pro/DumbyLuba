package fr.luba.poc.diagnostics

import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.gateway.GatewayHealth
import fr.luba.poc.gateway.GatewayPhoneChecks
import fr.luba.poc.gateway.GatewayProbe
import org.junit.Assert.*
import org.junit.Test

/** Verdicts of Service → Check the setup, link by link, and the fixes offered. */
class SetupCheckTest {
    private val healthy = GatewayHealth(batteryPercent = 80, charging = true, wifiRssiDbm = -55, thermalStatus = 0, batteryExempt = true)
    private val disconnected = SetupCheck.Robot(ready = false, viaGateway = false, rssi = null, charging = false, name = "Luba-VS1",
        watchdogTested = false, watchdogDemonstrated = false)
    private fun probe(robots: List<SeenRobot> = listOf(SeenRobot("Luba-VS1", "AA", -45)), version: String? = "1.8.0", ms: Long = 120, failure: String? = null) =
        GatewayProbe("192.168.0.10", ms, version, emptySet(), healthy, robots, failure)
    private fun List<CheckItem>.of(title: String) = first { it.title == title }

    @Test fun `pilot, gateway reached and robot seen, everything in place except the safety test`() {
        val items = SetupCheck.pilot(true, 1, probe(), null, disconnected, null, null, "1.8.0")
        assertEquals(CheckLevel.OK, items.of("Connexion à la passerelle").level)
        assertEquals(CheckLevel.OK, items.of("Versions").level)
        assertEquals(CheckLevel.OK, items.of("Robot vu par la passerelle").level)
        assertEquals(CheckLevel.OK, items.of("Passerelle sur le robot").level)
        assertEquals(CheckFix.OPEN_CONNECTION, items.of("Liaison robot").fix)
        assertEquals(CheckFix.COMMAND_CUTOFF, items.of("Coupure des commandes").fix)
        assertTrue(items.none { it.level == CheckLevel.FAILED })
    }

    @Test fun `pilot, gateway unreachable, failure with the reason and the gateway page as fix`() {
        val items = SetupCheck.pilot(false, 0, null, "passerelle introuvable sur le Wi-Fi", disconnected, null, null, "1.8.0")
        assertEquals(CheckFix.WIFI_SETTINGS, items.of("Wi-Fi de ce téléphone").fix)
        assertEquals(CheckLevel.WARNING, items.of("Passerelle sur le Wi-Fi").level)
        val connection = items.of("Connexion à la passerelle")
        assertEquals(CheckLevel.FAILED, connection.level)
        assertEquals("passerelle introuvable sur le Wi-Fi", connection.detail)
        assertEquals(CheckFix.PAIR_GATEWAY, connection.fix)
        // Nothing measured beyond an unreachable gateway: no version or robot verdict.
        assertTrue(items.none { it.title == "Versions" || it.title == "Robot vu par la passerelle" })
    }

    @Test fun `pilot, robot not seen names it and the Mammotion app, Bluetooth off on the gateway reported as such`() {
        val none = SetupCheck.pilot(true, 1, probe(robots = emptyList()), null, disconnected, null, null, "1.8.0").of("Robot vu par la passerelle")
        assertEquals(CheckLevel.FAILED, none.level)
        assertTrue(none.detail, none.detail.contains("Luba-VS1") && none.detail.contains("Mammotion"))
        val off = SetupCheck.pilot(true, 1, probe(robots = emptyList(), failure = "Bluetooth désactivé sur la passerelle"), null, disconnected, null, null, "1.8.0")
        assertEquals("Bluetooth désactivé sur la passerelle", off.of("Robot vu par la passerelle").detail)
    }

    @Test fun `pilot, slow Wi-Fi and different versions only warned`() {
        val items = SetupCheck.pilot(true, 1, probe(version = "1.7.0", ms = 1500), null, disconnected, null, null, "1.8.0")
        assertEquals(CheckLevel.WARNING, items.of("Connexion à la passerelle").level)
        assertEquals(CheckLevel.WARNING, items.of("Versions").level)
        assertEquals(CheckLevel.SKIPPED, SetupCheck.pilot(true, 1, probe(version = null), null, disconnected, null, null, "1.8.0").of("Versions").level)
    }

    @Test fun `pilot, robot linked through the gateway checked from the live link without a probe`() {
        val linked = disconnected.copy(ready = true, viaGateway = true, rssi = -62, charging = true, watchdogTested = true, watchdogDemonstrated = true)
        val items = SetupCheck.pilot(true, 1, null, null, linked, healthy.copy(wifiRssiDbm = -74), "1.8.0", "1.8.0")
        assertEquals(CheckLevel.OK, items.of("Connexion à la passerelle").level)
        assertEquals(CheckLevel.OK, items.of("Liaison robot").level)
        assertEquals(CheckLevel.WARNING, items.of("Passerelle sur le robot").level)
        assertEquals(CheckLevel.WARNING, items.of("Wi-Fi à la base").level)
        assertEquals(CheckLevel.OK, items.of("Coupure des commandes").level)
    }

    @Test fun `placement thresholds follow the fall detection level`() {
        assertEquals(CheckLevel.SKIPPED, SetupCheck.placement(null).level)
        assertEquals(CheckLevel.OK, SetupCheck.placement(-50).level)
        assertEquals(CheckLevel.WARNING, SetupCheck.placement(-51).level)
        assertEquals(CheckLevel.WARNING, SetupCheck.placement(-70).level)
        assertEquals(CheckLevel.FAILED, SetupCheck.placement(-71).level)
    }

    @Test fun `Wi-Fi at the base only judged while charging with a known level`() {
        assertEquals(CheckLevel.SKIPPED, SetupCheck.baseWifi(healthy, charging = false).level)
        assertEquals(CheckLevel.SKIPPED, SetupCheck.baseWifi(healthy.copy(wifiRssiDbm = -1000), charging = true).level)
        assertEquals(CheckLevel.OK, SetupCheck.baseWifi(healthy, charging = true).level)
        assertEquals(CheckLevel.WARNING, SetupCheck.baseWifi(healthy.copy(wifiRssiDbm = -70), charging = true).level)
    }

    @Test fun `direct Bluetooth, permission, robot in range and the gateway advice`() {
        val refused = SetupCheck.direct(bluetoothOn = true, permission = false, devices = emptyList(), gatewaySeen = false, robot = disconnected)
        assertEquals(CheckFix.BLUETOOTH_SETTINGS, refused.of("Bluetooth").fix)
        val inRange = SetupCheck.direct(true, true, listOf(SeenRobot("Luba-VS1", "AA", -60), SeenRobot("Luba-VS9", "BB", null)), false, disconnected)
        assertEquals(CheckLevel.WARNING, inRange.of("Liaison robot").level)
        assertTrue(inRange.of("Liaison robot").detail.startsWith("Luba-VS1 à portée"))
        assertEquals(CheckFix.PAIR_GATEWAY, inRange.of("Portée").fix)
        val withGateway = SetupCheck.direct(true, true, emptyList(), gatewaySeen = true, robot = disconnected)
        assertEquals(CheckLevel.FAILED, withGateway.of("Liaison robot").level)
        assertEquals(CheckFix.USE_GATEWAY, withGateway.of("Portée").fix)
    }

    @Test fun `gateway phone, service, phone checks and robot held`() {
        val checks = GatewayPhoneChecks.list(bluetoothOn = false, batteryExempt = true, wifiConnected = true, silence = true, dndGranted = false)
        val items = SetupCheck.gateway(checks, running = false, robotHeld = false, robotLabel = "Pas encore de robot", pilot = null)
        assertEquals(CheckLevel.FAILED, items.of("Service passerelle").level)
        assertEquals(CheckFix.BLUETOOTH_SETTINGS, items.of("Bluetooth").fix)
        assertEquals(CheckLevel.WARNING, items.of("« Ne pas déranger »").level)
        assertEquals(CheckLevel.WARNING, items.of("Robot").level)
        assertEquals(CheckLevel.SKIPPED, items.of("Pilote").level)
    }

    @Test fun `robot not found hint names the remembered robot`() {
        assertTrue(SetupCheck.notFoundHint("Luba-VS1", viaGateway = true).contains("Luba-VS1 ne s'annonce pas : éteint, hors de portée de la passerelle"))
        assertTrue(SetupCheck.notFoundHint(null, viaGateway = false).startsWith("Aucun robot trouvé"))
    }
}
