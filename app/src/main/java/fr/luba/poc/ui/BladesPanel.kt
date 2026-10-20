package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.diagnostics.BladeSafety
import fr.luba.poc.diagnostics.HeightSequence
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.Point
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.delay

/** Tabs of the blade test bench: height setting, rotation at rest, cutting while moving. */
enum class BladeBench(private val labelEn: String, private val labelFr: String) {
    HEIGHT("Height", "Hauteur"), STATIC("Robot stationary", "Robot immobile"), MOVING("Moving", "En déplacement");

    val label: String get() = tr(labelEn, labelFr)
}

/** Test running on this page: locks the tabs and the other actions. */
private fun RobotUi.bladeBusy(): Boolean = bladeTestActive || bladeHeightPending || heightSequence.running || (navTest.running && navTest.tonte)

private fun RobotUi.bladeIdle(): Boolean = ready && !robotRoute.running && !manualActive && !mowing.active && !navTest.running &&
    !teach.running && !campaign.running && !bladeTestActive && !mapLoading

private fun bladeBitsLabel(sensors: Int?): String = when (val bits = sensors?.let { (it shr 9) and 7 }) {
    null -> tr("unknown", "inconnu"); 0 -> tr("stopped", "arrêtées"); 1 -> tr("running", "en marche"); else -> tr("raw $bits", "brut $bits")
}

internal fun robotStateLabel(state: Int?): String = when (state) {
    null -> tr("unknown", "inconnu"); 11 -> tr("ready (11)", "prêt (11)"); 20 -> tr("manual mowing (20)", "tonte manuelle (20)"); 13 -> tr("mowing (13)", "tonte (13)")
    14 -> tr("return (14)", "retour (14)"); else -> tr("state $state", "état $state")
}

/**
 * Service → Cutting height ([BladeBench.HEIGHT]) and Service → Blade test ([BladeBench.STATIC], [BladeBench.MOVING]):
 * benches with the same layout (live status, settings, action at the bottom), tabs only when there are several benches.
 * Robot commands and guards are those of [RobotBle]; leaving the page stops the running test.
 */
@Composable
internal fun BladesPage(robot: RobotBle, state: RobotUi, back: () -> Unit, exportArchive: () -> Unit, benches: List<BladeBench>, title: String,
                        header: @Composable ColumnScope.() -> Unit) {
    OnLeaveScreen(robot) {
        robot.stopOwnedBladeTest(tr("Left the Blades screen", "Sortie écran Lames"))
        robot.stopHeightSequence(tr("Left the Blades screen", "Sortie écran Lames"))
        if (robot.state.value.navTest.tonte) robot.stopNavTest(tr("Left the Blades screen", "Sortie écran Lames"), userInitiated = true)
    }
    var bench by rememberSaveable { mutableStateOf(benches.first()) }
    if (bench !in benches) bench = benches.first()
    var height by rememberSaveable { mutableIntStateOf(state.telemetry.bladeHeightMm ?: state.cutHeights.defaultMm) }
    // Range of the connected model (higher on the H versions): a height chosen for another robot is brought back into it.
    LaunchedEffect(state.cutHeights) { height = state.cutHeights.clamp(height) }
    var supervised by rememberSaveable { mutableStateOf(false) }
    var knifeAlt by rememberSaveable { mutableStateOf(false) }
    var radius by rememberSaveable { mutableFloatStateOf(4f) }
    var duration by rememberSaveable { mutableIntStateOf(300) }
    // Confirmed supervision only applies to the selected test.
    LaunchedEffect(bench) { supervised = false }
    BladeInterruptionDialog(robot, state)
    PushedPage(title, back, bottomBar = {
        when (bench) {
            BladeBench.HEIGHT -> HeightActions(robot, state, height)
            BladeBench.STATIC -> StaticActions(robot, state, height, knifeAlt, supervised)
            BladeBench.MOVING -> MovingActions(robot, state, height, radius, duration, supervised)
        }
    }) {
        header()
        if (benches.size > 1) Segmented(benches.map { it to it.label }, bench, { bench = it }, enabled = !state.bladeBusy())
        // Warning at the top, as on every page that moves the robot or the blades.
        SafetyNotice(when (bench) {
            BladeBench.HEIGHT -> tr("Robot stationary, discs stopped. The robot briefly switches to manual mowing (blades OFF) to adjust the deck, then returns to the ready state.",
                "Robot immobile, disques à l'arrêt. Le robot passe brièvement en tonte manuelle (lames OFF) pour régler le plateau, puis revient à l'état prêt.")
            BladeBench.STATIC -> tr("Robot on the ground and stationary, discs clear, no person or animal nearby. Keep the physical stop within reach.",
                "Robot au sol et immobile, disques dégagés, personne ni animal à proximité. Gardez l'arrêt physique accessible.")
            BladeBench.MOVING -> tr("The robot really drives with the blades running. Clear area (toys, cables, stones), no person or animal, physical stop within reach.",
                "Le robot roule réellement lames en marche. Zone dégagée (jouets, câbles, cailloux), personne ni animal, arrêt physique accessible.")
        })
        BladeLiveStatus(state)
        when (bench) {
            BladeBench.HEIGHT -> HeightBench(robot, state, height) { height = it }
            BladeBench.STATIC -> StaticBench(robot, state, height, knifeAlt, { knifeAlt = it }, supervised) { supervised = it }
            BladeBench.MOVING -> MovingBench(robot, state, height, { height = it }, radius, { radius = it }, duration, { duration = it }, supervised) { supervised = it }
        }
        BladeResults(state, bench, exportArchive)
    }
}

