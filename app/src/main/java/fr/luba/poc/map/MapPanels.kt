package fr.luba.poc.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.planning.*
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.weather.WeatherPillSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Header, side bar and panels laid on the map ([MapScreen]): named composables reading the map state holders
 * ([MapSheets], [MapActionState], [PointMoveState]…).
 */

/** Mammotion header: back, status pill (Bluetooth or gateway link, positioning, batteries, weather). */
@Composable
internal fun MapHeader(
    state: RobotUi, telemetry: fr.luba.poc.protocol.Telemetry, demo: Boolean, cloud: Boolean,
    apiPosition: fr.luba.poc.protocol.Telemetry?, apiKnown: LastKnownPosition?, known: LastKnownPosition?,
    form: MowingTaskForm, sheets: MapSheets, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val fresh = state.connection == ConnectionState.Receiving && (state.ageMs ?: Long.MAX_VALUE) < 5000
    val shownTelemetry = if (cloud) apiPosition ?: fr.luba.poc.protocol.Telemetry.EMPTY else telemetry
    // Charge: Mammotion account (API), or active BLE if it is more recent.
    val charge = if (demo) null else rememberChargeStatus(apiPosition, telemetry.takeIf { state.ready })
    Row(modifier.fillMaxWidth().padding(Dimens.Md), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) MapSideButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back", "Retour"), onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DeviceStatusPill(bluetooth = state.ready && !demo, battery = if (demo) null else charge?.percent ?: shownTelemetry.battery ?: (if (cloud) apiKnown?.battery else known?.battery),
                position = positionQuality(shownTelemetry.rtkStatus), cloud = cloud,
                charging = charge != null, gateway = !demo && !cloud, viaGateway = state.viaGateway && !demo && !cloud,
                stale = state.ready && !fresh && !demo && !cloud,
                threshold = if (demo) null else batteryThreshold(charge != null, state.robotRoute,
                    form.chargeLow.roundToInt(), form.chargeHigh.roundToInt()),
                onClick = { sheets.statusPanel = true }) {
                // Garden weather: detail on tap.
                WeatherPillSegment(state.mowing.running || state.robotRoute.running, telemetry)
            }
        }
        if (onBack != null) Spacer(Modifier.size(44.dp))
    }
}

/** Right vertical bar: compass, station, recentering, following, move to a point, layers. */
@Composable
internal fun MapSideBar(
    camera: () -> MapCamera, onCamera: (MapCamera) -> Unit, follow: RobotFollow, scope: CoroutineScope,
    sheets: MapSheets, layers: MapLayersState, actions: MapActionState, pointMove: PointMoveState, pointTarget: PointTarget,
    stationEnabled: Boolean, followEnabled: Boolean, moveEnabled: Boolean, layersEnabled: Boolean, layersShown: Boolean,
) {
    val colors = MammotionColors
    Column(Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.Sm),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // Compass: follows the map rotation, a tap brings north back to the top.
        MapCompass(camera().rotation, { degrees -> onCamera(camera().rotatedBy(degrees)) })
        MapSideButton(Icons.Rounded.EvStation, tr("Charging station", "Station de charge"), { sheets.baseDialog = true },
            enabled = stationEnabled, tint = colors.Green)
        MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter the map", "Recentrer la carte"), {
            val from = follow.view(camera()); follow.stop()
            scope.launch { animateCamera(from, MapCamera()) { onCamera(it) } }
        })
        // Robot following: map centered on it, zoom kept; a second tap stops it.
        MapSideButton(if (follow.enabled) Icons.Rounded.GpsFixed else Icons.Rounded.MyLocation,
            if (follow.enabled) tr("Stop following the robot", "Arrêter le suivi du robot") else tr("Follow the robot", "Suivre le robot"), {
                if (follow.enabled) { onCamera(follow.view(camera())); follow.stop() } else follow.start()
            }, enabled = followEnabled, tint = if (follow.enabled) colors.Green else colors.TextPrimary)
        MapSideButton(Icons.Rounded.NearMe, tr("Move to a point", "Déplacer à un point"), {
            pointMove.pointMode = true; pointTarget.destination = null; pointMove.pointConfirmed = false; pointMove.pointAttempted = false; sheets.selectingZones = false
        }, enabled = moveEnabled && !actions.placingAccess && !pointMove.pointMode)
        // Layers: show the signal surveys above the map (bottom bar), or fold them.
        MapSideButton(Icons.Rounded.Layers, if (layers.layersOpen) tr("Hide the layers", "Masquer les calques") else tr("Show the signal layers", "Afficher les calques des signaux"), {
            layers.layersOpen = !layers.layersOpen; layers.showSignalHeat = layers.layersOpen
        }, enabled = layersEnabled, tint = if (layers.layersOpen && layers.showSignalHeat && layersShown) colors.Green else colors.TextPrimary)
    }
}

