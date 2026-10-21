package fr.luba.poc.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.planning.*
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/*
 * Sheets and dialogs opened from the map ([MapScreen]): each one a named composable reading its states from the map
 * state holders ([MapSheets], [MapLayersState]…), outside the MapScreen method and its Dalvik register limit.
 */

/** "Signals and terrain" / "Local mowing" / "Simulated route" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapSignalsSheet(
    sheets: MapSheets, layers: MapLayersState, state: RobotUi, sim: SimulationUi, simulation: SimulationSession,
    signalView: SignalReliefView, samples: List<SignalSample>, simulatedSurvey: Boolean, surveyMatches: Boolean,
    demo: Boolean, cloud: Boolean, cuttingHeight: Int,
    onStartRobotRoute: (MowingSimulation, Boolean, Int) -> Boolean, onTrajectorySpeed: (Int) -> Unit, onStop: () -> Unit,
) {
    if (!sheets.signalPanel) return
    val colors = MammotionColors
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { sheets.signalPanel = false }, containerColor = colors.Background) {
        val heat = surveyMatches && samples.isNotEmpty()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Text(if (heat) tr("Signals and terrain", "Signaux et relief") else if (state.robotRoute.started) tr("Local mowing", "Tonte locale") else tr("Simulated route", "Trajet simulé"), style = MaterialTheme.typography.headlineSmall)
            if (heat) SettingsGroup("${if (simulatedSurvey) tr("Simulated heatmap", "Heatmap simulée") else tr("Measured heatmap", "Heatmap mesurée")} · ${tr("${samples.size} readings", "${samples.size} relevés")}") {
                SignalReliefControls(signalView, samples, onSignal = { layers.surveySignal = it; layers.showSignalHeat = true },
                    onShading = { layers.reliefShading = it }, mount = if (simulatedSurvey) null else layers.reliefMount,
                    onMount = { layers.reliefMount = it; ReliefSettings.saveMount(context,it) }, on3d = { sheets.relief3dOpen = true },
                    hidden = !layers.showSignalHeat, onHide = { layers.showSignalHeat = false },
                    calibration = layers.tiltCalibration, onCalibrate = if (simulatedSurvey) null else ({ sheets.signalPanel = false; sheets.calibrationOpen = true }))
                InfoNote(if (simulatedSurvey) tr("Fictional signals and terrain generated during the simulation.", "Signaux et terrain fictifs générés pendant la simulation.")
                    else tr("Robot readings on the driven cells. Relative terrain (no absolute altitude), deduced from the slopes of the phone mounted on the robot; positions less accurate without Fix.", "Mesures du robot sur les cellules parcourues. Relief relatif (sans altitude absolue), déduit des pentes du téléphone posé sur le robot ; positions moins précises sans Fix."))
                if (simulatedSurvey && sim.error.startsWith(tr("Heatmap save", "Sauvegarde heatmap"))) StatusNote(sim.error, NoteTone.DANGER)
                if (!simulatedSurvey && state.surveySaveError) StatusNote(tr("Could not save the survey", "Sauvegarde du relevé impossible"), NoteTone.DANGER)
                NavRow(tr("Saved heatmaps", "Heatmaps enregistrées"), icon = Icons.Rounded.WifiTethering, onClick = { simulation.pause(); sheets.signalPanel = false; sheets.signalHistoryOpen = true })
            }
            if (state.robotRoute.started) SettingsGroup(tr("Local mowing", "Tonte locale")) {
                KeyValueRow(tr("State", "État"), state.robotRoute.status)
                KeyValueRow(tr("Height", "Hauteur"), "${state.robotRoute.heightMm} mm")
                KeyValueRow(tr("Recharge", "Recharge"), tr("return ${state.robotRoute.returnPercent} % · resume ${state.robotRoute.resumePercent} %", "retour ${state.robotRoute.returnPercent} % · reprise ${state.robotRoute.resumePercent} %"))
                KeyValueRow(tr("Recharges", "Recharges"), rechargeLine(state))
                if (state.robotRoute.running) SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
                    valueRange = 10f..100f, valueLabel = "${state.trajectorySpeedPercent} %")
                if (state.robotRoute.running) DangerPillButton(tr("Stop", "Arrêter"), onStop, Modifier.fillMaxWidth())
            }
            // The simulated route can be handed to the real robot (same route, same checks).
            if (sim.engine != null && sim.engine?.preview?.localOnly != true && !demo && !cloud && !state.robotRoute.running) SettingsGroup(tr("Run this route", "Exécuter ce trajet")) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    SecondaryPillButton(tr("Follow, blades stopped", "Suivre, lames arrêtées"), { simulation.pause(); onStartRobotRoute(sim.engine!!, false, sim.engine!!.preview.cutHeights.firstOrNull() ?: cuttingHeight) }, Modifier.weight(1f), enabled = state.ready)
                    if (sim.engine?.preview?.mode?.isSurvey != true)
                        PrimaryPillButton(tr("Mow", "Tondre"), { simulation.pause(); onStartRobotRoute(sim.engine!!, true, sim.engine!!.preview.cutHeights.firstOrNull() ?: cuttingHeight) }, Modifier.weight(1f), enabled = state.ready, icon = Icons.Rounded.PlayArrow)
                }
                if (!state.ready) InfoNote(tr("Connect the robot to run this route.", "Connectez le robot pour exécuter ce trajet."))
            }
        }
    }
}

/** Encountered obstacles, task history and device status sheets. */
@Composable
internal fun MapInfoSheets(
    sheets: MapSheets, state: RobotUi, telemetry: fr.luba.poc.protocol.Telemetry, source: MapSource, showingRobotRoute: Boolean,
    marker: DisplayedRobot?, known: LastKnownPosition?,
    onOpenTask: (String) -> Unit, onOpenAllTasks: () -> Unit, onOpenRobot: () -> Unit,
) {
    EncounterSheet(state.difficulties, state.obstaclePhoto.photos, state.obstacles)
    if (sheets.taskHistory) fr.luba.poc.history.TaskHistorySheet(state.mowingHistory, state.mowingRecord,
        onOpen = { sheets.taskHistory = false; onOpenTask(it) }, onOpenAll = { sheets.taskHistory = false; onOpenAllTasks() }, onDismiss = { sheets.taskHistory = false })
    if (sheets.statusPanel) DeviceStatusSheet(state, telemetry,
        context = if (showingRobotRoute) tr("Local mowing · ${state.robotRoute.index}/${state.robotRoute.count}", "Tonte locale · ${state.robotRoute.index}/${state.robotRoute.count}")
            else source.label,
        marker = marker, known = known,
        onOpenRobot = { sheets.statusPanel = false; onOpenRobot() }, onDismiss = { sheets.statusPanel = false })
}

