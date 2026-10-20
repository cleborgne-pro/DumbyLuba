package fr.luba.poc.map

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Background of the full-screen map, chosen in the **Map** sheet. Labels evaluated at display time. */
enum class MapBackdrop(private val labelEn: String, private val labelFr: String, private val detailEn: String,
                       private val detailFr: String) {
    GRID("Grid", "Quadrillage", "Metric grid tied to the map: one line per meter, stronger every 5 m",
        "Repère métrique lié à la carte : un trait par mètre, plus marqué tous les 5 m"),
    DOTS("Dots", "Points", "Discreet pattern, fixed on screen", "Trame discrète, fixe à l'écran"),
    PLAIN("Plain", "Uni", "Neutral background, no grid", "Fond neutre, sans repère");
    val label: String get() = tr(labelEn, labelFr)
    val detail: String get() = tr(detailEn, detailFr)
}

/**
 * Display settings of the full-screen map: background, zone names, scale and planned route animation.
 * The Obstacles and Difficulties layers stay in [MapExtraLayers], the heatmap in [MapLayerSettings].
 */
data class MapDisplay(
    val backdrop: MapBackdrop = MapBackdrop.GRID,
    val zoneNames: Boolean = true,
    val scaleBar: Boolean = true,
    val routeAnimation: Boolean = true,
) {
    fun encode(): String = listOf(backdrop.name, zoneNames, scaleBar, routeAnimation).joinToString(";")

    companion object {
        /** Missing or unreadable value: default setting field by field. */
        fun decode(raw: String?): MapDisplay {
            val parts = raw?.split(";").orEmpty()
            val default = MapDisplay()
            fun flag(i: Int, fallback: Boolean) = when (parts.getOrNull(i)) { "true" -> true; "false" -> false; else -> fallback }
            return MapDisplay(
                backdrop = MapBackdrop.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: default.backdrop,
                zoneNames = flag(1, default.zoneNames),
                scaleBar = flag(2, default.scaleBar),
                routeAnimation = flag(3, default.routeAnimation),
            )
        }
    }
}

/**
 * Display settings kept between two launches. Compose state (read when drawing: a change redraws the map without
 * recomposing the screen) and outside MapScreen (Dalvik register limit, see IsolatedBox).
 */
object MapDisplaySettings {
    private const val KEY = "map_display"
    private var prefs: android.content.SharedPreferences? = null
    var current by mutableStateOf(MapDisplay())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("map_layers", Context.MODE_PRIVATE).also {
            current = MapDisplay.decode(it.getString(KEY, null))
        }
    }

    fun update(transform: (MapDisplay) -> MapDisplay) {
        val next = transform(current)
        if (next == current) return
        current = next
        prefs?.edit()?.putString(KEY, next.encode())?.apply()
    }

    /** Reset for tests (no preference written). */
    internal fun resetForTest(display: MapDisplay = MapDisplay()) { prefs = null; current = display }
}

/** Grid step: thin line and strong line (meters), the thin one staying at least 14 dp apart on screen. */
internal fun gridSteps(pxPerMeter: Double, minPx: Double): Pair<Double, Double> {
    val minor = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).firstOrNull { it * pxPerMeter >= minPx } ?: 100.0
    return minor to minor * 5
}

/**
 * Metric grid tied to the map (it rotates and zooms with it), drawn in the map layer.
 * [view]: camera applied to the layer, to draw only the visible part.
 */
fun DrawScope.drawMetricGrid(proj: MapProjection, view: MapCamera, unit: Float, colors: LubaColors) {
    val center = Point(size.width / 2.0, size.height / 2.0)
    val corners = listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height).map { (x, y) ->
        val canvas = view.inverse(Point(x.toDouble(), y.toDouble()), center)
        proj.map(Offset(canvas.x.toFloat(), canvas.y.toFloat()))
    }
    val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
    val (minor, major) = gridSteps(proj.scale * view.zoom, 14.0 * unit * view.zoom)
    val dark = colors.Background.luminance() < 0.5f
    val fine = colors.MapGrid.copy(alpha = if (dark) 0.10f else 0.13f)
    val strong = colors.MapGrid.copy(alpha = if (dark) 0.22f else 0.30f)
    fun isMajor(v: Double) = kotlin.math.abs(v / major - Math.round(v / major)) < 1e-6
    var x = floor(minX / minor) * minor
    while (x <= maxX) {
        val m = isMajor(x)
        drawLine(if (m) strong else fine, proj.screen(Point(x, minY)), proj.screen(Point(x, maxY)),
            (if (m) 1f else 0.6f) * unit)
        x += minor
    }
    var y = floor(minY / minor) * minor
    while (y <= maxY) {
        val m = isMajor(y)
        drawLine(if (m) strong else fine, proj.screen(Point(minX, y)), proj.screen(Point(maxX, y)),
            (if (m) 1f else 0.6f) * unit)
        y += minor
    }
}

/** Zone label: name, interior point and extent (meters) to hide names that are too wide. */
class ZoneLabel(val id: Long, val name: String, val at: Point, val widthM: Double, val heightM: Double)

/**
 * Labels of the named zones, computed once per map (JTS interior point, like the order numbers). A zone corrected
 * locally ([MapCorrections]) carries a pencil after its name, a DumbyLuba zone (phone only) the "DL" mark.
 */
