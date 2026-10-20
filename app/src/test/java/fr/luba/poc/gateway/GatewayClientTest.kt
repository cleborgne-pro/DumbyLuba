package fr.luba.poc.gateway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.ble.SeenRobot
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Pilot: connection to the gateway (saved address, then mDNS announcements), robot search through it, readable
 * failures. Gateway simulated on a free loopback port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayClientTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val code = "424242"
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val received = CopyOnWriteArrayList<Int>()
    private val pool = Executors.newCachedThreadPool()

    @After fun close() { servers.forEach { it.close() }; pool.shutdownNow(); GatewayHealthStore.clear() }

    /** Minimal gateway: challenge, code check, then [session] (received frames recorded in [received]). */
    private fun gateway(gatewayCode: String = code, session: (DataInputStream, DataOutputStream) -> Unit = { _, _ -> }): Int {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress()).also { servers += it }
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    socket.use {
                        val input = DataInputStream(BufferedInputStream(it.getInputStream()))
                        val output = DataOutputStream(BufferedOutputStream(it.getOutputStream()))
                        val nonce = ByteArray(GatewayProtocol.NONCE_BYTES).also { n -> SecureRandom().nextBytes(n) }
                        GatewayProtocol.write(output, GatewayProtocol.CHALLENGE, byteArrayOf(GatewayProtocol.VERSION.toByte()) + nonce)
                        val hello = GatewayProtocol.read(input)
                        if (!GatewayProtocol.verify(gatewayCode, nonce, hello.payload.drop(1).toByteArray())) {
                            GatewayProtocol.write(output, GatewayProtocol.DENIED, "Code de passerelle incorrect".toByteArray())
                            return@use
                        }
                        GatewayProtocol.write(output, GatewayProtocol.WELCOME, GatewayProtocol.welcome())
                        runCatching {
                            session(input, output)
                            while (true) received += GatewayProtocol.read(input).type
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    /** Closed port: connection refused immediately. */
    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Blocking call (mDNS search) run in the background while the test plays the network. */
    private fun <T> background(block: () -> T): Future<T> = pool.submit<T> { block() }

    private val nsd get() = context.getSystemService(NsdManager::class.java)
    private fun discoveries() = shadowOf(nsd).getDiscoveryListeners(GatewayProtocol.SERVICE_TYPE).orEmpty()

    /** mDNS announcements to loopback ([ports]) for the search started after [before] searches, otherwise the search fails. */
    private fun announce(vararg ports: Int, others: Boolean = false, before: Int = 0) {
        val deadline = System.currentTimeMillis() + 5000
        while (discoveries().size <= before && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val discovery = discoveries().last()
        if (others) discovery.onServiceFound(NsdServiceInfo().apply { serviceName = "Imprimante"; serviceType = GatewayProtocol.SERVICE_TYPE })
        if (ports.isEmpty()) { discovery.onStartDiscoveryFailed(GatewayProtocol.SERVICE_TYPE, 0); return }
        ports.forEachIndexed { i, port ->
            val info = NsdServiceInfo().apply { serviceName = "${GatewayProtocol.SERVICE_NAME} $i"; serviceType = GatewayProtocol.SERVICE_TYPE }
            discovery.onServiceFound(info)
            @Suppress("DEPRECATION")
            shadowOf(nsd).getResolveListeners(info).orEmpty().forEach { listener ->
                listener.onServiceResolved(NsdServiceInfo().apply {
                    serviceName = info.serviceName; host = InetAddress.getByName("127.0.0.1"); this.port = port
                })
            }
        }
    }

    @Test fun `saved address reached directly, without search or settings rewrite`() {
        val port = gateway()
        val config = GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code)
        GatewayConfig.save(context, config.copy(host = "", port = 1))
        val (channel, target) = GatewayClient.openAny(context, config)
        channel.close()
        assertEquals(config, target)
        // Settings not rewritten: the chosen address was already the saved one.
        assertEquals("", GatewayConfig.read(context).host)
        assertEquals(true, GatewayHealthStore.reach.value?.reachable)
    }

    @Test fun `saved address unreachable, gateway announced on Wi-Fi chosen and saved`() {
        val port = gateway()
        val config = GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", closedPort(), pilotCode = code)
        val result = background { GatewayClient.openAny(context, config) }
        announce(port, others = true)
        val (channel, target) = result.get(10, TimeUnit.SECONDS)
        channel.close()
        assertEquals(port, target.port)
        assertEquals(target, GatewayConfig.read(context))
    }

    @Test fun `code refused by the saved gateway reported as is, gateway still reachable`() {
        val port = gateway(gatewayCode = "111111")
        val result = background { GatewayClient.openAny(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code)) }
        announce()
        val error = runCatching { result.get(10, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
        assertTrue("$error", error is GatewayDenied)
        assertEquals("Code de passerelle incorrect", error!!.message)
        assertNotEquals(false, GatewayHealthStore.reach.value?.reachable)
    }

    @Test fun `no gateway, readable cause and gateway recorded as unreachable`() {
        val dead = closedPort()
        val result = background { GatewayClient.openAny(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", dead, pilotCode = code)) }
        announce()
        val error = runCatching { result.get(10, TimeUnit.SECONDS) }.exceptionOrNull()?.cause!!
        assertEquals("127.0.0.1 injoignable (connexion impossible) et passerelle introuvable sur le Wi-Fi", error.message)
        assertEquals(GatewayReach(false, GatewayHealthStore.reach.value!!.at, error.message!!), GatewayHealthStore.reach.value)
    }

    @Test fun `robot search through the gateway, health recorded along the way`() {
        val port = gateway { input, output ->
            check(GatewayProtocol.read(input).type == GatewayProtocol.SCAN)
            GatewayProtocol.write(output, GatewayProtocol.HEALTH, GatewayHealth(batteryPercent = 77).encode())
            GatewayProtocol.write(output, GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult("Luba-VS1", "AA:01", -60))
            GatewayProtocol.write(output, GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult("Luba-VS2", "AA:02", -71))
            // Same robot seen twice: last measurement kept.
            GatewayProtocol.write(output, GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult("Luba-VS1", "AA:01", -55))
            GatewayProtocol.write(output, GatewayProtocol.SCAN_END)
        }
        val robots = GatewayClient.scan(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code))
        assertEquals(listOf(SeenRobot("Luba-VS1", "AA:01", -55), SeenRobot("Luba-VS2", "AA:02", -71)), robots)
        assertEquals(77, GatewayHealthStore.latest.value?.health?.batteryPercent)
        val deadline = System.currentTimeMillis() + 3000
        while (GatewayProtocol.CLOSE !in received && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("close announced to the gateway", GatewayProtocol.CLOSE in received)
    }

    @Test fun `search refused by the gateway, reason reported`() {
        val port = gateway { input, output ->
            GatewayProtocol.read(input)
            GatewayProtocol.write(output, GatewayProtocol.FAILURE, "Bluetooth désactivé sur la passerelle".toByteArray())
        }
        val error = assertThrows(GatewayScanFailed::class.java) {
            GatewayClient.scan(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code))
        }
        assertEquals("Bluetooth désactivé sur la passerelle", error.message)
    }

    @Test fun `setup check probe, version, health and robots seen, without opening the robot link`() {
        val port = gateway { input, output ->
            check(GatewayProtocol.read(input).type == GatewayProtocol.SCAN)
            GatewayProtocol.write(output, GatewayProtocol.HEALTH, GatewayHealth(batteryPercent = 64, wifiRssiDbm = -58).encode())
            GatewayProtocol.write(output, GatewayProtocol.SCAN_RESULT, GatewayProtocol.scanResult("Luba-VS1", "AA:01", -44))
            GatewayProtocol.write(output, GatewayProtocol.SCAN_END)
        }
        val probe = GatewayClient.probe(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code))
        assertEquals("127.0.0.1", probe.host)
        assertEquals(fr.luba.poc.BuildConfig.VERSION_NAME, probe.appVersion)
        assertEquals(64, probe.health?.batteryPercent)
        assertEquals(listOf(SeenRobot("Luba-VS1", "AA:01", -44)), probe.robots)
        assertNull(probe.scanFailure)
        assertTrue(probe.handshakeMs >= 0)
        assertEquals(fr.luba.poc.BuildConfig.VERSION_NAME, GatewayHealthStore.gatewayVersion.value)
        val deadline = System.currentTimeMillis() + 3000
        while (GatewayProtocol.CLOSE !in received && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("no robot link opened", GatewayProtocol.OPEN in received)
    }

    @Test fun `setup check probe, Bluetooth off on the gateway reported, gateway still reached`() {
        val port = gateway { input, output ->
            GatewayProtocol.read(input)
            GatewayProtocol.write(output, GatewayProtocol.FAILURE, "Bluetooth désactivé sur la passerelle".toByteArray())
        }
        val probe = GatewayClient.probe(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code))
        assertEquals("Bluetooth désactivé sur la passerelle", probe.scanFailure)
        assertTrue(probe.robots.isEmpty())
    }

    @Test fun `find and test, gateway reached then released`() {
        val port = gateway()
        assertEquals("Passerelle 127.0.0.1 jointe, code accepté",
            GatewayClient.find(context, GatewayConfig(GatewayRole.CLIENT, "127.0.0.1", port, pilotCode = code)))
        val deadline = System.currentTimeMillis() + 3000
        while (GatewayProtocol.CLOSE !in received && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(GatewayProtocol.CLOSE in received)
    }

    @Test fun `first announced gateway, or nothing if the search fails`() {
        val count = discoveries().size
        val first = background { GatewayClient.discover(context) }
        announce(9001, 9002, before = count)
        assertEquals("127.0.0.1" to 9001, first.get(10, TimeUnit.SECONDS))
        val again = discoveries().size
        val none = background { GatewayClient.discover(context) }
        announce(before = again)
        assertNull(none.get(10, TimeUnit.SECONDS))
    }
}
