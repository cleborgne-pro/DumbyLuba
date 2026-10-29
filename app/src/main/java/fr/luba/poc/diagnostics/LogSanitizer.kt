package fr.luba.poc.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Removes personal data from exported logs so that an archive can be attached to a public ticket as is.
 *
 * One instance per export: the same robot name, MAC or IP address gets the same pseudonym in every file of the
 * archive, so the events stay comparable. What remains is what an analysis needs: events, timings, decoded
 * messages, map geometry in the robot's local frame (metres from its RTK base, meaningless without the base's
 * position, which is removed).
 *
 * Removed or replaced:
 * - absolute position: `latitude`, `longitude`, `lat`, `lon` of the decoded messages (RTK base, NavLatLonUp);
 * - identifiers: robot names (model prefix kept, `Luba-VS‹1›`), RTK base number, device names and keys, IMEI/IMSI/ICCID,
 *   user and device ids, MAC addresses, names of the other Bluetooth devices paired with the phone;
 * - network: Wi-Fi names and passwords, IP addresses (local, or of a connection error), mobile network credentials;
 * - account: e-mail addresses, tokens, the Mammotion account number (`subtype` of every protobuf message, which the
 *   robot echoes back, `user_account` / `userAccount` and the other account fields), in a protobuf dump, a JSON field or
 *   a free text;
 * - raw data that cannot be filtered: received BLE frames (their decoded form stays), raw ZMQ messages.
 */
class LogSanitizer {
    private val pseudonyms = HashMap<String, String>()
    private val counters = HashMap<String, Int>()
    /** Number of replacements per category, written in the archive's report. */
    private val tally = sortedMapOf<String, Int>()
    val counts: Map<String, Int> get() = tally

    /** One JSONL line; a line that is not JSON is filtered as text. */
    fun line(line: String): String {
        if (line.isBlank()) return line
        val parsed = try { JSONObject(line) } catch (_: Exception) { return text(line) }
        return event(parsed).toString()
    }

    /** A whole JSON document (test report); filtered as text if it does not parse. */
    fun document(content: String): String {
        val parsed = try { JSONObject(content) } catch (_: Exception) { return text(content) }
        return (walk(parsed, null) as JSONObject).toString(2)
    }

    private fun event(line: JSONObject): JSONObject {
        val kind = line.optString("event")
        if (kind == "ble_rx") {
            // Raw notification fragments: telemetry carries the RTK position; the decoded form follows in protobuf_decoded.
            val hex = line.optString("detail")
            line.put("detail", "‹BLE frame removed, ${hex.length / 2} bytes›"); count("ble frame")
        }
        // Text preview of a raw ZMQ message from the robot: unknown content.
        if (kind == "sensor_probe_zmq_msg") { line.put("detail", "‹ZMQ message removed›"); count("zmq message") }
        if (kind in OTHER_DEVICE_EVENTS) line.optJSONObject("fields")?.let { fields ->
            val name = fields.optString("name", "")
            if (name.isNotEmpty() && !ROBOT_NAME.containsMatchIn(name)) { fields.put("name", "‹other device›"); count("device name") }
        }
        return walk(line, null) as JSONObject
    }

    private fun walk(value: Any?, key: String?): Any? = when {
        value is JSONObject -> JSONObject().also { result ->
            value.keys().forEach { k -> result.put(k, walk(value.opt(k), k)) }
        }
        value is JSONArray -> JSONArray().also { result -> for (i in 0 until value.length()) result.put(walk(value.opt(i), key)) }
        // Account number: removed whatever its JSON type (number or string).
        key in ACCOUNT_KEYS && value != JSONObject.NULL -> { count("account number"); "‹removed›" }
        value !is String -> value
        key in REDACTED_KEYS -> { count(key!!); "‹removed›" }
        key in PROTOBUF_KEYS -> text(protobuf(value))
        else -> text(value)
    }

    /** Protobuf text format (`message.toString()`): the value of each sensitive field is replaced. */
    fun protobuf(dump: String): String = PROTOBUF_FIELD.replace(dump) { match ->
        val field = match.groupValues[2]
        count(when (field) { in POSITION_FIELDS -> "position"; in ACCOUNT_FIELDS -> "account number"; else -> field })
        "${match.groupValues[1]}$field: \"‹removed›\""
    }

    /** Patterns looked for in every text: robot names, MAC and IP addresses, e-mails, tokens. */
    fun text(value: String): String {
        if (value.isEmpty()) return value
        var out = ROBOT_NAME.replace(value) { m -> count("robot name"); "${m.groupValues[1]}-${m.groupValues[2]}‹${pseudonym("robot", m.value.lowercase())}›" }
        out = MAC.replace(out) { m -> count("mac"); "‹mac-${pseudonym("mac", m.value.lowercase())}›" }
        out = IP.replace(out) { m -> count("ip"); "‹ip-${pseudonym("ip", m.value)}›" }
        out = EMAIL.replace(out) { count("email"); "‹email›" }
        out = JWT.replace(out) { count("token"); "‹token›" }
        out = ACCOUNT_TEXT.replace(out) { m -> count("account number"); "${m.groupValues[1]}‹removed›" }
        return out
    }

