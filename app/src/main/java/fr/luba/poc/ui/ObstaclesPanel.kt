package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import fr.luba.poc.camera.near
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.*
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.util.UUID

/** Tabs of Service → Obstacle tests: bumper, learning, real bypass, border (the list has its own page). */
enum class ObstacleBench(private val labelEn: String, private val labelFr: String) {
    CONTACT("Contact", "Contact"), LEARN("Learn", "Apprendre"), COURSE("Bypass", "Contournement"), BORDER("Border", "Bordure");
    val label: String get() = tr(labelEn, labelFr)
}

private fun RobotUi.courseRunning(): Boolean = navTest.running && navTest.courseObstacleId != null

private fun RobotUi.obstacleBusy(): Boolean = teach.running || courseRunning() || borderFrontal.running

private fun RobotUi.obstacleIdle(): Boolean = ready && !robotRoute.running && !manualActive && !mowing.active && !teach.running &&
    !navTest.running && !bladeTestActive && !campaign.running && !mapLoading && !borderFrontal.running

private fun bumperCodeLabel(code: Int?): String = when (code) {
    null -> tr("none", "aucun"); 1014, -1014 -> "contact"; 1207, -1207 -> tr("stuck fault", "défaut bloqué"); else -> "code $code"
}

/** Incident types stored in French (persisted values), shown in the current language. */
private fun incidentKindLabel(kind: String): String = when (kind) {
    "Contact bumper" -> tr("Bumper contact", "Contact bumper")
    "Frottement" -> tr("Rubbing", "Frottement")
    "Collision" -> "Collision"
    "Quasi-incident" -> tr("Near miss", "Quasi-incident")
    "Observation" -> "Observation"
    else -> kind
}

/**
 * Service → Obstacle tests: benches with the same layout (warning, live status, preconditions, procedure, action at
 * the bottom). Obstacles stay local to the phone; leaving the page stops the running test.
 */
@Composable
internal fun ObstaclesPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    OnLeaveScreen(robot) {
        robot.stopObstacleTest()
        robot.stopBorderFrontal(tr("Left the Obstacles screen", "Sortie écran Obstacles"))
        if (robot.state.value.navTest.courseObstacleId != null) robot.stopNavTest(tr("Left the Obstacles screen", "Sortie écran Obstacles"), userInitiated = true)
    }
    var bench by rememberSaveable { mutableStateOf(ObstacleBench.CONTACT) }
    var supervised by rememberSaveable { mutableStateOf(false) }
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var borderId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(bench) { supervised = false }
    // An obstacle proposal (contact during learning or a route) opens its tab.
    LaunchedEffect(state.teach.proposal) { if (state.teach.proposal != null) bench = ObstacleBench.LEARN }
    val selected = state.obstacles.firstOrNull { it.id == courseId } ?: nearestObstacle(state)
    CourseInterruptionDialog(robot, state)
    PushedPage(ServicePage.OBSTACLE_TESTS.title, back, bottomBar = {
        when (bench) {
            ObstacleBench.CONTACT -> ContactActions(robot, state, supervised)
            ObstacleBench.LEARN -> LearnActions(robot, state, supervised)
            ObstacleBench.COURSE -> CourseActions(robot, state, selected, supervised)
            ObstacleBench.BORDER -> BorderActions(robot, state, borderId, supervised)
        }
    }) {
        header()
        Segmented(ObstacleBench.entries.map { it to it.label }, bench, { bench = it }, enabled = !state.obstacleBusy())
        SafetyNotice(when (bench) {
            ObstacleBench.CONTACT -> tr("Robot stationary, blades stopped. Nobody in front of the robot, physical stop within reach.", "Robot immobile, lames arrêtées. Personne devant le robot, arrêt physique accessible.")
            ObstacleBench.LEARN -> tr("The robot really moves forward (1 m at most, 30 s), blades stopped. Soft or fixed obstacle in front of it, nobody on the path.", "Le robot avance réellement (1 m au plus, 30 s), lames arrêtées. Obstacle souple ou fixe devant lui, personne sur la trajectoire.")
            ObstacleBench.COURSE -> tr("The robot really drives, blades stopped, around a saved obstacle. Area cleared, physical stop within reach. Bumper contact: STOP and incident recorded.", "Le robot roule réellement, lames arrêtées, autour d'un obstacle enregistré. Zone dégagée, arrêt physique accessible. Contact pare-chocs : STOP et incident enregistré.")
            ObstacleBench.BORDER -> tr("The robot really drives, blades stopped, until it touches or passes a border. Nobody in front of it, physical stop within reach. Bumper contact: STOP, no obstacle recorded.", "Le robot roule réellement, lames arrêtées, jusqu'à toucher ou dépasser une bordure. Personne devant lui, arrêt physique accessible. Contact pare-chocs : STOP, aucun obstacle enregistré.")
        })
        ObstacleLiveStatus(state)
        when (bench) {
            ObstacleBench.CONTACT -> ContactBench(robot, state, supervised) { supervised = it }
            ObstacleBench.LEARN -> LearnBench(robot, state, supervised) { supervised = it }
            ObstacleBench.COURSE -> CourseBench(robot, state, selected, { courseId = it }, supervised) { supervised = it }
            ObstacleBench.BORDER -> BorderBench(robot, state, borderId, { borderId = it }, supervised) { supervised = it }
        }
    }
}

/**
 * Service → Obstacles: what the garden contains (saved and encountered obstacles, difficulties, photos) and the
 * simulation of a contact. No robot command here: the tests are in Service → Obstacle tests.
 */
@Composable
internal fun ObstacleListPage(robot: RobotBle, state: RobotUi, back: () -> Unit, openSimulation: () -> Unit) {
    PushedPage(ServicePage.OBSTACLES.title, back) {
        ObstacleListBench(robot, state, openSimulation)
    }
}

private fun nearestObstacle(state: RobotUi): LocalObstacle? {
    val x = state.telemetry.x; val y = state.telemetry.y
    if (x == null || y == null) return state.obstacles.firstOrNull()
    return state.obstacles.minByOrNull { Point(it.x, it.y).distance(Point(x, y)) }
}

