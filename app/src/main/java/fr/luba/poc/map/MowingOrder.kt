package fr.luba.poc.map

/** Cutting order only: the links stay checked by the simulation engine. */
object MowingOrder {
    private fun enterLoop(loop: List<Point>, from: Point, anchors: List<Point>): List<Point> {
        if (loop.size < 4 || loop.first().distance(loop.last()) > 1e-8) return loop
        val entries = loop.zipWithNext().mapIndexed { segment,(a,b) ->
            val dx=b.x-a.x; val dy=b.y-a.y
            val denominator=dx*dx+dy*dy
            val t=if (denominator < 1e-12) 0.0 else
                (((from.x-a.x)*dx+(from.y-a.y)*dy)/denominator).coerceIn(0.0,1.0)
            segment to Point(a.x+t*dx,a.y+t*dy)
        }
        // Do not replace a verified manoeuvre entry with a projection that forces a pivot against a boundary or an
        // exclusion.
        val verified = loop.zipWithNext().flatMapIndexed { segment,(a,b) ->
            anchors.filter { Geometry.distance(it,a,b)<1e-8 }.map { segment to it }
        }
        val candidates = if (anchors.isEmpty()) entries else verified
        if (candidates.isEmpty()) return loop
        val (segment,point) = candidates.minBy { it.second.distance(from) }
        val rotated = listOf(point)+loop.drop(segment+1)+loop.drop(1).take(segment)+point
        return rotated.filterIndexed { i,p -> i == 0 || p.distance(rotated[i-1]) > 1e-8 }
    }
    fun apply(plan: Preview, bordersFirst: Boolean, nearbyNoGo: Boolean, base: Point?): Preview {
        val outer = plan.borders.filterIndexed { i,_ -> !plan.borderKinds.getOrElse(i) { i >= plan.outerLoops } }
        val noGo = plan.borders.filterIndexed { i,_ -> plan.borderKinds.getOrElse(i) { i >= plan.outerLoops } }
        fun nearest(paths: List<List<Point>>, from: Point?, groups: List<Int> = emptyList()): List<List<Point>> {
            if (from == null || paths.isEmpty()) return paths
            val entered = paths.map { enterLoop(it,from,plan.borderAnchors) }
            val first = entered.indices.minBy { entered[it].first().distance(from) }
            val group=groups.getOrNull(first)
            val same=entered.indices.filter { it!=first && group!=null && groups.getOrNull(it)==group }
            return listOf(entered[first])+same.map { entered[it] }+entered.filterIndexed { i,_ -> i!=first && i !in same }
        }
        val rows = nearest(plan.runs,base,plan.runCellIds)
        val ordered = mutableListOf<List<Point>>()
        if (!nearbyNoGo) {
            val borderPaths = nearest(noGo,base)+nearest(outer,noGo.lastOrNull()?.last() ?: base)
            if (bordersFirst) ordered.addAll(borderPaths)
            ordered.addAll(rows)
            if (!bordersFirst) ordered.addAll(borderPaths)
        } else {
            if (bordersFirst) ordered.addAll(nearest(outer,base))
            // Wait for the pass closest to the border to avoid an early round trip.
            // Split that pass at the meeting point, then resume its remaining part.
            val events = rows.indices.associateWith { mutableListOf<List<Point>>() }
            noGo.forEach { loop ->
                val distances = rows.map { path -> path.zipWithNext().minOf { (a,b) ->
                    loop.minOf { Geometry.distance(it,a,b) }
                } }
                val row = rows.indices.minByOrNull { distances[it] }
                if (row != null) events.getValue(row).add(loop) else ordered.add(loop)
            }
            rows.forEachIndexed { index, path ->
                data class Encounter(val segment: Int, val fraction: Double, val point: Point, val loop: List<Point>)
                val encounters = events.getValue(index).map { loop ->
                    path.zipWithNext().mapIndexed { segment, (a,b) ->
                        val target = loop.minBy { Geometry.distance(it,a,b) }
                        val dx=b.x-a.x; val dy=b.y-a.y
                        val t=if (dx*dx+dy*dy < 1e-12) 0.0 else
                            (((target.x-a.x)*dx+(target.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                        Encounter(segment,t,Point(a.x+t*dx,a.y+t*dy),loop)
                    }.let { candidates ->
                        candidates.minBy { e -> loop.minOf { it.distance(e.point) } }
                    }
                }.sortedWith(compareBy({ it.segment },{ it.fraction }))
                var part = mutableListOf(path.first())
                path.zipWithNext().forEachIndexed { segment, (_,b) ->
                    encounters.filter { it.segment == segment }.forEach { event ->
                        if (part.last().distance(event.point)>1e-8) part.add(event.point)
                        if (part.size>=2) ordered.add(part.toList())
                        ordered.add(enterLoop(event.loop,event.point,plan.borderAnchors))
                        part=mutableListOf(event.point)
                    }
                    if (part.last().distance(b)>1e-8) part.add(b)
                }
                if (part.size>=2) ordered.add(part)
            }
            if (!bordersFirst) ordered.addAll(nearest(outer,ordered.lastOrNull()?.last() ?: base))
        }
        return plan.copy(scheduledPaths=ordered)
    }
}
