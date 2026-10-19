package fr.luba.poc.map

/** Partially traced route: passes and border loops already "drawn", and the tip of the trace. */
data class RouteReveal(val runs: List<List<Point>>, val borders: List<List<Point>>, val tip: Point?)

/**
 * Progressive reveal of a computed preview, in the real mowing order, as if it were being drawn.
 * Purely visual: the plan, its validation and the simulation preparation do not depend on the animation.
 */
class RouteDrawing(preview: Preview) {
    private val paths = preview.cuttingPaths.filter { it.size >= 2 }
    // The mowing order may rotate or reverse a border loop: a closed loop is still a border.
    private val borderKinds = paths.map { path ->
        path in preview.borders || path.reversed() in preview.borders ||
            (path.size >= 3 && path.first().distance(path.last()) < 1e-6)
    }
    private val lengths = paths.map(::length)
    val meters = lengths.sum()

    /** Route traced up to [fraction] of its total length (0 = nothing, 1 = everything). */
    fun reveal(fraction: Double): RouteReveal {
        if (fraction >= 1.0 || meters <= 0.0) return RouteReveal(
            paths.filterIndexed { i, _ -> !borderKinds[i] }, paths.filterIndexed { i, _ -> borderKinds[i] }, null)
        var budget = meters * fraction.coerceAtLeast(0.0)
        val runs = mutableListOf<List<Point>>()
        val borders = mutableListOf<List<Point>>()
        for ((i, path) in paths.withIndex()) {
            val target = if (borderKinds[i]) borders else runs
            if (lengths[i] <= budget) {
                target.add(path); budget -= lengths[i]; continue
            }
            val partial = prefix(path, budget)
            if (partial.size >= 2) target.add(partial)
            return RouteReveal(runs, borders, partial.last())
        }
        return RouteReveal(runs, borders, paths.lastOrNull()?.last())
    }

    companion object {
        /** Trace duration: long enough to follow the mowing order, short even for a large garden. */
        fun durationMillis(meters: Double): Int = (900 + meters * 3).toInt().coerceIn(900, 3200)

        private fun length(path: List<Point>) = path.zipWithNext().sumOf { (a, b) -> a.distance(b) }

        /** Start of [path] over [meters] meters, last segment interpolated. */
        internal fun prefix(path: List<Point>, meters: Double): List<Point> {
            val out = mutableListOf(path.first())
            var left = meters
            for ((a, b) in path.zipWithNext()) {
                val step = a.distance(b)
                if (step <= left) { out.add(b); left -= step; continue }
                if (step > 0 && left > 0) out.add(Point(a.x + (b.x - a.x) * left / step, a.y + (b.y - a.y) * left / step))
                break
            }
            return out
        }
    }
}
