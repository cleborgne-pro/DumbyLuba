package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Heading hold during a straight back-off. The reverse is commanded with a zero angular speed, but on a slippery
 * slope one wheel slips and the robot yaws: on 2026-10-19 at 14:02 the heading went from 90° to 55° over a 0.8 m
 * back-off after a contact, the robot drifted 14 cm sideways towards the border of Fosse and the mowing stopped with
 * "Back-off deviation >12 cm". The robot never steers while reversing (the steering sign in reverse has not been
 * measured): when the heading error exceeds [ENTER_DEGREES], the reverse stops and the robot pivots in place
 * (verified convention: positive angular = heading increases) back to the wanted heading, then reverses again.
 *
 * The wanted heading is the back-off heading tilted towards the back-off line, so that the reverse brings the robot
 * back onto the line instead of only running parallel to it. Headings are compared through the change of the
 * reported heading since the start ([current]): no compass offset involved.
 */
object BackOffSteering {
    /** Heading error starting a pivot, and error at which it ends (hysteresis: the heading is reported at 1 Hz). */
    const val ENTER_DEGREES = 8.0
    const val EXIT_DEGREES = 3.0
    /** Distance over which the lateral offset is corrected, and maximum tilt towards the line. */
    const val LATERAL_LOOKAHEAD_M = 0.4
    const val MAX_TILT_DEGREES = 20.0
    /** No pivot in the first centimetres: the robot first moves away from the object it hit. */
    const val FREE_START_M = 0.10
    /** A pivot that does not converge is abandoned after this time (the reverse continues, deviation still checked). */
    const val PIVOT_TIMEOUT_MS = 4_000L
    /** After an abandoned pivot, reverse at least this far before the next one; at most [MAX_PIVOTS] per back-off. */
    const val RETRY_AFTER_M = 0.15
    const val MAX_PIVOTS = 6
    /** Pivot speed: gain (fraction of the error per second), bounds in protocol units. */
    const val PIVOT_GAIN = 0.5
    const val MIN_PIVOT = 35
    const val MAX_PIVOT = 120

    /** Signed offset (m) of [position] from the line [from] → [target], positive on the left of the travel direction. */
    fun offset(from: Point, target: Point, position: Point): Double {
        val length = from.distance(target)
        if (length < 1e-9) return 0.0
        val ux = (target.x - from.x) / length; val uy = (target.y - from.y) / length
        return (position.x - from.x) * -uy + (position.y - from.y) * ux
    }

    /**
     * Wanted body heading (degrees): the back-off [heading], tilted so that the reverse goes back towards the line.
     * Robot on the left of the travel direction: the travel direction (and therefore the body) turns clockwise.
     */
    fun wanted(heading: Double, from: Point, target: Point, position: Point): Double {
        val tilt = Math.toDegrees(atan2(offset(from, target, position), LATERAL_LOOKAHEAD_M)).coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES)
        return wrap360(heading - tilt)
    }

    /** Current body heading: back-off heading plus the change of the reported heading since the start. */
    fun current(heading: Double, reportedAtStart: Double, reported: Double): Double = wrap360(heading + wrap180(reported - reportedAtStart))

    /** Signed error (degrees, -180..180): positive = the heading must increase. */
    fun error(wanted: Double, current: Double): Double = wrap180(wanted - current)

    /** Pivot to start or continue: [pivoting] gives the hysteresis. */
    fun pivotNeeded(error: Double, pivoting: Boolean): Boolean = abs(error) > if (pivoting) EXIT_DEGREES else ENTER_DEGREES

    /** In-place angular command for [error] (positive increases the heading), [degreesPerUnit] from the calibration. */
    fun pivotAngular(error: Double, degreesPerUnit: Double): Int {
        require(degreesPerUnit > 0)
        val magnitude = (abs(error) * PIVOT_GAIN / degreesPerUnit).roundToInt().coerceIn(MIN_PIVOT, MAX_PIVOT)
        return (sign(error) * magnitude).toInt()
    }

    fun wrap180(degrees: Double): Double = ((degrees + 540) % 360 + 360) % 360 - 180
    private fun wrap360(degrees: Double): Double = (degrees % 360 + 360) % 360
}
