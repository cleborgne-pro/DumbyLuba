package fr.luba.poc.gateway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.i18n.tr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gateway announced on the Wi-Fi (mDNS `_dumbyluba._tcp`). */
data class GatewayEndpoint(val name: String, val host: String, val port: Int)

/** Order in which gateways are tried: the saved address first, then those announced on the Wi-Fi, without duplicates. */
object GatewaySearch {
    fun candidates(host: String, port: Int, announced: List<GatewayEndpoint>): List<GatewayEndpoint> =
        (listOfNotNull(GatewayEndpoint("", host.trim(), port).takeIf { it.host.isNotBlank() }) + announced)
            .distinctBy { it.host to it.port }

    /** Readable reason for a connection failure: Java timeouts translated, otherwise the message as is. */
    fun reason(error: Throwable): String = when {
        error is java.net.SocketTimeoutException -> tr("timed out", "délai dépassé")
        error is java.net.ConnectException || error is java.net.NoRouteToHostException -> tr("connection impossible", "connexion impossible")
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    /**
     * Failure of every gateway tried ([failures], in the order tried; [saved]: saved address). The refusal of ANOTHER
     * gateway does not hide the failure of the saved gateway: on 2026-10-15 at 18:23, the Redmi (saved address) no longer
     * answered in time (Wi-Fi at −87 dBm) and the ESP32, announced on the same Wi-Fi with another code, refused; the app
     * showed "Wrong gateway code" for 12 min. A refusal by the saved gateway itself (code, busy, version) is reported
     * as is ([GatewayDenied]).
     */
    fun failure(saved: GatewayEndpoint?, failures: List<Pair<GatewayEndpoint, Throwable>>, announced: Int): Exception {
        fun same(a: GatewayEndpoint, b: GatewayEndpoint) = a.host == b.host && a.port == b.port
        val savedFailure = saved?.let { s -> failures.firstOrNull { same(it.first, s) } }
        (savedFailure?.second as? GatewayDenied)?.let { return it }
        val others = failures.filter { saved == null || !same(it.first, saved) }
        if (savedFailure != null) {
            val detail = others.joinToString("") { (endpoint, error) -> tr("; other gateway ${endpoint.host}: ${reason(error)}", " ; autre passerelle ${endpoint.host} : ${reason(error)}") }
            return IllegalStateException(tr("${saved.host} unreachable (${reason(savedFailure.second)})", "${saved.host} injoignable (${reason(savedFailure.second)})") +
                (if (announced == 0) tr(" and gateway not found on the Wi-Fi", " et passerelle introuvable sur le Wi-Fi") else "") + detail)
        }
        failures.lastOrNull { it.second is GatewayDenied }?.let { return it.second as GatewayDenied }
        return IllegalStateException(when {
            announced == 0 && saved == null -> tr("gateway not found on the Wi-Fi", "passerelle introuvable sur le Wi-Fi")
            announced == 0 -> tr("${saved!!.host} unreachable and gateway not found on the Wi-Fi", "${saved!!.host} injoignable et passerelle introuvable sur le Wi-Fi")
            else -> tr("$announced gateway(s) announced, none reachable", "$announced passerelle(s) annoncée(s), aucune joignable") +
                failures.joinToString("") { (endpoint, error) -> tr("; ${endpoint.host}: ${reason(error)}", " ; ${endpoint.host} : ${reason(error)}") }
        })
    }
}

/** Pilot side: search for gateways on the Wi-Fi and search for the robot through them. */
object GatewayClient {
    /**
     * Opens a session with the gateway: the saved address, otherwise each gateway announced on the Wi-Fi with the saved
     * code. The gateway that accepts the code becomes the saved address (DHCP, another phone).
     * Failure everywhere: see [GatewaySearch.failure] (the failure of the saved gateway prevails over another's refusal).
     */
    fun openAny(context: Context, config: GatewayConfig): Pair<GatewayChannel, GatewayConfig> {
        val failures = mutableListOf<Pair<GatewayEndpoint, Throwable>>()
        val tried = mutableSetOf<Pair<String, Int>>()
        fun attempt(endpoints: List<GatewayEndpoint>): Pair<GatewayChannel, GatewayConfig>? {
            for (endpoint in endpoints) {
                if (!tried.add(endpoint.host to endpoint.port)) continue
                try {
                    val channel = GatewayChannel.open(endpoint.host, endpoint.port, config.code)
                    val target = config.copy(host = endpoint.host, port = endpoint.port)
                    if (target.host != config.host || target.port != config.port) GatewayConfig.save(context, target)
                    return channel to target
                } catch (error: Exception) { failures += endpoint to error }
            }
            return null
        }
        attempt(GatewaySearch.candidates(config.host, config.port, emptyList()))?.let { return it }
        // Saved address unreachable: every gateway announced on the Wi-Fi is tried.
        val announced = discoverAll(context)
        attempt(GatewaySearch.candidates(config.host, config.port, announced))?.let { return it }
        val saved = GatewaySearch.candidates(config.host, config.port, emptyList()).firstOrNull()
        val failure = GatewaySearch.failure(saved, failures, announced.size)
        if (failure !is GatewayDenied) GatewayHealthStore.unreachable(failure.message.orEmpty())
        throw failure
    }

