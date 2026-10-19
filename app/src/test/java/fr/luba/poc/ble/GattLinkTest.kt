package fr.luba.poc.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Local GATT link ([GattLink]): connection, discovery, MTU, subscription, writes, notifications, losses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GattLinkTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter

    private class Recorder : RobotLink.Listener {
        val events = mutableListOf<String>()
        override fun onDiscovering(link: RobotLink) { events += "discovering" }
        override fun onReady(link: RobotLink, mtu: Int) { events += "ready $mtu" }
        override fun onLost(link: RobotLink, code: Int, reason: String) { events += "lost $code" }
        override fun onFailure(link: RobotLink, reason: String) { events += "failure $reason" }
        override fun onWritten(link: RobotLink, code: Int) { events += "written $code" }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) { events += "rssi $rssi" }
        override fun onNotification(link: RobotLink, bytes: ByteArray) { events += "notification ${bytes.size}" }
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) { events += "event $kind" }
    }

    private fun service(notify: Boolean = true): BluetoothGattService = BluetoothGattService(RobotLink.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(BluetoothGattCharacteristic(RobotLink.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        if (notify) addCharacteristic(BluetoothGattCharacteristic(RobotLink.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ).apply {
            addDescriptor(BluetoothGattDescriptor(RobotLink.CCC, BluetoothGattDescriptor.PERMISSION_WRITE))
        })
    }

    private fun gattOf(link: GattLink): BluetoothGatt =
        GattLink::class.java.getDeclaredField("gatt").apply { isAccessible = true }.get(link) as BluetoothGatt

    @Test fun `full connection then write, notification, RSSI and loss`() {
        val recorder = Recorder()
        val link = requireNotNull(GattLink.open(app, adapter, "AA:BB:CC:00:00:02", recorder))
        assertEquals("BLE", link.description)
        val gatt = gattOf(link)
        val shadow = shadowOf(gatt)
        val service = service()
        shadow.addDiscoverableService(service)
        shadow.allowCharacteristicNotification(service.getCharacteristic(RobotLink.NOTIFY))
        val callback = requireNotNull(shadow.gattCallback)
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertTrue(recorder.events.toString(), "discovering" in recorder.events)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.onMtuChanged(gatt, 512, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(gatt, service.getCharacteristic(RobotLink.NOTIFY).getDescriptor(RobotLink.CCC), BluetoothGatt.GATT_SUCCESS)
        assertTrue(recorder.events.toString(), "ready 512" in recorder.events)
        assertTrue(link.write(byteArrayOf(0x4d, 0, 0, 1, 7)))
        callback.onCharacteristicWrite(gatt, service.getCharacteristic(RobotLink.WRITE), BluetoothGatt.GATT_SUCCESS)
        assertTrue("written 0" in recorder.events)
        callback.onCharacteristicChanged(gatt, service.getCharacteristic(RobotLink.NOTIFY), byteArrayOf(1, 2, 3))
        assertTrue("notification 3" in recorder.events)
        link.readRssi()
        callback.onReadRemoteRssi(gatt, -61, BluetoothGatt.GATT_SUCCESS)
        assertTrue("rssi -61" in recorder.events)
        callback.onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue("lost 8" in recorder.events)
        // Deliberate close: no more callbacks, no more writes.
        link.close()
        val before = recorder.events.size
        callback.onCharacteristicChanged(gatt, service.getCharacteristic(RobotLink.NOTIFY), byteArrayOf(1))
        assertEquals(before, recorder.events.size)
        assertFalse(link.write(byteArrayOf(1)))
        assertFalse(link.readRssi())
    }

    @Test fun `incomplete service or refused MTU, failure reported`() {
        val recorder = Recorder()
        val link = requireNotNull(GattLink.open(app, adapter, "AA:BB:CC:00:00:03", recorder))
        val gatt = gattOf(link)
        val shadow = shadowOf(gatt)
        shadow.addDiscoverableService(service(notify = false))
        shadow.gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        shadow.gattCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertTrue(recorder.events.toString(), recorder.events.any { it.startsWith("failure") && it.contains("FF02") })
        val other = Recorder()
        val second = requireNotNull(GattLink.open(app, adapter, "AA:BB:CC:00:00:04", other))
        val gatt2 = gattOf(second)
        shadowOf(gatt2).gattCallback.onServicesDiscovered(gatt2, 129)
        assertTrue(other.events.toString(), other.events.any { it.startsWith("failure") })
        assertNull(GattLink.open(app, null, "AA:BB:CC:00:00:05", other))
    }
}
