package fr.luba.poc.planning

import fr.luba.poc.map.*

/** A moved cyclic entry remains a border, even after an anchor is inserted. */
internal class TaskClassifier(terrain: PlanningTerrain,preview: Preview) {
    private val zones=terrain.zones.map { polygon(it.points).buffer(1e-7) }
    private val borders=preview.borders.map { line(it).buffer(1e-7) }
    private val cache=mutableMapOf<List<Point>,Pair<Int,Boolean>>()
    fun group(path: List<Point>): Pair<Int,Boolean> = cache.getOrPut(path) {
        val shape=line(path)
        zones.indexOfFirst { it.covers(shape) } to
            (path.first().distance(path.last())<=1e-8 && borders.any { it.covers(shape) })
    }
}

/** Exact repair of a window: permutations AND directions, directed links with headings.
 * Optimum of the additive cost over these tasks and anchors only, never of the continuous CPP.
 * Local cost cache to avoid rechecking the long blocks at each DP state.
 */
internal class NeighborhoodSequencer(private val terrain: PlanningTerrain,
    private val evaluator: MissionEvaluator, private val checkpoint: () -> Unit) {
    private val own = mutableMapOf<List<Point>,Double>()
    private val links = mutableMapOf<Pair<List<Point>?,List<Point>?>,Double>()
    private fun cost(path: List<Point>): Double = own.getOrPut(path) {
        if (terrain.envelope.pathCollision(path) == null) evaluator.pathCost(path) else Double.POSITIVE_INFINITY
    }
    private fun link(a: List<Point>?, b: List<Point>?): Double = links.getOrPut(a to b) { evaluator.linkCost(a,b) }

    /** Oriented Held–Karp, O(2^n n²), n <= 8; the outer ends are fixed. */
    internal fun exact(paths: List<List<Point>>, before: List<Point>? = null,
        after: List<Point>? = null): Pair<List<List<Point>>,Double> {
        require(paths.size in 1..8 && paths.all { it.size>=2 && length(it)>1e-8 })
        val n=paths.size
        val variants=paths.flatMap { listOf(it,it.reversed()) }
        val size=1 shl n
        val costs=Array(size) { DoubleArray(2*n) { Double.POSITIVE_INFINITY } }
        val parents=Array(size) { IntArray(2*n) { -1 } }
        for (v in variants.indices) costs[1 shl (v/2)][v]=cost(variants[v])+link(before,variants[v])
        for (mask in 1 until size) {
            if (mask%16==0) checkpoint()
            for (last in variants.indices) {
                val current=costs[mask][last]
                if (!current.isFinite()) continue
                for (next in variants.indices) {
                    val bit=1 shl (next/2)
                    if (mask and bit != 0) continue
                    val nextMask=mask or bit
                    val proposed=current+link(variants[last],variants[next])+cost(variants[next])
                    if (proposed<costs[nextMask][next]-1e-9) {
                        costs[nextMask][next]=proposed; parents[nextMask][next]=last
                    }
                }
            }
        }
        var last=variants.indices.minBy { costs[size-1][it]+link(variants[it],after) }
        val best=costs[size-1][last]+link(variants[last],after)
        if (!best.isFinite()) return paths to best
        var mask=size-1
        val result=mutableListOf<List<Point>>()
        while (mask!=0) {
            result.add(variants[last])
            val previous=parents[mask][last]
            mask=mask xor (1 shl (last/2)); last=previous
        }
        return result.reversed() to best
    }

    /** Deterministic LNS: remove a costly window, reinsert its tasks exactly.
     * No exchange across a zone/border separation; every task is kept.
     */
    fun optimize(preview: Preview): Preview {
        val paths=preview.cuttingPaths
        if (paths.isEmpty() || paths.size>64 || terrain.config.localSearchBudget==0) return preview
        val classifier=TaskClassifier(terrain,preview)
        fun group(path: List<Point>) = classifier.group(path)
        if (paths.any { group(it).first<0 }) return preview
        var order=paths
        data class Window(val begin: Int,val end: Int,val priority: Double)
        val windows=paths.indices.map { begin ->
            var end=begin+1
            while (end<paths.size && end-begin<6 && group(paths[end])==group(paths[begin])) end++
            val priority=(begin until end).sumOf { i -> link(paths.getOrNull(i-1),paths[i]) }+
                link(paths[end-1],paths.getOrNull(end))
            Window(begin,end,priority)
        }.sortedWith(compareByDescending<Window> { it.priority }.thenBy { it.begin })
        for (window in windows.take(terrain.config.localSearchBudget)) {
            checkpoint()
            val slice=order.subList(window.begin,window.end)
            val before=order.getOrNull(window.begin-1); val after=order.getOrNull(window.end)
            val old=slice.sumOf(::cost)+(listOf(before)+slice+listOf(after)).zipWithNext().sumOf { (a,b) -> link(a,b) }
            val repaired=exact(slice,before,after)
            if (repaired.second<old-1e-9) order=order.take(window.begin)+repaired.first+order.drop(window.end)
        }
        // Windows stay within their zone: the order metadata does not change.
        return preview.copy(scheduledPaths=order,localOnly=true,pathZoneIds=preview.zonesOf(order),outwardPaths=emptyMap())
    }
}
