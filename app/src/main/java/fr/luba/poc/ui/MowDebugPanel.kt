package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.PaintedMission
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.planning.FinishingCheck
import fr.luba.poc.planning.FinishingOffer
import fr.luba.poc.planning.FinishingPass
import fr.luba.poc.planning.FinishingPlan
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.simulation.MissionAvoidance
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/**
 * Finishing asked from a mowing report ([fr.luba.poc.history.FinishingOfferGroup]): the finishing page targets the gaps
 * of that mowing, for the map it was asked on.
 */
object FinishingDraft {
    private var draft by mutableStateOf<Pair<RobotMap, FinishingOffer>?>(null)
    fun offer(map: RobotMap): FinishingOffer? = draft?.takeIf { it.first == map }?.second
    fun set(map: RobotMap, offer: FinishingOffer?) { draft = offer?.let { map to it } }
}

/**
 * Service → Finishing the gaps, opened from a mowing report: real mowing, blades running, limited to the gaps of that
 * mowing ([FinishingPass]). The route is computed, checked and followed by the same building blocks as mowing started
 * from the map (planner, preparation, BLE control, return to base, robot protections). A zone painted with a finger is
 * chosen from Mow, with the zones ([fr.luba.poc.map.ZonePaintState]).
 */
@Composable
internal fun MowDebugPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    val map = state.robotMap
    val context = LocalContext.current
    val store = remember(map) { BaseStore(context, map) }
    val base = remember(map) { store.resolve(map) }
    val accesses = remember(map) { store.accesses() }
    var height by rememberSaveable { mutableIntStateOf(state.telemetry.bladeHeightMm ?: state.cutHeights.defaultMm) }
    // Range of the connected model (higher on the H versions): a height chosen for another robot is brought back into it.
    LaunchedEffect(state.cutHeights) { height = state.cutHeights.clamp(height) }
    var spacing by rememberSaveable { mutableFloatStateOf(0.25f) }
    var supervised by rememberSaveable { mutableStateOf(false) }
    val finishing = FinishingDraft.offer(map)
    val routeStarted = state.robotRoute.started && state.robotRoute.running
    val t = state.telemetry
    // Start as close as possible to the robot; the base if it is charging or its position is unknown. Only the switch
    // between the two prepares again: the robot moving does not.
    val fromBase = t.x == null || t.y == null || t.chargeState in setOf(1, 2)
    val preparing: Preparing<FinishingPlan>? = if (base == null || finishing == null) null else { checkpoint, progress ->
        val start = if (!fromBase && t.x != null && t.y != null) Point(t.x, t.y) else base.point
        FinishingPass.prepare(map, finishing, start, base, accesses, state.obstacles, spacing.toDouble(), 0.3, checkpoint, progress)
    }
    // Prepared on arrival and again on every change of the gaps, the map, the settings, the obstacles or the start.
    val preparation = rememberAutoPreparation(listOf(map, finishing, spacing, state.obstacles, base, accesses, fromBase), preparing) {
        robot.recordMapPreview(it.mission.preview)
    }
    val mission = preparation.result?.mission

    val idle = state.ready && !state.robotRoute.running && !state.manualActive && !state.mowing.active && !state.navTest.running &&
        !state.teach.running && !state.campaign.running && !state.bladeTestActive && !state.mapLoading && !state.bladeHeightPending &&
        !state.heightSequence.running

    PushedPage(ServicePage.MOW_DEBUG.title, back, bottomBar = {
        MowDebugActions(robot, state, mission?.engine, height, supervised, idle, preparation, onStart = ::forgetBorderFollow)
    }) {
        header()
        SafetyNotice(tr("Real mowing, blades running, limited to the gaps of the last mowing and their edges. Area cleared (toys, cables, stones), no people or animals, physical stop within reach.",
            "Vraie tonte, lames en marche, limitée aux manques de la dernière tonte et à leurs bords. Zone dégagée (jouets, câbles, cailloux), personne ni animal, arrêt physique accessible."))
        if (finishing == null) InfoNote(tr("No gaps to finish: open the report of a mowing in History and choose “Prepare the finishing”. To mow part of a zone, paint it from Mow.",
            "Aucun manque à finir : ouvrez le compte rendu d'une tonte dans l'Historique et choisissez « Préparer la finition ». Pour tondre une partie de zone, peignez-la depuis Tondre."))
        else {
            MowDebugMap(state, finishing.gaps.flatMap { it.outlines }, mission)
            FinishingTarget(finishing, preparation.result?.check)
        }
        MowDebugStatus(state, mission, base != null)
        MowDebugSettings(robot, state, height, { height = it }, spacing, { spacing = it }, supervised, { supervised = it }, routeStarted)
    }
}

