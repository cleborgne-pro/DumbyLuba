package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Telemetry

data class DisplayedRobot(val point: Point, val heading: Double?, val at: Long,
    val source: String, val fresh: Boolean, val quality: Int?)

/** Display only: cloud coordinates never drive BLE navigation. */
object RobotPositionDisplay {
    const val FUTURE_TOLERANCE_MS = 5000L
    /**
     * [recentBle]: last BLE position shown before a reconnection (telemetry reset). Without it, the robot would fall
     * back to an old FIX (often the base) until the link comes back.
     */
    fun resolve(ble: Telemetry, known: LastKnownPosition?, api: Telemetry?, apiKnown: LastKnownPosition?, now: Long,
                recentBle: DisplayedRobot? = null): DisplayedRobot? {
        fun live(t: Telemetry?, source: String, maxAge: Long): DisplayedRobot? {
            t ?: return null
            val at=t.lastPositionAt ?: return null
            val x=t.x?.takeIf { it.isFinite() } ?: return null
            val y=t.y?.takeIf { it.isFinite() } ?: return null
            // The display clock only ticks every second and positions arrive at 1 Hz: a position slightly newer than
            // the clock is valid (otherwise the robot flickers between the BLE position and the last FIX). Only a
            // clearly future date is rejected.
            if (at<=0 || at>now+FUTURE_TOLERANCE_MS) return null
            return DisplayedRobot(Point(x,y),t.heading?.takeIf { it.isFinite() },at,source,maxOf(0L,now-at)<=maxAge,t.positioningLevel)
        }
        val direct=live(ble,"BLE",2000)
        if (direct?.fresh == true) return direct
        fun saved(k: LastKnownPosition?): DisplayedRobot? = k?.last?.takeIf { it.at in 1..now }?.let {
            DisplayedRobot(Point(it.x,it.y),it.heading,it.at,tr("Last FIX", "Dernier FIX"),false,it.quality)
        }
        val recent = recentBle?.takeIf { it.at in 1..now+FUTURE_TOLERANCE_MS }?.copy(source = tr("Last BLE position", "Dernière position BLE"), fresh = false)
        return listOfNotNull(direct,live(api,"API",10000),saved(known),saved(apiKnown),recent).maxByOrNull { it.at }
    }
}
