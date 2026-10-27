package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import kotlin.math.roundToInt

/** Single interactive policy: geometric generation, local order, full validation.
 * Global searches remain reserved for the scientific comparators.
 */
internal object AutomaticPlanner {
    /** Accepted extra cost (route score of the zone) to mow a whole side before moving on rather than following the
     * nearest link. */
    const val BAR_TOLERANCE = 0.02
    internal fun sameCoveragePaths(a: List<List<Point>>,b: List<List<Point>>): Boolean {
        fun signatures(paths: List<List<Point>>) = paths.map { setOf(it,it.reversed()) }.toSet()
        return signatures(a)==signatures(b)
    }
    /** [orientation] false: [PlanningConfig.orientation] ignored (Optimized compares its mixed plan with an automatic zigzag).
     * Zamboni: a plan not validated because the chained runs of a zone led the local order to a dead end
     * ([LocalMowingOrder.schedule]) is computed again with that zone ordered without chains, as in Zigzag
     * ([Preview.zoneReorderedCells] −1); kept only if it is validated, so the plans validated with chains are unchanged. */
    fun plan(input: PlanningRequest,mode: CoverageMode,checkpoint: () -> Unit,orientation: Boolean = true): PlanningResult {
        var deadlock=false
        val chained=plan(input,mode,checkpoint,orientation,false) { deadlock=true }
        if(mode!=CoverageMode.ZAMBONI || chained.selected.valid || !deadlock) return chained
        val unchained=plan(input,mode,checkpoint,orientation,true) {}
        return if(unchained.selected.valid) unchained else chained
    }
    /** [unchain]: a Zamboni zone whose chained order has no safe link is ordered without chains; [deadlock] is called
     * when such a zone is met. */
    private fun plan(input: PlanningRequest,mode: CoverageMode,checkpoint: () -> Unit,orientation: Boolean,
                     unchain: Boolean,deadlock: () -> Unit): PlanningResult {
        val request=(if(mode.isSurvey) input.copy(config=input.config.copy(minimumCoverage=0.0)) else input).snapshot()
        val phases=linkedMapOf<String,Long>()
        fun <T> timed(name: String,block: () -> T): T {
            checkpoint();val start=System.nanoTime()
            return try { block() } finally { phases[name]=(phases[name] ?: 0)+System.nanoTime()-start }
        }
        var terrain: PlanningTerrain?=null
        var transitions: TransitionPlanner?=null
        val candidates=mutableListOf<CandidateResult>()
        fun report(selected: CandidateResult) = PlanningResult(request.hash,PlannerStrategy.EXPERIMENTAL_LNS,
            selected,candidates.toList(),terrain?.assumptions.orEmpty()+
                tr("Local order by cells; disc tool and stop–pivot not calibrated; no physical validation",
                    "Ordre local par cellules ; outil disque et arrêt–pivot non calibrés ; aucune validation physique"),
            phases.toMap(),transitions?.queries ?: 0,transitions?.hits ?: 0)
        try {
            val t=timed("preprocessing") { PlanningTerrain(request) };terrain=t
            val links=TransitionPlanner(t);transitions=links
            val evaluator=MissionEvaluator(t,links);val c=request.config
            // Manual direction: the requested angle is the only candidate of every zone, never replaced by another one.
            val manual=orientation && mode.usesOrientation && c.orientation==OrientationMode.MANUAL
            // Change every session: zones whose last mowing is remembered for their current outline (outline as recorded,
            // before the border strategy moves it).
            val eachSession=orientation && mode.usesOrientation && c.orientation==OrientationMode.EACH_SESSION
            val outlines=(request.navigationMap ?: request.map).shapes
            val loopChecks=mutableMapOf<List<Point>,Boolean>()
            fun loopSafe(path: List<Point>): Boolean = loopChecks.getOrPut(path) { t.envelope.pathCollision(path,
                finalHeading=path.zipWithNext().firstOrNull { (a,b) -> a.distance(b)>1e-9 }
                    ?.let { heading(it.first,it.second) })==null }
            val entryClearance=mutableMapOf<Point,Boolean>()
            fun loopEntrySafe(point: Point): Boolean = entryClearance.getOrPut(point) {
                // A new origin must also allow leaving the loop again.
                // Navigation keeps its pivot reserves, without a tangent shortcut
                // that blocks the rest of the route a few centimetres from a limit.
                t.envelope.turnCollision(point,0.0,180.0)==null &&
                    t.envelope.turnCollision(point,180.0,360.0)==null
            }
            // Recorded outline: no-go loops joined and left at a distance, pivots outside the set margin.
            val noGoLeads=if(c.boundary==BoundaryMeaning.RECORDED_TRACE && !mode.isSurvey)
                NoGoLoopLeads(t.holes,request.robot,c.outerOffsetM) else null
            val parts=mutableListOf<Preview>()
            val referenceParts=mutableListOf<Preview>()
            fun order(geometry: Preview,previous: List<Point>?,prefix: List<List<Point>>,chained: Boolean = true): Preview {
                val entry=previous?.lastOrNull() ?: request.start
                // Two bounded policies: reserve at the initial origin or on all
                // origins. Same nearby start; compare the complete links,
                // return included, without recomputing the surface unions.
                var failure: IllegalArgumentException?=null
                // Greedy order (nearest link), then sweep-bar orders ([LocalMowingOrder.SweepBar])
                // under the chosen reserve policy: a whole side mowed before moving on.
                // Several bars often give the same order: mission cost evaluated once per order.
                val costs=HashMap<List<List<Point>>,Double>()
                fun schedule(all: Boolean,bar: LocalMowingOrder.SweepBar?,first: Int?): Pair<Preview,Double>? = try {
                    val p=LocalMowingOrder.schedule(geometry,entry,c.bordersFirst,c.nearbyNoGo,
                        { before,after -> evaluator.linkCost(before ?: previous,after) },
                        { t.envelope.pathCollision(it)==null },checkpoint,
                        entryDistance={ evaluator.linkDistance(previous,it) },
                        entryLowerBound={ t.routes.distanceLowerBound(previous?.last() ?: request.start,it.first()) },loopSafe=::loopSafe,
                        loopEntrySafe=::loopEntrySafe,reserveAllLoopEntries=all,noGoLoopLead=noGoLeads?.let { it::lead },
                        sweepBar=bar,firstLayer=first,chained=chained)
                    checkpoint()
                    val cost=costs.getOrPut(p.cuttingPaths) { evaluator.travelCost(prefix+p.cuttingPaths) }
                    if(cost.isFinite()) p to cost else null
                } catch(e: IllegalArgumentException) { failure=e;null }
                val policies=if(geometry.borders.size>1) listOf(false,true) else listOf(false)
                data class Choice(val all: Boolean,val first: Int?,val preview: Preview,val cost: Double)
                val choices=policies.mapNotNull { all -> schedule(all,null,null)?.let { Choice(all,null,it.first,it.second) } }.toMutableList()
                // Checkerboard: a temporarily unreachable cell may be postponed after the start of the other direction;
                // each direction is then also tried first.
                if(mode==CoverageMode.CHECKERBOARD && choices.all { LocalMowingOrder.layerSwitches(it.preview)>1 })
                    choices+=policies.flatMap { all -> listOf(0,1).mapNotNull { first -> schedule(all,null,first)?.let { Choice(all,first,it.first,it.second) } } }
                if(choices.isEmpty() && chained && geometry.mode==CoverageMode.ZAMBONI) {
                    // Zamboni dead end: the zone ordered again without chains (Zigzag order), its cells reported as not
                    // driven in their Zamboni order (-1; 0 stays 0, the zigzag order of every cell).
                    deadlock()
                    if(unchain) return order(geometry,previous,prefix,false).let { p ->
                        p.copy(zoneReorderedCells=p.zoneReorderedCells.map { if(it>0) -1 else it }) }
                }
                require(choices.isNotEmpty()) { failure?.message ?: tr("No complete reachable order", "Aucun ordre complet accessible") }
                fun entryLength(p: Preview)=evaluator.linkDistance(previous,p.cuttingPaths.first())
                val nearest=choices.minOf { entryLength(it.preview) }
                val greedy=choices.filter { entryLength(it.preview)<=nearest+1e-8 }
                    .minWith(compareBy<Choice> { LocalMowingOrder.layerSwitches(it.preview) }.thenBy { it.cost })
                if(mode.isSurvey) return greedy.preview
                // Tidiest order (fewest cells left behind the bar) among those costing no more than BAR_TOLERANCE
                // above the greedy order on this zone; on a tie, the cheapest.
                val before=if(prefix.isEmpty()) 0.0 else evaluator.travelCost(prefix)
                val budget=before+(greedy.cost-before)*(1+BAR_TOLERANCE)+1e-9
                val candidates=listOf(greedy.preview to greedy.cost)+LocalMowingOrder.SWEEP_BARS.mapNotNull { bar ->
                    schedule(greedy.all,bar,greedy.first) }.filter { it.second<=budget && entryLength(it.first)<=nearest+1e-8 }
                return candidates.minWith(compareBy<Pair<Preview,Double>> { LocalMowingOrder.layerSwitches(it.first) }
                    .thenBy { LocalMowingOrder.backtracks(it.first) }.thenBy { it.second }).first
            }
            /** [shifted]: "Change every session" moved this zone away from its automatic direction; plan at the automatic
             * direction with shifted rows, tried if the moved plan is refused. */
            data class ZonePlan(val zone: MapShape,val geometry: Preview,val reference: Preview,val mixedReference: Preview?,
                                val shifted: (() -> Preview)? = null,val fromEdge: (() -> Preview)? = null)
            val geometries=mutableListOf<ZonePlan>()
            val orderedZones=mutableListOf<MapShape>()
            val raw=timed("baseline_generation") {
                    for(zone in t.zones) {
                        checkpoint()
                        val entry=request.start
                        val limit=if(mode==CoverageMode.CHECKERBOARD || mode==CoverageMode.SURVEY_GRID) 90 else 180
                        val coarse=(0 until limit step 15).toList()
                        var reference: Preview?=null
                        var referenceCost=Double.POSITIVE_INFINITY
                        val memory=if(!eachSession) null else request.orientationMemory[zone.id]?.takeIf { m ->
                            outlines.firstOrNull { it.kind==ShapeKind.ZONE && it.id==zone.id }?.let(m::matches)==true }
                        val rule=memory?.let { OrientationRule(it.directions,OrientationRule.PHASES[OrientationRule.nextPhaseIndex(it.phaseIndex)]) }
                        val angles=if(manual) listOf(foldAngle(c.manualAngleDeg,limit)) else (zone.points.zipWithNext()+listOf(zone.points.last() to zone.points.first()))
                            .sortedByDescending { (a,b) -> a.distance(b) }.take(6)
                            .map { (a,b) -> ((heading(a,b).roundToInt()%limit)+limit)%limit }
                            .plus(coarse).plus(rule?.let { r -> OrientationRule.extraAngles(r.avoid).map { foldAngle(it,limit) } }.orEmpty()).distinct()
                        val rank: ((Preview) -> Double)?=if(mode.isSurvey) null else { preview ->
                                checkpoint()
                                // Ranking only: hypothetical direct links,
                                // never used to move the robot. The chosen order
                                // is then recomputed with the actual safe links.
                                fun proxyLink(before: List<Point>?,after: List<Point>?): Double {
                                    val incoming=before?.zipWithNext()?.lastOrNull { (a,b) -> a.distance(b)>1e-9 }
                                        ?.let { heading(it.first,it.second) } ?: request.startHeading
                                    val outgoing=after?.zipWithNext()?.firstOrNull { (a,b) -> a.distance(b)>1e-9 }
                                        ?.let { heading(it.first,it.second) } ?: if(after==null) request.finishHeading else request.startHeading
                                    return evaluator.pathCost(listOf(before?.last() ?: entry,after?.first() ?: request.finish),
                                        incoming,outgoing,false)
                                }
                                val ordered=LocalMowingOrder.schedule(preview,entry,c.bordersFirst,c.nearbyNoGo,
                                    { before,after -> proxyLink(before,after) },{ true },checkpoint,rotateLoops=false)
                                val paths=ordered.cuttingPaths
                                paths.sumOf { evaluator.pathCost(it) }+paths.indices.sumOf { i ->
                                    proxyLink(paths.getOrNull(i-1),paths[i])
                                }+proxyLink(paths.lastOrNull(),null)+c.weights.uTurns*preview.connectors
                            }
                        var automatic: Preview?=null
                        // Spiral: one spiral per zone, in the direction of the setting, computed with the orientations of the
                        // zigzag, which replaces it on the zone when the spiral cannot be built or mows more than 0.2 point less
                        // ([CoveragePlanner.plan]).
                        val geometry=try { CoveragePlanner.plan(zone.points,t.holes,c.spacingM,c.marginM,mode,c.borders,
                            c.borderPasses,c.noGoPasses,t.obstacleDiscs,request.robot,
                            c.boundary==BoundaryMeaning.RECORDED_TRACE,entry,
                            sweepOptions=if(mode.isSurvey) null else SweepOptions(angles),continuous=true,outerOffsetM=c.outerOffsetM,turnStyle=c.turnStyle,speedMps=c.speedMps,cutWidthM=c.cutWidthM,
                            orientationRule=rule,observeAutomatic=if(rule==null) null else { p -> automatic=p },
                            observeCandidate=if(mode.isSurvey) null else { preview ->
                                // Historical reference chosen in the order of the orientations.
                                val legacyCost=preview.meters+preview.runs.size*10
                                if(preview.angle in coarse && (legacyCost<referenceCost ||
                                        legacyCost==referenceCost && preview.angle<(reference?.angle ?: Int.MAX_VALUE))) {
                                    reference=preview;referenceCost=legacyCost
                                }
                            },
                            candidateCost=rank,checkpoint=checkpoint,spiralDirection=c.spiralDirection)
                        } catch(e: IllegalArgumentException) {
                            if(!manual) throw e
                            val name=zone.name.trim().ifBlank { "Zone" }; val angle=foldAngle(c.manualAngleDeg,limit)
                            throw IllegalArgumentException(tr("Manual angle $angle° cannot be mowed on $name: ${e.message}. Use Automatic.",
                                "Angle manuel $angle° impossible sur $name : ${e.message}. Utilisez Automatique."),e)
                        }
                        // Optimized mode: the historical reference also gets its reoriented stripes,
                        // so that the surface fallback does not always lead back to the zigzag.
                        val mixedReference=if(mode!=CoverageMode.OPTIMIZED) null else reference?.let { r ->
                            try {
                                CoveragePlanner.plan(zone.points,t.holes,c.spacingM,c.marginM,mode,c.borders,
                                    c.borderPasses,c.noGoPasses,t.obstacleDiscs,request.robot,
                                    c.boundary==BoundaryMeaning.RECORDED_TRACE,entry,sweepOptions=SweepOptions(listOf(r.angle)),
                                    continuous=true,outerOffsetM=c.outerOffsetM,turnStyle=c.turnStyle,speedMps=c.speedMps,cutWidthM=c.cutWidthM)
                                    .takeUnless { sameCoveragePaths(it.runs,r.runs) }
                            } catch(e: IllegalArgumentException) { null }
                            catch(e: org.locationtech.jts.geom.TopologyException) { null }
                        }
                        // Change every session moved away from the automatic direction: same direction as Automatic with
                        // shifted rows if the moved plan is refused (computed only then, one orientation).
                        val auto=automatic
                        val shifted=if(rule==null || auto==null || auto.angle==geometry.angle) null else { ->
                            CoveragePlanner.plan(zone.points,t.holes,c.spacingM,c.marginM,mode,c.borders,
                                c.borderPasses,c.noGoPasses,t.obstacleDiscs,request.robot,
                                c.boundary==BoundaryMeaning.RECORDED_TRACE,entry,sweepOptions=SweepOptions(listOf(auto.angle),rule.phase),
                                continuous=true,outerOffsetM=c.outerOffsetM,turnStyle=c.turnStyle,speedMps=c.speedMps,cutWidthM=c.cutWidthM,
                                candidateCost=rank,checkpoint=checkpoint)
                        }
                        // Spiral from the centre: the same zone planned from the edge, used if no order from the centre has safe
                        // links on it (computed only then; the chains entered at their centre end may lead to a dead end).
                        val fromEdge=if(geometry.outwardRuns.isEmpty()) null else { ->
                            CoveragePlanner.plan(zone.points,t.holes,c.spacingM,c.marginM,mode,c.borders,
                                c.borderPasses,c.noGoPasses,t.obstacleDiscs,request.robot,
                                c.boundary==BoundaryMeaning.RECORDED_TRACE,entry,sweepOptions=SweepOptions(angles),
                                continuous=true,outerOffsetM=c.outerOffsetM,turnStyle=c.turnStyle,speedMps=c.speedMps,cutWidthM=c.cutWidthM,
                                candidateCost=rank,checkpoint=checkpoint,spiralDirection=SpiralDirection.FROM_EDGE)
                                .let { p -> p.copy(zoneReorderedCells=listOf(if(p.zoneModes==listOf(CoverageMode.SPIRAL)) -1 else 0)) }
                        }
                        // Manual: the requested angle is its own reference, so the surface fallback never swaps it.
                        geometries.add(ZonePlan(zone,geometry,if(manual) geometry else reference ?: geometry,mixedReference,shifted,fromEdge))
                    }
                    // User selection order kept as is: the first zone
                    // chosen is mowed first, entered as close as possible to the start (robot, or
                    // base if it is charging) in this zone; each next zone starts from the exit of the previous one.
                    for(zone in geometries) {
                        val previous=parts.lastOrNull()?.cuttingPaths?.lastOrNull()
                        referenceParts.add(zone.reference);orderedZones.add(zone.zone)
                        val ordered=timed("local_order") {
                            try { order(zone.geometry,previous,parts.flatMap { it.cuttingPaths }) }
                            catch(e: IllegalArgumentException) {
                                // Spiral from the centre without a complete safe order: the zone from the edge (warned).
                                val edge=zone.fromEdge ?: throw e
                                order(edge(),previous,parts.flatMap { it.cuttingPaths })
                            }
                        }
                        parts.add(ordered)
                    }
                    MultiZonePlanner.combine(orderedZones,parts,mode,request.start)
            }.copy(localOnly=true)
            // Exact surface reference only if the geometry changes.
            // Compare the pass footprints, without computing an extra global mission
            // for each orientation. A loss >0.2 point triggers the local fallback
            // to the historical orientation, with full validation.
            val reference=MultiZonePlanner.combine(orderedZones,referenceParts,mode,request.start)
            // A cyclic rotation with a collinear point keeps the footprint.
            // Compare the generated geometry, independent of the loop origins.
            val sameFootprint=sameCoveragePaths(raw.runs+raw.borders,reference.runs+reference.borders)
            val minimumArea=if(sameFootprint || mode.isSurvey) 0.0 else timed("coverage_reference") {
                (MissionEvaluator.coverage(reference.cuttingPaths.map { MissionLeg(it,true) },t.target,c.cutWidthM/2,
                    c.indexedCoverage,c.coverageCellM).covered-0.002*t.target.area).coerceAtLeast(0.0)
            }
            var selected=timed("evaluation") { evaluator.evaluate("continuous-local",raw,minimumArea) }
            candidates.add(selected)
            fun fallback(name: String,geometries: List<Preview>) {
                val fallbackParts=mutableListOf<Preview>()
                for(geometry in geometries) {
                    val previous=fallbackParts.lastOrNull()?.cuttingPaths?.lastOrNull()
                    fallbackParts.add(timed("local_fallback") {
                        order(geometry,previous,fallbackParts.flatMap { it.cuttingPaths })
                    })
                }
                selected=timed("evaluation") { evaluator.evaluate(name,
                    MultiZonePlanner.combine(orderedZones,fallbackParts,mode,request.start).copy(localOnly=true),minimumArea) }
                candidates.add(selected)
            }
            if(!selected.valid && !sameFootprint && geometries.any { it.mixedReference!=null }) try {
                fallback("continuous-reference-mixed",geometries.map { it.mixedReference ?: it.reference })
            } catch(e: IllegalArgumentException) {
                candidates.add(CandidateResult("continuous-reference-mixed",PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Order refused", "Ordre refusé"))))
            }
            if(!selected.valid && geometries.any { it.shifted!=null }) try {
                val shiftedParts=timed("orientation_shift") { geometries.map { z ->
                    z.shifted?.let { s -> try { s() } catch(e: IllegalArgumentException) { null } } ?: z.geometry } }
                fallback("continuous-orientation-shift",shiftedParts)
            } catch(e: IllegalArgumentException) {
                candidates.add(CandidateResult("continuous-orientation-shift",PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Order refused", "Ordre refusé"))))
            }
            if(!selected.valid && !sameFootprint) fallback("continuous-reference",referenceParts)
            if(manual && !selected.valid) selected=selected.copy(diagnostics=selected.diagnostics+
                tr("Manual angle ${c.manualAngleDeg}°: route not validated at this angle. Use Automatic.",
                    "Angle manuel ${c.manualAngleDeg}° : trajet non validé à cet angle. Utilisez Automatique."))
            return report(selected)
        } catch(e: org.locationtech.jts.geom.TopologyException) {
            val failed=CandidateResult("geometry",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Geometry error", "Erreur géométrique")))
            candidates.add(failed);return report(failed)
        } catch(e: IllegalArgumentException) {
            val failed=CandidateResult("input",if(terrain==null) PlanStatus.INVALID_INPUT else PlanStatus.NO_SOLUTION_FOUND,
                null,null,null,listOf(e.message ?: tr("Unsafe route", "Trajet non sûr")))
            candidates.add(failed);return report(failed)
        }
    }
}