/** Live status: bumper, last contact received, detection validated on this connection. */
@Composable
private fun ObstacleLiveStatus(state: RobotUi) {
    val t = state.telemetry
    SettingsGroup(tr("Live status", "État en direct")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            val alert = t.sensorStatus?.let { it and 7 }
            StatTile(Icons.Rounded.Shield, when (alert) { null -> tr("unknown", "inconnu"); 0 -> tr("clear", "libre"); else -> tr("alert $alert", "alerte $alert") }, tr("Bumper", "Pare-chocs"), Modifier.weight(1f),
                if (alert == 0) MammotionColors.GreenDeep else MammotionColors.Warning)
            StatTile(Icons.Rounded.Sensors, bumperCodeLabel(state.lastBumperCode), tr("Event", "Événement"), Modifier.weight(1f),
                if (state.lastBumperCode == null) MammotionColors.TextSecondary else MammotionColors.Danger)
            StatTile(if (state.teach.contactValidated) Icons.Rounded.CheckCircle else Icons.Rounded.HelpOutline,
                if (state.teach.contactValidated) tr("validated", "validée") else tr("to test", "à tester"), tr("Detection", "Détection"), Modifier.weight(1f),
                if (state.teach.contactValidated) MammotionColors.GreenDeep else MammotionColors.Warning)
        }
        KeyValueRow(tr("Last contact", "Dernier contact"), state.lastBumperAt?.let { "${state.lastBumperCode} · ${Format.time(it)}" } ?: tr("none since opening", "aucun depuis l'ouverture"))
        KeyValueRow(tr("Robot mode", "Mode robot"), robotStateLabel(t.robotState))
        KeyValueRow(tr("Position · heading", "Position · cap"), "${t.x?.let { Format.num(it, 2) } ?: "—"} / ${t.y?.let { Format.num(it, 2) } ?: "—"} m · ${t.heading?.let { "${Format.num(it, 0)}°" } ?: "—"}")
        KeyValueRow(tr("Saved obstacles", "Obstacles enregistrés"), "${state.obstacles.size}")
    }
}

@Composable
private fun TeachPreconditions(state: RobotUi) {
    val t = state.telemetry
    SettingsGroup(tr("Preconditions", "Préconditions")) {
        PreconditionList(TeachSafety.checks(state.selected?.name, state.ageMs, t.battery, if (state.teach.running) 11 else t.robotState, t.sensorStatus, t.selfCheck,
            state.campaign.running, state.bladeTestActive, state.navTest.running, state.mapLoading).map { (label, ok, _) -> label to ok })
    }
}

@Composable
private fun TeachStatus(state: RobotUi, title: String) {
    val teach = state.teach
    if (!teach.running && teach.results.none { it.title == title }) return
    SettingsGroup(tr("Tracking", "Suivi")) {
        if (teach.running) LubaProgress(Modifier.fillMaxWidth())
        if (teach.status.isNotBlank()) StatusNote(teach.status, when { teach.running -> NoteTone.WARNING
            teach.outcome == "CONTACT_DETECTED" || teach.outcome == "BLOCKED_AHEAD" -> NoteTone.SUCCESS; teach.outcome == "ABORTED" -> NoteTone.DANGER; else -> NoteTone.INFO })
        teach.results.filter { it.title == title }.asReversed().take(20).forEach {
            ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs))
        }
    }
}

// ───────────────────────────── Contact (stationary) ─────────────────────────────

@Composable
private fun ContactBench(robot: RobotBle, state: RobotUi, supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    TeachPreconditions(state)
    SettingsGroup(tr("Procedure", "Déroulé")) {
        StepLine(1, tr("Confirm supervision", "Confirmer la supervision"), tr("Next to the robot, to press the bumper.", "À côté du robot, pour appuyer sur le pare-chocs."))
        LubaCheckRow(tr("I am supervising on site", "Je supervise sur place"), supervised, onSupervised, enabled = !state.teach.running)
        StepLine(2, tr("Start listening (30 s)", "Lancer l'écoute (30 s)"), tr("Manual mode: manual mowing with blades OFF (20), where the LUBA 2 publishes the contact. Alternative: robot ready (11).", "Mode manuel : tonte manuelle lames OFF (20), où le LUBA 2 publie le contact. Variante : robot prêt (11)."))
        StepLine(3, tr("Press the front bumper", "Appuyer sur le pare-chocs avant"), tr("A 1014 contact stops the test and validates detection for this connection.", "Un contact 1014 arrête l'essai et valide la détection pour cette connexion."))
    }
    TeachStatus(state, "Bumper immobile")
}

