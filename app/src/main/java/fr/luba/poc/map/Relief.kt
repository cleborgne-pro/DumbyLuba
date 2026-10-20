package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acceleration measured by the phone mounted on the robot (m/s², Android frame: x to the right of the screen, y to the
 * top of the screen, z out of the screen). With the robot still or slow, this is the local vertical: the phone tilted
 * with the robot gives the slope of the ground under the robot.
 */
data class PhoneGravity(val x: Double, val y: Double, val z: Double) {
    val norm get() = sqrt(x * x + y * y + z * z)
}

/**
 * Orientation of the phone lying flat (screen to the sky) on the robot's cover. [angle]: angle between the top of the
 * phone and the front of the robot, counted to the left (degrees). [AUTO]: deduced from the robot's movements.
 */
enum class PhoneMount(private val en: String, private val fr: String, val angle: Double?) {
    AUTO("Automatic", "Automatique", null), CALIBRATED("Saved", "Enregistrée", null),
    TOP_FORWARD("Top towards the front", "Haut vers l'avant", 0.0), TOP_BACKWARD("Top towards the back", "Haut vers l'arrière", 180.0),
    TOP_LEFT("Top towards the left", "Haut vers la gauche", 90.0), TOP_RIGHT("Top towards the right", "Haut vers la droite", 270.0);
    /** Display label in the interface language. */
    val label: String get() = tr(en, fr)

    /**
     * Ground slopes in the robot frame: (rise towards the front, rise towards the right), as a tangent (0.10 = 10 %).
     * Null if the reading is not that of a flat, almost still phone.
     */
    fun robotSlopes(g: PhoneGravity): Pair<Double, Double>? = PhoneTilt.robotSlopes(g, angle ?: 0.0)

    /** Inverse of [robotSlopes]: reading the phone would have on a given slope (simulation and tests). */
    fun gravity(slopeForward: Double, slopeRight: Double): PhoneGravity = PhoneTilt.gravity(slopeForward, slopeRight, angle ?: 0.0)

    companion object {
        const val G = 9.80665
        /** cos 40°: beyond this, the phone is no longer flat (or the robot would be on an impossible slope). */
        const val MIN_FLATNESS = 0.766
    }
}

/** Phone frame ↔ robot frame for a phone turned by [angle] degrees (top of the phone towards the left). */
object PhoneTilt {
    /** Front and right of the robot expressed in the phone plane (x, y). */
    fun axes(angle: Double): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val a = Math.toRadians(angle)
        return (sin(a) to cos(a)) to (cos(a) to -sin(a))
    }
    fun robotSlopes(g: PhoneGravity, angle: Double): Pair<Double, Double>? {
        val n = g.norm
        if (!n.isFinite() || n !in 7.5..12.0 || g.z / n < PhoneMount.MIN_FLATNESS) return null
        val (f, r) = axes(angle)
        return (g.x * f.first + g.y * f.second) / g.z to (g.x * r.first + g.y * r.second) / g.z
    }
    fun gravity(slopeForward: Double, slopeRight: Double, angle: Double): PhoneGravity {
        val z = PhoneMount.G / sqrt(1 + slopeForward * slopeForward + slopeRight * slopeRight)
        val fw = slopeForward * z; val rt = slopeRight * z
        val (f, r) = axes(angle)
        return PhoneGravity(fw * f.first + rt * r.first, fw * f.second + rt * r.second, z)
    }
    /** Angle brought into [0, 360). */
    fun normalize(angle: Double) = ((angle % 360) + 360) % 360
}

/** How the phone orientation was obtained. */
enum class MountSource(private val en: String, private val fr: String) {
    MANUAL("chosen by hand", "choisie à la main"), CALIBRATED("saved on flat ground", "enregistrée sur sol plat"),
    MOTION("deduced from the robot's starts and stops", "déduite des démarrages et arrêts du robot"),
    TERRAIN("deduced from terrain consistency, front/back assumed", "déduite de la cohérence du relief, avant/arrière supposé"),
    DEFAULT("not determined, top towards the front assumed", "non déterminée, haut vers l'avant supposé");
    /** Display label in the interface language. */
    val label: String get() = tr(en, fr)
}

