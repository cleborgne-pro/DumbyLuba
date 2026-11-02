package fr.luba.poc.diagnostics

/**
 * Decides which received BLE frames reach the event log.
 *
 * Fold test of 2026-11-02: raw frames (`ble_rx`) and the full text of the periodic reports (`protobuf_decoded`) made
 * 84 % of the log, 37 to 70 MB/h even with the robot docked, and filled the per-launch cap in 4 to 6 h.
 * - Raw frames are kept only while their message is not decoded: they are written when decoding fails, dropped once
 *   it succeeds (the decoded form carries the same content, minus the account number echoed by the robot).
 * - The periodic messages ([PERIODIC_KEYS], about one per second each) are written in full at most every
 *   [periodicIntervalMs], and an unchanged text at most every [unchangedIntervalMs]; every other message is written
 *   each time. The `telemetry` event still records each report in reduced form.
 *
 * Used from the BLE thread only.
 */
class FrameLogFilter(
    private val periodicIntervalMs: Long = PERIODIC_INTERVAL_MS,
    private val unchangedIntervalMs: Long = UNCHANGED_INTERVAL_MS,
) {
    private val pendingFrames = ArrayList<ByteArray>()
    private val lastWritten = HashMap<String, Pair<Long, String>>()

    /** Remembers a raw frame until its message is decoded (bounded: a broken stream does not grow memory). */
    fun received(frame: ByteArray) {
        if (pendingFrames.size >= MAX_PENDING_FRAMES) pendingFrames.removeAt(0)
        pendingFrames += frame.copyOf()
    }

    /** The message made of the pending frames was decoded: they are not logged. */
    fun decoded() = pendingFrames.clear()

    /** The pending frames could not be decoded: returns them, to be logged, and forgets them. */
    fun undecoded(): List<ByteArray> = pendingFrames.toList().also { pendingFrames.clear() }

    /**
     * Whether the decoded message of kind [key] (see [messageKey]) is written at [nowMs]; [content] (its sub-message, read
     * only for the periodic ones) tells an unchanged message.
     */
    fun writeDecoded(key: String, nowMs: Long, content: () -> String): Boolean {
        if (key !in PERIODIC_KEYS) return true
        val text = content()
        val previous = lastWritten[key]
        val write = previous == null || nowMs - previous.first >= (if (previous.second == text) unchangedIntervalMs else periodicIntervalMs)
        if (write) lastWritten[key] = nowMs to text
        return write
    }

    companion object {
        const val PERIODIC_INTERVAL_MS = 10_000L
        const val UNCHANGED_INTERVAL_MS = 60_000L
        private const val MAX_PENDING_FRAMES = 64
        /** Messages the robot sends about once per second, connected or docked. */
        val PERIODIC_KEYS = setOf("SYS/TOAPP_REPORT_DATA", "SYS/SYSTEMUPDATEBUF", "SYS/BIDIRE_COMM_CMD", "NET/TOAPP_WIFI_IOT_STATUS")

        /** `<sub-message>/<its payload>` of a message, e.g. `SYS/TOAPP_REPORT_DATA`. */
        fun messageKey(subMessage: String, payload: String?): String = if (payload == null) subMessage else "$subMessage/$payload"
    }
}
