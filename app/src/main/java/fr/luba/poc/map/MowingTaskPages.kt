package fr.luba.poc.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.planning.*
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Task pages opened from the map ([MapScreen]): mowing in progress, task settings (zones, mode, heights, speed,
 * advanced settings) and route preview (start point, check, simulate or start). States read from the map holders
 * ([MowingTaskForm], [RoutePreparationState], [PreviewStartState]…).
 */
@Composable
internal fun MowingTaskPages(
    sheets: MapSheets, form: MowingTaskForm, prep: RoutePreparationState, state: RobotUi, map: RobotMap, planningMap: RobotMap,
    preview: Preview?, selected: Set<Long>, localObstacles: List<LocalObstacle>, demo: Boolean, cloud: Boolean,
    passHeights: List<Int>, cuttingHeight: Int, start: PreviewStartState, zoneStarts: ZoneStartsState,
    prepared: Boolean, simulateEnabled: Boolean,
    onCompute: () -> Unit, onSimulate: () -> Unit, onStartRobot: () -> Unit, onStop: () -> Unit, onTrajectorySpeed: (Int) -> Unit,
) {
    if (!sheets.mowingDialog) return
    val colors = MammotionColors
    val cutHeights = state.cutHeights
    val back = { if (!prep.computing && !prep.simBuilding) { if (form.mowingStep == 2 && !state.robotRoute.running && !state.mowing.active) { form.mowingStep = 1; prep.estimate = null } else sheets.mowingDialog = false } }
    androidx.activity.compose.BackHandler(onBack = back)
    val zoneLine = selectedZoneLine(map, (preview?.zoneIds?.takeIf { it.isNotEmpty() } ?: selected.toList()))
    when {
        state.robotRoute.running || state.mowing.active -> PushedPage(if (state.robotRoute.running) tr("Mowing in progress", "Tonte en cours") else tr("Mammotion task active", "Tâche Mammotion active"), onBack = { sheets.mowingDialog = false },
            bottomBar = { DangerPillButton("STOP", onStop, Modifier.fillMaxWidth()) }) {
            if (state.robotRoute.running) {
                SettingsGroup(tr("Progress", "Avancement")) {
                    val live = fr.luba.poc.ui.MowingLiveStats.of(state.telemetry, state.robotRoute)
                    Text(state.robotRoute.status, style = MaterialTheme.typography.titleMedium)
                    live.fraction?.let { done ->
                        Text(live.progress.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.GreenDeep)
                        LubaProgress(done, Modifier.fillMaxWidth())
                    }
                    if (state.robotRoute.cutProgress != null) InfoNote(tr("Share of cutting stripes mowed, excluding travel with blades stopped.", "Part des bandes de coupe tondues, sans les déplacements lames arrêtées."))
                    KeyValueRow(tr("Blades", "Lames"), live.blades)
                    live.height?.let { KeyValueRow(tr("Reported height", "Hauteur rapportée"), it.removePrefix(tr("Height ", "Hauteur "))) }
                }
                SettingsGroup(tr("Settings", "Réglages")) {
                    KeyValueRow(tr("Cutting height", "Hauteur de coupe"), CutPasses.progress(state.robotRoute.cutHeights, state.robotRoute.heightMm))
                    KeyValueRow(tr("Recharge", "Recharge"), tr("return at ${state.robotRoute.returnPercent} % · resume at ${state.robotRoute.resumePercent} %", "retour à ${state.robotRoute.returnPercent} % · reprise à ${state.robotRoute.resumePercent} %"))
                    KeyValueRow(tr("Battery", "Batterie"), state.telemetry.battery?.let { "$it %" } ?: tr("unknown", "inconnue"))
                    KeyValueRow(tr("Recharges", "Recharges"), rechargeLine(state))
                    state.mowingRecord?.let { r ->
                        KeyValueRow(tr("Duration", "Durée"), tr("${fr.luba.poc.history.MowingText.duration(r.totals.totalSeconds)} / ≈ ${fr.luba.poc.history.MowingText.duration(r.forecast.totalSeconds)} estimated", "${fr.luba.poc.history.MowingText.duration(r.totals.totalSeconds)} / ≈ ${fr.luba.poc.history.MowingText.duration(r.forecast.totalSeconds)} estimée"))
                    }
                    SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
                        valueRange = 10f..100f, valueLabel = "${state.trajectorySpeedPercent} %")
                }
            } else StatusNote(tr("A robot activity is still reported. Stop it before preparing a new task.", "Une activité robot est encore signalée. Arrêtez-la avant de préparer une nouvelle tâche."), NoteTone.WARNING)
        }
        form.mowingStep < 2 -> PushedPage(if (sheets.surveyTask) tr("Survey route", "Parcours de relevé") else tr("Task settings", "Paramètres de la tâche"), onBack = back,
            bottomBar = {
                val area = CoveragePlanner.selectedArea(map, selected)
                val rough = if (area > 0 && !form.mode.isSurvey) realEstimate(state, demo, area / form.spacing.coerceAtLeast(0.05f) * passHeights.size,
                    form.mowingSpeed, form.autonomy, form.chargeTime, form.initialBattery, form.chargeLow, form.chargeHigh) else null
                Text("${Format.num(area, 0)} m²${rough?.let { tr(" · ≈ ${durationText(it.totalSeconds)} (estimate before computation)", " · ≈ ${durationText(it.totalSeconds)} (estimation avant calcul)") } ?: ""}",
                    style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PrimaryPillButton(if (prep.computing) tr("Computing…", "Calcul…") else tr("Route preview", "Aperçu du trajet"), { onCompute(); form.mowingStep = 2 },
                    Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && map.complete && !prep.computing, icon = Icons.Rounded.Route)
            }) {
            ZoneOrderGroup(selected.size, zoneLine, onEdit = { sheets.mowingDialog = false; sheets.selectingZones = true }, editEnabled = !prep.computing)
            // Mowing beyond the Mammotion boundaries (DumbyLuba zones, enlargements): recalled before every task.
            val beyond = remember(map, selected) { MapCorrections.beyondMammotion(map, selected) }
            if (beyond >= 0.05) StatusNote(tr("Includes ${MapCorrections.squareMeters(beyond)} beyond the Mammotion boundaries (DumbyLuba zones, enlargements; hatched on the map): this surface only exists on this phone and is mowed under your responsibility.",
                "Dont ${MapCorrections.squareMeters(beyond)} hors des contours Mammotion (zones DumbyLuba, agrandissements ; hachurés sur la carte) : cette surface n'existe que sur ce téléphone et est tondue sous votre responsabilité."), NoteTone.WARNING)
            // The signal survey is no longer a mowing mode: it is automatic while moving, and the dedicated route opens
            // from Service → Signal surveys.
            SettingsGroup(if (sheets.surveyTask) tr("Signal survey route", "Parcours de relevé des signaux") else tr("Route mode", "Mode de trajet")) {
                // Five modes on two rows of three equal tiles (the second row padded): a row of five cut the labels on a
                // folded Fold.
                if (!sheets.surveyTask) Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        ChoiceTile(Icons.Rounded.Reorder, "Zigzag", form.mode == CoverageMode.LINES, { form.mode = CoverageMode.LINES }, Modifier.weight(1f), enabled = !prep.computing)
                        ChoiceTile(Icons.Rounded.AutoAwesome, tr("Optimized", "Optimisé"), form.mode == CoverageMode.OPTIMIZED, { form.mode = CoverageMode.OPTIMIZED }, Modifier.weight(1f), enabled = !prep.computing)
                        ChoiceTile(Icons.Rounded.GridOn, tr("Checkerboard", "Damier"), form.mode == CoverageMode.CHECKERBOARD, { form.mode = CoverageMode.CHECKERBOARD }, Modifier.weight(1f), enabled = !prep.computing)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        ChoiceTile(Icons.Rounded.Waves, "Zamboni", form.mode == CoverageMode.ZAMBONI, { form.mode = CoverageMode.ZAMBONI }, Modifier.weight(1f), enabled = !prep.computing)
                        ChoiceTile(Icons.Rounded.TrackChanges, tr("Spiral", "Spirale"), form.mode == CoverageMode.SPIRAL, { form.mode = CoverageMode.SPIRAL }, Modifier.weight(1f), enabled = !prep.computing)
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (form.mode == CoverageMode.OPTIMIZED)
                    InfoNote(tr("Zigzag fitted to the shape: each part takes the direction needing the fewest U-turns.", "Zigzag adapté à la forme : chaque partie prend la direction qui demande le moins de demi-tours."))
                if (form.mode == CoverageMode.ZAMBONI)
                    InfoNote(zamboniNote())
                if (form.mode == CoverageMode.SPIRAL) {
                    // Direction of the spiral, kept while another mode is chosen.
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        Text(tr("Spiral direction", "Sens de la spirale"), style = MaterialTheme.typography.bodyLarge)
                        Segmented(SpiralDirection.entries.map { it to it.label }, form.spiralDirection, { form.spiralDirection = it }, enabled = !prep.computing)
                    }
                    InfoNote(spiralNote(form.spiralDirection))
                }
                if (form.mode.isSurvey) {
                    Segmented(listOf(CoverageMode.TOUR to tr("Full loop", "Grand tour"), CoverageMode.SURVEY_GRID to tr("Grid", "Quadrillage")), form.mode, { form.mode = it }, enabled = !prep.computing)
                    InfoNote(if (form.mode == CoverageMode.TOUR) tr("Blades stopped. Adjacent zones merged: only the shared outer boundary is driven.", "Lames arrêtées. Zones adjacentes fusionnées : seul le contour extérieur commun est parcouru.")
                        else tr("Blades stopped. Merged boundary and crossed grid: the inside of the zones is measured too.", "Lames arrêtées. Contour fusionné et quadrillage croisé : l'intérieur des zones est aussi mesuré."))
                    if (form.mode == CoverageMode.SURVEY_GRID) SliderRow(tr("Grid spacing", "Maille du quadrillage"), form.signalGridSpacing, { form.signalGridSpacing = it.roundToInt().toFloat() }, 1f..10f,
                        valueLabel = "${form.signalGridSpacing.toInt()} m", steps = 8, enabled = !prep.computing)
                    SurveyOffsetSetting.Slider(!prep.computing && !demo)
                    SliderRow(tr("Survey speed", "Vitesse du relevé"), form.mowingSpeed, { form.mowingSpeed = it }, 0.2f..0.6f,
                        valueLabel = "${Format.num(form.mowingSpeed.toDouble(), 2)} m/s", enabled = !prep.computing)
                }
            }
            if (!form.mode.isSurvey) {
                OrientationGroup(form, planningMap, selected, enabled = !prep.computing)
                SettingsGroup(tr("Mowing", "Tonte")) {
                    SliderRow(tr("Cutting height", "Hauteur de coupe"), cuttingHeight.toFloat(), { form.mowingHeight = it }, cutHeights.floatRange(),
                        valueLabel = "$cuttingHeight mm", steps = cutHeights.sliderSteps, enabled = !prep.computing)
                    // Tall grass: several passes lower and lower, the last one at the cutting height.
                    val maxPasses = CutPasses.maxPasses(cuttingHeight, cutHeights)
                    SliderRow(tr("Cutting passes", "Passes de hauteur"), passHeights.size.toFloat(), { form.heightPasses = it.roundToInt() }, 1f..CutPasses.MAX_PASSES.toFloat(),
                        valueLabel = if (passHeights.size == 1) tr("1 pass", "1 passe") else "${passHeights.size} passes", steps = CutPasses.MAX_PASSES - 2,
                        enabled = !prep.computing && maxPasses > 1)
                    if (passHeights.size > 1) {
                        val firstRange = CutPasses.firstRange(cuttingHeight, passHeights.size, cutHeights)
                        if (firstRange.first < firstRange.last)
                            SliderRow(tr("First pass height", "Hauteur de la première passe"), passHeights.first().toFloat(), { form.firstPassHeight = it.roundToInt().toFloat() },
                                firstRange.first.toFloat()..firstRange.last.toFloat(), valueLabel = "${passHeights.first()} mm",
                                steps = firstRange.last - firstRange.first - 1, enabled = !prep.computing)
                        InfoNote(tr("Tall grass: ${CutPasses.label(passHeights)}. Every other pass is driven in reverse; " +
                            "duration multiplied by ${passHeights.size}.",
                            "Herbe haute : ${CutPasses.label(passHeights)}. Une passe sur deux est parcourue en sens inverse ; " +
                            "durée multipliée par ${passHeights.size}."))
                    } else if (maxPasses == 1 && form.heightPasses > 1)
                        InfoNote(tr("Cutting height too high for several passes (at least 5 mm apart, ${cutHeights.maxMm} mm at most).", "Hauteur de coupe trop haute pour plusieurs passes (5 mm d'écart au moins, ${cutHeights.maxMm} mm au plus)."))
                    SliderRow(tr("Task speed", "Vitesse de la tâche"), form.mowingSpeed, { form.mowingSpeed = it }, 0.2f..0.6f,
                        valueLabel = "${Format.num(form.mowingSpeed.toDouble(), 2)} m/s", enabled = !prep.computing)
                    // Perimeter offset (5 cm to 1 m) with the height and the speed; settings kept.
                    OuterOffsetSetting.Slider(!prep.computing && form.borderPasses > 0)
                }
                WorkWindowGroup(enabled = !prep.computing)
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().clickable { sheets.advancedOpen = !sheets.advancedOpen }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        Text(tr("Advanced settings", "Réglages avancés"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(tr("${(form.spacing * 100).roundToInt()} cm · ${form.borderPasses} + ${form.noGoPasses} loops", "${(form.spacing * 100).roundToInt()} cm · ${form.borderPasses} + ${form.noGoPasses} tours"), style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                        Icon(if (sheets.advancedOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.TextSecondary)
                    }
                    if (sheets.advancedOpen) {
                        SliderRow(tr("Pass spacing", "Espacement des passes"), form.spacing, { form.spacing = (it * 100).roundToInt() / 100f }, 0.05f..0.35f,
                            valueLabel = "${(form.spacing * 100).roundToInt()} cm", steps = 29, enabled = !prep.computing)
                        SliderRow(tr("Perimeter loops", "Tours du périmètre"), form.borderPasses.toFloat(), { form.borderPasses = it.roundToInt() }, 0f..4f,
                            valueLabel = "${form.borderPasses}", steps = 3, enabled = !prep.computing)
                        SliderRow(tr("Loops around no-go zones", "Tours autour des zones interdites"), form.noGoPasses.toFloat(), { form.noGoPasses = it.roundToInt() }, 0f..4f,
                            valueLabel = "${form.noGoPasses}", steps = 3, enabled = !prep.computing)
                        ToggleRow(tr("Perimeter first", "Périmètre d'abord"), form.bordersFirst, { form.bordersFirst = it },
                            subtitle = tr("Mow the borders before the inside of the zones.", "Tondre les bordures avant l'intérieur des zones."),
                            enabled = !prep.computing && form.borders && form.borderPasses + form.noGoPasses > 0)
                        // Zamboni: always on (its order is built for duration plus wear); Spiral: off (no U-turns between its
                        // rings). The saved choice is kept for the other modes.
                        val zamboni = form.mode == CoverageMode.ZAMBONI
                        val spiral = form.mode == CoverageMode.SPIRAL
                        ToggleRow(TurnStyle.LAWN.label, zamboni || !spiral && form.turnStyle == TurnStyle.LAWN, { form.turnStyle = if (it) TurnStyle.LAWN else TurnStyle.FAST },
                            subtitle = if (zamboni) zamboniLawnNote() else if (spiral) spiralLawnNote() else if (form.turnStyle == TurnStyle.LAWN) TurnStyle.LAWN.hint else TurnStyle.FAST.hint,
                            enabled = !prep.computing && !form.mode.isSurvey && !zamboni && !spiral)
                        ToggleRow(tr("No-go zones on the way", "Zones interdites au passage"), form.nearbyNoGo, { form.nearbyNoGo = it },
                            subtitle = if (form.nearbyNoGo) tr("Their loop is done when the route passes nearby, then mowing resumes.", "Leur tour est fait quand le trajet passe à proximité, puis la tonte reprend.")
                                else tr("All the no-go zone loops are grouped before the perimeter.", "Tous les tours de zones interdites sont regroupés avant le périmètre."),
                            enabled = !prep.computing && form.borders && form.noGoPasses > 0)
                        ChargeRangeRow(form.chargeLow, form.chargeHigh, enabled = !prep.computing) { low, high ->
                            form.chargeLow = low; form.chargeHigh = high
                            form.returnReserve = fr.luba.poc.simulation.ReturnReserve.sanitize(form.returnReserve.roundToInt(), low.roundToInt()).toFloat()
                        }
                        ReturnReserveRow(form.returnReserve, form.chargeLow, enabled = !prep.computing) { form.returnReserve = it }
                    }
                }
            }
            if (prep.error.isNotBlank()) StatusNote(prep.error, NoteTone.DANGER)
        }
        else -> PushedPage(tr("Route preview", "Aperçu du trajet"), onBack = back,
            bottomBar = {
                PreviewFooterNotes(
                    warnings = listOfNotNull(
                        tr("Robot self-stop not demonstrated (Service → Command cutoff): supervision required.", "Arrêt seul du robot non démontré (Service → Coupure des commandes) : supervision requise.")
                            .takeIf { state.ready && !demo && !cloud && state.motorWatchdog?.demonstrated != true },
                        state.missionCheckpoint
                            ?.let { tr("Starting replaces the saved \"${it.task.title}\" (${(it.progress * 100).toInt()} % ${it.task.doneLabel}).", "Démarrer remplace la sauvegarde « ${it.task.title} » (${(it.progress * 100).toInt()} % ${it.task.doneLabel}).") }),
                    status = when {
                        prep.computing -> tr("Computing the route · ${prep.progress.third}", "Calcul du trajet · ${prep.progress.third}")
                        prep.simBuilding -> prep.simulationPreparationStage.takeIf { it.startsWith(tr("Checking", "Vérification")) } ?: tr("Checking the links · ${prep.simulationPreparationStage}", "Vérification des raccords · ${prep.simulationPreparationStage}")
                        !state.ready -> tr("Start possible as soon as the robot is connected (gateway or direct Bluetooth).", "Démarrage possible dès que le robot est connecté (passerelle ou Bluetooth direct).")
                        cloud || demo -> tr("To mow, read the robot's Bluetooth map.", "Pour tondre, lisez la carte Bluetooth du robot.")
                        else -> null
                    },
                    busy = prep.computing || prep.simBuilding)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    SecondaryPillButton(tr("Simulate", "Simuler"), onSimulate, Modifier.weight(1f), enabled = simulateEnabled, icon = Icons.Rounded.Animation)
                    PrimaryPillButton(if (preview?.mode?.isSurvey == true) tr("Start the survey", "Lancer le relevé") else tr("Start", "Démarrer"), onStartRobot,
                        Modifier.weight(1f), icon = Icons.Rounded.PlayArrow,
                        enabled = preview?.localOnly != true && prepared && state.ready && !demo && !cloud && !prep.computing && !prep.simBuilding && !state.campaign.running &&
                            !state.navTest.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading && !state.robotRoute.running)
                }
            }) {
            // Link preparation: state shown only once, in the bottom bar.
            MowingPreview(planningMap, preview, localObstacles, prep.estimate, form.mowingSpeed,
                computing = prep.computing, validating = false, stage = if(prep.simBuilding) prep.simulationPreparationStage else prep.progress.third,
                zoneIds = preview?.zoneIds?.takeIf { it.isNotEmpty() }?.toSet() ?: selected,
                autonomyMinutes = form.autonomy, chargeMinutes = form.chargeTime, initialBattery = form.initialBattery,
                chargeLow = form.chargeLow, chargeHigh = form.chargeHigh, requestedStart = start.previewStart?.point,
                onPickStart = if (sheets.pickingStart && !prep.computing) { p ->
                    if (ZoneStart.zoneAt(planningMap, selected, p) == null) prep.error = tr("Start: tap a point in a chosen zone, outside no-go zones", "Départ : touchez un point d'une zone choisie, hors zone interdite")
                    else { start.startOverride = StartOverride.Chosen(p); onCompute() }
                } else null)
            StartPointGroup(planningMap, start.previewStart, selected.firstOrNull(), zoneStarts.starts, sheets.pickingStart,
                enabled = !prep.computing && !prep.simBuilding,
                onPick = { sheets.pickingStart = !sheets.pickingStart; if (prep.error.startsWith(tr("Start", "Départ"))) prep.error = "" },
                onNearest = { start.startOverride = StartOverride.Nearest; onCompute() },
                onSave = { start.previewStart?.let { r -> if (r.zoneId != null && r.point != null) zoneStarts.save(r.zoneId, r.point) } },
                onForget = {
                    selected.firstOrNull()?.let { id -> zoneStarts.clear(id) }
                    if (start.previewStart?.source == StartSource.SAVED) onCompute()
                })
            SettingsGroup(tr("Route", "Trajet")) {
                KeyValueRow("Zones", zoneLine)
                KeyValueRow(tr("Settings", "Réglages"), if (form.mode.isSurvey) "${form.mode.label}${if (form.mode == CoverageMode.SURVEY_GRID) tr(" · grid ${form.signalGridSpacing.toInt()} m", " · maille ${form.signalGridSpacing.toInt()} m") else ""} · ${tr("blades stopped", "lames arrêtées")} · ${Format.num(form.mowingSpeed.toDouble(), 2)} m/s"
                    else "${when (form.mode) { CoverageMode.CHECKERBOARD -> tr("Checkerboard", "Damier"); CoverageMode.OPTIMIZED -> tr("Optimized", "Optimisé"); CoverageMode.ZAMBONI -> tr("Zamboni (experimental)", "Zamboni (expérimental)"); CoverageMode.SPIRAL -> spiralSummary(form.spiralDirection); else -> "Zigzag" }} · ${preview?.cutHeights?.takeIf { it.size > 1 }?.let { "${it.size} passes " + CutPasses.label(it) } ?: "$cuttingHeight mm"} · ${(form.spacing * 100).roundToInt()} cm · ${Format.num(form.mowingSpeed.toDouble(), 2)} m/s")
                preview?.let { p -> directionLine(planningMap, p, form.spacing.toDouble())?.let { KeyValueRow(tr("Direction", "Direction"), it) } }
                preview?.let { p -> zoneModeWarnings(planningMap, p).forEach { StatusNote(it, NoteTone.WARNING) } }
                if (prep.estimateError.isNotBlank()) InfoNote(prep.estimateError)
            }
            if (!form.mode.isSurvey) fr.luba.poc.weather.MowingRainWarning(prep.estimate?.totalSeconds)
            prep.planningReport?.let { report ->
                SettingsGroup(tr("Check", "Vérification")) {
                    report.selected.metrics?.let { metric ->
                        KeyValueRow(tr("Coverage", "Couverture"), "${Format.num(metric.coverage*100,1)} %")
                        KeyValueRow(tr("Unmowed area", "Surface non tondue"), "${Format.num(metric.uncoveredAreaM2,1)} m²")
                        KeyValueRow(tr("Total distance", "Distance totale"), tr("${Format.num(metric.distanceM,0)} m including ${Format.num(metric.transitM,0)} m without cutting", "${Format.num(metric.distanceM,0)} m dont ${Format.num(metric.transitM,0)} m hors coupe"))
                    }
                    if (report.selected.valid) StatusNote(tr("Route checked: same route for the simulation and real mowing.", "Trajet vérifié : même trajet pour la simulation et la tonte réelle."), NoteTone.SUCCESS)
                    else StatusNote(tr("Route not validated: ${report.selected.diagnostics.joinToString(" ; ")}", "Trajet non validé : ${report.selected.diagnostics.joinToString(" ; ")}"), NoteTone.DANGER)
                }
            }
            if (state.status.startsWith(tr("Mowing refused", "Tonte refusée")) || state.status.startsWith(tr("Preparation:", "Préparation :"))) StatusNote(state.status, NoteTone.DANGER)
            if (prep.error.isNotBlank()) StatusNote(prep.error, NoteTone.DANGER)
        }
    }
}

