package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Telemetry
import kotlin.math.floor

enum class SurveySignal(private val en: String, private val fr: String) {
    WIFI("Wi-Fi", "Wi-Fi"), RTK("RTK FIX", "RTK FIX"), BLE("Bluetooth", "Bluetooth"), MOBILE("Mobile", "Mobile"),
    GPS("GNSS satellites", "Satellites GNSS"),
    /** Terrain layers: computed over the whole survey (tilt of the phone mounted on the robot), not per reading. */
    SLOPE("Slope", "Pente"), RELIEF("Terrain", "Relief");
    /** Display label in the interface language. */
    val label: String get() = tr(en, fr)
    val terrain get() = this == SLOPE || this == RELIEF
}

data class SignalSample(val point: Point, val at: Long, val wifi: Int?, val ble: Int?, val mobile: Int?,
    val rtkLevel: Int?, val satellites: Int?,
    /** Robot heading (mathematical angle, degrees) and acceleration of the phone mounted on the robot, for the terrain. */
    val heading: Double? = null, val gravity: PhoneGravity? = null) {
    fun value(signal: SurveySignal): Double? = when (signal) {
        SurveySignal.WIFI -> wifi?.toDouble()
        SurveySignal.BLE -> ble?.toDouble()
        SurveySignal.MOBILE -> mobile?.toDouble()
        SurveySignal.RTK -> rtkLevel?.let { if (it == 0) 100.0 else 0.0 }
        SurveySignal.GPS -> satellites?.toDouble()
        SurveySignal.SLOPE, SurveySignal.RELIEF -> null
    }
    val hasTilt get() = gravity != null && heading?.isFinite() == true
}

data class SignalSurvey(val robotId: String = "", val mapKey: String = "", val zoneIds: List<Long> = emptyList(),
    val samples: List<SignalSample> = emptyList(),
    val sessionId: String = "", val startedAt: Long = 0, val simulated: Boolean = false,
    val mode: CoverageMode = CoverageMode.TOUR, val mapSnapshot: RobotMap? = null,
    /** Continuous map ([SignalLiveMap]): updated whenever the robot moves, one version per day. */
    val live: Boolean = false)
/** [level]: position 0..1 in the survey scale (terrain), null for fixed scales. */
data class SignalCell(val point: Point, val value: Double, val count: Int, val level: Float? = null)

object SignalCoverage {
    /** Key of the Mammotion map: a local correction does not detach the surveys. */
    fun mapKey(map: RobotMap): String = map.originalShapes.hashCode().toString()
    /** Freshness checked separately for the position and each signal family. Zero RSSI = unknown.
     * [contours]: reading kept within 30 cm of a survey route; null: everywhere (continuous map). */
    fun sample(t: Telemetry, now: Long, contours: List<List<Point>>?, tilt: PhoneGravity? = null): SignalSample? {
        fun fresh(at: Long?) = at?.let { now - it in 0..2000 } == true
        val x = t.x ?: return null; val y = t.y ?: return null
        if (!x.isFinite() || !y.isFinite() || !fresh(t.lastPositionAt)) return null
        val point = Point(x,y)
        if (contours != null && contours.none { path -> path.zipWithNext().any { (a,b) -> Geometry.distance(point,a,b) <= 0.3 } }) return null
        fun rssi(value: Int?) = value?.takeIf { fresh(t.lastConnectAt) && it in -126..-1 }
        val rtk = t.positioningLevel.takeIf { fresh(t.lastRtkAt) }
        val stars = t.gpsStars?.takeIf { fresh(t.lastRtkAt) && it >= 0 }
        val heading = t.heading?.takeIf { it.isFinite() && tilt != null }
        val sample = SignalSample(point,requireNotNull(t.lastPositionAt),rssi(t.wifiRssi),rssi(t.bleRssi),rssi(t.mobileRssi),rtk,stars,
            heading,tilt?.takeIf { heading != null })
        return sample.takeIf { s -> s.hasTilt || SurveySignal.entries.any { s.value(it) != null } }
    }
    /** Measured 1 m cells; no extrapolation towards the inside of the zones. */
    fun cells(samples: List<SignalSample>, signal: SurveySignal, mount: PhoneMount = PhoneMount.TOP_FORWARD): List<SignalCell> = when (signal) {
        SurveySignal.SLOPE -> ReliefModel.result(samples,mount)?.slopeCells.orEmpty()
        SurveySignal.RELIEF -> ReliefModel.result(samples,mount)?.heightCells.orEmpty()
        else -> radioCells(samples,signal)
    }
    private fun radioCells(samples: List<SignalSample>, signal: SurveySignal): List<SignalCell> = samples
        .filter { it.value(signal) != null }.groupBy { floor(it.point.x).toInt() to floor(it.point.y).toInt() }
        .map { (key, values) -> SignalCell(Point(key.first+0.5,key.second+0.5),values.map { requireNotNull(it.value(signal)) }.average(),values.size) }
    /** Deterministic fictional model, for simulation only; never radio readings. */
    fun simulated(point: Point, base: Point, elapsedSeconds: Double, heading: Double? = null): SignalSample {
        val distance = point.distance(base)
        val shadow = kotlin.math.sin(point.x*0.27)*kotlin.math.cos(point.y*0.19)
        val wifi = (-40-distance*1.1+shadow*12).toInt().coerceIn(-110,-35)
        val ble = (-38-distance*1.6+shadow*8).toInt().coerceIn(-115,-30)
        val mobile = (-70+kotlin.math.cos(point.x*0.11+point.y*0.09)*20).toInt()
        val fix = if (kotlin.math.sin(point.x*0.17)+kotlin.math.cos(point.y*0.23)>-0.5) 0 else 1
        return SignalSample(point,(elapsedSeconds*1000).toLong(),wifi,ble,mobile,fix,if(fix==0) 28 else 12,
            heading,heading?.let { ReliefModel.syntheticGravity(point,it) })
    }
    fun strength(signal: SurveySignal, cell: SignalCell): Float = cell.level?.takeIf { signal == SurveySignal.RELIEF } ?: strength(signal,cell.value)
    fun strength(signal: SurveySignal, value: Double): Float = when (signal) {
        SurveySignal.RTK -> (value / 100).toFloat()
        // Slope: green when flat, red at 30 % and beyond (a lawn rarely exceeds 30 %).
        SurveySignal.SLOPE -> (1 - value / 30).toFloat()
        SurveySignal.RELIEF -> 0.5f
        SurveySignal.GPS -> (value / 30).toFloat()
        // Wi-Fi: red at −80 dBm (gateway link unstable below −70), green at −50 dBm.
        SurveySignal.WIFI -> ((value + 80) / 30).toFloat()
        else -> ((value + 100) / 50).toFloat()
    }.coerceIn(0f,1f)
}
