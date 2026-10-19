package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import kotlin.math.roundToInt

/**
 * Passes of decreasing height for tall grass: the same task is mowed several times, high first then lower and lower
 * down to the requested cutting height (for example 3 passes: 70, 55 then 40 mm).
 *
 * The prepared route contains all the passes ([expand]): simulation, real mowing, recharges, resume and estimates use
 * the same plan, with one height per pass ([Preview.pathHeights]). Every other pass is driven in reverse: it starts
 * where the previous one ended, without transit, and the grass is cut in the other direction.
 */
object CutPasses {
    const val MAX_PASSES = 3
    /** Minimum gap between two successive passes: below it, the intermediate pass cuts almost nothing. */
    const val MIN_STEP_MM = 5
    val HEIGHT_RANGE = 25..70

    /** Number of passes possible with this final height (at least [MIN_STEP_MM] between two passes, 70 mm at most). */
    fun maxPasses(finalMm: Int): Int = (1 + (HEIGHT_RANGE.last - finalMm.coerceIn(HEIGHT_RANGE)) / MIN_STEP_MM).coerceIn(1, MAX_PASSES)

    /** First-pass height allowed for [count] passes down to [finalMm]. */
    fun firstRange(finalMm: Int, count: Int): IntRange {
        val final = finalMm.coerceIn(HEIGHT_RANGE)
        val n = count.coerceIn(1, maxPasses(final))
        return (final + MIN_STEP_MM * (n - 1)).coerceAtMost(HEIGHT_RANGE.last)..HEIGHT_RANGE.last
    }

    /**
     * Pass heights, from the first to the last, evenly spread from [firstMm] to [finalMm].
     * Clamped values: a single pass at [finalMm] if the gap does not allow several distinct passes.
     */
    fun heights(finalMm: Int, firstMm: Int, count: Int): List<Int> {
        val final = finalMm.coerceIn(HEIGHT_RANGE)
        val n = count.coerceIn(1, maxPasses(final))
        if (n == 1) return listOf(final)
        val first = firstMm.coerceIn(firstRange(final, n))
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
        return preview.copy(scheduledPaths = scheduled, pathHeights = perPath, cutHeights = heights,
            meters = preview.meters * heights.size, links = links)
    }

    /** "Pass 2/3 · 55 mm" during a multi-pass task, otherwise "55 mm". */
    fun progress(heights: List<Int>, currentMm: Int): String {
        val pass = heights.indexOf(currentMm)
        return if (heights.size < 2 || pass < 0) "$currentMm mm" else tr("Pass ${pass + 1}/${heights.size} · $currentMm mm", "Passe ${pass + 1}/${heights.size} · $currentMm mm")
    }
}