/**
 * Direction of the passes ([OrientationMode]): automatic, changed every session or one manual angle (0 to 175° in 5° steps).
 * Visible but disabled for the modes whose direction follows the shape; the saved choice is kept for the next zigzag.
 * Manual: the angle is also shown and set on a map of the chosen zones ([OrientationPicker], guide lines only); the
 * "Route preview" button of the page then computes the route at this angle.
 */
@Composable
private fun OrientationGroup(form: MowingTaskForm, map: RobotMap, selected: Set<Long>, enabled: Boolean) {
    val applies = form.mode.usesOrientation
    SettingsGroup(tr("Direction of the passes", "Direction des passes")) {
        Segmented(OrientationMode.entries.map { it to it.choiceLabel }, form.orientation, { form.orientation = it }, enabled = enabled && applies)
        if (form.orientation == OrientationMode.MANUAL) {
            SliderRow(tr("Angle of the passes", "Angle des passes"), form.manualAngle.coerceAtMost(OrientationMode.MANUAL_MAX_DEG).toFloat(),
                { form.manualAngle = ((it / OrientationMode.MANUAL_STEP_DEG).roundToInt() * OrientationMode.MANUAL_STEP_DEG).coerceIn(0, OrientationMode.MANUAL_MAX_DEG) },
                0f..OrientationMode.MANUAL_MAX_DEG.toFloat(), valueLabel = "${form.manualAngle}°",
                steps = OrientationMode.MANUAL_MAX_DEG / OrientationMode.MANUAL_STEP_DEG - 1, enabled = enabled && applies)
            // Same angle as the one handed to the planner (folded into 0..179).
            if (applies && selected.isNotEmpty())
                OrientationPicker(map, selected, form.mode, foldAngle(form.manualAngle), enabled) { form.manualAngle = it }
        }
        InfoNote(if (applies) form.orientation.hint else orientationUnusedNote())
    }
}