/** Terrain dialogs: tilt reference, saved heatmaps, 3D view. */
@Composable
internal fun MapTerrainDialogs(
    sheets: MapSheets, layers: MapLayersState, relief: ReliefResult?, simulatedSurvey: Boolean,
    store: SignalSurveyStore, map: RobotMap, reliefMap: RobotMap, signalView: SignalReliefView, samples: List<SignalSample>,
    currentSurveySession: String?,
) {
    val context = LocalContext.current
    if (sheets.calibrationOpen) TiltCalibrationDialog(
        // Current orientation: that of the displayed real survey if known, otherwise the manual choice.
        orientation = relief?.mount?.takeIf { !simulatedSurvey && it.source != MountSource.DEFAULT }
            ?: layers.reliefMount.angle?.let { MountEstimate(it, MountSource.MANUAL) }
            ?: layers.tiltCalibration?.angle?.let { MountEstimate(it, MountSource.CALIBRATED) },
        calibration = layers.tiltCalibration,
        onSaved = { c -> ReliefSettings.saveCalibration(context,c); layers.tiltCalibration = c
            if (c.angle != null) { layers.reliefMount = PhoneMount.CALIBRATED; ReliefSettings.saveMount(context,PhoneMount.CALIBRATED) } },
        onCleared = { ReliefSettings.clearCalibration(context); layers.tiltCalibration = null
            if (layers.reliefMount == PhoneMount.CALIBRATED) { layers.reliefMount = PhoneMount.AUTO; ReliefSettings.saveMount(context,PhoneMount.AUTO) } },
        onDismiss = { sheets.calibrationOpen = false })
    if (sheets.signalHistoryOpen) SignalHistoryDialog(store,map,layers.reliefMount,layers.tiltCalibration,onMount={ layers.reliefMount=it; ReliefSettings.saveMount(context,it) },onDismiss={ sheets.signalHistoryOpen=false },
        onShowOnMap={ survey -> SignalOverlay.show(survey.takeIf { it.sessionId!=currentSurveySession }); sheets.signalHistoryOpen=false })
    if (sheets.relief3dOpen) Relief3DDialog(signalView,reliefMap,
        signalReliefLegend(signalView,samples),onDismiss={ sheets.relief3dOpen=false })
}

