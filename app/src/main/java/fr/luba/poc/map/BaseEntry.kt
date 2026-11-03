package fr.luba.poc.map

import kotlin.math.atan2

/**
 * Entry nearest to the base of a zone planned by [CoveragePlanner.plan] outside production (`continuous = false`, the
 * research `Geometry.plan` path): the run or loop nearest to [basePoint] driven first, from its nearest point, where the
 * chassis envelope allows it. Production keeps whole blocks and leaves the start to [fr.luba.poc.planning.LocalMowingOrder].
 */
internal object BaseEntry {
    private fun List<Point>.distinctConsecutive() = filterIndexed { i,p -> i == 0 || p.distance(this[i-1]) > 1e-8 }

    fun nearBase(preview: Preview, basePoint: Point?, envelope: TrajectoryEnvelope): Preview {
        // Spiral from the centre: its chains are entered at their centre end only, after their core; the planned order is
        // kept rather than entered in the middle of a chain.
        if (preview.outwardRuns.isNotEmpty()) return preview
        val base = basePoint ?: return preview
        data class Entry(val run: Int, val segment: Int, val point: Point, val distance: Double)
        val entries = (preview.runs+preview.borders).flatMapIndexed { index, run ->
            run.zipWithNext().mapIndexed { segment, (a,b) ->
                val dx=b.x-a.x; val dy=b.y-a.y
                val t=if (dx*dx+dy*dy < 1e-12) 0.0 else
                    (((base.x-a.x)*dx+(base.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                val p=Point(a.x+t*dx,a.y+t*dy)
                Entry(index,segment,p,p.distance(base))
            }
        }.sortedBy { it.distance }
        for (entry in entries) {
            if (entry.run >= preview.runs.size) {
                val index = entry.run-preview.runs.size
                val loop = preview.borders[index]
                val rotated = (listOf(entry.point)+loop.drop(entry.segment+1)+loop.drop(1).take(entry.segment)+entry.point).distinctConsecutive()
                val heading = Math.toDegrees(atan2(rotated[1].y-rotated[0].y,rotated[1].x-rotated[0].x))
                if (envelope.pathCollision(rotated,finalHeading=heading) != null) continue
                return preview.copy(borders=listOf(rotated)+preview.borders.filterIndexed { i,_ -> i != index },startWithBorders=true,
                    borderKinds=listOf(preview.borderKinds[index])+preview.borderKinds.filterIndexed { i,_ -> i != index })
            }
            val run = preview.runs[entry.run]
            val tail = (listOf(entry.point)+run.drop(entry.segment+1)).distinctConsecutive()
            val head = (run.take(entry.segment+1)+entry.point).distinctConsecutive()
            val first = if (tail.size >= 2) tail else run.reversed()
            if (envelope.pathCollision(first) != null) continue
            val remainder = if (tail.size >= 2 && head.size >= 2) listOf(head) else emptyList()
            val cell=preview.runCellIds.getOrNull(entry.run)
            val same=preview.runs.indices.filter { it!=entry.run && cell!=null && preview.runCellIds.getOrNull(it)==cell }
            val other=preview.runs.indices.filter { it!=entry.run && it !in same }
            return preview.copy(runs=listOf(first)+same.map { preview.runs[it] }+remainder+other.map { preview.runs[it] },
                runCellIds=if(cell==null) emptyList() else listOf(cell)+same.map { cell }+remainder.map { cell }+other.map { preview.runCellIds[it] })
        }
        return preview
    }
}