/** Live status: what the robot reports, whatever the test. */
@Composable
private fun BladeLiveStatus(state: RobotUi) {
    val t = state.telemetry
    SettingsGroup(tr("Live status", "État en direct")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            LiveTile(Icons.Rounded.Height, t.bladeHeightMm?.let { "$it mm" } ?: "—", tr("Height", "Hauteur"),
                if (t.heightMoving == true) MammotionColors.Warning else MammotionColors.GreenDeep)
            val bits = t.sensorStatus?.let { (it shr 9) and 7 }
            LiveTile(Icons.Rounded.ContentCut, bladeBitsLabel(t.sensorStatus), tr("Blades", "Lames"),
                when (bits) { 0 -> MammotionColors.GreenDeep; 1 -> MammotionColors.Danger; else -> MammotionColors.Warning })
            LiveTile(Icons.Rounded.Speed, t.cutterRpm?.let { "$it" } ?: "—", tr("rpm", "tr/min"))
        }
        KeyValueRow(tr("Height adjustment", "Réglage de hauteur"), t.heightMoving?.let { if (it) tr("in progress · target ${t.heightTargetMm ?: "—"} mm", "en cours · cible ${t.heightTargetMm ?: "—"} mm") else tr("done", "terminé") } ?: tr("no feedback", "aucun retour"),
            valueColor = if (t.heightMoving == true) MammotionColors.Warning else MammotionColors.TextPrimary)
        KeyValueRow(tr("Robot mode", "Mode robot"), robotStateLabel(t.robotState))
        KeyValueRow(tr("Battery", "Batterie"), t.battery?.let { "$it %" } ?: "—")
        KeyValueRow(tr("Blade status", "État des disques"), if (t.knifeStatus == null && t.cutterMode == null && t.lastKnifeStatusAt == null) tr("not reported yet", "pas encore rapporté")
            else "${t.knifeStatus ?: "—"} · mode ${t.cutterMode ?: "—"} · ${t.lastKnifeStatusAt?.let(Format::time) ?: tr("never", "jamais")}")
        if (state.bladeStatus.isNotBlank()) InfoNote(state.bladeStatus)
    }
}

@Composable
private fun RowScope.LiveTile(icon: ImageVector, value: String, label: String, tint: androidx.compose.ui.graphics.Color = MammotionColors.GreenDeep) =
    StatTile(icon, value, label, Modifier.weight(1f), tint)

// ───────────────────────────── Height ─────────────────────────────