data class MountEstimate(val angle: Double, val source: MountSource, val events: Int = 0)

/**
 * Reference taken with the robot still on flat, level ground: the vertical seen by the phone becomes the tilt zero
 * (phone not quite flat, tilted holder, chassis) and [angle], if known, the default orientation of the phone on the
 * robot (degrees, top of the phone towards the left of the front).
 */
data class TiltCalibration(val reference: PhoneGravity, val angle: Double?, val at: Long) {
    /** Phone tilt at the time of the reference, in degrees. */
    val tiltDegrees get() = Math.toDegrees(kotlin.math.acos((reference.z / reference.norm).coerceIn(-1.0, 1.0)))

    /** Corrected reading: rotation that brings the reference back to the phone vertical (Rodrigues' formula). */
    fun level(g: PhoneGravity): PhoneGravity {
        val n = reference.norm
        val ux = reference.x / n; val uy = reference.y / n; val uz = reference.z / n
        // Axis u × z = (uy, −ux, 0), sin θ = |axis|, cos θ = uz.
        val sinT = hypot(ux, uy)
        if (sinT < 1e-9) return g
        val kx = uy / sinT; val ky = -ux / sinT
        val cosT = uz
        val dot = kx * g.x + ky * g.y
        // k × g with k = (kx, ky, 0).
        val cx = ky * g.z; val cy = -kx * g.z; val cz = kx * g.y - ky * g.x
        return PhoneGravity(g.x * cosT + cx * sinT + kx * dot * (1 - cosT),
            g.y * cosT + cy * sinT + ky * dot * (1 - cosT),
            g.z * cosT + cz * sinT)
    }

    companion object {
        const val MIN_READINGS = 5
        /** Spread tolerated between readings during the capture: beyond it, the robot or the phone is moving. */
        const val MAX_SPREAD = 0.15

        /** Reference from the readings received over a few seconds, or the reason for the refusal. */
        fun capture(readings: List<PhoneGravity>, angle: Double?, at: Long): Pair<TiltCalibration?, String?> {
            if (readings.size < MIN_READINGS) return null to tr("Not enough readings received from the gateway (${readings.size}/$MIN_READINGS): check that this phone is controlling through the gateway phone mounted on the robot.", "Pas assez de mesures reçues de la passerelle (${readings.size}/$MIN_READINGS) : vérifier que ce téléphone pilote via le téléphone passerelle posé sur le robot.")
            val mean = PhoneGravity(readings.sumOf { it.x } / readings.size, readings.sumOf { it.y } / readings.size, readings.sumOf { it.z } / readings.size)
            val spread = readings.maxOf { sqrt((it.x - mean.x).let { d -> d * d } + (it.y - mean.y).let { d -> d * d } + (it.z - mean.z).let { d -> d * d }) }
            if (spread > MAX_SPREAD) return null to tr("Unstable readings (%.2f m/s²): robot or phone moving. Try again with the robot stopped.", "Mesures instables (%.2f m/s²) : robot ou téléphone en mouvement. Recommencer robot arrêté.").format(spread)
            if (mean.norm !in 8.8..10.8) return null to tr("Inconsistent reading (%.1f m/s²): try again.", "Mesure incohérente (%.1f m/s²) : recommencer.").format(mean.norm)
            if (mean.z / mean.norm < 0.866) return null to tr("Phone tilted by more than 30°: lay it flat, screen facing the sky.", "Téléphone incliné de plus de 30° : le poser à plat, écran vers le ciel.")
            return TiltCalibration(mean, angle?.let(PhoneTilt::normalize), at) to null
        }
    }
}

