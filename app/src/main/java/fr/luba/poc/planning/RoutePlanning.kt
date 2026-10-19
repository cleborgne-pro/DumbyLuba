package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import fr.luba.poc.simulation.TrajectoryPlanner

data class CoveragePlan(val preview: Preview, val report: PlanningResult?)

/** Common entry point of local routes: preview, simulation, survey and BLE tracking.
 * Modes designate an objective; links always use TrajectoryPlanner.
 * The engine choice never constitutes an authorisation to run the robot.
 */
object RoutePlanning {
    /** Computation used in production and directly measurable by the benchmarks. */
    internal fun calculate(request: PlanningRequest,mode: CoverageMode = CoverageMode.LINES,
        checkpoint: () -> Unit = {}): PlanningResult = if(mode==CoverageMode.LINES)
        CoverageEngine.planAutomatic(request,checkpoint)
        else CoverageEngine.planPattern(request,mode,checkpoint)
    /** Single production policy: contiguous blocks and validated neighbouring links.
     * The whole coverage is still evaluated before the plan is published.
     */
    fun coverage(request: PlanningRequest, mode: CoverageMode = CoverageMode.LINES,
        checkpoint: () -> Unit = {}, progress: (Int,Int,String) -> Unit = { _,_,_ -> }): CoveragePlan {
        progress(0,1,tr("Computing passes and local links", "Calcul des passes et raccords locaux"))
        val result=calculate(request,mode,checkpoint)
        val preview=requireNotNull(result.selected.preview) { result.selected.diagnostics.joinToString(" ; ") }
        // Only the production facade publishes a usable plan: the model must
        // have validated the whole mission. Research variants stay local.
        // The simulation preparation and the BLE controller still check the
        // segments, actual headings, fresh map, blades and battery reserve.
        val ready=result.selected.valid && result.selected.metrics?.geometricViolations==0
        progress(1,1,tr("Route computed", "Trajet calculé"))
        // Internal links already computed: the preparation revalidates them instead of searching again.
        // plannerLabel is persisted (MissionCheckpoint) and compared: kept untranslated.
        val mission=result.selected.mission
        val transits=mission.filter { !it.cutting }.map { it.path }
        val links=if(ready && transits.size==mission.count { it.cutting }+1 &&
            mission.filter { it.cutting }.map { it.path }==preview.cuttingPaths) transits.drop(1).dropLast(1) else null
        return CoveragePlan(preview.copy(localOnly=!ready,plannerLabel="Automatique",links=links),result)
    }

