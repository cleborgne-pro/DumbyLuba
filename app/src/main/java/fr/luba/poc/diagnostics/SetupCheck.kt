package fr.luba.poc.diagnostics

import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.gateway.GatewayHealth
import fr.luba.poc.gateway.GatewayProbe
import fr.luba.poc.gateway.PhoneCheck
import fr.luba.poc.gateway.PhoneFix
import fr.luba.poc.gateway.summary
import fr.luba.poc.gateway.versionNote
import fr.luba.poc.gateway.wifiKnown
import fr.luba.poc.i18n.tr

/** Result of one point of the setup check. */
enum class CheckLevel { OK, WARNING, FAILED, SKIPPED }

/** Action offered next to a point that is not fine. */
enum class CheckFix { PAIR_GATEWAY, OPEN_CONNECTION, BLUETOOTH_SETTINGS, WIFI_SETTINGS, BATTERY, DO_NOT_DISTURB, COMMAND_CUTOFF, USE_GATEWAY }

data class CheckItem(val title: String, val level: CheckLevel, val detail: String, val fix: CheckFix? = null)

/**
 * Service → Check the setup: from what the app knows and what it just measured, each link of the chain from this
 * phone to the robot, its state and the fix. Pure: the measurements are made by the page, the verdicts are tested here.
 */
object SetupCheck {
    /** Gateway ↔ robot Bluetooth signal of a phone lying on the robot: −50 dBm or better (same as the fall detection). */
    const val PLACED_DBM = -50
    /** Below this, the gateway ↔ robot link drops easily. */
    const val WEAK_DBM = -70
    /** Gateway Wi-Fi at or below this level: warning (the docking of 16/10/2026 lost the link at −73 dBm). */
    const val WIFI_WEAK_DBM = -70
    /** Connection + two round trips above this: slow Wi-Fi for the commands still driven from the pilot. */
    const val SLOW_HANDSHAKE_MS = 800L

    /** What the pilot knows of the robot side when the check runs. */
    data class Robot(val ready: Boolean, val viaGateway: Boolean, val rssi: Int?, val charging: Boolean, val name: String?,
        val watchdogTested: Boolean, val watchdogDemonstrated: Boolean)