/** Gaps targeted by the finishing and checks of the prepared route ([FinishingPass.check]). */
@Composable
private fun FinishingTarget(offer: FinishingOffer, check: FinishingCheck?) {
    SettingsGroup(title = tr("Finishing of the gaps", "Finition des manques")) {
        KeyValueRow(tr("Left unmowed", "Reste à tondre"), tr("${Format.num(offer.areaM2, 1)} m² in ${Format.plural(offer.count, "gap", "gaps")}",
            "${Format.num(offer.areaM2, 1)} m² en ${Format.plural(offer.count, "manque", "manques")}"))
        check?.let { c ->
            KeyValueRow(tr("Route", "Trajet"), tr("${Format.num(c.routeM, 0)} m · ${(c.ratio * 100).toInt()} % of a new mowing (${Format.num(c.newMowingM, 0)} m)",
                "${Format.num(c.routeM, 0)} m · ${(c.ratio * 100).toInt()} % d'une nouvelle tonte (${Format.num(c.newMowingM, 0)} m)"))
            KeyValueRow(tr("Robot envelope", "Gabarit du robot"), if (c.safe) tr("Every pass inside the safe surface", "Toutes les passes dans la surface sûre")
                else tr("${c.collisions.size} pass(es) outside", "${c.collisions.size} passe(s) hors surface"))
        }
        InfoNote(tr("Gaps of the last mowing, in orange: each one is widened a little over the mowed grass so that the lap runs on its edge and the passes cover it. " +
            "Route refused if a pass leaves the safe surface or if it exceeds ${(FinishingPass.MAX_ROUTE_RATIO * 100).toInt()} % of a new mowing.",
            "Manques de la dernière tonte, en orange : chacun est un peu élargi sur l'herbe tondue pour que le tour passe sur son bord et que les passes le couvrent. " +
            "Trajet refusé si une passe sort de la surface sûre ou s'il dépasse ${(FinishingPass.MAX_ROUTE_RATIO * 100).toInt()} % d'une nouvelle tonte."))
    }
}

