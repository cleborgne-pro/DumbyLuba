package fr.luba.poc.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import fr.luba.poc.i18n.tr

/**
 * Pure decision: turn wireless debugging back on only if the user wants it, the app has the system
 * permission, the Wi-Fi is connected (otherwise Android turns it off again at once) and no more than
 * once per `minIntervalMs`.
 */
internal object WirelessDebugPolicy {
    const val MIN_INTERVAL_MS = 10_000L

    fun shouldEnable(keep: Boolean, canWrite: Boolean, wifiConnected: Boolean, enabled: Boolean,
                     nowMs: Long, lastAttemptMs: Long?, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean =
        keep && canWrite && wifiConnected && !enabled && (lastAttemptMs == null || nowMs - lastAttemptMs >= minIntervalMs)
}

/**
 * Gateway phone: Android turns wireless debugging off on every Wi-Fi loss (robot at the far end of the
 * garden) and on every restart. With the WRITE_SECURE_SETTINGS permission, granted once with
 * `adb shell pm grant fr.luba.poc android.permission.WRITE_SECURE_SETTINGS`, it is turned back on as soon
 * as the Wi-Fi returns. Without a dialog only on an already approved network ("Always allow on this
 * network"); the Mac reconnects by itself through mDNS (new port on each activation).
 */
class WirelessDebugKeeper(private val context: Context, private val report: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var lastAttemptMs: Long? = null
    private var started = false

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = schedule(SETTLE_MS)
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule(SETTLE_MS)
    }
    private val check = Runnable { evaluate() }
    private val periodic = object : Runnable {
        override fun run() { evaluate(); main.postDelayed(this, PERIOD_MS) }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { context.contentResolver.registerContentObserver(Settings.Global.getUriFor(SETTING), false, observer) }
        runCatching {
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network)
        }
        main.postDelayed(periodic, SETTLE_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(check); main.removeCallbacks(periodic)
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        runCatching { connectivity?.unregisterNetworkCallback(network) }
    }

    private fun schedule(delayMs: Long) {
        // Android turns the setting off right after a network change: wait until it is done.
        main.removeCallbacks(check); main.postDelayed(check, delayMs)
    }

    private fun evaluate() {
        val now = android.os.SystemClock.elapsedRealtime()
        val wifi = wifiConnected(); val on = enabled(context)
        if (!WirelessDebugPolicy.shouldEnable(keep(context), canWrite(context), wifi, on, now, lastAttemptMs)) return
        lastAttemptMs = now
        val done = runCatching { Settings.Global.putInt(context.contentResolver, SETTING, 1) }
        report(done.fold({ tr("Wireless debugging turned back on", "Débogage sans fil réactivé") },
            { tr("Wireless debugging: turning back on refused (${it.javaClass.simpleName})", "Débogage sans fil : réactivation refusée (${it.javaClass.simpleName})") }))
    }

    private fun wifiConnected(): Boolean = runCatching {
        val caps = connectivity?.allNetworks?.mapNotNull { connectivity.getNetworkCapabilities(it) }.orEmpty()
        caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    }.getOrDefault(false)

    companion object {
        const val SETTING = "adb_wifi_enabled"
        private const val PREFS = "gateway"
        private const val KEY = "keep_adb_wifi"
        private const val SETTLE_MS = 3_000L
        private const val PERIOD_MS = 60_000L

        private var installed: WirelessDebugKeeper? = null
        private val events = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
        /** Latest reactivations, shown in Service → Gateway. */
        val lastEvents = events

        /** A single watcher per process, active as long as the process lives. */
        fun install(context: Context) {
            if (installed != null || !canWrite(context)) return
            installed = WirelessDebugKeeper(context.applicationContext) { text ->
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date())
                events.value = (events.value + "$stamp $text").takeLast(5)
                android.util.Log.i("DumbyLubaAdbWifi", text)
            }.also { it.start() }
        }

        fun canWrite(context: Context) =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        fun enabled(context: Context) = runCatching { Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1 }.getOrDefault(false)
        fun keep(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEY, true)
        fun setKeep(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply() }
    }
}