@Composable
private fun HeightBench(robot: RobotBle, state: RobotUi, height: Int, onHeight: (Int) -> Unit) {
    val t = state.telemetry
    val sequence = state.heightSequence
    val locked = state.bladeHeightPending || sequence.running || !state.bladeIdle()
    SettingsGroup(tr("Setpoint", "Consigne")) {
        SliderRow(tr("Requested height", "Hauteur demandée"), height.toFloat(), { onHeight(it.toInt()) }, state.cutHeights.floatRange(),
            valueLabel = "$height mm", steps = state.cutHeights.sliderSteps, enabled = !locked)
        ChipChoice(state.cutHeights.presets.map { it to "$it" }, height, onHeight, enabled = !locked)
        val heights = state.cutHeights
        InfoNote(when {
            heights.high -> tr("H version (high cut): ${heights.label}.", "Version H (coupe haute) : ${heights.label}.")
            else -> tr("Range of this model: ${heights.label}.", "Plage de ce modèle : ${heights.label}.")
        } + (state.telemetry.productType ?: state.productType)?.let { tr(" Model code $it.", " Code modèle $it.") }.orEmpty())
        if (state.bladeHeightPending) {
            val target = if (sequence.running) sequence.targets.getOrNull(sequence.index) ?: height else height
            val from = sequence.startMm ?: t.bladeHeightMm
            val current = t.bladeHeightMm
            val fraction = if (from != null && current != null && from != target)
                (1f - kotlin.math.abs(target - current).toFloat() / kotlin.math.abs(target - from)).coerceIn(0f, 1f) else null
            ProgressNote(tr("Waiting for robot feedback · ${current ?: "—"} mm → $target mm", "Attente du retour robot · ${current ?: "—"} mm → $target mm"), fraction)
        }
    }
    SettingsGroup(tr("Automatic sequences", "Séquences automatiques")) {
        val heights = state.cutHeights
        val current = t.bladeHeightMm?.takeIf { it in heights }
        val enabled = current != null && !locked
        SecondaryPillButton(current?.let { tr("Round trip: ${HeightSequence.roundTrip(it, heights).joinToString(" → ")} mm", "Aller-retour : ${HeightSequence.roundTrip(it, heights).joinToString(" → ")} mm") } ?: tr("Round trip ±10 mm", "Aller-retour ±10 mm"),
            { current?.let { robot.startHeightSequence(HeightSequence.roundTrip(it, heights)) } }, Modifier.fillMaxWidth(), enabled = enabled)
        SecondaryPillButton(current?.let { tr("Full travel: ${HeightSequence.extremes(it, heights).joinToString(" → ")} mm", "Course complète : ${HeightSequence.extremes(it, heights).joinToString(" → ")} mm") } ?: tr("Full travel ${heights.minMm} ↔ ${heights.maxMm} mm", "Course complète ${heights.minMm} ↔ ${heights.maxMm} mm"),
            { current?.let { robot.startHeightSequence(HeightSequence.extremes(it, heights)) } }, Modifier.fillMaxWidth(), enabled = enabled)
        if (current == null) InfoNote(tr("Current height unknown: apply a single setpoint first.", "Hauteur actuelle inconnue : appliquez d'abord une consigne simple."))
        else InfoNote(tr("Each setpoint must be reported by the robot before the next one; stops at the first fault.", "Chaque consigne doit être rapportée par le robot avant la suivante ; arrêt au premier défaut."))
        if (sequence.targets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
                sequence.targets.forEachIndexed { index, mm ->
                    StatusPill("$mm mm", when {
                        !sequence.running -> MammotionColors.TextSecondary
                        index < sequence.index -> MammotionColors.Green
                        index == sequence.index -> MammotionColors.Warning
                        else -> MammotionColors.TextTertiary
                    })
                }
            }
            if (sequence.status.isNotBlank()) StatusNote(sequence.status, if (sequence.running) NoteTone.WARNING else NoteTone.INFO)
        }
    }
    SettingsGroup(tr("On-site observation", "Observation sur place")) {
        Text(tr("Did the deck move?", "Le plateau a-t-il bougé ?"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Yes", "Oui"), { robot.recordHeightObservation(true) }, Modifier.weight(1f))
            SecondaryPillButton(tr("No", "Non"), { robot.recordHeightObservation(false) }, Modifier.weight(1f))
        }
        KeyValueRow(tr("Adjustment feedback (driver)", "Retour réglage (driver)"), t.driverHeightMm?.let { "$it mm · ${t.lastDriverHeightAt?.let(Format::time)}" } ?: "absent")
        KeyValueRow(tr("Work report", "Rapport de travail"), t.workHeightMm?.let { "$it mm · ${t.lastWorkHeightAt?.let(Format::time)}" } ?: "absent")
        InfoNote(tr("The motor noise confirms an action, not the exact height: measure with a ruler if needed.", "Le bruit du moteur confirme une action, pas la hauteur exacte : mesurez au réglet si besoin."))
    }
}