@Composable
private fun MowDebugMap(state: RobotUi, pieces: List<List<Point>>, mission: PaintedMission?) {
    val t = state.telemetry
    val route = state.robotRoute
    var cameraReset by remember { mutableIntStateOf(0) }
    SettingsGroup(title = tr("Map", "Carte")) {
        Box(Modifier.fillMaxWidth().height(420.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
            MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                trail = if (route.started) route.trail else emptyList(),
                planned = if (route.started) route.planned else mission?.engine?.edges?.filterNot { it.cutting }?.let { e -> e.map { it.a } + listOfNotNull(e.lastOrNull()?.b) }.orEmpty(),
                cuttingPasses = mission?.preview?.cuttingPaths.orEmpty(),
                obstacles = state.obstacles, interactive = true, cameraReset = cameraReset,
                paintPieces = pieces, modifier = Modifier.fillMaxSize())
            MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        if (t.x == null || t.y == null) InfoNote(tr("Robot position unavailable: the route will start from the base.", "Position du robot indisponible : le trajet partira de la base."))
        else if (t.chargeState in setOf(1, 2)) InfoNote(tr("Robot charging: straight exit from the base, then a link; recharges first if the battery is too low.", "Robot en charge : sortie de base en ligne droite, puis raccord ; recharge d'abord si la batterie est trop basse."))
    }
}

@Composable
private fun MowDebugSettings(robot: RobotBle, state: RobotUi, height: Int, onHeight: (Int) -> Unit, spacing: Float, onSpacing: (Float) -> Unit,
                             supervised: Boolean, onSupervised: (Boolean) -> Unit, locked: Boolean) {
    SettingsGroup(title = tr("Mowing settings", "Réglages de la tonte")) {
        SliderRow(tr("Cutting height", "Hauteur de coupe"), height.toFloat(), { onHeight(it.toInt()) }, state.cutHeights.floatRange(),
            valueLabel = "$height mm", steps = state.cutHeights.sliderSteps, enabled = !locked)
        SliderRow(tr("Pass spacing", "Espacement des passes"), spacing, { onSpacing((it * 20).let(Math::round) / 20f) }, 0.15f..0.35f,
            valueLabel = "${(spacing * 100).toInt()} cm", steps = 3, enabled = !locked)
        SliderRow(tr("Forward speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
            valueLabel = "${state.trajectorySpeedPercent} %", enabled = !locked)
        LubaCheckRow(tr("I am supervising on site, area cleared", "Je supervise sur place, zone dégagée"), supervised, onSupervised, enabled = !state.robotRoute.running)
    }
    InfoNote(tr("Like mowing from the map: link from the robot, passes with blades running, links with blades stopped, return to base. " +
        "Obstacle, contact, deviation, unexpected state or STOP: robot and blades stop. " +
        "Required coverage: ${(PaintedZone.MIN_COVERAGE * 100).toInt()} % of the retained area.",
        "Comme une tonte depuis la carte : raccord depuis le robot, passes lames en marche, liaisons lames arrêtées, retour à la base. " +
        "Obstacle, contact, écart, état inattendu ou STOP : arrêt du robot et des lames. " +
        "Couverture exigée : ${(PaintedZone.MIN_COVERAGE * 100).toInt()} % de la surface retenue."))
}

@Composable
private fun MowDebugStatus(state: RobotUi, mission: PaintedMission?, baseKnown: Boolean) {
    // Nothing to track: no empty card.
    if (baseKnown && mission == null && !state.robotRoute.started) return
    SettingsGroup(title = tr("Tracking", "Suivi")) {
        if (!baseKnown) StatusNote(tr("Base unknown or without a docking axis: leave the robot charging so it can be observed, or read the map again.", "Base inconnue ou sans axe d'accostage : laissez le robot en charge pour l'observer, ou relisez la carte."), tone = NoteTone.WARNING)
        mission?.let { m ->
            val metrics = m.report?.selected?.metrics
            KeyValueRow(tr("Target area", "Surface ciblée"), tr("${Format.num(m.areaM2, 1)} m² outside no-go zones", "${Format.num(m.areaM2, 1)} m² hors no-go"))
            if (m.preview.skippedBorderLoops > 0) StatusNote(tr("${m.preview.skippedBorderLoops} border lap(s) impassable for the chassis " +
                "(no-go zone or obstacle too close): not driven, zigzags with a reserve around them.", "${m.preview.skippedBorderLoops} tour(s) de bordure impraticable(s) pour le châssis " +
                "(no-go ou obstacle trop près) : non parcouru(s), zigzags avec réserve autour."), tone = NoteTone.WARNING)
            KeyValueRow(tr("Planned coverage", "Couverture prévue"), metrics?.let { "${(it.coverage * 100).toInt()} %" } ?: "—")
            KeyValueRow(tr("Cutting · links", "Coupe · liaisons"), "${Format.num(m.engine.total, 0)} m · ${Format.num(m.engine.edges.filterNot { it.cutting }.sumOf { it.meters }, 0)} m")
        }
        MowDebugRouteStatus(state)
    }
}

/** Progress of the current or finished BLE route, shared by both modes. */
@Composable
internal fun MowDebugRouteStatus(state: RobotUi) {
    val route = state.robotRoute
    if (!route.started) return
    StatusNote(route.status, tone = if (route.running) NoteTone.WARNING else NoteTone.INFO)
    if (route.count > 0) {
        val done = route.index.toFloat() / route.count
        LubaProgress(done, Modifier.fillMaxWidth())
        KeyValueRow("Segment", "${route.index}/${route.count}")
    }
    KeyValueRow(tr("Cutting height", "Hauteur de coupe"), "${route.heightMm} mm")
    KeyValueRow(tr("Battery", "Batterie"), state.telemetry.battery?.let { "$it %" } ?: "—")
    if (route.running) InfoNote(tr("Mowing continues if you leave this page; STOP remains available here and on the map.", "La tonte continue si vous quittez cette page ; STOP reste disponible ici et sur la carte."))
}

@Composable
internal fun ColumnScope.MowDebugActions(robot: RobotBle, state: RobotUi, engine: MowingSimulation?, height: Int, supervised: Boolean, idle: Boolean,
                                         preparation: AutoPreparation<*>, onStart: () -> Unit = {}, blades: Boolean = true) {
    if (state.robotRoute.running) {
        DangerPillButton(if (blades) tr("STOP · stop the robot and the blades", "STOP · arrêter le robot et les lames") else tr("STOP · stop the robot", "STOP · arrêter le robot"), { robot.stopBladeTest(tr("STOP debug mowing", "STOP tonte debug")) }, Modifier.fillMaxWidth())
        return
    }
    // Route prepared by itself (on arrival, after every change): its state stays next to the button it enables.
    if (preparation.computing) ProgressNote(tr("Computing the route · ${preparation.stage}", "Calcul du trajet · ${preparation.stage}"))
    if (preparation.error.isNotBlank()) StatusNote(preparation.error, tone = NoteTone.DANGER)
    // Prepared with saved obstacles only (neither presumed obstacles nor avoided areas): the resume prepares the same way.
    PrimaryPillButton(if (blades) tr("Mow · $height mm", "Tondre · $height mm") else tr("Follow · blades OFF", "Suivre · lames OFF"),
        { engine?.let { if (robot.startRobotRoute(it, blades, height, MissionAvoidance(emptyList(), emptyList()))) onStart() } }, Modifier.fillMaxWidth(),
        enabled = engine != null && supervised && idle && state.robotMap == engine.map, icon = Icons.Rounded.PlayArrow)
    // A single saved task per robot: the new one replaces the one waiting to be resumed on the map.
    if (engine != null) state.missionCheckpoint?.let {
        InfoNote(tr("Starting replaces the saved task “${it.task.title}” (${(it.progress * 100).toInt()} % ${it.task.doneLabel}), resumed from the map.", "Démarrer remplace la sauvegarde « ${it.task.title} » (${(it.progress * 100).toInt()} % ${it.task.doneLabel}), reprise depuis la carte."))
    }
    InfoNote(tr("Interrupted (STOP, link, app closed): resume from the point reached, from the map.", "Interrompu (STOP, liaison, fermeture de l'application) : reprise au point atteint depuis la carte."))
}
