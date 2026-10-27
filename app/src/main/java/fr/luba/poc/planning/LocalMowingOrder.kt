package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*

/** Finish the started cell, then join a neighbouring block. No pass splitting,
 * no global permutation after this choice, no unvalidated connection.
 * Checkerboard: all passes of one orientation before those of the other. With a [SweepBar],
 * a virtual bar advances over the zone: a cell is only started if nothing remains
 * entirely behind it (a whole side mowed before moving on).
 */
internal object LocalMowingOrder {
    /**
     * Sweep bar. [relativeDegrees]: direction of advance relative to the passes (90 = perpendicular, the bar follows
     * the rows; 0 = parallel, the bar cuts across the passes). [sign]: direction of advance on the first orientation;
     * a next orientation (checkerboard) advances from the side where the robot is.
     */
    data class SweepBar(val relativeDegrees: Double,val sign: Int,val windowM: Double = WINDOW_M) {
        init { require((sign==1 || sign==-1) && windowM.isFinite()) }
    }
    /** Bar window: +1 m, a cell starting less than 1 m after the end of the least advanced block may be started
     * (neighbouring cells whose edges are not aligned); −1 m, it must overlap the bar by at least 1 m. */
    const val WINDOW_M = 1.0
    /** Bars tried: −1 m and +1 m windows, perpendicular then parallel to the passes, in both directions. */
    val SWEEP_BARS = listOf(-WINDOW_M,WINDOW_M).flatMap { window ->
        listOf(90.0,0.0).flatMap { relative -> listOf(SweepBar(relative,1,window),SweepBar(relative,-1,window)) } }
    /** [oneWay]: entered only at the start of [path] (a Zamboni run taken in the order of its cell). */
    private data class Task(val index: Int,val path: List<Point>,val cell: Int?,val oneWay: Boolean = false)
    /**
     * Orientation of a checkerboard pass: 0 = the computed one, 1 = the perpendicular. The sweep numbers its cells
     * angle × 100,000 + rank ([CoveragePlanner]); without this number, the dominant direction of the pass segments.
     */
    private fun layerOf(preview: Preview,cell: Int?,path: List<Point>): Int {
        if(cell!=null && cell>=0) return if(cell/100000==preview.angle) 0 else 1
        val across=path.zipWithNext().sumOf { (a,b) ->
            if(angleDifference(heading(a,b)%180,preview.angle.toDouble())%180 in 45.0..135.0) a.distance(b) else -a.distance(b) }
        return if(across>0) 1 else 0
    }
    /** Gap beyond which a cell is "entirely behind" another. */
    private const val BEHIND_M = 0.5
    /**
     * Backtracks of a planned order: cells started while a remaining cell (same orientation) lay entirely behind it.
     * Counted on the axis perpendicular then parallel to the passes, each in its best direction: 0 when the robot
     * never left anything behind it, in either direction.
     */
    private class Visit(val cell: Int,val layer: Int,val points: MutableList<Point>)
    /** Cells in visiting order (consecutive passes of the same cell grouped), border loops excluded. */
    private fun visits(scheduled: Preview): List<Visit> {
        val layered=scheduled.mode==CoverageMode.CHECKERBOARD
        val visits=mutableListOf<Visit>()
        for(path in scheduled.cuttingPaths) {
            val index=scheduled.runs.indexOfFirst { it==path || it.reversed()==path }
            if(index<0) continue
            val cell=scheduled.runCellIds.getOrNull(index) ?: index
            val layer=if(layered) layerOf(scheduled,scheduled.runCellIds.getOrNull(index),path) else 0
            val last=visits.lastOrNull()
            if(last?.cell==cell) last.points.addAll(path) else visits.add(Visit(cell,layer,path.toMutableList()))
        }
        return visits
    }
    /** Checkerboard: direction changes between two successive cells (1 = one whole direction, then the other). */
    fun layerSwitches(scheduled: Preview): Int = visits(scheduled).zipWithNext().count { (a,b) -> a.layer!=b.layer }
    fun backtracks(scheduled: Preview): Int {
        return visits(scheduled).groupBy { it.layer }.entries.sumOf { (layer,group) ->
            listOf(90.0,0.0).sumOf { relative ->
                val axis=Math.toRadians(scheduled.angle+90.0*layer+relative)
                val c=kotlin.math.cos(axis);val s=kotlin.math.sin(axis)
                val ranges=group.map { v -> v.points.map { it.x*c+it.y*s }.let { it.min() to it.max() } }
                listOf(1,-1).minOf { sign ->
                    val signed=ranges.map { (a,b) -> if(sign>0) a to b else -b to -a }
                    signed.indices.count { i -> signed.drop(i+1).any { it.second<signed[i].first-BEHIND_M } }
                }
            }
        }
    }
    fun schedule(preview: Preview,from: Point,bordersFirst: Boolean,nearbyNoGo: Boolean,
        link: (List<Point>?,List<Point>) -> Double, safe: (List<Point>) -> Boolean,
        checkpoint: () -> Unit = {}, entryDistance: ((List<Point>) -> Double)? = null,
        limit: Int = Int.MAX_VALUE,rotateLoops: Boolean = true,
        loopSafe: ((List<Point>) -> Boolean)? = null,
        loopEntrySafe: ((Point) -> Boolean)? = null, reserveAllLoopEntries: Boolean = false,
        /** Proven lower bound of entryDistance (geodesic without heading): pruning without effect on the choice. */
        entryLowerBound: ((List<Point>) -> Double)? = null,
        /** No-go loops: slanted entry and exit, pivots outside the margin ([NoGoLoopLeads]). */
        noGoLoopLead: ((List<Point>) -> NoGoLoopLeads.Leaded?)? = null,
        sweepBar: SweepBar? = null,
        /** Checkerboard: direction mowed first (0 = the computed one, 1 = the perpendicular); null = the nearest. */
        firstLayer: Int? = null,
        /** Zamboni: false, the runs of a cell are not chained (order of the Zigzag mode), fallback of [AutomaticPlanner]. */
        chained: Boolean = true): Preview {
        // Spiral from the centre ([Preview.outwardRuns]): each chain entered at its centre end only, once its core and the
        // chains inside it are mowed.
        val outward=preview.outwardRuns
        val rows=preview.runs.mapIndexed { i,path -> Task(i,path,preview.runCellIds.getOrNull(i) ?: i,oneWay=i in outward) }.toMutableList()
        fun ready(task: Task)=outward[task.index]?.let { before -> rows.none { it.index in before } } ?: true
        fun List<Task>.ready()=if(outward.isEmpty()) this else filter(::ready)
        val outer=mutableListOf<Task>(); val holes=mutableListOf<Task>()
        preview.borders.forEachIndexed { i,path ->
            (if(preview.borderKinds.getOrElse(i) { i>=preview.outerLoops }) holes else outer)
                .add(Task(preview.runs.size+i,path,null))
        }
        val result=mutableListOf<List<Point>>()
        val verified=mutableMapOf<List<Point>,Boolean>()
        val verifiedLoops=mutableMapOf<List<Point>,Boolean>()
        var previous: List<Point>?=null
        val holeTasks=holes.map { it.index }.toSet()
        // No-go loop extended with an entry and an exit at a distance: the pivot no longer happens on the loop.
        val leads=mutableMapOf<Triple<Int,Point?,Point>,NoGoLoopLeads.Leaded?>()
        // Extended path → (extension, loop in its original direction, checked once per direction).
        val leaded=mutableMapOf<List<Point>,Pair<NoGoLoopLeads.Leaded,List<Point>>>()
        val plainOf=mutableMapOf<List<Point>,LoopEntries.Entry>()
        fun withLead(task: Task,entry: LoopEntries.Entry): LoopEntries.Entry {
            if(noGoLoopLead==null || task.index !in holeTasks || entry.validationPath.first()!=entry.validationPath.last()) return entry
            val lead=leads.getOrPut(Triple(task.index,entry.validationPath.getOrNull(1),entry.point)) { noGoLoopLead(entry.path) } ?: return entry
            val path=lead.path
            leaded[path]=lead to entry.validationPath
            plainOf[path]=entry
            return LoopEntries.Entry(path.first(),path) { path }
        }
        /** Extended entry, then, if it has neither a passing check nor a safe link, the loop alone (former behaviour). */
        fun options(entry: LoopEntries.Entry): List<LoopEntries.Entry> = listOfNotNull(entry,plainOf[entry.validationPath])
        fun choose(pool: List<Task>): Pair<Task,List<Point>> {
            checkpoint()
            val position=previous?.last() ?: from
            val variants=pool.flatMap { task ->
                (if(task.cell==null && rotateLoops) LoopEntries.entries(task.path,position)
                    else if(task.oneWay) listOf(LoopEntries.Entry(task.path.first(),task.path) { task.path })
                    else listOf(LoopEntries.Entry(task.path.first(),task.path) { task.path },
                        LoopEntries.Entry(task.path.last(),task.path.reversed()) { task.path.reversed() }))
                    .map { task to withLead(task,it) }
            }
                .sortedWith(compareBy<Pair<Task,LoopEntries.Entry>> { it.second.point.distance(position) }
                    .thenBy { it.first.index })
            fun safeEntry(task: Task,entry: LoopEntries.Entry): Boolean =
                leaded[entry.validationPath]?.let { (lead,loop) ->
                    // Whole loop checked once per direction; entry and exit checked with their loop segment.
                    verifiedLoops.getOrPut(loop) { loopSafe?.invoke(loop) ?: safe(loop) } &&
                        verified.getOrPut(lead.entryCheck) { safe(lead.entryCheck) } && verified.getOrPut(lead.exitCheck) { safe(lead.exitCheck) }
                } ?: if(task.cell==null && loopSafe!=null && entry.validationPath.first()==entry.validationPath.last())
                    // Check of the WHOLE loop, closure included, for each
                    // direction. The cyclic rotation/collinear subdivision keeps the
                    // same translations and turns; the link is still checked separately.
                    verifiedLoops.getOrPut(entry.validationPath) { loopSafe(entry.validationPath) }
                else verified.getOrPut(entry.path) { safe(entry.path) }
            // Extra reserve at the initial start. The following steps
            // keep the directed links and the full mission validation.
            fun admissible(task: Task,entry: LoopEntries.Entry) = previous!=null && !reserveAllLoopEntries || task.cell!=null || loopEntrySafe==null ||
                entry.point.distance(entry.validationPath.first())<1e-8 || loopEntrySafe(entry.point)
            if(previous==null && entryDistance!=null) {
                var best: Triple<Task,List<Point>,Double>?=null
                var bestCost=Double.POSITIVE_INFINITY
                for((task,first) in variants) for(entry in options(first)) {
                    checkpoint()
                    // The Euclidean distance bounds the actual link:
                    // no farther candidate can beat the best access.
                    if(entry===first && entry.point.distance(position)>(best?.third ?: Double.POSITIVE_INFINITY)+1e-8) break
                    if(!admissible(task,entry)) continue
                    val path=entry.path
                    // A candidate whose lower bound already exceeds the best access would be rejected
                    // by the next test: no need to compute its full oriented link.
                    if(best!=null && entryLowerBound!=null && entryLowerBound(path)>best!!.third+1e-8) continue
                    val distance=entryDistance(path)
                    if(!distance.isFinite() || distance>(best?.third ?: Double.POSITIVE_INFINITY)+1e-8) continue
                    if(!safeEntry(task,entry)) continue
                    val cost=link(null,path)
                    if(cost.isFinite() && (best==null || distance<best!!.third-1e-8 || cost<bestCost && distance<=best!!.third+1e-8)) {
                        best=Triple(task,path,distance);bestCost=cost
                    }
                    // Extended entry chosen or possible: the loop alone is only a fallback.
                    if(cost.isFinite()) break
                }
                return requireNotNull(best) { tr("No reachable safe entry", "Aucune entrée sûre accessible") }.let { it.first to it.second }
            }
            // Three neighbouring ends at a time. Widen only if no
            // safe directed link exists, without building a dense n² graph.
            for(batch in variants.chunked(3)) {
                val choices=batch.mapNotNull { (task,first) ->
                    options(first).firstNotNullOfOrNull { entry ->
                        if(!admissible(task,entry)) return@firstNotNullOfOrNull null
                        val path=entry.path
                        if(!safeEntry(task,entry)) null else {
                            val cost=link(previous,path)
                            if(cost.isFinite()) Triple(task,path,cost) else null
                        }
                    }
                }
                if(choices.isNotEmpty()) return choices.minBy { it.third }.let { it.first to it.second }
            }
            throw IllegalArgumentException(tr("No safe link to the remaining blocks", "Aucun raccord sûr vers les blocs restants"))
        }
        fun take(pool: MutableList<Task>) {
            while(pool.isNotEmpty() && result.size<limit) {
                val (task,path)=choose(pool)
                pool.remove(task);result.add(path);previous=path
            }
        }
        if(!nearbyNoGo && bordersFirst) { take(holes);take(outer) }
        else if(bordersFirst) take(outer)
        // Checkerboard: orientation of a pass (0 = the computed one, 1 = the perpendicular).
        val layered=preview.mode==CoverageMode.CHECKERBOARD
        val layers=rows.associate { task -> task.index to if(layered) layerOf(preview,preview.runCellIds.getOrNull(task.index),task.path) else 0 }
        var activeCell: Int?=null
        var activeLayer: Int?=null
        // Zamboni: the runs of a cell are one front-advancing order cut by breaks. The cell is entered at its first run
        // forwards or at its last run backwards, then driven run after run in that order ([chainStep] +1 or -1); a run
        // without a safe link falls back to the nearest one of the cell. Without [chained], the order of the Zigzag mode.
        val chains=if(preview.mode!=CoverageMode.ZAMBONI || !chained) emptyMap() else rows.groupBy { it.cell }.filterValues { it.size>1 }
        var chainAt=-1; var chainStep=0
        fun chainEntries(pool: List<Task>): List<Task> = if(chains.isEmpty()) pool else pool.flatMap { task ->
            val chain=chains[task.cell] ?: return@flatMap listOf(task)
            listOfNotNull(task.copy(oneWay=true).takeIf { task.index==chain.first().index },
                task.copy(path=task.path.reversed(),oneWay=true).takeIf { task.index==chain.last().index })
        }
        /** Next run of the active Zamboni cell, in its order, or null (no chain, or no safe link to it). */
        fun chainNext(): Pair<Task,List<Point>>? {
            val chain=chains[activeCell] ?: return null
            val next=chain.getOrNull(chainAt+chainStep)?.takeIf { t -> chainStep!=0 && rows.any { it.index==t.index } } ?: return null
            return try { choose(listOf(next.copy(path=if(chainStep>0) next.path else next.path.reversed(),oneWay=true))) }
                catch(e: IllegalArgumentException) { null }
        }
        fun chosenEntry(pool: List<Task>): Pair<Task,List<Point>> =
            if(chains.isEmpty()) choose(pool) else try { choose(chainEntries(pool).ifEmpty { pool }) } catch(e: IllegalArgumentException) { choose(pool) }
        var sign=sweepBar?.sign ?: 1
        /** Projection of a block (whole cell or no-go loop) onto the bar's axis of advance. */
        fun span(tasks: List<Task>,axis: Double): Pair<Double,Double> {
            val c=kotlin.math.cos(axis);val s=kotlin.math.sin(axis)
            val values=tasks.flatMap { t -> t.path.map { it.x*c+it.y*s } }
            return values.min() to values.max()
        }
        fun open(): List<Task> {
            val loops=if(nearbyNoGo) holes.toList() else emptyList()
            // Started orientation finished before the other; no-go loops stay open to both. Before the
            // first pass, the requested direction, otherwise all: the start stays as close as possible.
            val current=(activeLayer ?: firstLayer)?.takeIf { l -> rows.any { layers[it.index]==l } }
            val candidates=(if(layered && current!=null) rows.filter { layers[it.index]==current } else rows).ready()
            val open=candidates+loops
            val bar=sweepBar ?: return open
            // Orientation of the open blocks: single outside checkerboard; in checkerboard, the started one, then the other.
            val layer=candidates.map { layers.getValue(it.index) }.distinct().singleOrNull()
            if(previous==null || layer==null) return open
            val axis=Math.toRadians(preview.angle+90.0*layer+bar.relativeDegrees)
            if(activeLayer!=null && layer!=activeLayer) {
                // Next orientation: the bar starts from the side where the robot is.
                val (low,high)=span(open,axis)
                val here=previous!!.last().let { it.x*kotlin.math.cos(axis)+it.y*kotlin.math.sin(axis) }
                sign=if(here-low<=high-here) 1 else -1
            }
            val units=open.groupBy { it.cell ?: -(it.index+1) }.values.map { tasks ->
                tasks to span(tasks,axis).let { (a,b) -> if(sign>0) a to b else -b to -a }
            }
            // Bar: end of the least advanced block. Only blocks starting before it (plus the window) are open.
            val position=units.minOf { it.second.second }
            return units.filter { (_,range) -> range.first<position+bar.windowM || range.second<=position+1e-9 }.flatMap { it.first }
        }
        // Position in [result] of each run scheduled, for the constraints of [outward] once ordered.
        val placed=HashMap<Int,Int>()
        while(result.size<limit && (rows.isNotEmpty() || nearbyNoGo && holes.isNotEmpty())) {
            val active=rows.filter { it.cell==activeCell }.ready()
            val (task,path)=if(activeCell!=null && active.isNotEmpty()) chainNext() ?: choose(active).also { chainStep=0 } else {
                val pool=open()
                val all=rows.ready()+if(nearbyNoGo) holes else emptyList()
                // Bar or orientation without a safe link: fall back to all remaining blocks rather than refuse.
                val entry=if(pool.size==all.size) chosenEntry(pool) else try { chosenEntry(pool) } catch(e: IllegalArgumentException) { chosenEntry(all) }
                chains[entry.first.cell]?.let { chain -> chainStep=if(!entry.first.oneWay) 0
                    else if(entry.first.index==chain.first().index && entry.first.path==chain.first().path) 1 else -1 }
                entry
            }
            if(task.cell==null) { holes.remove(task);activeCell=null }
            else {
                rows.removeAll { it.index==task.index };activeCell=task.cell
                placed[task.index]=result.size
                activeLayer=layers.getValue(task.index)
                chainAt=chains[task.cell]?.indexOfFirst { it.index==task.index } ?: -1
            }
            result.add(path);previous=path
        }
        if(!bordersFirst) { take(holes);take(outer) }
        // Zones of the paths kept only if every path is still found whole (split or rotated loop: unknown).
        // Spiral from the centre: each outward chain with the paths to mow before it, as positions in the order: its
        // constraints, and every path mowed before it within a core's reach of its centre end (pocket passes whichever
        // chain their pocket was given to, loops), so that a reordered resume keeps them first.
        val reach=preview.outwardReachM
        val outwardPaths=outward.entries.mapNotNull { (chain,before) -> placed[chain]?.let { at ->
            val start=preview.runs[chain].first()
            val near=if(reach<=0.0) emptyList() else (0 until at).filter { k ->
                result[k].zipWithNext().any { (a,b) -> Geometry.distance(start,a,b)<=reach+1e-6 } }
            at to (before.mapNotNull { placed[it] }+near).toSet() }
        }.filter { it.second.isNotEmpty() }.toMap()
        return preview.copy(scheduledPaths=result,localOnly=true,pathZoneIds=preview.zonesOf(result),outwardPaths=outwardPaths)
    }
}
