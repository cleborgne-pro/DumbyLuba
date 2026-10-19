package fr.luba.poc.gateway

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import fr.luba.poc.map.PhoneGravity

/**
 * Accelerometer of the gateway phone lying on the robot, smoothed over about one second (vibrations, robot
 * accelerations). The resulting vertical gives the robot tilt, hence the terrain slope (Slope and Terrain layers).
 */
class PhoneTiltSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val filter = GravityFilter()

    val available get() = sensor != null

    fun start() { sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { runCatching { manager?.unregisterListener(this) } }

    /** Last smoothed vertical and its age in ms, or null without a recent measurement. */
    fun current(): Pair<PhoneGravity, Int>? = filter.current(SystemClock.elapsedRealtime())

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size >= 3) filter.add(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
            event.timestamp / 1_000_000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Exponential low-pass filter (time constant [tauMs]); clock in ms since boot. */
class GravityFilter(private val tauMs: Double = 1000.0, private val maxAgeMs: Long = 2000) {
    private var x = 0.0; private var y = 0.0; private var z = 0.0
    private var at = Long.MIN_VALUE
    @Synchronized fun add(ax: Double, ay: Double, az: Double, timeMs: Long) {
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return
        if (at == Long.MIN_VALUE || timeMs - at > maxAgeMs) { x = ax; y = ay; z = az }
        else {
            val k = (1 - kotlin.math.exp(-(timeMs - at).coerceAtLeast(0) / tauMs))
            x += k * (ax - x); y += k * (ay - y); z += k * (az - z)
        }
        at = timeMs
    }
    @Synchronized fun current(now: Long): Pair<PhoneGravity, Int>? =
        if (at == Long.MIN_VALUE || now - at > maxAgeMs) null else PhoneGravity(x, y, z) to (now - at).coerceAtLeast(0).toInt()
}
