package fr.luba.poc.gateway

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr

/**
 * Wi-Fi tunnel between the pilot phone and the gateway phone mounted on the robot.
 * TCP, frames `[type u8][length u16][payload]`. The gateway does not interpret commands:
 * it writes the received BluFi frames to FF01 and sends back the FF02 notifications, one operation at a time.
 *
 * Authentication: random challenge from the gateway, HMAC-SHA256 answer computed from the six-digit code
 * (the code never travels in clear). Both sides send a [PING] every [PING_MS];
 * [SILENCE_MS] without any frame = link lost (the gateway then stops the robot itself).
 */
object GatewayProtocol {
    /** 2: HEALTH frame (gateway battery, Wi-Fi, thermal state) sent to the pilot. */
    const val VERSION = 2
    const val PORT = 8766
    const val SERVICE_TYPE = "_dumbyluba._tcp."
    const val SERVICE_NAME = "DumbyLuba passerelle"
    /** Maximum frame payload (u16). Autonomous tracking frames (obstacles, outcome with trace) sometimes exceed 4 KB. */
    const val MAX_PAYLOAD = 16384
    /**
     * Capabilities announced by the gateway after its name, in WELCOME (`name\ncaps=a,b`). `autonomy`: it can follow
     * a route prepared by the pilot without it (frames 0x80..0x97 of [fr.luba.poc.autonomy.LegFrames]). A gateway that
     * does not announce it (ESP32) is driven frame by frame, as before; the protocol version did not change.
     */
    const val CAP_AUTONOMY = "autonomy"
    /** The gateway can run the station exit itself ([fr.luba.poc.autonomy.LegSpec.dockExit]). */
    const val CAP_DOCK_EXIT = "dock-exit"
    /**
     * The gateway updates itself over Wi-Fi (ESP32): it accepts the [CONFIG] frame and announces a planned restart
     * with the [OTA_BEGIN_EVENT] event. A gateway that does not announce it never receives [CONFIG].
     */
    const val CAP_OTA = "ota"
    /** Event (EVENT frame): the gateway is about to restart for an update; robot stopped, back in ~1 min. */
    const val OTA_BEGIN_EVENT = "ota_begin"
    /** [CONFIG] setting: update accepted while the pilot holds the robot (mowing in progress). */
    const val CONFIG_OTA_DURING_MOWING = "ota_during_mowing"
    /** [CONFIG] payload: `key=value` lines. */
    fun config(otaDuringMowing: Boolean): ByteArray =
        "$CONFIG_OTA_DURING_MOWING=${if (otaDuringMowing) 1 else 0}".toByteArray(Charsets.UTF_8)
    const val GATEWAY_NAME = "DumbyLuba passerelle"
    fun welcome(capabilities: List<String> = listOf(CAP_AUTONOMY, CAP_DOCK_EXIT)): ByteArray =
        (GATEWAY_NAME + if (capabilities.isEmpty()) "" else "\ncaps=" + capabilities.joinToString(",")).toByteArray(Charsets.UTF_8)
    fun capabilities(welcome: Frame): Set<String> = welcome.text().lineSequence().drop(1).firstOrNull { it.startsWith("caps=") }
        ?.removePrefix("caps=")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** Period at which the gateway health is sent to the pilot. */
    const val HEALTH_MS = 5000L
    /**
     * Period at which the gateway phone tilt (terrain) is sent. It travels at the end of the HEALTH frame, ignored by
     * older pilots: the protocol stays at version 2, compatible with the ESP32 gateway.
     */
    const val TILT_MS = 500L
    const val PING_MS = 250L
    const val SILENCE_MS = 2000L
    const val NONCE_BYTES = 16

    // Gateway → client
    const val CHALLENGE = 0x01
    const val WELCOME = 0x03
    const val DENIED = 0x04
    const val SCAN_RESULT = 0x11
    const val SCAN_END = 0x12
    const val DISCOVERING = 0x21
    const val READY = 0x22
    const val LOST = 0x23
    const val FAILURE = 0x24
    const val WRITTEN = 0x31
    const val RSSI_RESULT = 0x33
    const val NOTIFY = 0x40
    const val EVENT = 0x70
    const val HEALTH = 0x71