    /** Pilot through a gateway. [probe] null if not run (robot already linked through the gateway) or failed ([probeError]). */
    fun pilot(phoneWifi: Boolean?, announced: Int, probe: GatewayProbe?, probeError: String?, robot: Robot,
              liveHealth: GatewayHealth?, liveVersion: String?, appVersion: String): List<CheckItem> = buildList {
        add(when (phoneWifi) {
            false -> CheckItem(tr("Wi-Fi of this phone", "Wi-Fi de ce téléphone"), CheckLevel.FAILED,
                tr("not connected: the gateway is reached over the home Wi-Fi", "non connecté : la passerelle se joint par le Wi-Fi de la maison"), CheckFix.WIFI_SETTINGS)
            else -> CheckItem(tr("Wi-Fi of this phone", "Wi-Fi de ce téléphone"), CheckLevel.OK, tr("connected", "connecté"))
        })
        add(if (announced > 0) CheckItem(tr("Gateway on the Wi-Fi", "Passerelle sur le Wi-Fi"), CheckLevel.OK,
                tr("$announced announced", "$announced annoncée(s)"))
            else CheckItem(tr("Gateway on the Wi-Fi", "Passerelle sur le Wi-Fi"), CheckLevel.WARNING,
                tr("none announced: the saved address is used (gateway off, other Wi-Fi, or announcements blocked by the router)",
                    "aucune annoncée : l'adresse enregistrée est utilisée (passerelle éteinte, autre Wi-Fi, ou annonces bloquées par le routeur)")))
        val linked = robot.ready && robot.viaGateway
        add(when {
            linked -> CheckItem(tr("Gateway connection", "Connexion à la passerelle"), CheckLevel.OK, tr("in use: the robot is connected through it", "en service : le robot est connecté par elle"))
            probe != null -> CheckItem(tr("Gateway connection", "Connexion à la passerelle"),
                if (probe.handshakeMs > SLOW_HANDSHAKE_MS) CheckLevel.WARNING else CheckLevel.OK,
                tr("${probe.host}, code accepted, answer in ${probe.handshakeMs} ms", "${probe.host}, code accepté, réponse en ${probe.handshakeMs} ms") +
                    if (probe.handshakeMs > SLOW_HANDSHAKE_MS) tr(": slow Wi-Fi", " : Wi-Fi lent") else "")
            else -> CheckItem(tr("Gateway connection", "Connexion à la passerelle"), CheckLevel.FAILED,
                probeError ?: tr("not reached", "non jointe"), CheckFix.PAIR_GATEWAY)
        })
        val version = liveVersion ?: probe?.appVersion
        if (linked || probe != null) add(when {
            version == null -> CheckItem(tr("Versions", "Versions"), CheckLevel.SKIPPED, tr("gateway version unknown (older gateway or ESP32)", "version de la passerelle inconnue (passerelle plus ancienne ou ESP32)"))
            else -> versionNote(version, appVersion)?.let { CheckItem(tr("Versions", "Versions"), CheckLevel.WARNING, it) }
                ?: CheckItem(tr("Versions", "Versions"), CheckLevel.OK, tr("$version on both phones", "$version sur les deux téléphones"))
        })
        val health = liveHealth ?: probe?.health
        if (health != null) {
            val warnings = health.warnings()
            add(if (warnings.isEmpty()) CheckItem(tr("Gateway phone", "Téléphone passerelle"), CheckLevel.OK, health.summary())
                else CheckItem(tr("Gateway phone", "Téléphone passerelle"), CheckLevel.WARNING, warnings.joinToString(" · ")))
        } else if (linked || probe != null) add(CheckItem(tr("Gateway phone", "Téléphone passerelle"), CheckLevel.SKIPPED, tr("health not received", "santé non reçue")))
        // Robot seen by the gateway's Bluetooth.
        when {
            linked -> Unit
            probe?.scanFailure != null -> add(CheckItem(tr("Robot seen by the gateway", "Robot vu par la passerelle"), CheckLevel.FAILED, probe.scanFailure))
            probe != null && probe.robots.isEmpty() -> add(CheckItem(tr("Robot seen by the gateway", "Robot vu par la passerelle"), CheckLevel.FAILED,
                notFoundHint(robot.name, viaGateway = true)))
            probe != null -> add(CheckItem(tr("Robot seen by the gateway", "Robot vu par la passerelle"), CheckLevel.OK,
                probe.robots.joinToString { r -> r.name + (r.rssi?.let { " ($it dBm)" } ?: "") }))
        }
        add(if (robot.ready) CheckItem(tr("Robot link", "Liaison robot"), CheckLevel.OK,
                if (robot.viaGateway) tr("connected through the gateway", "connecté par la passerelle") else tr("connected over direct Bluetooth", "connecté en Bluetooth direct"))
            else CheckItem(tr("Robot link", "Liaison robot"), CheckLevel.WARNING, tr("not connected yet: automatic attempt every 15 s, or tap the robot",
                "pas encore connecté : tentative automatique toutes les 15 s, ou touchez le robot"), CheckFix.OPEN_CONNECTION))
        add(placement(if (linked) robot.rssi else probe?.robots?.singleOrNull()?.rssi))
        add(baseWifi(health, robot.charging))
        add(cutoff(robot))
    }

