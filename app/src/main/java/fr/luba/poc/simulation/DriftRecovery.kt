package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotPose
import fr.luba.poc.planning.CoverageEngine
import fr.luba.poc.protocol.CollisionLatch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tracking stop for a deviation or a footprint outside the safe corridor, with the edge where it happened: the mission
 * can resume ([DriftRecovery]). Still an [IllegalStateException]: everywhere else, same stop as before.
 */
class RouteDrift(val index: Int, message: String) : IllegalStateException(message)

/** Prepared resume: optional back-off (heading unchanged), then the remaining route starting with a blades-OFF link. */
data class DriftPlan(
    /** Point to reach in reverse first, or null (robot already inside the safe surface, or straight escape ahead first). */
    val backOff: Point?,
    /** Blades-OFF link up to the route, then the rest of the route; [origins]: original edge (-1: link). */
    val edges: List<SimEdge>, val origins: List<Int>,
    val rejoinMeters: Double,
    /** Cutting skipped to rejoin the route further on (m). */
    val skippedCuttingMeters: Double,
)

/**
 * Leaving the route during a mission (2026-10-14 at 14:17: chassis right at the edge during a bypass, stop "Enveloppe
 * robot hors du couloir sûr"; then "Écart au trajet >30 cm" on resume): instead of stopping the mowing, the robot
 * backs off in a straight line along its own trail until its chassis is again inside the safe surface with the
 * margins, then rejoins the route with blades OFF and continues. Same rule for actual mowing (Fold and gateway) and
 * the obstacle simulation.
 *
 * Bounds: [MAX_RECOVERIES] resumes per mission and [MAX_SAME_SPOT] in a row at the same place ([SAME_SPOT_M]); a new
 * exit near the previous one rejoins the route further on, so as not to retry the same tricky passage in a loop.
 * Drift inside the base zone, other safety stops: stop unchanged.
 */
object DriftRecovery {
    const val MAX_RECOVERIES = 30
    /** Successive exits at the same place: beyond this, the passage is deemed impassable and the stop kept. */
    const val MAX_SAME_SPOT = 3
    /** Route checked after the resume point (pivots included) with the tracking tolerance. */
    const val LOOKAHEAD_M = 1.0
    /** Back-off along the trail: bounds and step (m). */
    const val MIN_BACK_OFF_M = 0.1
    const val MAX_BACK_OFF_M = 1.0
    const val BACK_OFF_STEP_M = 0.1
    /** Allowed gap between the back-off point and the travelled trail (the robot goes back where it went). */
    const val TRAIL_TOLERANCE_M = 0.2
    /** Exit closer than this to the previous one: resume that much further on the route. */
    const val SAME_SPOT_M = 1.0
    /** Distances (m) by which the resume point may advance along the route ([REJOIN_WINDOW_M]). */
    val ENTRY_OFFSETS_M = listOf(0.0, 0.3, 0.6, 1.0, 1.5, 2.5, 4.0)
    /**
     * Entries up to this far along the route from the projection of the robot ([ENTRY_OFFSETS_M]) compete on their
     * manoeuvre cost ([rejoinSeconds]) instead of the nearest one winning; beyond it, only when none of them is safe.
     */
    const val REJOIN_WINDOW_M = 1.0
    /** Nominal forward speed of the cost (m/s): default 30 % setting (docs/TRACKING_EXECUTION.md). */
    private const val REJOIN_SPEED_MPS = 0.13
    /**
     * Cost of a skipped cutting metre (s): the robot drifted with its blades on beside the pass, so the skipped
     * stretch is partly mowed; about a quarter of its mowing time at [REJOIN_SPEED_MPS]. Higher, the robot came back
     * sideways next to itself (two pivots of ≈ 90°); lower, it skipped the whole window every time.
     */
    private const val SKIPPED_CUTTING_S_PER_M = 2.0

    /** Stop reason kept, or null if one more resume is allowed ([count] resumes done, [streak] at the same place). */
    fun refusal(count: Int, streak: Int): String? = when {
        count >= MAX_RECOVERIES -> tr("$MAX_RECOVERIES resumes already made, stop kept", "$MAX_RECOVERIES reprises déjà effectuées, arrêt maintenu")
        streak >= MAX_SAME_SPOT -> tr("Exit #${MAX_SAME_SPOT + 1} in a row at the same place, stop kept (passage too tight: widen the zone or draw it as a no-go zone)",
            "${MAX_SAME_SPOT + 1}ᵉ sortie de suite au même endroit, arrêt maintenu (passage trop serré : élargir la zone ou le tracer en no-go)")
        else -> null
    }

