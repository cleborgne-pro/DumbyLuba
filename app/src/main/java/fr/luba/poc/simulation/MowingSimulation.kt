package fr.luba.poc.simulation

import fr.luba.poc.Clock
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.planning.RoutePlanning
import java.util.UUID
import kotlin.math.*

/** [cutting]: planned mowing pass; [bladesOn]: blades running on the edge (passes, and links internal to a zone, see
 * [BladeContinuity]). [heightMm]: cutting height of the successive pass the edge belongs to
 * ([fr.luba.poc.map.CutPasses]); null: height of the task, or that of the previous range. */
data class SimEdge(val a: Point, val b: Point, val cutting: Boolean, val escape: Boolean = false, val bladesOn: Boolean = cutting,
                   val heightMm: Int? = null) { val meters get()=a.distance(b) }
data class SimConfig(val initialProgress: Double=0.0, val initialBattery: Double=100.0, val autonomyMinutes: Double=60.0,
                     val speedMps: Double=0.3, val returnPercent: Double=20.0, val resumePercent: Double=90.0, val chargeMinutes: Double=30.0, val turnDegreesPerSecond: Double=45.0,
                     val baseEntryHeadingDegrees: Double?=null, val baseApproachMeters: Double=1.5, val dockingSpeedMps: Double=0.1, val instantCharging: Boolean=false,
                     /** Battery aimed for on arrival by the dynamic return ([ReturnReserve]). */
                     val returnReservePercent: Double=ReturnReserve.DEFAULT_PERCENT.toDouble())
enum class SimPhase(private val labelEn: String, private val labelFr: String) {
    LEAVING_DOCK("Leaving the base · slow forward", "Sortie de base · marche avant lente"), DEPARTING("Start / resume", "Départ / reprise"),
    MOWING("Mowing", "Tonte"), RETURNING("Return to base", "Retour à la base"), ALIGNING("Alignment before recharge", "Alignement avant recharge"),
    DOCKING("Recharge return · reversing", "Retour recharge · marche arrière"), CHARGING("Recharge", "Recharge"),
    COMPLETE("Mowing finished · at the base", "Tonte terminée · à la base"), BLOCKED("Simulation blocked", "Simulation bloquée");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}
data class SimState(val position: Point, val battery: Double, val phase: SimPhase, val edge: Int=0, val offset: Double=0.0,
                    val covered: Double=0.0, val elapsed: Double=0.0, val cycles: Int=0, val completedReturn: Boolean=false,
                    val transit: List<Point> = emptyList(), val transitIndex: Int=0, val transitOffset: Double=0.0,
                    val log: List<String> = emptyList(), val trail: List<Point> = emptyList(), val heading: Double=0.0,
                    val resumeTransit: List<Point> = emptyList(), val signalSamples: List<SignalSample> = emptyList(), val surveyId: String = "", val surveyStartedAt: Long = 0,
                    /** End of mowing below the return threshold, without recharge ([FinishPolicy]). */
                    val finishing: Boolean = false,
                    /** While charging: points needed to finish from the base ([FinishPolicy]), computed once per recharge. */
                    val chargeNeed: Double? = null)

