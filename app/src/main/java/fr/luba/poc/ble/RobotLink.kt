@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import fr.luba.poc.i18n.tr
import java.util.UUID

/**
 * Link to the robot: raw BluFi frames written to FF01, FF02 notifications received.
 * Two implementations: [GattLink] (this phone's Bluetooth) and the Wi-Fi gateway
 * ([fr.luba.poc.gateway.GatewayLink]), which has the same frames written by the phone mounted on the robot.
 * One operation at a time: each [write] and [readRssi] gets exactly one response.
 */
interface RobotLink {
    /** Writes a complete BluFi frame (sequence already assigned); response through [Listener.onWritten]. */
    fun write(frame: ByteArray): Boolean
    fun readRssi(): Boolean
    /** Deliberate close: no more callbacks afterwards. */
    fun close()
    /** Short label for the log ("BLE", "passerelle 192.168.0.189"). */
    val description: String

    /** Callbacks may be invoked on any thread. */
    interface Listener {
        fun onDiscovering(link: RobotLink) {}
        /** Notifications active: the link accepts writes. */
        fun onReady(link: RobotLink, mtu: Int)
        /** Link lost (GATT code, or a negative code for the gateway). */
        fun onLost(link: RobotLink, code: Int, reason: String)
        /** Setup or protocol failure. */
        fun onFailure(link: RobotLink, reason: String)
        fun onWritten(link: RobotLink, code: Int)
        fun onRssi(link: RobotLink, rssi: Int, code: Int)
        fun onNotification(link: RobotLink, bytes: ByteArray)
        fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?> = emptyMap()) {}
        /** Frame from the gateway's autonomous tracking (progress, surveys, outcome). */
        fun onLegFrame(link: RobotLink, type: Int, payload: ByteArray) {}
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val GATT_SUCCESS = 0
    }
}

/** Local GATT link: connection, discovery, MTU 512, FF02 subscription, then writes with response. */
@SuppressLint("MissingPermission")
class GattLink private constructor(private val listener: RobotLink.Listener) : RobotLink {
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var closed = false
    override val description = "BLE"

    private fun guarded(block: () -> Unit) {
        if (closed) return
        try { block() } catch (error: Exception) {
            if (!closed) listener.onFailure(this, tr("BLE: ${error.message ?: error.javaClass.simpleName}", "BLE : ${error.message ?: error.javaClass.simpleName}"))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, newState: Int) = guarded {
            listener.onEvent(this@GattLink, "gatt_connection_callback", tr("GATT state", "État GATT"), mapOf("statusCode" to code, "newState" to newState))
            if (code != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onLost(this@GattLink, code, tr("BLE lost ($code)", "Perte BLE ($code)"))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onDiscovering(this@GattLink)
                check(g.discoverServices()) { tr("GATT discovery refused", "Découverte GATT refusée") }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) = guarded {
            check(code == BluetoothGatt.GATT_SUCCESS) { tr("Service discovery: $code", "Découverte services : $code") }
            listener.onEvent(this@GattLink, "gatt_services", tr("GATT inventory", "Inventaire GATT"), mapOf("services" to g.services.map { service ->
                mapOf("uuid" to service.uuid.toString(), "characteristics" to service.characteristics.map { c ->
                    mapOf("uuid" to c.uuid.toString(), "properties" to c.properties, "descriptors" to c.descriptors.map { it.uuid.toString() }) }) }))
            val service = requireNotNull(g.getService(RobotLink.SERVICE)) { tr("Mammotion FFFF service missing", "Service Mammotion FFFF absent") }
            val characteristic = requireNotNull(service.getCharacteristic(RobotLink.WRITE)) { tr("FF01 missing", "FF01 absent") }
            check(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { tr("Write with response unavailable", "Écriture avec réponse indisponible") }
            requireNotNull(service.getCharacteristic(RobotLink.NOTIFY)) { tr("FF02 missing", "FF02 absent") }
            write = characteristic
            if (!g.requestMtu(512)) subscribe(g)
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, code: Int) = guarded {
            listener.onEvent(this@GattLink, "gatt_mtu", tr("MTU negotiated", "MTU négocié"), mapOf("mtu" to value, "statusCode" to code))
            mtu = if (code == BluetoothGatt.GATT_SUCCESS) value else 23
            subscribe(g)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) = guarded {
            listener.onEvent(this@GattLink, "gatt_cccd", tr("Notification subscription", "Abonnement notifications"), mapOf("uuid" to descriptor.uuid.toString(), "statusCode" to code))
            check(descriptor.uuid == RobotLink.CCC && code == BluetoothGatt.GATT_SUCCESS) { tr("Notification subscription: $code", "Abonnement notifications : $code") }
            listener.onReady(this@GattLink, mtu)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, code: Int) = guarded {
            if (characteristic.uuid == RobotLink.WRITE) listener.onWritten(this@GattLink, code)
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, code: Int) = guarded { listener.onRssi(this@GattLink, rssi, code) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = guarded {
            if (characteristic.uuid == RobotLink.NOTIFY) listener.onNotification(this@GattLink, value.copyOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = guarded {
            if (android.os.Build.VERSION.SDK_INT < 33 && characteristic.uuid == RobotLink.NOTIFY) listener.onNotification(this@GattLink, characteristic.value.copyOf())
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val notification = requireNotNull(g.getService(RobotLink.SERVICE)?.getCharacteristic(RobotLink.NOTIFY))
        check(notification.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) { tr("Notifications unavailable", "Notifications indisponibles") }
        check(g.setCharacteristicNotification(notification, true)) { tr("Notifications refused", "Notifications refusées") }
        val descriptor = requireNotNull(notification.getDescriptor(RobotLink.CCC)) { tr("CCCD missing", "CCCD absent") }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        check(g.writeDescriptor(descriptor)) { tr("CCCD write refused", "Écriture CCCD refusée") }
    }

    override fun write(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val target = write ?: return false
        target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        target.value = frame
        return g.writeCharacteristic(target)
    }

    override fun readRssi(): Boolean = gatt?.readRemoteRssi() ?: false

    override fun close() {
        closed = true
        val old = gatt; gatt = null; write = null
        try { old?.disconnect(); old?.close() } catch (_: Exception) { }
    }

    companion object {
        /** Starts the connection; null if Bluetooth is unavailable. */
        fun open(context: Context, adapter: BluetoothAdapter?, address: String, listener: RobotLink.Listener): GattLink? {
            val device = adapter?.getRemoteDevice(address) ?: return null
            val link = GattLink(listener)
            link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE) ?: return null
            return link
        }
    }
}