/** Note of the Zamboni mode: what it does, and that its rolling U-turns are not validated on the robot yet. */
internal fun zamboniNote(): String = tr(
    "Experimental. Passes taken a few rows apart with rolling U-turns, while the mowed part advances as one block: a stop leaves no isolated stripe. Relies on 0.35 m turns not yet validated on the robot.",
    "Expérimental. Passes prises à quelques rangs d'écart avec des demi-tours en roulant, la partie tondue avançant d'un seul bloc : un arrêt ne laisse pas de bande isolée. Repose sur des virages de 0,35 m pas encore validés sur le robot.")

/** Note of the Spiral mode in its [direction]: what it does, its zigzag parts and fallback, and that its turns are not
 * validated on the robot yet. */
internal fun spiralNote(direction: SpiralDirection = SpiralDirection.FROM_EDGE): String = if (direction == SpiralDirection.FROM_CENTRE) tr(
    "Experimental. Passes parallel to the boundary, joined into a spiral driven from the centre out to the boundary without U-turns; the centre is mowed in zigzag first, the tight corners in zigzag too. A zone where the spiral would mow less is mowed in zigzag (shown in the preview). Relies on 0.35 m turns not yet validated on the robot.",
    "Expérimental. Passes parallèles au contour, reliées en spirale parcourue du centre vers le contour sans demi-tour ; le centre est d'abord tondu en zigzag, les coins serrés aussi. Une zone où la spirale tondrait moins est tondue en zigzag (signalé dans l'aperçu). Repose sur des virages de 0,35 m pas encore validés sur le robot.")
