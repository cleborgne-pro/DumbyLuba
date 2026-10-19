package fr.luba.poc.ui

import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.gateway.GatewayReach
import fr.luba.poc.gateway.GatewayRobotState
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.GatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStatusTest {
    private val none = GatewayStatus()

    @Test fun pilotConnectedThroughGatewayIsLabelled() {
        val s = linkStatus(RobotUi(ready = true, viaGateway = true, connection = ConnectionState.WaitingTelemetry), GatewayRole.CLIENT, none)
        assertTrue(s.connected)
        assertEquals("Connecté via la passerelle", s.short)
    }

    @Test fun pilotFallbackIsDirectBluetooth() {
        val s = linkStatus(RobotUi(ready = true, viaGateway = false, connection = ConnectionState.WaitingTelemetry), GatewayRole.CLIENT, none)
        assertEquals("Connecté en Bluetooth", s.short)
    }

    @Test fun pilotDisconnected() {
        val s = linkStatus(RobotUi(), GatewayRole.CLIENT, none)
        assertFalse(s.connected)
        assertEquals("Déconnecté", s.short)
    }

    @Test fun gatewayPhoneShowsBluetoothLink() {
        val held = GatewayStatus(running = true, robot = GatewayRobotState(address = "AA", name = "Luba-X", ready = true))
        val s = linkStatus(RobotUi(), GatewayRole.GATEWAY, held)
        assertTrue(s.connected)
        assertEquals("Connecté en Bluetooth", s.short)
        assertTrue(s.summary.contains("en attente du pilote"))
        assertTrue(linkStatus(RobotUi(), GatewayRole.GATEWAY, held.copy(robot = held.robot.copy(pilot = true))).summary.contains("piloté"))
    }

    @Test fun gatewayPhoneWithoutRobot() {
        assertFalse(linkStatus(RobotUi(), GatewayRole.GATEWAY, GatewayStatus(running = true)).connected)
        assertFalse(linkStatus(RobotUi(), GatewayRole.GATEWAY, none).connected)
    }

    @Test fun pilotWithReachableGatewayBlamesRobotNotGateway() {
        val s = linkStatus(RobotUi(), GatewayRole.CLIENT, none, GatewayReach(true, 0L))
        assertFalse(s.connected)
        assertEquals(true, s.gatewayReachable)
        assertEquals("Robot injoignable", s.short)
        assertTrue(s.summary.contains("Passerelle joignable"))
    }

    @Test fun pilotWithUnreachableGateway() {
        val s = linkStatus(RobotUi(), GatewayRole.CLIENT, none, GatewayReach(false, 0L, "Wi-Fi muet"))
        assertEquals(false, s.gatewayReachable)
        assertEquals("Passerelle injoignable", s.short)
        assertTrue(s.summary.contains("Wi-Fi muet"))
    }

    @Test fun reachIgnoredOutsidePilotRoleOrWhenLinked() {
        assertEquals("Déconnecté", linkStatus(RobotUi(), GatewayRole.OFF, none, GatewayReach(true, 0L)).short)
        val linked = linkStatus(RobotUi(ready = true, viaGateway = true, connection = ConnectionState.WaitingTelemetry), GatewayRole.CLIENT, none, GatewayReach(true, 0L))
        assertEquals("Connecté via la passerelle", linked.short)
    }
}
