package fr.luba.poc.obstacles

import fr.luba.poc.diagnostics.MapNavFollower
import fr.luba.poc.diagnostics.MapNavPlan
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.CoveragePlanner
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RecordedBoundary
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/** Outline targeted by the frontal test: outer limit of a zone or outline of a no-go zone. */
enum class BorderKind(private val labelEn: String, private val labelFr: String) {
    OUTER("Outer border", "Bordure extérieure"), NO_GO("No-go zone", "Zone interdite");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}

data class BorderChoice(val shapeId: Long, val kind: BorderKind, val label: String)

/**
 * Frontal test of a border (Service → Obstacle tests → Border). The recorded outline is read as the side of the chassis
 * ([RecordedBoundary]): along a border, the robot side stays at the set margin, but at the end of a pass the front faces
 * the outline, and the robot is longer than it is wide. This test places the robot facing the outline, at the closest
 * point, then advances it slowly: stop at the point where the planner ends the passes, visual check, then advance up
 * to the bumper contact or the observed edge.
 *
 * All distances are measured along the outline normal, on the free side (inside the zone, outside the no-go zone).
 * Contact point [contact] on the outline, unit normal ([normalX], [normalY]) pointing to the free side.
 */
data class BorderFrontalPlan(
    val shapeId: Long, val kind: BorderKind, val label: String,
    val contact: Point, val normalX: Double, val normalY: Double,
    /** Robot centre at the end of a pass, from the outline (same rule as [CoveragePlanner]). */
    val passEndM: Double,
    val robot: RobotEnvelope = RobotEnvelope.LUBA2,
) {
    init {
        require(contact.x.isFinite() && contact.y.isFinite() && passEndM.isFinite() && passEndM > 0) { tr("Invalid frontal test", "Essai frontal invalide") }
        require(kotlin.math.abs(sqrt(normalX * normalX + normalY * normalY) - 1.0) < 1e-6) { tr("Non-unit normal", "Normale non unitaire") }
    }

    private fun along(distance: Double) = Point(contact.x + normalX * distance, contact.y + normalY * distance)

    /** Start of the slow advance, reached by the move to the point (robot already facing the outline). */
    val approachM: Double get() = passEndM + BorderFrontal.SLOW_RUN_M
    /** Start of the last straight line of the move to the point: it turns the robot to face the outline. */
    val entryM: Double get() = approachM + BorderFrontal.ENTRY_RUN_M
    val passEnd: Point get() = along(passEndM)
    val approach: Point get() = along(approachM)
    val entry: Point get() = along(entryM)
    /** Robot centre when the bumper passes the outline by the physical tolerance: stop without contact. */
    val limit: Point get() = along(limitCenterM)
    val limitCenterM: Double get() = robot.frontM - RecordedBoundary.PHYSICAL_TOLERANCE_M

    /** Robot heading facing the outline (degrees, counter-clockwise from +x). */
    val headingDegrees: Double get() = Math.toDegrees(atan2(-normalY, -normalX)).let { (it + 360) % 360 }

    /** Distance from the centre to the outline, along the normal (negative beyond the outline). */
    fun centerGap(center: Point): Double = (center.x - contact.x) * normalX + (center.y - contact.y) * normalY

    /** Distance from the front bumper to the outline, robot facing the outline (negative: bumper beyond). */
    fun frontGap(center: Point): Double = centerGap(center) - robot.frontM

    /** Lateral offset of the centre from the normal through the contact point. */
    fun lateral(center: Point): Double = (center.x - contact.x) * -normalY + (center.y - contact.y) * normalX

    /** Planned bumper position at the end of a pass, from the outline. */
    val plannedFrontGapM: Double get() = passEndM - robot.frontM

    /** Front corners during the end-of-pass pivot ([RobotEnvelope.turnClearanceM]), from the outline. */
    val plannedPivotGapM: Double get() = passEndM - robot.turnClearanceM
}

