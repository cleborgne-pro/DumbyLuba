package fr.luba.poc.gateway

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Phone sensors: gateway accelerometer (terrain) and pilot Wi-Fi (cause of an outage). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneSensorsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `accelerometer, readings smoothed while recent, listener released on stop`() {
        val manager = context.getSystemService(SensorManager::class.java)
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(manager).addSensor(accelerometer)
        val tilt = PhoneTiltSensor(context)
        assertTrue(tilt.available)
        assertNull(tilt.current())
        tilt.start()
        assertTrue(shadowOf(manager).hasListener(tilt))
        fun send(x: Float, y: Float, z: Float) = shadowOf(manager).sendSensorEventToListeners(SensorEventBuilder.newBuilder()
            .setSensor(accelerometer).setValues(floatArrayOf(x, y, z)).setTimestamp(SystemClock.elapsedRealtimeNanos()).build())
        send(0f, 0f, 9.8f)
        val (gravity, age) = tilt.current()!!
        assertEquals(9.8, gravity.z, 1e-3); assertEquals(0, age)
        // Brief jolt: smoothed, not copied.
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        send(3f, 0f, 9.8f)
        assertTrue(tilt.current()!!.first.x in 0.1..1.0)
        // No reading for 2 s: tilt unknown rather than stale.
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_001))
        assertNull(tilt.current())
        tilt.stop()
        assertFalse(shadowOf(manager).hasListener(tilt))
    }

    @Test fun `phone without accelerometer, sensor unavailable, start has no effect`() {
        val tilt = PhoneTiltSensor(context)
        assertFalse(tilt.available)
        tilt.start(); tilt.stop()
        assertNull(tilt.current())
    }

    @Test fun `pilot Wi-Fi, each change announced once to listeners`() {
        // Process monitoring already installed by the application of a previous test: reused here.
        listOf("wifi" to null, "connected" to null).forEach { (name, value) ->
            PhoneWifi::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
        }
        PhoneWifi.install(context)
        PhoneWifi.install(context) // a single monitor
        val heard = CopyOnWriteArrayList<String>()
        val listener: (String) -> Unit = { heard += it }
        PhoneWifi.listen(listener)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(connectivity).networkCallbacks
        assertTrue(callbacks.isNotEmpty())
        val network = ShadowNetwork.newInstance(7)
        callbacks.forEach { it.onLost(network) }
        callbacks.forEach { it.onLost(network) }
        assertEquals(false, PhoneWifi.connected)
        assertEquals(1, heard.size)
        assertTrue(heard.single(), heard.single().startsWith("Wi-Fi du téléphone déconnecté depuis "))
        assertTrue(PhoneWifi.phoneOffline())
        callbacks.forEach { it.onAvailable(network) }
        assertEquals("Wi-Fi du téléphone connecté", heard.last())
        assertFalse(PhoneWifi.phoneOffline())
        // Wi-Fi turned off in the settings: that is the cause shown.
        @Suppress("DEPRECATION") context.getSystemService(WifiManager::class.java).setWifiEnabled(false)
        assertTrue(PhoneWifi.phoneOffline())
        assertTrue(PhoneWifi.describe().startsWith("Wi-Fi du téléphone désactivé"))
        PhoneWifi.unlisten(listener)
        callbacks.forEach { it.onLost(network) }
        assertEquals(2, heard.size)
    }
}
