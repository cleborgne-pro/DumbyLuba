package fr.luba.poc.planning

import fr.luba.poc.history.CoverageGap
import fr.luba.poc.history.CoverageMap
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.OuterOffsetSetting
import fr.luba.poc.map.PaintedMission
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.RecordedBoundary
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.TrajectoryEnvelope
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.MapBase
import kotlin.math.atan2
import kotlin.math.roundToLong
import org.locationtech.jts.geom.Geometry as JtsGeometry

/**
 * Finishing pass offered after a mowing: the portions left unmowed ([CoverageMap.gaps]) of a finished task, worth a
 * short dedicated route rather than a new mowing of the zones.
 *
 * [areaM2]: total of the gaps; [zonesAreaM2]: measured surface of the task zones, the reference of a new mowing.
 */
data class FinishingOffer(val taskId: String, val gaps: List<CoverageGap>, val areaM2: Double, val zonesAreaM2: Double,
                          val area: JtsGeometry) {
    val count: Int get() = gaps.size
}

/**
 * Checks of a prepared finishing route: [collisions] lists the cutting passes whose LUBA 2 footprint leaves the free
 * physical surface of the robot map ([TrajectoryEnvelope]); [routeM] is the whole route (exit, passes, links,
 * return) and [newMowingM] the cutting length of a new mowing of the task zones at the same spacing.
 */
data class FinishingCheck(val routeM: Double, val cuttingM: Double, val newMowingM: Double, val collisions: List<String>) {
    val ratio: Double get() = if (newMowingM <= 0) Double.POSITIVE_INFINITY else routeM / newMowingM
    val safe: Boolean get() = collisions.isEmpty()
    val short: Boolean get() = ratio <= FinishingPass.MAX_ROUTE_RATIO
    val ok: Boolean get() = safe && short
}

/** Prepared finishing: mission ready for the BLE route, the offer it answers and its checks. */
data class FinishingPlan(val offer: FinishingOffer, val mission: PaintedMission, val check: FinishingCheck)

/**
 * Finishing phase on the real gaps: offer at the end of a finished mowing, dedicated target and checks.
 *
 * The gaps are mowed like a painted area ([PaintedZone.prepare]: same planner, navigation and route preparation on the
 * unchanged robot map), on a dedicated target: the gaps widened by the planner inset ([targetInsetM]) plus
 * [OVERLAP_M]. The edge of a temporary zone counts as a recorded outline (robot centre kept that far inside it):
 * widened, the lap around each piece runs [OVERLAP_M] outside the gap, on its ragged edge, and the passes cover the
 * gap itself, even about 1 m wide where a painted zone of the same gap leaves its centre line only. Where a gap
 * touches the zone boundary, the lap is the usual border lap: the strip along the edge is mowed as in a mowing.
 * Measured on the reference map (`FinishingPassTest`): a 1 m band closed entirely, a 3 m band closed to 2 %, route
 * about a quarter of a new mowing.
 */
object FinishingPass {
    /** Offered from this total of gaps: below, a dedicated trip is not worth it. */
    const val MIN_OFFER_M2 = 1.0
    /** Beyond this share of the zones left unmowed, resuming or mowing again is the right answer, not a finishing. */
    const val MAX_GAP_SHARE = 0.3
    /** Finishing route at most half the cutting length of a new mowing of the zones. */
    const val MAX_ROUTE_RATIO = 0.5
    /** Mowed grass taken around each gap: tolerates the position error and joins gaps a few decimetres apart. */
    const val OVERLAP_M = 0.2

    /**
     * Offer for a task: only a finished mowing ([complete]), with a measured coverage, at least [minM2] of gaps and at
     * most [MAX_GAP_SHARE] of its zones left. Null otherwise.
     */
    fun offer(taskId: String, coverage: CoverageMap, complete: Boolean, minM2: Double = MIN_OFFER_M2): FinishingOffer? {
        if (!complete || coverage.coveredM2 <= 0.0 || coverage.areaM2 <= 0.0) return null
        val gaps = coverage.gaps()
        val total = gaps.sumOf { it.areaM2 }
        if (total < minM2 || total > MAX_GAP_SHARE * coverage.areaM2) return null
        return FinishingOffer(taskId, gaps, total, coverage.areaM2, coverage.gapArea())
    }