/** "Map" sheet (source, layers, map reading, demo, corrections, driven recording) and the corrections editor. */
@Composable
internal fun MapSettingsDialogs(
    sheets: MapSheets, layers: MapLayersState, state: RobotUi, map: RobotMap, source: MapSource,
    signalView: SignalReliefView, samples: List<SignalSample>, surveyMatches: Boolean, simulatedSurvey: Boolean, simulating: Boolean, demo: Boolean, cloud: Boolean, prep: RoutePreparationState,
    onToggleDemo: () -> Unit, onFetchMap: () -> Unit, onOpenAccount: () -> Unit, onOpenDrivenRecord: () -> Unit,
    onSaveCorrections: (List<MapCorrection>) -> Boolean,
) {
    if (sheets.mapActions) MapSettingsSheet(
        source = source,
        summary = mapSummary(map),
        heat = MapHeatChoice(if (surveyMatches && samples.isNotEmpty())
            availableLayers(samples, signalView.relief != null, layers.surveySignal) else emptyList(),
            layers.surveySignal, layers.showSignalHeat, layers.layersOpen, simulatedSurvey),
        mapLoading = state.mapLoading,
        fetchEnabled = mapFetchAllowed(state, simulating),
        accountEnabled = !simulating,
        demoEnabled = !prep.computing && !state.robotRoute.running && !state.mowing.active && !simulating,
        onSignal = { layers.surveySignal = it; layers.showSignalHeat = true },
        onHeatVisible = { layers.showSignalHeat = it },
        onLayerBar = { layers.layersOpen = it },
        onFetch = { sheets.mapActions = false; onFetchMap() },
        onAccount = { sheets.mapActions = false; onOpenAccount() },
        onToggleDemo = { onToggleDemo(); sheets.mapActions = false },
        onHistory = { sheets.mapActions = false; sheets.signalHistoryOpen = true },
        onCalibration = { sheets.mapActions = false; sheets.calibrationOpen = true },
        onDismiss = { sheets.mapActions = false },
        corrections = state.mapCorrections.count { it.enabled },
        correctionsEnabled = !demo && !simulating && !state.robotRoute.running && !state.mowing.active && state.mammotionMap.complete,
        onCorrections = { sheets.mapActions = false; sheets.correcting = true },
        recordEnabled = state.ready && !demo && !cloud && !simulating && !state.robotRoute.running && !state.mowing.active && state.mammotionMap.complete,
        onRecord = { sheets.mapActions = false; onOpenDrivenRecord() })
    if (sheets.correcting) androidx.compose.ui.window.Dialog(onDismissRequest = { sheets.correcting = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = false, dismissOnClickOutside = false)) {
        MapCorrectionEditor(state.mammotionMap, state.mapCorrections, onSaveCorrections, onClose = { sheets.correcting = false })
    }
}

/** Simulation settings and controls of the prepared route. */
@Composable
internal fun MapSimulationDialog(
    sheets: MapSheets, form: MowingTaskForm, sim: SimulationUi, simulation: SimulationSession, preview: Preview?, base: MapBase?,
    map: RobotMap, selected: Set<Long>, onSelection: (Set<Long>) -> Unit, demo: Boolean, zoneSelectionAllowed: Boolean,
    prep: RoutePreparationState, routeRunning: Boolean, onStart: () -> Unit,
) {
    if (sheets.simulationDialog) SimulationDialog(
        ui = sim, preview = preview, base = base, map = map, selected = selected, manualBase = demo,
        onSelection = { if (zoneSelectionAllowed) onSelection(it) }, zoneSelectionAllowed = zoneSelectionAllowed,
        initialProgress = form.initialProgress, onProgress = { form.initialProgress = it },
        battery = form.initialBattery, onBattery = { form.initialBattery = it }, autonomy = form.autonomy, onAutonomy = { form.autonomy = it },
        chargeTime = form.chargeTime, onChargeTime = { form.chargeTime = it }, building = prep.simBuilding,
        onSpeed = simulation::speed, onToggle = { if (!routeRunning) simulation.toggle() },
        onClose = { sheets.simulationDialog = false }, onClear = { if (!routeRunning) { simulation.clear(); sheets.simulationDialog = false; sheets.selectingZones = true } },
        onPreview = { sheets.simulationDialog = false; form.mowingStep = 1; sheets.mowingDialog = true },
        onStart = onStart, chargeLow = form.chargeLow, chargeHigh = form.chargeHigh,
    )
}

