package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg

 data class Telemetry(
    val bladeHeightMm: Int? = null,
    val heightMoving: Boolean? = null, val heightTargetMm: Int? = null,
    val driverHeightMm: Int? = null, val lastDriverHeightAt: Long? = null,
    val workHeightMm: Int? = null, val lastWorkHeightAt: Long? = null,
    val cutterRpm: Int? = null,
    val lastBladeHeightAt: Long? = null,
    val lastCutterRpmAt: Long? = null,
    val knifeStatus: Int? = null,
    val lastKnifeStatusAt: Long? = null,
    val cutterMode: Int? = null,
    val battery: Int? = null,
    val chargeState: Int? = null, val workProgress: Int? = null,
    val robotState: Int? = null,
    val sensorStatus: Int? = null,
    val selfCheck: Long? = null,
    val x: Double? = null,
    val y: Double? = null,
    val heading: Double? = null, // Mathematical angle: +x = 0°, +y = 90°.
    val rawHeading: Double? = null,
    val baseX: Double? = null, val baseY: Double? = null, val lastBaseAt: Long? = null,
    val positionType: Int? = null,
    val wifiRssi: Int? = null, val bleRssi: Int? = null, val mobileRssi: Int? = null,
    val lastConnectAt: Long? = null, val gpsStars: Int? = null,
    val rtkStatus: Int? = null, val lastRtkAt: Long? = null,
    /** Raw `rtk.pos_level`, no documented meaning (1 recorded in full FIX on 2026-10-15): does not tell whether the position is FIX. */
    val positioningLevel: Int? = null,
    /** Latitude/longitude published by the robot (RTK station or position), for the garden weather. */
    val geoLat: Double? = null, val geoLon: Double? = null, val geoSource: String? = null,
    val firmware: String? = null,
    val lastReportAt: Long? = null,
    val lastPositionAt: Long? = null,
    val lastDeviceAt: Long? = null,
    val positionRate: Double = 0.0,
    val reports: Long = 0
) {
    /**
     * RTK FIX solution: `rtk.status` = 4 (PyMammotion RTKStatus: 0 none, 1–2 Single, 4 Fix, 5 Float). The app's only
     * definition of FIX; `pos_level` is not one (it was 1 with status 4 in every record of 2026-10-15).
     */
    val rtkFix: Boolean get() = rtkStatus == RTK_FIX
    companion object { const val RTK_FIX = 4 }
}

class TelemetryReducer {
    var value = Telemetry()
        private set
    private val positions = ArrayDeque<Long>()