/** Robot protections read from its self-check: STOP lock, collision, forbidden time range, beyond the mapped zones. */
internal class RobotProtection(state: RobotUi, demo: Boolean, cloud: Boolean) {
    val selfCheck = state.telemetry.selfCheck
    val stopLock = fr.luba.poc.protocol.StopUnlock.locked(selfCheck)
    // Self-check 25 beyond the mapped zones, bumper clear: not a lock for the blade-OFF moves (2026-10-20,
    // [fr.luba.poc.protocol.OffMapLatch]); a note replaces the "Robot stuck" panel.
    val offMapLatch = state.ready && !demo && !cloud && !state.mowing.active && !state.robotRoute.running &&
        fr.luba.poc.protocol.OffMapLatch.ignored(selfCheck, state.telemetry.sensorStatus,
            PointPlanner.beyondMapped(state.robotMap, state.telemetry.x?.let { x -> state.telemetry.y?.let { y -> Point(x, y) } }))
    val robotBlocked = state.ready && !demo && !cloud && ((fr.luba.poc.protocol.StopUnlock.blocked(selfCheck) && !offMapLatch) || state.unlockPending)
    /** Forbidden time range of the Mammotion app (self-check 23): nothing to unlock. */
    val forbiddenTime = fr.luba.poc.protocol.SelfCheck.forbiddenTime(selfCheck)
}

/**
 * Robot STOP button pressed (self-check 11) or another protection (25 on 2026-10-14): no movement possible before
 * unlocking; the attempt stays without movement. Robot stuck and mowing interrupted: a single panel (2026-10-15, 17:53:
 * two stacked technical panels covered the whole map). The resume shows once the robot is unlocked.
 */
@Composable
internal fun RobotProtectionPanel(
    protection: RobotProtection, state: RobotUi, blockedCheckpoint: MissionCheckpoint?, pointMode: Boolean,
    onDiscard: () -> Unit, onUnlockRobot: () -> Unit,
) {
    val colors = MammotionColors
    val selfCheck = protection.selfCheck
    val stopLock = protection.stopLock
    val robotBlocked = protection.robotBlocked
    var offMapHidden by remember(selfCheck) { mutableStateOf(false) }
    // Forbidden time range: a message is enough (can be hidden until the next state change).
    var forbiddenHidden by remember(selfCheck) { mutableStateOf(false) }
    if (robotBlocked && protection.forbiddenTime && !state.unlockPending) {
        if (!forbiddenHidden) MapFloatingNote(tr("Outside the working hours set in the Mammotion app: the robot refuses to work " +
            "until the end of this range. Nothing to unlock. To stop being blocked, disable this range in the Mammotion " +
            "app and use the DumbyLuba one (Task settings): clean stop, return to base " +
            "and resume at the interrupted point.",
            "Hors de la plage horaire réglée dans l'app Mammotion : le robot refuse de travailler " +
            "jusqu'à la fin de cette plage. Rien à déverrouiller. Pour ne plus être bloqué, désactiver cette plage dans l'app " +
            "Mammotion et utiliser celle de DumbyLuba (Paramètres de la tâche) : arrêt propre, retour à la base " +
            "et reprise au point interrompu."), NoteTone.WARNING) { forbiddenHidden = true }
    }
    else if (protection.offMapLatch && !robotBlocked && !offMapHidden && !state.navTest.running && !state.manualActive)
        MapFloatingNote(tr("Beyond the mapped zones (self-check 25): ignored, joystick and move to a point stay available, blades stopped.",
            "Hors des zones cartographiées (self-check 25) : ignoré, joystick et déplacement au point restent possibles, lames arrêtées."),
            NoteTone.INFO) { offMapHidden = true }
    else if (robotBlocked) {
        val collision = fr.luba.poc.protocol.CollisionLatch.latched(selfCheck)
        var details by remember { mutableStateOf(false) }
        MapPanel(when { stopLock -> tr("Robot locked after STOP", "Robot verrouillé après STOP"); collision -> tr("Robot stuck against an obstacle", "Robot bloqué contre un obstacle"); else -> tr("Robot protection active", "Protection du robot active") },
            subtitle = blockedCheckpoint?.let { tr("${it.task.title} at ${(it.progress * 100).toInt()} % · resume possible once the robot is cleared", "${it.task.title} à ${(it.progress * 100).toInt()} % · reprise possible une fois le robot dégagé") }) {
            Text(state.unlockStatus.ifBlank { when {
                stopLock -> tr("The robot's STOP button was used: it refuses any movement.", "Le bouton STOP du robot a été utilisé : il refuse tout déplacement.")
                collision -> tr("It touched an object and refuses any movement while it stays against it. \"Clear\" backs it off by 30 cm, blades stopped.", "Il a touché un objet et refuse tout déplacement tant qu'il reste contre lui. « Dégager » le fait reculer de 30 cm, lames arrêtées.")
                else -> tr("It refuses any movement while its protection stays active.", "Il refuse tout déplacement tant que sa protection reste active.")
            } }, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
            if (details) InfoNote((if (collision) tr("30 cm straight back-off, blades stopped, then wait for the lock to be released (self-check $selfCheck).", "Recul de 30 cm en ligne droite, lames arrêtées, puis attente de la levée du verrou (self-check $selfCheck).")
                else tr("Unlock without movement: stop, cancel any Mammotion task, read the errors again (self-check $selfCheck).", "Déverrouillage sans mouvement : arrêt, annulation de la tâche Mammotion éventuelle, relecture des erreurs (self-check $selfCheck).")) +
                tr(" If the robot refuses: ${fr.luba.poc.protocol.StopUnlock.physical(selfCheck)}.", " Si le robot le refuse : ${fr.luba.poc.protocol.StopUnlock.physical(selfCheck)}.") +
                (blockedCheckpoint?.interruption?.let { tr("\nStop: ${InterruptionSummary.of(it).detail ?: it}", "\nArrêt : ${InterruptionSummary.of(it).detail ?: it}") } ?: ""))
            TextAction(if (details) tr("Hide the details", "Masquer les détails") else tr("Details", "Détails"), { details = !details })
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                if (blockedCheckpoint != null) SecondaryPillButton(tr("Discard", "Abandonner"), onDiscard, Modifier.weight(1f),
                    enabled = !state.unlockPending)
                PrimaryPillButton(if (state.unlockPending) tr("Unlocking…", "Déverrouillage…") else if (collision) tr("Clear", "Dégager") else tr("Unlock", "Déverrouiller"), onUnlockRobot,
                    Modifier.weight(1f), enabled = !state.unlockPending && !state.navTest.running && !state.robotRoute.running && !state.manualActive)
            }
        }
    }
    else if (state.unlockStatus.startsWith(tr("Robot unlocked", "Robot déverrouillé")) && !pointMode)
        MapFloatingNote(state.unlockStatus, NoteTone.SUCCESS)
}

