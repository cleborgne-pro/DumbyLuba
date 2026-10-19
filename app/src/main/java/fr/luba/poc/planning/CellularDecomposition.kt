package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Geometry as JtsGeometry

/** Interchangeable decomposers; coordinates already rotated into the sweep frame. */
internal class PlanningBudgetExceeded(message: String) : IllegalArgumentException(message)
internal fun interface TerrainDecomposer { fun cells(domain: JtsGeometry): List<JtsGeometry> }
internal object WholeTerrain : TerrainDecomposer {
    override fun cells(domain: JtsGeometry) = listOf(domain)
}
/** Event-based polygonal decomposition: the extrema define exact slabs.
 * One-to-one adjacencies merge; a split/merge opens a new BCD cell.
 * A point contact is never considered a passage. O(v²) cost at worst, bounded.
 */
internal class BoustrophedonDecomposer(private val maximumEvents: Int = 512) : TerrainDecomposer {
    override fun cells(domain: JtsGeometry): List<JtsGeometry> {
        require(domain.isValid && !domain.isEmpty)
        fun extrema(ring: org.locationtech.jts.geom.LineString): List<Double> {
            // Collapse plateaus, including the one crossing the start of the ring.
            val values=mutableListOf<Double>()
            ring.coordinates.dropLast(1).forEach { if(values.lastOrNull()!=it.y) values.add(it.y) }
            if(values.size>1 && values.first()==values.last()) values.removeAt(values.lastIndex)
            return values.indices.filter { i ->
                val before=values[(i+values.size-1)%values.size]; val after=values[(i+1)%values.size]
                values[i]<before && values[i]<after || values[i]>before && values[i]>after
            }.map { values[it] }
        }
        val events=parts(domain).filterIsInstance<Polygon>().flatMap { polygon ->
            extrema(polygon.exteriorRing)+(0 until polygon.numInteriorRing).flatMap { extrema(polygon.getInteriorRingN(it)) }
        }.distinct().sorted()
        if(events.size>maximumEvents) throw PlanningBudgetExceeded(tr("BCD budget exceeded: ${events.size} events", "Budget BCD dépassé : ${events.size} événements"))
        val box=domain.envelopeInternal
        val result=mutableListOf<JtsGeometry>()
        var previous=emptyList<Pair<JtsGeometry,Int>>()
        for ((low,high) in events.zipWithNext()) {
            if (high-low<=1e-12) continue
            val slab=polygon(listOf(fr.luba.poc.map.Point(box.minX-1,low),fr.luba.poc.map.Point(box.maxX+1,low),
                fr.luba.poc.map.Point(box.maxX+1,high),fr.luba.poc.map.Point(box.minX-1,high)))
            val pieces=parts(domain.intersection(slab)).filterIsInstance<Polygon>().filter { it.area>1e-12 }
                .sortedBy { it.envelopeInternal.minX }
            val parents=pieces.map { piece -> previous.indices.filter { p -> piece.intersection(previous[p].first).length>1e-9 } }
            previous=pieces.mapIndexed { i,piece ->
                val links=parents[i]
                val cell=if (links.size==1 && parents.count { links[0] in it }==1) {
                    val id=previous[links[0]].second
                    result[id]=result[id].union(piece); id
                } else result.size.also { result.add(piece) }
                piece to cell
            }
        }
        require(result.isNotEmpty()) { tr("Empty partition", "Partition vide") }
        val combined=union(result)
        val tolerance=maxOf(1e-8,domain.area*1e-9)
        require(combined.symDifference(domain).area<=tolerance) { tr("Incomplete partition", "Partition incomplète") }
        require(result.sumOf { it.area }-combined.area<=tolerance) { tr("Overlapping cells", "Cellules superposées") }
        return result
    }
}
