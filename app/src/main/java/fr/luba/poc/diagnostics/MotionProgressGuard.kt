package fr.luba.poc.diagnostics

import fr.luba.poc.map.Point

/** Emergency stop when no translation is observed; this is not a contact sensor. */
class MotionProgressGuard(private val windowMs: Long = 3000, private val minimumMeters: Double = 0.03) {
    private var anchor: Point? = null
    private var started = 0L
    private var previousReport = -1L
    init { require(windowMs > 0 && minimumMeters.isFinite() && minimumMeters > 0) }
    fun reset() { anchor=null; started=0; previousReport=-1 }
    fun blocked(position: Point, positionAt: Long, now: Long, linear: Int): Boolean {
        require(position.x.isFinite() && position.y.isFinite())
        if (linear == 0) { reset(); return false }
        if (positionAt <= previousReport) return false
        previousReport=positionAt
        val origin=anchor
        if (origin==null || position.distance(origin)>=minimumMeters) {
            anchor=position; started=now
            return false
        }
        return now-started>=windowMs
    }
}
