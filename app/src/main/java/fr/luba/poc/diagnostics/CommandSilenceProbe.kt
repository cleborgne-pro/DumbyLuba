package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.obstacles.LocalObstacle
import org.json.JSONObject

/**
 * "Command cutoff" test: the robot drives slowly in a straight line, then the app stops sending any
 * movement command (neither speed nor STOP) while it keeps listening to telemetry. We observe whether
 * the firmware stops the robot on its own, which simulates a frozen or crashed app while the radio
 * link stays up.
 *
 * Positions are published at 1 Hz: the stop is only timed to the second. It is accepted after
 * [STILL_PAIRS] consecutive pairs of distinct positions at most [STILL_M] apart; the reported delay
 * is an upper bound (position at the start of the standstill minus the time of the last command).
 * If the robot exceeds [maxDriftM] or is not confirmed stationary after [maxSilenceMs], the app
 * sends STOP: the motor watchdog is then not demonstrated.
 */
class CommandSilenceProbe(
    val maxSilenceMs: Long = MAX_SILENCE_MS,
    val maxDriftM: Double = MAX_DRIFT_M,
    val minMotionM: Double = MIN_MOTION_M,
    val maxDriveMs: Long = MAX_DRIVE_MS,
) {
    companion object {
        /** RTK noise tolerated between two stationary positions. */
        const val STILL_M = 0.03
        /** Two stationary pairs: a single repeated position is not enough to conclude. */
        const val STILL_PAIRS = 2
        const val MAX_SILENCE_MS = 6000L
        const val MAX_DRIFT_M = 1.0
        /** Minimum movement observed while driving for the cutoff to be meaningful. */
        const val MIN_MOTION_M = 0.10
        /** Minimum drive before the cutoff (≥ 3 positions at 1 Hz) and maximum without movement. */
        const val DRIVE_MS = 3000L
        const val MAX_DRIVE_MS = 6000L
        /** Clearance required in front of the robot, inside the zone, away from no-go zones and obstacles. */
        const val CLEARANCE_M = 2.5
        /** Threshold beyond which a self-stop is judged too slow for unsupervised mowing. */
        const val ACCEPTABLE_BOUND_MS = 2500L
    }

    enum class Phase { DRIVING, SILENT, DONE }
    private data class Fix(val at: Long, val position: Point)

    var phase = Phase.DRIVING
        private set
    private val driveFixes = mutableListOf<Fix>()
    private val silentFixes = mutableListOf<Fix>()
    private var driveStartedAt = 0L
    private var silenceAt = 0L
    var verdict: SilenceVerdict? = null
        private set

    fun startDrive(now: Long) { driveStartedAt = now }

    /** Positions while driving; ignores repeated positions (same timestamp). */
    fun driveFix(fixAt: Long, position: Point) {
        require(phase == Phase.DRIVING && position.x.isFinite() && position.y.isFinite())
        if (driveFixes.lastOrNull()?.at?.let { it >= fixAt } == true) return
        driveFixes += Fix(fixAt, position)
    }
    val drivenMeters: Double get() = if (driveFixes.size < 2) 0.0 else driveFixes.first().position.distance(driveFixes.last().position)
    /** Average speed observed over the driving positions (m/s), if measurable. */
    val speedMps: Double? get() {
        if (driveFixes.size < 2) return null
        val dt = (driveFixes.last().at - driveFixes.first().at) / 1000.0
        return if (dt > 0) drivenMeters / dt else null
    }

    /** `true` = cut now; `false` = keep driving; error = no movement observed. */
    fun readyToSilence(now: Long): Boolean {
        check(phase == Phase.DRIVING)
        val elapsed = now - driveStartedAt
        if (elapsed >= DRIVE_MS && driveFixes.size >= 2 && drivenMeters >= minMotionM) return true
        check(elapsed < maxDriveMs) { tr("No movement observed: %.0f cm in %d s", "Mouvement non observé : %.0f cm en %d s").format(drivenMeters * 100, elapsed / 1000) }
        return false
    }

    fun startSilence(lastCommandAt: Long) {
        check(phase == Phase.DRIVING && driveFixes.isNotEmpty()) { tr("No position before the cutoff", "Aucune position avant la coupure") }
        phase = Phase.SILENT; silenceAt = lastCommandAt
    }

    private fun conclude(value: SilenceVerdict): SilenceVerdict { verdict = value; phase = Phase.DONE; return value }

    private fun drift(position: Point) = driveFixes.last().position.distance(position)
    /** Distance from the last position published while driving (display). */
    fun driftOf(position: Point): Double? = driveFixes.lastOrNull()?.position?.distance(position)

    /**
     * Position received during the silence. Returns a final verdict, or null to continue.
     * Only positions published after the last command count.
     */
    fun silentFix(fixAt: Long, position: Point): SilenceVerdict? {
        check(phase == Phase.SILENT)
        require(position.x.isFinite() && position.y.isFinite())
        if (fixAt <= silenceAt || silentFixes.lastOrNull()?.at?.let { it >= fixAt } == true) return null
        silentFixes += Fix(fixAt, position)
        val drift = drift(position)
        if (drift > maxDriftM) return conclude(SilenceVerdict.StillMoving(fixAt - silenceAt, drift,
            tr("The robot traveled %.0f cm without command: STOP sent by the app", "Le robot a parcouru %.0f cm sans commande : STOP envoyé par l'application").format(drift * 100)))
        if (silentFixes.size > STILL_PAIRS) {
            val tail = silentFixes.takeLast(STILL_PAIRS + 1)
            if (tail.zipWithNext().all { (a, b) -> a.position.distance(b.position) <= STILL_M }) {
                return conclude(SilenceVerdict.RobotStopped(tail.first().at - silenceAt, drift(tail.last().position), speedMps))
            }
        }
        return null
    }

    /** Clock-driven check, independent of position arrivals. */
    fun tick(now: Long): SilenceVerdict? {
        if (phase != Phase.SILENT) return null
        if (now - silenceAt < maxSilenceMs) return null
        val last = silentFixes.lastOrNull()
        return conclude(if (silentFixes.size < 2) SilenceVerdict.Inconclusive(tr("Not enough positions after the cutoff (${silentFixes.size} in ${maxSilenceMs / 1000} s)",
            "Positions insuffisantes après la coupure (${silentFixes.size} en ${maxSilenceMs / 1000} s)"))
            else SilenceVerdict.StillMoving(now - silenceAt, last?.let { drift(it.position) } ?: 0.0,
                tr("Stop not confirmed ${maxSilenceMs / 1000} s after the last command: STOP sent by the app",
                    "Arrêt non confirmé ${maxSilenceMs / 1000} s après la dernière commande : STOP envoyé par l'application")))
    }

    fun abort(reason: String): SilenceVerdict = conclude(SilenceVerdict.Inconclusive(reason))
}