    /** Scientific comparisons only, never called by the interface. */
    internal fun researchCoverage(request: PlanningRequest, mode: CoverageMode = CoverageMode.LINES,
        strategy: PlannerStrategy = PlannerStrategy.ADVANCED, checkpoint: () -> Unit = {},
        progress: (Int,Int,String) -> Unit = { _,_,_ -> }): CoveragePlan {
        val snapshot=request.snapshot()
        if(mode==CoverageMode.LINES && strategy!=PlannerStrategy.BASELINE) {
            val report=CoverageEngine.plan(snapshot,strategy,checkpoint)
            val preview=requireNotNull(report.selected.preview) { report.selected.diagnostics.joinToString(" ; ") }
            return CoveragePlan(preview.copy(plannerLabel=label(strategy)),report)
        }
        val config=snapshot.config
        val zones=snapshot.zoneIds.map { id -> requireNotNull(snapshot.map.shapes.singleOrNull {
            it.id==id && it.kind==ShapeKind.ZONE
        }) { tr("Missing zone: $id", "Zone absente : $id") } }
        val preview=MultiZonePlanner.plan(zones,
            snapshot.map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },
            config.spacingM,config.marginM,mode,config.borders,config.borderPasses,config.noGoPasses,
            obstacles=snapshot.obstacles.map { fr.luba.poc.obstacles.ObstacleDisc(it.center.x,it.center.y,
                it.radiusM+snapshot.robot.turnClearanceM) },robot=snapshot.robot,
            recordedBoundary=config.boundary==BoundaryMeaning.RECORDED_TRACE,basePoint=snapshot.start,
            bordersFirst=config.bordersFirst,nearbyNoGo=config.nearbyNoGo,
            progress={ done,total,name -> checkpoint(); progress(done,total,name) },outerOffsetM=config.outerOffsetM)
        return CoveragePlan(preview.copy(plannerLabel=if(mode.isSurvey) "Relevé · ${mode.label}"
            else if(mode==CoverageMode.CHECKERBOARD) "Référence · damier" else label(PlannerStrategy.BASELINE)),null)
    }

    /** Planner label stored in [Preview.plannerLabel] (persisted): kept untranslated. */
    fun label(strategy: PlannerStrategy): String = when(strategy) {
        PlannerStrategy.BASELINE -> "Référence"
        PlannerStrategy.OPTIMIZED -> "Optimisé"
        PlannerStrategy.ADVANCED -> "Avancé"
        PlannerStrategy.EXPERIMENTAL_BCD -> "BCD"
        PlannerStrategy.EXPERIMENTAL_JOINT -> "Orientation et ordre"
        PlannerStrategy.EXPERIMENTAL_LNS -> "Recherche locale"
    }

    fun point(map: RobotMap,start: Point,target: Point,obstacles: List<LocalObstacle>) =
        TrajectoryPlanner.pointPlan(map,start,target,obstacles)

    /** Same navigation and same envelope validation for all local missions.
     * Reserves stay explicit: the supervised move keeps its 0.95 m margin,
     * surveys and mowing keep their existing reserves.
     */
    fun navigation(map: RobotMap,margin: Double,holes: List<List<Point>>,
        accesses: List<List<Point>> = emptyList(),base: MapBase? = null,
        robot: RobotEnvelope = RobotEnvelope.LUBA2,recordedBoundary: Boolean = false,
        limitCenter: Point? = null,limitRadius: Double? = null,curveSegments: Int = 8,
        avoidAreas: List<List<Point>> = emptyList()): TrajectoryPlanner {
        if(base!=null) {
            require(limitCenter==null && limitRadius==null && curveSegments==8)
            return TrajectoryPlanner.withDock(map,margin,holes,accesses,base.point,
                base.entryHeadingDegrees,base.approachDistanceM,robot,recordedBoundary,avoidAreas)
        }
        return TrajectoryPlanner(map,margin,holes,accesses,robot,recordedBoundary,
            limitCenter,limitRadius,curveSegments,avoidAreas=avoidAreas)
    }

    /**
     * Station route (leave the base, come back): checked link between the base approach point and the robot, or
     * failing that a mapped zone. The wrapping simulation provides the departure and the return through the docking
     * corridor. A charging robot (or one inside the base safety zone) is not a valid end: the link to it would use
     * the docking corridor, then refused as a route; a zone is used as the reference instead.
     */
    fun station(routes: TrajectoryPlanner, map: RobotMap, base: MapBase, robot: Point, config: SimConfig): MowingSimulation {
        val axis=Math.toRadians(requireNotNull(base.entryHeadingDegrees) { tr("Unknown base axis: observe the robot while charging", "Axe de base inconnu : observer le robot en charge") })
        val approach=Point(base.point.x-kotlin.math.cos(axis)*base.approachDistanceM,base.point.y-kotlin.math.sin(axis)*base.approachDistanceM)
        val factory=org.locationtech.jts.geom.GeometryFactory()
        val candidates=listOf(robot)+map.shapes.filter { it.kind==ShapeKind.ZONE && it.points.size>=3 }.map { zone ->
            val poly=factory.createPolygon((zone.points+zone.points.first()).map { org.locationtech.jts.geom.Coordinate(it.x,it.y) }.toTypedArray())
            val p=poly.interiorPoint; Point(p.x,p.y)
        }
        val path=candidates.asSequence().filter { it.distance(approach)>0.2 && routes.safe(it,it) }.mapNotNull { candidate ->
            runCatching { routes.between(approach,candidate) }.getOrNull()
        }.firstOrNull() ?: error(tr("No safe access to the base: check its position and axis", "Aucun accès sûr à la base : vérifier sa position et son axe"))
        return MowingSimulation(routes,base.point,Preview(listOf(path),path.zipWithNext().sumOf { (a,b)->a.distance(b) },0,0),map,config)
    }

    /** Navigation of a mowing mission or station route: no-go zones and learnt obstacles
     * (exclusion radius) as holes, local accesses and oriented base. Shared by the map and
     * debug mowing so that simulation and actual execution prepare the same route.
     * Obstacles near the docking corridor: margin adjusted by [DockClearance] (pivot at the approach point).
     */
    fun mission(map: RobotMap,obstacles: List<LocalObstacle>,accesses: List<List<Point>>,base: MapBase,
        recordedBoundary: Boolean = true,avoidAreas: List<List<Point>> = emptyList()): TrajectoryPlanner {
        DockClearance.requireReachable(obstacles,base)
        val obstacleHoles=DockClearance.fit(obstacles,base).map { obstacle ->
            (0 until 64).map { i ->
                val a=i*Math.PI*2/64
                val radius=obstacle.exclusionRadiusM/kotlin.math.cos(Math.PI/64)
                Point(obstacle.x+radius*kotlin.math.cos(a),obstacle.y+radius*kotlin.math.sin(a))
            }
        }
        return navigation(map,RobotEnvelope.LUBA2.halfWidthM+0.05,
            map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }+obstacleHoles,
            accesses,base=base,recordedBoundary=recordedBoundary,avoidAreas=avoidAreas)
    }

    /** Physical navigation for escapes and back-offs: no-go zones and learnt obstacles at their physical radius
     * (without safety margin), chassis margin. */
    /**
     * Re-preparation of an interrupted task ([checkpoint]) from its preview, on the current map, base, accesses and
     * obstacles: [mission] navigation with the presumed obstacles and avoided areas of the start
     * ([fr.luba.poc.simulation.MissionAvoidance.forResume]), saved continuous blade zones. Same plan as the start for
     * the map, the supervised tests (border, painted zone) and the surveys; the fingerprint checks it afterwards.
     * Base of the start if [base] is the same station ([fr.luba.poc.simulation.MissionCheckpoint.planningBase]): the
     * station observed when docking again no longer changes the resumed plan.
     */
    fun resume(checkpoint: fr.luba.poc.simulation.MissionCheckpoint, map: RobotMap, base: MapBase, accesses: List<List<Point>>,
        registered: List<LocalObstacle>, difficulties: fr.luba.poc.difficulty.DifficultyUi, config: SimConfig,
        recordedBoundary: Boolean = true, progress: (String) -> Unit = {}): MowingSimulation {
        val avoidance = fr.luba.poc.simulation.MissionAvoidance.forResume(checkpoint, difficulties, registered)
        val planningBase = checkpoint.planningBase(base)
        val routes = mission(map, avoidance.planningObstacles(registered), accesses, planningBase, recordedBoundary, avoidance.avoidAreas)
        return MowingSimulation(routes, planningBase.point, checkpoint.preview, map, config.copy(returnPercent = checkpoint.returnPercent.toDouble(),
            resumePercent = checkpoint.resumePercent.toDouble(), baseEntryHeadingDegrees = planningBase.entryHeadingDegrees,
            baseApproachMeters = planningBase.approachDistanceM), bladeZones = checkpoint.bladeZones, onPreparationProgress = progress)
    }

    fun physical(map: RobotMap,obstacles: List<LocalObstacle>,recordedBoundary: Boolean = true): TrajectoryPlanner =
        navigation(map,RobotEnvelope.LUBA2.halfWidthM+0.05,
            map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points } +
                obstacles.map { fr.luba.poc.simulation.ObstacleBypass.circle(Point(it.x,it.y),it.radiusM) },
            recordedBoundary=recordedBoundary)

    /**
     * Start of the computation of a mowing, a survey or a debug mowing: as close as possible to where the robot is.
     * Base if the robot is charging, if its position is unknown or if it is inside the base safety zone (it leaves it
     * through the docking corridor). Robot stopped inside a reserve (border, no-go zone, obstacle): nearest free point
     * where it can pivot; the actual escape is recomputed at launch from its exact pose.
     */
    fun missionStart(routes: TrajectoryPlanner, robot: Point?, docked: Boolean, base: Point?): Point? {
        if(robot==null || !robot.x.isFinite() || !robot.y.isFinite() || docked) return base
        if(base!=null) routes.baseSafety?.let { (station,_,radius) -> if(robot.distance(station)<=radius+0.05) return base }
        return nearestUsable(routes,robot) ?: base ?: robot
    }

    /**
     * Nearest free point to [p] (2 m at most) where the robot can stand and pivot; null if none. Used for a robot
     * stopped inside a reserve and for the start point chosen or saved per zone.
     */
    fun nearestUsable(routes: TrajectoryPlanner, p: Point): Point? {
        if(!p.x.isFinite() || !p.y.isFinite()) return null
        fun usable(q: Point) = routes.safe(q,q) && routes.supportsPivot(q)
        if(usable(p)) return p
        for(distance in listOf(0.1,0.2,0.3,0.45,0.6,0.8,1.0,1.5,2.0)) {
            (0 until 36).map { step -> val a=Math.toRadians(step*10.0)
                Point(p.x+distance*kotlin.math.cos(a),p.y+distance*kotlin.math.sin(a)) }
                .firstOrNull(::usable)?.let { return it }
        }
        return null
    }

    /** Single assembly of passes and oriented links. The mission boundaries
     * (station or current position) are explicit; no optimisation on read.
     * A missing link invalidates the whole mission, without a fallback direct segment.
     */
    fun assemble(paths: List<List<Point>>,
        between: (Point,Point,Double?,Double?) -> List<Point>?,
        start: Point? = null, startHeading: Double? = null,
        end: Point? = null, endHeading: Double? = null,
        progress: (Int,Int) -> Unit = { _,_ -> }): List<MissionLeg>? {
        if(paths.isEmpty() || paths.any { it.size<2 || length(it)<=1e-8 }) return null
        var position=start
        var incoming=startHeading
        val result=mutableListOf<MissionLeg>()
        paths.forEachIndexed { index,path ->
            progress(index+1,paths.size)
            val outgoing=path.zipWithNext().first { (a,b) -> a.distance(b)>1e-9 }.let { heading(it.first,it.second) }
            position?.let { result.add(MissionLeg(between(it,path.first(),incoming,outgoing) ?: return null,false)) }
            result.add(MissionLeg(path,true))
            position=path.last()
            incoming=path.zipWithNext().last { (a,b) -> a.distance(b)>1e-9 }.let { heading(it.first,it.second) }
        }
        end?.let { result.add(MissionLeg(between(position!!,it,incoming,endHeading) ?: return null,false)) }
        return result
    }
}