data class ReliefResult(
    /** Mean local slope per 1 m cell, in %. */
    val slopeCells: List<SignalCell>,
    /** Relative height per 1 m cell, in m above the lowest measured cell. */
    val heightCells: List<SignalCell>,
    /** Height difference between the lowest and the highest cell, in m. */
    val dropM: Double,
    /** Phone mounting offset removed from the readings (front/right tangents, robot frame). */
    val offsetForward: Double, val offsetRight: Double, val offsetEstimated: Boolean,
    /** Tilt readings used. */
    val used: Int,
    /** Pieces of ground not linked by neighbouring cells: their relative heights are independent. */
    val components: Int,
    /**
     * Terrain shading per cell (key: integer corner of the cell): 1 when flat, darker on slopes facing away from the light
     * (north-west of the map), lighter facing it. Overlaid on a signal color, it shows both at once.
     */
    val shades: Map<Pair<Int, Int>, Float> = emptyMap(),
    /** Phone orientation used (degrees, top of the phone towards the left of the front) and its origin. */
    val mount: MountEstimate = MountEstimate(0.0, MountSource.MANUAL),
    /** Mean gap (m) between the measured slopes and the rebuilt terrain: small if the slopes form a real terrain. */
    val residual: Double = 0.0,
    /** Readings corrected by a reference taken on flat ground: known mounting offset, not estimated. */
    val calibrated: Boolean = false,
) {
    fun shadeAt(p: Point): Float? = shades[floor(p.x).toInt() to floor(p.y).toInt()]
}

/**
 * Terrain rebuilt from the slopes: each reading gives the ground gradient under the robot (phone tilt, robot heading).
 * The mean gradients per 1 m cell are integrated by least squares over the neighbouring measured cells: this gives a
 * relative height, without absolute altitude or extrapolation outside the driven cells.
 */
object ReliefModel {
    private const val HEADING_BINS = 8
    private const val MIN_BIN_SAMPLES = 3

    private class Measure(val point: Point, val heading: Double, val forward: Double, val right: Double)

    fun result(samples: List<SignalSample>, mount: PhoneMount, calibration: TiltCalibration? = null): ReliefResult? {
        // Reference on flat ground: all readings are corrected before the orientation and the slopes.
        val leveled = if (calibration == null) samples else samples.map { s -> s.gravity?.let { s.copy(gravity = calibration.level(it)) } ?: s }
        val fixed = mount.angle
        val saved = calibration?.angle?.takeIf { mount == PhoneMount.CALIBRATED }
        val estimate = when {
            saved != null -> MountEstimate(saved, MountSource.CALIBRATED)
            fixed != null -> MountEstimate(fixed, MountSource.MANUAL)
            else -> MountEstimator.estimate(leveled) ?: return null
        }
        return result(leveled, estimate, calibration != null)
    }

    fun result(samples: List<SignalSample>, mount: MountEstimate, calibrated: Boolean = false): ReliefResult? {
        val tilted = samples.filter { it.hasTilt }.sortedBy { it.at }
        // Starts and braking: the felt thrust would read as a slope, these readings are discarded.
        val skip = MountEstimator.transients(tilted)
        val measures = tilted.mapIndexedNotNull { i, s ->
            if (i in skip) return@mapIndexedNotNull null
            val (f, r) = PhoneTilt.robotSlopes(requireNotNull(s.gravity), mount.angle) ?: return@mapIndexedNotNull null
            Measure(s.point, Math.toRadians(requireNotNull(s.heading)), f, r)
        }
        if (measures.isEmpty()) return null
        val (of, or, estimated) = if (calibrated) Triple(0.0, 0.0, false) else offset(measures)
        // Ground gradient in the map frame: front = (cos h, sin h), right = (sin h, −cos h).
        val grads = measures.groupBy { floor(it.point.x).toInt() to floor(it.point.y).toInt() }.mapValues { (_, list) ->
            var gx = 0.0; var gy = 0.0
            list.forEach { m ->
                val f = m.forward - of; val r = m.right - or
                gx += cos(m.heading) * f + sin(m.heading) * r
                gy += sin(m.heading) * f - cos(m.heading) * r
            }
            Triple(gx / list.size, gy / list.size, list.size)
        }
        val keys = grads.keys.toList()
        val index = keys.withIndex().associate { it.value to it.index }
        val heights = DoubleArray(keys.size)
        val (components, residual) = integrate(keys, index, grads, heights)
        val low = heights.minOrNull() ?: 0.0
        val drop = (heights.maxOrNull() ?: 0.0) - low
        val slope = keys.map { k -> val g = requireNotNull(grads[k])
            SignalCell(Point(k.first + 0.5, k.second + 0.5), hypot(g.first, g.second) * 100, g.third) }
        val height = keys.mapIndexed { i, k ->
            val v = heights[i] - low
            SignalCell(Point(k.first + 0.5, k.second + 0.5), v, requireNotNull(grads[k]).third, if (drop > 1e-6) (v / drop).toFloat() else 0.5f)
        }
        val shades = grads.mapValues { (_, g) -> hillshade(g.first, g.second) }
        return ReliefResult(slope, height, drop, of, or, estimated, measures.size, components, shades, mount, residual, calibrated)
    }

