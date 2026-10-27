package fr.luba.poc.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.MammotionColors

/**
 * Manual direction chosen on the map: the chosen zones and their no-go zones, crossed by guide lines at [angle]
 * ([OrientationGuide], two families for the checkerboard), updated on every step. A finger drawn across the map sets
 * the direction (5° steps), two fingers move, zoom and turn the view. Nothing is computed here: "Route preview" then
 * runs the real computation with this angle.
 */
@Composable
internal fun OrientationPicker(map: RobotMap, zoneIds: Set<Long>, mode: CoverageMode, angle: Int, enabled: Boolean, onAngle: (Int) -> Unit) {
    val colors = MammotionColors
    // Chosen zones and the no-go zones touching them only: the view frames what will be mowed.
    val view = remember(map, zoneIds) { guideMap(map, zoneIds) }
    val area = remember(map, zoneIds) { runCatching { OrientationGuide.area(map, zoneIds) }.getOrNull() }
    val overlays = remember(area, angle, mode) {
        val a = area ?: return@remember emptyList()
        val families = if (mode == CoverageMode.CHECKERBOARD) listOf(angle, angle + 90) else listOf(angle)
        families.flatMapIndexed { i, deg ->
            // Second checkerboard family lighter: the first one is the angle of the slider.
            val color = colors.LinePass.copy(alpha = if (i == 0) 0.9f else 0.5f)
            runCatching { OrientationGuide.lines(a, deg) }.getOrDefault(emptyList()).map { MapOverlay(it, color, widthDp = 1.5f) }
        }
    }
    val change by rememberUpdatedState(onAngle)
    val input = remember(area) {
        val start = mutableStateOf<Point?>(null)
        val minimum = area?.let(OrientationGuide::minimumDrag) ?: 0.0
        MapPaintInput(begin = { start.value = it },
            extend = { p -> start.value?.takeIf { it.distance(p) >= minimum }?.let { change(OrientationGuide.angleOf(it, p)) } },
            finish = { start.value = null })
    }
    Box(Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.large).background(colors.MapBackground).testTag("orientation-map")) {
        MapLive(map = view, cuttingZoneIds = zoneIds, overlays = overlays, onPaint = input.takeIf { enabled && area != null },
            modifier = Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.small, color = colors.Surface.copy(alpha = 0.95f)) {
            Text(if (mode == CoverageMode.CHECKERBOARD) "$angle° / ${angle + 90}°" else "$angle°", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall, color = colors.Value)
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = MaterialTheme.shapes.small, color = colors.Surface.copy(alpha = 0.95f)) {
            Text(tr("One finger: direction · two fingers: move", "Un doigt : direction · deux doigts : déplacer"),
                Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** [map] reduced to the zones [zoneIds] and the no-go zones within their extent. */
internal fun guideMap(map: RobotMap, zoneIds: Set<Long>): RobotMap {
    val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.id in zoneIds }
    val points = zones.flatMap { it.points }
    if (points.isEmpty()) return map.copy(shapes = emptyList())
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val holes = map.shapes.filter { s -> s.excludes && s.points.any { it.x in minX..maxX && it.y in minY..maxY } }
    return map.copy(shapes = zones + holes)
}