    /** Direct Bluetooth from this phone. [devices]: last search; [gatewaySeen]: a gateway announced on the Wi-Fi. */
    fun direct(bluetoothOn: Boolean, permission: Boolean, devices: List<SeenRobot>, gatewaySeen: Boolean, robot: Robot): List<CheckItem> = buildList {
        add(when {
            !permission -> CheckItem("Bluetooth", CheckLevel.FAILED, tr("permission refused: allow it in the Android settings", "permission refusée : autorisez-la dans les paramètres Android"), CheckFix.BLUETOOTH_SETTINGS)
            !bluetoothOn -> CheckItem("Bluetooth", CheckLevel.FAILED, tr("off", "désactivé"), CheckFix.BLUETOOTH_SETTINGS)
            else -> CheckItem("Bluetooth", CheckLevel.OK, tr("on", "activé"))
        })
        val seen = devices.filter { it.rssi != null }
        add(when {
            robot.ready -> CheckItem(tr("Robot link", "Liaison robot"), CheckLevel.OK, tr("connected over direct Bluetooth", "connecté en Bluetooth direct") +
                (robot.rssi?.let { " ($it dBm)" } ?: ""))
            seen.isNotEmpty() -> CheckItem(tr("Robot link", "Liaison robot"), CheckLevel.WARNING,
                tr("${seen.joinToString { it.name }} in range, not connected: tap it", "${seen.joinToString { it.name }} à portée, non connecté : touchez-le"), CheckFix.OPEN_CONNECTION)
            else -> CheckItem(tr("Robot link", "Liaison robot"), CheckLevel.FAILED, notFoundHint(robot.name, viaGateway = false), CheckFix.OPEN_CONNECTION)
        })
        add(if (gatewaySeen) CheckItem(tr("Range", "Portée"), CheckLevel.WARNING,
                tr("a gateway is announced on this Wi-Fi: go through it to control the robot across the garden", "une passerelle est annoncée sur ce Wi-Fi : passez par elle pour piloter le robot dans tout le jardin"), CheckFix.USE_GATEWAY)
            else CheckItem(tr("Range", "Portée"), CheckLevel.WARNING,
                tr("direct Bluetooth only reaches a few meters: a gateway on the robot is needed across the garden", "le Bluetooth direct ne porte qu'à quelques mètres : une passerelle sur le robot est nécessaire dans tout le jardin"), CheckFix.PAIR_GATEWAY))
        add(cutoff(robot))
    }

    /** Phone mounted on the robot: its own checks, the robot it holds and the pilot. */
    fun gateway(checks: List<PhoneCheck>, running: Boolean, robotHeld: Boolean, robotLabel: String, pilot: String?): List<CheckItem> = buildList {
        add(if (running) CheckItem(tr("Gateway service", "Service passerelle"), CheckLevel.OK, tr("started", "démarré"))
            else CheckItem(tr("Gateway service", "Service passerelle"), CheckLevel.FAILED, tr("stopped", "arrêté"), CheckFix.PAIR_GATEWAY))
        checks.forEach { check ->
            add(CheckItem(check.label, if (check.ok) CheckLevel.OK else if (check.fix == PhoneFix.DO_NOT_DISTURB) CheckLevel.WARNING else CheckLevel.FAILED, check.detail,
                when (check.fix) { PhoneFix.BLUETOOTH -> CheckFix.BLUETOOTH_SETTINGS; PhoneFix.WIFI -> CheckFix.WIFI_SETTINGS
                    PhoneFix.BATTERY -> CheckFix.BATTERY; PhoneFix.DO_NOT_DISTURB -> CheckFix.DO_NOT_DISTURB; null -> null }))
        }
        add(CheckItem("Robot", if (robotHeld) CheckLevel.OK else CheckLevel.WARNING, robotLabel))
        add(CheckItem(tr("Pilot", "Pilote"), if (pilot != null) CheckLevel.OK else CheckLevel.SKIPPED, pilot ?: tr("none connected right now", "aucun connecté en ce moment")))
    }

