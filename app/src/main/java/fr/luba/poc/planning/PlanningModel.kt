package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import java.security.MessageDigest

/** Metric software contract, distinct from an authorisation to control the robot. */
enum class PlannerStrategy { BASELINE, OPTIMIZED, EXPERIMENTAL_BCD, EXPERIMENTAL_JOINT, EXPERIMENTAL_LNS, ADVANCED }
enum class BoundaryMeaning { PHYSICAL, RECORDED_TRACE, UNKNOWN }
enum class PlanStatus { VALIDATED_IN_MODEL, PARTIAL_COVERAGE, INVALID_INPUT, NO_SOLUTION_FOUND, UNVERIFIED_CAPABILITY, BUDGET_EXHAUSTED, NUMERIC_FAILURE }
data class ScoreWeights(val distance: Double = 1.0, val duration: Double = 0.3,
    val uTurns: Double = 2.0, val overlap: Double = 1.0, val transit: Double = 2.0) {
    init { require(listOf(distance,duration,uTurns,overlap,transit).all { it.isFinite() && it >= 0 }) }
    fun score(m: MissionMetrics) = distance*m.distanceM + duration*m.durationSeconds +
        uTurns*m.uTurns + overlap*m.extraVisitAreaM2 + transit*m.transitM
}
data class PlanningConfig(val spacingM: Double = 0.25, val marginM: Double = 0.6,
    val cutWidthM: Double = 0.4, val minimumCoverage: Double = 0.95,
    val speedMps: Double = 0.3, val turnDegreesPerSecond: Double = 45.0,
    val stopSeconds: Double = 0.0, val uncertaintyM: Double = 0.0,
    val borders: Boolean = true, val borderPasses: Int = 1, val noGoPasses: Int = 1,
    val bordersFirst: Boolean = true, val nearbyNoGo: Boolean = true,
    val boundary: BoundaryMeaning = BoundaryMeaning.PHYSICAL,
    val allowHypotheses: Boolean = true, val pivotVerified: Boolean = false,
    val verifiedMaxSlopeDegrees: Double? = null, val weights: ScoreWeights = ScoreWeights(),
    val candidateBudget: Int = 8, val localSearchBudget: Int = 24,
    val optimizeZoneOrder: Boolean = false, val indexedCoverage: Boolean = true,
    /** Cell of the mowed-surface measurement; 0 = exact vector oracle (tests, ablations). */
    val coverageCellM: Double = 0.02,
    /** Margin between the robot side and the recorded outline (outer side of the chassis) for the outer loop. */
    val outerOffsetM: Double = fr.luba.poc.map.CoveragePlanner.RECORDED_OUTER_OFFSET_M) {
    init {
        require(listOf(spacingM,marginM,cutWidthM,speedMps,turnDegreesPerSecond).all { it.isFinite() && it > 0 })
        require(spacingM >= 0.05 && marginM >= 0.3 && cutWidthM <= 2)
        require(minimumCoverage.isFinite() && minimumCoverage in 0.0..1.0)
        require(stopSeconds.isFinite() && stopSeconds >= 0 && uncertaintyM.isFinite() && uncertaintyM >= 0)
        require(borderPasses in 0..20 && noGoPasses in 0..20 && candidateBudget in 1..64 && localSearchBudget in 0..256)
        require(coverageCellM.isFinite() && coverageCellM in 0.0..0.1)
        require(outerOffsetM.isFinite() && outerOffsetM in 0.0..fr.luba.poc.map.CoveragePlanner.MAX_OUTER_OFFSET_M) { tr("Outer loop inset out of range 0–1 m", "Retrait du tour extérieur hors plage 0–1 m") }
        require(verifiedMaxSlopeDegrees == null || verifiedMaxSlopeDegrees.isFinite() && verifiedMaxSlopeDegrees in 0.0..89.0)
    }
}
/** Physical radius + user reserve, WITHOUT inflation by the chassis. */
data class PlanningObstacle(val center: Point, val radiusM: Double)
data class SlopeRegion(val polygon: List<Point>, val degrees: Double)
/** Saved local accesses and station axis: same context for the score and the simulation. */
data class PlanningNavigation(val accesses: List<List<Point>> = emptyList(),
    val base: fr.luba.poc.simulation.MapBase? = null)
