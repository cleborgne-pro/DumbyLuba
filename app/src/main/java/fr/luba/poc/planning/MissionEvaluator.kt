package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust

/** Bounded, oriented cache, strictly local to one snapshot; failures are remembered. */
internal class TransitionPlanner(private val terrain: PlanningTerrain) {
    private data class Key(val a: Point,val b: Point,val incoming: Double?,val outgoing: Double?)
    private val cache = object : LinkedHashMap<Key,List<Point>?>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,List<Point>?>?) = size > 2048
    }
    var queries = 0L; private set
    var hits = 0L; private set
    fun between(a: Point,b: Point,incoming: Double?,outgoing: Double?): List<Point>? {
        queries++
        val key = Key(a,b,incoming,outgoing)
        if (cache.containsKey(key)) { hits++; return cache[key] }
        val result = try {
            terrain.routes.between(a,b,incoming,outgoing).also {
                terrain.envelope.requirePath(it,incoming,outgoing)
            }
        } catch (_: IllegalArgumentException) { null }
        cache[key] = result
        return result
    }
}

internal class MissionEvaluator(private val terrain: PlanningTerrain, val transitions: TransitionPlanner) {
    private val request = terrain.request
    private val config = request.config
    fun assemble(paths: List<List<Point>>): List<MissionLeg>? =
        RoutePlanning.assemble(paths,transitions::between,request.start,request.startHeading,
            request.finish,request.finishHeading)
    /** Exact cost of the oriented transitions, additive cost without recomputing the cut unions. */
    fun travelCost(paths: List<List<Point>>): Double {
        val mission = assemble(paths) ?: return Double.POSITIVE_INFINITY
        val (distance,transit,angle,stops) = movement(mission)
        val w = config.weights
        return w.distance*distance + w.transit*transit + w.duration*(distance/config.speedMps+angle/config.turnDegreesPerSecond+stops*config.stopSeconds)
    }
    internal fun pathCost(path: List<Point>, initial: Double? = null, final: Double? = null, cutting: Boolean = true): Double {
        val edges=path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-9 }
        val directions=edges.map { heading(it.first,it.second) }
        val angles=(listOfNotNull(initial)+directions+listOfNotNull(final)).zipWithNext().map { angleDifference(it.first,it.second) }.filter { it>1e-7 }
        val distance=length(path); val w=config.weights
        return (w.distance+if(cutting) 0.0 else w.transit)*distance + w.duration*(distance/config.speedMps+
            angles.sum()/config.turnDegreesPerSecond+angles.size*config.stopSeconds)
    }
    internal fun linkCost(before: List<Point>?,after: List<Point>?): Double {
        val a=before?.last() ?: request.start; val b=after?.first() ?: request.finish
        val incoming=before?.zipWithNext()?.lastOrNull { (x,y) -> x.distance(y)>1e-9 }?.let { heading(it.first,it.second) } ?: request.startHeading
        val outgoing=after?.zipWithNext()?.firstOrNull { (x,y) -> x.distance(y)>1e-9 }?.let { heading(it.first,it.second) } ?: request.finishHeading
        val path=transitions.between(a,b,incoming,outgoing) ?: return Double.POSITIVE_INFINITY
        return pathCost(path,incoming,outgoing,false)
    }
    internal fun linkDistance(before: List<Point>?,after: List<Point>): Double {
        val a=before?.last() ?: request.start
        val incoming=before?.zipWithNext()?.lastOrNull { (x,y) -> x.distance(y)>1e-9 }
            ?.let { heading(it.first,it.second) } ?: request.startHeading
        val outgoing=after.zipWithNext().firstOrNull { (x,y) -> x.distance(y)>1e-9 }
            ?.let { heading(it.first,it.second) }
        return transitions.between(a,after.first(),incoming,outgoing)?.let(::length) ?: Double.POSITIVE_INFINITY
    }
    private fun movement(mission: List<MissionLeg>): List<Double> {
        var distance=0.0; var transit=0.0; var angle=0.0; var stops=0.0
        var previous = request.startHeading
        mission.forEach { leg -> leg.path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-9 }.forEach { (a,b) ->
            val d=a.distance(b); val next=heading(a,b)
            val turn=previous?.let { angleDifference(it,next) } ?: 0.0
            if (turn>1e-7) { angle+=turn; stops++ }
            previous=next; distance+=d; if (!leg.cutting) transit+=d
        } }
        if (previous != null && request.startHeading != null) {
            val turn=angleDifference(previous!!,request.startHeading)
            if (turn>1e-7) { angle+=turn; stops++ }
        }
        return listOf(distance,transit,angle,stops)
    }
    fun evaluate(name: String,preview: Preview,minimumArea: Double = 0.0): CandidateResult {
        val mission = assemble(preview.cuttingPaths) ?: return CandidateResult(name,PlanStatus.NO_SOLUTION_FOUND,
            preview,null,null,listOf(tr("Oriented link not found; no proof of global impossibility",
                "Raccord orienté introuvable ; aucune preuve d'impossibilité globale")))
        val assembled = mission.flatMap { it.path }
        val fullPath = assembled.filterIndexed { i,p -> i == 0 || p != assembled[i-1] }
        var violations = if (terrain.envelope.pathCollision(fullPath,request.startHeading,request.startHeading) == null) 0 else 1
        // The cut is a disc centred on the reference point, an explicit assumption distinct from the chassis rectangle.
        val coverage = coverage(mission,terrain.target,config.cutWidthM/2,config.indexedCoverage,config.coverageCellM)
        mission.filter { it.cutting }.forEach { leg ->
            if (!terrain.physical.covers(line(leg.path).buffer(config.cutWidthM/2/kotlin.math.cos(Math.PI/64),16))) violations++
        }
        val (distance,transit,angle,stops) = movement(mission)
        // Opposition of the sweep passes: a link with two right angles counts as a U-turn.
        val passDirections=mutableListOf<Pair<Int,Double>>()
        mission.filter { it.cutting }.forEach { leg -> leg.path.zipWithNext().forEach { (a,b) ->
            if (a.distance(b)>1e-9) preview.passes.withIndex().filter { (_,pass) ->
                if(pass.size<2) false else {
                    val dx=b.x-a.x; val dy=b.y-a.y; val square=dx*dx+dy*dy
                    fun projection(p: Point) = ((p.x-a.x)*dx+(p.y-a.y)*dy)/square
                    fun collinear(p: Point) = kotlin.math.abs((p.x-a.x)*dy-(p.y-a.y)*dx)<=1e-7*kotlin.math.sqrt(square)
                    val first=projection(pass.first()); val last=projection(pass.last())
                    collinear(pass.first()) && collinear(pass.last()) && minOf(1.0,maxOf(first,last))-maxOf(0.0,minOf(first,last))>1e-8
                }
            }.sortedBy { (_,pass) -> minOf(a.distance(pass.first()),a.distance(pass.last())) }.forEach { (id,_) ->
                if(passDirections.lastOrNull()?.first!=id) passDirections.add(id to heading(a,b))
            }
        } }
        val uTurns=passDirections.zipWithNext().count { (a,b) -> angleDifference(a.second,b.second)>=150.0 }
        val metrics = MissionMetrics(distance,transit,uTurns,stops.toInt(),angle,
            distance/config.speedMps+angle/config.turnDegreesPerSecond+stops*config.stopSeconds,
            terrain.target.area,coverage.covered, (terrain.target.area-coverage.covered).coerceAtLeast(0.0),
            coverage.repeated,coverage.extra,violations)
        val score=config.weights.score(metrics)
        val status = when {
            !score.isFinite() -> PlanStatus.NUMERIC_FAILURE
            violations>0 -> PlanStatus.NO_SOLUTION_FOUND
            metrics.coverage+1e-9 < config.minimumCoverage || metrics.coveredAreaM2+1e-6 < minimumArea -> PlanStatus.PARTIAL_COVERAGE
            else -> PlanStatus.VALIDATED_IN_MODEL
        }
        return CandidateResult(name,status,preview,metrics,if (status==PlanStatus.VALIDATED_IN_MODEL) score else null,
            when (status) {
                PlanStatus.PARTIAL_COVERAGE -> listOf(tr("Residual area ${metrics.uncoveredAreaM2} m²; threshold or reference not reached",
                    "Surface résiduelle ${metrics.uncoveredAreaM2} m² ; seuil ou référence non atteint"))
                PlanStatus.NO_SOLUTION_FOUND -> listOf(tr("Envelope or tool violation", "Violation de l'enveloppe ou de l'outil"))
                PlanStatus.NUMERIC_FAILURE -> listOf(tr("Non-finite score: weights too large", "Score non fini : pondérations trop grandes"))
                else -> emptyList()
            },mission)
    }
    data class Coverage(val covered: Double,val repeated: Double,val extra: Double)
    companion object {
        /** Temporal visits: continuity at a vertex does not count as a second visit.
         * Invariant under line subdivision, including for multiple overlaps. */
        internal fun coverage(mission: List<MissionLeg>,target: JtsGeometry,radius: Double,indexed: Boolean = true,
            rasterCell: Double = 0.0): Coverage = normalize(mission).let {
                if(rasterCell>0) RasterCoverage.measure(it,target,radius,rasterCell)
                else if(indexed) indexedCoverage(it,target,radius) else referenceCoverage(it,target,radius) }

        /** A collinear vertex does not change a visit. Removing it prevents the facet orientation
         * of the JTS caps from creating a numerical revisit of a few mm². */
        private fun normalize(mission: List<MissionLeg>): List<MissionLeg> {
            val result=mutableListOf<MissionLeg>()
            var path=mutableListOf<Point>()
            fun flush() { if(path.size>=2) result.add(MissionLeg(path.toList(),true)); path=mutableListOf() }
            mission.forEach { leg ->
                if(!leg.cutting) { if(length(leg.path)>1e-9) { flush(); result.add(leg) } }
                else {
                    if(path.isNotEmpty() && path.last()!=leg.path.firstOrNull()) flush()
                    for(point in leg.path) {
                        if(path.lastOrNull()==point) continue
                        if(path.size>=2) {
                            val a=path[path.size-2]; val b=path.last()
                            val cross=(b.x-a.x)*(point.y-b.y)-(b.y-a.y)*(point.x-b.x)
                            val dot=(b.x-a.x)*(point.x-b.x)+(b.y-a.y)*(point.y-b.y)
                            if(kotlin.math.abs(cross)<=1e-12*maxOf(1.0,a.distance(b)*b.distance(point)) && dot>0) path.removeAt(path.lastIndex)
                        }
                        path.add(point)
                    }
                }
            }
            flush(); return result
        }

        private data class Sweep(val index: Int,val shape: JtsGeometry,val fresh: JtsGeometry)
        /** Repeated global unions dominated the profile: index of the earlier neighbouring footprints
         * only, then batched unions. Same temporal definition as the sequential oracle. */
        private fun indexedCoverage(mission: List<MissionLeg>,target: JtsGeometry,radius: Double): Coverage {
            val sweeps=mutableListOf<Sweep>()
            var previous: Point?=null
            mission.forEach { leg ->
                if(!leg.cutting) { if(length(leg.path)>1e-9) previous=null }
                else leg.path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-9 }.forEach { (a,b) ->
                    val shape=OverlayNGRobust.overlay(line(listOf(a,b)).buffer(radius,16),target,OverlayNG.INTERSECTION).buffer(0.0)
                    val fresh=if(previous==a) OverlayNGRobust.overlay(shape,disc(a,radius),OverlayNG.DIFFERENCE).buffer(0.0) else shape
                    sweeps.add(Sweep(sweeps.size,shape,fresh)); previous=b
                }
            }
            val index=org.locationtech.jts.index.strtree.STRtree()
            sweeps.forEach { if(!it.shape.isEmpty) index.insert(it.shape.envelopeInternal,it) }
            index.build()
            val overlaps=sweeps.mapNotNull { sweep ->
                if(sweep.shape.isEmpty) return@mapNotNull null
                val neighbors=index.query(sweep.shape.envelopeInternal).filterIsInstance<Sweep>().filter { it.index<sweep.index }
                    .sortedBy { it.index }.map { it.shape }
                if(neighbors.isEmpty()) null else OverlayNGRobust.overlay(sweep.fresh,
                    OverlayNGRobust.union(neighbors),OverlayNG.INTERSECTION).buffer(0.0)
            }
            fun area(shapes: List<JtsGeometry>) = if(shapes.isEmpty()) 0.0 else OverlayNGRobust.union(shapes).area
            return Coverage(area(sweeps.map { it.shape }),area(overlaps),overlaps.sumOf { it.area })
        }
        /** Oracle kept for ablation and equivalence tests. */
        private fun referenceCoverage(mission: List<MissionLeg>,target: JtsGeometry,radius: Double): Coverage {
            var covered: JtsGeometry = geometryFactory.createPolygon()
            var repeated: JtsGeometry = geometryFactory.createPolygon()
            var extra=0.0
            var previous: Point?=null
            mission.forEach { leg ->
                if (!leg.cutting) { if (length(leg.path)>1e-9) previous=null }
                else leg.path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-9 }.forEach { (a,b) ->
                    val sweep = OverlayNGRobust.overlay(line(listOf(a,b)).buffer(radius,16),target,OverlayNG.INTERSECTION).buffer(0.0)
                    val freshVisit = if (previous==a) OverlayNGRobust.overlay(sweep,disc(a,radius),OverlayNG.DIFFERENCE).buffer(0.0) else sweep
                    val overlap = OverlayNGRobust.overlay(freshVisit,covered,OverlayNG.INTERSECTION).buffer(0.0)
                    extra += overlap.area
                    repeated = OverlayNGRobust.overlay(repeated,overlap,OverlayNG.UNION).buffer(0.0)
                    covered = OverlayNGRobust.overlay(covered,sweep,OverlayNG.UNION).buffer(0.0)
                    previous=b
                }
            }
            return Coverage(covered.area,repeated.area,extra)
        }
    }
}
