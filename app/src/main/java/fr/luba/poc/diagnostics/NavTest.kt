package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Commands

/** Position sample observed during a random walk; absent if telemetry provides no position. */
data class NavSample(val x: Double, val y: Double, val heading: Double?, val elapsedMs: Long)

/** Configuration of a supervised random walk. Speeds in raw device units, not calibrated. */
data class NavConfig(
    val segments: Int = 6, val linearSpeed: Int = 50, val angularSpeed: Int = 30,
    val tonte: Boolean = false, val heightMm: Int = 60, val automatic: Boolean = false,
    val pointDestination: Boolean = false, val maxDurationMs: Long = 60_000, val maxRadiusM: Double = 5.0, val batteryFloor: Int = 30,
) {
    init {
        require(segments in 3..20) { tr("Number of segments outside the 3–20 range", "Nombre de segments hors plage 3–20") }
        require(linearSpeed in 1..Commands.MAX_LINEAR_SPEED) { tr("Linear speed outside 1–${Commands.MAX_LINEAR_SPEED}", "Vitesse linéaire hors bornes 1–${Commands.MAX_LINEAR_SPEED}") }
        require(angularSpeed in 1..Commands.MAX_ANGULAR_SPEED) { tr("Angular speed outside 1–${Commands.MAX_ANGULAR_SPEED}", "Vitesse angulaire hors bornes 1–${Commands.MAX_ANGULAR_SPEED}") }
        require(heightMm in fr.luba.poc.protocol.CutHeightRange.ANY) { fr.luba.poc.protocol.CutHeightRange.ANY.refusal(heightMm)!! }
        require(maxDurationMs in 10_000..MAX_DURATION_MS) { tr("Duration outside the 10–600 s range", "Durée hors plage 10–600 s") }
        require(maxRadiusM in 1.0..(if (pointDestination) 200.0 else 20.0)) { tr("Radius outside the 1–20 m range", "Rayon hors plage 1–20 m") }
        require(batteryFloor in 10..80) { tr("Battery floor outside the 10–80 % range", "Plancher batterie hors plage 10–80 %") }
    }
    companion object {
        /** The map course (≈ 20–30 m, pivots included) often exceeds 2 min at reduced speed. */
        const val MAX_DURATION_MS = 600_000L
    }
}

/** Piece of a random walk: rotation in place, forward move, or explicit STOP followed by a pause. */
data class NavSegment(val kind: Kind, val linear: Int, val angular: Int, val durationMs: Long) {
    enum class Kind { ROTATE, ADVANCE, STOP_PAUSE }
}

/** Segmented random walk, deterministic for the given seed. */
object RandomWalk {
    const val MIN_ROTATE_MS = 1000L
    const val MAX_ROTATE_MS = 3000L
    const val MIN_ADVANCE_MS = 2000L
    const val MAX_ADVANCE_MS = 6000L
    const val MIN_PAUSE_MS = 1000L
    const val MAX_PAUSE_MS = 2000L

    /** Random rotation then forward move, explicit STOP and pause between segments.
     * The total duration stays under the cap, the STOP pause is reserved first and the
     * list always ends with a STOP_PAUSE: no movement can be the last piece. */
    fun plan(random: kotlin.random.Random, config: NavConfig): List<NavSegment> {
        val out = mutableListOf<NavSegment>()
        var total = 0L
        for (index in 0 until config.segments) {
            val room = config.maxDurationMs - total
            if (room < MIN_PAUSE_MS) break
            val turn = if (random.nextBoolean()) config.angularSpeed else -config.angularSpeed
            val pause = random.nextLong(MIN_PAUSE_MS, MAX_PAUSE_MS + 1).coerceAtMost(room)
            var left = room - pause
            val rotate = random.nextLong(MIN_ROTATE_MS, MAX_ROTATE_MS + 1).coerceAtMost(left)
            left -= rotate
            val advance = random.nextLong(MIN_ADVANCE_MS, MAX_ADVANCE_MS + 1).coerceAtMost(left)
            if (rotate > 0) out += NavSegment(NavSegment.Kind.ROTATE, 0, turn, rotate)
            if (advance > 0) out += NavSegment(NavSegment.Kind.ADVANCE, config.linearSpeed, 0, advance)
            out += NavSegment(NavSegment.Kind.STOP_PAUSE, 0, 0, pause)
            total += rotate + advance + pause
        }
        return out
    }
}