    /**
     * Robot entered the tracking reserve of an obstacle the route already bypasses (2026-10-15 at 09:06:41: 94° pivot
     * at the link point in front of the base, 15 cm slide towards the neighbouring obstacle, mowing stopped).
     * Recoverable: the link starts with a straight-line escape checked against the physical obstacles
     * ([RobotRoute.departure]).
     */
    val OBSTACLE_RESERVE: String get() = tr(OBSTACLE_RESERVE_EN, OBSTACLE_RESERVE_FR)
    private const val OBSTACLE_RESERVE_EN = "Local obstacle inside the robot envelope"
    private const val OBSTACLE_RESERVE_FR = "Obstacle local dans l'enveloppe robot"

    /**
     * Recoverable stops: route deviation, footprint outside the corridor, reserve of an already bypassed obstacle,
     * return drift or pivot slide ([PivotSlide]) beyond the tolerance or outside the safe zone. Reasons are matched in
     * both languages (the gateway and the pilot phone may not use the same one).
     */
    fun recoverable(reason: String): Boolean =
        reason.startsWith("Écart au trajet") || reason.startsWith("Enveloppe robot hors du couloir") ||
            reason.startsWith("Route deviation") || reason.startsWith("Robot envelope outside the safe corridor") ||
            reason == OBSTACLE_RESERVE_FR || reason == OBSTACLE_RESERVE_EN ||
            reason.startsWith("Glissement au pivot") || reason.startsWith("Pivot slide") ||
            ((reason.startsWith("Dérive de") || reason.startsWith("Glissement de")) && reason.contains("hors de la zone sûre")) ||
            ((reason.startsWith("Drift of") || reason.startsWith("Slide of")) && reason.contains("outside the safe zone"))

    /** Chassis entirely inside the free physical surface, at [position] and heading [headingDegrees]. */
    fun bodyInside(physical: TrajectoryPlanner, position: Point, headingDegrees: Double): Boolean =
        physical.footprintSafe(RobotPose(position, headingDegrees))

    /** Trail breaks ([MissionTrail.BREAK]) excluded: never back off along an untravelled jump. */
    private fun distanceToTrail(p: Point, trail: List<Point>): Double = MissionTrail.distance(p, trail)

    /**
     * Possible starts of the resume, in order: the current pose (null) if the chassis is entirely inside the surface
     * ([bodyInside]), then the straight back-off points behind the robot (heading unchanged), on its recent trail,
     * chassis back inside, from the nearest to the farthest.
     */
    fun starts(physical: TrajectoryPlanner, position: Point, headingDegrees: Double, trail: List<Point>): List<Point?> {
        val out = mutableListOf<Point?>()
        if (bodyInside(physical, position, headingDegrees)) out += null
        if (trail.isEmpty()) return out
        val rad = Math.toRadians(headingDegrees)
        var d = MIN_BACK_OFF_M
        while (d <= MAX_BACK_OFF_M + 1e-9) {
            val q = Point(position.x - d * cos(rad), position.y - d * sin(rad))
            if (distanceToTrail(q, trail) > TRAIL_TOLERANCE_M) break
            if (bodyInside(physical, q, headingDegrees)) out += q
            d += BACK_OFF_STEP_M
        }
        return out
    }

    /**
     * Back-off starts after a contact at the limit of the task area ([EdgeContactKind.LIMIT]): the collision lock only
     * clears once the robot has moved away, so the current pose is never a start. Points of [starts] at least
     * [fr.luba.poc.protocol.CollisionLatch.UNLOCK_BACK_OFF_M] behind the robot; without a trail behind it (contact right
     * after a pivot), the straight [ObstacleBypass.BLIND_BACK_OFF_M] back-off checked on the map ([ObstacleBypass.backOffCandidates]).
     */
    fun limitStarts(physical: TrajectoryPlanner, position: Point, headingDegrees: Double, trail: List<Point>): List<Point> {
        val along = starts(physical, position, headingDegrees, trail).filterNotNull()
            .filter { it.distance(position) >= CollisionLatch.UNLOCK_BACK_OFF_M - 1e-9 }
        if (along.isNotEmpty()) return along
        return ObstacleBypass.backOffCandidates(physical, position, headingDegrees, ObstacleBypass.straightBehind(position, headingDegrees),
            ObstacleBypass.BLIND_BACK_OFF_M)
    }

    private fun headingOf(e: SimEdge) = Math.toDegrees(atan2(e.b.y - e.a.y, e.b.x - e.a.x))

    /** Route over [LOOKAHEAD_M] after [entry] (edge [entryIndex]) with the tracking tolerance, pivots included. */
    private fun ahead(routes: TrajectoryPlanner, path: List<SimEdge>, entryIndex: Int, entry: Point): Boolean {
        val points = mutableListOf(entry)
        var meters = 0.0; var i = entryIndex
        while (i < path.size && meters < LOOKAHEAD_M) { meters += points.last().distance(path[i].b); points += path[i].b; i++ }
        return routes.pathTolerant(points, headingOf(path[entryIndex]), path.getOrNull(i)?.let(::headingOf))
    }