/** Manual access being drawn: free passage into the zones, travelled with blades stopped. */
@Composable
internal fun ManualAccessPanel(actions: MapActionState, onValidate: (List<Point>) -> Unit) {
    if (!actions.placingAccess) return
    MapPanel(tr("Manual access · ${actions.draftAccess.size} points", "Accès manuel · ${actions.draftAccess.size} points"),
        subtitle = tr("Draw a free passage into the zones. Assumed width: 0.8 m + margin.", "Tracez un passage libre jusqu'à l'intérieur des zones. Largeur supposée : 0,8 m + marge."),
        onClose = { actions.placingAccess = false; actions.draftAccess = emptyList() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            TextAction(tr("Undo the point", "Annuler le point"), { actions.draftAccess = actions.draftAccess.dropLast(1) }, enabled = actions.draftAccess.isNotEmpty())
            Spacer(Modifier.weight(1f))
            PrimaryPillButton(tr("Validate", "Valider"), { onValidate(actions.draftAccess); actions.placingAccess = false; actions.draftAccess = emptyList() },
                enabled = actions.draftAccess.size >= 2)
        }
    }
}

/** Simulated route without heatmap: opens the sheet that hands it to the robot. */
@Composable
internal fun SimulatedRouteBar(sheets: MapSheets) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { sheets.signalPanel = true }.padding(vertical = 8.dp)) {
                Text(tr("Simulated route", "Trajet simulé"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("Run this route on the robot", "Exécuter ce trajet sur le robot"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { sheets.signalPanel = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Route and local mowing details", "Détails du trajet et de la tonte locale")) }
        }
    }
}

/** Simulation in progress: phase, mowed share, battery, time, play/pause, speed and details. */
@Composable
internal fun SimulationBar(s: SimState, total: Double, sim: SimulationUi, simulation: SimulationSession, sheets: MapSheets, routeRunning: Boolean) {
    val colors = MammotionColors
    val coverage = (s.covered / total).toFloat().coerceIn(0f, 1f)
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { sheets.simulationDialog = true }.padding(vertical = 6.dp)) {
                    Text("Simulation · ${s.phase.label}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                        Text(tr("Mowed ${(coverage * 100).toInt()} %", "Tondu ${(coverage * 100).toInt()} %"), style = MaterialTheme.typography.labelMedium, color = colors.Green)
                        Text(tr("Battery ${s.battery.toInt()} %", "Batterie ${s.battery.toInt()} %"), style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                        Text("${(s.elapsed / 60).toInt()} min", style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                    }
                }
                if (s.phase !in setOf(SimPhase.COMPLETE, SimPhase.BLOCKED)) IconButton(
                    onClick = simulation::toggle, enabled = !routeRunning
                ) { Icon(if (sim.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (sim.playing) tr("Pause the simulation", "Mettre la simulation en pause") else tr("Resume the simulation", "Reprendre la simulation")) }
                Box {
                    TextAction("×${sim.speed}", { sheets.simulationSpeedMenu = true })
                    DropdownMenu(expanded = sheets.simulationSpeedMenu, onDismissRequest = { sheets.simulationSpeedMenu = false }) {
                        listOf(1, 5, 20, 60, 120, 300).forEach { speed ->
                            DropdownMenuItem(text = { Text("×$speed${if (sim.speed == speed) tr(" · selected", " · sélectionné") else ""}") },
                                onClick = { simulation.speed(speed); sheets.simulationSpeedMenu = false })
                        }
                    }
                }
                IconButton(onClick = { sheets.simulationDialog = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Simulation details and speed", "Détails et vitesse de simulation")) }
            }
            LubaProgress(coverage, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp))
            if (s.phase == SimPhase.BLOCKED) StatusNote(s.log.lastOrNull().orEmpty(), NoteTone.DANGER,
                Modifier.padding(horizontal = Dimens.Md, vertical = Dimens.Xs))
        }
    }
}

/** Route computation in progress and computation error, while the task pages are closed. */
@Composable
internal fun RouteComputationPanels(prep: RoutePreparationState, sheets: MapSheets) {
    if (prep.computing && !sheets.mowingDialog) MapPanel(tr("Computing the route", "Calcul du trajet"), subtitle = prep.progress.third) {
        LubaProgress(prep.progress.first.toFloat() / prep.progress.second.coerceAtLeast(1), Modifier.fillMaxWidth())
    }
    if (prep.error.isNotBlank() && !sheets.mowingDialog) MapFloatingNote(prep.error, NoteTone.DANGER, onDismiss = { prep.error = "" })
}

/** Zone choice on the map, in mowing order (numbers on the map). */
@Composable
internal fun ZoneChoicePanel(sheets: MapSheets, form: MowingTaskForm, prep: RoutePreparationState, map: RobotMap,
                             selected: Set<Long>, onSelected: (Set<Long>) -> Unit) {
    MapPanel(if (sheets.surveyTask) tr("Survey zones", "Zones du relevé") else tr("Mowing zones", "Zones de tonte"),
        subtitle = if (!map.complete) tr("Load a complete map first (Map button).", "Chargez d'abord une carte complète (bouton Carte).")
            else if (sheets.surveyTask) tr("Tap the zones to drive through, blades stopped: they will be surveyed in this order.", "Touchez les zones à parcourir, lames arrêtées : elles seront relevées dans cet ordre.")
            else tr("Tap the zones in mowing order: they will be mowed in this order.", "Touchez les zones dans l'ordre de tonte : elles seront tondues dans cet ordre."),
        onClose = { sheets.selectingZones = false }, closeDescription = tr("Close the zone choice", "Fermer le choix des zones")) {
        ZoneOrderChips(map, selected.toList(), onRemove = { id -> onSelected(selected - id) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
            TextAction(tr("Add all", "Tout ajouter"), { onSelected(selected + map.shapes.filter { it.kind == ShapeKind.ZONE }.map { it.id }) }, enabled = map.complete)
            TextAction(tr("Clear", "Effacer"), { onSelected(emptySet()) }, enabled = selected.isNotEmpty())
            Spacer(Modifier.weight(1f))
            PrimaryPillButton(tr("Next", "Suivant"), { sheets.selectingZones = false; form.mowingStep = 1; sheets.mowingDialog = true },
                enabled = selected.isNotEmpty() && map.complete && !prep.computing)
        }
    }
}

/** Bottom action bar (Mow, Manual, History, Map), with the demo or Mammotion task note and the empty map panel. */
@Composable
internal fun MapActions(
    state: RobotUi, demo: Boolean, cloud: Boolean, map: RobotMap, previewReady: Boolean, sheets: MapSheets, form: MowingTaskForm,
    onChooseZones: () -> Unit, onFetchMap: () -> Unit, onOpenAccount: () -> Unit, onOpenManual: () -> Unit,
) {
    val colors = MammotionColors
    // Link, positioning and battery are in the top pill: only a Mammotion task in progress or the demo map
    // are still flagged above the bar.
    if (demo || state.mowing.active) MapFloatingNote(if (demo) tr("Demo map", "Carte de démonstration") else state.mowing.status, NoteTone.INFO)
    // No map on this phone (new installation, cache cleared): reading triggered from here, over Bluetooth
    // by the gateway when the pilot goes through it, instead of an empty map with the action hidden in the sheet.
    if (!demo && !map.complete && !state.mowing.active) NoMapPanel(state, cloud, mapFetchAllowed(state, false),
        onFetch = onFetchMap, onAccount = onOpenAccount)
    ActionBar {
        // Mow goes back to the preparation in progress (preview already computed); otherwise zone choice.
        ActionBarItem(Icons.Rounded.PlayArrow, if (state.mowing.active) tr("Follow", "Suivre") else tr("Mow", "Tondre"), colors.ActionMow, {
            when {
                state.mowing.active -> sheets.mowingDialog = true
                previewReady && !sheets.surveyTask -> { form.mowingStep = 2; sheets.mowingDialog = true }
                else -> onChooseZones()
            }
        }, Modifier.weight(1f), highlighted = true)
        ActionBarItem(Icons.Rounded.Gamepad, tr("Manual", "Manuel"), colors.ActionManual, onOpenManual, Modifier.weight(1f), enabled = state.ready && !demo && !cloud)
        ActionBarItem(Icons.Rounded.History, tr("History", "Historique"), colors.ActionTasks, { sheets.taskHistory = true }, Modifier.weight(1f))
        ActionBarItem(Icons.Rounded.Map, tr("Map", "Carte"), colors.ActionMap, { sheets.mapActions = true }, Modifier.weight(1f))
    }
}

/** Chosen zones in mowing order: numbered badge, tap to remove. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ZoneOrderChips(map: RobotMap, order: List<Long>, onRemove: (Long) -> Unit) {
    val colors = MammotionColors
    if (order.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        order.forEachIndexed { index, id ->
            val name = map.shapes.firstOrNull { it.id == id }?.let { MapCorrections.zoneTitle(it, "Zone ${index + 1}") } ?: "Zone ${index + 1}"
            Surface(onClick = { onRemove(id) }, shape = CircleShape, color = colors.GreenSoft) {
                Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(24.dp).background(colors.Ink, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = colors.OnInk)
                    }
                    Text(name, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.Close, contentDescription = tr("Remove $name", "Retirer $name"), modifier = Modifier.size(16.dp), tint = colors.TextSecondary)
                }
            }
        }
    }
}

/**
 * Move to a point: path, section beyond the mapped zones (warning and its own acknowledgement), supervision, speed,
 * start and STOP. Separate function: keeps MapScreen away from the Dalvik register limit.
 */
@Composable
internal fun PointMovePanel(
    state: RobotUi, destination: Point?, plan: fr.luba.poc.diagnostics.MapNavPlan?, computing: Boolean, error: String,
    attempted: Boolean, confirmed: Boolean, onConfirmed: (Boolean) -> Unit, idle: Boolean, onTrajectorySpeed: (Int) -> Unit,
    onStop: () -> Unit, onStart: () -> Unit, onClose: () -> Unit,
) {
    // Section beyond the mapped zones: acknowledged separately for each destination and each path.
    var offMapConfirmed by remember(destination, plan) { mutableStateOf(false) }
    val offMeters = plan?.offMapMeters ?: 0.0
    val offMap = offMeters > 0.0
    MapPanel(tr("Move to a point", "Déplacer à un point"),
        subtitle = if (state.navTest.running || attempted) state.navTest.status else if (computing) tr("Computing the path…", "Calcul du chemin…")
            else if (error.isNotBlank()) error
            else plan?.let {
                if (offMap) tr("Path: ${Format.num(it.meters,1)} m including ${Format.num(offMeters,1)} m beyond the map · blades stopped",
                    "Chemin : ${Format.num(it.meters,1)} m dont ${Format.num(offMeters,1)} m hors carte · lames arrêtées")
                else tr("Safe path: ${Format.num(it.meters,1)} m · blades stopped", "Chemin sûr : ${Format.num(it.meters,1)} m · lames arrêtées")
            } ?: tr("Tap the map to choose the destination, inside or beyond the zones.", "Touchez la carte pour choisir la destination, dans les zones ou au-delà."),
        onClose = onClose) {
        plan?.departure?.takeIf { !state.navTest.running }?.let { exit ->
            StatusNote(tr("Robot on its base: it first leaves in a straight line, slowly, to the link point (${Format.num(exit.meters,1)} m), then follows the path.",
                "Robot sur sa base : il sort d’abord en ligne droite, lentement, jusqu’au point de liaison (${Format.num(exit.meters,1)} m), puis suit le chemin."), NoteTone.INFO)
        }
        if (offMap && !state.navTest.running) StatusNote(tr("Beyond the mapped zones (orange dots): ${Format.num(offMeters,1)} m driven in a straight line without border check. Only the no-go zones and the saved obstacles are avoided: a wall, a pool or a slope are not known there.",
            "Au-delà des zones cartographiées (pointillés orange) : ${Format.num(offMeters,1)} m parcourus en ligne droite sans contrôle des bords. Seules les zones interdites et les obstacles enregistrés sont évités : un mur, une piscine ou une pente n’y sont pas connus."), NoteTone.WARNING)
        if (offMap && state.navTest.running) StatusNote(tr("Section beyond the map: stay next to the robot, STOP within reach.",
            "Tronçon hors carte : rester près du robot, STOP à portée de main."), NoteTone.WARNING)
        if (!state.navTest.running) LubaCheckRow(tr("I am supervising the move, area clear and stop within reach", "Je supervise le déplacement, zone dégagée et arrêt accessible"),
            confirmed, onConfirmed)
        if (offMap && !state.navTest.running) LubaCheckRow(tr("I have checked that the dotted section is clear and safe", "J’ai vérifié que le tronçon en pointillé est dégagé et sans danger"),
            offMapConfirmed, { offMapConfirmed = it })
        SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
            valueRange=10f..100f,valueLabel="${state.trajectorySpeedPercent} %")
        if (state.navTest.running) DangerPillButton("STOP", onStop, Modifier.fillMaxWidth())
        else PrimaryPillButton(tr("Go to the point", "Aller au point"), onStart, Modifier.fillMaxWidth(),
            enabled = idle && plan != null && !computing && confirmed && (!offMap || offMapConfirmed), icon = Icons.Rounded.NearMe)
    }
}

/**
 * Floating panel laid on the map (zone choice, move, access, resume): shaded white card, title, discreet subtitle and
 * optional close cross.
 */
@Composable
internal fun MapPanel(
    title: String? = null,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    closeDescription: String = tr("Close", "Fermer"),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            if (title != null || onClose != null) Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = if (onClose != null) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                }
                if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.padding(start = Dimens.Xs).size(40.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = closeDescription, tint = colors.TextSecondary)
                }
            }
            content()
        }
    }
}