@Composable
private fun ColumnScope.HeightActions(robot: RobotBle, state: RobotUi, height: Int) {
    when {
        state.heightSequence.running -> DangerPillButton(tr("Cancel the sequence", "Annuler la séquence"), { robot.stopHeightSequence() }, Modifier.fillMaxWidth())
        state.bladeHeightPending -> SecondaryPillButton(tr("Cancel waiting (setting not confirmed)", "Annuler l'attente (réglage non confirmé)"), robot::cancelBladeHeight, Modifier.fillMaxWidth())
        else -> PrimaryPillButton(tr("Apply $height mm", "Appliquer $height mm"), { robot.setBladeHeight(height) }, Modifier.fillMaxWidth(),
            enabled = state.bladeIdle(), icon = Icons.Rounded.Height)
    }
}

// ───────────────────────────── Blades at rest ─────────────────────────────

@Composable
private fun StaticBench(robot: RobotBle, state: RobotUi, height: Int, knifeAlt: Boolean, onKnifeAlt: (Boolean) -> Unit,
                        supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    val t = state.telemetry
    SettingsGroup(tr("Preconditions", "Préconditions")) {
        PreconditionList(BladeSafety.checks(state.selected?.name, state.ageMs, t.battery, if (state.bladeTestActive) 11 else t.robotState,
            if (state.bladeTestActive) 0 else t.sensorStatus, t.selfCheck, state.campaign.running, height, state.cutHeights).map { it.label to it.ok })
    }
    SettingsGroup(tr("Procedure", "Déroulé")) {
        StepLine(1, tr("Check that the blades are stopped", "Vérifier l'arrêt des lames"), tr("OFF sent, then a fresh OFF state is awaited.", "OFF envoyé, puis état OFF frais attendu."))
        SecondaryPillButton(tr("Check that the blades are stopped", "Vérifier l'arrêt des lames"), { robot.prepareBladeStop(height) }, Modifier.fillMaxWidth(), enabled = state.bladeIdle() && !state.bladeHeightPending)
        StepLine(2, tr("Confirm supervision", "Confirmer la supervision"))
        LubaCheckRow(tr("I am supervising on site", "Je supervise sur place"), supervised, onSupervised, enabled = !state.campaign.running)
        StepLine(3, tr("Hold the button at the bottom", "Maintenir le bouton en bas"), tr("Manual mowing (20), discs at $height mm, 10 s at most; releasing stops.", "Tonte manuelle (20), disques à $height mm, 10 s au plus ; relâcher arrête."))
        ToggleRow(tr("Alternative SysKnifeControl command", "Commande alternative SysKnifeControl"), knifeAlt, onKnifeAlt, enabled = !state.campaign.running && !state.bladeTestActive,
            subtitle = tr("LUBA 1 / joystick variant to start the discs", "Variante LUBA 1 / joystick pour démarrer les disques"))
    }
}

@Composable
internal fun CheckLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, contentDescription = if (ok) "OK" else tr("Not met", "Non satisfait"),
            tint = if (ok) MammotionColors.Green else MammotionColors.Danger, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (ok) MammotionColors.TextPrimary else MammotionColors.Danger)
    }
}

/** Numbered step of a procedure: discreet round badge, title and optional detail. */
@Composable
internal fun StepLine(number: Int, title: String, detail: String = "") {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape).background(MammotionColors.SurfaceMuted), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelSmall, color = MammotionColors.TextSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
    }
}

/**
 * Test preconditions: one-line summary, only the unmet conditions listed;
 * the full detail stays available.
 */
@Composable
internal fun PreconditionList(checks: List<Pair<String, Boolean>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = checks.filterNot { it.second }
    if (failed.isEmpty()) StatusNote(tr("All conditions are met (${checks.size}/${checks.size})", "Toutes les conditions sont remplies (${checks.size}/${checks.size})"), NoteTone.SUCCESS)
    else StatusNote(tr("${Format.plural(failed.size, "unmet condition", "unmet conditions")} out of ${checks.size}",
        "${Format.plural(failed.size, "condition non remplie", "conditions non remplies")} sur ${checks.size}"), NoteTone.WARNING)
    (if (expanded) checks else failed).forEach { (label, ok) -> CheckLine(label, ok) }
    // Offset so the link text lines up with the card content.
    TextAction(if (expanded) tr("Hide details", "Masquer le détail") else tr("See the ${checks.size} conditions", "Voir les ${checks.size} conditions"), { expanded = !expanded }, Modifier.offset(x = (-12).dp))
}

/** Progress of a wait or a computation: green bar and current step. */
@Composable
internal fun ProgressNote(text: String, fraction: Float? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        if (fraction != null) LubaProgress(fraction, Modifier.fillMaxWidth()) else LubaProgress(Modifier.fillMaxWidth())
        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    }
}

