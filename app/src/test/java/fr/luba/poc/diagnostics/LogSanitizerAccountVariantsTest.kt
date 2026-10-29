package fr.luba.poc.diagnostics

import fr.luba.poc.EventLog
import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.DevNetProto.GetNetworkInfoRsp
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlDriverProto.DrvSessionCtrlAck
import fr.luba.poc.proto.MctrlDriverProto.MctlDriver
import fr.luba.poc.proto.MctrlPeptProto.MctlPept
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mammotion account number (DumbyLuba#3) in every format it can take in a log line or an export: protobuf dump,
 * JSON value (number, negative, string, nested), free text with `subtype:` at the start of a line, negative value.
 * Fictitious account numbers only.
 */
class LogSanitizerAccountVariantsTest {
    private val account = 87654321
    /** Account number above 2^31 read back as a signed int32 (`subtype` is `int32`). */
    private val negative = -1234567890

    private fun line(event: String, detail: String, fields: Map<String, Any?> = emptyMap()) = JSONObject()
        .put("schemaVersion", 2).put("event", event).put("detail", detail).put("fields", EventLog.json(fields)).toString()

    private fun reply(subtype: Int) = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_ESP).setSender(MsgDevice.DEV_COMM_ESP)
        .setRcver(MsgDevice.DEV_MOBILEAPP).setSubtype(subtype).setSeqs(12)
        .setNet(DevNet.newBuilder().setToappNetworkinfoRsp(GetNetworkInfoRsp.newBuilder().setReqIds(1))).build()

    private fun assertNoAccount(out: String, vararg numbers: Int) =
        numbers.forEach { n -> assertFalse("$n left in: $out", out.contains(Math.abs(n.toLong()).toString())) }

    @Test fun negativeSubtypeInADecodedMessageIsRemoved() {
        val out = LogSanitizer().line(line("protobuf_decoded", "MSG_CMD_TYPE_ESP seq=12", mapOf("decoded" to reply(negative).toString())))
        assertNoAccount(out, negative)
        assertTrue(out, JSONObject(out).getJSONObject("fields").getString("decoded").contains("subtype: \"‹removed›\""))
    }

    @Test fun nestedAccountFieldsInADecodedMessageAreRemoved() {
        // Control takeover acknowledgement: preemptAccount nested in the driver message.
        val takeover = LubaMsg.newBuilder().setSubtype(account).setDriver(MctlDriver.newBuilder()
            .setToappSessionCtrlAck(DrvSessionCtrlAck.newBuilder().setPreemptAccount(negative))).build()
        val sanitizer = LogSanitizer()
        val out = sanitizer.line(line("protobuf_decoded", "driver", mapOf("decoded" to takeover.toString())))
        assertNoAccount(out, account, negative)
        // Sensor probe report: last perception message.
        val perception = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_PEPT).setSubtype(negative)
            .setPept(MctlPept.getDefaultInstance()).build()
        assertNoAccount(sanitizer.document(JSONObject().put("lastPerception", perception.toString()).toString()), negative)
    }

    @Test fun jsonValuesNumberNegativeStringAndNestedAreRemoved() {
        val sanitizer = LogSanitizer()
        val out = sanitizer.line(line("cloud_session", "session", mapOf(
            "subtype" to negative,
            "userAccount" to "$account",
            "user_account" to account.toLong(),
            "preemptAccount" to "$negative",
            "session" to mapOf("userInformation" to mapOf("userAccount" to "$account", "userAccountId" to account)),
            "list" to listOf(mapOf("user_account" to negative), mapOf("accountId" to "$account")),
        )))
        assertNoAccount(out, account, negative)
        val fields = JSONObject(out).getJSONObject("fields")
        listOf("subtype", "userAccount", "user_account", "preemptAccount").forEach { assertEquals(it, "‹removed›", fields.getString(it)) }
        // Whole document (test report), account fields at any depth.
        val document = sanitizer.document(JSONObject().put("a", JSONObject().put("b", org.json.JSONArray().put(JSONObject().put("subtype", negative)))).toString())
        assertNoAccount(document, negative)
    }

    @Test fun freeTextSubtypeAtTheStartOfALineIsRemoved() {
        val sanitizer = LogSanitizer()
        // A protobuf dump written as plain text (detail of an event, not a `decoded` field).
        val out = sanitizer.line(line("map_error", reply(account).toString()))
        assertNoAccount(out, account)
        assertNoAccount(sanitizer.text("seqs: 3\nsubtype: $account\nversion: 1"), account)
        assertNoAccount(sanitizer.text("subtype:$account"), account)
        assertNoAccount(sanitizer.text("SUBTYPE = $account"), account)
        assertNoAccount(sanitizer.text("user_account=\"$account\" userAccount: $account preemptAccount: $account userAccountId=$account"), account)
        assertNoAccount(sanitizer.text("{\"user_account\":\"$account\",\"subtype\":$account}"), account)
        // Ordinary numbers next to it are kept.
        assertEquals("seqs: 3", sanitizer.text("seqs: 3"))
    }

    @Test fun freeTextNegativeSubtypeIsRemoved() {
        val sanitizer = LogSanitizer()
        assertNoAccount(sanitizer.text("subtype: $negative"), negative)
        assertNoAccount(sanitizer.text("seqs: 3\nsubtype: $negative\nversion: 1"), negative)
        assertNoAccount(sanitizer.text("{\"userAccount\":$negative}"), negative)
        assertNoAccount(sanitizer.line(line("map_error", reply(negative).toString())), negative)
    }
}
