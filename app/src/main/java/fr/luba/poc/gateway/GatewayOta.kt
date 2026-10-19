package fr.luba.poc.gateway

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/**
 * "Gateway updates during mowing" setting (ESP32 gateway, [GatewayProtocol.CAP_OTA]).
 *
 * Off: the gateway refuses an update while the pilot holds the robot. On: it accepts it, announces its restart
 * ([GatewayProtocol.OTA_BEGIN_EVENT]), writes the safety stop, updates itself and then reconnects to the robot; the
 * pilot then resumes the interrupted mowing from its saved progress.
 * The value is sent to the gateway on every connection and as soon as it changes.
 */
object GatewayOta {
    private const val PREFS = "gateway"
    private const val KEY = "otaDuringMowing"
    /** Window during which a gateway loss follows an update announcement (planned restart, not a failure). */
    const val RESTART_WINDOW_MS = 30_000L
    /** Time given to the gateway to come back and to the robot to be resumed: flashing, restart, Wi-Fi, Bluetooth. */
    const val RESUME_WINDOW_MS = 6 * 60_000L

    @Volatile var duringMowing = false; private set
    private val links = CopyOnWriteArraySet<GatewayLink>()

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY, false).also { duringMowing = it }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply()
        update(value)
    }

    /** In-memory value, pushed to the connected gateways that support it. */
    internal fun update(value: Boolean) {
        duringMowing = value
        links.forEach { it.pushConfig() }
    }

    internal fun register(link: GatewayLink) { links.add(link) }
    internal fun unregister(link: GatewayLink) { links.remove(link) }
}