    /**
     * Driving time (s) of [edges] from [heading], turns included up to [finalHeading]: [REJOIN_SPEED_MPS], pivot rate
     * and stop measured on the robot ([CoverageEngine.ROBOT_TURN_DEGREES_PER_SECOND], [CoverageEngine.ROBOT_STOP_SECONDS]
     * per turn sharper than [PathFollower.CORNER_DEGREES], where tracking stops and pivots).
     */
    private fun drivingSeconds(edges: List<SimEdge>, heading: Double, finalHeading: Double): Double {
        val moving = edges.filter { it.meters > 1e-6 }
        val headings = listOf(heading) + moving.map(::headingOf) + finalHeading
        val turns = headings.zipWithNext().map { (a, b) -> abs(((b - a) % 360 + 540) % 360 - 180) }
        return moving.sumOf { it.meters } / REJOIN_SPEED_MPS + turns.sum() / CoverageEngine.ROBOT_TURN_DEGREES_PER_SECOND +
            turns.count { it > PathFollower.CORNER_DEGREES } * CoverageEngine.ROBOT_STOP_SECONDS
    }

    /**
     * Cost (s) of resuming at [entry] (edge [entryIndex]) through [link] from [heading]: driving the link, minus the
     * stretch of the route from [from] (projection of the start on edge [index]) to [entry] the robot no longer drives,
     * plus the skipped cutting ([SKIPPED_CUTTING_S_PER_M]). A robot 30 cm beside its pass meets it about 1 m further
     * on with a slight angle rather than with two square pivots, and one that went past a corner heads for the next
     * stretch instead of turning back to the corner.
     */
    private fun rejoinSeconds(path: List<SimEdge>, index: Int, from: Point, entryIndex: Int, entry: Point,
                              link: List<SimEdge>, heading: Double, skippedCutting: Double): Double {
        val skippedRoute = if (entryIndex == index) listOf(SimEdge(from, entry, false))
            else listOf(SimEdge(from, path[index].b, false)) + path.subList(index + 1, entryIndex) + SimEdge(path[entryIndex].a, entry, false)
        return drivingSeconds(link, heading, headingOf(path[entryIndex])) -
            drivingSeconds(skippedRoute, headingOf(path[index]), headingOf(path[entryIndex])) +
            skippedCutting * SKIPPED_CUTTING_S_PER_M
    }