/** Status message laid on the map, with "OK" to clear it when it can be dismissed. */
@Composable
internal fun MapFloatingNote(text: String, tone: NoteTone, onDismiss: (() -> Unit)? = null) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.Xs), verticalAlignment = Alignment.CenterVertically) {
            StatusNote(text, tone, Modifier.weight(1f))
            if (onDismiss != null) TextAction("OK", onDismiss)
        }
    }
}

/** BLE map reading possible now (robot linked directly or through the gateway, no mission, test or simulation). */
internal fun mapFetchAllowed(state: RobotUi, simulating: Boolean): Boolean =
    state.ready && !state.mapLoading && !state.campaign.running && !state.bladeTestActive && !state.navTest.running &&
        !state.teach.running && !state.robotRoute.running && !state.mowing.active && !state.manualActive && !simulating

/** Empty map: read the robot map (through the gateway when the pilot uses it) or open the Mammotion account map. */
@Composable
internal fun NoMapPanel(state: RobotUi, cloud: Boolean, fetchEnabled: Boolean, onFetch: () -> Unit, onAccount: () -> Unit) {
    val gateway = state.viaGateway && !cloud
    val localOnly = fr.luba.poc.auth.AccountMode.observe()
    val subtitle = when {
        state.mapLoading -> (if (gateway) tr("Reading the robot map over Bluetooth through the gateway…", "Lecture de la carte du robot en Bluetooth par la passerelle…")
            else tr("Reading the robot map over Bluetooth…", "Lecture de la carte du robot en Bluetooth…")) + " " + state.mammotionMap.source
        state.ready && gateway -> tr("Robot connected through the gateway: the gateway reads its map over Bluetooth.", "Robot connecté par la passerelle : la passerelle lit sa carte en Bluetooth.")
        state.ready -> tr("Robot connected over Bluetooth: read its map.", "Robot connecté en Bluetooth : lisez sa carte.")
        localOnly -> tr("Connect the robot (Bluetooth or gateway) to read its map.", "Connectez le robot (Bluetooth ou passerelle) pour lire sa carte.")
        else -> tr("Connect the robot (Bluetooth or gateway) to read its map, or view the Mammotion account map.", "Connectez le robot (Bluetooth ou passerelle) pour lire sa carte, ou consultez la carte du compte Mammotion.")
    }
    MapPanel(tr("No map on this phone", "Aucune carte sur ce téléphone"), subtitle = subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
            if (!localOnly) SecondaryPillButton(tr("Account map", "Carte du compte"), onAccount, Modifier.weight(1f))
            PrimaryPillButton(if (state.mapLoading) tr("Reading…", "Lecture…") else if (gateway) tr("Read via the gateway", "Lire par la passerelle") else tr("Read the robot map", "Lire la carte du robot"),
                onFetch, Modifier.weight(1.3f), enabled = fetchEnabled && !cloud)
        }
    }
}