/** Discard of the interrupted task, and the charging station (local route, or native return through the account). */
@Composable
internal fun MapStationDialogs(
    sheets: MapSheets, actions: MapActionState, prep: RoutePreparationState, state: RobotUi, cloudStation: Boolean,
    cloudReturn: fr.luba.poc.auth.CloudReturnUi?, cloudRobotName: String?, onCloudReturn: () -> Unit, onCancelCloudReturn: () -> Unit,
    stationEnabled: Boolean, onBaseAction: (Boolean) -> Unit, onStopMammotionTask: () -> Unit, onDiscardMissionCheckpoint: () -> Unit,
) {
    if (sheets.discardConfirm) LubaDialog(onDismissRequest = { sheets.discardConfirm = false }, title = tr("Discard the interrupted task?", "Abandonner la tâche interrompue ?"), content = {
        Text(tr("The progress is cleared: the next task will start from the beginning.", "L'avancement est effacé : la prochaine tâche repartira du début."), style = MaterialTheme.typography.bodyMedium)
    }, footer = {
        DangerPillButton(tr("Discard", "Abandonner"), { sheets.discardConfirm = false; actions.resumeError = ""; onDiscardMissionCheckpoint() }, Modifier.fillMaxWidth())
    })

    if (sheets.baseDialog && cloudStation) CloudStationDialog(cloudReturn!!, cloudRobotName, onCloudReturn, onCancelCloudReturn) { sheets.baseDialog = false }
    if (sheets.baseDialog && !cloudStation) StationDialog(state, actions.baseBusy, actions.baseSupervised, { actions.baseSupervised = it },
        error = prep.error.takeIf { it.startsWith("Base") },
        leaveEnabled = actions.baseSupervised && stationEnabled && !actions.baseBusy,
        rechargeEnabled = actions.baseSupervised && stationEnabled && !actions.baseBusy,
        onLeave = { onBaseAction(true) }, onRecharge = { onBaseAction(false) },
        onStopMammotionTask = onStopMammotionTask, onDismiss = { sheets.baseDialog = false })
}

/** "Device status" sheet opened from the status pill at the top of the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceStatusSheet(
    state: RobotUi, t: fr.luba.poc.protocol.Telemetry, context: String, marker: DisplayedRobot?, known: LastKnownPosition?,
    onOpenRobot: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = MammotionColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.Background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Device status", "État de l'appareil"), style = MaterialTheme.typography.headlineSmall)
                Text(context, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
            }
            SettingsGroup(tr("Connection", "Connexion")) {
                KeyValueRow("Bluetooth", state.connection.label)
                KeyValueRow(tr("Bluetooth signal", "Signal Bluetooth"), state.rssi?.let { "$it dBm" } ?: "—")
                KeyValueRow(tr("Last telemetry", "Dernière télémétrie"), state.telemetry.lastReportAt?.let(Format::time) ?: tr("never received", "jamais reçue"))
                NavRow(tr("Manage the connection", "Gérer la connexion"), icon = Icons.Rounded.Bluetooth, onClick = onOpenRobot)
            }
            SettingsGroup(tr("Positioning and battery", "Positionnement et batterie")) {
                val quality = positionQuality(t.rtkStatus)
                KeyValueRow(tr("Positioning", "Positionnement"), quality.label, valueColor = if (quality == PositionQuality.FIX) colors.GreenDeep else colors.Warning)
                KeyValueRow(tr("Last RTK state", "Dernier état RTK"), t.lastRtkAt?.let(Format::time) ?: "—")
                KeyValueRow(tr("Battery", "Batterie"), t.battery?.let { "$it %" } ?: "—")
                marker?.let { robot -> KeyValueRow(tr("Displayed position", "Position affichée"), "${robot.source} · ${if (robot.fresh) tr("recent", "récente") else tr("last known", "dernière connue")}") }
                InfoNote(if (quality == PositionQuality.FIX) tr("Accurate RTK position: automatic mowing is possible.", "Position RTK précise : la tonte automatique est possible.")
                    else tr("Only a Fix positioning allows accurate automatic mowing.", "Seul un positionnement Fix permet une tonte automatique précise."))
            }
            known?.let { k ->
                fun time(at: Long?) = at?.let { java.text.SimpleDateFormat(tr("MM-dd HH:mm:ss", "dd/MM HH:mm:ss"), fr.luba.poc.i18n.I18n.locale).format(java.util.Date(it)) } ?: "—"
                SettingsGroup(tr("Last known position", "Dernière position connue")) {
                    KeyValueRow("Robot", k.robotName)
                    KeyValueRow("Communication", time(k.communicationAt))
                    KeyValueRow(tr("Fix position", "Position Fix"), k.last?.let { p -> time(p.at) } ?: tr("none saved", "aucune enregistrée"))
                    if (state.positionSaveError) StatusNote(tr("Local save failed: position kept in memory", "Échec de sauvegarde locale : position conservée en mémoire"), NoteTone.DANGER)
                }
            }
            SettingsGroup(tr("Technical details", "Détails techniques")) {
                KeyValueRow(tr("Robot state (code)", "État robot (code)"), t.robotState?.toString() ?: "—")
                KeyValueRow(tr("RTK state / quality", "RTK état / qualité"), "${t.rtkStatus ?: "—"} / ${t.positioningLevel ?: "—"}")
                KeyValueRow(tr("Position type", "Type de position"), t.positionType?.toString() ?: "—")
                KeyValueRow(tr("Local position", "Position locale"), if (t.x != null && t.y != null) "${Format.num(t.x, 2)} / ${Format.num(t.y, 2)} m" else "—")
            }
        }
    }
}

/** Map summary for the Map sheet: zones, no-go zones and passages. */
private fun mapSummary(map: RobotMap): String {
    val zones = map.shapes.count { it.kind == ShapeKind.ZONE }
    val noGo = map.shapes.count { it.kind == ShapeKind.NO_GO }
    return listOfNotNull(Format.plural(zones, "zone", "zones"),
        noGo.takeIf { it > 0 }?.let { Format.plural(it, tr("no-go zone", "zone interdite"), tr("no-go zones", "zones interdites")) }).joinToString(" · ")
}

