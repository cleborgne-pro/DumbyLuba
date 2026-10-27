package fr.luba.poc.planning

import fr.luba.poc.map.*

/** Exact DP over both directions of each block, for a fixed order; bounded 2-opt/relocation. */
internal class TaskSequencer(private val terrain: PlanningTerrain,private val evaluator: MissionEvaluator,
    private val checkpoint: () -> Unit) {
    private data class Task(val path: List<Point>,val zone: Int,val border: Boolean)
    private fun directions(tasks: List<Task>): Pair<List<List<Point>>,Double> {
        val variants=tasks.map { listOf(it.path,it.path.reversed()) }
        val costs=Array(tasks.size) { DoubleArray(2) { Double.POSITIVE_INFINITY } }
        val parents=Array(tasks.size) { IntArray(2) }
        for (i in tasks.indices) for (sense in 0..1) {
            val path=variants[i][sense]
            if (terrain.envelope.pathCollision(path)!=null) continue
            val own=evaluator.pathCost(path)
            if (i==0) costs[i][sense]=own+evaluator.linkCost(null,path)
            else for (previous in 0..1) {
                val score=costs[i-1][previous]+own+evaluator.linkCost(variants[i-1][previous],path)
                if (score<costs[i][sense]-1e-9) { costs[i][sense]=score; parents[i][sense]=previous }
            }
        }
        var sense=(0..1).minBy { costs.last()[it]+evaluator.linkCost(variants.last()[it],null) }
        val score=costs.last()[sense]+evaluator.linkCost(variants.last()[sense],null)
        val selected=MutableList(tasks.size) { emptyList<Point>() }
        for (i in tasks.indices.reversed()) { selected[i]=variants[i][sense]; sense=parents[i][sense] }
        return selected to score
    }
    internal fun optimizeDirections(paths: List<List<Point>>) = directions(paths.map { Task(it,0,false) })
    fun optimize(preview: Preview,preserveLoopClasses: Boolean = false): Preview {
        val paths=preview.cuttingPaths
        if (paths.isEmpty() || paths.size>64) return preview
        val zones=terrain.zones.map { polygon(it.points).buffer(1e-7) }
        val classifier=if(preserveLoopClasses) TaskClassifier(terrain,preview) else null
        val initial=paths.map { path -> Task(path,zones.indexOfFirst { it.covers(line(path)) }.let { if(it<0) -1 else it },
            classifier?.group(path)?.second ?: preview.borders.any { it==path || it==path.reversed() }) }
        // Membership cannot be proven: keep the complete order rather than bypass a lock.
        if (initial.any { it.zone<0 }) return preview
        var order=initial
        var best=directions(order)
        var attempts=0
        loop@ for (i in order.indices) for (j in i+1 until order.size) {
            if (attempts>=terrain.config.localSearchBudget/2) break@loop
            val slice=order.subList(i,j+1)
            if (slice.map { it.border }.distinct().size>1 || slice.map { it.zone }.distinct().size>1) continue
            checkpoint(); attempts++
            val proposed=order.take(i)+slice.reversed()+order.drop(j+1)
            val candidate=directions(proposed)
            if (candidate.second<best.second-1e-9) { order=proposed; best=candidate }
        }
        loop@ for (i in order.indices) for (j in order.indices) {
            if (attempts>=terrain.config.localSearchBudget*(if(terrain.config.optimizeZoneOrder) 3 else 4)/4) break@loop
            if (i==j || order.subList(minOf(i,j),maxOf(i,j)+1).any { it.zone!=order[i].zone || it.border!=order[i].border }) continue
            checkpoint(); attempts++
            val proposed=order.toMutableList().also { it.add(j,it.removeAt(i)) }
            val candidate=directions(proposed)
            if (candidate.second<best.second-1e-9) { order=proposed; best=candidate }
        }
        if (terrain.config.optimizeZoneOrder) {
            // Permutation of whole-zone blocks; tasks are never interleaved.
            val groups=order.groupBy { it.zone }.values.toList()
            for (i in groups.indices) for (j in i+1 until groups.size) {
                if (attempts>=terrain.config.localSearchBudget) break
                checkpoint(); attempts++
                val candidate=directions((groups.take(i)+groups.subList(i,j+1).reversed()+groups.drop(j+1)).flatten())
                if (candidate.second<best.second-1e-9) best=candidate
            }
        }
        if(!best.second.isFinite()) return preview
        val zoneOrder=best.first.map { path -> terrain.zones[zones.indexOfFirst { it.covers(line(path)) }].id }.distinct()
        val angles=preview.zoneIds.zip(preview.zoneAngles).toMap()
        val phases=preview.zoneIds.zip(preview.zonePhases).toMap()
        val modes=preview.zoneIds.zip(preview.zoneModes).toMap()
        val reordered=preview.zoneIds.zip(preview.zoneReorderedCells).toMap()
        return preview.copy(scheduledPaths=best.first,localOnly=true,zoneIds=zoneOrder,pathZoneIds=preview.zonesOf(best.first),outwardPaths=emptyMap(),
            zoneAngles=zoneOrder.map { angles[it] ?: preview.angle },
            zonePhases=if(phases.size==preview.zoneIds.size) zoneOrder.map { phases[it] ?: 0.5 } else emptyList(),
            zoneModes=if(modes.size==preview.zoneIds.size) zoneOrder.map { modes[it] ?: preview.mode } else emptyList(),
            zoneReorderedCells=if(reordered.size==preview.zoneIds.size) zoneOrder.map { reordered[it] ?: 0 } else emptyList())
    }
}
