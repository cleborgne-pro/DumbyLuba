package fr.luba.poc.ble

import android.os.SystemClock
import fr.luba.poc.diagnostics.FrameLogFilter
import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.Blufi

/**
 * Decodes the frames received from the robot into messages and logs them through a [FrameLogFilter]: raw frames only
 * when their message fails to decode, the periodic reports in full every few seconds, every other message each time.
 * BLE thread only.
 */
internal class ReceivedFrames(private val event: (String, String, Map<String, Any?>) -> Unit) {
    private val filter = FrameLogFilter()

    /** Whether the last decoded message was written in full: the lines that follow it can use the same throttling. */
    var lastWritten = true
        private set

    /**
     * The message completed by [bytes], or null while it is incomplete or when it is JSON from the legacy transport.
     * [writeAll] (sensor capture) writes every decoded message in full.
     */
    fun decode(blufi: Blufi, bytes: ByteArray, writeAll: Boolean): LubaMsg? {
        filter.received(bytes)
        val message = try {
            val payload = blufi.receive(bytes) ?: return null
            // The legacy transport may also carry JSON; no watchdog refresh.
            if (payload.firstOrNull() == '{'.code.toByte()) {
                filter.decoded()
                event("legacy_json", tr("BLE JSON received, not interpreted", "JSON BLE reçu, non interprété"), emptyMap())
                return null
            }
            LubaMsg.parseFrom(payload)
        } catch (error: Exception) {
            filter.undecoded().forEach { event("ble_rx", it.hex(), emptyMap()) }
            throw error
        }
        filter.decoded()
        logDecoded(message, writeAll)
        return message
    }

    private fun logDecoded(message: LubaMsg, writeAll: Boolean) {
        val subMessage = message.lubaSubMsgCase
        // Key and content of the sub-message: the envelope (sequence, robot timestamp) changes with every message.
        val (payload, content) = when (subMessage) {
            LubaMsg.LubaSubMsgCase.SYS -> message.sys.subSysMsgCase.name to message.sys
            LubaMsg.LubaSubMsgCase.NET -> message.net.netSubTypeCase.name to message.net
            else -> null to null
        }
        val key = FrameLogFilter.messageKey(subMessage.name, payload)
        lastWritten = writeAll || filter.writeDecoded(key, SystemClock.elapsedRealtime()) { content?.toString().orEmpty() }
        if (!lastWritten) return
        // Without `subtype`: the robot echoes the Mammotion account number there (DumbyLuba#3), only its presence is kept.
        val text = message.toBuilder().clearSubtype().build().toString()
        event("protobuf_decoded", "${message.msgtype} seq=${message.seqs}", mapOf("msgType" to message.msgtypeValue,
            "sender" to message.senderValue, "receiver" to message.rcverValue, "msgAttr" to message.msgattrValue,
            "protobufSeq" to message.seqs, "robotTimestamp" to message.timestamp, "accountEcho" to (message.subtype != 0),
            "decoded" to text))
    }
}