fun zoneLabels(map: RobotMap): List<ZoneLabel> {
    val factory = GeometryFactory()
    val original = map.mammotionShapes?.associateBy { it.id }
    return map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 && it.name.isNotBlank() }.mapNotNull { zone ->
        runCatching {
            val polygon = factory.createPolygon((zone.points + zone.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
            val c = polygon.centroid.takeIf { polygon.covers(it) } ?: polygon.interiorPoint
            val corrected = original?.get(zone.id)?.let { it.points != zone.points } == true
            ZoneLabel(zone.id, MapCorrections.zoneTitle(zone, zone.name) + if (corrected) " ✎" else "", Point(c.x, c.y), zone.points.maxOf { it.x } - zone.points.minOf { it.x },
                zone.points.maxOf { it.y } - zone.points.minOf { it.y })
        }.getOrNull()
    }
}

/**
 * Zone names on the map: light pill at a constant on-screen size, always readable whatever the rotation. A chosen zone
 * already carries its order number: its name goes just below. A name wider than its zone on screen is omitted (small
 * zones, zoomed-out map).
 */
fun DrawScope.drawZoneNames(labels: List<ZoneLabel>, order: List<Long>, screen: (Point) -> Offset, pxPerMeter: Double,
                            unit: Float, mapRotation: Float, colors: LubaColors) {
    if (labels.isEmpty()) return
    val dark = colors.Background.luminance() < 0.5f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.TextPrimary.toArgb()
        textSize = 11.5f * unit
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    val pill = (if (dark) Color(0xFF14171A) else Color.White).copy(alpha = 0.82f)
    labels.forEach { label ->
        val text = label.name.let { if (it.length > 22) it.take(21) + "…" else it }
        val textWidth = paint.measureText(text)
        val fits = min(label.widthM, label.heightM) * pxPerMeter >= 18f * unit &&
            max(label.widthM, label.heightM) * pxPerMeter >= textWidth + 12f * unit
        if (!fits) return@forEach
        val at = screen(label.at)
        val below = if (label.id in order) 30f * unit else 0f
        rotate(-mapRotation, at) {
            val w = textWidth + 14f * unit
            val h = 20f * unit
            val topLeft = Offset(at.x - w / 2, at.y + below - h / 2)
            drawRoundRect(pill, topLeft, Size(w, h), CornerRadius(h / 2))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(text, at.x, at.y + below - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
    }
}

/** Round length of the scale bar: the longest that fits in [maxPx]. */
internal fun scaleBarMeters(pxPerMeter: Double, maxPx: Double): Double =
    listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0).lastOrNull { it * pxPerMeter <= maxPx } ?: 0.5

/** Discreet scale bar: round length in meters, recomputed on zoom. [pxPerMeter]: screen pixels per meter. */
@Composable
fun MapScaleBar(pxPerMeter: Double, modifier: Modifier = Modifier) {
    if (pxPerMeter <= 0.0 || pxPerMeter.isNaN()) return
    val colors = MammotionColors
    val density = LocalDensity.current
    val meters = scaleBarMeters(pxPerMeter, with(density) { 96.dp.toPx() }.toDouble())
    val barDp = with(density) { (meters * pxPerMeter).toFloat().toDp() }
    val label = if (meters < 1) "${Format.num(meters * 100, 0)} cm" else "${Format.num(meters, 0)} m"
    Surface(modifier.semantics { contentDescription = tr("Scale: $label", "Échelle : $label") }, shape = CircleShape, color = colors.Overlay,
        contentColor = colors.TextPrimary, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.width(barDp).height(6.dp)) {
                val stroke = 1.5.dp.toPx()
                val y = size.height - stroke / 2
                drawLine(colors.TextPrimary, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(colors.TextPrimary, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke, StrokeCap.Round)
                drawLine(colors.TextPrimary, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke, StrokeCap.Round)
                drawLine(colors.TextSecondary, Offset(size.width / 2, size.height * 0.45f), Offset(size.width / 2, size.height), stroke * 0.7f)
            }
        }
    }
}

/**
 * Travel with blades stopped (return, link, access, move to a point): blue dashes on a light edging, distinct from the
 * mowing passes and borders (white). [unit] = 1 dp on screen.
 */
fun DrawScope.drawTransitPath(path: androidx.compose.ui.graphics.Path, unit: Float, colors: LubaColors, width: Float = 2f) {
    val dark = colors.Background.luminance() < 0.5f
    drawPath(path, (if (dark) Color.Black else Color.White).copy(alpha = 0.7f),
        style = Stroke((width + 2.5f) * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(path, colors.Transit, style = Stroke(width * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 4f * unit))))
}

/**
 * Section of a move beyond the mapped zones: orange dots on an opaque halo laid over the route, so that the dashes of
 * the mapped part do not show through.
 */
fun DrawScope.drawOffMapPath(path: androidx.compose.ui.graphics.Path, unit: Float, colors: LubaColors, width: Float = 3.5f) {
    val dark = colors.Background.luminance() < 0.5f
    drawPath(path, if (dark) Color.Black else Color.White,
        style = Stroke((width + 3f) * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(path, colors.Warning, style = Stroke(width * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f * unit, width * 2f * unit))))
}

/** Destination of the move to a point: blue target circled in white, at a constant on-screen size. */
fun DrawScope.drawMapTarget(at: Offset, unit: Float, colors: LubaColors) {
    drawCircle(colors.Transit.copy(alpha = 0.18f), 14f * unit, at)
    drawCircle(Color.White, 8f * unit, at)
    drawCircle(colors.Transit, 8f * unit, at, style = Stroke(2.5f * unit))
    drawCircle(colors.Transit, 2.5f * unit, at)
}