object BorderFrontal {
    /** Slow advance before the end-of-pass point: the robot arrives there at reduced speed and on axis. */
    const val SLOW_RUN_M = 0.30
    /** Last straight line of the move to the point, perpendicular to the outline. */
    const val ENTRY_RUN_M = 0.50
    /**
     * Speed up to the end of the pass (≈ 6.6 cm/s) then up to the edge (≈ 4.7 cm/s), at 0.95 mm/s per unit. Above the
     * threshold where the LUBA 2 stalls (≈ 25–30 units, survey of 2026-10-10): at 20 units, the advance was not guaranteed.
     */
    const val APPROACH_LINEAR = 70
    const val EDGE_LINEAR = 50
    /** Maximum lateral offset from the normal during the advance (straight line without correction). */
    const val MAX_LATERAL_M = 0.15
    /** Minimum side length to target a point of the segment far from its ends. */
    private const val END_CLEARANCE_M = 0.30
    /** Wait for a verdict at the end-of-pass stop, robot stationary. */
    const val VERDICT_TIMEOUT_MS = 180_000L
    const val APPROACH_TIMEOUT_MS = 60_000L
    const val EDGE_TIMEOUT_MS = 90_000L

    fun choices(map: RobotMap): List<BorderChoice> {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val noGos = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }
        return zones.mapIndexed { i, s -> BorderChoice(s.id, BorderKind.OUTER, s.name.ifBlank { "Zone ${i + 1}" }) } +
            noGos.mapIndexed { i, s -> BorderChoice(s.id, BorderKind.NO_GO, s.name.ifBlank { tr("No-go zone ${i + 1}", "Zone interdite ${i + 1}") }) }
    }

    /**
     * Robot centre at the end of a pass, from the outline: [CoveragePlanner] rule for a recorded outline.
     * Outer limit: loop at half a width + margin, passes half a cut behind this loop. No-go zone: a quarter cut behind
     * its loop, and at least the pivot radius + margin + 1 cm.
     */
    fun passEndM(kind: BorderKind, outerOffsetM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): Double {
        require(outerOffsetM.isFinite() && outerOffsetM >= 0) { tr("Invalid margin", "Marge invalide") }
        val loop = RecordedBoundary.centerInsetM(robot) + outerOffsetM
        return when (kind) {
            BorderKind.OUTER -> loop + CoveragePlanner.ZIGZAG_LOOP_INSET_M
            BorderKind.NO_GO -> max(loop + Geometry.CUT_WIDTH_M / 4, robot.turnClearanceM + outerOffsetM + 0.01)
        }
    }

    /**
     * Point of outline [shapeId] closest to the robot centre, on a side long enough for a frontal test, and normal
     * towards the free side. Explicit refusal if the outline is missing or no side fits.
     */
    fun plan(map: RobotMap, shapeId: Long, center: Point, outerOffsetM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): BorderFrontalPlan {
        require(center.x.isFinite() && center.y.isFinite()) { tr("Invalid robot position", "Position du robot invalide") }
        val choice = choices(map).firstOrNull { it.shapeId == shapeId } ?: error(tr("Outline not found on the map", "Contour introuvable sur la carte"))
        val shape: MapShape = map.shapes.first { it.id == shapeId && (it.kind == ShapeKind.ZONE || it.kind == ShapeKind.NO_GO) }
        val ring = shape.points.let { if (it.first() == it.last()) it.dropLast(1) else it }
        require(ring.size >= 3) { tr("Incomplete outline", "Contour incomplet") }
        val margin = robot.halfWidthM + END_CLEARANCE_M
        data class Candidate(val point: Point, val nx: Double, val ny: Double, val distance: Double)
        val candidates = ring.indices.mapNotNull { i ->
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            val length = a.distance(b)
            // Side too short: the bumper would also touch the neighbouring side, the measurement would not be frontal.
            if (length < 2 * margin) return@mapNotNull null
            val ux = (b.x - a.x) / length; val uy = (b.y - a.y) / length
            val t = ((center.x - a.x) * ux + (center.y - a.y) * uy).coerceIn(margin, length - margin)
            val p = Point(a.x + ux * t, a.y + uy * t)
            // Normal towards the free side: inside the zone for a limit, outside the no-go zone for a no-go.
            var nx = -uy; var ny = ux
            val probe = Point(p.x + nx * 0.05, p.y + ny * 0.05)
            if (Geometry.inside(probe, ring) != (choice.kind == BorderKind.OUTER)) { nx = -nx; ny = -ny }
            Candidate(p, nx, ny, p.distance(center))
        }
        val best = candidates.minByOrNull { it.distance } ?: error(tr("No side long enough (≥ %.2f m) on this outline", "Aucun côté assez long (≥ %.2f m) sur ce contour").format(2 * margin))
        return BorderFrontalPlan(shapeId, choice.kind, choice.label, best.point, best.nx, best.ny, passEndM(choice.kind, outerOffsetM, robot), robot)
    }

    /**
     * Checks of the test line: entry point and straight line up to the outline inside a mapped zone, outside the other
     * no-go zones and away from the saved obstacles.
     */
    fun lineFailure(map: RobotMap, plan: BorderFrontalPlan, obstacles: List<LocalObstacle>): String? {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
        val noGos = map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 && it.id != plan.shapeId }
        val samples = (0..20).map { i -> plan.entryM * i / 20.0 }.filter { it > 0.02 }
            .map { d -> Point(plan.contact.x + plan.normalX * d, plan.contact.y + plan.normalY * d) }
        if (samples.any { p -> zones.none { Geometry.inside(p, it.points) } }) return tr("Test line outside the mapped zones: choose another place", "Ligne d'essai hors des zones cartographiées : choisir un autre endroit")
        // Chassis (half-width) on the line: no other no-go zone may be touched.
        val corridor = robot(plan).halfWidthM + 0.05
        if (noGos.any { shape -> samples.any { p -> Geometry.inside(p, shape.points) || distanceToRing(p, shape.points) < corridor } })
            return tr("Another no-go zone borders the test line: bring the robot to another place on the outline", "Une autre zone interdite borde la ligne d'essai : approcher le robot d'un autre endroit du contour")
        val blocking = obstacles.firstOrNull { o ->
            Geometry.distance(Point(o.x, o.y), plan.contact, plan.entry) < o.exclusionRadiusM + robot(plan).turnClearanceM
        }
        if (blocking != null) return tr("Saved obstacle on the test line (${blocking.label.ifBlank { blocking.kind.label }})", "Obstacle enregistré sur la ligne d'essai (${blocking.label.ifBlank { blocking.kind.label }})")
        return null
    }

    private fun robot(plan: BorderFrontalPlan) = plan.robot

    private fun distanceToRing(p: Point, ring: List<Point>): Double =
        ring.indices.minOf { i -> Geometry.distance(p, ring[i], ring[(i + 1) % ring.size]) }

    /**
     * Move to the point ending facing the outline: safe path up to the entry point, then a perpendicular straight line
     * up to the start of the slow advance, checked with the LUBA 2 footprint.
     */
    fun navPlan(map: RobotMap, start: Point, plan: BorderFrontalPlan, obstacles: List<LocalObstacle>): MapNavPlan {
        lineFailure(map, plan, obstacles)?.let { error(it) }
        // Robot already at the entry point: direct straight line up to the start of the slow advance.
        if (start.distance(plan.entry) <= MapNavFollower.ARRIVAL_M) {
            val direct = fr.luba.poc.planning.RoutePlanning.point(map, start, plan.approach, obstacles)
            return direct.copy(labels = direct.labels.dropLast(1) + APPROACH_LABEL)
        }
        val base = fr.luba.poc.planning.RoutePlanning.point(map, start, plan.entry, obstacles)
        val points = base.points + plan.approach
        val routes = requireNotNull(base.routes) { tr("Plan without physical surface", "Plan sans surface physique") }
        try { routes.requirePath(points) }
        catch (e: IllegalArgumentException) { error(tr("Last straight line facing the outline not safe: ${e.message}", "Dernière ligne droite face au contour non sûre : ${e.message}")) }
        return base.copy(points = points, labels = base.labels.dropLast(1) + ENTRY_LABEL + APPROACH_LABEL)
    }

    val ENTRY_LABEL: String get() = tr("Alignment facing the outline", "Alignement face au contour")
    val APPROACH_LABEL: String get() = tr("Facing the outline", "Face au contour")

    /** Predicted centre on the normal when the command sent now takes effect (straight-line advance). */
    fun predictedCenterGap(measuredGap: Double, positionAgeMs: Long, commandDelayMs: Long, linear: Int, metersPerUnit: Double): Double =
        measuredGap - linear * metersPerUnit * (positionAgeMs.coerceAtLeast(0) + commandDelayMs) / 1000.0

    /** Readable summary: end-of-pass reserves according to the observed edge position. */
    fun summary(plan: BorderFrontalPlan, passEndFrontGap: Double?, edgeFrontGap: Double?): String {
        val parts = mutableListOf<String>()
        passEndFrontGap?.let { parts += tr("end of pass: bumper %.0f cm from the outline (planned %.0f cm)", "bout de passe : pare-chocs à %.0f cm du contour (prévu %.0f cm)").format(it * 100, plan.plannedFrontGapM * 100) }
        edgeFrontGap?.let { edge ->
            parts += tr("edge %.0f cm %s", "bord à %.0f cm %s").format(kotlin.math.abs(edge) * 100, if (edge >= 0) tr("before the outline", "avant le contour") else tr("beyond the outline", "au-delà du contour"))
            // Actual end-of-pass reserve: planned bumper distance to the outline minus the observed edge offset.
            val reserve = plan.plannedFrontGapM - edge
            val pivot = plan.plannedPivotGapM - edge
            parts += tr("end-of-pass reserve: bumper %.0f cm, corners at pivot %.0f cm", "réserve en bout de passe : pare-chocs %.0f cm, coins au pivot %.0f cm").format(reserve * 100, pivot * 100)
        }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

/** Steps of the frontal test, in order. */
enum class BorderPhase(private val labelEn: String, private val labelFr: String) {
    IDLE("—", "—"),
    POSITIONING("Positioning facing the outline", "Positionnement face au contour"),
    APPROACH("Slow advance to the end of the pass", "Avance lente jusqu'au bout de passe"),
    PASS_END("Stop at the end of the pass: visual check", "Arrêt en bout de passe : contrôle visuel"),
    EDGE("Slow advance to the edge", "Avance lente jusqu'au bord"),
    SETTLING("Stop: measuring the position", "Arrêt : mesure de la position");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}

/** Stop reason of a frontal test advance. */
enum class BorderStop { TARGET, CONTACT, SENSOR_ALERT, EDGE_SEEN, LIMIT }

/** Displayed state of the frontal test (Service → Obstacle tests → Border). */
data class BorderFrontalUi(
    val running: Boolean = false,
    val phase: BorderPhase = BorderPhase.IDLE,
    val shapeId: Long? = null,
    val plan: BorderFrontalPlan? = null,
    /** Preview (target point, planned distances) or reason for unavailability. */
    val preview: String = "",
    val previewRefused: Boolean = false,
    val status: String = tr("No frontal test", "Aucun essai frontal"),
    /** Stored value compared by the UI (`ObstaclesPanel`, `outcome != "Non démarré"`): literal kept untranslated. */
    val outcome: String = "Non démarré",
    val outerOffsetM: Double = 0.0,
    /** Bumper to outline, measured at the end-of-pass stop, and centre overshoot beyond the planned point. */
    val passEndFrontGapM: Double? = null,
    val passEndOvershootM: Double? = null,
    /** Bumper to outline, measured at the contact or at the reported edge. */
    val edgeFrontGapM: Double? = null,
    /** Bumper to outline during the advance (published position, 1 Hz). */
    val liveFrontGapM: Double? = null,
    val results: List<fr.luba.poc.diagnostics.TestResult> = emptyList(),
)
