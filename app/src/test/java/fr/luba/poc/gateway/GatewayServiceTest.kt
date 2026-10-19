package fr.luba.poc.gateway

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.net.ConnectException
import java.net.Socket

/**
 * Gateway service on the robot: foreground start, locks, accepting a pilot, stop.
 * Listens on a free port ([GatewayService.listenPort] = 0), never on the real port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayServiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ServiceController<GatewayService>? = null

    @Before fun setUp() { GatewayService.listenPort = 0 }

    @After fun tearDown() {
        runCatching { controller?.destroy() }
        GatewayService.listenPort = GatewayProtocol.PORT
    }

    private fun start(intent: Intent? = null): GatewayService =
        Robolectric.buildService(GatewayService::class.java, intent).also { controller = it }.create().startCommand(0, 1).get()

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        return condition()
    }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun `foreground start, notification, CPU lock, listening for pilots`() {
        val service = start()
        val shadow = shadowOf(service)
        assertEquals(8767, shadow.lastForegroundNotificationId)
        val notification = shadow.lastForegroundNotification
        assertEquals("DumbyLuba — passerelle robot", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Wi-Fi ↔ Bluetooth : en attente du téléphone de pilotage", notification.text())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Arrêter la passerelle"), notification.actions.map { it.title.toString() })
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("gateway")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        val status = GatewayService.status.value
        assertTrue(status.running)
        assertEquals("En attente du téléphone de pilotage", status.message)
        val port = GatewayService.boundPort!!
        assertTrue(port > 0)
        // Second start (button, restart): same listener, nothing reopened.
        controller!!.startCommand(0, 2)
        assertEquals(port, GatewayService.boundPort)
    }

    @Test fun `pilot accepted with the right code then released, wrong code refused`() {
        start()
        val port = GatewayService.boundPort!!
        val code = GatewayConfig.read(context).code
        val wifi = context.getSystemService(WifiManager::class.java)
        assertThrows(GatewayDenied::class.java) {
            GatewayChannel.open("127.0.0.1", port, if (code == "000000") "000001" else "000000")
        }
        // Connection refused: no pilot shown, the gateway keeps waiting (it used to show "Pilot connected: …").
        assertTrue(waitFor { GatewayService.status.value.pilot == null })
        assertEquals("En attente du téléphone de pilotage", GatewayService.status.value.message)
        assertTrue(GatewayService.status.value.lastEvents.last().contains("Connexion refusée (127.0.0.1) : Code de passerelle incorrect"))
        val channel = GatewayChannel.open("127.0.0.1", port, code)
        assertTrue(waitFor { GatewayService.status.value.pilot != null })
        assertTrue(GatewayService.status.value.message.startsWith("Pilote connecté : "))
        // Wi-Fi kept active during the session.
        assertTrue(waitFor { shadowOf(wifi).activeLockCount > 0 })
        channel.send(GatewayProtocol.CLOSE); channel.close()
        assertTrue(waitFor { GatewayService.status.value.pilot == null })
        assertEquals("En attente du téléphone de pilotage", GatewayService.status.value.message)
        assertTrue(waitFor { shadowOf(wifi).activeLockCount == 0 })
    }

    @Test fun `search requested by the pilot, gateway Bluetooth off, reason sent to the pilot`() {
        start()
        val channel = GatewayChannel.open("127.0.0.1", GatewayService.boundPort!!, GatewayConfig.read(context).code)
        channel.send(GatewayProtocol.SCAN)
        var frame = channel.read()
        while (frame.type != GatewayProtocol.FAILURE) {
            assertNotEquals(GatewayProtocol.SCAN_RESULT, frame.type)
            assertNotEquals(GatewayProtocol.SCAN_END, frame.type)
            frame = channel.read()
        }
        assertEquals("Bluetooth désactivé sur la passerelle", frame.text())
        channel.send(GatewayProtocol.CLOSE); channel.close()
        assertTrue(waitFor { GatewayService.status.value.lastEvents.any { it.endsWith("Recherche Bluetooth : Bluetooth désactivé sur la passerelle") } })
    }

    @Test fun `stop, listener closed, locks released, stopped state`() {
        val service = start()
        val port = GatewayService.boundPort!!
        val wake = ShadowPowerManager.getLatestWakeLock()
        controller!!.destroy(); controller = null
        val status = GatewayService.status.value
        assertFalse(status.running)
        assertEquals("Passerelle arrêtée", status.message)
        assertNull(status.pilot)
        assertNull(GatewayService.boundPort)
        assertThrows(ConnectException::class.java) { Socket("127.0.0.1", port).close() }
        assertTrue(waitFor { !wake.isHeld })
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `notification Stop action, the gateway stops by itself`() {
        start()
        val service = controller!!.withIntent(Intent(context, GatewayService::class.java).setAction("STOP_GATEWAY")).startCommand(0, 2).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `cannot listen, start refused and service stopped`() {
        GatewayService.listenPort = -1
        val service = start()
        val status = GatewayService.status.value
        assertFalse(status.running)
        assertTrue(status.message, status.message.startsWith("Démarrage refusé : "))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `chosen robot, reconnection at startup and state in the notification`() {
        GatewayConfig.saveRobot(context, "AA:BB:CC:DD:EE:FF", "Luba-VS123")
        val service = start()
        assertTrue(waitFor { GatewayService.status.value.robot.address == "AA:BB:CC:DD:EE:FF" })
        val label = GatewayService.status.value.robot.label
        assertTrue(waitFor { context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == 8767 && it.notification.text() == GatewayService.status.value.robot.label } })
        assertTrue(label, label.contains("Luba-VS123"))
        assertNotNull(service)
    }

    @Test fun `robot chosen or released outside the service, saved and gateway started`() {
        GatewayService.connectRobot(context, "11:22:33:44:55:66", "Luba-X")
        assertEquals("11:22:33:44:55:66" to "Luba-X", GatewayConfig.robot(context))
        val started = shadowOf(context).nextStartedService
        assertEquals(GatewayService::class.java.name, started.component?.className)
        assertNull(GatewayService.releaseRobot(context))
        assertNull(GatewayConfig.robot(context))
    }

    @Test fun `phone restart, gateway relaunched only in its role`() {
        val receiver = GatewayBootReceiver()
        GatewayConfig.save(context, GatewayConfig(GatewayRole.CLIENT, code = "123456"))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedService)
        GatewayConfig.save(context, GatewayConfig(GatewayRole.GATEWAY, code = "123456"))
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertNull(shadowOf(context).nextStartedService)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(GatewayService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(GatewayService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
    }
}
