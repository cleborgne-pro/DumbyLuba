package fr.luba.poc.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.history.drawRealCoverage
import fr.luba.poc.i18n.tr
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.min

/**
 * Static layer of the map ([MapScreen]): lawn, base safety zone, route stripes and center lines, obstacles, heatmap,
 * real coverage, accesses, station, simulation, real trace, resume, move to a point, zone order and names. The camera is read in the
 * drawing and layer phases only ([camera] provider): a zoom or a pan redraws without recomposing. The animated
 * effects are drawn by [MapFxLayer].
 */
@Composable
internal fun MapRouteCanvas(
    map: RobotMap, selected: Set<Long>, camera: () -> MapCamera, follow: RobotFollow, bounds: MapBounds,
    viewportTop: Float, viewportBottom: Float, state: RobotUi, sim: SimulationUi, preview: Preview?, mapBase: MapBase?,
    cutWidth: Float, extraLayers: Set<MapExtraLayer>, heatShown: Boolean, signalView: SignalReliefView,
    accesses: List<List<Point>>, draftAccess: List<Point>, showingRobotRoute: Boolean, simWheels: WheelTravel,
    resumeSkip: ResumeSkipView?, pointMove: PointMoveState, pointTarget: PointTarget, demo: Boolean,
    recoveryPoints: List<KnownPoint>, zoneNameLabels: List<ZoneLabel>, routeGeometry: LiveGeometry,
    /** Real coverage of the current or last mowing ([fr.luba.poc.history.realCoverageLayer]), null when hidden. */
    coverageLayer: Pair<fr.luba.poc.history.CoverageMap, androidx.compose.ui.graphics.ImageBitmap>?,
) {
    val colors = MammotionColors
Canvas(
    Modifier
        .fillMaxSize()
        .mapAppear(map.shapes)
        .graphicsLayer {
            val camera = camera()
            scaleX = camera.zoom.toFloat()
            scaleY = camera.zoom.toFloat()
            rotationZ = camera.rotation.toFloat()
            val shown = follow.view(camera)
            translationX = shown.offset.x.toFloat()
            translationY = shown.offset.y.toFloat()
        },
) {
    val camera = camera()
    val w0 = bounds.width
    val h0 = bounds.height
    val minX = bounds.minX
    val minY = bounds.minY
    val availableHeight = (size.height - viewportTop - viewportBottom).coerceAtLeast(120f)
    val scale = min((size.width - 40) / w0, availableHeight / h0)
    val ox = (size.width - w0 * scale) / 2
    val oy = viewportBottom + (availableHeight - h0 * scale) / 2

    fun screen(p: Point) = Offset(
        (ox + (p.x - minX) * scale).toFloat(),
        (size.height - oy - (p.y - minY) * scale).toFloat(),
    )

    fun path(points: List<Point>, closed: Boolean): Path {
        val out = Path()
        // Trace break ([MissionTrail.BREAK]): the next piece starts again without a connecting line.
        var start = true
        points.forEach { p ->
            if (fr.luba.poc.simulation.MissionTrail.isBreak(p)) { start = true; return@forEach }
            val o = screen(p)
            if (start) out.moveTo(o.x, o.y) else out.lineTo(o.x, o.y)
            start = false
        }
        if (closed) out.close()
        return out
    }

    // The Canvas is enlarged by graphicsLayer: compensate the zoom to keep thin lines on screen, independently
    // of the metric cutting areas.
    val screenUnit = 1.dp.toPx() / camera.zoom.toFloat()
    fun screenStroke(width: Float) = Stroke(
        width = width * screenUnit, cap = StrokeCap.Round, join = StrokeJoin.Round,
    )
    val completedColor = colors.Mowed
    val display = MapDisplaySettings.current

    // "Grid" background: metric grid tied to the map, under the lawn.
    if (display.backdrop == MapBackdrop.GRID)
        drawMetricGrid(MapProjection(minX, minY, scale, ox, oy, size.height), follow.view(camera), screenUnit, colors)

    // Lawn in relief: shadow, gradient, hatched no-go zones (with no zone chosen, all stay bright).
    drawLawn(map, { selected.isEmpty() || it.id in selected }, ::path, screenUnit, colors)
    // Base safety zone (grey perimeter of the Mammotion app): disc excluded from routes, under the planned route,
    // only when the docking axis is known (otherwise the planner does not set it).
    val stationPoint = sim.engine?.base ?: mapBase?.point
    val stationHeading = sim.engine?.dockEntryHeading ?: mapBase?.entryHeadingDegrees
    val safetyRadius = sim.engine?.routes?.baseSafety?.third
        ?: stationHeading?.let { TrajectoryPlanner.baseSafetyRadius(mapBase?.approachDistanceM ?: 1.5) }
    if (stationPoint != null && safetyRadius != null)
        drawBaseSafetyZone(screen(stationPoint), (safetyRadius * scale).toFloat(), screenUnit, colors.BaseSafety)

    // Stripes to the scale of the cutting width: each pass is drawn separately, so overlaps add up in opacity
    // and become visible.
    val cutPx = (cutWidth * scale).toFloat().coerceAtLeast(1f)
    val bandStroke = Stroke(width = cutPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    routeGeometry.begin(MapProjection(minX, minY, scale, ox, oy, size.height))
    val routePath = routeGeometry::path
    (sim.engine?.preview ?: preview)?.passes?.forEach { drawPath(routePath(it, false), colors.BandPass, style = bandStroke) }
    // Border loops lighter than the passes: the margin held along the boundaries and around the no-go zones
    // reads as an edging, as in the Mammotion app.
    (sim.engine?.preview ?: preview)?.borders?.forEach { drawPath(routePath(it, true), colors.BandBorder, style = bandStroke) }

    // Thin center lines of constant width: exact path at the center of the stripes.
    val centerStroke = screenStroke(0.75f)
    (sim.engine?.preview ?: preview)?.runs?.forEach { drawPath(routePath(it, false), colors.LinePass, style = centerStroke) }
    (sim.engine?.preview ?: preview)?.borders?.forEach { drawPath(routePath(it, true), colors.LineBorder, style = centerStroke) }
    routeGeometry.end()
    coverageLayer?.let { (coverage, image) -> drawRealCoverage(coverage, image, ::screen) }

    // Local point obstacles: physical circle, safety margin, turning envelope.
    // Saved obstacles; presumed obstacles are drawn by the Obstacles layer (dashed).
    drawLocalObstacles(state.obstacles, ::screen, scale, colors, unit = screenUnit)
    // Encountered obstacles (photo) and difficulties heatmap, according to the displayed layers.
    drawDifficultyLayers(state.difficulties, extraLayers, state.obstaclePhoto.photos, ::screen, scale, colors, unit = screenUnit)

    if (heatShown) signalView.cells.forEach { cell ->
        val color = signalView.color(cell)
        val corner = screen(Point(cell.point.x-0.5,cell.point.y+0.5))
        drawRect(color,corner,androidx.compose.ui.geometry.Size(scale.toFloat(),scale.toFloat()))
    }
    // Manual accesses: travel with blades stopped (blue dashes).
    accesses.forEach { drawTransitPath(path(it, false), screenUnit, colors, 1.75f) }
    if (draftAccess.size >= 2) drawTransitPath(path(draftAccess, false), screenUnit, colors, 2.5f)
    // Station sent by the robot, without manual editing; lightning badge when it is too small on screen.
    stationPoint?.let { p ->
        val o = screen(p)
        drawStation(o, scale, stationHeading,
            null, colors.Ink, mapBase?.approachDistanceM ?: 1.5, (1.5/camera.zoom).toFloat())
        if (stationNeedsBadge(scale, screenUnit)) drawStationBadge(o, screenUnit, colors.Ink, colors.OnInk)
    }
    sim.state?.takeIf { !showingRobotRoute }?.let { s ->
        // Already mowed: dark green stripes at the cutting width, under the robot trace.
        sim.engine?.edges?.take(s.edge)?.filter { it.cutting }?.forEach { e ->
            drawPath(path(listOf(e.a, e.b), false), completedColor.copy(alpha = 0.32f), style = bandStroke)
        }
        sim.engine?.edges?.getOrNull(s.edge)?.takeIf { it.cutting && s.offset > 0 }?.let { e ->
            val fraction = s.offset / e.meters
            val end = Point(e.a.x + (e.b.x - e.a.x) * fraction, e.a.y + (e.b.y - e.a.y) * fraction)
            drawPath(path(listOf(e.a, end), false), completedColor.copy(alpha = 0.32f), style = bandStroke)
        }
        drawPath(path(s.trail, false), completedColor.copy(alpha = 0.22f), style = screenStroke(6f))
        drawPath(path(s.trail, false), completedColor, style = screenStroke(1.75f))
        if (s.phase in setOf(SimPhase.LEAVING_DOCK, SimPhase.RETURNING, SimPhase.DEPARTING, SimPhase.ALIGNING))
            drawTransitPath(path(s.transit, false), screenUnit, colors)
        val o = screen(s.position)
        drawLuba(o, scale, s.heading, simWheels)
    }
    if (showingRobotRoute) {
        // To do: planned route in white (like the preview); done: green robot trace.
        drawPath(path(state.robotRoute.planned, false), colors.LinePass.copy(alpha = 0.9f), style = screenStroke(1.25f))
        // Real trace: soft halo under a sharp line, readable on the bright lawn.
        drawPath(path(state.robotRoute.trail, false), completedColor.copy(alpha = 0.25f), style = screenStroke(7f))
        drawPath(path(state.robotRoute.trail, false), completedColor, style = screenStroke(2f))
    }
    (sim.engine?.preview ?: preview)?.let { route ->
        route.cuttingPaths.firstOrNull { it.size >= 2 }?.first()?.let {
            drawRouteStart(screen(it), camera.zoom.toFloat(), camera.rotation.toFloat())
        }
    }
    // "Resume further on": remaining stripes, skipped stripes (not mowed), stop and resume point.
    resumeSkip?.let { v ->
        fun segments(list: List<Pair<Point, Point>>) = Path().also { out ->
            list.forEach { (a, b) -> val oa = screen(a); val ob = screen(b); out.moveTo(oa.x, oa.y); out.lineTo(ob.x, ob.y) } }
        drawPath(segments(v.ahead), colors.LinePass, style = screenStroke(1.25f))
        if (v.skipped.isNotEmpty()) {
            val skipped = segments(v.skipped)
            drawPath(skipped, colors.Warning.copy(alpha = 0.35f), style = bandStroke)
            drawPath(skipped, colors.Warning, style = screenStroke(2f))
        }
        drawCircle(colors.Danger, 5 * screenUnit, screen(v.stop))
        drawCircle(Color.White, 5 * screenUnit, screen(v.stop), style = screenStroke(1.5f))
        drawRouteStart(screen(v.target), camera.zoom.toFloat(), camera.rotation.toFloat(), label = tr("Resume", "Reprise"))
    }
    // Gateway fallen off the robot: where to pick up the phone.
    state.gatewayDrop?.takeIf { !demo }?.let { drop ->
        drawRouteStart(screen(Point(drop.x, drop.y)), camera.zoom.toFloat(), camera.rotation.toFloat(),
            label = tr("Gateway", "Passerelle"), purple = colors.Danger)
    }
    if (pointMove.pointMode) {
        val points = if (state.navTest.running) state.navTest.planned else pointTarget.pointPlan?.points.orEmpty()
        if (points.size > 1) drawTransitPath(path(points,false), screenUnit, colors, 3f)
        // Straight exit from the base to the link point, before the path.
        val exitLine = if (state.navTest.running) state.navTest.plannedExit else pointTarget.pointPlan?.departure?.let { listOf(it.from, it.approach) }.orEmpty()
        if (exitLine.size > 1) drawTransitPath(path(exitLine,false), screenUnit, colors, 3f)
        // Sections beyond the mapped zones: orange dots over the route.
        (if (state.navTest.running) state.navTest.plannedOffMap else pointTarget.pointPlan?.offMap.orEmpty())
            .forEach { if (it.size > 1) drawOffMapPath(path(it,false), screenUnit, colors) }
        pointTarget.destination?.let { drawMapTarget(screen(it), screenUnit, colors) }
        val trail = state.navTest.trail.map { Point(it.x,it.y) }
        if (trail.size>1) drawPath(path(trail,false),colors.RobotMarker,style=screenStroke(2f))
    }
    // Real position distinct from the simulation.
    if (!demo) {
        recoveryPoints.zipWithNext().filter { (a, b) -> b.at - a.at <= 5000 }.forEach { (a, b) ->
            drawPath(path(listOf(Point(a.x, a.y), Point(b.x, b.y)), false),
                colors.RobotMarker.copy(alpha = 0.6f), style = screenStroke(2f))
        }
    }
    drawZoneOrder(map, selected.toList(), ::screen, screenUnit, camera.rotation.toFloat())
    if (display.zoneNames) drawZoneNames(zoneNameLabels, selected.toList(), ::screen, scale, screenUnit, camera.rotation.toFloat(), colors)
}
}