else tr(
    "Experimental. Passes parallel to the boundary, joined into an inward spiral without U-turns; the centre and the tight corners are finished in zigzag. A zone where the spiral would mow less is mowed in zigzag (shown in the preview). Relies on 0.35 m turns not yet validated on the robot.",
    "Expérimental. Passes parallèles au contour, reliées en spirale vers l'intérieur sans demi-tour ; le centre et les coins serrés sont finis en zigzag. Une zone où la spirale tondrait moins est tondue en zigzag (signalé dans l'aperçu). Repose sur des virages de 0,35 m pas encore validés sur le robot.")

/** Spiral mode and its [direction] in the summary of the route preview. */
internal fun spiralSummary(direction: SpiralDirection): String = if (direction == SpiralDirection.FROM_CENTRE)
    tr("Spiral from the centre (experimental)", "Spirale depuis le centre (expérimental)")
    else tr("Spiral from the edge (experimental)", "Spirale depuis le bord (expérimental)")

/** Subtitle of the Lawn friendly toggle in Spiral mode, where it does not apply. */
internal fun spiralLawnNote(): String = tr(
    "Not used by Spiral: no U-turns between its rings.",
    "Non utilisé par Spirale : pas de demi-tours entre ses anneaux.")

/** Subtitle of the Lawn friendly toggle in Zamboni mode, where it cannot be turned off. */
internal fun zamboniLawnNote(): String = tr(
    "Always on in Zamboni: the order weighs duration and lawn wear.",
    "Toujours actif en Zamboni : l'ordre tient compte de la durée et de l'usure du gazon.")

