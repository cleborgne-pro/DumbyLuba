package fr.luba.poc.relay

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground services of the phone: robot link kept in the background ([RobotLinkService]) and relay to the home server
 * ([RelayService]): notification, locks, stop, sending and resumption of the pending upload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayServicesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val wifi get() = context.getSystemService(WifiManager::class.java)
    private var link: ServiceController<RobotLinkService>? = null
    private var relay: ServiceController<RelayService>? = null
    private var http: ServerSocket? = null
    private val requests = CopyOnWriteArrayList<Pair<String?, String>>()

    @After fun tearDown() {
        runCatching { link?.destroy() }; runCatching { relay?.destroy() }
        http?.close()
    }

    private fun waitFor(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        return condition()
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    // ---- Robot link kept in the background ----

    private fun startLink(): RobotLinkService =
        Robolectric.buildService(RobotLinkService::class.java).also { link = it }.create().startCommand(0, 1).get()

    @Test fun `link, foreground with STOP robot, CPU and Wi-Fi locks held then released`() {
        val service = startLink()
        assertEquals(8766, shadowOf(service).lastForegroundNotificationId)
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals("DumbyLuba — robot connecté", notification.title())
        assertEquals(listOf("STOP robot"), notification.actions.map { it.title.toString() })
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            context.getSystemService(NotificationManager::class.java).getNotificationChannel("link").importance)
        assertTrue(RobotLinkService.active.value)
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        assertEquals(1, shadowOf(wifi).activeLockCount)
        // Second start (back to the background): nothing duplicated.
        link!!.startCommand(0, 2)
        assertEquals(1, shadowOf(wifi).activeLockCount)
        link!!.destroy(); link = null
        assertFalse(RobotLinkService.active.value)
        assertFalse(wake.isHeld)
        assertEquals(0, shadowOf(wifi).activeLockCount)
    }

    @Test fun `link, notification STOP stops the robot and the service`() {
        startLink()
        val service = link!!.withIntent(Intent(context, RobotLinkService::class.java).setAction("STOP_ROBOT")).startCommand(0, 2).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `link, app removed from recent tasks, link closed and service stopped`() {
        val service = startLink()
        service.onTaskRemoved(Intent())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `link, foreground refused by Android, service stopped without staying active`() {
        val controller = Robolectric.buildService(RobotLinkService::class.java).also { link = it }.create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("arrière-plan"))
        val service = controller.startCommand(0, 1).get()
        assertFalse(RobotLinkService.active.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `start and stop through the shortcuts`() {
        RobotLinkService.start(context)
        assertEquals(RobotLinkService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        RobotLinkService.stop(context)
        assertEquals(RobotLinkService::class.java.name, shadowOf(context).nextStoppedService.component?.className)
    }

    // ---- Relay to the home server ----

    /** Minimal home server (HTTP/1.1 on the loopback, free port): records the code and body of each upload. */
    private fun server(status: Int = 200): String {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { http = it }
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream().buffered()
                    fun line() = generateSequence { input.read().takeIf { b -> b >= 0 } }.takeWhile { b -> b != '\n'.code }
                        .map { b -> b.toChar() }.joinToString("").trimEnd('\r')
                    val request = line()
                    val headers = generateSequence { line().takeIf { h -> h.isNotEmpty() } }.associate { h ->
                        h.substringBefore(':').trim().lowercase() to h.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var read = 0
                        while (read < b.size) { val n = input.read(b, read, b.size - read); if (n < 0) break; read += n } }
                    if (request.startsWith("POST /api/snapshots ")) requests += headers["x-relay-code"] to body.toString(Charsets.UTF_8)
                    it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}"
    }

    private fun startRelay(url: String): RelayService {
        val config = RelayConfig.read(context)
        RelayConfig.save(context, config.copy(url = url))
        return Robolectric.buildService(RelayService::class.java).also { relay = it }.create().startCommand(0, 1).get()
    }

    @Test fun `relay, state sent to the server with the code, without account credentials`() {
        val url = server()
        val service = startRelay(url)
        assertEquals(8765, shadowOf(service).lastForegroundNotificationId)
        assertEquals("DumbyLuba — relais actif", shadowOf(service).lastForegroundNotification.title())
        assertEquals(listOf("Arrêter le relais"), shadowOf(service).lastForegroundNotification.actions.map { it.title.toString() })
        assertTrue(RelayService.status.value.running)
        assertEquals(url, RelayService.status.value.serverUrl)
        assertTrue(waitFor { RelayService.status.value.message == "Informations transmises au serveur" })
        assertNotNull(RelayService.status.value.lastSentAt)
        val (code, body) = requests.first()
        assertEquals(RelayConfig.read(context).code, code)
        val snapshot = JSONObject(body)
        assertEquals(RelayConfig.read(context).id, snapshot.getString("relayId"))
        assertEquals(1L, snapshot.getLong("sequence"))
        assertFalse(snapshot.has("code"))
        // Upload acknowledged: nothing left pending on the phone.
        assertFalse(File(context.filesDir, "relay-latest.json").exists())
        assertEquals(1, shadowOf(wifi).activeLockCount)
        relay!!.destroy(); relay = null
        assertFalse(RelayService.status.value.running)
        assertEquals("Relais arrêté", RelayService.status.value.message)
        assertEquals(0, shadowOf(wifi).activeLockCount)
    }

    @Test fun `relay, pending upload from a previous launch sent first`() {
        File(context.filesDir, "relay-latest.json").writeText("{\"pending\":true}")
        startRelay(server())
        assertTrue(waitFor { requests.isNotEmpty() })
        assertEquals("{\"pending\":true}", requests.first().second)
    }

    @Test fun `relay, server error, sending suspended and observation kept`() {
        startRelay(server(status = 500))
        assertTrue(waitFor { RelayService.status.value.message.startsWith("Transmission suspendue : HTTP 500") })
        assertTrue(RelayService.status.value.running)
        assertNull(RelayService.status.value.lastSentAt)
        assertTrue(File(context.filesDir, "relay-latest.json").exists())
    }

    @Test fun `relay, address not configured, start refused`() {
        val service = Robolectric.buildService(RelayService::class.java).also { relay = it }.create().startCommand(0, 1).get()
        assertFalse(RelayService.status.value.running)
        assertTrue(RelayService.status.value.message, RelayService.status.value.message.startsWith("Démarrage refusé : "))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `relay, notification Stop button`() {
        startRelay(server())
        val service = relay!!.withIntent(Intent(context, RelayService::class.java).setAction("STOP")).startCommand(0, 2).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
