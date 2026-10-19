package fr.luba.poc.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Overlay of the map's right vertical bar and the stack of bottom panels (protection alert, layers, resume, zone
 * choice, action bar…).
 *
 * The bar sits at the top right and is drawn above everything else. The bottom panels stack up from the bottom over
 * the full width; only a panel that would rise up to the bar's height is narrowed to its left: the bar's buttons are
 * never covered, and the panels' close cross stays visible.
 */
@Composable
fun MapOverlayLayout(
    sideTop: Dp,
    sideEnd: Dp,
    horizontal: Dp,
    bottom: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    side: @Composable () -> Unit,
    panels: @Composable () -> Unit,
) {
    Layout(contents = listOf(side, panels), modifier = modifier) { (sideItems, panelItems), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val top = sideTop.roundToPx()
        val end = sideEnd.roundToPx()
        val pad = horizontal.roundToPx()
        val space = gap.roundToPx()

        val sidePlaced = sideItems.map { it.measure(Constraints(maxWidth = width, maxHeight = (height - top).coerceAtLeast(0))) }
        val sideWidth = sidePlaced.maxOfOrNull { it.width } ?: 0
        val sideBottom = top + (sidePlaced.maxOfOrNull { it.height } ?: 0)
        val lane = MapOverlayLane.of(width, pad, end, sideWidth, space)

        // Stacking from the bottom, like a column spaced by `gap` and centered horizontally.
        var y = height - bottom.roundToPx()
        val placed = panelItems.asReversed().map { item ->
            val narrow = sideWidth > 0 && MapOverlayLane.reachesSide(y, sideBottom, space) { item.heightAt(lane.full) }
            val laneWidth = if (narrow) lane.narrow else lane.full
            val p = item.measure(Constraints(maxWidth = laneWidth, maxHeight = height.coerceAtLeast(0)))
            y -= p.height
            val x = pad + (laneWidth - p.width).coerceAtLeast(0) / 2
            Triple(p, x, y).also { y -= space }
        }

        layout(width, height) {
            placed.forEach { (p, x, py) -> p.place(x, py) }
            // Placed last: drawn and touched above the panels.
            sidePlaced.forEach { it.place(width - end - it.width, top, zIndex = 1f) }
        }
    }
}

/** Expected height of a panel at this width; unknown (null) if its content cannot estimate it. */
private fun Measurable.heightAt(width: Int): Int? =
    runCatching { maxIntrinsicHeight(width) }.getOrNull()

/** Widths available to the bottom panels: the full width, or left of the right bar. */
internal data class MapOverlayLane(val full: Int, val narrow: Int) {
    companion object {
        fun of(width: Int, pad: Int, sideEnd: Int, sideWidth: Int, gap: Int) = MapOverlayLane(
            full = (width - 2 * pad).coerceAtLeast(0),
            narrow = (width - pad - sideEnd - sideWidth - gap).coerceAtLeast(0),
        )

        /**
         * True if a panel whose bottom is at [bottomY] would rise up to the bar (bottom at [sideBottom]), margin [gap]
         * included. Unknown height: narrowed as a precaution as soon as the bar is not far above.
         */
        fun reachesSide(bottomY: Int, sideBottom: Int, gap: Int, fullHeight: () -> Int?): Boolean {
            if (bottomY <= sideBottom + gap) return true
            val h = fullHeight() ?: return true
            return bottomY - h < sideBottom + gap
        }
    }
}