sealed interface SilenceVerdict {
    val title: String
    /** The robot stopped on its own; [boundMs] = upper bound of the delay since the last command. */
    data class RobotStopped(val boundMs: Long, val driftM: Double, val speedMps: Double?) : SilenceVerdict {
        override val title get() = if (boundMs <= CommandSilenceProbe.ACCEPTABLE_BOUND_MS) tr("Self-stop demonstrated", "Arrêt seul démontré") else tr("Self-stop, but slow", "Arrêt seul, mais lent")
    }
    /** Still moving: the app had to send STOP. */
    data class StillMoving(val elapsedMs: Long, val driftM: Double, val reason: String) : SilenceVerdict {
        override val title get() = tr("No self-stop", "Pas d'arrêt seul")
    }
    data class Inconclusive(val reason: String) : SilenceVerdict {
        override val title get() = tr("Inconclusive", "Non concluant")
    }
}

/** Result stored per robot, shown before mowing. */
data class MotorWatchdogResult(val robotId: String, val at: Long, val firmware: String?, val linearRaw: Int,
                               val outcome: String, val boundMs: Long?, val driftM: Double?, val speedMps: Double?, val detail: String) {
    /** Motor watchdog demonstrated: self-stop, fast enough, during this test. */
    val demonstrated: Boolean get() = outcome == ROBOT_STOPPED && boundMs != null && boundMs <= CommandSilenceProbe.ACCEPTABLE_BOUND_MS

    fun toJson(): String = JSONObject().put("robotId", robotId).put("at", at).put("firmware", firmware).put("linearRaw", linearRaw)
        .put("outcome", outcome).put("boundMs", boundMs).put("driftM", driftM).put("speedMps", speedMps).put("detail", detail).toString()

    companion object {
        const val ROBOT_STOPPED = "ROBOT_STOPPED"
        const val STILL_MOVING = "STILL_MOVING"
        const val INCONCLUSIVE = "INCONCLUSIVE"

        fun of(robotId: String, at: Long, firmware: String?, linearRaw: Int, verdict: SilenceVerdict) = when (verdict) {
            is SilenceVerdict.RobotStopped -> MotorWatchdogResult(robotId, at, firmware, linearRaw, ROBOT_STOPPED, verdict.boundMs, verdict.driftM, verdict.speedMps,
                tr("Self-stop at most %.1f s after the last command, %.0f cm from the last moving position",
                    "Arrêt seul au plus tard %.1f s après la dernière commande, %.0f cm depuis la dernière position en mouvement").format(verdict.boundMs / 1000.0, verdict.driftM * 100))
            is SilenceVerdict.StillMoving -> MotorWatchdogResult(robotId, at, firmware, linearRaw, STILL_MOVING, null, verdict.driftM, null, verdict.reason)
            is SilenceVerdict.Inconclusive -> MotorWatchdogResult(robotId, at, firmware, linearRaw, INCONCLUSIVE, null, null, null, verdict.reason)
        }

        fun fromJson(text: String): MotorWatchdogResult? = runCatching {
            val j = JSONObject(text)
            fun long(key: String) = if (j.has(key) && !j.isNull(key)) j.getLong(key) else null
            fun double(key: String) = if (j.has(key) && !j.isNull(key)) j.getDouble(key) else null
            MotorWatchdogResult(j.getString("robotId"), j.getLong("at"), j.optString("firmware").takeIf { j.has("firmware") && !j.isNull("firmware") },
                j.getInt("linearRaw"), j.getString("outcome").also { require(it in setOf(ROBOT_STOPPED, STILL_MOVING, INCONCLUSIVE)) },
                long("boundMs"), double("driftM"), double("speedMps"), j.getString("detail"))
        }.getOrNull()
    }
}