data class PlanningRequest(val map: RobotMap, val zoneIds: List<Long>, val start: Point,
    val startHeading: Double? = null, val config: PlanningConfig = PlanningConfig(),
    val robot: RobotEnvelope = RobotEnvelope.LUBA2,
    val obstacles: List<PlanningObstacle> = emptyList(), val corridorWidthsM: Map<Long,Double> = emptyMap(),
    val slopes: List<SlopeRegion> = emptyList(), val navigation: PlanningNavigation? = null,
    /** Mission arrival (the base) if it differs from the start (robot position). */
    val end: Point? = null,
    /** DANGER boundary portions kept away from the links ([fr.luba.poc.simulation.TrajectoryPlanner.dangerClearance]);
     * null: those annotated on the phone, filled in by [RoutePlanning.coverage]. */
    val dangerLines: List<List<Point>>? = null,
    /** Typed boundary portions for the border strategy ([BorderStrategy]); null: those annotated on the phone,
     * applied by [RoutePlanning.coverage] (empty once applied). */
    val borders: List<BorderPortion>? = null,
    /** Recorded map for the transits when [map] carries the outlines moved by the border strategy. */
    val navigationMap: RobotMap? = null) {
    /** Evaluated mission end point: the base, or the start if there is no distinct arrival. */
    val finish: Point get() = end ?: start
    /** Heading on arrival: the start one for a round trip, free for a distinct arrival. */
    val finishHeading: Double? get() = if (end == null) startHeading else null
    /** Defensive copy on entry; no cache survives this snapshot. */
    fun snapshot() = copy(map=map.copy(shapes=map.shapes.map { it.copy(points=it.points.toList()) }),
        zoneIds=zoneIds.toList(), obstacles=obstacles.toList(), corridorWidthsM=corridorWidthsM.toSortedMap(),
        slopes=slopes.map { it.copy(polygon=it.polygon.toList()) },
        navigation=navigation?.copy(accesses=navigation.accesses.map { it.toList() }),
        dangerLines=dangerLines?.map { it.toList() })
    val hash: String get() = stableHash(toString())
}
internal fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun heading(a: Point,b: Point) = Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
internal fun angleDifference(a: Double,b: Double) = kotlin.math.abs(((b-a)%360+540)%360-180)
internal fun length(path: List<Point>) = path.zipWithNext().sumOf { (a,b) -> a.distance(b) }
data class MissionLeg(val path: List<Point>, val cutting: Boolean)
data class MissionMetrics(val distanceM: Double, val transitM: Double, val uTurns: Int,
    val pivots: Int, val turnDegrees: Double, val durationSeconds: Double,
    val targetAreaM2: Double, val coveredAreaM2: Double, val uncoveredAreaM2: Double,
    val repeatedAreaM2: Double, val extraVisitAreaM2: Double, val geometricViolations: Int) {
    val coverage: Double get() = if (targetAreaM2 > 0) coveredAreaM2/targetAreaM2 else 0.0
}
data class CandidateResult(val name: String, val status: PlanStatus, val preview: Preview?,
    val metrics: MissionMetrics?, val score: Double?, val diagnostics: List<String>,
    val mission: List<MissionLeg> = emptyList()) {
    val valid: Boolean get() = status == PlanStatus.VALIDATED_IN_MODEL && score != null
    val planHash: String get() = stableHash(mission.toString())
}
data class PlanningResult(val snapshotHash: String, val strategy: PlannerStrategy,
    val selected: CandidateResult, val candidates: List<CandidateResult>,
    val assumptions: List<String>, val phaseNanos: Map<String,Long>, val transitionQueries: Long,
    val transitionCacheHits: Long)