@Composable
private fun ColumnScope.StaticActions(robot: RobotBle, state: RobotUi, height: Int, knifeAlt: Boolean, supervised: Boolean) {
    HoldToRunButton(tr("Hold to spin the discs (10 s max)", "Maintenir pour faire tourner les disques (10 s max)"), tr("Release to stop", "Relâcher pour arrêter"), state.bladeTestActive,
        enabled = supervised && !state.bladeHeightPending && state.ready && !state.campaign.running && !state.mowing.active &&
            !state.navTest.running && !state.teach.running && !state.heightSequence.running,
        onStart = { robot.startBladeTest(height, knifeAlt) }, onStop = { robot.stopOwnedBladeTest(tr("Button released / gesture canceled", "Relâchement / annulation du geste")) })
    DangerPillButton(tr("Stop the blades", "Arrêter les lames"), { robot.stopBladeTest(tr("Manual BLADES STOP", "STOP LAMES manuel")) }, Modifier.fillMaxWidth())
}

// ───────────────────────────── Cutting while moving ─────────────────────────────

@Composable
private fun MovingBench(robot: RobotBle, state: RobotUi, height: Int, onHeight: (Int) -> Unit, radius: Float, onRadius: (Float) -> Unit,
                        duration: Int, onDuration: (Int) -> Unit, supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    val nav = state.navTest
    val running = nav.running && nav.tonte
    LaunchedEffect(state.ready, state.robotMap.complete, radius) {
        while (true) {
            if (state.ready && !robot.state.value.navTest.running) robot.previewNavTest(radius.toDouble(), tonte = true)
            delay(2000)
        }
    }
    MovingMap(state)
    SettingsGroup(tr("Tracking", "Suivi")) {
        if (nav.status.isNotBlank() && (nav.tonte || !nav.running)) StatusNote(nav.status, navTone(nav.outcome, nav.running))
        if (!nav.running && nav.preview.isNotBlank()) InfoNote(nav.preview)
        KeyValueRow(tr("Commanded blades", "Lames commandées"), if (running) (if (nav.bladesOn) tr("running", "en marche") else tr("stopped", "arrêtées")) else "—",
            valueColor = if (running && nav.bladesOn) MammotionColors.Danger else MammotionColors.TextPrimary)
        KeyValueRow("Point", if (running) "${nav.segmentIndex}/${nav.segmentCount}" else "—")
        KeyValueRow(tr("Distance traveled", "Distance parcourue"), "${Format.num(nav.observedMeters, 2)} m")
        KeyValueRow(tr("Planned with blades running", "Prévu lames en marche"), "${Format.num(nav.plannedCutMeters, 1)} m")
    }
    SettingsGroup(tr("Test settings", "Réglages de l'essai")) {
        SliderRow(tr("Cutting height", "Hauteur de coupe"), height.toFloat(), { onHeight(it.toInt()) }, state.cutHeights.floatRange(),
            valueLabel = "$height mm", steps = state.cutHeights.sliderSteps, enabled = !running)
        SliderRow(tr("Travel speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
            valueLabel = "${state.trajectorySpeedPercent} %", enabled = !running)
        SliderRow(tr("Radius around the start", "Rayon autour du départ"), radius, onRadius, 2f..8f, valueLabel = "${Format.num(radius.toDouble())} m", steps = 11, enabled = !running)
        Text(tr("Maximum duration", "Durée maximale"), style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
        Segmented(listOf(120 to "2 min", 300 to "5 min", 600 to "10 min"), duration, onDuration, enabled = !running)
        LubaCheckRow(tr("I am supervising on site, area cleared", "Je supervise sur place, zone dégagée"), supervised, onSupervised, enabled = !running)
    }
    InfoNote(tr("Reported height, manual mowing, then three stripes and a border with the blades running (links with the blades stopped), each state confirmed by the robot. " +
        "Deviation > 30 cm, obstacle, contact, unexpected state, leaving the screen or STOP: the robot and the blades stop. " +
        "Start more than 0.95 m from boundaries and no-go zones.",
        "Hauteur rapportée, tonte manuelle, puis trois bandes et une bordure lames en marche (liaisons lames arrêtées), chaque état confirmé par le robot. " +
        "Écart > 30 cm, obstacle, contact, état inattendu, sortie de l'écran ou STOP : arrêt du robot et des lames. " +
        "Départ à plus de 0,95 m des limites et exclusions."))
}

@Composable
private fun MovingMap(state: RobotUi) {
    val t = state.telemetry
    var cameraReset by remember { mutableIntStateOf(0) }
    SettingsGroup(tr("Planned route", "Parcours prévu")) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
            MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                trail = state.navTest.trail.map { Point(it.x, it.y) }, start = state.navTest.trail.firstOrNull()?.let { Point(it.x, it.y) },
                obstacles = state.obstacles, planned = state.navTest.planned, plannedBands = state.navTest.plannedBands,
                plannedBorders = state.navTest.plannedBorders, interactive = true, cameraReset = cameraReset, modifier = Modifier.fillMaxSize())
            MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        InfoNote(tr("Stripes and border: blades running · links: blades stopped.", "Bandes et bordure : lames en marche · liaisons : lames arrêtées."))
        if (t.x == null || t.y == null) InfoNote(tr("Robot position unavailable: test impossible.", "Position du robot indisponible : essai impossible."))
        if (!state.robotMap.complete) InfoNote(tr("Incomplete map: read the robot map before the test.", "Carte incomplète : lisez la carte du robot avant l'essai."))
    }
}

