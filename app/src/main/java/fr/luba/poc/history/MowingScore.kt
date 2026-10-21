package fr.luba.poc.history

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Part of the Mowing Score, with the points it can take off at most. */
enum class ScoreFactor(private val labelEn: String, private val labelFr: String, val maxPenalty: Double) {
    COVERAGE("Coverage", "Couverture", 40.0),
    OVERLAP("Overlaps", "Chevauchements", 15.0),
    ROTATIONS("Rotations", "Rotations", 15.0),
    REVERSING("Reversing", "Marches arrière", 10.0),
    INCIDENTS("Incidents", "Incidents", 20.0);

    val label: String get() = tr(labelEn, labelFr)
}

/**
 * One line of the score: points taken off ([penalty], 0 to [ScoreFactor.maxPenalty]), the measured value as shown,
 * and whether it was measured ([measured] false: not enough data, nothing taken off; or estimated, see [estimated]).
 */
data class ScoreContribution(val factor: ScoreFactor, val penalty: Double, val value: String, val measured: Boolean = true,
                             val estimated: Boolean = false)

/**
 * Mowing Score out of 100: 100 minus the points taken off by each [ScoreFactor]. [realCoverage]: coverage and
 * overlaps come from the real coverage map; otherwise coverage is estimated from the route progress and overlaps are
 * not measured.
 */
data class MowingScore(val total: Int, val contributions: List<ScoreContribution>, val realCoverage: Boolean) {
    fun of(factor: ScoreFactor): ScoreContribution = contributions.first { it.factor == factor }

    companion object {
        /** Overlap ratio (area mowed twice / area mowed) tolerated: neighbouring stripes overlap by design. */
        const val OVERLAP_TOLERANCE = 0.15
        /** Reversing allowed per run (leaving the base, resume after a recharge). */
        const val REVERSE_ALLOWANCE_M = 1.5
        /** Points per incident; incidents less than [INCIDENT_GROUP_MS] apart count once (a link loss and its STOP). */
        const val INCIDENT_POINTS = 4.0
        const val INCIDENT_GROUP_MS = 10_000L

        /**
         * Score of a mowing from its record, its track ([track], null if not recorded) and its real coverage
         * ([coverage], null if not measured).
         */
        fun of(record: MowingRecord, track: TaskTrack?, coverage: CoverageMap?): MowingScore {
            val real = coverage != null && coverage.areaM2 > 0
            val contributions = listOf(coverage(record, coverage), overlap(coverage), rotations(track), reversing(record, track), incidents(record, track))
            val total = (100.0 - contributions.sumOf { it.penalty }).roundToInt().coerceIn(0, 100)
            return MowingScore(total, contributions, real)
        }

        private fun pct(v: Double) = MowingText.percent(v * 100)

        private fun coverage(record: MowingRecord, coverage: CoverageMap?): ScoreContribution {
            val max = ScoreFactor.COVERAGE.maxPenalty
            if (coverage != null && coverage.areaM2 > 0) {
                val f = coverage.fraction
                return ScoreContribution(ScoreFactor.COVERAGE, (1 - f) * max, tr("${pct(f)} of the zones mowed", "${pct(f)} des zones tondues"))
            }
            val p = if (record.outcome == MowingOutcome.COMPLETE) 1.0 else record.progress
            return ScoreContribution(ScoreFactor.COVERAGE, (1 - p) * max,
                tr("${pct(p)} of the route covered (estimate)", "${pct(p)} du trajet parcouru (estimation)"), estimated = true)
        }

        private fun overlap(coverage: CoverageMap?): ScoreContribution {
            if (coverage == null || coverage.coveredM2 <= 0) return ScoreContribution(ScoreFactor.OVERLAP, 0.0, tr("not measured", "non mesuré"), measured = false)
            val ratio = coverage.repeatedM2 / coverage.coveredM2
            val penalty = ((ratio - OVERLAP_TOLERANCE) * 50).coerceIn(0.0, ScoreFactor.OVERLAP.maxPenalty)
            return ScoreContribution(ScoreFactor.OVERLAP, penalty, tr("${pct(ratio)} of the mowed area mowed twice or more",
                "${pct(ratio)} de la surface tondue l'a été plusieurs fois"))
        }

        private fun rotations(track: TaskTrack?): ScoreContribution {
            val planned = track?.meta?.let { TrackMetrics.plannedPivots(it.cutting) } ?: 0
            if (track == null || track.samples.size < 2 || planned == 0)
                return ScoreContribution(ScoreFactor.ROTATIONS, 0.0, tr("not measured", "non mesuré"), measured = false)
            val actual = TrackMetrics.pivots(track.samples)
            val excess = actual.toDouble() / planned - 1
            val penalty = (excess * 30).coerceIn(0.0, ScoreFactor.ROTATIONS.maxPenalty)
            return ScoreContribution(ScoreFactor.ROTATIONS, penalty, tr("$actual pivots for $planned planned", "$actual pivots pour $planned prévus"))
        }

        private fun reversing(record: MowingRecord, track: TaskTrack?): ScoreContribution {
            if (track == null || track.samples.size < 2) return ScoreContribution(ScoreFactor.REVERSING, 0.0, tr("not measured", "non mesuré"), measured = false)
            val meters = TrackMetrics.reverseMeters(track.samples)
            val allowance = REVERSE_ALLOWANCE_M * (1 + record.resumes + record.recharges)
            val penalty = ((meters - allowance) * 0.5).coerceIn(0.0, ScoreFactor.REVERSING.maxPenalty)
            val m = MowingForecaster.num(meters, 1)
            return ScoreContribution(ScoreFactor.REVERSING, penalty, tr("$m m in reverse (${MowingForecaster.num(allowance, 1)} m allowed)",
                "$m m en marche arrière (${MowingForecaster.num(allowance, 1)} m admis)"))
        }

        private fun incidents(record: MowingRecord, track: TaskTrack?): ScoreContribution {
            val events = track?.events.orEmpty()
            val count = if (events.isEmpty()) record.obstacles else TrackMetrics.incidents(events)
            val penalty = (count * INCIDENT_POINTS).coerceIn(0.0, ScoreFactor.INCIDENTS.maxPenalty)
            val text = if (count == 0) tr("none", "aucun") else tr("$count incident${if (count > 1) "s" else ""}", "$count incident${if (count > 1) "s" else ""}")
            return ScoreContribution(ScoreFactor.INCIDENTS, penalty, if (events.isEmpty()) tr("$text (obstacles only)", "$text (obstacles seulement)") else text,
                estimated = events.isEmpty())
        }
    }
}

