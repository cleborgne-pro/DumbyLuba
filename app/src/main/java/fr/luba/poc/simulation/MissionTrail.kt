package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Actual robot trail during a route ([RobotRouteUi.trail]): kept for the whole mowing, recharges and resumes included
 * (request of 2026-10-15: on resume, the route history was lost).
 *
 * A point is added as soon as the robot has moved more than [MIN_STEP_M]. Beyond [MAX_POINTS], the old part is
 * simplified (Douglas-Peucker, [TOLERANCE_M]): a straight stripe shrinks to its ends, so the trail of a mowing of
 * several hours fits in memory without losing its beginning. The last [RAW_TAIL] points stay raw: back-offs and
 * obstacle escapes consult the recent trail.
 *
 * Break ([BREAK], non-numeric point): two consecutive points more than [GAP_M] apart are not connected. The robot was
 * moved without being tracked (return through "Recharge", mowing resumed from the base with the kept trail of the
 * Fosse): before, a straight line connected the stop point to the base across the zones (request of 2026-10-15). The
 * drawing and back-offs only connect points of the same piece ([pieces], [links]).
 */
object MissionTrail {
    const val MIN_STEP_M = 0.03
    const val MAX_POINTS = 12_000
    const val RAW_TAIL = 2_000
    const val TOLERANCE_M = 0.02
    /** Jump between two recorded positions beyond which the trail is broken (positions at 1 Hz: 0.6 m at most while moving). */
    const val GAP_M = 3.0
    /** Trail break marker. */
    val BREAK = Point(Double.NaN, Double.NaN)

    fun isBreak(p: Point): Boolean = p.x.isNaN() && p.y.isNaN()

    fun append(trail: List<Point>, p: Point): List<Point> = merge(trail, listOf(p))

    fun merge(trail: List<Point>, points: List<Point>): List<Point> {
        if (points.isEmpty()) return trail
        val merged = trail.toMutableList()
        val before = merged.size
        points.forEach { p ->
            if (!p.x.isFinite() || !p.y.isFinite()) return@forEach
            val last = merged.lastOrNull()?.takeUnless(::isBreak)
            if (last != null && last.distance(p) <= MIN_STEP_M) return@forEach
            if (last != null && last.distance(p) > GAP_M) merged += BREAK
            merged += p
        }
        return if (merged.size == before) trail else compact(merged)
    }

    /** Continuous pieces of the trail (breaks removed). */
    fun pieces(trail: List<Point>): List<List<Point>> {
        val out = mutableListOf<List<Point>>()
        var current = mutableListOf<Point>()
        trail.forEach { p -> if (isBreak(p)) { if (current.isNotEmpty()) out += current; current = mutableListOf() } else current += p }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Segments actually travelled: consecutive pairs of the same piece. */
    fun links(trail: List<Point>): List<Pair<Point, Point>> = trail.zipWithNext().filter { (a, b) -> !isBreak(a) && !isBreak(b) }

    /** Distance from [p] to the trail (travelled segments, or isolated point), infinite if empty. */
    fun distance(p: Point, trail: List<Point>): Double {
        val links = links(trail)
        if (links.isNotEmpty()) return links.minOf { (a, b) -> Geometry.distance(p, a, b) }
        return trail.filterNot(::isBreak).minOfOrNull { it.distance(p) } ?: Double.POSITIVE_INFINITY
    }

    /** Trail bounded to [MAX_POINTS]: old part simplified, then tolerance doubled as long as needed. */
    fun compact(trail: List<Point>): List<Point> {
        if (trail.size <= MAX_POINTS) return trail
        val tail = trail.takeLast(RAW_TAIL)
        val head = trail.subList(0, trail.size - RAW_TAIL + 1)   // last point of the head = first of the tail
        var tolerance = TOLERANCE_M
        var simplified = simplifyPieces(head, tolerance)
        while (simplified.size + RAW_TAIL > MAX_POINTS * 3 / 4 && tolerance < 5.0) {
            tolerance *= 2
            simplified = simplifyPieces(head, tolerance)
        }
        return simplified.dropLast(1) + tail
    }

    /** Piece-by-piece simplification: breaks and ends kept in place. */
    private fun simplifyPieces(points: List<Point>, tolerance: Double): List<Point> {
        val out = ArrayList<Point>(points.size)
        var run = ArrayList<Point>()
        points.forEach { p ->
            if (isBreak(p)) { out += simplify(run, tolerance); run = ArrayList(); out += p } else run += p
        }
        out += simplify(run, tolerance)
        return out
    }

    /** Iterative Douglas-Peucker: ends kept, maximum gap [tolerance] from the original trail. */
    fun simplify(points: List<Point>, tolerance: Double): List<Point> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast().let { it[0] to it[1] }
            if (to - from < 2) continue
            var worst = -1; var distance = tolerance
            for (i in from + 1 until to) {
                val d = Geometry.distance(points[i], points[from], points[to])
                if (d > distance) { distance = d; worst = i }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(intArrayOf(from, worst)); stack.addLast(intArrayOf(worst, to))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/**
 * Trail of a mowing saved next to its resume point ([MissionCheckpointStore]): one file per robot, tied to the mowing
 * by its start date ([MissionCheckpoint.startedAt]). Atomic write; unreadable ⇒ ignored.
 */
class MissionTrailStore(private val directory: File) {
    private fun file(id: String) = File(directory, "mission-trail-" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin")

    @Synchronized fun save(id: String, startedAt: Long, trail: List<Point>) {
        val target = file(id)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { stream ->
            val out = DataOutputStream(stream.buffered())
            out.writeInt(MAGIC); out.writeLong(startedAt); out.writeInt(trail.size)
            trail.forEach { out.writeFloat(it.x.toFloat()); out.writeFloat(it.y.toFloat()) }
            out.flush(); stream.fd.sync()
        }
        check(tmp.renameTo(target)) { tr("Failed to save the mowing trail", "Échec sauvegarde de la trace de tonte") }
    }

    /** Trail of the mowing started at [startedAt], empty if missing, from another mowing or unreadable. */
    fun read(id: String, startedAt: Long): List<Point> = runCatching {
        val f = file(id)
        if (!f.exists()) return emptyList()
        require(f.length() in 16..(16L + 8L * MissionTrail.MAX_POINTS * 2))
        DataInputStream(f.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC)
            if (input.readLong() != startedAt) return emptyList()
            val count = input.readInt()
            require(count in 0..MissionTrail.MAX_POINTS * 2)
            List(count) { Point(input.readFloat().toDouble(), input.readFloat().toDouble()).also { require(MissionTrail.isBreak(it) || (it.x.isFinite() && it.y.isFinite())) } }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun delete(id: String) { file(id).delete() }

    private companion object { const val MAGIC = 0x4C545231 }   // « LTR1 »
}