@Composable
private fun ColumnScope.ContactActions(robot: RobotBle, state: RobotUi, supervised: Boolean) {
    if (state.teach.running) {
        SecondaryPillButton(tr("I heard the beep / touched the bumper", "J'ai entendu le bip / touché le pare-chocs"), robot::recordBumperBeep, Modifier.fillMaxWidth())
        DangerPillButton(tr("STOP · stop the test", "STOP · arrêter l'essai"), robot::stopObstacleTest, Modifier.fillMaxWidth())
    } else {
        val enabled = supervised && state.obstacleIdle()
        PrimaryPillButton(tr("Listen to the bumper · manual mode (30 s)", "Écouter le pare-chocs · mode manuel (30 s)"), { robot.startObstacleTest(false, manualMode = true) }, Modifier.fillMaxWidth(),
            enabled = enabled, icon = Icons.Rounded.Sensors)
        SecondaryPillButton(tr("Listen in ready mode (30 s)", "Écouter en mode prêt (30 s)"), { robot.startObstacleTest(false) }, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

// ───────────────────────────── Learning ─────────────────────────────

@Composable
private fun LearnBench(robot: RobotBle, state: RobotUi, supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    SettingsGroup(tr("Procedure", "Déroulé")) {
        CheckLine(tr("Bumper detection validated on this connection", "Détection pare-chocs validée sur cette connexion"), state.teach.contactValidated)
        if (!state.teach.contactValidated) InfoNote(tr("Not required: without a received contact, the approach still stops after 1 m, 30 s or 3 s without moving. A contact during the approach validates detection.",
            "Pas obligatoire : sans contact reçu, l'approche s'arrête quand même après 1 m, 30 s ou 3 s sans avancer. Un contact pendant l'approche valide la détection."))
        StepLine(1, tr("Place the robot facing the obstacle", "Placer le robot face à l'obstacle"), tr("30–80 cm away, in line; stops on contact or after 1 m.", "À 30–80 cm, dans l'axe ; arrêt au contact ou après 1 m."))
        StepLine(2, tr("Confirm supervision", "Confirmer la supervision"), tr("Physical stop within reach.", "Arrêt physique accessible."))
        LubaCheckRow(tr("I am supervising on site", "Je supervise sur place"), supervised, onSupervised, enabled = !state.teach.running)
        StepLine(3, tr("Check the proposal", "Vérifier la proposition"), tr("Adjust type, radius and margin before saving.", "Ajuster type, rayon et marge avant d'enregistrer."))
    }
    SettingsGroup(tr("Identify without contact", "Identifier sans contact")) {
        StepLine(1, tr("Drive the robot facing the obstacle", "Amener le robot face à l'obstacle"), tr("With the joystick (manual control), blades stopped, bumper close to the obstacle.", "Au joystick (pilotage manuel), lames arrêtées, pare-chocs près de l'obstacle."))
        StepLine(2, tr("Place the obstacle", "Placer l'obstacle"), tr("Touching the bumper by default; distance, type, radius and margin adjustable before saving. No robot movement.", "Au contact du pare-chocs par défaut ; distance, type, rayon et marge réglables avant d'enregistrer. Aucun mouvement du robot."))
        SecondaryPillButton(tr("Obstacle in front of the robot", "Obstacle devant le robot"), robot::proposeObstacleAhead, Modifier.fillMaxWidth(),
            enabled = state.telemetry.x != null && state.telemetry.y != null && state.telemetry.heading != null && !state.obstacleBusy())
        InfoNote(tr("While driving with the joystick, a bumper contact stops the robot and opens the obstacle sheet directly: keep it with its size, or dismiss it.",
            "En pilotage au joystick, un contact pare-chocs arrête le robot et ouvre directement la fiche de l'obstacle : le garder avec sa taille, ou l'écarter."))
    }
    state.teach.proposal?.let { ProposalEditor(robot, state, it) }
    TeachStatus(state, "Apprentissage d'obstacle")
}

@Composable
private fun ProposalEditor(robot: RobotBle, state: RobotUi, proposal: TeachProposal) {
    var kind by remember(proposal) { mutableStateOf(ObstacleKind.OTHER) }
    var radius by remember(proposal) { mutableFloatStateOf(proposal.radiusM.toFloat()) }
    var margin by remember(proposal) { mutableFloatStateOf(0.2f) }
    // Obstacle pointed at without contact: distance in front of the robot, adjustable.
    var ahead by remember(proposal) { mutableFloatStateOf((proposal.aheadM ?: 0.0).toFloat()) }
    var aheadSet by remember(proposal) { mutableStateOf(false) }
    // Until the distance is set by hand, the object stays against the bumper whatever its radius.
    fun resize(r: Float) { radius = r; if (proposal.aheadM != null && !aheadSet) ahead = ManualContact.defaultAheadM(r.toDouble()).toFloat() }
    val estimated = proposal.center(radius.toDouble(), proposal.aheadM?.let { ahead.toDouble() })
    val draft = LocalObstacle("proposal", kind, estimated.x, estimated.y, radius.toDouble(), margin.toDouble(), label = tr("Proposal", "Proposition"))
    SettingsGroup(tr("Proposal to check", "Proposition à vérifier")) {
        ObstacleMap(state, state.obstacles + draft, planned = listOf(Point(proposal.contact.x, proposal.contact.y), estimated), height = 240)
        ChipChoice(ObstacleKind.entries.map { it to it.label }, kind, { kind = it; resize(it.defaultRadiusM.toFloat()) })
        SliderRow(tr("Obstacle radius", "Rayon de l'obstacle"), radius, { resize(it) }, 0.05f..2f, valueLabel = "${Format.num(radius.toDouble(), 2)} m", steps = 38)
        SliderRow(tr("Safety margin", "Marge de sécurité"), margin, { margin = it }, 0f..2f, valueLabel = "${Format.num(margin.toDouble(), 2)} m", steps = 39)
        if (proposal.aheadM != null) SliderRow(tr("Center distance in front of the robot", "Distance du centre devant le robot"), ahead, { ahead = it; aheadSet = true },
            ManualContact.AHEAD_RANGE_M.start.toFloat()..ManualContact.AHEAD_RANGE_M.endInclusive.toFloat(), valueLabel = "${Format.num(ahead.toDouble(), 2)} m", steps = 79)
        KeyValueRow(if (proposal.aheadM != null) "Robot" else "Contact", "${Format.num(proposal.contact.x, 2)} / ${Format.num(proposal.contact.y, 2)} m · ${Format.num(proposal.contact.headingDeg, 0)}°")
        KeyValueRow(tr("Estimated center", "Centre estimé"), "${Format.num(estimated.x, 2)} / ${Format.num(estimated.y, 2)} m")
        KeyValueRow(tr("Avoided area", "Zone évitée"), tr("${Format.num(draft.keepOutDisc(RobotEnvelope.LUBA2).radiusM, 2)} m around", "${Format.num(draft.keepOutDisc(RobotEnvelope.LUBA2).radiusM, 2)} m autour"))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Ignore", "Ignorer"), robot::dismissTeachProposal, Modifier.weight(1f))
            PrimaryPillButton(tr("Save", "Enregistrer"), {
                val now = System.currentTimeMillis()
                robot.saveObstacle(LocalObstacle(UUID.randomUUID().toString(), kind, estimated.x, estimated.y,
                    radiusM = radius.toDouble(), marginM = margin.toDouble(), createdAtMs = now, updatedAtMs = now, label = kind.label.lowercase()))
                robot.dismissTeachProposal()
            }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ColumnScope.LearnActions(robot: RobotBle, state: RobotUi, supervised: Boolean) {
    if (state.teach.running) {
        SecondaryPillButton(tr("I heard the beep / touched the bumper", "J'ai entendu le bip / touché le pare-chocs"), robot::recordBumperBeep, Modifier.fillMaxWidth())
        DangerPillButton(tr("STOP · stop the approach", "STOP · arrêter l'approche"), robot::stopObstacleTest, Modifier.fillMaxWidth())
    } else PrimaryPillButton(tr("Slow approach until contact (1 m max)", "Approche lente jusqu'au contact (1 m max)"), { robot.startObstacleTest(true) }, Modifier.fillMaxWidth(),
        enabled = supervised && state.obstacleIdle(), icon = Icons.Rounded.PlayArrow)
}

// ───────────────────────────── List ─────────────────────────────

@Composable
private fun ObstacleMap(state: RobotUi, obstacles: List<LocalObstacle>, planned: List<Point> = emptyList(), trail: List<Point> = emptyList(), height: Int = 300) {
    val t = state.telemetry
    var cameraReset by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().height(height.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
        MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading, trail = trail, start = trail.firstOrNull(),
            obstacles = obstacles, planned = planned, interactive = true, cameraReset = cameraReset, modifier = Modifier.fillMaxSize())
        MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
private fun ObstacleListBench(robot: RobotBle, state: RobotUi, openSimulation: () -> Unit) {
    var editing by remember { mutableStateOf<LocalObstacle?>(null) }
    var deleting by remember { mutableStateOf<LocalObstacle?>(null) }
    var incidentFor by remember { mutableStateOf<LocalObstacle?>(null) }
    SettingsGroup(tr("Map", "Carte")) {
        ObstacleMap(state, state.obstacles)
        InfoNote(tr("Avoided by all local routes at radius + margin + robot turning (${Format.num(RobotEnvelope.LUBA2.turnClearanceM, 2)} m).", "Évité par tous les trajets locaux à rayon + marge + rotation du robot (${Format.num(RobotEnvelope.LUBA2.turnClearanceM, 2)} m)."))
    }
    SettingsGroup(tr("Saved obstacles (${state.obstacles.size})", "Obstacles enregistrés (${state.obstacles.size})")) {
        if (state.obstacles.isEmpty()) InfoNote(tr("No saved obstacle: learn one in Service → Obstacle tests, " +
            "or save an encountered obstacle below.", "Aucun obstacle enregistré : apprenez-en un dans Service → Essais d'obstacle, " +
            "ou enregistrez un obstacle rencontré ci-dessous."))
        state.obstacles.forEachIndexed { index, obstacle ->
            if (index > 0) LubaDivider()
            ObstacleRow(state, obstacle, { editing = obstacle }, { incidentFor = obstacle }, { deleting = obstacle })
        }
    }
    DifficultiesGroup(robot, state)
    ObstaclePhotosGroup(robot, state)
    SettingsGroup("Simulation") {
        NavRow(ServicePage.OBSTACLE_SIM.title, icon = Icons.Rounded.Science, subtitle = tr("Contact and back-off on the map, without the robot", "Contact et recul sur la carte, sans robot"), onClick = openSimulation)
    }
    InfoNote(tr("Obstacles saved on this phone only: never sent to the robot nor converted into Mammotion no-go zones.", "Obstacles enregistrés sur ce téléphone uniquement : jamais envoyés au robot ni convertis en zones interdites Mammotion."))
    deleting?.let { obstacle -> DeleteObstacleDialog(robot, state, obstacle) { deleting = null } }
    editing?.let { obstacle -> ObstacleEditor(obstacle, onDismiss = { editing = null }) { robot.saveObstacle(it); editing = null } }
    incidentFor?.let { obstacle -> IncidentDialog(robot, state, obstacle) { incidentFor = null } }
}

@Composable
private fun ObstacleRow(state: RobotUi, obstacle: LocalObstacle, edit: () -> Unit, incident: () -> Unit, delete: () -> Unit) {
    val colors = MammotionColors
    val t = state.telemetry
    Column(Modifier.padding(top = Dimens.Xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(obstacle.label.ifBlank { obstacle.kind.label }.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            if (t.x != null && t.y != null) Text(tr("${Format.num(Point(obstacle.x, obstacle.y).distance(Point(t.x, t.y)), 1)} m away", "à ${Format.num(Point(obstacle.x, obstacle.y).distance(Point(t.x, t.y)), 1)} m"),
                style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        }
        Text(tr("${Format.num(obstacle.x, 2)} / ${Format.num(obstacle.y, 2)} m · radius ${Format.num(obstacle.radiusM, 2)} m · margin ${Format.num(obstacle.marginM, 2)} m", "${Format.num(obstacle.x, 2)} / ${Format.num(obstacle.y, 2)} m · rayon ${Format.num(obstacle.radiusM, 2)} m · marge ${Format.num(obstacle.marginM, 2)} m"),
            style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        Text(Format.plural(obstacle.incidents.size, "incident", "incidents") + (obstacle.incidents.lastOrNull()?.let { tr(" · last: ${incidentKindLabel(it.kind)}", " · dernier : ${incidentKindLabel(it.kind)}") } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        if (obstacle.incidents.size >= 3) StatusNote(tr("Recurring incidents: adjust the position or the margin.", "Incidents récurrents : ajustez la position ou la marge."), NoteTone.WARNING)
        // Row actions, offset so the button text lines up with the record text.
        Row(Modifier.fillMaxWidth().offset(x = (-12).dp)) {
            TextAction(tr("Edit", "Modifier"), edit, enabled = !state.obstacleBusy())
            TextAction("Incident", incident, enabled = !state.obstacleBusy())
            Spacer(Modifier.weight(1f))
            TextAction(tr("Delete", "Supprimer"), delete, Modifier.offset(x = 24.dp), enabled = !state.obstacleBusy(), danger = true)
        }
    }
}

@Composable
private fun DeleteObstacleDialog(robot: RobotBle, state: RobotUi, obstacle: LocalObstacle, dismiss: () -> Unit) {
    LubaDialog(onDismissRequest = dismiss, title = tr("Delete this obstacle?", "Supprimer cet obstacle ?"), content = {
        Text(tr("${obstacle.kind.label} and its history will be deleted from this phone. It will no longer be avoided by future routes.", "${obstacle.kind.label} et son historique seront supprimés de ce téléphone. Il ne sera plus évité par les prochains trajets."))
        val contacts = fr.luba.poc.difficulty.PresumedObstacles.hiddenContacts(state.difficulties.events, obstacle,
            state.obstacles.filter { it.id != obstacle.id }, System.currentTimeMillis()).size
        if (contacts > 0) Text(tr("The $contacts bumper contacts recorded at this place will be dismissed too, so that it does not come back as a presumed obstacle.",
            "Les $contacts contacts pare-chocs consignés à cet endroit seront écartés aussi, pour qu'il ne revienne pas en obstacle présumé."))
    }, footer = {
        DangerPillButton(tr("Delete", "Supprimer"), { robot.deleteObstacle(obstacle.id); dismiss() }, Modifier.fillMaxWidth(), enabled = !state.obstacleBusy())
    })
}

@Composable
private fun IncidentDialog(robot: RobotBle, state: RobotUi, obstacle: LocalObstacle, dismiss: () -> Unit) {
    val t = state.telemetry
    var kind by remember(obstacle) { mutableStateOf("Contact bumper") }
    var note by remember(obstacle) { mutableStateOf("") }
    LubaDialog(onDismissRequest = dismiss, title = "Incident · ${obstacle.kind.label}", content = {
        ChipChoice(listOf("Contact bumper", "Frottement", "Collision", "Quasi-incident", "Observation").map { it to incidentKindLabel(it) }, kind, { kind = it })
        LubaTextField(note, { note = it.take(500) }, tr("Note (optional)", "Note (facultative)"), singleLine = false)
        KeyValueRow(tr("Robot position", "Position du robot"), if (t.x != null && t.y != null) "${Format.num(t.x, 2)} / ${Format.num(t.y, 2)} m" else tr("unavailable", "indisponible"))
    }, footer = {
        PrimaryPillButton(tr("Save the incident", "Enregistrer l'incident"), {
            robot.addObstacleIncident(obstacle.id, ObstacleIncident(atMs = System.currentTimeMillis(), kind = kind, note = note.trim(), x = t.x, y = t.y))
            dismiss()
        }, Modifier.fillMaxWidth())
    })
}

@Composable
private fun ObstacleEditor(obstacle: LocalObstacle, onDismiss: () -> Unit, onSave: (LocalObstacle) -> Unit) {
    var dx by remember(obstacle) { mutableFloatStateOf(0f) }
    var dy by remember(obstacle) { mutableFloatStateOf(0f) }
    var radius by remember(obstacle) { mutableFloatStateOf(obstacle.radiusM.toFloat()) }
    var margin by remember(obstacle) { mutableFloatStateOf(obstacle.marginM.toFloat()) }
    var kind by remember(obstacle) { mutableStateOf(obstacle.kind) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("Edit the obstacle", "Modifier l'obstacle"), content = {
        ChipChoice(ObstacleKind.entries.map { it to it.label }, kind, { kind = it })
        SliderRow(tr("East offset (+x)", "Décalage Est (+x)"), dx, { dx = it }, -5f..5f, valueLabel = "${Format.num(dx.toDouble(), 2)} m", steps = 99)
        SliderRow(tr("North offset (+y)", "Décalage Nord (+y)"), dy, { dy = it }, -5f..5f, valueLabel = "${Format.num(dy.toDouble(), 2)} m", steps = 99)
        KeyValueRow(tr("New position", "Nouvelle position"), "${Format.num(obstacle.x + dx, 2)} / ${Format.num(obstacle.y + dy, 2)} m")
        SliderRow(tr("Obstacle radius", "Rayon de l'obstacle"), radius, { radius = it }, 0.05f..2f, valueLabel = "${Format.num(radius.toDouble(), 2)} m", steps = 38)
        SliderRow(tr("Safety margin", "Marge de sécurité"), margin, { margin = it }, 0f..2f, valueLabel = "${Format.num(margin.toDouble(), 2)} m", steps = 39)
        KeyValueRow(tr("Avoided area", "Zone évitée"), tr("${Format.num(radius.toDouble() + margin.toDouble() + RobotEnvelope.LUBA2.turnClearanceM, 2)} m around", "${Format.num(radius.toDouble() + margin.toDouble() + RobotEnvelope.LUBA2.turnClearanceM, 2)} m autour"))
    }, footer = {
        PrimaryPillButton(tr("Save", "Enregistrer"), {
            onSave(obstacle.edited(x = obstacle.x + dx, y = obstacle.y + dy, radiusM = radius.toDouble(), marginM = margin.toDouble(),
                kind = kind, atMs = System.currentTimeMillis()))
        }, Modifier.fillMaxWidth())
    })
}

// ───────────────────────────── Bypass course ─────────────────────────────

@Composable
private fun CourseBench(robot: RobotBle, state: RobotUi, selected: LocalObstacle?, onSelect: (String) -> Unit,
                        supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    val nav = state.navTest
    val running = state.courseRunning()
    val t = state.telemetry
    // Preview on each obstacle choice (and when the map or the list change), never during the test.
    LaunchedEffect(selected?.id, state.ready, state.robotMap.complete, state.obstacles) {
        if (selected != null && state.ready && !robot.state.value.navTest.running) robot.previewObstacleCourse(selected.id)
    }
    if (selected == null) {
        SettingsGroup(tr("Target obstacle", "Obstacle visé")) { InfoNote(tr("No saved obstacle: learn one in the Learn tab.", "Aucun obstacle enregistré : apprenez-en un dans l'onglet Apprendre.")) }
        return
    }
    SettingsGroup(tr("Target obstacle", "Obstacle visé")) {
        ChipChoice(state.obstacles.map { o ->
            o.id to (o.label.ifBlank { o.kind.label }.replaceFirstChar { it.uppercase() } +
                (if (t.x != null && t.y != null) " · ${Format.num(Point(o.x, o.y).distance(Point(t.x, t.y)), 1)} m" else ""))
        }, selected.id, onSelect, enabled = !running)
        KeyValueRow(tr("Clearance (radius + margin + turning)", "Réserve (rayon + marge + rotation)"), "${Format.num(ObstacleCourse.keepOutM(selected), 2)} m")
    }
    val trail = if (nav.courseObstacleId == selected.id) nav.trail.map { Point(it.x, it.y) } else emptyList()
    SettingsGroup(tr("Planned course", "Parcours prévu")) {
        ObstacleMap(state, state.obstacles, planned = if (nav.courseObstacleId == selected.id) nav.planned else emptyList(), trail = trail)
        InfoNote(tr("Go to the opposite side and back: the straight line crosses the obstacle, the route must bypass it.", "Aller au côté opposé puis retour : la ligne droite traverse l'obstacle, le trajet doit le contourner."))
    }
    SettingsGroup(tr("Tracking", "Suivi")) {
        if (nav.courseObstacleId == selected.id) {
            if (nav.running || nav.status.startsWith(tr("Navigation refused", "Navigation refusée")) || nav.outcome != "Non démarrée")
                StatusNote(nav.status, navTone(nav.outcome, nav.running, refused = nav.status.startsWith(tr("Navigation refused", "Navigation refusée"))))
            if (!nav.running && nav.preview.isNotBlank()) {
                if (nav.preview.startsWith(tr("Route unavailable", "Parcours indisponible"))) StatusNote(nav.preview, NoteTone.DANGER) else InfoNote(nav.preview)
            }
        }
        val gapNow = if (t.x != null && t.y != null) Point(t.x, t.y).distance(Point(selected.x, selected.y)) - selected.radiusM else null
        KeyValueRow(tr("Current gap to the surface", "Écart actuel à la surface"), gapNow?.let { "${Format.num(it, 2)} m" } ?: "—")
        val minGap = ObstacleCourse.observedSurfaceGap(trail, selected)
        KeyValueRow(tr("Minimum observed gap", "Écart minimal observé"), minGap?.let { "${Format.num(it, 2)} m" } ?: "—",
            valueColor = when { minGap == null -> MammotionColors.TextPrimary; ObstacleCourse.passed(minGap) -> MammotionColors.GreenDeep; else -> MammotionColors.Danger })
        KeyValueRow("Point", if (running) "${nav.segmentIndex}/${nav.segmentCount}" else "—")
        KeyValueRow(tr("Distance traveled", "Distance parcourue"), "${Format.num(nav.observedMeters, 2)} m")
    }
    SettingsGroup(tr("Test settings", "Réglages de l'essai")) {
        SliderRow(tr("Forward speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
            valueLabel = "${state.trajectorySpeedPercent} %", enabled = !running)
        SecondaryPillButton(tr("Refresh the planned course", "Actualiser le parcours prévu"), { robot.previewObstacleCourse(selected.id) }, Modifier.fillMaxWidth(), enabled = state.ready && !nav.running)
        LubaCheckRow(tr("I am supervising on site, area cleared", "Je supervise sur place, zone dégagée"), supervised, onSupervised, enabled = !running)
    }
    InfoNote(tr("Start between ${Format.num(ObstacleCourse.keepOutM(selected) + ObstacleCourse.MIN_START_GAP_M, 1)} and " +
        "${ObstacleCourse.MAX_START_DISTANCE_M.toInt()} m from the centre. Passed if the robot's side (${Format.num(RobotEnvelope.LUBA2.halfWidthM, 2)} m) " +
        "never reached the obstacle surface. Deviation from the route, footprint outside the safe area or contact: STOP.",
        "Départ entre ${Format.num(ObstacleCourse.keepOutM(selected) + ObstacleCourse.MIN_START_GAP_M, 1)} et " +
        "${ObstacleCourse.MAX_START_DISTANCE_M.toInt()} m du centre. Réussi si le flanc du robot (${Format.num(RobotEnvelope.LUBA2.halfWidthM, 2)} m) " +
        "n'a jamais atteint la surface de l'obstacle. Écart au trajet, emprise hors surface sûre ou contact : STOP."))
    if (nav.courseObstacleId != null && nav.results.isNotEmpty()) SettingsGroup(tr("Results", "Résultats")) {
        nav.results.asReversed().take(30).forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
    }
}

@Composable
private fun ColumnScope.CourseActions(robot: RobotBle, state: RobotUi, selected: LocalObstacle?, supervised: Boolean) {
    if (state.courseRunning()) DangerPillButton(tr("STOP · stop the robot", "STOP · arrêter le robot"), { robot.stopNavTest(tr("STOP manual bypass", "STOP contournement manuel"), userInitiated = true) }, Modifier.fillMaxWidth())
    else PrimaryPillButton(tr("Start bypass", "Démarrer le contournement"), { selected?.let { robot.startObstacleCourse(it.id) } }, Modifier.fillMaxWidth(),
        enabled = selected != null && supervised && state.obstacleIdle() && state.robotMap.complete, icon = Icons.Rounded.PlayArrow)
}

/** Unrequested stop of the bypass: reason shown until acknowledged. */
@Composable
private fun CourseInterruptionDialog(robot: RobotBle, state: RobotUi) {
    val reason = state.navTest.interruption?.takeIf { state.navTest.courseObstacleId != null } ?: return
    LubaDialog(onDismissRequest = robot::acknowledgeNavInterruption, title = tr("Bypass interrupted", "Contournement interrompu"), content = {
        StatusNote(reason, NoteTone.DANGER)
        Text(tr("The robot received STOP. Distance traveled: ${Format.num(state.navTest.observedMeters, 2)} m.", "Le robot a reçu STOP. Distance parcourue : ${Format.num(state.navTest.observedMeters, 2)} m."),
            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    })
}

/** Photos taken after a bumper contact (back-off then photo, Mammotion camera stream). */
@Composable
private fun ObstaclePhotosGroup(robot: RobotBle, state: RobotUi) {
    val photo = state.obstaclePhoto
    var shown by remember { mutableStateOf<fr.luba.poc.camera.ObstaclePhoto?>(null) }
    SettingsGroup(tr("Photos after contact (${photo.photos.size})", "Photos après contact (${photo.photos.size})")) {
        // Local mode: no photo (the video only goes through the Mammotion cloud), the robot does not wait.
        val localOnly = fr.luba.poc.auth.AccountMode.observe()
        ToggleRow(tr("Photograph the obstacle after the back-off", "Photographier l'obstacle après le recul"), photo.enabled && !localOnly, robot::setObstaclePhotoEnabled,
            enabled = !localOnly,
            subtitle = if (localOnly) fr.luba.poc.auth.AccountMode.message else tr("After a contact, the robot backs off then waits stationary, blades stopped, up to " +
                "${fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS / 1000} s for the photo from its camera. Requires a signed-in Mammotion account and the screen on.",
                "Après un contact, le robot recule puis attend immobile, lames arrêtées, jusqu'à " +
                "${fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS / 1000} s la photo de sa caméra. Compte Mammotion connecté et écran allumé nécessaires."))
        fr.luba.poc.camera.ObstaclePhotoCameraChoice(robot, state)
        if (photo.status.isNotBlank()) KeyValueRow(tr("Last photo", "Dernière photo"), photo.status)
        if (photo.photos.isEmpty()) InfoNote(tr("No photo yet.", "Aucune photo pour l'instant."))
        photo.photos.take(12).forEach { p ->
            val near = state.obstacles.firstOrNull { o -> listOf(p).near(o.x, o.y, o.radiusM + o.marginM).isNotEmpty() }
            Row(Modifier.fillMaxWidth().clickable { shown = p }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                PhotoImage(p, Modifier.width(96.dp).aspectRatio(4f / 3f), maxSide = 320)
                Column(Modifier.weight(1f)) {
                    Text(java.text.SimpleDateFormat("dd/MM HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(p.takenAt)), style = MaterialTheme.typography.titleSmall)
                    Text(tr("Contact ${p.code ?: "?"} · photo ${p.delaySeconds} s after", "Contact ${p.code ?: "?"} · photo ${p.delaySeconds} s après") +
                        (if (p.x != null && p.y != null) " · ${Format.num(p.x, 1)} / ${Format.num(p.y, 1)} m" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                    near?.let { Text(tr("Near: ${it.label.ifBlank { it.kind.label }}", "Près de : ${it.label.ifBlank { it.kind.label }}"), style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary) }
                }
            }
        }
        InfoNote(tr("Photos are included in the diagnostics archive (Service → Logs → Export all).", "Les photos sont incluses dans l'archive de diagnostic (Service → Journaux → Tout exporter)."))
    }
    shown?.let { p ->
        LubaDialog(onDismissRequest = { shown = null }, title = tr("Photo after contact", "Photo après contact"), content = {
            PhotoImage(p, Modifier.fillMaxWidth().aspectRatio(4f / 3f), maxSide = 1280)
            KeyValueRow(tr("Taken", "Prise"), java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(p.takenAt)))
            KeyValueRow(tr("Delay after the contact", "Délai après le contact"), "${p.delaySeconds} s")
            if (p.x != null && p.y != null) KeyValueRow(tr("Contact position", "Position du contact"), "${Format.num(p.x, 2)} / ${Format.num(p.y, 2)} m")
        })
    }
}

/** JPEG image decoded off the main thread, downscaled to [maxSide] pixels. */
@Composable
internal fun PhotoImage(photo: fr.luba.poc.camera.ObstaclePhoto, modifier: Modifier, maxSide: Int) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photo.file, maxSide) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(photo.file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                android.graphics.BitmapFactory.decodeFile(photo.file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let { androidx.compose.foundation.Image(it, contentDescription = tr("Obstacle photo", "Photo de l'obstacle"), modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
            ?: Text("…", color = androidx.compose.ui.graphics.Color.White)
    }
}

// ───────────────────────────── Frontal border test ─────────────────────────────

private fun cm(meters: Double?): String = meters?.let { "${Format.num(it * 100, 0)} cm" } ?: "—"

/**
 * Border: the robot positions itself facing the chosen outline (point closest to it), moves slowly to the point where
 * passes end, waits for the visual check, then advances until bumper contact or the observed edge.
 */
@Composable
private fun BorderBench(robot: RobotBle, state: RobotUi, selectedId: Long?, onSelect: (Long) -> Unit,
                        supervised: Boolean, onSupervised: (Boolean) -> Unit) {
    val border = state.borderFrontal
    val choices = remember(state.robotMap) { BorderFrontal.choices(state.robotMap) }
    val t = state.telemetry
    val chosen = choices.firstOrNull { it.shapeId == selectedId } ?: choices.firstOrNull()
    // Preview on each outline choice and when the robot has moved at least 50 cm, never during the test.
    val moved = if (t.x != null && t.y != null) "${(t.x / 0.5).toInt()}:${(t.y / 0.5).toInt()}" else ""
    LaunchedEffect(chosen?.shapeId, state.ready, state.robotMap, state.obstacles, moved) {
        if (chosen != null && !robot.state.value.borderFrontal.running) robot.previewBorderFrontal(chosen.shapeId)
    }
    if (chosen == null) {
        SettingsGroup(tr("Target outline", "Contour visé")) { InfoNote(if (state.robotMap.complete) tr("No zone or no-go zone on the map.", "Aucune zone ni zone interdite sur la carte.") else tr("Read the robot map first.", "Lire la carte du robot d'abord.")) }
        return
    }
    SettingsGroup(tr("Target outline", "Contour visé")) {
        ChipChoice(choices.map { it.shapeId to "${it.label} · ${it.kind.label.lowercase()}" }, chosen.shapeId, onSelect, enabled = !border.running)
        InfoNote(tr("The target point is the point of this outline closest to the robot: first bring the robot near the spot to test.", "Le point visé est le plus proche du robot sur ce contour : approchez d'abord le robot de l'endroit à tester."))
    }
    val plan = border.plan?.takeIf { it.shapeId == chosen.shapeId }
    SettingsGroup(tr("Planned by the planner", "Prévu par le planificateur")) {
        val trail = if (border.running || border.outcome != "Non démarré") state.navTest.trail.map { Point(it.x, it.y) } else emptyList()
        ObstacleMap(state, state.obstacles, planned = plan?.let { listOf(it.entry, it.approach, it.passEnd, it.contact) } ?: emptyList(), trail = trail, height = 240)
        if (border.preview.isNotBlank() && !border.running) {
            if (border.previewRefused) StatusNote(border.preview, NoteTone.DANGER) else InfoNote(border.preview)
        }
        if (plan != null) {
            KeyValueRow(tr("Outer lap margin setting", "Marge du tour extérieur réglée"), cm(border.outerOffsetM))
            KeyValueRow(tr("Robot side on the lap", "Côté du robot sur le tour"), cm(border.outerOffsetM))
            KeyValueRow(tr("Center at the end of the pass", "Centre en bout de passe"), cm(plan.passEndM))
            KeyValueRow(tr("Bumper at the end of the pass", "Pare-chocs en bout de passe"), cm(plan.plannedFrontGapM))
            KeyValueRow(tr("Front corners at the pivot", "Coins avant au pivot"), cm(plan.plannedPivotGapM))
        }
    }
    SettingsGroup(tr("Tracking", "Suivi")) {
        if (border.running) LubaProgress(Modifier.fillMaxWidth())
        if (border.running || border.outcome != "Non démarré")
            StatusNote(border.status, when {
                border.running -> NoteTone.WARNING
                border.outcome == "PASS" -> NoteTone.SUCCESS
                border.outcome in setOf("ABORTED", "FAILED", "CONTACT_BEFORE_PASS_END") -> NoteTone.DANGER
                else -> NoteTone.INFO
            })
        KeyValueRow(tr("Step", "Étape"), border.phase.label)
        if (border.phase == BorderPhase.POSITIONING) KeyValueRow(tr("Move", "Déplacement"), state.navTest.status)
        KeyValueRow(tr("Bumper ↔ outline (live)", "Pare-chocs ↔ contour (direct)"), cm(border.liveFrontGapM))
        KeyValueRow(tr("Bumper at the end of the pass (measured)", "Pare-chocs en bout de passe (mesuré)"), cm(border.passEndFrontGapM))
        KeyValueRow(tr("Overshoot of the pass end point", "Dépassement du point de bout de passe"), cm(border.passEndOvershootM))
        KeyValueRow(tr("Bumper at the observed edge", "Pare-chocs au bord constaté"), cm(border.edgeFrontGapM))
    }
    SettingsGroup(tr("Procedure", "Déroulé")) {
        StepLine(1, tr("Positioning", "Positionnement"), tr("Go-to-point move, blades stopped, up to ${Format.num(BorderFrontal.SLOW_RUN_M * 100, 0)} cm before the end of the pass, facing the outline.", "Déplacement au point, lames arrêtées, jusqu'à ${Format.num(BorderFrontal.SLOW_RUN_M * 100, 0)} cm avant le bout de passe, face au contour."))
        StepLine(2, tr("Slow advance to the end of the pass", "Avance lente jusqu'au bout de passe"), tr("Stops where mowing passes end facing this outline. Visual check: does the front pass the real border?", "Arrêt là où les passes de tonte s'arrêtent face à ce contour. Contrôle visuel : l'avant dépasse-t-il la bordure réelle ?"))
        StepLine(3, tr("Advance to the edge (optional)", "Avance jusqu'au bord (facultatif)"), tr("Very slow, until bumper contact, the edge reported with “Edge reached”, or the bumper " +
            "${Format.num(fr.luba.poc.map.RecordedBoundary.PHYSICAL_TOLERANCE_M * 100, 0)} cm beyond the outline.", "Très lente, jusqu'au contact pare-chocs, au bord signalé par « Bord atteint », ou au pare-chocs " +
            "${Format.num(fr.luba.poc.map.RecordedBoundary.PHYSICAL_TOLERANCE_M * 100, 0)} cm au-delà du contour."))
        InfoNote(tr("Distances measured along the normal to the outline, from the center of rotation (published point + 8 cm). Positions at 1 Hz: a few centimetres of uncertainty.", "Distances mesurées sur la normale au contour, depuis le centre de rotation (point publié + 8 cm). Positions à 1 Hz : quelques centimètres d'incertitude."))
        LubaCheckRow(tr("I am supervising on site, in front of the border", "Je supervise sur place, devant la bordure"), supervised, onSupervised, enabled = !border.running)
    }
    if (border.results.isNotEmpty()) SettingsGroup(tr("Results", "Résultats")) {
        border.results.asReversed().take(20).forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
    }
}

@Composable
private fun ColumnScope.BorderActions(robot: RobotBle, state: RobotUi, selectedId: Long?, supervised: Boolean) {
    val border = state.borderFrontal
    when {
        border.phase == BorderPhase.PASS_END -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                PrimaryPillButton(tr("End of pass OK", "Bout de passe OK"), { robot.borderPassEndVerdict(true) }, Modifier.weight(1f))
                DangerPillButton(tr("Too far", "Trop loin"), { robot.borderPassEndVerdict(false) }, Modifier.weight(1f))
            }
            SecondaryPillButton(tr("Advance to the edge", "Avancer jusqu'au bord"), robot::continueBorderToEdge, Modifier.fillMaxWidth())
            DangerPillButton(tr("STOP · stop the test", "STOP · arrêter l'essai"), { robot.stopBorderFrontal() }, Modifier.fillMaxWidth())
        }
        border.phase == BorderPhase.EDGE -> {
            PrimaryPillButton(tr("Edge reached · stop and measure", "Bord atteint · arrêter et mesurer"), robot::borderEdgeSeen, Modifier.fillMaxWidth())
            DangerPillButton(tr("STOP · stop the test", "STOP · arrêter l'essai"), { robot.stopBorderFrontal() }, Modifier.fillMaxWidth())
        }
        border.running -> DangerPillButton(tr("STOP · stop the robot", "STOP · arrêter le robot"), { robot.stopBorderFrontal() }, Modifier.fillMaxWidth())
        else -> {
            val choices = BorderFrontal.choices(state.robotMap)
            val id = (choices.firstOrNull { it.shapeId == selectedId } ?: choices.firstOrNull())?.shapeId
            PrimaryPillButton(tr("Start the frontal test", "Lancer l'essai frontal"), { id?.let(robot::startBorderFrontal) }, Modifier.fillMaxWidth(),
                enabled = id != null && supervised && state.obstacleIdle() && state.robotMap.complete && !state.borderFrontal.previewRefused,
                icon = Icons.Rounded.PlayArrow)
        }
    }
}
