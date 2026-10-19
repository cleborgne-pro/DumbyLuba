package fr.luba.poc.planning

import fr.luba.poc.map.*
import kotlin.math.roundToInt

internal fun interface OrientationSearch { fun options(zones: List<MapShape>,baseline: Preview,budget: Int): List<SweepOptions> }
/** Dominant edge directions, 5° neighbourhood, alternating phases; stable order, no randomness. */
internal object AdaptiveOrientation : OrientationSearch {
    override fun options(zones: List<MapShape>,baseline: Preview,budget: Int): List<SweepOptions> {
        val edges=zones.flatMap { zone -> (zone.points+zone.points.first()).zipWithNext() }
            .sortedWith(compareByDescending<Pair<Point,Point>> { it.first.distance(it.second) }.thenBy { it.first.x }.thenBy { it.first.y })
        fun normalize(angle: Int) = (angle%180+180)%180
        val dominant=edges.map { normalize(heading(it.first,it.second).roundToInt()) }.distinct().take(6)
        val angles=(dominant+listOf(normalize(baseline.angle-5),normalize(baseline.angle+5))+(0 until 180 step 15)).distinct()
        val variants=mutableListOf<SweepOptions>()
        for (phase in listOf(0.5,0.25,0.75)) for (angle in angles) variants.add(SweepOptions(listOf(angle),phase))
        // Reserve some tries for the phases even with a small budget.
        return (variants.take(maxOf(1,budget-2))+listOf(SweepOptions(listOf(baseline.angle),0.25),SweepOptions(listOf(baseline.angle),0.75))).take(budget)
    }
}
