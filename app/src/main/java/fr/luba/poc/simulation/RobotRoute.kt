package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*

/** [RETURN_TO_BASE]: local route up to the approach point in front of the base, then docking handed over to the
 * official Mammotion return (the only use of native navigation, as for every docking of a route). */
enum class RouteIntent { FOLLOW, RETURN_TO_BASE, LEAVE_BASE;
    val returnsToBase: Boolean get() = this == RETURN_TO_BASE
}

enum class RoutePhase { FOLLOWING, RETURNING, ALIGNING, DOCKING, CHARGING, RESUMING, COMPLETE, STOPPED }

data class RobotRouteUi(
    val phase: RoutePhase = RoutePhase.STOPPED, val rechargeCycles: Int = 0,
    val heightMm: Int = 60, val returnPercent: Int = 20, val resumePercent: Int = 90,
    /** Return threshold in force if it differs from the low one: floor of a mowing end below the return threshold ([FinishPolicy]). */
    val lowWatch: Int? = null,
    /** While charging: departure level if below the resume threshold (short remainder, [FinishPolicy]). */
    val chargeTarget: Int? = null,
    val running: Boolean = false, val started: Boolean = false,
    val status: String = tr("BLE route not started", "Trajet BLE non démarré"), val index: Int = 0,
    val count: Int = 0, val planned: List<Point> = emptyList(), val trail: List<Point> = emptyList(),
    /**
     * Mowing: share of the cutting metres already mowed over the whole mission (resumes included), 1 once the stripes
     * are finished. null for a route without cutting (recharge, survey, move): progress then follows the edges.
     */
    val cutProgress: Double? = null,
    /** Mowing passes of decreasing height ([fr.luba.poc.map.CutPasses]); empty for a single pass. */
    val cutHeights: List<Int> = emptyList(),
) {
    val showSupervision: Boolean get() = running || started || status.startsWith(tr("Start refused", "Départ refusé"))
}

/** Simulated time and battery are never used to command the robot. */
object RobotRoute {
    fun requireExecutable(preview: Preview) {
        require(!preview.localOnly) { tr("Experimental plan reserved for simulation", "Plan expérimental réservé à la simulation") }
    }
    fun cuttingAllowed(preview: Preview, requested: Boolean): Boolean = requested && !preview.mode.isSurvey
    fun actionEdges(engine: MowingSimulation, start: Point, heading: Double, intent: RouteIntent,
                    physical: TrajectoryPlanner? = null): List<SimEdge> = when(intent) {
        RouteIntent.FOLLOW -> edges(engine,start,heading,physical)
        RouteIntent.LEAVE_BASE -> emptyList()
        RouteIntent.RETURN_TO_BASE -> departure(engine.routes,physical,start,heading,engine.baseApproach,(engine.dockEntryHeading+180)%360)
            .also { requireOutsideBaseZone(engine.routes, it) }
    }

    /**
     * Link from the actual robot pose, computed at launch. If the robot stopped inside the reserve of an obstacle
     * (safety margin) where pivoting is forbidden, the plan first includes a straight-line escape to a point where a
     * full pivot is allowed: this segment is checked against the physical obstacles without margin ([physical]), all
     * the rest with the margins.
     */
    /** Explicit refusal if a segment (excluding the front corridor and the escape) enters the base zone. */
    fun requireOutsideBaseZone(routes: TrajectoryPlanner, edges: List<SimEdge>) {
        val path = edges.filterNot { it.escape }
        path.firstOrNull { e -> routes.baseZoneViolation(listOf(e.a,e.b)) != null }?.let { e ->
            throw IllegalArgumentException(tr("Route refused: passes through the base safety zone (%.2f m; %.2f m) → (%.2f m; %.2f m)",
                "Trajet refusé : passage dans la zone de sécurité de la base (%.2f m ; %.2f m) → (%.2f m ; %.2f m)")
                .format(e.a.x,e.a.y,e.b.x,e.b.y))
        }
    }

    fun departure(routes: TrajectoryPlanner, physical: TrajectoryPlanner?, start: Point, heading: Double,
                  target: Point, targetHeading: Double?): List<SimEdge> {
        fun transit(points: List<Point>) = points.zipWithNext().filter { (a,b) -> a.distance(b)>1e-8 }.map { (a,b) -> SimEdge(a,b,false) }
        val failure = try {
            val path = routes.directJoin(routes.between(start,target,heading,targetHeading),heading,targetHeading)
            routes.requirePath(path,heading,targetHeading)
            return transit(path)
        } catch (e: IllegalArgumentException) { e }
        val escape = physical?.let { escapePoint(routes,it,start,heading) } ?: throw failure
        val direction = Math.toDegrees(kotlin.math.atan2(escape.y-start.y,escape.x-start.x))
        val rest = routes.between(escape,target,direction,targetHeading)
        routes.requirePath(rest,direction,targetHeading)
        return listOf(SimEdge(start,escape,false,escape=true)) + transit(rest)
    }

