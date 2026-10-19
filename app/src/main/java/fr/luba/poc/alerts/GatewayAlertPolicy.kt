package fr.luba.poc.alerts

/**
 * Alert thresholds for the gateway phone battery (same scale as the displayed warnings: low below 25 %, very low
 * below 15 %). 3-point hysteresis so the alert does not repeat around a threshold; gateway plugged in or unknown
 * reading: no alert.
 */
object GatewayAlertPolicy {
    const val WARNING_PERCENT = 24
    const val CRITICAL_PERCENT = 14
    const val HYSTERESIS = 3

    fun battery(percent: Int, charging: Boolean, prior: Severity?): Severity? = when {
        charging || percent !in 0..100 -> null
        percent <= CRITICAL_PERCENT -> Severity.CRITICAL
        prior == Severity.CRITICAL && percent <= CRITICAL_PERCENT + HYSTERESIS -> Severity.CRITICAL
        percent <= WARNING_PERCENT -> Severity.WARNING
        prior != null && percent <= WARNING_PERCENT + HYSTERESIS -> Severity.WARNING
        else -> null
    }

    /** Loss of the Wi-Fi tunnel to the gateway: critical if the robot was moving, otherwise a warning. */
    fun lost(moving: Boolean): Severity = if (moving) Severity.CRITICAL else Severity.WARNING
}