private fun durationText(seconds: Double): String {
    val minutes = ceil(seconds / 60).toInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}

/** Chosen zones, in mowing order; a tap goes back to the choice on the map. */
@Composable
private fun ZoneOrderGroup(count: Int, zoneLine: String, onEdit: () -> Unit, editEnabled: Boolean) {
    SettingsGroup("Zones") {
        NavRow(Format.plural(count, "zone", "zones") + tr(" · mowing order", " · ordre de tonte"), subtitle = zoneLine, icon = Icons.Rounded.Grass,
            enabled = editEnabled, onClick = onEdit)
    }
}

/** Working hours ([fr.luba.poc.mowing.WorkWindowSetting]): setting shared by all tasks. */
@Composable
private fun WorkWindowGroup(enabled: Boolean) {
    val colors = MammotionColors
    val window by fr.luba.poc.mowing.WorkWindowSetting.flow.collectAsStateWithLifecycle()
    SettingsGroup(tr("Working hours", "Plage horaire de travail")) {
        ToggleRow(tr("Limit mowing hours", "Limiter les heures de tonte"), window.enabled, { fr.luba.poc.mowing.WorkWindowSetting.set(window.copy(enabled = it)) },
            subtitle = if (window.enabled) tr("Mowing allowed ${window.label}", "Tonte permise de ${window.label}") else tr("Mowing allowed at any time", "Tonte permise à toute heure"), enabled = enabled)
        if (window.enabled) {
            Row(Modifier.fillMaxWidth()) {
                Text(tr("Working hours", "Heures de travail"), style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, modifier = Modifier.weight(1f))
                Text(window.label, style = MaterialTheme.typography.titleSmall, color = colors.Value)
            }
            val step = fr.luba.poc.mowing.WorkWindow.STEP
            LubaRangeSlider(value = window.startMinutes.toFloat()..window.endMinutes.toFloat(), onValueChange = { range ->
                val start = (range.start / step).roundToInt() * step
                val end = (range.endInclusive / step).roundToInt() * step
                if (end - start >= fr.luba.poc.mowing.WorkWindow.MIN_SPAN)
                    fr.luba.poc.mowing.WorkWindowSetting.set(window.copy(startMinutes = start, endMinutes = end))
            }, valueRange = 0f..fr.luba.poc.mowing.WorkWindow.DAY.toFloat(), steps = fr.luba.poc.mowing.WorkWindow.DAY / step - 1, enabled = enabled)
            InfoNote(tr("Outside: no mowing starts; a mowing in progress stops and the robot returns to base, blades stopped " +
                "(resume possible afterwards from the interrupted point); a robot left outside is brought back to base.",
                "En dehors : aucune tonte ne démarre ; une tonte en cours s'arrête et le robot rentre à la base, lames arrêtées " +
                "(reprise possible ensuite depuis le point interrompu) ; un robot resté dehors est ramené à la base."))
        }
        InfoNote(tr("Working hours set in the Mammotion app add to these: outside that range, the robot flatly refuses " +
            "to work (self-check 23), without return to base or resume. Better disable it and keep this one.",
            "Une plage horaire réglée dans l'app Mammotion s'ajoute à celle-ci : hors de cette plage, le robot refuse " +
            "net de travailler (autodiagnostic 23), sans retour à la base ni reprise. Mieux vaut la désactiver et garder celle-ci."))
    }
}

