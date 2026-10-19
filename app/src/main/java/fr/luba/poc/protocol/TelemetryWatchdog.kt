package fr.luba.poc.protocol

/** Monotonic clock; no robot timestamp or ACK takes part in the timeout. */
class TelemetryWatchdog {
    private var lastValid: Long = 0
    fun start(now: Long) { lastValid = now }
    fun recordValidReport(now: Long) { lastValid = now }
    fun expired(now: Long, timeoutMs: Long): Boolean {
        require(timeoutMs > 0)
        return now - lastValid >= timeoutMs
    }
}