    /**
     * Mounting offset (phone not perfectly flat, chassis): it turns with the robot whereas the ground slope changes sign
     * when the robot makes a U-turn. Mean of opposite heading sectors; without a round trip, nothing is removed.
     */
    private fun offset(measures: List<Measure>): Triple<Double, Double, Boolean> {
        val bins = measures.groupBy { m ->
            val deg = (Math.toDegrees(m.heading) % 360 + 360) % 360
            ((deg + 180.0 / HEADING_BINS) / (360.0 / HEADING_BINS)).toInt() % HEADING_BINS
        }.filterValues { it.size >= MIN_BIN_SAMPLES }
            .mapValues { (_, l) -> l.sumOf { it.forward } / l.size to l.sumOf { it.right } / l.size }
        val pairs = (0 until HEADING_BINS / 2).mapNotNull { b ->
            val a = bins[b] ?: return@mapNotNull null; val c = bins[b + HEADING_BINS / 2] ?: return@mapNotNull null
            (a.first + c.first) / 2 to (a.second + c.second) / 2
        }
        var of = if (pairs.isEmpty()) 0.0 else pairs.sumOf { it.first } / pairs.size
        var or = if (pairs.isEmpty()) 0.0 else pairs.sumOf { it.second } / pairs.size
        // Refinement: a cell crossed in different directions sees the same ground under different angles, which separates
        // the slope (fixed on the map) from the mounting offset (fixed on the robot). Alternating least squares.
        val crossed = measures.groupBy { floor(it.point.x).toInt() to floor(it.point.y).toInt() }.values.filter { cell ->
            cell.size >= 2 && cell.any { a -> cell.any { b -> abs(sin(a.heading - b.heading)) > 0.5 || cos(a.heading - b.heading) < -0.5 } }
        }
        if (crossed.size >= 3) {
            repeat(300) {
                var sf = 0.0; var sr = 0.0; var n = 0
                crossed.forEach { cell ->
                    var gx = 0.0; var gy = 0.0
                    cell.forEach { m ->
                        val f = m.forward - of; val r = m.right - or
                        gx += cos(m.heading) * f + sin(m.heading) * r; gy += sin(m.heading) * f - cos(m.heading) * r
                    }
                    gx /= cell.size; gy /= cell.size
                    cell.forEach { m ->
                        sf += m.forward - (gx * cos(m.heading) + gy * sin(m.heading))
                        sr += m.right - (gx * sin(m.heading) - gy * cos(m.heading)); n++
                    }
                }
                val nf = sf / n; val nr = sr / n
                val moved = abs(nf - of) + abs(nr - or)
                of = nf; or = nr
                if (moved < 1e-7) return Triple(of, or, true)
            }
            return Triple(of, or, true)
        }
        return Triple(of, or, pairs.isNotEmpty())
    }

