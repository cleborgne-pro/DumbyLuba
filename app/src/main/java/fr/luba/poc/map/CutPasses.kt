package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.CutHeightRange
import kotlin.math.roundToInt

/**
 * Passes of decreasing height for tall grass: the same task is mowed several times, high first then lower and lower
 * down to the requested cutting height (for example 3 passes: 70, 55 then 40 mm; up to 100 mm on an H version).
 *
 * The prepared route contains all the passes ([expand]): simulation, real mowing, recharges, resume and estimates use
 * the same plan, with one height per pass ([Preview.pathHeights]). Every other pass is driven in reverse: it starts
 * where the previous one ended, without transit, and the grass is cut in the other direction.
 */
object CutPasses {
    const val MAX_PASSES = 3
    /** Minimum gap between two successive passes: below it, the intermediate pass cuts almost nothing. */
    const val MIN_STEP_MM = 5

    /** Number of passes possible with this final height (at least [MIN_STEP_MM] between two passes, up to the top of [range]). */
    fun maxPasses(finalMm: Int, range: CutHeightRange = CutHeightRange.STANDARD): Int =
        (1 + (range.maxMm - range.clamp(finalMm)) / MIN_STEP_MM).coerceIn(1, MAX_PASSES)

    /** First-pass height allowed for [count] passes down to [finalMm]. */
    fun firstRange(finalMm: Int, count: Int, range: CutHeightRange = CutHeightRange.STANDARD): IntRange {
        val final = range.clamp(finalMm)
        val n = count.coerceIn(1, maxPasses(final, range))
        return (final + MIN_STEP_MM * (n - 1)).coerceAtMost(range.maxMm)..range.maxMm
    }

    /**
     * Pass heights, from the first to the last, evenly spread from [firstMm] to [finalMm] within the cutting heights
     * of the robot ([range], higher on the H versions).
     * Clamped values: a single pass at [finalMm] if the gap does not allow several distinct passes.
     */
    fun heights(finalMm: Int, firstMm: Int, count: Int, range: CutHeightRange = CutHeightRange.STANDARD): List<Int> {
        val final = range.clamp(finalMm)
        val n = count.coerceIn(1, maxPasses(final, range))
        if (n == 1) return listOf(final)
        val first = firstMm.coerceIn(firstRange(final, n, range))
        return (0 until n).map { i -> (first - (first - final) * i.toDouble() / (n - 1)).roundToInt() }
    }

    /** Short label: "70 → 55 → 40 mm". */
    fun label(heights: List<Int>): String = heights.joinToString(" → ", postfix = " mm")

    /**
     * Mowing route repeated for each height, every other pass in reverse. A survey, a single-pass plan or an already
     * expanded preview stays unchanged. The computed links are reused in both directions (the preparation validates
     * them again like the others).
     */
    fun expand(preview: Preview, heights: List<Int>): Preview {
        if (heights.size < 2 || preview.mode.isSurvey || preview.pathHeights.isNotEmpty()) return preview
        val paths = preview.cuttingPaths.filter { it.size >= 2 }
        if (paths.isEmpty()) return preview
        val reversed = paths.asReversed().map { it.asReversed() }
        val scheduled = heights.indices.flatMap { pass -> if (pass % 2 == 0) paths else reversed }
        val perPath = heights.flatMap { height -> List(paths.size) { height } }
        val links = preview.links?.let { planned -> planned + planned.map { it.asReversed() } }
        val zones = preview.cuttingPathZones?.filterIndexed { i, _ -> preview.cuttingPaths[i].size >= 2 }
        val pathZones = zones?.let { z -> heights.indices.flatMap { pass -> if (pass % 2 == 0) z else z.asReversed() } }.orEmpty()
        return preview.copy(scheduledPaths = scheduled, pathHeights = perPath, cutHeights = heights,
            meters = preview.meters * heights.size, links = links, pathZoneIds = pathZones, outwardPaths = emptyMap())
    }

    /** "Pass 2/3 · 55 mm" during a multi-pass task, otherwise "55 mm". */
    fun progress(heights: List<Int>, currentMm: Int): String {
        val pass = heights.indexOf(currentMm)
        return if (heights.size < 2 || pass < 0) "$currentMm mm" else tr("Pass ${pass + 1}/${heights.size} · $currentMm mm", "Passe ${pass + 1}/${heights.size} · $currentMm mm")
    }
}