    /**
     * Resume after an exit at edge [index] of [path]: optional back-off ([starts]), or as a last resort for a chassis
     * overlapping an edge a straight escape ahead, then a blades-OFF link
     * ([RobotRoute.departure], optional escape included) up to the projection of the start on the interrupted edge,
     * advanced by [skipM] then by the [ENTRY_OFFSETS_M]: the cheapest entry within [REJOIN_WINDOW_M] ([rejoinSeconds]),
     * otherwise the first one further on whose link succeeds; the rest of the route is unchanged.
     * First a link keeping the tracking tolerance up to the entry pivot and over [LOOKAHEAD_M] after it, and meeting a
     * pass near the outline along its axis ([ObstacleBypass.arrivesAligned]), so as not to resume right at the edge or
     * nose first into the border; failing that, the first safe one. [afterContact]: contact at the limit of the task
     * area ([EdgeContactKind.LIMIT]), back-off of at least 30 cm first ([limitStarts]).
     */
    fun plan(routes: TrajectoryPlanner, physical: TrajectoryPlanner, path: List<SimEdge>, origins: List<Int>?, index: Int,
             position: Point, headingDegrees: Double, trail: List<Point>, skipM: Double = 0.0, afterContact: Boolean = false): DriftPlan {
        require(index in path.indices) { tr("Interrupted edge outside the route", "Arête interrompue hors trajet") }
        require(origins == null || origins.size == path.size)
        require(position.x.isFinite() && position.y.isFinite() && headingDegrees.isFinite()) { tr("Invalid robot pose", "Pose du robot invalide") }
        val inside = bodyInside(physical, position, headingDegrees)
        // Chassis overlapping an edge: last resort, the straight escape ahead from the current pose ([RobotRoute.escapePoint],
        // only if it moves the chassis away from the edge). 2026-10-19 at 14:50, Fosse: self-check 25 during a 150° pivot
        // with a slide towards the border; after the pivot the trail was ahead of the robot, no back-off was possible and
        // the mowing stopped, while the manual resume from the same pose left with a 30 cm escape ahead.
        // After a contact at the limit ([afterContact]): a back-off first, always ([limitStarts]); no escape ahead.
        val starts: List<Point?> = if (afterContact) limitStarts(physical, position, headingDegrees, trail)
            else starts(physical, position, headingDegrees, trail).let { if (inside) it else it + null }
        fun noStart() = (if (trail.isEmpty()) tr("Back-off impossible: robot trail missing", "Recul impossible : trace du robot absente")
            else tr("Back-off impossible: chassis outside the zone along the whole trail within %.0f cm behind the robot",
                "Recul impossible : châssis hors de la zone sur toute la trace à moins de %.0f cm derrière le robot").format(MAX_BACK_OFF_M * 100)) +
            tr(", no straight escape ahead", ", pas d'échappement droit devant")
        val current = path[index]
        val dx = current.b.x - current.a.x; val dy = current.b.y - current.a.y
        val l2 = dx * dx + dy * dy
        // Interrupted edge truncated at the projection of the start, then the rest: (edge, start parameter).
        fun entryAt(t0: Double, offset: Double): Pair<Int, Double>? {
            var i = index; var t = t0; var left = offset
            while (i < path.size) {
                val e = path[i]
                val rest = (1 - t) * e.meters
                if (left < rest - 0.02) return i to (t + if (e.meters > 0) left / e.meters else 0.0)
                left -= rest; i++; t = 0.0
            }
            return null
        }
        var failure: IllegalArgumentException? = null
        // Resume at [offset] beyond the projection (parameter [t0] of the interrupted edge) of [backOff] (or the current
        // pose), with its cost; null if the link fails or does not pass the [strict] checks.
        fun candidate(backOff: Point?, t0: Double, offset: Double, strict: Boolean): Pair<DriftPlan, Double>? {
            val (entryIndex, t) = entryAt(t0, skipM + offset) ?: return null
            val start = backOff ?: position
            val e = path[entryIndex]
            val entry = Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t)
            val link = try { RobotRoute.departure(routes, physical, start, headingDegrees, entry, headingOf(e)) }
                catch (error: IllegalArgumentException) { failure = error; return null }
            // Chassis overlapping an edge: never a pivot or a link that does not start with the escape.
            if (backOff == null && !inside && link.firstOrNull()?.escape != true) return null
            // Strict: also along the pass axis when it lies near the outline, rather than nose first into the border.
            if (strict && !(ObstacleBypass.followTolerant(routes, link, start, headingDegrees, headingOf(e)) &&
                    ObstacleBypass.arrivesAligned(routes, link, headingOf(e)) && ahead(routes, path, entryIndex, entry))) return null
            val rest = listOf(e.copy(a = entry)) + path.drop(entryIndex + 1)
            val restOrigins = (entryIndex until path.size).map { origins?.get(it) ?: it }
            val edges = link + rest
            try { RobotRoute.requireOutsideBaseZone(routes, edges) } catch (error: IllegalArgumentException) { failure = error; return null }
            val skipped = ((index until entryIndex).sumOf { if (path[it].cutting) path[it].meters * (if (it == index) 1 - t0 else 1.0) else 0.0 } +
                if (e.cutting) (if (entryIndex == index) t - t0 else t) * e.meters else 0.0).coerceAtLeast(0.0)
            val from = Point(current.a.x + dx * t0, current.a.y + dy * t0)
            return DriftPlan(backOff, edges, link.map { -1 } + restOrigins, link.sumOf { it.meters }, skipped) to
                rejoinSeconds(path, index, from, entryIndex, entry, link, headingDegrees, skipped)
        }
        for (strict in listOf(true, false)) for (backOff in starts) {
            val start = backOff ?: position
            val t0 = if (l2 < 1e-12) 1.0 else (((start.x - current.a.x) * dx + (start.y - current.a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val (near, far) = ENTRY_OFFSETS_M.partition { it <= REJOIN_WINDOW_M + 1e-9 }
            near.mapNotNull { candidate(backOff, t0, it, strict) }.minByOrNull { it.second }?.let { return it.first }
            far.firstNotNullOfOrNull { candidate(backOff, t0, it, strict) }?.let { return it.first }
        }
        if (afterContact && starts.isEmpty()) throw IllegalArgumentException(tr("Back-off impossible after the border contact: trail missing or chassis blocked behind",
            "Recul impossible après le contact en bordure : trace absente ou châssis bloqué derrière"))
        if (!inside && starts.size == 1) throw IllegalArgumentException(noStart() + (failure?.message?.let { " ($it)" } ?: ""))
        throw IllegalArgumentException(tr("No safe link to rejoin the route", "Aucun raccord sûr pour rejoindre le trajet") + (failure?.message?.let { " ($it)" } ?: ""))
    }
}