    /**
     * Least squares: z_b − z_a ≈ mean gradient · (c_b − c_a) over the 8 neighbours, weight 1/distance². Conjugate gradient
     * on the Laplacian (slight regularization to fix the mean height of each piece). Returns the number of pieces.
     */
    private fun integrate(keys: List<Pair<Int, Int>>, index: Map<Pair<Int, Int>, Int>,
                          grads: Map<Pair<Int, Int>, Triple<Double, Double, Int>>, z: DoubleArray): Pair<Int, Double> {
        val n = keys.size
        val neighbours = Array(n) { i ->
            val (x, y) = keys[i]
            buildList {
                for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) index[x + dx to y + dy]?.let { j ->
                    val ga = requireNotNull(grads[keys[i]]); val gb = requireNotNull(grads[keys[j]])
                    // Expected height of i relative to j: mean gradient · (c_i − c_j).
                    val delta = (ga.first + gb.first) / 2 * -dx + (ga.second + gb.second) / 2 * -dy
                    add(Triple(j, 1.0 / (dx * dx + dy * dy), delta))
                }
            }
        }
        val eps = 1e-6
        val b = DoubleArray(n) { i -> neighbours[i].sumOf { (_, w, d) -> w * d } }
        fun apply(v: DoubleArray, out: DoubleArray) {
            for (i in 0 until n) { var s = eps * v[i]; for ((j, w, _) in neighbours[i]) s += w * (v[i] - v[j]); out[i] = s }
        }
        val r = b.copyOf(); val p = r.copyOf(); val ap = DoubleArray(n)
        var rr = r.sumOf { it * it }
        val tolerance = 1e-12 * maxOf(1.0, rr)
        var iteration = 0
        while (rr > tolerance && iteration < 4 * n + 50) {
            apply(p, ap)
            val alpha = rr / p.indices.sumOf { p[it] * ap[it] }
            for (i in 0 until n) { z[i] += alpha * p[i]; r[i] -= alpha * ap[i] }
            val next = r.sumOf { it * it }
            val beta = next / rr; rr = next
            for (i in 0 until n) p[i] = r[i] + beta * p[i]
            iteration++
        }
        // Linked pieces: each one centered on zero (relative heights between pieces unknown).
        val component = IntArray(n) { -1 }
        var count = 0
        for (start in 0 until n) if (component[start] < 0) {
            val stack = ArrayDeque(listOf(start)); component[start] = count
            val members = mutableListOf<Int>()
            while (stack.isNotEmpty()) {
                val i = stack.removeLast(); members += i
                neighbours[i].forEach { (j, _, _) -> if (component[j] < 0) { component[j] = count; stack.addLast(j) } }
            }
            val mean = members.sumOf { z[it] } / members.size
            members.forEach { z[it] -= mean }
            count++
        }
        // Residual gap: a slope field that is not that of a terrain (wrong orientation) does not integrate.
        var err = 0.0; var weight = 0.0
        for (i in 0 until n) for ((j, w, d) in neighbours[i]) { val e = z[i] - z[j] - d; err += w * e * e; weight += w }
        return count to if (weight > 0) sqrt(err / weight) else 0.0
    }

    /** Grazing light from the north-west (45° elevation), slopes exaggerated ×[Z_FACTOR] so that a lawn stays readable. */
    private const val Z_FACTOR = 4.0
    fun hillshade(gx: Double, gy: Double): Float {
        val nx = -gx * Z_FACTOR; val ny = -gy * Z_FACTOR
        val norm = sqrt(nx * nx + ny * ny + 1)
        val alt = Math.toRadians(45.0); val az = Math.toRadians(135.0)
        val light = (nx * cos(alt) * cos(az) + ny * cos(alt) * sin(az) + sin(alt)) / norm
        return (light / sin(alt)).toFloat().coerceIn(0.55f, 1.35f)
    }

    /** Deterministic fictional terrain (simulation only): gentle undulation of about ±50 cm. */
    fun syntheticHeight(p: Point): Double = 0.35 * sin(p.x * 0.21) + 0.25 * cos(p.y * 0.17 + 0.6) + 0.015 * p.x

    fun syntheticGradient(p: Point): Pair<Double, Double> =
        (0.35 * 0.21 * cos(p.x * 0.21) + 0.015) to (-0.25 * 0.17 * sin(p.y * 0.17 + 0.6))

    /** Phone reading on the fictional terrain, robot oriented along [headingDegrees] (mathematical angle). */
    fun syntheticGravity(p: Point, headingDegrees: Double, mount: PhoneMount = PhoneMount.TOP_FORWARD): PhoneGravity =
        syntheticGravity(p, headingDegrees, mount.angle ?: 0.0)

    fun syntheticGravity(p: Point, headingDegrees: Double, mountAngle: Double): PhoneGravity {
        val (gx, gy) = syntheticGradient(p)
        val h = Math.toRadians(headingDegrees)
        return PhoneTilt.gravity(gx * cos(h) + gy * sin(h), gx * sin(h) - gy * cos(h), mountAngle)
    }

    fun fresh(at: Long?, now: Long, maxAgeMs: Long = 2000) = at != null && abs(now - at) <= maxAgeMs
}