object SilenceClearance {
    /**
     * Refusal if the [CommandSilenceProbe.CLEARANCE_M] segment in front of the robot leaves a zone,
     * crosses a no-go zone or approaches a local obstacle: the robot may keep going without commands.
     */
    fun failure(map: RobotMap, obstacles: List<LocalObstacle>, position: Point, headingDegrees: Double, halfWidthM: Double): String? {
        if (!map.complete) return tr("Robot map required", "Carte du robot requise")
        if (!headingDegrees.isFinite()) return tr("Heading unavailable", "Cap indisponible")
        val rad = Math.toRadians(headingDegrees)
        val samples = (0..25).map { i -> val d = CommandSilenceProbe.CLEARANCE_M * i / 25
            Point(position.x + d * kotlin.math.cos(rad), position.y + d * kotlin.math.sin(rad)) }
        val end = samples.last()
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val zone = zones.firstOrNull { Geometry.inside(position, it.points) } ?: return tr("Robot outside the map zones", "Robot hors des zones de la carte")
        if (samples.any { !Geometry.inside(it, zone.points) }) return tr("Less than %.1f m clear in front of the robot in the zone", "Moins de %.1f m dégagés devant le robot dans la zone").format(CommandSilenceProbe.CLEARANCE_M)
        if (samples.any { p -> edgeDistance(p, zone) < halfWidthM }) return tr("Zone edge too close in front of the robot", "Bord de zone trop proche devant le robot")
        map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }.forEach { nogo ->
            if (samples.any { Geometry.inside(it, nogo.points) || edgeDistance(it, nogo) < halfWidthM }) return tr("No-go zone in front of the robot", "Zone interdite devant le robot")
        }
        obstacles.forEach { o ->
            if (Geometry.distance(Point(o.x, o.y), position, end) < o.exclusionRadiusM + halfWidthM) return tr("Local obstacle in front of the robot", "Obstacle local devant le robot")
        }
        return null
    }
    private fun edgeDistance(p: Point, shape: MapShape): Double =
        shape.points.indices.minOf { i -> Geometry.distance(p, shape.points[i], shape.points[(i + 1) % shape.points.size]) }
}