/** "Charging station" dialog: supervision, states and actions. */
@Composable
private fun StationDialog(
    state: RobotUi, busy: Boolean,
    supervised: Boolean, onSupervised: (Boolean) -> Unit, error: String?,
    leaveEnabled: Boolean, rechargeEnabled: Boolean, onLeave: () -> Unit, onRecharge: () -> Unit,
    onStopMammotionTask: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = MammotionColors
    val nativeReturning = state.nativeReturnActive || state.telemetry.robotState == 14
    // Charging contact of the BLE report (continuous, stale after 15 s): leave only from the base, recharge only away from it.
    rememberWallClock()
    val presence = if (state.ready) StationPresence.of(state.telemetry, BLE_CHARGE_MAX_AGE_MS, System.currentTimeMillis()) else StationPresence.UNKNOWN
    LubaDialog(onDismissRequest = onDismiss, title = tr("Charging station", "Station de charge"), content = {
        if (presence == StationPresence.ON_BASE) InfoNote(tr("Robot on its base. Leave the station: straight exit driving forward, slowly, " +
            "blades stopped.", "Robot sur sa base. Quitter la station : sortie en marche avant, en ligne droite et lentement, lames arrêtées."))
        else InfoNote(tr("Recharge: DumbyLuba drives the robot in front of the base along the verified local route (50 cm from the edges, blades " +
            "stopped), then the official Mammotion return handles the docking.",
            "Recharger : DumbyLuba conduit le robot devant la base par le trajet local vérifié (50 cm des bords, lames " +
            "arrêtées), puis le retour officiel Mammotion assure l'accostage."))
        SafetyNotice(tr("Stay nearby, stop within reach.", "Restez à proximité, arrêt accessible."))
        LubaCheckRow(if (presence == StationPresence.ON_BASE) tr("I am supervising the robot and the exit in front of the station is clear",
            "Je supervise le robot et la sortie devant la station est dégagée")
            else tr("I am supervising the robot and the way to the station is clear", "Je supervise le robot et l'accès à la station est dégagé"), supervised, onSupervised)
        val lines = listOfNotNull(tr("Preparing the station route…", "Préparation du trajet de station…").takeIf { busy },
            state.robotRoute.status.takeIf { state.robotRoute.started },
            state.nativeReturnStatus.takeIf { it.isNotBlank() },
            state.taskStopStatus.takeIf { it.isNotBlank() })
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary) }
        if (error != null) StatusNote(error, NoteTone.DANGER)
    }, footer = {
        if (nativeReturning) DangerPillButton(tr("Stop the Mammotion return", "Arrêter le retour Mammotion"), onStopMammotionTask, Modifier.fillMaxWidth(),
            enabled = state.ready && !state.taskStopPending)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            if (presence.canLeave) SecondaryPillButton(tr("Leave the station", "Quitter la station"), onLeave, Modifier.weight(1f),
                enabled = leaveEnabled && !nativeReturning)
            if (presence.canRecharge) PrimaryPillButton(tr("Recharge", "Recharger"), onRecharge, Modifier.weight(1f), icon = Icons.Rounded.EvStation,
                enabled = rechargeEnabled && !nativeReturning)
        }
    })
}
