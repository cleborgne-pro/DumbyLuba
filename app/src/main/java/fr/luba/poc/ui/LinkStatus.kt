package fr.luba.poc.ui

import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.gateway.GatewayReach
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.GatewayStatus
import fr.luba.poc.i18n.tr

/**
 * Robot link labels shared by the home screen and the Service tab.
 * - Pilot phone: "via the gateway" when the open link is the gateway's (not on a direct fallback).
 * - Gateway phone: the Bluetooth link is held by the gateway service, not by the screen.
 * - Pilot not linked to the robot: [gatewayReach] tells a silent robot (off, out of range) from an unreachable gateway.
 *
 * [gatewayReachable]: null outside control via the gateway or when unknown.
 */
data class LinkStatus(val connected: Boolean, val connecting: Boolean, val short: String, val summary: String, val robotName: String?,
    val gatewayReachable: Boolean? = null)

fun linkStatus(state: RobotUi, role: GatewayRole, gateway: GatewayStatus, gatewayReach: GatewayReach? = null): LinkStatus {
    if (role == GatewayRole.GATEWAY) {
        val robot = gateway.robot
        val name = robot.name ?: robot.address
        return when {
            !gateway.running -> LinkStatus(false, false, tr("Gateway stopped", "Passerelle arrêtée"), tr("Gateway stopped", "Passerelle arrêtée"), null)
            robot.ready -> LinkStatus(true, false, tr("Connected over Bluetooth", "Connecté en Bluetooth"),
                tr("Robot connected over Bluetooth to the gateway", "Robot connecté en Bluetooth à la passerelle") + if (robot.pilot) tr(" · controlled from the other phone", " · piloté depuis l'autre téléphone") else tr(" · waiting for the pilot", " · en attente du pilote"), name)
            robot.address != null && robot.connecting -> LinkStatus(false, true, tr("Connecting…", "Connexion…"), tr("Bluetooth connection to the robot…", "Connexion Bluetooth au robot…"), name)
            robot.address != null -> LinkStatus(false, true, tr("Out of range", "Hors de portée"), tr("Robot off or out of Bluetooth range: retrying automatically", "Robot éteint ou hors de portée du Bluetooth : nouvel essai automatique"), name)
            else -> LinkStatus(false, false, tr("No robot", "Aucun robot"), tr("No robot: connect it from Service → Bluetooth connection", "Aucun robot : le connecter depuis Service → Connexion Bluetooth"), null)
        }
    }
    val via = state.viaGateway
    return when {
        state.ready -> LinkStatus(true, false, if (via) tr("Connected via the gateway", "Connecté via la passerelle") else tr("Connected over Bluetooth", "Connecté en Bluetooth"),
            (if (via) tr("Connected via the Wi-Fi gateway (Bluetooth held by the phone on the robot)", "Connecté via la passerelle Wi-Fi (Bluetooth tenu par le téléphone sur le robot)") else tr("Connected over Bluetooth", "Connecté en Bluetooth")) +
                (state.rssi?.let { " · $it dBm" } ?: ""), state.selected?.name)
        state.connection == ConnectionState.Disconnected && role == GatewayRole.CLIENT && gatewayReach != null ->
            if (gatewayReach.reachable) LinkStatus(false, false, tr("Robot unreachable", "Robot injoignable"),
                tr("Gateway reachable · robot not answering over Bluetooth (off or out of range): retrying automatically", "Passerelle joignable · robot sans réponse en Bluetooth (éteint ou hors de portée) : nouvel essai automatique"), null, true)
            else LinkStatus(false, false, tr("Gateway unreachable", "Passerelle injoignable"),
                tr("Gateway unreachable", "Passerelle injoignable") + gatewayReach.detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), null, false)
        state.connection == ConnectionState.Disconnected -> LinkStatus(false, false, tr("Disconnected", "Déconnecté"), tr("Disconnected", "Déconnecté"), null)
        else -> LinkStatus(false, true, tr("Connecting…", "Connexion…"), state.connection.label, state.selected?.name)
    }
}
