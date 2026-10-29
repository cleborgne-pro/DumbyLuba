package fr.luba.poc.diagnostics

import fr.luba.poc.EventLog
import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.DevNetProto.GetNetworkInfoRsp
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.mqtt_rtk_connect
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_location
import fr.luba.poc.proto.MctrlSysProto.rpt_rtk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    private fun line(event: String, detail: String, fields: Map<String, Any?> = emptyMap()) = JSONObject()
        .put("schemaVersion", 2).put("event", event).put("detail", detail).put("fields", EventLog.json(fields)).toString()

    /** JSON as sorted Kotlin values: org.json key order is not specified. */
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toSortedSet().associateWith { plain(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }

    @Test fun decodedTelemetryLosesRtkPositionAndBaseNumberButKeepsLocalPosition() {
        val report = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
            .setRtk(rpt_rtk.newBuilder().setGpsStars(31).setLatStd(12)
                .setMqttRtkInfo(mqtt_rtk_connect.newBuilder().setLatitude(48.8566123).setLongitude(2.3522456).setRtkBaseNum("RTKNB0123456789")))
            .addLocations(rpt_dev_location.newBuilder().setRealPosX(12345).setRealPosY(-6789)))).build()
        val out = LogSanitizer().line(line("protobuf_decoded", "MSG_CMD_TYPE_EMBED_SYS seq=1", mapOf("decoded" to report.toString())))
        assertFalse(out, out.contains("48.85")); assertFalse(out, out.contains("2.352")); assertFalse(out, out.contains("RTKNB0123456789"))
        val decoded = JSONObject(out).getJSONObject("fields").getString("decoded")
        assertTrue(decoded, decoded.contains("latitude: \"‹removed›\""))
        assertTrue(decoded, decoded.contains("lat_std: 12")) // precision, not a position
        assertTrue(decoded, decoded.contains("gps_stars: 31"))
        assertTrue(decoded, decoded.contains("real_pos_x: 12345")) // local frame, kept for the analysis
    }

    @Test fun networkInfoLosesSsidMacAndIp() {
        val info = LubaMsg.newBuilder().setNet(DevNet.newBuilder().setToappNetworkinfoRsp(GetNetworkInfoRsp.newBuilder()
            .setWifiSsid("Livebox-Dupont").setWifiMac("a4:5e:60:12:34:56").setIp(0x6401A8C0))).build()
        val out = LogSanitizer().line(line("protobuf_decoded", "net", mapOf("decoded" to info.toString())))
        assertFalse(out, out.contains("Dupont")); assertFalse(out, out.contains("a4:5e")); assertFalse(out, out.contains(0x6401A8C0.toString()))
    }

    @Test fun robotNamesMacsAndIpsGetStablePseudonymsAcrossFiles() {
        val sanitizer = LogSanitizer()
        val connect = JSONObject(sanitizer.line(line("connect", "Connecting Luba-VSABC1234 D4:3D:39:AA:BB:CC", mapOf("address" to "D4:3D:39:AA:BB:CC"))))
        assertEquals("Connecting Luba-VS‹1› ‹mac-1›", connect.getString("detail"))
        assertEquals("‹mac-1›", connect.getJSONObject("fields").getString("address"))
        val report = JSONObject(sanitizer.document(JSONObject().put("robot", "Luba-VSABC1234").put("other", "Luba-VAXYZ9876").toString()))
        assertEquals("Luba-VS‹1›", report.getString("robot")) // same robot, same pseudonym, model prefix kept
        assertEquals("Luba-VA‹2›", report.getString("other"))
        val error = sanitizer.text("failed to connect to /192.168.1.42 (port 8765) from /10.0.0.7 after 3000ms; gateway 192.168.1.42:8765")
        assertEquals("failed to connect to /‹ip-1› (port 8765) from /‹ip-2› after 3000ms; gateway ‹ip-1›:8765", error)
    }

    @Test fun firmwareVersionsAndModelNamesAreKept() {
        val sanitizer = LogSanitizer()
        assertEquals("firmware 1.12.5.38, model Luba-VS", sanitizer.text("firmware 1.12.5.38, model Luba-VS"))
        assertEquals("v 192.168.1", sanitizer.text("v 192.168.1"))
    }

    @Test fun otherPairedDevicesAreHiddenRobotsPseudonymized() {
        val sanitizer = LogSanitizer()
        val headset = JSONObject(sanitizer.line(line("bonded_device", "paired", mapOf("name" to "Jean's AirPods Pro", "address" to "11:22:33:44:55:66"))))
        assertEquals("‹other device›", headset.getJSONObject("fields").getString("name"))
        val robot = JSONObject(sanitizer.line(line("bonded_device", "paired", mapOf("name" to "Luba-VAQWERTY1"))))
        assertEquals("Luba-VA‹1›", robot.getJSONObject("fields").getString("name"))
    }

    @Test fun rawFramesAccountDataAndZmqAreRemoved() {
        val sanitizer = LogSanitizer()
        val rx = JSONObject(sanitizer.line(line("ble_rx", "0a0b0c0d")))
        assertEquals("‹BLE frame removed, 4 bytes›", rx.getString("detail"))
        val zmq = JSONObject(sanitizer.line(line("sensor_probe_zmq_msg", "#1 · 12 B · hello", mapOf("parts" to "68656c6c6f"))))
        assertEquals("‹ZMQ message removed›", zmq.getString("detail"))
        assertEquals("‹removed›", zmq.getJSONObject("fields").getString("parts"))
        val cloud = sanitizer.text("login jean.dupont@example.fr token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop")
        assertEquals("login ‹email› token ‹token›", cloud)
        val network = JSONObject(sanitizer.line(line("sensor_probe_zmq", "robot at 192.168.1.30", mapOf("network" to "Livebox · 192.168.1.30"))))
        assertEquals("‹removed›", network.getJSONObject("fields").getString("network"))
        assertEquals("robot at ‹ip-1›", network.getString("detail"))
    }

    @Test fun mapGeometryAndOrdinaryEventsAreUnchanged() {
        val sanitizer = LogSanitizer()
        val original = line("map_preview", "22 elements", mapOf("types" to listOf(0, 1, 25, 26), "runs" to listOf(mapOf("x" to 1.5, "y" to -2.25))))
        val out = JSONObject(sanitizer.line(original))
        assertEquals(plain(JSONObject(original)), plain(out))
        assertEquals("not json", sanitizer.line("not json"))
        assertTrue(sanitizer.counts.isEmpty())
        assertTrue(JSONObject(sanitizer.report(3)).getBoolean("anonymized"))
    }

    @Test fun mammotionAccountNumberIsRemovedFromDecodedMessagesJsonAndText() {
        // Fictitious account number, echoed by the robot in `subtype` of every reply (DumbyLuba#3).
        val account = 87654321
        val reply = LubaMsg.newBuilder().setSubtype(account).setSeqs(12)
            .setNet(DevNet.newBuilder().setToappNetworkinfoRsp(GetNetworkInfoRsp.newBuilder().setReqIds(1))).build()
        val sanitizer = LogSanitizer()
        val decodedLine = sanitizer.line(line("protobuf_decoded", "MSG_CMD_TYPE_ESP seq=12", mapOf("decoded" to reply.toString())))
        assertFalse(decodedLine, decodedLine.contains("$account"))
        val decoded = JSONObject(decodedLine).getJSONObject("fields").getString("decoded")
        assertTrue(decoded, decoded.contains("subtype: \"‹removed›\""))
        assertTrue(decoded, decoded.contains("seqs: 12"))
        // JSON field, number or string, at any depth; free text.
        val json = sanitizer.line(line("cloud_status", "userAccount=$account, subtype: $account", mapOf(
            "userAccount" to account, "session" to mapOf("user_account" to "$account", "userInformation" to mapOf("userAccount" to "$account")),
            "subtype" to account, "note" to "{\"user_account\":\"$account\"}", "seq" to 12)))
        assertFalse(json, json.contains("$account"))
        val fields = JSONObject(json).getJSONObject("fields")
        assertEquals("‹removed›", fields.getString("userAccount"))
        assertEquals("‹removed›", fields.getString("subtype"))
        assertEquals(12, fields.getInt("seq"))
        // Sensor probe report: last perception message dumped as protobuf text.
        val report = sanitizer.document(JSONObject().put("lastPerception", reply.toString()).toString())
        assertFalse(report, report.contains("$account"))
        assertTrue(sanitizer.counts.getValue("account number") >= 7)
        // Other numbers are left alone.
        assertEquals("firmware 1.10.5.246 seq=12", sanitizer.text("firmware 1.10.5.246 seq=12"))
    }
}