    /**
     * Distance between a temporary zone edge and the centre of its lap in the planner ([PlanningTerrain], recorded
     * outline with a border lap: half width + outer margin).
     */
    fun targetInsetM(robot: RobotEnvelope = RobotEnvelope.LUBA2, outerOffsetM: Double = OuterOffsetSetting.meters): Double =
        RecordedBoundary.centerInsetM(robot) + outerOffsetM

    /** Planner target: gaps widened by [targetInsetM] + [OVERLAP_M] (clipped to the zones by [PaintedZone.pieces]). */
    fun target(offer: FinishingOffer, outerOffsetM: Double = OuterOffsetSetting.meters): JtsGeometry =
        offer.area.buffer(targetInsetM(outerOffsetM = outerOffsetM) + OVERLAP_M, 8)

    /**
     * Prepares the finishing of [offer] on [map] from [start] and checks it ([check]); fails with a readable message
     * when no plan is possible, when a pass leaves the safe surface or when the route is not clearly shorter than a
     * new mowing.
     */
    fun prepare(map: RobotMap, offer: FinishingOffer, start: Point, base: MapBase, accesses: List<List<Point>>,
                obstacles: List<LocalObstacle>, spacingM: Double, speedMps: Double,
                checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): FinishingPlan {
        val mission = PaintedZone.prepare(map, target(offer), start, base, accesses, obstacles, spacingM, borders = true,
            speedMps = speedMps, checkpoint = checkpoint, progress = progress, label = { tr("Finishing $it", "Finition $it") })
        progress(tr("Checking the finishing", "Vérification de la finition"))
        val check = check(mission, offer, spacingM)
        require(check.safe) { tr("Finishing refused: ${check.collisions.first()}", "Finition refusée : ${check.collisions.first()}") }
        require(check.short) {
            val route = check.routeM.roundToLong(); val full = check.newMowingM.roundToLong()
            tr("Finishing refused: $route m of route against $full m for a new mowing; mow the zones again",
                "Finition refusée : $route m de trajet contre $full m pour une nouvelle tonte ; retondez les zones")
        }
        return FinishingPlan(offer, mission, check)
    }

    /**
     * Envelope of every cutting pass on the free physical surface of the robot map (the one the BLE route follows)
     * and length compared with a new mowing (zones area / spacing, cutting only: favourable to the new mowing).
     */
    fun check(mission: PaintedMission, offer: FinishingOffer, spacingM: Double): FinishingCheck {
        val envelope = TrajectoryEnvelope(mission.engine.routes.physicalSurface)
        val collisions = mission.preview.cuttingPaths.mapIndexedNotNull { index, path ->
            if (path.size < 2) return@mapIndexedNotNull null
            // Closed loop: the robot ends facing its first segment.
            val heading = if (path.first().distance(path.last()) < 1e-8)
                Math.toDegrees(atan2(path[1].y - path[0].y, path[1].x - path[0].x)) else null
            val hit = try { envelope.pathCollision(path, finalHeading = heading) }
                catch (e: IllegalArgumentException) { return@mapIndexedNotNull e.message ?: "pass ${index + 1}" }
            hit?.let { tr("pass ${index + 1} leaves the safe surface (${it.motion.name.lowercase()})",
                "la passe ${index + 1} sort de la surface sûre (${it.motion.name.lowercase()})") }
        }
        val edges = mission.engine.edges
        return FinishingCheck(edges.sumOf { it.meters }, edges.filter { it.bladesOn }.sumOf { it.meters }, offer.zonesAreaM2 / spacingM, collisions)
    }
}