@Composable
private fun ColumnScope.MovingActions(robot: RobotBle, state: RobotUi, height: Int, radius: Float, duration: Int, supervised: Boolean) {
    if (state.navTest.running && state.navTest.tonte)
        DangerPillButton(tr("STOP · stop the robot and the blades", "STOP · arrêter le robot et les lames"), { robot.stopNavTest(tr("Manual cutting test STOP", "STOP essai de coupe manuel"), userInitiated = true) }, Modifier.fillMaxWidth())
    else {
        PrimaryPillButton(tr("Start the cutting test · $height mm", "Démarrer l'essai de coupe · $height mm"), { robot.startBladeMoveTest(radius.toDouble(), height, duration) }, Modifier.fillMaxWidth(),
            enabled = supervised && state.bladeIdle() && !state.bladeHeightPending && !state.heightSequence.running && state.robotMap.complete,
            icon = Icons.Rounded.PlayArrow)
        DangerPillButton(tr("Stop the blades", "Arrêter les lames"), { robot.stopBladeTest(tr("Manual BLADES STOP", "STOP LAMES manuel")) }, Modifier.fillMaxWidth())
    }
}

/** Unrequested stop of the moving test: the reason stays shown until acknowledged. */
@Composable
private fun BladeInterruptionDialog(robot: RobotBle, state: RobotUi) {
    val reason = state.navTest.interruption?.takeIf { state.navTest.tonte } ?: return
    LubaDialog(onDismissRequest = robot::acknowledgeNavInterruption, title = tr("Cutting test interrupted", "Essai de coupe interrompu"), content = {
        StatusNote(reason, NoteTone.DANGER)
        Text(tr("The robot received STOP and the blade stop. Distance traveled: ${Format.num(state.navTest.observedMeters, 2)} m.",
            "Le robot a reçu STOP et l'arrêt des lames. Distance parcourue : ${Format.num(state.navTest.observedMeters, 2)} m."),
            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    })
}

// ───────────────────────────── Results ─────────────────────────────

@Composable
private fun BladeResults(state: RobotUi, bench: BladeBench, exportArchive: () -> Unit) {
    // Height results: titles "Hauteur …" / "Height …" (both kept, saved results may be in either language).
    val results = when (bench) {
        BladeBench.HEIGHT -> state.bladeResults.filter { it.title.contains("auteur") }
        BladeBench.STATIC -> state.bladeResults.filterNot { it.title.contains("auteur") }
        BladeBench.MOVING -> if (state.navTest.tonte) state.navTest.results else emptyList()
    }
    if (results.isEmpty()) return
    SettingsGroup(tr("Results", "Résultats")) {
        results.asReversed().take(30).forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
        TextAction(tr("Export tests and logs", "Exporter les essais et journaux"), exportArchive, enabled = !state.campaign.running && !state.bladeBusy())
    }
}

/** Tone of a route test state: failed, running, passed or neutral. */
internal fun navTone(outcome: String, running: Boolean, refused: Boolean = false): NoteTone = when {
    refused || outcome in setOf("ABORTED", "FAILED") -> NoteTone.DANGER
    running -> NoteTone.WARNING
    outcome == "PASS" -> NoteTone.SUCCESS
    else -> NoteTone.INFO
}
