package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import java.util.PriorityQueue
import kotlin.math.abs

/** Polygonal shortest path: reflex vertices and visible segments tangent to the outlines.
 *
 * Lazy A*: candidate links (tangency, planar computation) are queued without geometric check;
 * the visibility of a link is only checked when it is removed from the queue. The Euclidean
 * heuristic is consistent: the first validated removal of a vertex gives its minimum distance,
 * like the previous exhaustive search.
 * Checks are memoised and the instance can be shared between threads.
 */
internal class ShortestRoutes(area: Geometry, private val safe: (Point, Point) -> Boolean) {
    private data class Corner(val point: Point, val before: Point, val after: Point)
    private fun distance(a: Point, b: Point): Double { val dx=a.x-b.x; val dy=a.y-b.y; return kotlin.math.sqrt(dx*dx+dy*dy) }
    private fun cross(a: Point, b: Point, c: Point) =
        (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private val corners = buildList {
        fun ring(line: LineString, shell: Boolean) {
            var points = line.coordinates.dropLast(1).map { Point(it.x,it.y) }
            val signedArea = points.indices.sumOf { i ->
                val a=points[i]; val b=points[(i+1)%points.size]; a.x*b.y-b.x*a.y
            }
            // The free surface is always on the left of the edges.
            if ((signedArea > 0) != shell) points = points.reversed()
            points.indices.forEach { i ->
                val before=points[(i+points.size-1)%points.size]
                val p=points[i]; val after=points[(i+1)%points.size]
                if (cross(before,p,after) < -1e-12) add(Corner(p,before,after))
            }
        }
        for (i in 0 until area.numGeometries) {
            val polygon=area.getGeometryN(i) as? Polygon ?: continue
            if (polygon.isEmpty) continue
            ring(polygon.exteriorRing,true)
            for (j in 0 until polygon.numInteriorRing) ring(polygon.getInteriorRingN(j),false)
        }
    }
    private fun tangent(corner: Corner, p: Point): Boolean {
        val a=cross(corner.point,p,corner.before)
        val b=cross(corner.point,p,corner.after)
        return a*b >= 0.0 || abs(a)<1e-10 || abs(b)<1e-10
    }
    /** Neighbours compatible by tangency only; visibility is checked later. */
    private val candidates = arrayOfNulls<IntArray>(corners.size)
    private fun candidates(i: Int): IntArray = candidates[i] ?: run {
        val a=corners[i]
        corners.indices.filter { j -> j!=i && tangent(a,corners[j].point) && tangent(corners[j],a.point) }.toIntArray()
    }.also { candidates[i]=it }
    /** 0 unknown, 1 visible, 2 hidden; triangular matrix (≤ 2 MB) if reasonable, otherwise a map.
     * Benign concurrent writes: one byte, deterministic result. */
    private val pairState: ByteArray? = if (corners.size <= 2048) ByteArray(corners.size*(corners.size-1)/2+1) else null
    private val pairMap = HashMap<Long,Boolean>()
    private fun visible(i: Int, j: Int): Boolean {
        val a=minOf(i,j); val b=maxOf(i,j)
        pairState?.let { state ->
            val k=b*(b-1)/2+a
            when(state[k].toInt()) { 1 -> return true; 2 -> return false }
            val result=safe(corners[a].point,corners[b].point)
            state[k]=if(result) 1 else 2
            return result
        }
        val key=a.toLong()*corners.size+b
        synchronized(pairMap) { pairMap[key] }?.let { return it }
        val result=safe(corners[a].point,corners[b].point)
        synchronized(pairMap) { pairMap[key]=result }
        return result
    }

    // Simulation returns change their start but keep the same destination.
    // Do not recompute the visibility of the vertices towards that destination on every search.
    private val targetVisibility = object : LinkedHashMap<Point, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Point, ByteArray>?) = size > 64
    }

    private class Visit(val node: Int, val parent: Int, val cost: Double, val estimate: Double, val verified: Boolean)

    fun between(start: Point, end: Point): List<Point> {
        if(safe(start,end)) return listOf(start,end)
        val toEnd = synchronized(targetVisibility) { targetVisibility.getOrPut(end) { ByteArray(corners.size) } }
        val target=corners.size; val source=corners.size+1
        val closed=BooleanArray(corners.size+1)
        val distances=DoubleArray(corners.size+1) { Double.POSITIVE_INFINITY }
        // Best cost queued by an already verified link: the only safe pruning criterion.
        val tentative=DoubleArray(corners.size) { Double.POSITIVE_INFINITY }
        val previous=IntArray(corners.size+1) { -1 }
        val queue=PriorityQueue<Visit>(compareBy { it.estimate })
        fun h(j: Int) = if(j==target) 0.0 else distance(corners[j].point,end)
        // Links from the start and to the destination: deferred until removal.
        corners.forEachIndexed { i,c ->
            if(tangent(c,start)) { val cost=distance(start,c.point); queue.add(Visit(i,source,cost,cost+h(i),false)) }
        }
        fun reachesEnd(i: Int): Boolean {
            when(toEnd[i].toInt()) { 1 -> return true; 2 -> return false }
            val corner=corners[i]
            val result=tangent(corner,end) && safe(corner.point,end)
            toEnd[i]=if(result) 1 else 2
            return result
        }
        while(queue.isNotEmpty()) {
            val visit=queue.remove(); val i=visit.node
            if(closed[i]) continue
            if(!visit.verified) {
                val valid=if(visit.parent==source) safe(start,corners[i].point) else reachesEnd(visit.parent)
                if(!valid) continue
            }
            closed[i]=true; distances[i]=visit.cost; previous[i]=visit.parent
            if(i==target) break
            val corner=corners[i]
            if(tangent(corner,end) && toEnd[i].toInt()!=2) {
                val cost=visit.cost+distance(corner.point,end)
                queue.add(Visit(target,i,cost,cost,false))
            }
            for(j in candidates(i)) if(!closed[j]) {
                val cost=visit.cost+distance(corner.point,corners[j].point)
                if(cost<tentative[j] && visible(i,j)) {
                    tentative[j]=cost
                    queue.add(Visit(j,i,cost,cost+h(j),true))
                }
            }
        }
        require(distances[target].isFinite()) { tr("No mapped link to the base or between the zones.", "Pas de liaison cartographiée vers la base ou entre les zones.") }
        val path=mutableListOf(end)
        var i=previous[target]
        while(i>=0 && i!=source) { path.add(corners[i].point); i=previous[i] }
        path.add(start)
        return path.reversed().zipWithNext().fold(mutableListOf(start)) { result,(_,b) ->
            if(result.last()!=b) result.add(b)
            result
        }
    }
}