    /** Nearest escape point: full pivot allowed with the margins, straight line clear for the chassis with the
     * physical obstacles, full pivot physically possible at the start.
     * Robot too close to an edge to pivot in place (recorded outline = chassis side): first straight ahead along its
     * axis [heading], without pivot at the start, chassis checked during the translation. */
    fun escapePoint(routes: TrajectoryPlanner, physical: TrajectoryPlanner, start: Point, heading: Double? = null): Point? {
        if (heading != null && heading.isFinite()) {
            val rad = Math.toRadians(heading)
            for (distance in listOf(0.3,0.5,0.75,1.0,1.5,2.0)) {
                val p = Point(start.x+distance*kotlin.math.cos(rad),start.y+distance*kotlin.math.sin(rad))
                if (!routes.safe(p,p) || !routes.supportsPivot(p)) continue
                // The chassis may already overlap the edge: allowed if the translation only moves it away ([TrajectoryPlanner.leavesStraight]).
                if (!physical.leavesStraight(start,p,heading)) break
                return p
            }
        }
        if (!physical.supportsPivot(start)) return null
        for (distance in listOf(0.3,0.5,0.75,1.0,1.5,2.0)) for (step in 0 until 24) {
            val direction = step*15.0
            val rad = Math.toRadians(direction)
            val p = Point(start.x+distance*kotlin.math.cos(rad),start.y+distance*kotlin.math.sin(rad))
            if (!routes.safe(p,p) || !routes.supportsPivot(p) || !physical.safe(start,p)) continue
            if (physical.motionCollision(fr.luba.poc.map.RobotPose(start,direction),fr.luba.poc.map.RobotPose(p,direction)) != null) continue
            return p
        }
        return null
    }

    /**
     * Height passes: range [from, end) followed at the same height, and that height. An edge without height
     * (departure, transit, bypass) keeps [current]; without cutting, the whole route stays at [current].
     */
    fun heightRange(path: List<SimEdge>, from: Int, current: Int, cutting: Boolean): Pair<Int, Int> {
        require(from in path.indices)
        val height = if (cutting) path[from].heightMm ?: current else current
        var end = from + 1
        while (end < path.size && (!cutting || (path[end].heightMm ?: height) == height)) end++
        return end to height
    }

    /** A return triggered at the end of a segment must not restart its blades. */
    fun resumeIndex(edges: List<SimEdge>, index: Int, position: Point, approach: Point): Int {
        require(index in edges.indices)
        val arrival = if (edges[index].b == approach) 0.05 else fr.luba.poc.diagnostics.MapNavFollower.ARRIVAL_M
        return if (position.distance(edges[index].b) <= arrival) index + 1 else index
    }
    fun edges(engine: MowingSimulation, start: Point, heading: Double, physical: TrajectoryPlanner? = null): List<SimEdge> =
        missionEdges(engine,start,heading,physical).edges

    /**
     * Departure link, mowing edges (all of them, or the remaining ones from [resume]) then return in front of the base.
     * The resume truncates the interrupted edge with an overlap ([MissionProgress.OVERLAP_M]).
     */
    fun missionEdges(engine: MowingSimulation, start: Point, heading: Double, physical: TrajectoryPlanner? = null,
                     resume: MissionResume? = null): MissionEdges {
        requireExecutable(engine.preview)
        require(start.x.isFinite() && start.y.isFinite() && heading.isFinite())
        fun transit(points: List<Point>) = points.zipWithNext().filter { (a,b) -> a.distance(b)>1e-8 }
            .map { (a,b) -> SimEdge(a,b,false) }
        fun headingOf(edge: SimEdge) = Math.toDegrees(kotlin.math.atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
        val dockHeading=(engine.dockEntryHeading+180)%360
        val mowing = if (resume == null) engine.edges else MissionProgress.remaining(engine.edges,resume.edge,resume.point,resume.overlap)
        require(mowing.any { it.cutting } || resume == null) { tr("No mowing left: use return to base", "Aucune tonte restante : utiliser le retour à la base") }
        require(mowing.isNotEmpty()) { tr("Empty route", "Trajet vide") }
        val engineFrom = if (resume == null) 0 else engine.edges.size - mowing.size
        val leaving = departure(engine.routes,physical,start,heading,mowing.first().a,headingOf(mowing.first()))
        val back = transit(engine.routes.between(mowing.last().b,engine.baseApproach,headingOf(mowing.last()),dockHeading))
        val rest = mowing + back
        // Link already validated (optional escape included); the rest with the margins, from the end of the link.
        val joined = leaving.filterNot { it.escape }
        val from = joined.firstOrNull()?.a ?: rest.first().a
        val fromHeading = leaving.lastOrNull { it.escape }?.let(::headingOf) ?: heading
        engine.routes.requirePath(listOf(from)+(joined+rest).map { it.b },fromHeading,dockHeading)
        requireOutsideBaseZone(engine.routes, leaving + rest)
        return MissionEdges(leaving + rest, leaving.size, leaving.size + mowing.size, engineFrom)
    }
}

/** Telemetry older than the GATT write never confirms a transition. */
class RouteBladeGate(val label: String, private val enabled: Boolean) {
    companion object {
        /** Transits/resumes reuse indices; the label identifies a single write. */
        fun unique(prefix: String, enabled: Boolean) = RouteBladeGate("${prefix}_${java.util.UUID.randomUUID()}",enabled)
    }
    private var writtenAt: Long? = null
    fun written(completedLabel: String, at: Long) {
        if (completedLabel == label) writtenAt = at
    }
    fun confirmed(deviceAt: Long, bladeBits: Int): Boolean =
        writtenAt?.let { deviceAt > it && bladeBits == if (enabled) 1 else 0 } == true
}