/** Recharge thresholds: return to base then resume, minimum gap of 5 %. */
@Composable
private fun ChargeRangeRow(low: Float, high: Float, enabled: Boolean, onChange: (Float, Float) -> Unit) {
    val colors = MammotionColors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(tr("Battery: return / resume", "Batterie : retour / reprise"), style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, modifier = Modifier.weight(1f))
            Text("${low.toInt()} % – ${high.toInt()} %", style = MaterialTheme.typography.titleSmall, color = colors.Value)
        }
        LubaRangeSlider(value = low..high, onValueChange = { range ->
            val l = range.start.roundToInt().coerceAtMost(80).toFloat()
            val h = range.endInclusive.roundToInt().toFloat()
            if (h - l >= 5f) onChange(l, h)
        }, valueRange = 10f..100f, steps = 89, enabled = enabled)
        InfoNote(tr("Mowing stops at ${low.toInt()} % to recharge, then resumes at the interrupted point at ${high.toInt()} %.", "La tonte s'arrête à ${low.toInt()} % pour recharger, puis reprend au point interrompu à ${high.toInt()} %."))
    }
}

/**
 * Return reserve ("Never Die Outside"): the robot heads back as soon as its battery nears the return cost plus this
 * reserve, even above the low threshold; bounded by [fr.luba.poc.simulation.ReturnReserve.range].
 */
