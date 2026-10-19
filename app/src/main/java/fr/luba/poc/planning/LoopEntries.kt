package fr.luba.poc.planning

import fr.luba.poc.map.Point

/** Move the origin of a loop, never cut an open pass. Projections
 * subdivide a collinear segment: same segments covered, whole loop kept.
 * Each candidate must still pass the link and chassis checks.
 */
internal object LoopEntries {
    class Entry(val point: Point,val validationPath: List<Point>,build: () -> List<Point>) {
        val path: List<Point> by lazy(LazyThreadSafetyMode.NONE,build)
    }
    fun entries(path: List<Point>,from: Point): List<Entry> {
        if(path.size<4 || path.first().distance(path.last())>1e-8)
            return listOf(Entry(path.first(),path) { path },Entry(path.last(),path.reversed()) { path.reversed() })
        val result=linkedMapOf<Pair<Int,Point>,Entry>()
        for((direction,loop) in listOf(path,path.reversed()).withIndex()) {
            loop.zipWithNext().forEachIndexed { i,(a,b) ->
                val dx=b.x-a.x;val dy=b.y-a.y;val square=dx*dx+dy*dy
                val t=if(square<1e-12) 0.0 else (((from.x-a.x)*dx+(from.y-a.y)*dy)/square).coerceIn(0.0,1.0)
                for(point in listOf(Point(a.x+t*dx,a.y+t*dy),a)) {
                    result.getOrPut(direction to point) { Entry(point,loop) {
                        val rotated=listOf(point)+loop.drop(i+1)+loop.drop(1).take(i)+point
                        rotated.filterIndexed { index,p -> index==0 || p.distance(rotated[index-1])>1e-8 }
                    } }
                }
            }
        }
        return result.values.toList()
    }
}
