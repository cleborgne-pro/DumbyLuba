package fr.luba.poc.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last health state received from the gateway by the pilot, with its reception time (stale beyond [STALE_MS]).
 * Link open: one frame every 5 s. Robot not linked: each automatic search (about every 15 s) brings one back; the
 * threshold therefore covers several searches.
 */
data class ReceivedHealth(val health: GatewayHealth, val receivedAt: Long) {
    fun fresh(now: Long = System.currentTimeMillis()) = now - receivedAt in -2_000..STALE_MS
    companion object { const val STALE_MS = 60_000L }
}

/**
 * Gateway reachability as seen by the pilot, independent of the robot: a robot that is off or out of range does not make
 * the gateway "disconnected". [reachable] false: Wi-Fi lost, gateway silent or unreachable ([detail]).
 */
data class GatewayReach(val reachable: Boolean, val at: Long, val detail: String = "") {
    fun fresh(now: Long = System.currentTimeMillis()) = now - at in -2_000..ReceivedHealth.STALE_MS
}

object GatewayHealthStore {
    private val mutable = MutableStateFlow<ReceivedHealth?>(null)
    val latest: StateFlow<ReceivedHealth?> = mutable.asStateFlow()
    private val reachMutable = MutableStateFlow<GatewayReach?>(null)
    val reach: StateFlow<GatewayReach?> = reachMutable.asStateFlow()

    /** Last Wi-Fi level received from the gateway (dBm, time), kept when it becomes unreachable. */
    @Volatile private var lastWifi: Pair<Int, Long>? = null

    /** HEALTH frame received: the gateway answers. */
    fun update(health: GatewayHealth, now: Long = System.currentTimeMillis()) {
        if (health.wifiRssiDbm in -127..0) lastWifi = health.wifiRssiDbm to now
        mutable.value = ReceivedHealth(health, now); reached(now)
    }

    /** Reminder of the gateway's weak Wi-Fi for an outage or reconnection message; empty if there is nothing to say. */
    fun weakWifiHint(now: Long = System.currentTimeMillis()): String = lastWifi?.let { (dbm, at) -> wifiHint(dbm, now - at) }.orEmpty()

    /**
     * " · Gateway Wi-Fi −89 dBm (2 min ago): too weak, …" if the last received level, less than [WIFI_HINT_MS] old, is
     * below [GatewayHealth.warnings] (−70 dBm). Test of 2026-10-15: −87 to −89 dBm, link lost every 3 to 6 s, then
     * gateway unreachable.
     */
    fun wifiHint(dbm: Int, ageMs: Long): String {
        if (dbm !in -127..-70 || ageMs !in -2_000..WIFI_HINT_MS) return ""
        val age = if (ageMs < 60_000) "" else tr(" (${ageMs / 60_000} min ago)", " (il y a ${ageMs / 60_000} min)")
        return tr(" · Gateway Wi-Fi $dbm dBm$age: too weak, move the robot closer to the access point or add a repeater",
            " · Wi-Fi de la passerelle $dbm dBm$age : trop faible, rapprocher le robot du point d'accès ou ajouter un répéteur")
    }
    const val WIFI_HINT_MS = 15 * 60_000L
    /** Gateway reached and code accepted (search, link, test). */
    fun reached(now: Long = System.currentTimeMillis()) { reachMutable.value = GatewayReach(true, now) }
    /** Gateway unreachable or silent: its last health state is no longer meaningful. */
    fun unreachable(detail: String, now: Long = System.currentTimeMillis()) { mutable.value = null; reachMutable.value = GatewayReach(false, now, detail) }
    fun clear() { mutable.value = null; reachMutable.value = null; versionMutable.value = null }

    private val versionMutable = MutableStateFlow<String?>(null)
    /** App version announced by the gateway on the last accepted connection (Android gateway 1.6.0 onwards), else null. */
    val gatewayVersion: StateFlow<String?> = versionMutable.asStateFlow()
    fun version(value: String?) { versionMutable.value = value }
}

/** Reads the health state of THIS phone (gateway side, or local display on the gateway). */
object GatewayHealthReader {
    @Suppress("DEPRECATION")
    fun read(context: Context): GatewayHealth {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        val wifi = runCatching { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()?.connectionInfo
        return GatewayHealth(
            batteryPercent = percent, charging = plugged, batteryTempDeciC = temp,
            wifiRssiDbm = wifi?.rssi?.takeIf { it in -127..0 } ?: -1000,
            wifiMbps = wifi?.linkSpeed?.takeIf { it > 0 } ?: -1,
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1,
            powerSave = power.isPowerSaveMode,
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            screenOn = power.isInteractive)
    }
}
