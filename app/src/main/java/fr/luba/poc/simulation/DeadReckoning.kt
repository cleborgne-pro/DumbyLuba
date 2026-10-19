package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotPose
import kotlin.math.*

/**
 * Pose estimated between two RTK positions. The LUBA 2 (firmware 1.30) only publishes its position at 1 Hz, even
 * when 200 ms is requested: steering on the last received heading slows the pivot down far too early (survey of
 * 2026-10-10: 33 s for 125°). The commands sent since the last position are integrated (unicycle) with a gain
 * calibrated online, bounded, from the following positions.
 *
 * Command only: the safety checks (deviation, margins, envelope) are still made on the measured position. Initial
 * survey: ≈ 0.95 mm/s and ≈ 0.12 °/s per raw unit.
 */
class DeadReckoning(
    metersPerUnit: Double = DEFAULT_METERS_PER_UNIT,
    degreesPerUnit: Double = DEFAULT_DEGREES_PER_UNIT,
    private val horizonMs: Long = 1500,
    /** Command → effect delay to compensate in the prediction; 0 = published pose extended without anticipation. */
    private val commandDelayMs: Long = 0L,
    /** Calibration of this robot kept from a previous mission ([calibration]): bounded gains, calibrated rotation. */
    calibration: Calibration? = null,
    /**
     * Distance (m) between the rotation centre and the point published by the robot, located behind it on its axis
     * ([PUBLISHED_POINT_BEHIND_M] for the LUBA 2); 0: published point taken as the centre.
     */
    private val publishedBehindM: Double = 0.0,
) {
    /** Gains of a robot calibrated while moving, reusable at the start of the next mission. */
    data class Calibration(val metersPerUnit: Double, val degreesPerUnit: Double)

    companion object {
        const val DEFAULT_METERS_PER_UNIT = 0.00095
        const val DEFAULT_DEGREES_PER_UNIT = 0.12
        val METERS_RANGE = 0.0003..0.003
        val DEGREES_RANGE = 0.04..0.3
        /** Unexplained gap between two published headings: robot realignment. */
        const val JUMP_DEGREES = 45.0
        /** Minimum movement to measure a reliable travel direction (centimetre RTK). */
        const val COURSE_MIN_M = 0.08
        /** Confirmed heading realignment: full gain from this movement between two positions. */
        const val COURSE_FULL_M = 0.3
        const val COURSE_GAIN = 0.4
        /** Heading / travelled direction gap beyond which a second measurement is required. */
        const val LARGE_INNOVATION_DEGREES = 20.0
        /**
         * First heading confirmation: cumulative straight line (successive positions) at least this long.
         * Over 8–10 cm between two positions, 1 cm of RTK noise is enough to skew the direction by 30–40°:
         * execution bench of 2026-10-10 (Figuier, start): "confirmed" heading wrong by 37°, wrong pivot, 47 cm deviation.
         */
        const val CONFIRM_RUN_M = 0.15
        /** Obvious gap (published heading wrong after a stop, survey of 2026-10-10: 80–90°): adopted from the first measurement. */
        const val OBVIOUS_INNOVATION_DEGREES = 45.0
        /** Robot "at rest" in rotation: no rotation command above this threshold is acting any more. */
        const val REST_ANGULAR = 40
        /** Minimum commanded rotation (units × s, ≈ 35°) between two rests to calibrate the gain. */
        const val REST_CALIBRATION_UNITS = 300.0
        /** Forward speed while the heading is not confirmed by the motion (≈ 0.11 m/s). */
        const val UNCONFIRMED_LINEAR = 120
        /**
         * Navigation test: effective command → measured heading delay ≈ 0.5–0.8 s (test of 2026-10-10: heading
         * adjustment ≈ 0.5 s; ±30 cm zigzag at 100 % speed reproduced by a model at ≈ 0.7 s). Predicting the pose at
         * the moment the next command takes effect brings the deviation down to ≤ 25 cm on a striped course for delays
         * of 0.2 to 1.0 s, against 37–58 cm without compensation from 0.6 s. Not applied to mowing, whose behaviour is
         * validated separately.
         */
        const val NAV_COMMAND_DELAY_MS = 600L
        /** Actual mowing: same measured delay (debug mowing of 2026-10-10, 14:55: ≈ 0.6 s in pivot as in forward motion). */
        const val ROUTE_COMMAND_DELAY_MS = 600L
        /** Forward command unchanged for this duration before the measured interval: steady state. */
        const val STEADY_MS = 500L
        /** End of the LUBA 2 rotation after the last pivot command takes effect. */
        const val PIVOT_CONFIRM_MS = 200L
        /** Forward speed after a pivot while the heading is not measured (≈ 0.14 m/s). */
        const val POST_PIVOT_LINEAR = 150
        /**
         * Consecutive pivots predicted within [PIVOT_TRUST_DEGREES] (heading measured at the position confirming each
         * pivot): the heading predicted after a pivot is reliable for this robot, the slow advance [POST_PIVOT_LINEAR]
         * is no longer needed. A single larger error restores it.
         */
        const val PIVOT_TRUST_COUNT = 3
        const val PIVOT_TRUST_DEGREES = 6.0
        /**
         * The LUBA 2 publishes a point located 8 cm behind its rotation centre. Measured on the mowing of 2026-10-15
         * (212 pivots, Jardin d'Eden): −8.2 cm along the axis, +0.6 cm sideways, identical at the bottom, middle and
         * top of the zone (−8.0 / −7.8 / −7.7 cm). Each U-turn therefore moved the published position by about 16 cm;
         * on flat ground, the 80 to 105° pivots of 2026-10-14 moved it by 10 cm at most, which this offset alone
         * explains.
         */
        const val PUBLISHED_POINT_BEHIND_M = 0.08

        /**
         * LUBA 2 rotation centre from its published point ([PUBLISHED_POINT_BEHIND_M] ahead of it along the heading
         * axis); unknown heading: the published point. Display and trail: centred on the published point, the robot
         * seemed to turn around a point in front of it during a pivot, the rear sweeping as if it were skidding.
         */
        fun rotationCenter(published: Point, headingDegrees: Double?): Point {
            if (headingDegrees == null || !headingDegrees.isFinite()) return published
            val rad = Math.toRadians(headingDegrees)
            return Point(published.x + PUBLISHED_POINT_BEHIND_M * cos(rad), published.y + PUBLISHED_POINT_BEHIND_M * sin(rad))
        }
    }
    var metersPerUnit = (calibration?.metersPerUnit?.coerceIn(METERS_RANGE) ?: metersPerUnit); private set
    var degreesPerUnit = (calibration?.degreesPerUnit?.coerceIn(DEGREES_RANGE) ?: degreesPerUnit); private set

    private data class Sent(val at: Long, val linear: Int, val angular: Int)
    private val sent = ArrayDeque<Sent>()
    private var fixAt = Long.MIN_VALUE
    /** Last measured pose, at the rotation centre (origin of the prediction). */
    private var fix: RobotPose? = null
    /** Last published point as is: travelled direction and speed are measured on it. */
    private var fixPublished: Point? = null

    /**
     * Rotation centre measured at the last position ([fix]): published point moved forward by [publishedBehindM] along
     * the fused heading. Tracking, arrival and guards (deviation, slide, envelope, obstacles) check it: the published
     * point turns around it during a pivot while the robot stays in place.
     */
    val measured: Point? get() = fix?.position

    /**
     * Expected slide during an in-place pivot at a point (m/s, [SlideField.velocity]): added to the predicted pose for
     * pivot commands. Null: no slide expected.
     */
    var slide: ((Point) -> Point?)? = null

    /**
     * Last interval between two positions that was only an in-place rotation (no commanded advance, steady rotation,
     * robot heading not realigned): movement of the rotation centre and duration (s). Null otherwise.
     * Pivot slide measurement ([PivotSlideMeter], [SlideField]).
     */
    var rotationSlide: Pair<Point, Double>? = null
        private set

    /** Rotation centre matching the published point [published] on the fused heading [headingDegrees]. */
    fun center(published: Point, headingDegrees: Double): Point {
        if (publishedBehindM == 0.0) return published
        val rad = Math.toRadians(headingDegrees)
        return Point(published.x + publishedBehindM * cos(rad), published.y + publishedBehindM * sin(rad))
    }

    /**
     * Last in-place pivot not yet reflected by a measured position: the heading is only predicted.
     * Survey of 2026-10-10: pivot exit on a predicted heading 20–25° ahead of the actual heading, then a full-speed
     * diagonal start (23 cm deviation over a 0.8 m link). The caller advances slowly until the next position, which
     * corrects the heading.
     */
    fun pivotUnconfirmed(): Boolean {
        val last = sent.lastOrNull { it.linear == 0 && it.angular != 0 } ?: return false
        return fixAt < last.at + commandDelayMs + PIVOT_CONFIRM_MS
    }

    /** Predicted heading error (°) at the last positions that confirmed a pivot. */
    private val pivotErrors = ArrayDeque<Double>()

    /** The last [PIVOT_TRUST_COUNT] pivots were predicted within [PIVOT_TRUST_DEGREES]. */
    val pivotTrusted: Boolean
        get() = pivotErrors.size >= PIVOT_TRUST_COUNT && pivotErrors.all { it <= PIVOT_TRUST_DEGREES }

    /** Forward speed allowed after a pivot, before the position that measures the reached heading. */
    fun postPivotLinear(nominal: Int): Int =
        if (!pivotUnconfirmed() || pivotTrusted) nominal else minOf(nominal, POST_PIVOT_LINEAR)

    fun sent(at: Long, linear: Int, angular: Int) {
        sent.addLast(Sent(at, linear, angular))
        while (sent.size > 2 && sent[1].at < minOf(fixAt, at) - 5000) sent.removeFirst()
    }

    /** No notable commanded rotation acts on the robot at [at] (actual delays of 0.3 to 1.3 s). */
    private fun atRest(at: Long): Boolean =
        pieces(at - commandDelayMs - 700, at - commandDelayMs + 300).all { abs(it.third) <= REST_ANGULAR }

    /** Same commanded rotation (within 10 %) during the whole window [from, to). */
    private fun steadyRotation(from: Long, to: Long): Boolean {
        val all = pieces(from, to)
        if (all.isEmpty() || all.sumOf { it.first } < (to - from) / 1000.0 - 1e-6) return false
        val reference = all.first().third
        return all.all { abs(it.third - reference) <= abs(reference) / 10 }
    }

    /** Command in force at each instant of [from, to): (duration s, linear, angular). */
    private fun pieces(from: Long, to: Long): List<Triple<Double, Int, Int>> {
        if (to <= from) return emptyList()
        val out = mutableListOf<Triple<Double, Int, Int>>()
        var current = sent.lastOrNull { it.at <= from }
        var cursor = from
        for (command in sent) {
            if (command.at <= from) continue
            if (command.at >= to) break
            current?.let { out += Triple((command.at - cursor) / 1000.0, it.linear, it.angular) }
            current = command; cursor = command.at
        }
        current?.let { out += Triple((to - cursor) / 1000.0, it.linear, it.angular) }
        return out
    }

    /**
     * Gap between the heading published by the robot and the direction actually travelled (RTK).
     * Survey of 2026-10-10: after a stop, the LUBA 2 published 140° while driving at 50°, then realigned its heading
     * by itself (77° jump) after a few tens of centimetres.
     */
    var headingOffset = 0.0
        private set
    /** Heading confirmed by a measured straight movement since the last robot realignment. */
    var headingConfirmed = false
        private set
    private var reportedAtFix = Double.NaN
    /** Large heading / travelled direction gap awaiting confirmation (0 = none). */
    private var largeInnovation = 0.0
    /** Cumulative straight line since the last direction measurement, in the frame of the assumed heading (m). */
    private var runX = 0.0
    private var runY = 0.0
    /**
     * Commanded rotation (units × s) and measured rotation accumulated since the last "at rest" position.
     * Between two rests, the ratio depends neither on the actual delay nor on inertia: all the commanded rotation has
     * happened. Isolated intervals are skewed by them (end of pivot: small commands, residual rotation).
     */
    private var restUnits = 0.0
    private var restTurned = 0.0
    private var restStarted = false
    /** Rotation gain already calibrated on a complete or steady rotation of this robot (or previous mission). */
    var rotationCalibrated = calibration != null
        private set
    /** Gains to keep for the next mission, once the rotation is calibrated on this robot. */
    val calibration: Calibration? get() = if (rotationCalibrated) Calibration(metersPerUnit, degreesPerUnit) else null

    private fun wrap180(degrees: Double) = ((degrees + 540) % 360 + 360) % 360 - 180

    /** Fused heading: published heading corrected by the gap observed while moving. */
    fun heading(reported: Double): Double = ((reported + headingOffset) % 360 + 360) % 360

    /** New measured position: heading realigned on the trajectory, bounded calibration, new origin. */
    fun fix(position: Point, heading: Double, at: Long) {
        require(position.x.isFinite() && position.y.isFinite() && heading.isFinite())
        val previous = fixPublished
        val previousCenter = fix?.position
        val previousOffset = headingOffset
        val previousAt = fixAt
        var pureRotation = false
        if (previous != null && at > fixAt && reportedAtFix.isFinite()) {
            // The heading measured between two positions results from the commands sent [commandDelayMs] earlier.
            val parts = pieces(fixAt - commandDelayMs, at - commandDelayMs)
            val turnUnits = parts.sumOf { it.first * it.third }
            val linearUnits = parts.sumOf { it.first * it.second }
            val predictedTurn = turnUnits * degreesPerUnit
            val turned = wrap180(heading - reportedAtFix)
            // Position confirming a pivot: error of the predicted heading over the interval (end of pivot included).
            val confirming = pivotUnconfirmed() &&
                at >= (sent.lastOrNull { it.linear == 0 && it.angular != 0 }?.at ?: Long.MAX_VALUE) + commandDelayMs + PIVOT_CONFIRM_MS
            if (confirming) {
                pivotErrors.addLast(abs(wrap180(turned - predictedTurn)))
                while (pivotErrors.size > PIVOT_TRUST_COUNT) pivotErrors.removeFirst()
            }
            pureRotation = abs(linearUnits) < 10 && abs(turnUnits) >= 20
            if (abs(wrap180(turned - predictedTurn)) > JUMP_DEGREES) {
                pureRotation = false
                // The robot realigned its own heading: the old gap no longer holds, confirm again while driving.
                headingOffset = 0.0; headingConfirmed = false; largeInnovation = 0.0; runX = 0.0; runY = 0.0
                restUnits = 0.0; restTurned = 0.0; restStarted = false
            } else if (abs(turnUnits) >= 20 && abs(linearUnits) < 10 && turned * turnUnits > 0 &&
                    steadyRotation(fixAt - commandDelayMs - STEADY_MS, at - commandDelayMs))
                // Steady rotation only: at the end of a pivot, small commands coincide with the
                // residual rotation (actual delay, inertia); execution bench of 2026-10-10: gain estimated
                // 20 % too high, predicted heading ahead in curves, deviation over 30 cm at 100 %.
                degreesPerUnit = (0.7 * degreesPerUnit + 0.3 * abs(turned / turnUnits)).coerceIn(DEGREES_RANGE).also { rotationCalibrated = true }
            if (restStarted) { restUnits += turnUnits; restTurned += turned }
            if (atRest(at)) {
                if (restStarted && abs(restUnits) >= REST_CALIBRATION_UNITS && restTurned * restUnits > 0)
                    degreesPerUnit = (0.7 * degreesPerUnit + 0.3 * abs(restTurned / restUnits)).coerceIn(DEGREES_RANGE).also { rotationCalibrated = true }
                restUnits = 0.0; restTurned = 0.0; restStarted = true
            }
            val moved = position.distance(previous)
            val straight = linearUnits >= 40 && abs(predictedTurn) <= 12
            // Heading assumed during the interval (middle of the predicted rotation).
            val believed = heading + headingOffset - predictedTurn / 2
            if (!straight) { runX = 0.0; runY = 0.0 }
            else {
                // Chord of the cumulative straight line, expressed in the frame of the assumed heading: RTK noise only
                // affects its two ends. Also measured at low speed (less than 8 cm per second: execution bench of
                // 2026-10-10, heading wrong by 20° never realigned at 5 cm/s).
                val rad = Math.toRadians(believed)
                val dx = position.x - previous.x; val dy = position.y - previous.y
                runX += dx * cos(rad) + dy * sin(rad); runY += -dx * sin(rad) + dy * cos(rad)
                val chord = hypot(runX, runY)
                val innovation = Math.toDegrees(atan2(runY, runX))
                if (!headingConfirmed) {
                    // First confirmation: 15 cm of straight line, unless the gap is obvious.
                    if (chord >= COURSE_MIN_M && (abs(innovation) >= OBVIOUS_INNOVATION_DEGREES || chord >= CONFIRM_RUN_M)) {
                        headingOffset = wrap180(headingOffset + innovation)
                        headingConfirmed = true; largeInnovation = 0.0; runX = 0.0; runY = 0.0
                    }
                } else if (chord >= CONFIRM_RUN_M) {
                    // Heading already confirmed: a direction measured over 15 cm still carries RTK noise (≈ 5° for
                    // 1 cm; ≈ 10° over 8 cm: execution bench of 2026-10-10, two wrong measurements over 20° adopted
                    // at low speed in a curve). Correction weighted by the distance travelled; a large gap is only
                    // adopted at once if it is confirmed over two measurements (actual robot realignment).
                    // Debug mowing of 2026-10-10: a single gap > 20° adopted immediately skewed the heading by 20–30°
                    // and caused a pivot in the middle of a stripe.
                    val large = abs(innovation) > LARGE_INNOVATION_DEGREES
                    headingOffset = wrap180(headingOffset + when {
                        large && largeInnovation * innovation > 0 -> innovation
                        large -> 0.0
                        else -> COURSE_GAIN * min(1.0, chord / COURSE_FULL_M) * innovation
                    })
                    largeInnovation = if (large && largeInnovation * innovation <= 0) innovation else 0.0
                    runX = 0.0; runY = 0.0
                }
            }
            // Speed calibrated only in steady state: same commanded advance since [STEADY_MS]
            // before the interval. During an acceleration (robot delay and inertia), the robot
            // had hardly moved: the gain dropped by 30 % and the stop at the end of a stretch came too late.
            val window = (at - fixAt + STEADY_MS) / 1000.0
            val steady = pieces(fixAt - commandDelayMs - STEADY_MS, at - commandDelayMs).let { all ->
                all.sumOf { it.first } >= window - 1e-6 && all.all { abs(it.second - all.first().second) <= abs(all.first().second) / 10 } }
            if (abs(linearUnits) >= 40 && abs(turnUnits) < 10 && steady)
                metersPerUnit = (0.7 * metersPerUnit + 0.3 * moved / abs(linearUnits)).coerceIn(METERS_RANGE)
        }
        reportedAtFix = heading
        val fused = heading(heading)
        fixPublished = position
        val centre = center(position, fused)
        val seconds = (at - previousAt) / 1000.0
        // Pure rotation: the centre should not move; a heading realigned in between would skew the estimated centre.
        rotationSlide = if (pureRotation && previousCenter != null && seconds in 0.5..2.5 && abs(wrap180(headingOffset - previousOffset)) <= 3.0)
            Point(centre.x - previousCenter.x, centre.y - previousCenter.y) to seconds else null
        fix = RobotPose(centre, fused); fixAt = at
    }

    /**
     * Pose predicted at the moment a command sent at [now] takes effect ([commandDelayMs] later, 0 by default);
     * extrapolation limited to [horizonMs] after the last position. Commands already sent but not yet effective are
     * taken into account: only the part of each command acting before that moment is integrated.
     */
    fun predict(now: Long): RobotPose? {
        val origin = fix ?: return null
        var x = origin.position.x; var y = origin.position.y; var heading = origin.headingDegrees
        // In-place pivot on a slope: the centre slides during the rotation ([slide]).
        val sliding = slide?.invoke(origin.position)
        for ((seconds, linear, angular) in pieces(fixAt - commandDelayMs, minOf(now, fixAt + horizonMs))) {
            if (sliding != null && linear == 0 && angular != 0) { x += sliding.x * seconds; y += sliding.y * seconds }
            // Integration in 50 ms steps: arc accurate enough for a pivot with advance.
            val steps = max(1, (seconds / 0.05).roundToInt())
            val dt = seconds / steps
            repeat(steps) {
                heading += angular * degreesPerUnit * dt / 2
                val rad = Math.toRadians(heading)
                x += linear * metersPerUnit * dt * cos(rad); y += linear * metersPerUnit * dt * sin(rad)
                heading += angular * degreesPerUnit * dt / 2
            }
        }
        return RobotPose(Point(x, y), (heading % 360 + 360) % 360)
    }
}