/** Last tilt received from the phone mounted on the robot (gateway). */
object RobotTilt {
    @Volatile private var latest: Pair<PhoneGravity, Long>? = null
    fun update(gravity: PhoneGravity, at: Long) { latest = gravity to at }
    fun clear() { latest = null }
    /** Reading less than 2 s old, otherwise null. */
    fun current(now: Long): PhoneGravity? = latest?.takeIf { ReliefModel.fresh(it.second, now) }?.first
}

/**
 * Orientation of the phone on the robot without any setting, from the robot's movements alone:
 * 1. starts and stops: at the start of a pass (after the pivot), the start thrust adds to the first readings (with the
 *    tilt delay) then disappears; when braking, the opposite. These offsets point towards the front of the robot in the
 *    phone plane, direction included;
 * 2. failing that, terrain consistency: with a wrong orientation, the slopes do not form a terrain (the integration
 *    leaves a gap). This fixes the axis, not front/back (an upside-down terrain is still a terrain): top towards the
 *    front assumed.
 */
object MountEstimator {
    private const val MIN_EVENTS = 6

    /** [agreement]: 1 if all starts and brakings point in the same direction. */
    data class Motion(val angle: Double, val events: Int, val agreement: Double)

    fun estimate(samples: List<SignalSample>): MountEstimate? {
        if (samples.none { it.hasTilt }) return null
        motion(samples)?.let { return MountEstimate(it.angle, MountSource.MOTION, it.events) }
        terrain(samples)?.let { return MountEstimate(it, MountSource.TERRAIN) }
        return MountEstimate(0.0, MountSource.DEFAULT)
    }

    /**
     * Readings at the start (and end) of a pass where the thrust may fall: the tilt arrives late relative to the position
     * (about 1 s smoothing on the gateway, sent every 0.5 s), so the start thrust may appear on the 2nd or 3rd reading
     * rather than the 1st.
     */
    private const val WINDOW = 3

    /**
     * Mean of the start and braking offsets; slopes, which change little over one meter, cancel out on average.
     * Each offset compares the first [WINDOW] readings of a pass with the next [WINDOW] (and the last ones with the
     * previous ones): the thrust shows there in the right direction whatever the tilt delay. Comparing only the 1st
     * reading with the 2nd flipped as soon as there was ~1 s of delay (thrust falling on the 2nd): 180° orientation,
     * inverted terrain.
     */
    fun motion(samples: List<SignalSample>): Motion? {
        val list = samples.filter { it.hasTilt }.sortedBy { it.at }
        // Sum of the offsets of readings [from] to the mean of readings [ref] (phone plane).
        fun push(from: List<Int>, ref: List<Int>): Pair<Double, Double> {
            val rx = ref.sumOf { requireNotNull(list[it].gravity).x } / ref.size
            val ry = ref.sumOf { requireNotNull(list[it].gravity).y } / ref.size
            return from.sumOf { requireNotNull(list[it].gravity).x - rx } to from.sumOf { requireNotNull(list[it].gravity).y - ry }
        }
        // Each start (and each braking seen) gives a reading offset pointing towards the front of the robot.
        val pushes = mutableListOf<Pair<Double, Double>>()
        passes(list).forEach { pass ->
            if (pass.size < 2 * WINDOW) return@forEach
            pushes += push(pass.take(WINDOW), pass.subList(WINDOW, 2 * WINDOW))
            // Braking: thrust towards the back on the last readings, so the previous ones point towards the front.
            if (braking(list, pass)) pushes += push(pass.subList(pass.size - 2 * WINDOW, pass.size - WINDOW), pass.takeLast(WINDOW))
        }
        val strong = pushes.filter { hypot(it.first, it.second) > 0.02 }
        if (strong.size < MIN_EVENTS) return null
        val sx = strong.sumOf { it.first }; val sy = strong.sumOf { it.second }
        // Consistency: the offsets must point in the same direction (mean length of the unit vectors).
        val agreement = hypot(strong.sumOf { it.first / hypot(it.first, it.second) }, strong.sumOf { it.second / hypot(it.first, it.second) }) / strong.size
        if (agreement < 0.5) return null
        return Motion(PhoneTilt.normalize(Math.toDegrees(kotlin.math.atan2(sx, sy))), strong.size, agreement)
    }