    /** Searches for LUBAs through the gateway; updates its address if mDNS finds it elsewhere. */
    fun scan(context: Context, config: GatewayConfig, timeoutMs: Long = 12_000): List<SeenRobot> {
        val (channel, _) = openAny(context, config)
        try {
            channel.send(GatewayProtocol.SCAN)
            val found = linkedMapOf<String, SeenRobot>()
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastPing = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline) {
                val frame = channel.read()
                if (frame.type == GatewayProtocol.SCAN_END) break
                if (frame.type == GatewayProtocol.SCAN_RESULT) {
                    val (name, address, rssi) = GatewayProtocol.parseScanResult(frame)
                    found[address] = SeenRobot(name, address, rssi)
                }
                // Health sent at the start of the session: the gateway stays monitored even without a linked robot.
                if (frame.type == GatewayProtocol.HEALTH) GatewayHealth.decode(frame.payload)?.let { GatewayHealthStore.update(it) }
                if (frame.type == GatewayProtocol.FAILURE) throw GatewayScanFailed(frame.text())
                if (System.currentTimeMillis() - lastPing >= GatewayProtocol.PING_MS) { channel.send(GatewayProtocol.PING); lastPing = System.currentTimeMillis() }
            }
            runCatching { channel.send(GatewayProtocol.CLOSE) }
            return found.values.toList()
        } finally { channel.close() }
    }

    /** Finds the gateway that accepts the code (saved address, then Wi-Fi announcements), without touching the robot. */
    fun find(context: Context, config: GatewayConfig): String {
        val (channel, target) = openAny(context, config)
        runCatching { channel.send(GatewayProtocol.CLOSE) }
        channel.close()
        return tr("Gateway ${target.host} reached, code accepted", "Passerelle ${target.host} jointe, code accepté")
    }

    /** First gateway announced on the Wi-Fi: (IPv4 address, port) or null. */
    fun discover(context: Context, timeoutMs: Long = 4000): Pair<String, Int>? =
        discoverAll(context, timeoutMs, firstOnly = true).firstOrNull()?.let { it.host to it.port }

    /**
     * Every gateway announced over mDNS `_dumbyluba._tcp` during [timeoutMs] (IPv4 addresses). Announcements are
     * resolved one by one (a single resolution at a time on older Android versions).
     */
    fun discoverAll(context: Context, timeoutMs: Long = 3000, firstOnly: Boolean = false): List<GatewayEndpoint> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val done = CountDownLatch(1)
        val found = java.util.concurrent.CopyOnWriteArrayList<GatewayEndpoint>()
        val pending = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
        fun resolveNext() {
            if (!resolving.compareAndSet(false, true)) return
            val next = pending.poll() ?: run { resolving.set(false); return }
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { resolving.set(false); resolveNext() }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress
                        if (host != null && host.contains('.') && found.none { it.host == host && it.port == info.port })
                            found += GatewayEndpoint(info.serviceName, host, info.port)
                        if (firstOnly && found.isNotEmpty()) done.countDown()
                        resolving.set(false); resolveNext()
                    }
                })
            }.onFailure { resolving.set(false) }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith(GatewayProtocol.SERVICE_NAME)) return
                pending.offer(serviceInfo); resolveNext()
            }
        }
        return try {
            nsd.discoverServices(GatewayProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.toList()
        } catch (_: Exception) { found.toList() }
        finally { runCatching { nsd.stopServiceDiscovery(discovery) } }
    }
}

/**
 * Automatic reconnection of the pilot to the gateway: an attempt every [BASE_MS] while the robot is not connected,
 * spaced out up to [MAX_MS] when no gateway answers (no Wi-Fi, Redmi switched off), endlessly.
 */
object GatewayRetry {
    const val BASE_MS = 15_000L
    const val MAX_MS = 60_000L
    fun delayMs(failures: Int): Long = (BASE_MS shl failures.coerceIn(0, 2)).coerceAtMost(MAX_MS)
}

/**
 * Gateway reached, but its Bluetooth search failed (Bluetooth off on the gateway…): it is not unreachable.
 * [reason] is the French wire text; the message is translated for display ([GatewayWireText]).
 */
class GatewayScanFailed(reason: String) : IllegalStateException(GatewayWireText.display(reason))