    /** Placement of the gateway phone, from its Bluetooth signal to the robot. */
    fun placement(rssi: Int?): CheckItem = when {
        rssi == null -> CheckItem(tr("Gateway on the robot", "Passerelle sur le robot"), CheckLevel.SKIPPED, tr("signal not measured yet", "signal pas encore mesuré"))
        rssi >= PLACED_DBM -> CheckItem(tr("Gateway on the robot", "Passerelle sur le robot"), CheckLevel.OK, tr("well placed ($rssi dBm)", "bien posée ($rssi dBm)"))
        rssi >= WEAK_DBM -> CheckItem(tr("Gateway on the robot", "Passerelle sur le robot"), CheckLevel.WARNING,
            tr("$rssi dBm: lay the phone flat on top of the robot, as close as possible (−50 dBm or better expected)",
                "$rssi dBm : posez le téléphone à plat sur le dessus du robot, au plus près (−50 dBm ou mieux attendu)"))
        else -> CheckItem(tr("Gateway on the robot", "Passerelle sur le robot"), CheckLevel.FAILED,
            tr("$rssi dBm: the gateway phone is not on the robot, or too far from it", "$rssi dBm : le téléphone passerelle n'est pas sur le robot, ou trop loin"))
    }

    /** Gateway Wi-Fi measured while the robot charges: the docking and recharge happen there. */
    fun baseWifi(health: GatewayHealth?, charging: Boolean): CheckItem = when {
        !charging || health == null || !health.wifiKnown -> CheckItem(tr("Wi-Fi at the base", "Wi-Fi à la base"), CheckLevel.SKIPPED,
            tr("measured when the robot is charging at its base", "mesuré quand le robot charge à sa base"))
        health.wifiRssiDbm <= WIFI_WEAK_DBM -> CheckItem(tr("Wi-Fi at the base", "Wi-Fi à la base"), CheckLevel.WARNING,
            tr("${health.wifiRssiDbm} dBm: weak, the link may drop during docking; move the access point closer or add a repeater",
                "${health.wifiRssiDbm} dBm : faible, la liaison peut tomber pendant l'accostage ; rapprochez le point d'accès ou ajoutez un répéteur"))
        else -> CheckItem(tr("Wi-Fi at the base", "Wi-Fi à la base"), CheckLevel.OK, "${health.wifiRssiDbm} dBm")
    }

    private fun cutoff(robot: Robot) = when {
        robot.watchdogDemonstrated -> CheckItem(tr("Command cutoff", "Coupure des commandes"), CheckLevel.OK, tr("the robot stops on its own", "le robot s'arrête seul"))
        robot.watchdogTested -> CheckItem(tr("Command cutoff", "Coupure des commandes"), CheckLevel.WARNING,
            tr("not demonstrated: keep the physical STOP within reach", "non démontrée : gardez l'arrêt physique à portée"), CheckFix.COMMAND_CUTOFF)
        else -> CheckItem(tr("Command cutoff", "Coupure des commandes"), CheckLevel.WARNING,
            tr("not tested yet: test it before any mowing", "pas encore testée : testez-la avant toute tonte"), CheckFix.COMMAND_CUTOFF)
    }

    /**
     * Robot absent from a search: it stops advertising while another device holds its only Bluetooth link. Names the
     * remembered robot when known.
     */
    fun notFoundHint(known: String?, viaGateway: Boolean): String {
        val who = known?.takeIf { it.isNotBlank() }
        return when {
            who != null -> tr("$who does not advertise: off, out of range${if (viaGateway) " of the gateway" else ""}, or connected to the Mammotion app, another phone or a gateway. Close the Mammotion app, then search again.",
                "$who ne s'annonce pas : éteint, hors de portée${if (viaGateway) " de la passerelle" else ""}, ou connecté à l'app Mammotion, à un autre téléphone ou à une passerelle. Fermez l'app Mammotion puis relancez la recherche.")
            else -> tr("No robot found: off, out of range${if (viaGateway) " of the gateway" else ""}, or connected to the Mammotion app or another phone. Close the Mammotion app, then search again.",
                "Aucun robot trouvé : éteint, hors de portée${if (viaGateway) " de la passerelle" else ""}, ou connecté à l'app Mammotion ou à un autre téléphone. Fermez l'app Mammotion puis relancez la recherche.")
        }
    }
}
