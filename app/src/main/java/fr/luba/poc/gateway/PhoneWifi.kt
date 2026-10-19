package fr.luba.poc.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import fr.luba.poc.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wi-Fi of the pilot phone, as seen when the gateway drops.
 *
 * Tests of 2026-10-14: the gateway (Redmi) stayed connected to the Wi-Fi all morning; it was the Fold that lost its access
 * point at 10:33:09, then Android disabled its Wi-Fi at 10:35:10 (until 12:07). The pilot log only said "gateway Wi-Fi
 * lost" then "gateway silent": the cause was only visible in `dumpsys wifi`.
 */
object PhoneWifi {
    @Volatile var connected: Boolean? = null; private set
    @Volatile var changedAt = 0L; private set
    private var wifi: WifiManager? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun install(context: Context) {
        if (wifi != null) return
        wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed(true)
                override fun onLost(network: Network) = changed(false)
            })
        }
    }

    /** Wi-Fi state change (readable text): pilot log. */
    fun listen(listener: (String) -> Unit) { listeners += listener }
    fun unlisten(listener: (String) -> Unit) { listeners -= listener }

    private fun changed(now: Boolean) {
        if (connected == now) return
        connected = now; changedAt = System.currentTimeMillis()
        val text = describe()
        listeners.forEach { runCatching { it(text) } }
    }

    /** Wi-Fi enabled in the settings (null: unknown). */
    fun enabled(): Boolean? = runCatching { wifi?.isWifiEnabled }.getOrNull()

    fun describe(): String = describe(enabled(), connected, changedAt)

    internal fun describe(enabled: Boolean?, connected: Boolean?, changedAt: Long): String {
        val since = if (changedAt > 0) tr(" since ", " depuis ") + SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(Date(changedAt)) else ""
        return when {
            enabled == false -> tr("Phone Wi-Fi disabled$since: turn it back on to reach the gateway",
                "Wi-Fi du téléphone désactivé$since : le réactiver pour joindre la passerelle")
            connected == false -> tr("Phone Wi-Fi disconnected$since (out of range of the access point?)",
                "Wi-Fi du téléphone déconnecté$since (hors de portée du point d'accès ?)")
            connected == true -> tr("Phone Wi-Fi connected", "Wi-Fi du téléphone connecté")
            else -> tr("phone Wi-Fi state unknown", "état du Wi-Fi du téléphone inconnu")
        }
    }

    /** True if the phone clearly has no Wi-Fi: the gateway is not at fault. */
    fun phoneOffline(): Boolean = enabled() == false || connected == false
}
