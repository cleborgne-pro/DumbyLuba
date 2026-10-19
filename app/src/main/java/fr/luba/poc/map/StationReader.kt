package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.TelemetryReducer
import kotlin.math.abs

/** Read-only: the heading of the docked robot defines the opposite of the back-off direction towards the base.
 * The NAV field chargePileType.toward stays unused until its unit is verified.
 *
 * The station origins ("Transmise par le robot", "Position et axe observés en recharge") are stored and compared
 * (cache, base store): they stay in French; [originLabel] translates them for display.
 */
class StationReader(initial: MapStation? = null) {
    private val reducer = TelemetryReducer()
    var station: MapStation? = initial
        private set
    /** Start of the current charge: a position received before it (approaching the base) does not describe the station. */
    private var chargingSince: Long? = null
    fun accept(message: LubaMsg, now: Long = System.currentTimeMillis()): MapStation? {
        reducer.accept(message, now, now)
        val t = reducer.value
        if (message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasDev())
            chargingSince = if (t.chargeState in setOf(1, 2)) chargingSince ?: now else null
        if (message.hasNav() && message.nav.hasToappChgpileto()) {
            val p = Point(t.baseX!!, t.baseY!!)
            // A new position invalidates the old axis if the station was moved.
            station = MapStation(p, station?.takeIf { it.point.distance(p) < 0.1 }?.entryHeadingDegrees,
                "Transmise par le robot")
        }
        if (message.hasSys() && message.sys.hasToappReportData() &&
            (message.sys.toappReportData.hasDev() || message.sys.toappReportData.locationsCount > 0) &&
            t.chargeState in setOf(1, 2) && t.lastDeviceAt?.let { now-it in 0..5000 } == true &&
            t.lastPositionAt?.let { now-it in 0..5000 && it >= (chargingSince ?: Long.MAX_VALUE) } == true && t.heading?.isFinite() == true &&
            t.x != null && t.y != null) {
            val observed = MapStation(Point(t.x, t.y), MapHeading.normalize(t.heading + 180.0),
                "Position et axe observés en recharge")
            // RTK noise while charging: keep the station as long as it has not really moved, otherwise the map would
            // change on every report (prepared route refused at start, resume refused, preview recomputed, cache rewritten).
            if (!sameStation(station, observed)) station = observed
        }
        return station
    }

    private fun sameStation(current: MapStation?, observed: MapStation): Boolean {
        val heading = current?.entryHeadingDegrees ?: return false
        val turn = abs(MapHeading.normalize(observed.entryHeadingDegrees!! - heading + 180.0) - 180.0)
        return current.point.distance(observed.point) <= MOVE_M && turn <= TURN_DEG
    }

    companion object {
        /** Deviation below which the station observed while charging is the same (RTK noise, charging contacts). */
        const val MOVE_M = 0.05
        const val TURN_DEG = 5.0

        /** Display text of a stored station origin (stored values stay in French). */
        fun originLabel(origin: String): String = when (origin) {
            "Transmise par le robot" -> tr("Sent by the robot", "Transmise par le robot")
            "Position et axe observés en recharge" -> tr("Position and axis observed while charging", "Position et axe observés en recharge")
            else -> origin
        }
    }
}
