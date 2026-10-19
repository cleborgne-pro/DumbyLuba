package fr.luba.poc.gateway

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Wireless debugging turned back on when Android turns it off: permission, Wi-Fi connected, user choice, pace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WirelessDebugKeeperTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val reports = CopyOnWriteArrayList<String>()
    private var keeper: WirelessDebugKeeper? = null

    private fun setting() = Settings.Global.getInt(context.contentResolver, WirelessDebugKeeper.SETTING, 0)
    private fun disable() = Settings.Global.putInt(context.contentResolver, WirelessDebugKeeper.SETTING, 0)
    private fun idle(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    private fun wifi(connected: Boolean) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        if (connected) {
            shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        connectivity.allNetworks.forEach { shadowOf(connectivity).setNetworkCapabilities(it, caps) }
    }

    private fun start(permission: Boolean = true) {
        if (permission) shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        keeper = WirelessDebugKeeper(context) { reports += it }.also { it.start(); it.start() }
    }

    @After fun stop() { keeper?.stop() }

    @Test fun `off with Wi-Fi and permission, turned back on after settling, then at the periodic check`() {
        wifi(true); disable()
        start()
        assertTrue(WirelessDebugKeeper.canWrite(context))
        idle(Duration.ofMillis(2_900))
        assertEquals(0, setting())
        idle(Duration.ofMillis(200))
        assertEquals(1, setting())
        assertTrue(WirelessDebugKeeper.enabled(context))
        assertEquals(listOf("Débogage sans fil réactivé"), reports)
        // Turned off again by Android: turned back on at the next check (60 s), a single attempt.
        disable()
        idle(Duration.ofSeconds(60))
        assertEquals(1, setting())
        assertEquals(2, reports.size)
    }

    @Test fun `without Wi-Fi, without permission or refused by the user, no attempt`() {
        wifi(false); disable()
        start()
        idle(Duration.ofSeconds(5))
        assertEquals(0, setting())
        keeper!!.stop()

        wifi(true)
        WirelessDebugKeeper.setKeep(context, false)
        start()
        idle(Duration.ofSeconds(5))
        assertEquals(0, setting())
        assertTrue(reports.isEmpty())
    }

    @Test fun `stop, no more checks`() {
        wifi(true); disable()
        start()
        keeper!!.stop()
        idle(Duration.ofSeconds(120))
        assertEquals(0, setting())
        assertTrue(reports.isEmpty())
    }

    @Test fun `permission missing, no monitor installed and no write`() {
        assertFalse(WirelessDebugKeeper.canWrite(context))
        wifi(true); disable()
        WirelessDebugKeeper.install(context)
        idle(Duration.ofSeconds(120))
        assertEquals(0, setting())
    }
}