/** Interrupted move to a point: destination kept, resume from the robot's real position. */
@Composable
internal fun InterruptedPointPanel(resume: PointResume, state: RobotUi, enabled: Boolean, onResume: () -> Unit, onDiscard: () -> Unit) {
    val summary = InterruptionSummary.of(resume.interruption)
    val t = state.telemetry
    val distance = if (t.x != null && t.y != null) Point(t.x, t.y).distance(resume.target) else null
    MapPanel(tr("Move to a point interrupted", "Déplacement au point interrompu"),
        subtitle = "${Format.time(resume.startedAt).substringBeforeLast(':').substringBefore('.')} · ${summary.label}") {
        Text(distance?.let { tr("Destination ${Format.num(it, 1)} m from the robot: route recomputed from its position.", "Destination à ${Format.num(it, 1)} m du robot : trajet recalculé depuis sa position.") }
            ?: tr("Destination kept: route recomputed from the robot's position.", "Destination conservée : trajet recalculé depuis la position du robot."),
            style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
        if (summary.detail != null) InfoNote(summary.detail)
        if (state.motorWatchdog?.demonstrated != true) StatusNote(tr("Stay nearby during the resume.", "Restez à proximité pendant la reprise."), NoteTone.WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Discard", "Abandonner"), onDiscard, Modifier.weight(1f))
            PrimaryPillButton(tr("Resume", "Reprendre"), onResume, Modifier.weight(1.3f), enabled = enabled)
        }
    }
}

/**
 * Resume of an interrupted mowing: progress, cause, supervision guard, errors and actions. Compact panel (Fold outer
 * screen, 2026-10-16: four big buttons, the progress bar duplicated with the mowing bar and the layer strip hid the
 * whole map): "Resume" as the only button, "Discard" as a link, "Unblock" and "Resume further on" in the "⋯" menu.
 * "Unblock" backs the robot off to a safe position slightly before the blockage; "Resume further on" skips a stripe
 * distance chosen with the slider or by tapping the map, the resume point being drawn live.
 */
@Composable
internal fun InterruptedMowingPanel(
    checkpoint: MissionCheckpoint, map: RobotMap, state: RobotUi, busy: Boolean, resumeError: String,
    resumeEnabled: Boolean, skip: ResumeSkipView?, skipOpen: Boolean, skipMeters: Float,
    onSkipMeters: (Float) -> Unit, onOpenSkip: () -> Unit, onCloseSkip: () -> Unit,
    onBackOff: () -> Unit, onStopBackOff: () -> Unit, onDiscard: () -> Unit, onResume: () -> Unit,
) {
    val colors = MammotionColors
    // Cause in one sentence, technical message in "Details" (menu).
    val summary = InterruptionSummary.of(checkpoint.interruption)
    var details by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = Dimens.Lg, end = Dimens.Xs, top = Dimens.Sm, bottom = Dimens.Sm),
            verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${checkpoint.task.title} · ${(checkpoint.progress * 100).toInt()} %", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Format.time(checkpoint.updatedAt).substringBeforeLast(':').substringBefore('.')} · ${summary.label}",
                        style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (skipOpen) IconButton(onClick = onCloseSkip, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = tr("Close \"Resume further on\"", "Fermer « Reprendre plus loin »"), tint = colors.TextSecondary)
                }
                else Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Other resume actions", "Autres actions de reprise"), tint = colors.TextSecondary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Column {
                            Text(tr("Unblock", "Débloquer"))
                            Text(tr("Back-off with blades stopped to a safe position", "Recul lames arrêtées vers une position sûre"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                        } }, onClick = { menu = false; onBackOff() }, enabled = resumeEnabled && !state.backOffPending)
                        DropdownMenuItem(text = { Column {
                            Text(tr("Resume further on", "Reprendre plus loin"))
                            Text(tr("Skip an obstacle or a difficult passage", "Sauter un obstacle ou un passage difficile"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                        } }, onClick = { menu = false; onOpenSkip() }, enabled = !busy && !state.backOffPending)
                        if (summary.detail != null) DropdownMenuItem(text = { Text(if (details) tr("Hide the details", "Masquer les détails") else tr("Stop details", "Détails de l'arrêt")) },
                            onClick = { menu = false; details = !details })
                    }
                }
            }
            Column(Modifier.padding(end = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
                Text("${CutPasses.progress(checkpoint.preview.cutHeights, checkpoint.heightMm)} · ${selectedZoneLine(map, checkpoint.preview.zoneIds)}" +
                    (if (checkpoint.rechargeCycles > 0) " · ${checkpoint.rechargeCycles} recharge(s)" else "") +
                    (if (checkpoint.resumes > 0) tr(" · ${checkpoint.resumes} resume(s)", " · ${checkpoint.resumes} reprise(s)") else "") +
                    (if (checkpoint.skippedMeters >= 0.05) tr(" · ${Format.num(checkpoint.skippedMeters, 1)} m skipped", " · ${Format.num(checkpoint.skippedMeters, 1)} m sautés") else ""),
                    style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details && summary.detail != null) InfoNote(summary.detail)
                if (state.autoResumeStatus.isNotBlank()) StatusNote(state.autoResumeStatus, NoteTone.INFO)
                if (resumeError.isNotBlank()) StatusNote(resumeError, NoteTone.DANGER)
                if (state.checkpointSaveError) StatusNote(tr("Failed to write the progress on the phone.", "Échec d'écriture de l'avancement sur le téléphone."), NoteTone.DANGER)
                if (state.backOffStatus.isNotBlank()) StatusNote(state.backOffStatus, when {
                    state.backOffPending -> NoteTone.INFO
                    state.backOffStatus.startsWith(tr("Robot backed off", "Robot reculé")) -> NoteTone.SUCCESS
                    else -> NoteTone.DANGER })
                if (skipOpen) {
                    if (skip == null) InfoNote(if (busy) tr("Preparing the resume route…", "Préparation du trajet de reprise…") else tr("Preview unavailable: see the message above.", "Aperçu indisponible : voir le message ci-dessus."))
                    else if (skip.maxMeters < 0.1) InfoNote(tr("Less than one meter of stripe left: nothing to skip.", "Moins d'un mètre de bande restant : rien à sauter."))
                    else {
                        SliderRow(tr("Resume further on", "Reprendre plus loin"), skipMeters, { onSkipMeters((Math.round(it * 10) / 10f)) }, 0f..skip.maxMeters.toFloat(),
                            valueLabel = "+ ${Format.num(skip.skipMeters, 1)} m")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            TextAction("− 1 m", { onSkipMeters((skipMeters - 1f).coerceAtLeast(0f)) }, enabled = skipMeters > 0f)
                            TextAction("+ 1 m", { onSkipMeters((skipMeters + 1f).coerceAtMost(skip.maxMeters.toFloat())) }, enabled = skipMeters < skip.maxMeters)
                        }
                        Text(if (skip.skipMeters > 0.0) tr("Tap a stripe to place the resume: the ${Format.num(skip.skipMeters, 1)} m in orange will not be mowed.", "Touchez une bande pour placer la reprise : les ${Format.num(skip.skipMeters, 1)} m en orange ne seront pas tondus.")
                            else tr("Set the distance or tap a stripe: the purple dot shows the resume.", "Réglez la distance ou touchez une bande : le point violet montre la reprise."),
                            style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                    }
                }
                if (!state.ready) Text(tr("Connect the robot to resume (30 cm overlap at the interrupted point).", "Connectez le robot pour reprendre (30 cm de recouvrement au point interrompu)."),
                    style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                else if (state.motorWatchdog?.demonstrated != true)
                    Text(tr("Stay nearby during the resume.", "Restez à proximité pendant la reprise."), style = MaterialTheme.typography.labelSmall, color = colors.Warning)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    TextAction(tr("Discard", "Abandonner"), onDiscard, enabled = !busy && !state.backOffPending)
                    Spacer(Modifier.weight(1f))
                    if (state.backOffPending) DangerPillButton("STOP", onStopBackOff)
                    else PrimaryPillButton(if (busy) tr("Preparing…", "Préparation…") else if (skipOpen && skipMeters > 0f) tr("Resume at + ${Format.num(skipMeters.toDouble(), 1)} m", "Reprendre à + ${Format.num(skipMeters.toDouble(), 1)} m") else tr("Resume", "Reprendre"),
                        onResume, icon = Icons.Rounded.PlayArrow, enabled = resumeEnabled && (!skipOpen || skip != null))
                }
            }
        }
    }
}