    /** An ACK, a heartbeat or an unknown message is not telemetry. */
    fun accept(message: LubaMsg, wallTime: Long, elapsed: Long): Boolean {
        if (message.hasNav() && message.nav.hasToappChgpileto()) {
            val base = message.nav.toappChgpileto
            require(base.x.isFinite() && base.y.isFinite()) { tr("Invalid base position", "Position de base invalide") }
            value = value.copy(baseX = base.x.toDouble(), baseY = base.y.toDouble(), lastBaseAt = wallTime)
            // This frame is not a safety report for the watchdog.
        }
        if (message.hasNav() && message.nav.hasToappLatUp()) message.nav.toappLatUp.let { geo ->
            // Depending on the version, the firmware publishes the position in degrees or radians: both are accepted.
            val (lat, lon) = if (kotlin.math.abs(geo.lat) <= kotlin.math.PI / 2 && kotlin.math.abs(geo.lon) <= kotlin.math.PI && (geo.lat != 0.0 || geo.lon != 0.0))
                Math.toDegrees(geo.lat) to Math.toDegrees(geo.lon) else geo.lat to geo.lon
            if (validGeo(lat, lon)) value = value.copy(geoLat = lat, geoLon = lon, geoSource = "robot")
        }
        if (message.hasDriver() && message.driver.hasBidireKnifeHeightReport()) {
            val height = message.driver.bidireKnifeHeightReport.knifeHeight
            require(height in 25..70) { tr("Reported height out of range", "Hauteur rapportée hors plage") }
            value = value.copy(bladeHeightMm = height, lastBladeHeightAt = wallTime, driverHeightMm = height, lastDriverHeightAt = wallTime)
        }
        if (message.hasDriver() && message.driver.hasToappKnifeStatusChange()) {
            val change = message.driver.toappKnifeStatusChange
            value = value.copy(heightMoving = change.isStart != 0, heightTargetMm = change.endHeight)
            if (change.curHeight in 25..70) value = value.copy(
                bladeHeightMm = change.curHeight, lastBladeHeightAt = wallTime,
                driverHeightMm = change.curHeight, lastDriverHeightAt = wallTime)
        }
        if (message.hasDriver() && message.driver.hasToappKnifeStatus()) {
            value = value.copy(knifeStatus = message.driver.toappKnifeStatus.knifeStatus, lastKnifeStatusAt = wallTime)
        }
        if (message.hasDriver() && message.driver.hasCurrentCutterMode()) {
            val cutter = message.driver.currentCutterMode
            value = value.copy(cutterMode = cutter.currentCutterMode,
                cutterRpm = if (cutter.currentCutterRpm != 0) cutter.currentCutterRpm else value.cutterRpm,
                lastCutterRpmAt = if (cutter.currentCutterRpm != 0) wallTime else value.lastCutterRpmAt)
        }
        if (!message.hasSys() || !message.sys.hasToappReportData()) return false
        val report = message.sys.toappReportData
        if (report.hasConnect()) {
            val connection = report.connect
            fun rssi(raw: Int): Int? = raw.takeIf { it in -126..-1 }
            value = value.copy(wifiRssi=rssi(connection.wifiRssi), bleRssi=rssi(connection.bleRssi),
                mobileRssi=rssi(connection.mnetRssi), lastConnectAt=wallTime)
        }
        if (report.hasWork()) value = value.copy(workProgress = report.work.progress)
        if (report.hasWork() && report.work.knifeHeight in 25..70) {
            value = value.copy(bladeHeightMm = report.work.knifeHeight, lastBladeHeightAt = wallTime, workHeightMm = report.work.knifeHeight, lastWorkHeightAt = wallTime)
            // The firmware does not always send the end of movement: reaching the target ends it.
            if (value.heightMoving == true && value.heightTargetMm == report.work.knifeHeight) value = value.copy(heightMoving = false)
        }
        if (report.hasCutterWorkModeInfo()) {
            value = value.copy(cutterRpm = report.cutterWorkModeInfo.currentCutterRpm, lastCutterRpmAt = wallTime)
        }
        // An RTK report alone must immediately invalidate an old FIX solution.
        if (report.hasRtk()) value = value.copy(rtkStatus = report.rtk.status,
            positioningLevel = report.rtk.posLevel, gpsStars = report.rtk.gpsStars, lastRtkAt = wallTime)
        if (report.hasRtk() && report.rtk.hasMqttRtkInfo()) report.rtk.mqttRtkInfo.let { info ->
            if (validGeo(info.latitude, info.longitude)) value = value.copy(geoLat = info.latitude, geoLon = info.longitude, geoSource = "station RTK") // stored value (weather location source), translated at display
        }
        if (!report.hasDev() && report.locationsCount == 0) return false
        if (report.hasDev()) {
            require(report.dev.batteryVal in 0..100) { tr("Battery out of range", "Batterie hors plage") }
            value = value.copy(battery = report.dev.batteryVal, chargeState = report.dev.chargeState, robotState = report.dev.sysStatus,
                sensorStatus = report.dev.sensorStatus, selfCheck = report.dev.selfCheckStatus.toLong() and 0xffffffffL,
                lastDeviceAt = wallTime)
        }
        if (report.locationsCount > 0) {
            val location = report.getLocations(0)
            // PyMammotion device.update_report_data: integers in 10^-4 m/degree.
            value = value.copy(x = location.realPosX / 10000.0, y = location.realPosY / 10000.0,
                heading = RobotCoordinates.mapHeading(location.realToward / 10000.0), rawHeading = location.realToward / 10000.0, positionType = location.posType,
                lastPositionAt = wallTime)
            positions.addLast(elapsed)
        }
        while (positions.isNotEmpty() && elapsed - positions.first() > 10000) positions.removeFirst()
        value = value.copy(
            lastRtkAt = if (report.hasRtk()) wallTime else value.lastRtkAt,
            rtkStatus = if (report.hasRtk()) report.rtk.status else value.rtkStatus,
            positioningLevel = if (report.hasRtk()) report.rtk.posLevel else value.positioningLevel,
            firmware = if (report.hasFwInfo()) report.fwInfo.version.ifBlank { value.firmware } else value.firmware,
            lastReportAt = wallTime, reports = value.reports + 1, positionRate = rate(elapsed))
        return true
    }

    private fun validGeo(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (kotlin.math.abs(lat) > 1e-3 || kotlin.math.abs(lon) > 1e-3)

    fun rate(elapsed: Long): Double {
        while (positions.isNotEmpty() && elapsed - positions.first() > 10000) positions.removeFirst()
        if (positions.size < 2) return 0.0
        val duration = elapsed - positions.first()
        return if (duration > 0) (positions.size - 1) * 1000.0 / duration else 0.0
    }
}
