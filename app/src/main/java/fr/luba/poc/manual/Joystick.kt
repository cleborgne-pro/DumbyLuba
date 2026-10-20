package fr.luba.poc.manual

import fr.luba.poc.protocol.Commands
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

object Joystick {
    /** Dead zone around the centre of an axis, as a share of its travel. */
    const val DEAD_ZONE = 0.12f
    /** Exponent of the response curve beyond the dead zone: fine control at low speed, full speed at the end stop. */
    const val EXPO = 1.6

    /**
     * Response of one axis: zero in the dead zone, then progressive from zero (no jump when leaving the dead zone) up to
     * ±1 at the end stop. Values beyond ±1 are clamped.
     */
    fun response(value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        val magnitude = abs(clamped)
        if (magnitude <= DEAD_ZONE) return 0f
        val travel = ((magnitude - DEAD_ZONE) / (1f - DEAD_ZONE)).toDouble()
        return (sign(clamped) * travel.pow(EXPO)).toFloat()
    }

    /** Speed as a percentage of the protocol bounds: 100 % = 1000 linear / 450 angular (ceiling of the official rocker).
     * Separate axes, no diagonal normalisation: turning does not reduce forward speed. Each axis goes through [response]. */
    fun dualCommand(forward: Float, turn: Float, speed: Int): Pair<Int, Int> {
        require(forward.isFinite() && turn.isFinite() && speed in 10..100)
        return (-response(forward) * speed / 100f * Commands.JOYSTICK_MAX_LINEAR).roundToInt() to
            (-response(turn) * speed / 100f * Commands.JOYSTICK_MAX_ANGULAR).roundToInt()
    }

    /** Estimated forward speed (≈ 1 mm/s per unit, measured up to 500 units). */
    fun estimatedLinearMps(speed: Int): Double = speed / 100.0 * Commands.JOYSTICK_MAX_LINEAR / 1000.0

    /** Screen coordinates: up = forward, right = clockwise rotation (decreasing local heading). */
    fun command(x: Float, y: Float, speed: Int): Pair<Int, Int> {
        require(x.isFinite() && y.isFinite() && speed in 10..100)
        val length = hypot(x, y)
        if (length <= DEAD_ZONE) return 0 to 0
        val scale = maxOf(1f, length)
        return (-y / scale * speed / 100f * Commands.JOYSTICK_MAX_LINEAR).roundToInt() to
            (-x / scale * speed / 100f * Commands.JOYSTICK_MAX_ANGULAR).roundToInt()
    }

    /**
     * Haptic notch of an axis position: 0 in the dead zone, then 1 to 3 by third of the travel beyond it, 4 at the
     * end stop. A tick is felt each time the notch changes, so that the speed can be dosed without looking at the screen.
     */
    fun notch(value: Float): Int {
        val magnitude = abs(value.coerceIn(-1f, 1f))
        if (magnitude <= DEAD_ZONE) return 0
        if (magnitude >= 0.98f) return 4
        val travel = (magnitude - DEAD_ZONE) / (1f - DEAD_ZONE)
        return (1 + (travel * 3f).toInt()).coerceAtMost(3)
    }
}