/** Measurements of a track used by the score. */
object TrackMetrics {
    /** A pivot: at least [PIVOT_DEG] of rotation while the rotation centre moves less than [PIVOT_SPAN_M]. */
    const val PIVOT_DEG = 60.0
    const val PIVOT_SPAN_M = 0.15
    private val MOVING = setOf(TrackPhase.CUTTING, TrackPhase.TRAVEL, TrackPhase.RETURN)

    private fun turn(a: Double, b: Double) = ((b - a + 540.0) % 360.0) - 180.0

    /** Pivots on the spot in the track (recharging and docking left out; never across a gap). */
    fun pivots(samples: List<TrackSample>): Int {
        var count = 0
        var anchor: TrackSample? = null
        var turned = 0.0
        var previous: TrackSample? = null
        fun close() { if (abs(turned) >= PIVOT_DEG) count++; turned = 0.0 }
        for (s in samples) {
            val p = previous
            previous = s
            if (s.phase !in MOVING || s.heading.isNaN()) { close(); anchor = null; continue }
            val a = anchor
            if (p == null || a == null || p.heading.isNaN() || p.phase !in MOVING || TaskTrackPieces.broken(p, s)) { close(); anchor = s; continue }
            if (a.point.distance(s.point) > PIVOT_SPAN_M) { close(); anchor = s; continue }
            turned += turn(p.heading, s.heading)
        }
        close()
        return count
    }

    /**
     * Pivots the prepared route calls for: corners of at least [PIVOT_DEG] inside the cutting stripes, and at each
     * junction between consecutive stripes (in mowing order) the turn onto the link and the turn onto the next stripe
     * (a U-turn between two zigzag stripes counts two pivots).
     */
    fun plannedPivots(cutting: List<List<Point>>): Int {
        fun heading(a: Point, b: Point) = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
        var count = 0
        val stripes = cutting.map { line -> line.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-6 }.flatMap { listOf(it.first, it.second) }.distinct() }
            .filter { it.size >= 2 }
        stripes.forEach { line ->
            line.windowed(3).forEach { (a, b, c) -> if (abs(turn(heading(a, b), heading(b, c))) >= PIVOT_DEG) count++ }
        }
        stripes.zipWithNext().forEach { (from, to) ->
            val end = from.last(); val start = to.first()
            val out = heading(from[from.size - 2], end); val next = heading(start, to[1])
            if (end.distance(start) <= PIVOT_SPAN_M) { if (abs(turn(out, next)) >= PIVOT_DEG) count++ }
            else {
                val link = heading(end, start)
                if (abs(turn(out, link)) >= PIVOT_DEG) count++
                if (abs(turn(link, next)) >= PIVOT_DEG) count++
            }
        }
        return count
    }

    /** Distance travelled backwards: moves of at least 3 cm against the heading (recharging and docking left out). */
    fun reverseMeters(samples: List<TrackSample>): Double = samples.zipWithNext().sumOf { (a, b) ->
        if (a.phase !in MOVING || b.phase !in MOVING || a.heading.isNaN() || TaskTrackPieces.broken(a, b)) return@sumOf 0.0
        val dx = b.x - a.x; val dy = b.y - a.y
        val d = Math.hypot(dx, dy)
        if (d < 0.03) return@sumOf 0.0
        val h = Math.toRadians(a.heading)
        if ((dx * cos(h) + dy * sin(h)) < -0.5 * d) d else 0.0
    }

    /**
     * Incidents of the task journal: incident events less than [MowingScore.INCIDENT_GROUP_MS] apart count once; a STOP
     * asked by the user is not an incident.
     */
    fun incidents(events: List<TaskEvent>): Int {
        var count = 0
        var lastAt = Long.MIN_VALUE
        events.sortedBy { it.atMs }.forEach { e ->
            if (!e.type.incident || (e.type == TaskEventType.STOP && e.data["intentional"] == "true")) return@forEach
            if (lastAt == Long.MIN_VALUE || e.atMs - lastAt > MowingScore.INCIDENT_GROUP_MS) count++
            lastAt = e.atMs
        }
        return count
    }
}
