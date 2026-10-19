package fr.luba.poc.alerts

import fr.luba.poc.gateway.GatewayLink
import org.junit.Assert.*
import org.junit.Test

class GatewayAlertPolicyTest {
    private val events = mutableListOf<Pair<AlertKind, Severity>>()
    private val emit: (AlertKind, Severity) -> Unit = { kind, severity -> events.add(kind to severity) }

    /** Replays a series of readings like the pilot's alert timer (one per second). */
    private fun battery(monitor: AlertMonitor, percent: Int, charging: Boolean = false) =
        monitor.condition(AlertKind.GATEWAY_BATTERY, GatewayAlertPolicy.battery(percent, charging, monitor.severity(AlertKind.GATEWAY_BATTERY)), emit)

    @Test fun thresholdsMatchDisplayedWarnings() {
        assertNull(GatewayAlertPolicy.battery(25, false, null))
        assertEquals(Severity.WARNING, GatewayAlertPolicy.battery(24, false, null))
        assertEquals(Severity.WARNING, GatewayAlertPolicy.battery(15, false, null))
        assertEquals(Severity.CRITICAL, GatewayAlertPolicy.battery(14, false, null))
        assertEquals(Severity.CRITICAL, GatewayAlertPolicy.battery(0, false, null))
    }

    @Test fun chargingOrUnknownNeverAlerts() {
        assertNull(GatewayAlertPolicy.battery(3, true, Severity.CRITICAL))
        assertNull(GatewayAlertPolicy.battery(-1, false, null))
        assertNull(GatewayAlertPolicy.battery(-1, false, Severity.WARNING))
    }

    @Test fun dischargeAlertsOncePerLevelThenEscalates() {
        val m = AlertMonitor()
        listOf(30, 26, 24, 23, 22, 20, 16, 15, 14, 13, 10, 5).forEach { battery(m, it) }
        assertEquals(listOf(AlertKind.GATEWAY_BATTERY to Severity.WARNING, AlertKind.GATEWAY_BATTERY to Severity.CRITICAL), events)
    }

    @Test fun hysteresisAvoidsRepeatsAroundThresholds() {
        val m = AlertMonitor()
        listOf(24, 25, 24, 27, 24, 14, 15, 17, 14).forEach { battery(m, it) }
        assertEquals(listOf(AlertKind.GATEWAY_BATTERY to Severity.WARNING, AlertKind.GATEWAY_BATTERY to Severity.CRITICAL), events)
        battery(m, 18); battery(m, 28)
        assertNull(m.severity(AlertKind.GATEWAY_BATTERY))
        battery(m, 24)
        assertEquals(AlertKind.GATEWAY_BATTERY to Severity.WARNING, events.last()); assertEquals(3, events.size)
    }

    @Test fun pluggingInClearsAndUnpluggingRearms() {
        val m = AlertMonitor()
        battery(m, 10); battery(m, 11, charging = true); battery(m, 12)
        assertEquals(listOf(AlertKind.GATEWAY_BATTERY to Severity.CRITICAL, AlertKind.GATEWAY_BATTERY to Severity.CRITICAL), events)
    }

    @Test fun disconnectionAlertsOnceUntilTelemetryReturns() {
        val m = AlertMonitor(); m.arm()
        m.condition(AlertKind.GATEWAY_LOST, GatewayAlertPolicy.lost(moving = true), emit)
        // Failed automatic reconnections: same incident, no new notification.
        m.condition(AlertKind.GATEWAY_LOST, GatewayAlertPolicy.lost(moving = false), emit)
        m.condition(AlertKind.GATEWAY_LOST, GatewayAlertPolicy.lost(moving = false), emit)
        assertEquals(listOf(AlertKind.GATEWAY_LOST to Severity.CRITICAL), events)
        m.condition(AlertKind.GATEWAY_LOST, null, emit)
        m.condition(AlertKind.GATEWAY_LOST, GatewayAlertPolicy.lost(moving = false), emit)
        assertEquals(AlertKind.GATEWAY_LOST to Severity.WARNING, events.last())
    }

    @Test fun onlyTunnelLossesCountAsGatewayDisconnection() {
        assertTrue(GatewayLink.gatewayDisconnected(GatewayLink.LOST_SILENT))
        assertTrue(GatewayLink.gatewayDisconnected(GatewayLink.LOST_WIFI))
        assertFalse(GatewayLink.gatewayDisconnected(8)) // robot Bluetooth lost on the gateway side
        assertTrue(GatewayLink.gatewayUnreachable("Passerelle injoignable : connect timed out"))
        assertFalse(GatewayLink.gatewayUnreachable("Passerelle : robot introuvable"))
    }

    @Test fun journalRestoresGatewayAlerts() {
        val alert = RobotAlert(AlertKind.GATEWAY_LOST, Severity.CRITICAL, 1L, AlertContext("r", "Luba", "état", null, null), "Wi-Fi perdu")
        val decoded = AlertJournal.decode(AlertJournal(pending = mapOf(AlertJournal.key(alert) to alert)).encode())
        assertEquals(alert, decoded.pending.values.single())
    }
}