    // Client → gateway
    const val HELLO = 0x02
    const val SCAN = 0x10
    const val OPEN = 0x20
    const val WRITE = 0x30
    const val RSSI = 0x32
    const val CLOSE = 0x60
    /** Pilot settings ([config]); only towards a gateway that announces [CAP_OTA]. */
    const val CONFIG = 0x61

    // Both directions
    const val PING = 0x50

    class Frame(val type: Int, val payload: ByteArray = ByteArray(0)) {
        fun text() = payload.toString(Charsets.UTF_8)
        fun int(offset: Int = 0) = ((payload[offset].toInt() and 255) shl 24) or ((payload[offset + 1].toInt() and 255) shl 16) or
            ((payload[offset + 2].toInt() and 255) shl 8) or (payload[offset + 3].toInt() and 255)
    }

    fun write(out: DataOutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        require(type in 0..255 && payload.size <= MAX_PAYLOAD) { tr("Invalid gateway frame", "Trame passerelle invalide") }
        synchronized(out) {
            out.writeByte(type)
            out.writeShort(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun read(input: DataInputStream): Frame {
        val type = input.read()
        if (type < 0) throw EOFException(tr("Gateway closed", "Passerelle fermée"))
        val size = input.readUnsignedShort()
        require(size <= MAX_PAYLOAD) { tr("Gateway frame too long", "Trame passerelle trop longue") }
        val payload = ByteArray(size)
        input.readFully(payload)
        return Frame(type, payload)
    }

    fun ints(vararg values: Int): ByteArray = ByteArray(values.size * 4).also { out ->
        values.forEachIndexed { index, value ->
            for (k in 0..3) out[index * 4 + k] = (value shr (24 - 8 * k)).toByte()
        }
    }

    fun proof(code: String, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("DumbyLuba-gateway-v$VERSION".toByteArray(Charsets.UTF_8))
        return mac.doFinal(nonce)
    }

    fun verify(code: String, nonce: ByteArray, proof: ByteArray) = MessageDigest.isEqual(proof(code, nonce), proof)

    /** HELLO answer: version then proof. */
    fun hello(code: String, nonce: ByteArray) = byteArrayOf(VERSION.toByte()) + proof(code, nonce)

    /** Scan result: rssi (int), address length (u8), address, name. */
    fun scanResult(name: String, address: String, rssi: Int): ByteArray {
        val a = address.toByteArray(Charsets.UTF_8)
        return ints(rssi) + byteArrayOf(a.size.toByte()) + a + name.toByteArray(Charsets.UTF_8)
    }

    /** Link request: robot address, then its name (optional) after a line feed. */
    fun open(address: String, name: String?) = (if (name.isNullOrBlank()) address else "$address\n$name").toByteArray(Charsets.UTF_8)
    fun parseOpen(frame: Frame): Pair<String, String?> = frame.text().split('\n', limit = 2).let { it[0] to it.getOrNull(1)?.takeIf(String::isNotBlank) }

    fun parseScanResult(frame: Frame): Triple<String, String, Int> {
        val rssi = frame.int(0)
        val size = frame.payload[4].toInt() and 255
        val address = String(frame.payload, 5, size, Charsets.UTF_8)
        val name = String(frame.payload, 5 + size, frame.payload.size - 5 - size, Charsets.UTF_8)
        return Triple(name, address, rssi)
    }
}

/**
 * Health of the gateway phone, reported to the pilot. -1 = unknown (-1000 for the Wi-Fi signal; booleans 0/1).
 * [batteryPercent] 0..100; [charging] 1 if plugged in; [batteryTempDeciC] in 1/10 °C; [wifiRssiDbm] and [wifiMbps] of the
 * tunnel Wi-Fi; [thermalStatus] 0 (none) to 6 (emergency shutdown), Android 10+; [powerSave] battery saver active;
 * [batteryExempt] app exempted from battery optimization; [screenOn] screen on.
 */
data class GatewayHealth(val batteryPercent: Int = -1, val charging: Boolean = false, val batteryTempDeciC: Int = -1,
    val wifiRssiDbm: Int = -1000, val wifiMbps: Int = -1, val thermalStatus: Int = -1,
    val powerSave: Boolean = false, val batteryExempt: Boolean = true, val screenOn: Boolean = false,
    /** Acceleration of the gateway phone (m/s², Android frame) and its age when sent, for the terrain; null if absent. */
    val gravity: fr.luba.poc.map.PhoneGravity? = null, val gravityAgeMs: Int = 0) {
    fun encode(): ByteArray = GatewayProtocol.ints(batteryPercent, if (charging) 1 else 0, batteryTempDeciC, wifiRssiDbm, wifiMbps,
        thermalStatus, if (powerSave) 1 else 0, if (batteryExempt) 1 else 0, if (screenOn) 1 else 0) +
        (gravity?.let { g -> GatewayProtocol.ints((g.x * 1000).toInt(), (g.y * 1000).toInt(), (g.z * 1000).toInt(), gravityAgeMs.coerceAtLeast(0)) }
            ?: ByteArray(0))

    /**
     * Reason to stop any autonomous tracking (even blades OFF). The battery is not part of it: if the phone mounted on the
     * robot shuts down, the link drops and the robot stops by itself; a blades-OFF move (return to base in particular)
     * is therefore allowed down to 0 %. Null if all is well (or state unknown).
     *
     * Computed on the gateway phone and sent to the pilot as an autonomous tracking stop reason: kept in French on the
     * wire, translated at display by [GatewayWireText].
     */
    fun autonomyFailure(): String? = when {
        thermalStatus >= 4 -> "Téléphone posé sur le robot en surchauffe critique (niveau $thermalStatus) : arrêt de sécurité"
        else -> null
    }

    /**
     * Reason to refuse or stop cutting (blades spinning): battery of the phone mounted on the robot at
     * [CUTTING_MIN_BATTERY] % or less while not charging. Blades-OFF moves stay allowed. Null otherwise (or state unknown).
     * Sent over the wire like [autonomyFailure]: kept in French.
     */
    fun cuttingFailure(): String? = when {
        batteryPercent in 0..CUTTING_MIN_BATTERY && !charging ->
            "Batterie du téléphone posé sur le robot à $batteryPercent % : lames interdites (déplacement lames OFF seulement)"
        else -> null
    }

    /** Warnings to show the pilot; empty if all is well. */
    fun warnings(): List<String> = buildList {
        if (batteryPercent in 0..14 && !charging) add(tr("gateway battery very low ($batteryPercent %)", "batterie de la passerelle très faible ($batteryPercent %)"))
        else if (batteryPercent in 15..24 && !charging) add(tr("gateway battery low ($batteryPercent %)", "batterie de la passerelle faible ($batteryPercent %)"))
        if (thermalStatus >= 2) add(tr("gateway overheating (level $thermalStatus)", "passerelle en surchauffe (niveau $thermalStatus)"))
        else if (batteryTempDeciC >= 450) add(tr("gateway battery hot (${batteryTempDeciC / 10} °C)", "batterie de la passerelle chaude (${batteryTempDeciC / 10} °C)"))
        // −72 dBm measured on the Redmi A1: latency peaks > 600 ms; the warning therefore starts at −70 dBm.
        if (wifiRssiDbm in -127..-70) add(tr("gateway Wi-Fi weak ($wifiRssiDbm dBm)", "Wi-Fi de la passerelle faible ($wifiRssiDbm dBm)"))
        if (powerSave) add(tr("battery saver active on the gateway", "économiseur d'énergie actif sur la passerelle"))
        if (!batteryExempt) add(tr("gateway not exempted from battery optimization", "passerelle non exemptée de l'optimisation batterie"))
    }

    companion object {
        /** Battery level (%) at or below which, while not charging, the blades no longer spin (blades-OFF moves allowed). */
        const val CUTTING_MIN_BATTERY = 4
        fun decode(payload: ByteArray): GatewayHealth? {
            if (payload.size < 9 * 4) return null
            fun int(i: Int) = ((payload[i * 4].toInt() and 255) shl 24) or ((payload[i * 4 + 1].toInt() and 255) shl 16) or
                ((payload[i * 4 + 2].toInt() and 255) shl 8) or (payload[i * 4 + 3].toInt() and 255)
            // Fields added at the end of the frame: absent from older gateways (and from the ESP32).
            val gravity = if (payload.size >= 13 * 4) fr.luba.poc.map.PhoneGravity(int(9) / 1000.0, int(10) / 1000.0, int(11) / 1000.0) else null
            return GatewayHealth(int(0), int(1) == 1, int(2), int(3), int(4), int(5), int(6) == 1, int(7) == 1, int(8) == 1,
                gravity, if (gravity != null) int(12) else 0)
        }
    }
}

/** Limits code attempts: [maxFailures] failures per [windowMs], then refusal until they expire. */
class AttemptLimiter(private val maxFailures: Int = 5, private val windowMs: Long = 60_000) {
    private val failures = ArrayDeque<Long>()
    @Synchronized fun blocked(now: Long): Boolean { purge(now); return failures.size >= maxFailures }
    @Synchronized fun failed(now: Long) { purge(now); failures.addLast(now) }
    private fun purge(now: Long) { while (failures.isNotEmpty() && now - failures.first() >= windowMs) failures.removeFirst() }
}

/**
 * Texts sent over the gateway protocol (DENIED, FAILURE and LOST reasons, autonomous tracking stop reasons) stay in
 * French byte for byte, whatever the language of either phone: older ESP32 firmware sends the same texts and the pilot
 * compares some of them (busy gateway, STOP written). [display] translates the known ones on the phone that shows them;
 * an unknown text is shown as received. Newer ESP32 firmware sends some refusals in English ([englishWire]): they are
 * shown in French on a French device. The busy refusal stays French on the wire (the pilot retries when it contains
 * "occupée").
 */
object GatewayWireText {
    private val exact: Map<String, String> = mapOf(
        "Trop d'essais : attendre une minute" to "Too many attempts: wait a minute",
        "Version de passerelle différente : mettre à jour les deux téléphones" to "Different gateway version: update both phones",
        "Code de passerelle incorrect" to "Wrong gateway code",
        "Passerelle occupée par un autre pilote" to "Gateway busy with another pilot",
        "Liaison robot déjà ouverte" to "Robot link already open",
        "Un téléphone de pilotage est branché : l'arrêter d'abord" to "A pilot phone is connected: stop it first",
        "Bluetooth indisponible sur la passerelle" to "Bluetooth unavailable on the gateway",
        "Bluetooth désactivé sur la passerelle" to "Bluetooth turned off on the gateway",
        "Scanner Bluetooth indisponible" to "Bluetooth scanner unavailable",
        "Robot non connecté en Bluetooth sur la passerelle" to "Robot not connected over Bluetooth on the gateway",
        "Message BluFi en cours d'écriture : réessayer" to "BluFi message being written: try again",
        "État du robot absent ou périmé sur la passerelle" to "Robot state missing or stale on the gateway",
        "Position du robot absente ou périmée sur la passerelle" to "Robot position missing or stale on the gateway",
        "Modèle de robot en lecture seule : suivi autonome refusé" to "Robot model in read-only mode: autonomous tracking refused",
        "Écriture du pilote pendant le suivi autonome" to "Pilot write during autonomous tracking",
        "opération GATT sans réponse" to "GATT operation without response",
        "connexion sans réponse" to "connection without response",
        "connexion Wi-Fi fermée" to "Wi-Fi connection closed",
        "autre robot choisi" to "another robot chosen",
        "robot libéré" to "robot released",
        "passerelle arrêtée" to "gateway stopped",
        "écriture bloquée" to "write stuck",
        "message fragmenté interrompu" to "fragmented message interrupted",
        "robot non connecté : aucun arrêt à écrire" to "robot not connected: no stop to write",
        "robot non connecté" to "robot not connected",
        "message BluFi en cours d'écriture" to "BluFi message being written",
        "liaison robot perdue : arrêt non transmis" to "robot link lost: stop not transmitted",
        "écriture GATT bloquée : liaison fermée, arrêt non garanti" to "GATT write stuck: link closed, stop not guaranteed",
        "message fragmenté en cours : arrêt non injectable, liaison fermée (arrêt non garanti)" to
            "fragmented message in progress: stop cannot be injected, link closed (stop not guaranteed)",
        "aucun arrêt nécessaire" to "no stop needed",
        "BLE interrompu : liaison Bluetooth du robot fermée" to "BLE interrupted: robot Bluetooth link closed",
        "mode manuel non quitté" to "manual mode not exited",
        "suivi autonome poursuivi sans pilote" to "autonomous tracking continued without pilot",
        "arrêt déjà traité à l'arrêt de la passerelle" to "stop already handled when the gateway stopped",
    )
    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("Trame inconnue (-?\\d+)") to { m -> "Unknown frame ${m.groupValues[1]}" },
        Regex("La passerelle tient déjà un autre robot \\((.*)\\)") to { m -> "The gateway already holds another robot (${m.groupValues[1]})" },
        Regex("passerelle arrêtée sur le téléphone posé sur le robot ; (.*)", RegexOption.DOT_MATCHES_ALL) to
            { m -> "gateway stopped on the phone mounted on the robot; ${display(m.groupValues[1])}" },
        Regex("STOP écrit par la passerelle \\((\\d+) trame\\(s\\)\\), liaison Bluetooth conservée") to
            { m -> "STOP written by the gateway (${m.groupValues[1]} frame(s)), Bluetooth link kept" },
        Regex("STOP non confirmé \\((.*)\\)") to { m -> "STOP not confirmed (${timeout(m.groupValues[1])})" },
        Regex("écriture non confirmée \\((.*)\\)") to { m -> "write not confirmed (${timeout(m.groupValues[1])})" },
        Regex("arrêt non transmis \\((.*)\\)") to { m -> "stop not transmitted (${m.groupValues[1]})" },
        Regex("BLE interrompu : écriture refusée par le robot \\(code (.*)\\)") to
            { m -> "BLE interrupted: write refused by the robot (code ${m.groupValues[1]})" },
        Regex("BLE interrompu : écriture sans confirmation du robot depuis (.*) s") to
            { m -> "BLE interrupted: write unconfirmed by the robot for ${m.groupValues[1].replace(',', '.')} s" },
        Regex("BLE interrompu : file d'écriture saturée \\((\\d+) trames en attente\\)") to
            { m -> "BLE interrupted: write queue saturated (${m.groupValues[1]} frames pending)" },
        Regex("Téléphone posé sur le robot en surchauffe critique \\(niveau (-?\\d+)\\) : arrêt de sécurité") to
            { m -> "Phone mounted on the robot critically overheating (level ${m.groupValues[1]}): safety stop" },
        Regex("Batterie du téléphone posé sur le robot à (-?\\d+) % : lames interdites \\(déplacement lames OFF seulement\\)") to
            { m -> "Battery of the phone mounted on the robot at ${m.groupValues[1]} %: blades forbidden (blades-OFF moves only)" },
        // Docking requested by the gateway without a pilot (GatewayRobot.dockAlone).
        Regex("accostage non demandé : (.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "docking not requested: ${display(m.groupValues[1])}" },
        Regex("accostage non confirmé : (.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "docking not confirmed: ${display(m.groupValues[1])}" },
        Regex("lames OFF (.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "blades OFF ${display(m.groupValues[1])}" },
        Regex("Accostage Mammotion demandé par la passerelle \\(pilote absent\\), à (-?\\d+) cm du point d'approche") to
            { m -> "Mammotion docking requested by the gateway (pilot absent), ${m.groupValues[1]} cm from the approach point" },
    )

    /** Refusals sent in English by newer ESP32 firmware, with their French wording (shown on a French device). */
    private val englishWire: Map<String, String> = mapOf(
        "Wrong gateway code" to "Code de passerelle incorrect",
        "Too many attempts: wait a minute" to "Trop d'essais : attendre une minute",
    )
    /** Time appended by the gateway to a stop report handed to the returning pilot (` (HH:mm:ss)`). */
    private val timeSuffix = Regex("(.*) (\\(\\d{2}:\\d{2}:\\d{2}\\))", RegexOption.DOT_MATCHES_ALL)

    private fun timeout(value: String) = if (value == "délai dépassé") "timed out" else value

    /** [wire] in the current language; a text that is not known is shown as received. */
    fun display(wire: String): String {
        if (I18n.french) return englishWire[wire] ?: wire
        exact[wire]?.let { return it }
        timeSuffix.matchEntire(wire)?.let { m -> display(m.groupValues[1]).takeIf { it != m.groupValues[1] }?.let { return "$it ${m.groupValues[2]}" } }
        for ((regex, english) in patterns) regex.matchEntire(wire)?.let { return english(it) }
        return wire
    }
}