    /**
     * Straight passes of the survey (indices of the readings sorted by date) starting from standstill: each one follows a
     * break in the survey with a clear U-turn (pivot at the end of the previous pass), then close readings at the same
     * heading.
     */
    fun passes(list: List<SignalSample>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var current: MutableList<Int>? = null
        for (i in list.indices) {
            val p = list.getOrNull(i - 1)
            val turn = p?.let { abs(sin(Math.toRadians(requireNotNull(list[i].heading) - requireNotNull(it.heading)) / 2)) * 2 }
            val gap = p == null || (list[i].at - p.at) !in 400..1800
            when {
                p != null && gap && requireNotNull(turn) > 0.5 -> { current?.let { out += it }; current = mutableListOf(i) }
                gap || requireNotNull(turn) > 0.17 -> { current?.let { out += it }; current = null }
                else -> current?.add(i)
            }
        }
        current?.let { out += it }
        return out
    }

    /** Last interval clearly slower than the pass: the robot was braking at the last reading. */
    private fun braking(list: List<SignalSample>, pass: List<Int>): Boolean {
        fun speed(a: Int, b: Int) = list[a].point.distance(list[b].point) / ((list[b].at - list[a].at) / 1000.0)
        val cruise = pass.zipWithNext().dropLast(1).map { (a, b) -> speed(a, b) }.sorted().let { it.getOrNull(it.size / 2) } ?: return false
        return speed(pass[pass.size - 2], pass.last()) < 0.6 * cruise
    }

    /** Readings distorted by the thrust of a start or a braking, discarded from the terrain. */
    fun transients(list: List<SignalSample>): Set<Int> = passes(list).flatMap { pass ->
        // Thrust delayed like the tilt: it may affect the first (or last) [WINDOW] readings.
        pass.take(WINDOW) + (if (pass.size >= 2 * WINDOW && braking(list, pass)) pass.takeLast(WINDOW) else emptyList())
    }.toSet()

    /** Axis leaving the smallest integration gap; null if the terrain does not decide (flat, too few readings). */
    fun terrain(samples: List<SignalSample>): Double? {
        fun cost(angle: Double) = ReliefModel.result(samples, MountEstimate(angle, MountSource.TERRAIN))?.takeIf { it.heightCells.size >= 12 }?.residual
        val coarse = (0 until 36).mapNotNull { i -> cost(i * 5.0)?.let { i * 5.0 to it } }
        if (coarse.size < 36) return null
        val best = coarse.minBy { it.second }
        val fine = (-4..4).mapNotNull { d -> cost(best.first + d)?.let { best.first + d to it } }.minBy { it.second }
        val across = cost(fine.first + 90) ?: return null
        // Flat or almost flat ground: no orientation stands out (less than 5 mm of gap per meter).
        if (across < 0.005 || fine.second > 0.5 * across) return null
        // An upside-down terrain is still a terrain: keep the top of the phone towards the front.
        val a = PhoneTilt.normalize(fine.first)
        return if (a > 90 && a <= 270) PhoneTilt.normalize(a + 180) else a
    }
}