/** Deterministic local engine. No link with RobotBle or the commands. */
class MowingSimulation private constructor(val routes: TrajectoryPlanner, val base: Point, val preview: Preview, val map: RobotMap, val config: SimConfig, prepared: MowingSimulation?, bladeZones: List<List<Point>>?, onPreparationProgress:(String)->Unit = {}) {
    /** [bladeZones]: zones where the links keep the blades running; by default the zones requested by the preview
     * ([Preview.zoneIds]) in [map]. */
    constructor(routes: TrajectoryPlanner, base: Point, preview: Preview, map: RobotMap, config: SimConfig,
                bladeZones: List<List<Point>>? = null, onPreparationProgress:(String)->Unit = {}):
        this(routes,base,preview,map,config,null,bladeZones,onPreparationProgress)
    /** Continuous-blade zones requested at preparation (null: those of the preview): the resume prepares again with the same. */
    val bladeZones: List<List<Point>>? = prepared?.bladeZones ?: bladeZones
    val baseApproach: Point
    /** Direction of travel towards the station; the robot's nose points the opposite way while reversing. */
    val dockEntryHeading: Double
    private fun edgeHeading(edge: SimEdge) = Math.toDegrees(atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
    private fun calculateDeparture(target: Point, targetHeading: Double? = null): List<Point> =
        (listOf(base) + routes.between(baseApproach,target, (dockEntryHeading+180)%360, targetHeading)).also {
            routes.requirePath(it, initialHeading=(dockEntryHeading+180)%360, finalHeading=targetHeading)
        }
    private fun calculateReturn(start: Point, initialHeading: Double? = null): List<Point> =
        routes.between(start,baseApproach, initialHeading, (dockEntryHeading+180)%360).also {
            routes.requirePath(it, initialHeading=initialHeading, finalHeading=(dockEntryHeading+180)%360)
        }
    val edges: List<SimEdge>
    val total: Double
    /** Slowed stretches of the mission ([AdaptiveSpeed]), driven slower here as by the tracking; none when switched off. */
    val speedCaps: List<SpeedCap> by lazy { if (SmartFeatures.adaptiveSpeed) routes.speedZones.caps(edges) else emptyList() }
    private data class Checkpoint(val edge: Int, val departure: List<Point>, val returning: List<Point>)
    private val checkpoints: List<Checkpoint>
    private val nextCheckpoint: IntArray
    private val departureShortcuts: List<List<List<Point>?>>
    private val returnShortcuts: List<List<List<Point>?>>
    init {
        require(config.autonomyMinutes>0 && config.speedMps>0 && config.chargeMinutes>0 && config.turnDegreesPerSecond>0)
        if (prepared != null) {
            require(config.baseEntryHeadingDegrees == prepared.config.baseEntryHeadingDegrees &&
                config.baseApproachMeters == prepared.config.baseApproachMeters) { tr("Recompute the route after changing the base", "Recalculez le trajet après une modification de la base") }
            edges=prepared.edges; total=prepared.total
            baseApproach=prepared.baseApproach; dockEntryHeading=prepared.dockEntryHeading
            checkpoints=prepared.checkpoints; nextCheckpoint=prepared.nextCheckpoint
            departureShortcuts=prepared.departureShortcuts; returnShortcuts=prepared.returnShortcuts
        } else {
            // Links from the computation: reused only if they pass the same surface, chassis
            // and heading checks; otherwise full search.
            val planned=preview.links.orEmpty().filter { it.size>=2 }.groupBy { it.first() to it.last() }
            fun link(a:Point,b:Point,incoming:Double?,outgoing:Double?):List<Point> =
                planned[a to b]?.firstOrNull { path ->
                    routes.pathSafe(path,incoming,outgoing)
                } ?: routes.between(a,b,incoming,outgoing)
            val mission=requireNotNull(RoutePlanning.assemble(
                preview.cuttingPaths.filter { it.size>=2 },::link,
                progress={ index,total -> onPreparationProgress(tr("Checking the links · $index/$total", "Vérification des liaisons · $index/$total")) })) {
                tr("First create a preview with non-degenerate passes", "Créez d'abord un aperçu avec des passes non dégénérées")
            }
            // Survey: blades never running, links unchanged.
            val blades=if(preview.mode.isSurvey) mission.map { it.cutting } else BladeContinuity.legs(mission,
                bladeZones ?: map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in preview.zoneIds }.map { it.points })
            // Height passes: each cutting path carries the height of its pass, and so does the link preceding it
            // (height change while stopped, before entering it).
            val legHeights=if(preview.pathHeights.isEmpty()) List(mission.size) { null } else {
                val cuttingIndex=mission.runningFold(-1) { k,leg -> if(leg.cutting) k+1 else k }.drop(1)
                mission.indices.map { index ->
                    val path=if(mission[index].cutting) cuttingIndex[index] else cuttingIndex[index]+1
                    preview.pathHeights.getOrNull(path.coerceAtMost(preview.pathHeights.lastIndex))
                }
            }
            val all=mission.flatMapIndexed { index,leg -> leg.path.zipWithNext().mapNotNull { (a,b) ->
                require(routes.safe(a,b)) { tr("Route outside the safe surface", "Trajet hors de la surface sûre") }
                if(a.distance(b)>1e-8) SimEdge(a,b,leg.cutting,bladesOn=blades[index],heightMm=legHeights[index]) else null
            } }
            require(all.isNotEmpty()) { tr("First create a preview", "Créez d'abord un aperçu") }
            routes.requirePath(listOf(all.first().a) + all.map { it.b })
            routes.baseZoneViolation(listOf(all.first().a) + all.map { it.b })?.let { (a,b) ->
                throw IllegalArgumentException(tr("Route inside the base safety zone: (%.2f; %.2f) → (%.2f; %.2f)",
                    "Trajet dans la zone de sécurité de la base : (%.2f ; %.2f) → (%.2f ; %.2f)").format(a.x,a.y,b.x,b.y))
            }
            edges=all; total=edges.filter { it.cutting }.sumOf { it.meters }
            require(config.baseApproachMeters.isFinite() && config.baseApproachMeters in 1.0..1.5 &&
                config.dockingSpeedMps.isFinite() && config.dockingSpeedMps>0)
            config.baseEntryHeadingDegrees?.let { require(it.isFinite() && it in 0.0..360.0) }
            val candidates=config.baseEntryHeadingDegrees?.let { listOf(it) } ?: (
                routes.between(base,edges.first().a,null,edgeHeading(edges.first())).zipWithNext().filter { (a,b)->a.distance(b)>1e-7 }.flatMap { (a,b)->
                    val heading=(Math.toDegrees(atan2(a.y-b.y,a.x-b.x))+360.0)%360.0
                    listOf(heading,(heading+180.0)%360.0)
                } + (0 until 8).map { it*45.0 })
            val approach=candidates.firstNotNullOfOrNull { heading ->
                val rad=Math.toRadians(heading)
                val p=Point(base.x-cos(rad)*config.baseApproachMeters,base.y-sin(rad)*config.baseApproachMeters)
                if(!routes.maneuverSafe(base,p) || !routes.safe(p,p)) null else try { routes.between(p,edges.first().a,(heading+180)%360,edgeHeading(edges.first())); heading to p }
                    catch(_:IllegalArgumentException) { null }
            }
            require(approach!=null) { tr("Base entry axis outside the reachable passage: turn the base or adjust its access.",
                "Axe d’entrée de la base hors du passage accessible : tournez la base ou ajustez son accès.") }
            dockEntryHeading=approach.first; baseApproach=approach.second
            require(routes.maneuverSafe(baseApproach,base)) { tr("Reversing outside the safe surface", "Recul hors de la surface sûre") }
            require(routes.motionCollision(RobotPose(baseApproach,(dockEntryHeading+180)%360),RobotPose(base,(dockEntryHeading+180)%360)) == null) { tr("Chassis collision while reversing towards the base", "Collision du châssis pendant le recul vers la base") }
            // Station links are computed and checked with the route, never during its
            // playback. Spacing the points avoids one computation per outline segment.
            val indices=mutableListOf(0)
            var meters=0.0
            edges.forEachIndexed { index, edge ->
                meters += edge.meters
                if (meters >= 5.0) { indices.add(index+1); meters=0.0 }
            }
            if (indices.last()!=edges.size) indices.add(edges.size)
            val stationPaths=mutableListOf<Checkpoint>()
            fun reuseStationPath(point:Point,heading:Double?,departure:Boolean):List<Point>? {
                val paths=(stationPaths.takeLast(3)+stationPaths.take(1)).distinct().map {
                    if(departure) it.departure else it.returning
                }
                val candidates=paths.flatMap { path -> path.indices.filter { !departure || it>=1 }.map { k ->
                    if(departure) path.take(k+1)+point else listOf(point)+path.drop(k)
                } }.sortedBy { path -> path.zipWithNext().sumOf { (a,b)->a.distance(b) } }
                return candidates.firstOrNull { path ->
                    val anchor=if(departure) path[path.size-2] else path[1]
                    if(!routes.supportsShortcut(point,point,anchor)) false else (if(routes.pathSafe(path,
                            initialHeading=if(departure) (dockEntryHeading+180)%360 else heading,
                            finalHeading=if(departure) heading else (dockEntryHeading+180)%360)) true else false)
                }?.distinctConsecutive()
            }
            fun returnViaCheckpoint(index:Int,heading:Double?):List<Point>? = stationPaths.asReversed().firstNotNullOfOrNull { checkpoint ->
                val path=(listOf(edges[index-1].b)+(checkpoint.edge until index).reversed().map { edges[it].a }+
                    checkpoint.returning).distinctConsecutive()
                (if(routes.pathSafe(path,heading,(dockEntryHeading+180)%360)) path else null)
            }
            checkpoints=indices.mapNotNull { index ->
                onPreparationProgress(tr("Preparing the recharges · ${indices.indexOf(index)+1}/${indices.size}", "Préparation des recharges · ${indices.indexOf(index)+1}/${indices.size}"))
                val point=if(index==0) edges.first().a else edges[index-1].b
                if(index!=0 && index!=edges.size && !routes.supportsPivot(point)) return@mapNotNull null
                val incoming=edges.getOrNull(index-1)?.let(::edgeHeading)
                val outgoing=edges.getOrNull(index)?.let(::edgeHeading)
                try {
                    val departure=if(index==edges.size) emptyList() else
                        reuseStationPath(point,outgoing,true) ?: calculateDeparture(point,targetHeading=outgoing)
                    val returning=if(index==0) departure.drop(1).reversed() else
                        reuseStationPath(point,incoming,false) ?: returnViaCheckpoint(index,incoming) ?:
                        calculateReturn(point,initialHeading=incoming)
                    Checkpoint(index,departure,returning).also { stationPaths.add(it) }
                } catch(e: IllegalArgumentException) {
                    // A border vertex does not necessarily allow a pivot towards the station.
                    // Continue on the validated route up to the next possible link.
                    if(index==0 || index==edges.size) throw e else null
                }
            }
            nextCheckpoint=IntArray(edges.size+1)
            var checkpoint=0
            for(index in nextCheckpoint.indices) {
                while(checkpoints[checkpoint].edge<index) checkpoint++
                nextCheckpoint[index]=checkpoint
            }
            fun stationCandidates(departure:Boolean):List<List<Point>> = checkpoints.flatMap { checkpoint ->
                val path=if(departure) checkpoint.departure else checkpoint.returning
                path.indices.filter { !departure || it>=1 }.map { index ->
                    if(departure) path.take(index+1) else path.drop(index)
                }
            }.groupBy { if(departure) it.last() else it.first() }.values.map { paths ->
                paths.minBy { path -> path.zipWithNext().sumOf { (a,b)->a.distance(b) } }
            }
            // Complete the reused paths with direct accesses spread over the area.
            // A given region is searched only once, even if the zigzags come back to it often.
            val directStations=checkpoints.filter { it.edge<edges.size && routes.supportsPivot(edges[it.edge].a) }
                .distinctBy { floor(edges[it.edge].a.x/2).toInt() to floor(edges[it.edge].a.y/2).toInt() }
                .mapNotNull { checkpoint ->
                    onPreparationProgress(tr("Optimizing direct accesses to the base", "Optimisation des accès directs à la base"))
                    val p=edges[checkpoint.edge].a
                    try { Checkpoint(checkpoint.edge,calculateDeparture(p),calculateReturn(p)) }
                    catch(_:IllegalArgumentException) { null }
                }
            data class StationAccess(val anchor:Point,val path:List<Point>,val meters:Double,val surface:Int=-1)
            fun price(paths:List<List<Point>>,departure:Boolean):List<StationAccess> = paths.map { path ->
                StationAccess(if(departure) path.last() else path.first(),path,path.zipWithNext().sumOf { (a,b)->a.distance(b) })
            }.groupBy { it.anchor }.values.map { it.minBy { access -> access.meters } }
                .filter { routes.supportsShortcutAnchor(it.anchor) }
                .map { it.copy(surface=routes.localSurfaceId(it.anchor)) }
            // Accesses grouped by component, in their original order: the other components
            // would fail anyway (segment or triangle not covered).
            fun bySurface(paths:List<StationAccess>) = paths.groupBy { it.surface }
            fun reachable(groups:Map<Int,List<StationAccess>>,point:Point):List<StationAccess> =
                routes.localSurfaceId(point).let { if(it<0) emptyList() else groups[it].orEmpty() }
            val departures=price(stationCandidates(true)+directStations.flatMap { it.departure.indices.drop(1).map { k -> it.departure.take(k+1) } },true)
            val returns=price(stationCandidates(false)+directStations.flatMap { it.returning.indices.map { k -> it.returning.drop(k) } },false)
            fun shortcut(edge:SimEdge,groups:Map<Int,List<StationAccess>>):List<Point>? {
                if(!routes.supportsShortcutSegment(edge.a,edge.b)) return null
                val middle=Point((edge.a.x+edge.b.x)/2,(edge.a.y+edge.b.y)/2)
                return firstInOrder(reachable(groups,edge.a),{ it.meters+middle.distance(it.anchor) }) {
                    if(routes.supportsShortcut(edge.a,edge.b,it.anchor)) it.path else null
                }
            }
            fun parts(edge:SimEdge,paths:List<StationAccess>,groups:Map<Int,List<StationAccess>>,departure:Boolean):List<List<Point>?> {
                // A link checked over the whole pass also covers each of its portions.
                // Keep the fine splitting only when an obstacle imposes different accesses.
                if (edge.meters > 0.5) {
                    val first=paths.minByOrNull { it.meters+edge.a.distance(it.anchor) }
                    val last=paths.minByOrNull { it.meters+edge.b.distance(it.anchor) }
                    if (first != null && first == last &&
                        routes.supportsShortcutSegment(edge.a,edge.b) &&
                        routes.supportsShortcut(edge.a,edge.b,first.anchor)) return listOf(first.path)
                }
                val count=ceil(edge.meters/0.5).toInt().coerceAtLeast(1)
                return (0 until count).map { i ->
                    val portion=edge.copy(a=point(edge,edge.meters*i/count),b=point(edge,edge.meters*(i+1)/count))
                    shortcut(portion,groups) ?: run {
                        // Continue along the pass heading up to a fixed local link.
                        // Check the actual heading, without imposing a full pivot or restarting the graph.
                        val pivot=if(departure) portion.a else portion.b
                        val local=reachable(groups,pivot)
                        val direct=firstInOrder(local,{ it.meters+pivot.distance(it.anchor) }) { access ->
                            if(!routes.supportsLocalLink(pivot,access.anchor)) return@firstInOrder null
                            val path=if(departure) access.path+pivot else listOf(pivot)+access.path
                            (if(routes.pathSafe(path,
                                    initialHeading=if(departure) (dockEntryHeading+180)%360 else edgeHeading(edge),
                                    finalHeading=if(departure) edgeHeading(edge) else (dockEntryHeading+180)%360)) path.distinctConsecutive() else null)
                        }
                        // Leave a border with a small heading change, then
                        // place the full pivot inside the zone. These
                        // links are prepared here, never during the simulation.
                        val directMeters=direct?.zipWithNext()?.sumOf { (a,b)->a.distance(b) } ?: Double.POSITIVE_INFINITY
                        // By the triangle inequality, a detour through an intermediate pivot
                        // cannot beat the best direct access already validated.
                        // Lower bound on the pivot's component: the others give no link.
                        val lowerBound=local.minOfOrNull { it.meters+pivot.distance(it.anchor) } ?: Double.POSITIVE_INFINITY
                        if (direct != null && directMeters <= lowerBound+1e-8) return@run direct
                        val cap=edgeHeading(edge)
                        val sign=if(departure) -1 else 1
                        val stages=listOf(0.6,1.0,1.5,2.0).flatMap { distance ->
                            listOf(5.0,-5.0,10.0,-10.0,15.0,-15.0,30.0,-30.0,60.0,-60.0).mapNotNull { angle ->
                                val radians=Math.toRadians(cap+angle)
                                val stage=Point(pivot.x+sign*distance*cos(radians),pivot.y+sign*distance*sin(radians))
                                stage.takeIf { routes.supportsShortcutAnchor(it) && routes.supportsLocalLink(pivot,it) }
                            }
                        }
                        val candidates=stages.flatMap { stage -> local.map { access ->
                            Triple(stage,access,pivot.distance(stage)+stage.distance(access.anchor)+access.meters)
                        } }.filter { it.third<directMeters-1e-6 }
                        val staged=firstInOrder(candidates,{ it.third }) { (stage,access,_) ->
                            if(!routes.supportsShortcut(stage,stage,access.anchor)) return@firstInOrder null
                            val path=if(departure) access.path+stage+pivot else listOf(pivot,stage)+access.path
                            (if(routes.pathSafe(path,
                                    initialHeading=if(departure) (dockEntryHeading+180)%360 else cap,
                                    finalHeading=if(departure) cap else (dockEntryHeading+180)%360)) path.distinctConsecutive() else null)
                        }
                        staged ?: direct
                    }
                }
            }
            // Independent portions (frozen accesses, deterministic checks): spread over
            // the available cores, identical result whatever the execution order.
            val returnGroups=bySurface(returns); val departureGroups=bySurface(departures)
            val shortcuts=PreparationPool.map(edges.size,{ done ->
                onPreparationProgress(tr("Optimizing returns and departures · $done/${edges.size}", "Optimisation des retours et départs · $done/${edges.size}"))
            }) { index -> parts(edges[index],returns,returnGroups,false) to parts(edges[index],departures,departureGroups,true) }
            returnShortcuts=shortcuts.map { it.first }
            departureShortcuts=shortcuts.map { it.second }
        }
    }
    /** First non-null result in the order of a stable sort by key, without sorting the whole list:
     * same candidates tried in the same order, cost proportional to the actual attempts. */
    private inline fun <T,R:Any> firstInOrder(items: List<T>, key: (T)->Double, attempt: (T)->R?): R? {
        if(items.isEmpty()) return null
        val keys=DoubleArray(items.size) { key(items[it]) }
        val heap=IndexHeap(keys)
        while(true) { val i=heap.pop(); if(i<0) return null; attempt(items[i])?.let { return it } }
    }
    /** New scenario: shares the prepared polylines, without geometry or search. */
    fun withConfig(config: SimConfig)=MowingSimulation(routes,base,preview,map,config,this,null)
    private fun preparedShortcut(paths:List<List<List<Point>?>>,edge:Int,offset:Double):List<Point>? {
        val parts=paths.getOrNull(edge) ?: return null
        val index=floor(offset/edges[edge].meters*parts.size).toInt().coerceIn(0,parts.lastIndex)
        return parts[index]
    }
    private fun departure(edge: Int, offset: Double): List<Point> {
        preparedShortcut(departureShortcuts,edge,offset)?.let { path ->
            return (path+point(edges[edge],offset)).distinctConsecutive()
        }
        val checkpoint=checkpoints.last { it.edge<=edge }
        val path=checkpoint.departure.toMutableList()
        for(index in checkpoint.edge until edge) path.add(edges[index].b)
        if(edge<edges.size) path.add(point(edges[edge],offset))
        return path.distinctConsecutive()
    }
    private fun returnPath(s: SimState): List<Point> {
        preparedShortcut(returnShortcuts,s.edge,s.offset)?.let { path ->
            return (listOf(s.position)+path).distinctConsecutive()
        }
        val index=if(s.edge>=edges.size) edges.size else if(s.offset<1e-8) s.edge else s.edge+1
        val checkpoint=checkpoints[nextCheckpoint[index]]
        val path=mutableListOf(s.position)
        for(edge in s.edge until checkpoint.edge) path.add(edges[edge].b)
        path.addAll(checkpoint.returning)
        return path.distinctConsecutive()
    }
    /** Aligned vertices removed (exit along the route axis, intermediate support): no needless stop. */
    private fun List<Point>.withoutAlignedVertices(): List<Point> = distinctConsecutive().fold(mutableListOf()) { result, p ->
        if (result.size >= 2) {
            val a = result[result.size-2]; val b = result.last()
            val cross = (b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)
            val dot = (b.x-a.x)*(p.x-b.x)+(b.y-a.y)*(p.y-b.y)
            if (abs(cross) <= 1e-6*a.distance(p) && dot > 0) result.removeAt(result.lastIndex)
        }
        result.add(p); result
    }
    private fun List<Point>.distinctConsecutive(): List<Point> = fold(mutableListOf()) { result, point ->
        if(result.lastOrNull()!=point) result.add(point)
        result
    }
    private fun event(s:SimState,message:String)=s.copy(log=(s.log+"${(s.elapsed/60).toInt()} min · $message").takeLast(30))
    private fun point(e:SimEdge, offset:Double):Point { val p=(offset/e.meters).coerceIn(0.0,1.0); return Point(e.a.x+(e.b.x-e.a.x)*p,e.a.y+(e.b.y-e.a.y)*p) }
    fun start():SimState {
        val covered=total*config.initialProgress.coerceIn(0.0,100.0)/100
        var remaining=covered; var i=0; var offset=0.0
        while(i<edges.size) { val e=edges[i]; if(e.cutting && remaining<e.meters) { offset=remaining; break }; if(e.cutting) remaining-=e.meters; i++ }
        if (config.initialProgress >= 100.0) { i=edges.size; offset=0.0 }
        val trip=if(i==edges.size) listOf(base) else departure(i,offset)
        val s=SimState(base,config.initialBattery.coerceIn(0.0,100.0),if(i==edges.size) SimPhase.COMPLETE else departurePhase(trip),
            edge=i,offset=offset,covered=covered,transit=trip,trail=listOf(base),heading=(dockEntryHeading+180.0)%360.0,log=listOf(tr("0 min · Start at ${config.initialProgress.toInt()} % mowed", "0 min · Départ à ${config.initialProgress.toInt()} % de tonte")),
            surveyId=if(preview.mode.isSurvey) UUID.randomUUID().toString() else "",surveyStartedAt=if(preview.mode.isSurvey) Clock.now() else 0)
        // Same rule as the actual pilot (RouteRecharge.departureAllowed): immediate exit above the return threshold +
        // margin, otherwise recharge up to the resume threshold.
        val departureMinimum=min(config.resumePercent,config.returnPercent+RouteRecharge.DEPARTURE_MARGIN_PERCENT)
        return if(s.phase!=SimPhase.COMPLETE && s.battery<departureMinimum) event(s.copy(phase=SimPhase.CHARGING),tr("Initial recharge", "Recharge initiale"))
            else if(s.phase==SimPhase.LEAVING_DOCK) event(s,tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF")) else s
    }
    /**
     * Every departure from the station starts, as on the robot ([DockExit]), with a slow straight forward motion from
     * the base to the link point, without pivoting on the base; the link then follows at normal speed.
     */
    private fun departurePhase(trip: List<Point>) =
        if(trip.size>=2 && trip[0]==base && trip[1]==baseApproach) SimPhase.LEAVING_DOCK else SimPhase.DEPARTING
    /** Duration of a departure from the base: slow exit along the station axis, then link. */
    private fun departureSeconds(trip: List<Point>): Double {
        if(departurePhase(trip)!=SimPhase.LEAVING_DOCK) return travelSeconds(trip)
        val exit=base.distance(baseApproach)
        return exit/config.dockingSpeedMps + 2.0 + travelSeconds(trip.drop(1))
    }
    private fun returning(s:SimState, final:Boolean): SimState {
        val path=returnPath(s)
        return event(s.copy(phase=SimPhase.RETURNING,completedReturn=final,transit=path,
            resumeTransit=(listOf(base)+path.reversed()).distinctConsecutive(),transitIndex=0,transitOffset=0.0),
            if(final) tr("End of mowing, final return", "Fin de tonte, retour final") else tr("Battery: return to recharge", "Batterie : retour pour recharge"))
    }
    /** [onPhase]: each phase change crossed during the advance (accelerated playback: several per call). */
    fun advance(initial:SimState, seconds:Double, simulateSignals:Boolean=true, onPhase:((SimState)->Unit)?=null):SimState {
        require(seconds.isFinite() && seconds>=0 && seconds<=3600)
        var s=initial; var left=seconds
        while(left>1e-8 && s.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED)) {
            if (simulateSignals && preview.mode.isSurvey && s.phase == SimPhase.MOWING && edges.getOrNull(s.edge)?.cutting == true &&
                (s.signalSamples.isEmpty() || s.elapsed*1000-(s.signalSamples.last().at)>=1000)) {
                s = s.copy(signalSamples=s.signalSamples+SignalCoverage.simulated(s.position,base,s.elapsed,s.heading))
            }
            val playbackStep = min(left,1.0); left -= playbackStep
            if (s.phase == SimPhase.CHARGING) s = withChargeNeed(s)
            val dt = if (config.instantCharging && s.phase == SimPhase.CHARGING)
                max(playbackStep, (chargeTarget(s) - s.battery).coerceAtLeast(0.0) * config.chargeMinutes * 60 / 100)
            else playbackStep
            val phase = s.phase
            s = try {
                tick(s,dt)
            } catch (e: IllegalArgumentException) {
                event(s.copy(phase=SimPhase.BLOCKED), e.message ?: tr("Physical trajectory impossible", "Trajectoire physique impossible"))
            }
            if (s.phase != phase) onPhase?.invoke(s)
        }; return s
    }
    private fun travelSeconds(path: List<Point>): Double {
        val segments = path.zipWithNext().filter { (a,b) -> a.distance(b) > 1e-9 }
        val headings = segments.map { (a,b) -> Math.toDegrees(atan2(b.y-a.y,b.x-a.x)) }
        val turns = headings.zipWithNext().sumOf { (a,b) -> abs(((b-a+540.0)%360.0)-180.0) }
        // Unknown initial heading: reserve a U-turn, then the actual angles of the outline.
        // Two seconds per segment cover the rounding of the move and pivot ticks.
        return segments.sumOf { (a,b) -> a.distance(b) } / config.speedMps +
            (180.0 + turns) / config.turnDegreesPerSecond + segments.size * 2.0
    }
    // The heading is in degrees in the map frame (+x = 0°). No movement during the pivot.
    private fun returnSeconds(state: SimState) = travelSeconds(returnPath(state)) +
        180.0/config.turnDegreesPerSecond + 2.0 + config.baseApproachMeters/config.dockingSpeedMps
    /** Rest of the mowing from [edge] / [offset]: remaining cutting and links, then final return and docking. */
    private fun restSeconds(edge: Int, offset: Double): Double {
        val end=SimState(edges.lastOrNull()?.b ?: base,0.0,SimPhase.MOWING,edge=edges.size)
        if(edge>=edges.size) return returnSeconds(end)
        return travelSeconds(listOf(point(edges[edge],offset))+edges.subList(edge,edges.size).map { it.b }) + returnSeconds(end)
    }
    /** While charging: energy of the rest (exit, link, rest of the mowing, final return) remembered for the current recharge. */
    private fun withChargeNeed(s: SimState): SimState {
        if(s.chargeNeed!=null) return s
        val trip=s.resumeTransit.ifEmpty { departure(s.edge,s.offset) }
        return s.copy(chargeNeed=FinishPolicy.neededPercent(departureSeconds(trip)+restSeconds(s.edge,s.offset),config.autonomyMinutes))
    }
    /** Charge level at which to leave again: resume threshold, or less if the rest is short ([FinishPolicy]). */
    private fun chargeTarget(s: SimState): Double =
        s.chargeNeed?.let { FinishPolicy.chargeTarget(it,config.returnPercent,config.resumePercent) }?.coerceAtMost(config.resumePercent) ?: config.resumePercent
    private fun align(s: SimState, e: SimEdge, dt: Double): SimState? =
        alignHeading(s,Math.toDegrees(atan2(e.b.y-e.a.y,e.b.x-e.a.x)),dt)
    private fun alignHeading(s: SimState, target: Double, dt: Double): SimState? {
        val delta=((target-s.heading+540.0)%360.0)-180.0
        if(abs(delta)<1e-6) return null
        return s.copy(heading=(s.heading+delta.coerceIn(-config.turnDegreesPerSecond*dt,config.turnDegreesPerSecond*dt)+360.0)%360.0)
    }
    private fun tick(input:SimState,dt:Double):SimState {
        var s=input.copy(elapsed=input.elapsed+dt)
        // Configured autonomy = full discharge duration, without arbitrary penalty.
        val drain=100.0/(config.autonomyMinutes*60)
        if(s.phase==SimPhase.CHARGING) {
            s=withChargeNeed(s)
            val target=chargeTarget(s)
            s=s.copy(battery=min(max(target,s.battery),s.battery+dt*100/(config.chargeMinutes*60)))
            if(s.battery>=target-1e-9) {
                val early=target<config.resumePercent
                val trip=s.resumeTransit.ifEmpty { departure(s.edge,s.offset) }
                val roundTrip=(departureSeconds(trip)+returnSeconds(s.copy(position=point(edges[s.edge],s.offset))))*drain
                if(!early && roundTrip+config.returnPercent>=config.resumePercent) return event(s.copy(phase=SimPhase.BLOCKED),
                    tr("Autonomy insufficient to reach the resume point and come back", "Autonomie insuffisante pour atteindre la reprise et revenir"))
                s=event(s.copy(phase=departurePhase(trip),transit=trip,transitIndex=0,transitOffset=0.0,heading=(dockEntryHeading+180.0)%360.0,
                        finishing=false,chargeNeed=null),
                    (if(early) tr("Battery enough to finish (${s.battery.roundToInt()} %) · ", "Batterie suffisante pour finir (${s.battery.roundToInt()} %) · ")
                        else tr("Recharge finished · ", "Recharge terminée · ")) +
                    if(departurePhase(trip)==SimPhase.LEAVING_DOCK) tr("leaving the base in a straight line, slowly", "sortie de base en ligne droite, lentement")
                        else tr("resume at the same point", "reprise au même point"))
            }; return s
        }
        if(s.phase==SimPhase.ALIGNING) {
            val aligned=alignHeading(s,(dockEntryHeading+180.0)%360.0,dt)
            s=aligned ?: event(s.copy(phase=SimPhase.DOCKING,transit=listOf(baseApproach,base),
                transitIndex=0,transitOffset=0.0),tr("Simulated recharge return mode on · sensor-guided reversing", "Mode retour recharge simulé activé · recul guidé par capteurs"))
        } else if(s.phase==SimPhase.MOWING) {
            if(s.edge>=edges.size) return returning(s,true)
            // Return threshold: finish without recharge if the rest allows it ([FinishPolicy]), emergency return lower down afterwards.
            if(!s.finishing && s.battery<=config.returnPercent) {
                if(!FinishPolicy.finishWithoutRecharge(s.battery,FinishPolicy.neededPercent(restSeconds(s.edge,s.offset),config.autonomyMinutes)))
                    return returning(s,false)
                s=event(s.copy(finishing=true),tr("Return threshold: battery enough to finish the mowing then return", "Seuil bas : batterie suffisante pour finir la tonte puis rentrer"))
            }
            if(s.finishing && s.battery<=FinishPolicy.ARRIVAL_FLOOR_PERCENT) return returning(s.copy(finishing=false),false)
            val backSeconds=returnSeconds(s)
            // Never Die Outside ([ReturnReserve]): far from the base, return as soon as the battery nears the return cost
            // plus the reserve, even above the low threshold; same choice as at the low threshold (finish or recharge).
            if(!s.finishing) {
                val low=config.returnPercent.roundToInt(); val high=config.resumePercent.roundToInt()
                val watch=ReturnReserve.threshold(low,high,FinishPolicy.neededPercent(backSeconds,config.autonomyMinutes),
                    ReturnReserve.sanitize(config.returnReservePercent.roundToInt(),low))
                if(watch>low && s.battery<=watch) {
                    if(!FinishPolicy.finishWithoutRecharge(s.battery,FinishPolicy.neededPercent(restSeconds(s.edge,s.offset),config.autonomyMinutes)))
                        return event(returning(s,false),tr("Return reserve: battery near the return cost plus the reserve ($watch %)", "Réserve de retour : batterie proche du coût du retour plus la réserve ($watch %)"))
                    s=event(s.copy(finishing=true),tr("Return reserve: battery enough to finish the mowing then return", "Réserve de retour : batterie suffisante pour finir la tonte puis rentrer"))
                }
            }
            val back=backSeconds*drain
            if(s.battery<=back+dt*drain) return event(returning(s,false),tr("Early return: energy needed to reach the base", "Retour anticipé : énergie nécessaire pour atteindre la base"))
            // Time left in this step: the speed changes along the route on the slowed stretches ([AdaptiveSpeed]).
            var time=dt
            while(time>1e-8 && s.edge<edges.size) {
                val e=edges[s.edge]
                val aligned=align(s,e,dt)
                if(aligned != null) { s=aligned; break }
                val speed=AdaptiveSpeed.speedMps(config.speedMps,AdaptiveSpeed.percentAt(speedCaps,s.edge+if(e.meters>1e-9) s.offset/e.meters else 0.0))
                val step=min(time*speed,e.meters-s.offset); val offset=s.offset+step
                val future=if(offset>=e.meters-1e-8)
                    s.copy(position=e.b,edge=s.edge+1,offset=0.0)
                else s.copy(position=point(e,offset),offset=offset)
                // Final return: arrival at the [FinishPolicy] floor (the return threshold only decides a recharge).
                val arrivalReserve=if(future.edge<edges.size) 0.0 else FinishPolicy.ARRIVAL_FLOOR_PERCENT.toDouble()
                if(s.battery <= (returnSeconds(future)+dt)*drain+arrivalReserve)
                    return event(returning(s,false),tr("Early return before the next link to the base", "Retour anticipé avant le prochain raccord à la base"))
                s=s.copy(position=future.position,offset=offset,covered=s.covered+if(e.cutting)step else 0.0)
                time-=step/speed
                if(offset>=e.meters-1e-8) { s=s.copy(edge=s.edge+1,offset=0.0); break }
            }
        } else {
            val slow=s.phase==SimPhase.DOCKING || s.phase==SimPhase.LEAVING_DOCK
            var distance=dt*if(slow) config.dockingSpeedMps else config.speedMps
            // Leaving the base: only the base → link point edge, axis heading kept (no pivot on the base).
            val last=if(s.phase==SimPhase.LEAVING_DOCK) 1 else s.transit.size-1
            while(distance>1e-8 && s.transitIndex<last) {
                val e=SimEdge(s.transit[s.transitIndex],s.transit[s.transitIndex+1],false)
                val aligned=if(e.meters>1e-8 && !slow) align(s,e,dt) else null
                if(aligned != null) { s=aligned; break }
                val step=min(distance,e.meters-s.transitOffset); val offset=s.transitOffset+step
                s=s.copy(position=if(e.meters<1e-8)e.b else point(e,offset),transitOffset=offset); distance-=step
                if(offset>=e.meters-1e-8) { s=s.copy(transitIndex=s.transitIndex+1,transitOffset=0.0); if(e.meters>1e-8) break }
            }
            if(s.phase==SimPhase.LEAVING_DOCK && s.transitIndex>=last)
                s=if(s.transitIndex>=s.transit.size-1) event(s.copy(phase=SimPhase.MOWING),tr("Link point reached · mowing / route resume", "Point de liaison atteint · tonte / reprise du trajet"))
                    else event(s.copy(phase=SimPhase.DEPARTING),tr("Link point reached · link towards the mowing", "Point de liaison atteint · raccord vers la tonte"))
            else if(s.transitIndex>=s.transit.size-1) s=when(s.phase) {
                SimPhase.DEPARTING -> event(s.copy(phase=SimPhase.MOWING),tr("Mowing / route resume", "Tonte / reprise du trajet"))
                SimPhase.RETURNING -> event(s.copy(phase=SimPhase.ALIGNING),tr("Approach point reached · rear alignment facing the base", "Point d’approche atteint · alignement arrière face à la base"))
                else -> if(s.completedReturn) event(s.copy(phase=SimPhase.COMPLETE,position=base),tr("Arrived at the base, mission finished", "Arrivée à la base, mission terminée"))
                    else event(s.copy(phase=SimPhase.CHARGING,position=base,cycles=s.cycles+1),tr("Arrived at the base, recharge ${s.cycles+1}", "Arrivée à la base, recharge ${s.cycles+1}"))
            }
        }
        s=s.copy(battery=(s.battery-dt*drain).coerceAtLeast(0.0),trail=(s.trail+ s.position).takeLast(1800))
        return if(s.battery<=0 && s.phase!=SimPhase.COMPLETE) event(s.copy(phase=SimPhase.BLOCKED),tr("Battery exhausted before the base", "Batterie épuisée avant la base")) else s
    }
}

/** Binary heap of indices without boxing: order (key, index), identical to a stable sort. */
internal class IndexHeap(private val keys: DoubleArray) {
    private val heap = IntArray(keys.size) { it }
    private var size = keys.size
    private fun less(a: Int, b: Int) = keys[a] < keys[b] || (keys[a] == keys[b] && a < b)
    init { for (i in size / 2 - 1 downTo 0) down(i) }
    private fun down(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1; val r = l + 1
            var m = i
            if (l < size && less(heap[l], heap[m])) m = l
            if (r < size && less(heap[r], heap[m])) m = r
            if (m == i) return
            val t = heap[i]; heap[i] = heap[m]; heap[m] = t; i = m
        }
    }
    fun pop(): Int {
        if (size == 0) return -1
        val top = heap[0]
        heap[0] = heap[--size]
        down(0)
        return top
    }
}