/** Guards of the supervised random walk. They return the stop reason, or null if the walk may continue. */
object NavSafety {
    /** Containment margin inside the starting zone, when the map is available. */
    const val ZONE_MARGIN_M = 0.5
    /** Start preconditions: identified LUBA 2 robot, ready, stationary, blades OFF. */
    fun startFailure(
        name: String?, stateAgeMs: Long?, battery: Int?, robotState: Int?,
        sensors: Int?, selfCheck: Long?, campaignRunning: Boolean, bladeTestRunning: Boolean,
        mapLoading: Boolean, heightMm: Int, batteryFloor: Int = 30,
        /** Move to a point beyond the mapped zones: self-check 25 ignored there ([fr.luba.poc.protocol.OffMapLatch]). */
        ignoreOffMapLatch: Boolean = false,
        /** Cutting heights of the connected model. */
        heights: fr.luba.poc.protocol.CutHeightRange = fr.luba.poc.protocol.CutHeightRange.forRobot(name),
    ): String? {
        if (!fr.luba.poc.protocol.ModelSupport.motionValidated(name)) return tr("Walk reserved for a LUBA 2 identified as Luba-VS", "Marche réservée au LUBA 2 identifié Luba-VS")
        if (campaignRunning) return tr("Stop the BLE campaign before the walk", "Arrêter la campagne BLE avant la marche")
        if (bladeTestRunning) return tr("Finish the cutting test before the walk", "Terminer l'essai de coupe avant la marche")
        if (mapLoading) return tr("Wait for the map to be read", "Attendre la lecture de carte")
        heights.refusal(heightMm)?.let { return it }
        if (stateAgeMs == null || stateAgeMs !in 0..2000) return tr("Robot state missing or older than 2 s", "État robot absent ou âgé de plus de 2 s")
        if (battery == null || battery <= batteryFloor) return tr("Battery unknown or ≤$batteryFloor %", "Batterie inconnue ou ≤$batteryFloor %")
        if (robotState != 11) return tr("Robot not ready (MODE_READY=11 expected)", "Robot non prêt (MODE_READY=11 attendu)")
        if (!fr.luba.poc.protocol.SelfCheck.nominal(selfCheck) && !(ignoreOffMapLatch && selfCheck == fr.luba.poc.protocol.CollisionLatch.VALUE))
            return fr.luba.poc.protocol.SelfCheck.refusal(selfCheck)
        if (sensors == null || sensors and 7 != 0) return tr("Bumper health alert", "Alerte de santé bumper")
        if (sensors and (7 shl 9) != 0) return tr("Blades not confirmed OFF by telemetry", "Lames non confirmées OFF par la télémétrie")
        return null
    }

    /** Continuous check (every 200 ms) during the walk. */
    fun tickFailure(
        stateAgeMs: Long?, battery: Int?, sensors: Int?, tonte: Boolean, batteryFloor: Int,
        x: Double? = null, y: Double? = null,
        start: NavSample? = null, maxRadiusM: Double = 5.0,
        zone: List<Point>? = null, margin: Double = 0.0,
    ): String? {
        if (stateAgeMs == null || stateAgeMs !in 0..2000) return tr("Robot state missing or stale", "État robot absent ou périmé")
        if (battery == null || battery <= batteryFloor) return tr("Battery unknown or ≤$batteryFloor %", "Batterie inconnue ou ≤$batteryFloor %")
        if (sensors == null || sensors and 7 != 0) return tr("Bumper health alert", "Alerte de santé bumper")
        val bladeBits = (sensors shr 9) and 7
        // Mowing: bits at 0 tolerated — the disc may only be driven while moving (firmware 1.30);
        // only an unrecognised raw state stops the walk. Without mowing, any active blade stays fatal.
        if (tonte && bladeBits > 1) return tr("Blade state not recognized", "État lames non reconnu")
        if (!tonte && bladeBits != 0) return tr("Unexpected active blades", "Lames actives inattendues")
        if (start != null && x != null && y != null) {
            if (kotlin.math.hypot(x - start.x, y - start.y) > maxRadiusM) return tr("Maximum radius exceeded around the start", "Rayon maximal dépassé autour du départ")
            if (zone != null && zone.size >= 3 && !insideMargin(x, y, zone, margin)) return tr("Left the zone or its margin", "Sortie de la zone ou de la marge")
        }
        return null
    }

    /** Two new positions after STOP, with no movement above 5 mm. */
    fun observedStopDelay(trail: List<NavSample>, stopElapsedMs: Long): Long? {
        if (trail.size < 2) return null
        val previous = trail[trail.size - 2]; val last = trail.last()
        if (previous.elapsedMs < stopElapsedMs || last.elapsedMs <= previous.elapsedMs) return null
        if (kotlin.math.hypot(last.x - previous.x, last.y - previous.y) >= 0.005) return null
        return last.elapsedMs - stopElapsedMs
    }

    fun automaticPositionFailure(ageMs: Long?, x: Double?, y: Double?): String? =
        if (ageMs == null || ageMs !in 0..2000 || x == null || y == null || !x.isFinite() || !y.isFinite()) tr("Position missing or stale in automatic mode", "Position absente ou périmée en mode automatique") else null

    /** Point inside the zone, at least `margin` from every edge. */
    fun insideMargin(x: Double, y: Double, zone: List<Point>, margin: Double): Boolean {
        val p = Point(x, y)
        if (!Geometry.inside(p, zone)) return false
        return zone.indices.all { i -> Geometry.distance(p, zone[i], zone[(i + 1) % zone.size]) >= margin }
    }
}
