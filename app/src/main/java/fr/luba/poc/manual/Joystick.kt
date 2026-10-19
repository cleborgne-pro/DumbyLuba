package fr.luba.poc.manual

import fr.luba.poc.protocol.Commands
import kotlin.math.hypot
import kotlin.math.roundToInt

object Joystick {
    /** Speed as a percentage of the protocol bounds: 100 % = 1000 linear / 450 angular (ceiling of the official rocker).
     * Separate axes, no diagonal normalisation: turning does not reduce forward speed. */
    fun dualCommand(forward: Float, turn: Float, speed: Int): Pair<Int, Int> {
        require(forward.isFinite() && turn.isFinite() && speed in 10..100)
        fun axis(value: Float) = if (kotlin.math.abs(value) <= 0.12f) 0f else value.coerceIn(-1f, 1f)
        return (-axis(forward) * speed / 100f * Commands.JOYSTICK_MAX_LINEAR).roundToInt() to
            (-axis(turn) * speed / 100f * Commands.JOYSTICK_MAX_ANGULAR).roundToInt()
    }

    /** Estimated forward speed (≈ 1 mm/s per unit, measured up to 500 units). */
    fun estimatedLinearMps(speed: Int): Double = speed / 100.0 * Commands.JOYSTICK_MAX_LINEAR / 1000.0

    /** Screen coordinates: up = forward, right = clockwise rotation (decreasing local heading). */
    fun command(x: Float, y: Float, speed: Int): Pair<Int, Int> {
        require(x.isFinite() && y.isFinite() && speed in 10..100)
        val length = hypot(x, y)
        if (length <= 0.12f) return 0 to 0
        val scale = maxOf(1f, length)
        return (-y / scale * speed / 100f * Commands.JOYSTICK_MAX_LINEAR).roundToInt() to
            (-x / scale * speed / 100f * Commands.JOYSTICK_MAX_ANGULAR).roundToInt()
    }
}
