package fr.luba.poc.gateway

/**
 * "start" event the ESP32 gateway sends to each pilot that connects: "#12 power on, up 6 s; previous run 52 s, battery
 * 100 % on external power…" (wire text in English, `journal::startSummary`). Fold test of 2026-11-02: the board
 * restarted on power cuts every few seconds, visible only on its own `/log` page.
 */
object GatewayStartReport {
    /** Event kind on the wire (logged as `gateway_start`). */
    const val EVENT = "start"

    /** Boot number and reset reason of a start; null when the text is not a start report. */
    data class Start(val seq: Long, val reason: String) {
        /** Not asked for: power cut, brownout, crash, watchdog or unknown (an announced update restarts in software). */
        val unexpected: Boolean get() = reason !in EXPECTED_REASONS
    }

    fun parse(text: String): Start? {
        val match = START.find(text) ?: return null
        return Start(match.groupValues[1].toLongOrNull() ?: return null, match.groupValues[2].trim())
    }

    /**
     * Boot numbers seen by this app run: a start whose number changed since the previous connection, for a reason not
     * asked for, is a restart of the board while in use. The first start seen says nothing (the board may just have
     * been plugged in).
     */
    class Watch {
        private var lastSeq: Long? = null

        /** The start reported by [text] if it is an unexpected restart, else null. */
        fun unexpectedRestart(text: String): Start? {
            val start = parse(text) ?: return null
            val previous = lastSeq.also { lastSeq = start.seq }
            return start.takeIf { previous != null && previous != it.seq && it.unexpected }
        }
    }

    private val START = Regex("""#(\d+) ([a-z ]+?)(?:,|;|$)""")
    private val EXPECTED_REASONS = setOf("software restart", "reset button", "wake from deep sleep")
}
