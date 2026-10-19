package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.DeadReckoning
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lift detection during a movement driven over BLE (joystick, route, tests).
 *
 * In manual control, the LUBA 2 firmware keeps executing speed commands when the robot is lifted:
 * no error is guaranteed. Three independent signals trigger the stop:
 * - a Mammotion lift or tilt error code ([LIFT_CODES]);
 * - a change of bits 3 to 8 of `sensor_status` (undocumented, next to the bumper) during motion;
 * - wheels commanded without measured progress: constant linear command, but the RTK position
 *   advances less than [ratio] times the expected distance over [windowMs] (wheels in the air or stuck).
 */
class LiftGuard(
    private val windowMs: Long = 2500,
    private val commandDelayMs: Long = DeadReckoning.ROUTE_COMMAND_DELAY_MS,
    private val minExpectedM: Double = 0.20,
    private val ratio: Double = 0.25,
    private val metersPerUnit: Double = DeadReckoning.DEFAULT_METERS_PER_UNIT,
    private val activeMs: Long = 1500,
) {
    private data class Command(val at: Long, val linear: Int)
    private data class Fix(val at: Long, val position: Point)

    private val commands = ArrayDeque<Command>()
    private val fixes = ArrayDeque<Fix>()
    private var safetyBaseline: Int? = null

    init { require(windowMs > 0 && commandDelayMs >= 0 && minExpectedM > 0 && ratio in 0.0..1.0 && metersPerUnit > 0) }

    fun reset() { commands.clear(); fixes.clear(); safetyBaseline = null; progressRatio = null; slipping = false }

    /**
     * Last ratio of measured distance / expected distance over the window (constant command), or null if it
     * cannot be measured. Below [ratio]: stop (lift or blockage).
     */
    var progressRatio: Double? = null
        private set
    /** Recent ratios: the robot's usual progress (the raw speed calibration is approximate). */
    private val ratios = ArrayDeque<Double>()

    /**
     * Slipping at the last position: progress above the stop threshold but below [SLIP_FRACTION] of the
     * usual progress (median of the last [SLIP_HISTORY] windows, at least [SLIP_MIN_SAMPLES]).
     * Reported only: no stop.
     */
    var slipping: Boolean = false
        private set

    /** Speed command sent to the robot (raw protocol convention). */
    fun commanded(linear: Int, at: Long) {
        commands.addLast(Command(at, linear))
        if (linear == 0 && !moving(at)) { fixes.clear(); safetyBaseline = null }
        trim(at)
    }

    /** A non-zero command was sent recently: the robot is supposed to be moving. */
    fun moving(now: Long): Boolean = commands.lastOrNull()?.let { it.linear != 0 && now - it.at <= activeMs } == true

    /** New measured position; returns the stop cause if the wheels turn without moving the robot forward. */
    fun position(position: Point, at: Long): String? {
        if (!position.x.isFinite() || !position.y.isFinite()) return null
        if (fixes.lastOrNull()?.at?.let { it >= at } == true) return null
        fixes.addLast(Fix(at, position))
        trim(at)
        progressRatio = null; slipping = false
        if (!moving(at)) return null
        val reference = fixes.lastOrNull { it.at <= at - windowMs } ?: return null
        val span = at - reference.at
        // Command of constant sign over the whole window, shifted by the command delay.
        val from = reference.at - commandDelayMs
        val to = at - commandDelayMs
        val before = commands.lastOrNull { it.at <= from } ?: return null
        val during = commands.filter { it.at in (from + 1)..to }
        val all = listOf(before) + during
        if (all.any { it.linear == 0 } || all.map { it.linear.sign }.distinct().size != 1) return null
        var expected = 0.0
        var cursor = from
        var speed = before.linear
        for (c in during) { expected += abs(speed) * metersPerUnit * (c.at - cursor) / 1000.0; cursor = c.at; speed = c.linear }
        expected += abs(speed) * metersPerUnit * (to - cursor) / 1000.0
        if (expected < minExpectedM) return null
        val observed = position.distance(reference.position)
        val progress = observed / expected
        progressRatio = progress
        if (ratios.size >= SLIP_MIN_SAMPLES) {
            val usual = ratios.sorted()[ratios.size / 2]
            slipping = progress >= ratio && progress < SLIP_FRACTION * usual
        }
        ratios.addLast(progress)
        while (ratios.size > SLIP_HISTORY) ratios.removeFirst()
        if (observed >= ratio * expected) return null
        return tr("Wheels commanded without progress: %.0f cm traveled for %.0f cm expected in %.1f s — robot lifted or stuck",
            "Roues commandées sans progression : %.0f cm parcourus pour %.0f cm attendus en %.1f s — robot soulevé ou bloqué")
            .format(observed * 100, expected * 100, span / 1000.0)
    }

    /** Undocumented safety bits of `sensor_status`: any change during motion stops the robot. */
    fun sensors(sensorStatus: Int, now: Long): String? {
        val bits = (sensorStatus shr 3) and 0x3F
        if (!moving(now)) { safetyBaseline = bits; return null }
        val baseline = safetyBaseline ?: run { safetyBaseline = bits; return null }
        if (bits == baseline) return null
        safetyBaseline = bits
        return tr("Safety sensor changed during motion (sensor_status bits 3-8: ${baseline.toString(2)} → ${bits.toString(2)})",
            "Capteur de sécurité changé pendant le mouvement (sensor_status bits 3-8 : ${baseline.toString(2)} → ${bits.toString(2)})")
    }

    private fun trim(now: Long) {
        val horizon = now - windowMs - commandDelayMs - 2000
        while (commands.size > 1 && commands[1].at <= horizon) commands.removeFirst()
        while (fixes.size > 1 && fixes[1].at <= horizon) fixes.removeFirst()
    }

    companion object {
        /** Mammotion lift or tilt codes (PyMammotion `error_codes.csv` table). */
        val LIFT_CODES = setOf(1004, 1030, 2502, 2801, 11131, 11133)
        /** Slipping: progress below this fraction of the usual progress. */
        const val SLIP_FRACTION = 0.55
        const val SLIP_HISTORY = 40
        const val SLIP_MIN_SAMPLES = 6
        fun liftCode(code: Int): Boolean = abs(code) in LIFT_CODES
        fun liftLabel(code: Int): String = when (abs(code)) {
            1004 -> tr("tilt sensor activated", "capteur d'inclinaison activé")
            1030 -> tr("front wheels lifted or tilted", "roues avant soulevées ou inclinées")
            2502 -> tr("robot tilted too much", "robot trop incliné")
            2801 -> tr("lift sensor triggered", "capteur de soulèvement déclenché")
            11131 -> tr("rear lift while reversing", "soulèvement arrière en marche arrière")
            11133 -> tr("rear lift while pivoting", "soulèvement arrière en pivot")
            else -> tr("lift", "soulèvement")
        }
    }
}
