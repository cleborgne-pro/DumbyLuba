package fr.luba.poc.map

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.ceil

@Composable
fun MowingPreview(map: RobotMap, plan: Preview?, obstacles: List<LocalObstacle>, estimate: MowingEstimate?, speedMps: Float, autonomyMinutes: Float = 60f, chargeMinutes: Float = 30f, initialBattery: Float = 100f, chargeLow: Float = 20f, chargeHigh: Float = 90f,
    computing: Boolean = false, validating: Boolean = false, stage: String = tr("Computing the route", "Calcul du trajet"), zoneIds: Set<Long> = plan?.zoneIds.orEmpty().toSet(),
    /** Requested start point (chosen or saved), marked on the map; null = nearest to the robot. */
    requestedStart: Point? = null,
    /** Start choice in progress: a tap on the map designates it. */
    onPickStart: ((Point) -> Unit)? = null) {
    val colors = MammotionColors
    // Camera handed to the map and read only when drawing: zooming neither recomposes the map nor rebuilds its
    // thousands of passes; stroke widths, comet and markers keep their on-screen size.
    val cameraState = remember(map) { mutableStateOf(MapCamera()) }
    var camera by cameraState
    val simplified = plan?.takeIf { !computing && estimate == null }?.let {
        MowingEstimate.fromDistance(it.meters, speedMps.toDouble(), autonomyMinutes.toDouble(), chargeMinutes.toDouble(), initialBattery.toDouble(), chargeLow.toDouble(), chargeHigh.toDouble())
    }
    val area = remember(map, zoneIds) { CoveragePlanner.selectedArea(map, zoneIds) }
    // Route traced progressively in mowing order as soon as it is computed, rather than shown all at once.
    val drawing = remember(plan) { plan?.let(::RouteDrawing) }
    val traced = remember(plan) { Animatable(if (plan == null) 1f else 0f) }
    val context = LocalContext.current
    LaunchedEffect(drawing) {
        val route = drawing ?: return@LaunchedEffect
        val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (motion == 0f) traced.snapTo(1f)
        else traced.animateTo(1f, tween(RouteDrawing.durationMillis(route.meters), easing = FastOutSlowInEasing))
    }
    val reveal = drawing?.takeIf { traced.value < 1f }?.reveal(traced.value.toDouble())
    val order = remember(zoneIds) { zoneIds.toList() }
    val overlays = remember(requestedStart) { listOfNotNull(requestedStart?.let { MapOverlay(listOf(it), colors.Info, widthDp = 3f, dots = true) }) }
    val firstPass = remember(plan) { plan?.cuttingPaths?.firstOrNull { it.size >= 2 } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        PreviewMetric(Icons.Rounded.Schedule, tr("Total time", "Temps total"), estimate?.let { "≈ ${durationLabel(it.totalSeconds)}" } ?: simplified?.let { "≈ ${durationLabel(it.totalSeconds)}" } ?: "…",
            Modifier.weight(1f), loading = computing)
        PreviewMetric(Icons.Rounded.BatteryChargingFull, "Recharges", estimate?.recharges?.let { "≈ $it" } ?: simplified?.let { "≈ ${it.recharges}" } ?: "…",
            Modifier.weight(1f), loading = computing)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        PreviewMetric(Icons.Rounded.Grass, tr("Area", "Surface"), "${Format.num(area, 0)} m²", Modifier.weight(1f))
        PreviewMetric(Icons.Rounded.Route, "Distance", plan?.let { "${Format.num(it.meters, 0)} m" } ?: "—", Modifier.weight(1f), loading = computing)
    }
    // Border strategy applied per type ([BorderStrategy]): one line per border type of the selected zones.
    val borderStrategy = remember(map, zoneIds, BorderTypeRepository.annotations, plan?.mode) {
        if (plan?.mode?.isSurvey == true) emptyList()
        else BorderStrategy.summary(BorderTypeRepository.portions(map), zoneIds + map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.id },
            OuterOffsetSetting.meters)
    }
    if (borderStrategy.isNotEmpty()) Text(tr("Borders by type", "Bordures par type") + "\n" + borderStrategy.joinToString("\n") { "• $it" },
        style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
    if ((plan?.skippedBorderLoops ?: 0) > 0) Text(
        tr("${plan?.skippedBorderLoops} border fragment(s) too narrow to maneuver: not driven.", "${plan?.skippedBorderLoops} fragment(s) de bordure trop étroit(s) pour manœuvrer : non parcouru(s)."),
        style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
    if (computing) Text(stage, style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
    if (validating) {
        fr.luba.poc.ui.components.LubaProgress(Modifier.fillMaxWidth())
        Text(tr("$stage. The buttons will be available once preparation is finished.", "$stage. Les boutons seront disponibles à la fin de la préparation."),
            style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
    }
    Text(if (plan?.mode?.isSurvey == true) tr("White: grid and merged boundary · blades stopped", "Blanc : quadrillage et contour fusionné · lames arrêtées") else tr("White: passes and border loops (light edging) · Numbers: zone order", "Blanc : passes et tours de bordure (liseré clair) · Numéros : ordre des zones"),
        style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
    Box(Modifier.fillMaxWidth().height(360.dp).clip(MaterialTheme.shapes.large).background(colors.MapBackground)) {
        MapLive(map = map, cuttingZoneIds = zoneIds, zoneOrder = order, cuttingPasses = reveal?.runs ?: plan?.passes.orEmpty(), plannedBands = reveal?.runs ?: plan?.runs.orEmpty(),
            plannedBorders = reveal?.borders ?: plan?.borders.orEmpty(), obstacles = obstacles,
            start = reveal?.tip,
            routeStart = firstPass?.first(),
            routeStartAhead = routeStartAhead(firstPass),
            overlays = overlays,
            onTap = onPickStart,
            // Drag, pinch, rotate; compass at the top left (a tap brings north back to the top).
            interactive = true,
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.medium, color = colors.Surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { camera = camera.transform(Point(0.0, 0.0), Point(0.0, 0.0), Point(0.0, 0.0), 1 / 1.4, 0.0) }) {
                    Icon(Icons.Rounded.Remove, contentDescription = tr("Zoom out", "Dézoomer"))
                }
                Text("×${Format.num(camera.zoom, 1)}", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { camera = camera.transform(Point(0.0, 0.0), Point(0.0, 0.0), Point(0.0, 0.0), 1.4, 0.0) }) {
                    Icon(Icons.Rounded.Add, contentDescription = tr("Zoom in", "Zoomer"))
                }
                IconButton(onClick = { camera = MapCamera() }) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = tr("Recenter the map", "Recentrer la carte"))
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), color = colors.Surface.copy(alpha = 0.95f), shape = MaterialTheme.shapes.small) {
            Text(if (onPickStart != null) tr("Tap the start point · pinch to zoom", "Touchez le point de départ · pincer pour zoomer") else tr("Drag · pinch to zoom · rotate with two fingers", "Glisser · pincer pour zoomer · tourner à deux doigts"),
                Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
        }


    }

}

private fun durationLabel(seconds: Double): String {
    val minutes = ceil(seconds / 60).toInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

@Composable
private fun PreviewMetric(icon: ImageVector, label: String, value: String, modifier: Modifier, loading: Boolean = false) {
    val colors = MammotionColors
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = colors.GreenSoft) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = colors.GreenDeep, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.GreenDeep)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, color = colors.GreenDeep)
        }
    }
}
