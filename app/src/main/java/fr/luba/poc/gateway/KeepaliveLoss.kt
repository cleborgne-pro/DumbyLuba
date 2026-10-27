package fr.luba.poc.gateway

/**
 * Keepalive writes of the robot link (sync, report subscription, RSSI read) during a Wi-Fi outage of the gateway held by
 * the pilot (autonomous tracking, recharge at the base): their GATT confirmation only comes back after the reconnection,
 * or never if the frame stayed in the cut link. This is not a robot failure: wait for the reconnection, then drop the
 * frame rather than stopping the mission. Any other write (setpoint, STOP, blades) keeps the strict timeout.
 */
object KeepaliveLoss {
    /**
     * Labels of the keepalive writes issued by the periodic monitoring of `RobotBle`, and the report subscription of a
     * route start (2026-10-25, 15:31:31: `ROUTE_REPORTS` through the Redmi unconfirmed in 1.5 s, border task stopped
     * 2 s after its start and link closed; the same task restarted 8 s later without trouble).
     */
    val LABELS = setOf("sync(2)", "RPT_KEEP", "RPT_START", "ROUTE_REPORTS")
    /** Time allowed for the confirmation after the reconnection (frame resent by the pilot on resume). */
    const val AFTER_RESUME_MS = 3000L
    /** Strict operation timeout: bound on the delay of a setpoint or of the STOP when this phone drives the motion. */
    const val STRICT_MS = 1500L
    /** Gateway without a mission: a Wi-Fi delay is not a link loss. */
    const val GATEWAY_IDLE_MS = 3000L
    /**
     * Keepalive through the gateway: the gateway itself closes its Bluetooth link after 3 s without a GATT response and
     * reports it as a loss; beyond that, it is the Wi-Fi round trip. On 2026-10-16, a sync during the recharge, confirmed
     * in 1.8 s (Bluetooth < 150 ms on the gateway side), had been declared a "GATT timeout" and had stopped the mission.
     */
    const val GATEWAY_KEEPALIVE_MS = 5000L

    fun keepalive(rssi: Boolean, label: String?): Boolean = rssi || label in LABELS

    /**
     * GATT operation timeout before a failure. Direct link or activity driven from the screen (joystick, tests): strict
     * timeout. Through the gateway, automatic tracking runs on the gateway: this phone only writes while stopped (between
     * two trackings, recharge). Keepalive there tolerates the Wi-Fi; during a mission, other writes keep the strict timeout.
     */
    fun timeoutMs(viaGateway: Boolean, keepalive: Boolean, operatorDriving: Boolean, missionRunning: Boolean): Long = when {
        !viaGateway || operatorDriving -> STRICT_MS
        keepalive -> GATEWAY_KEEPALIVE_MS
        missionRunning -> STRICT_MS
        else -> GATEWAY_IDLE_MS
    }

    /**
     * True while the confirmation of a keepalive write started at [started] must still be awaited: outage in progress
     * ([outageSince] non-zero), or a reconnection after the write started that is too recent ([AFTER_RESUME_MS]).
     */
    fun waiting(keepalive: Boolean, started: Long, now: Long, outageSince: Long, resumedAt: Long): Boolean =
        keepalive && (outageSince != 0L || (resumedAt >= started && now - resumedAt < AFTER_RESUME_MS))

    /** Keepalive write sent before a reconnection and never confirmed: dropped without a failure. */
    fun dropped(keepalive: Boolean, started: Long, resumedAt: Long): Boolean = keepalive && resumedAt != 0L && resumedAt >= started
}
