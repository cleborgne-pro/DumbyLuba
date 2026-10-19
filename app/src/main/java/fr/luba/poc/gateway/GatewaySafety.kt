package fr.luba.poc.gateway

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.Blufi
import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.TelemetryReducer

/**
 * Safety stop decided by the gateway when the pilot phone goes silent.
 *
 * The gateway reads back the BluFi frames actually written (sequence, fragmented message in progress,
 * commanded blades, height) and the received telemetry (blade bits, height). When the pilot is lost, it
 * sends by itself, continuing the BluFi sequence: blades OFF if they may be turning, then zero speed.
 * If a fragmented message is unfinished, no frame can be injected without being concatenated to that
 * message: the link is then closed (stop not guaranteed, reported).
 */
class GatewaySafety(private val luba2: Boolean = true) {
    private val outgoing = Blufi()
    private val incoming = Blufi()
    private val reducer = TelemetryReducer()
    private var lastSeq: Int? = null
    private var commandedBlades = false
    private var commandedHeightMm: Int? = null
    var corrupted = false
        private set

    /** Frame successfully written to FF01. */
    fun written(frame: ByteArray) {
        if (frame.size >= 4) lastSeq = frame[2].toInt() and 255
        val payload = try { outgoing.receive(frame) } catch (_: Exception) { corrupted = true; null } ?: return
        val message = runCatching { LubaMsg.parseFrom(payload) }.getOrNull() ?: return
        if (message.hasDriver() && message.driver.hasMowCtrlByHand()) {
            val hand = message.driver.mowCtrlByHand
            commandedBlades = hand.cutKnifeCtrl == 1
            if (hand.cutKnifeHeight in 25..70) commandedHeightMm = hand.cutKnifeHeight
        }
        if (message.hasDriver() && message.driver.hasTodevKnifeHeightSet()) {
            message.driver.todevKnifeHeightSet.knifeHeight.takeIf { it in 25..70 }?.let { commandedHeightMm = it }
        }
        if (message.hasSys() && message.sys.hasTodevKnifeCtrl()) commandedBlades = message.sys.todevKnifeCtrl.knifeStatus == 1
    }

    /** Decoded robot message, handed to the gateway's autonomous tracking (telemetry, errors, lift). */
    @Volatile var onMessage: ((LubaMsg) -> Unit)? = null

    /** FF02 notification relayed to the pilot. */
    fun notified(frame: ByteArray, now: Long = System.currentTimeMillis()) {
        val payload = runCatching { incoming.receive(frame) }.getOrNull() ?: return
        if (payload.firstOrNull() == '{'.code.toByte()) return
        val message = runCatching { LubaMsg.parseFrom(payload) }.getOrNull() ?: return
        runCatching { reducer.accept(message, now, now) }
        onMessage?.let { listener -> runCatching { listener(message) } }
    }

    /** Blades possibly turning: ON command not followed by OFF, or non-zero robot state. */
    val bladesMayTurn: Boolean get() = commandedBlades ||
        (luba2 && reducer.value.sensorStatus?.let { ((it shr 9) and 7) != 0 } == true)

    /** Unfinished fragmented message: an injected frame would be concatenated to it. */
    val midMessage: Boolean get() = outgoing.assembling

    /** Fragmented notification being received: a new pilot must wait for the end of the message. */
    val incomingAssembling: Boolean get() = incoming.assembling

    /** Stop messages (blades OFF if needed, zero speed), or null if a fragmented message is unfinished. */
    fun emergencyPayloads(now: Long = System.currentTimeMillis()): List<ByteArray>? {
        if (midMessage || corrupted) return null
        val commands = Commands()
        return buildList {
            if (bladesMayTurn) add(commands.manualBlades(false, commandedHeightMm ?: reducer.value.bladeHeightMm?.takeIf { it in 25..70 } ?: 60, now))
            add(commands.stop(now))
        }
    }

    /** Stop frames continuing the BluFi sequence, or null if injection is impossible. */
    fun emergencyFrames(mtu: Int, now: Long = System.currentTimeMillis()): List<ByteArray>? {
        val payloads = emergencyPayloads(now) ?: return null
        val blufi = Blufi().apply { lastSeq?.let { continueAfter(it) } }
        return payloads.flatMap { blufi.encode(it, mtu) }
    }
}
