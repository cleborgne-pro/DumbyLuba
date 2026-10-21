package fr.luba.poc.history

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Real coverage shown on the map: [taskId] task, [running] while the mowing goes on. */
data class CoverageView(val taskId: String, val coverage: CoverageMap, val running: Boolean)

/**
 * Real coverage of the current mowing, then of the last one once it ends (also restored at launch from the history).
 * Kept outside the map screen state (MapScreen register limit), like [fr.luba.poc.map.MapExtraLayers].
 */
object RealCoverage {
    private val current = MutableStateFlow<CoverageView?>(null)
    val flow: StateFlow<CoverageView?> get() = current

    fun publish(view: CoverageView?) { current.value = view }

    /** Coverage restored at launch: ignored when a mowing has already published its own. */
    fun restore(view: CoverageView) { current.compareAndSet(null, view) }
}

/** Colours and image of the "Real coverage" layer. */
object CoverageLayerStyle {
    /** Mowed according to the track: deep translucent green, readable over the lawn and the planned stripes. */
    val covered = Color(0xFF1B5E20).copy(alpha = 0.42f)
    /** Mowed twice or more. */
    val repeated = Color(0xFF1B5E20).copy(alpha = 0.62f)
    /** Unmowed portion large enough for a pass. */
    val gap = Color(0xFFF97316).copy(alpha = 0.55f)

    /** Layer image; gaps only once the task has ended (everything ahead of the robot is still to mow). */
    fun image(coverage: CoverageMap, gaps: Boolean): ImageBitmap {
        val pixels = coverage.pixels(covered.toArgb(), repeated.toArgb(), if (gaps) gap.toArgb() else null)
        return android.graphics.Bitmap.createBitmap(pixels, coverage.cols, coverage.rows, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/**
 * Coverage to draw on the map showing [map]: the published one when [enabled] and its zones belong to this map, with its
 * image (gaps once the task has ended); null otherwise, and always on the demo map.
 */
@androidx.compose.runtime.Composable
fun realCoverageLayer(map: fr.luba.poc.map.RobotMap, enabled: Boolean): Pair<CoverageMap, ImageBitmap>? {
    val view by RealCoverage.flow.collectAsState()
    // Never on the demo map (its small zone ids could match test zones).
    val shown = view?.takeIf { v -> enabled && !map.source.contains("DÉMONSTRATION", true) &&
        v.coverage.zones.any { z -> map.shapes.any { it.id == z.id } } } ?: return null
    val image = androidx.compose.runtime.remember(shown) { CoverageLayerStyle.image(shown.coverage, gaps = !shown.running) }
    return shown.coverage to image
}

/** Draws the coverage [image] of [coverage] over the map, [screen] converting map metres into canvas pixels. */
fun DrawScope.drawRealCoverage(coverage: CoverageMap, image: ImageBitmap, screen: (Point) -> Offset) {
    val topLeft = screen(Point(coverage.minX, coverage.minY + coverage.rows * coverage.cellM))
    val bottomRight = screen(Point(coverage.minX + coverage.cols * coverage.cellM, coverage.minY))
    val width = (bottomRight.x - topLeft.x).roundToInt(); val height = (bottomRight.y - topLeft.y).roundToInt()
    if (width <= 0 || height <= 0) return
    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()), dstSize = IntSize(width, height),
        filterQuality = FilterQuality.None)
}

/** Labels of the real coverage. */
object CoverageText {
    /** Rounded down: 99.6 % mowed is not shown as complete. */
    fun percent(fraction: Double): String = "${kotlin.math.floor(fraction.coerceIn(0.0, 1.0) * 100 + 1e-9).toInt()} %"
    fun area(m2: Double): String = if (m2 <= 0.0) "0 m²" else "${MowingForecaster.num(m2, when { m2 < 1 -> 2; m2 < 10 -> 1; else -> 0 })} m²"
    fun zone(z: ZoneCoverage): String = tr("${percent(z.fraction)} · ${area(z.coveredM2)} of ${area(z.areaM2)}",
        "${percent(z.fraction)} · ${area(z.coveredM2)} sur ${area(z.areaM2)}")
    fun gaps(gaps: List<CoverageGap>): String = if (gaps.isEmpty()) tr("None", "Aucun")
        else tr("${gaps.size} · ${area(gaps.sumOf { it.areaM2 })} in all", "${gaps.size} · ${area(gaps.sumOf { it.areaM2 })} au total")
    /** Short label of the live bar. */
    fun live(c: CoverageMap): String = tr("Covered ${percent(c.fraction)}", "Couvert ${percent(c.fraction)}")
}
