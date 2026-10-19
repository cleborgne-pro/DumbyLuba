package fr.luba.poc.ble

import fr.luba.poc.proto.LubaMsgProto.LubaMsg

/**
 * Motion setpoints (linear / angular speed): only the most recent one means anything to the robot.
 *
 * When writing is slow (gateway Wi-Fi: 300 to 800 ms per frame, measured on 2026-10-11), setpoints sent at 5 Hz
 * piled up behind the write in progress, the queue went past 4 frames and the route stopped ("BLE interrupted or
 * queue saturated"), while the robot would have executed stale setpoints anyway.
 * A new setpoint therefore replaces the non-zero setpoints still pending. Zero setpoints (stop) are never removed,
 * nor are the fragments of a message whose write has started.
 */
object MotionSetpoints {
    data class Setpoint(val linear: Int, val angular: Int) {
        val zero: Boolean get() = linear == 0 && angular == 0
    }

    /** Motion setpoint carried by [payload], or null if it is not a motion command. */
    fun of(payload: ByteArray): Setpoint? = runCatching { LubaMsg.parseFrom(payload) }.getOrNull()
        ?.takeIf { it.hasDriver() && it.driver.hasTodevDevmotionCtrl() }
        ?.driver?.todevDevmotionCtrl?.let { Setpoint(it.setLinearSpeed, it.setAngularSpeed) }

    /**
     * Removes from [pending] the non-zero setpoints replaced by [incoming] (nothing if [incoming] is not a
     * setpoint). [keep] protects the items to retain (rest of a message being written).
     * Returns the number of items removed.
     */
    fun <T> coalesce(pending: MutableList<T>, incoming: Setpoint?, setpointOf: (T) -> Setpoint?, keep: (T) -> Boolean = { false }): Int {
        if (incoming == null) return 0
        val before = pending.size
        pending.removeAll { item -> !keep(item) && setpointOf(item)?.zero == false }
        return before - pending.size
    }

    /**
     * Adds [items] (frames of one message) to [pending]: a motion setpoint takes the place of the first setpoint it
     * replaces ([coalesce]) instead of going behind the commands queued in the meantime.
     * Appended at the end of the queue, it moved back on every replacement and was never written as long as
     * keep-alive commands arrived faster than the link could write them (test of 2026-10-13).
     * A stop pending after that slot keeps its order: the setpoint then goes to the end of the queue.
     * Returns the number of setpoints replaced.
     */
    fun <T> enqueue(pending: MutableList<T>, items: List<T>, incoming: Setpoint?, setpointOf: (T) -> Setpoint?, keep: (T) -> Boolean = { false }): Int {
        var slot = if (incoming == null) -1 else pending.indexOfFirst { !keep(it) && setpointOf(it)?.zero == false }
        // Stop (zero setpoint) pending after that slot: the new setpoint stays behind it, as before.
        if (slot >= 0 && (slot until pending.size).any { setpointOf(pending[it])?.zero == true }) slot = -1
        val removed = coalesce(pending, incoming, setpointOf, keep)
        // Items before [slot] are not replaceable setpoints: the index stays valid after the removal.
        if (slot >= 0) pending.addAll(slot, items) else pending.addAll(items)
        return removed
    }

    /**
     * Periodic command (same [label]: error polling, manual-mode keep-alive) still pending or being written: no need
     * to queue a second one.
     *
     * Test of 2026-10-13 (drive to a point through the gateway): writing went from 50 ms to 300-750 ms per frame;
     * the two keep-alive commands, re-sent every 500 ms, then took up the whole link. Only the motion setpoint was
     * replaced by the most recent one: none was written any more, and the robot stopped after about 1 s, every 30 cm.
     */
    fun <T> periodicPending(pending: Iterable<T>, inFlight: T?, label: String, labelOf: (T) -> String?): Boolean =
        (inFlight != null && labelOf(inFlight) == label) || pending.any { labelOf(it) == label }
}