    private fun pseudonym(kind: String, value: String): String = pseudonyms.getOrPut("$kind:$value") {
        (counters.getOrDefault(kind, 0) + 1).also { counters[kind] = it }.toString()
    }
    private fun count(category: String) { tally[category] = tally.getOrDefault(category, 0) + 1 }

    /** Summary written at the root of the archive. */
    fun report(photosLeftOut: Int): String = JSONObject()
        .put("anonymized", true)
        .put("note", "Personal data removed by the app before export: robot names (model prefix kept), RTK base " +
            "number and position, latitude/longitude, MAC and IP addresses, Wi-Fi and mobile network data, device " +
            "keys and ids, e-mails, tokens, Mammotion account number, other paired Bluetooth devices, raw BLE frames " +
            "and raw ZMQ messages. " +
            "Map and positions stay in the robot's local frame. Obstacle photos are not included.")
        .put("replacements", JSONObject(counts as Map<*, *>))
        .put("photosLeftOut", photosLeftOut)
        .toString(2)

    companion object {
        /** Mammotion robot or RTK base names (`Luba-VSxxxx`, `Yuka-MNxxxx`…): the model prefix is kept. */
        private val ROBOT_NAME = Regex("""\b(Luba|Yuka|Ezy|Spino|Kumar|RTK)-([A-Za-z0-9]{2})[A-Za-z0-9]{3,}\b""")
        private val MAC = Regex("""(?i)(?<![0-9a-f:])[0-9a-f]{2}(?::[0-9a-f]{2}){5}(?![0-9a-f:])""")
        // Private, link-local and carrier-grade ranges, or any address after "/" or before ":port" (socket errors);
        // firmware versions ("1.12.5.3") are left alone.
        private val IP = Regex("""(?<![\w.])(?:(?:10(?:\.\d{1,3}){3}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|192\.168(?:\.\d{1,3}){2}|169\.254(?:\.\d{1,3}){2}|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])(?:\.\d{1,3}){2})(?![\w.]*\d)|(?<=/)\d{1,3}(?:\.\d{1,3}){3}(?![\w.])|\d{1,3}(?:\.\d{1,3}){3}(?=:\d{1,5}\b))""")
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
        private val JWT = Regex("""eyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}""")

        private val POSITION_FIELDS = setOf("latitude", "longitude", "lat", "lon")
        /**
         * Protobuf fields holding the Mammotion account number: `subtype` of `LubaMsg`, set by the app to
         * `userInformation.userAccount` and echoed by the robot in every reply (DumbyLuba#3: in clear in the decoded
         * messages of a public export), and the account of a manual control takeover (`preemptAccount`).
         */
        private val ACCOUNT_FIELDS = setOf("subtype", "user_account", "userAccount", "preemptAccount")
        /** JSON keys of the account number, at any depth of a log line or report. */
        private val ACCOUNT_KEYS = ACCOUNT_FIELDS + setOf("user_account_id", "userAccountId", "accountId", "accountID", "account_id")
        /** Account number written in a free text: `userAccount=12345678`, `subtype: 12345678`, `"user_account":"12345678"`, negative values included (the sign is removed with the digits). */
        private val ACCOUNT_TEXT = Regex("""(?i)(\b(?:subtype|user_?account(?:_?id)?|preempt_?account)"?\s*[:=]\s*"?)-?\d+""")
        /** Fields of the Mammotion protos (app/src/main/proto) whose value identifies the user, the robot or the garden. */
        private val SENSITIVE_FIELDS = POSITION_FIELDS + ACCOUNT_FIELDS + setOf(
            "rtk_base_num", "rtk_url", "rtk_username", "rtk_password",
            "wifi_ssid", "ssid", "msgssid", "Confssid", "Memssid", "wifi_password", "password", "username", "apn_name", "wifi_mac",
            "ip", "gateway", "serverIp", "url", "tx_zmq_url", "sub_img_url",
            "imei", "imsi", "iccid",
            "productkey", "product_key", "product_secret", "device_secret", "devicename", "device_name", "origin_device_name", "devname",
            "deviceId", "deviceid", "userId", "userid", "accountID", "account", "identity_data", "identify", "decryption_key", "token",
        )
        private val PROTOBUF_FIELD = Regex("""(?m)^(\s*)(${SENSITIVE_FIELDS.joinToString("|")}):[ \t].*$""")
        /** Keys of the log fields carrying raw data that cannot be filtered. */
        private val REDACTED_KEYS = setOf("parts", "samples", "network", "password", "token", "accessToken", "refreshToken", "email")
        /** Keys holding a protobuf text dump. */
        private val PROTOBUF_KEYS = setOf("decoded", "mapNav", "lastPerception")
        /** Events listing Bluetooth devices seen or paired with the phone, not only robots. */
        private val OTHER_DEVICE_EVENTS = setOf("scan_advertisement", "scan_result", "bonded_device", "auto_connect", "gateway_robot")
    }
}