@Composable
private fun ReturnReserveRow(reserve: Float, low: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val range = fr.luba.poc.simulation.ReturnReserve.range(low.roundToInt())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        SliderRow(tr("Return reserve", "Réserve au retour"), reserve.coerceIn(range.first.toFloat(), range.last.toFloat()),
            { onChange(it.roundToInt().toFloat()) }, range.first.toFloat()..range.last.toFloat(), valueLabel = "${reserve.roundToInt()} %",
            steps = (range.last - range.first - 1).coerceAtLeast(0), enabled = enabled && range.last > range.first)
        InfoNote(tr("Far from the base, the robot returns as soon as its battery nears the cost of the return plus ${reserve.roundToInt()} %, even above ${low.roundToInt()} %. " +
            "Between ${range.first} and ${range.last} % (never above the low threshold).",
            "Loin de la base, le robot rentre dès que sa batterie approche du coût du retour plus ${reserve.roundToInt()} %, même au-dessus de ${low.roundToInt()} %. " +
            "Entre ${range.first} et ${range.last} % (jamais au-dessus du seuil bas)."))
    }
}

/** Preview footer: grouped warnings, then a discreet status line above the actions. */
@Composable
private fun PreviewFooterNotes(warnings: List<String>, status: String?, busy: Boolean) {
    if (warnings.isNotEmpty()) StatusNote(warnings.joinToString("\n"), NoteTone.WARNING)
    if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = MammotionColors.Green, strokeWidth = 2.dp)
        Text(status, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
