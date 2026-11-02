package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ble.SilenceTestUi
import fr.luba.poc.diagnostics.CampaignConfig
import fr.luba.poc.diagnostics.CommandSilenceProbe
import fr.luba.poc.diagnostics.MotorWatchdogResult
import fr.luba.poc.diagnostics.NavConfig
import fr.luba.poc.diagnostics.ProbeVision
import fr.luba.poc.diagnostics.SensorProbeSnapshot
import fr.luba.poc.diagnostics.SensorSlot
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.PathFollower
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.delay

/* Robot test pages of the Service tab (command cutoff, navigation, diagnostic campaign, sensors), opened from [RobotPanel]. */

private fun durationLabel(seconds: Int) = if (seconds < 120) "$seconds s" else "${seconds / 60} min"

/**
 * "Command cutoff" test: hold-to-run button. Slow straight-line move, then no movement command at all;
 * telemetry tells whether the firmware stops the robot on its own.
 */
@Composable
internal fun WatchdogPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    var linear by remember { mutableIntStateOf(30) }
    var confirmed by remember { mutableStateOf(false) }
    OnLeaveScreen(robot) { robot.stopSilenceTest(tr("Left the screen", "Sortie de l'écran")) }
    val test = state.silenceTest
    val enabled = confirmed && state.ready && !state.robotRoute.running && !state.manualActive && !state.mowing.active &&
        !state.navTest.running && !state.campaign.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading
    PushedPage(ServicePage.WATCHDOG.title, back, bottomBar = {
        HoldToRunButton(tr("Hold for the test (≈ 10 s)", "Maintenir pour l'essai (≈ 10 s)"), tr("Release = immediate STOP", "Relâcher = STOP immédiat"), test.running, enabled || test.running,
            onStart = { robot.startSilenceTest(linear) }, onStop = { robot.stopSilenceTest(tr("Button released", "Bouton relâché")) },
            onCancel = { robot.stopSilenceTest(tr("Gesture interrupted by the system", "Geste interrompu par le système")) })
    }) {
        header()
        SafetyNotice(tr("The robot really moves, then the app deliberately stops sending it commands. " +
            "It may keep going straight for up to ${Format.num(CommandSilenceProbe.MAX_DRIFT_M, 1)} m: " +
            "${Format.num(CommandSilenceProbe.CLEARANCE_M, 1)} m clear in front of it, no people or animals, physical stop within reach.",
            "Le robot roule réellement, puis l'application cesse volontairement de lui envoyer des commandes. " +
            "Il peut continuer tout droit jusqu'à ${Format.num(CommandSilenceProbe.MAX_DRIFT_M, 1)} m : " +
            "${Format.num(CommandSilenceProbe.CLEARANCE_M, 1)} m dégagés devant lui, personne ni animal, arrêt physique à portée de main."))
        SettingsGroup(title = tr("Last result", "Dernier résultat")) {
            val result = state.motorWatchdog
            if (result == null) StatusNote(tr("No test for this robot: the robot stopping on its own when the app goes silent is not demonstrated.", "Aucun essai pour ce robot : l'arrêt seul du robot quand l'application se tait n'est pas démontré."), tone = NoteTone.WARNING)
            else {
                StatusNote(if (result.demonstrated) tr("Motor watchdog demonstrated", "Watchdog moteur démontré") else when (result.outcome) {
                    MotorWatchdogResult.ROBOT_STOPPED -> tr("Stops on its own, but too slowly", "Arrêt seul, mais trop lent")
                    MotorWatchdogResult.STILL_MOVING -> tr("Does not stop on its own: app STOP required", "Pas d'arrêt seul : STOP de l'application nécessaire")
                    else -> tr("Inconclusive test", "Essai non concluant")
                }, tone = if (result.demonstrated) NoteTone.SUCCESS else NoteTone.DANGER)
                Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                KeyValueRow("Date", Format.time(result.at))
                KeyValueRow("Firmware", result.firmware ?: tr("unknown", "inconnu"))
                KeyValueRow(tr("Commanded speed", "Vitesse commandée"), tr("${result.linearRaw} units", "${result.linearRaw} unités"))
                result.boundMs?.let { KeyValueRow(tr("Stop at the latest", "Arrêt au plus tard"), tr("${Format.num(it / 1000.0, 1)} s after the last command", "${Format.num(it / 1000.0, 1)} s après la dernière commande")) }
                result.driftM?.let { KeyValueRow(tr("Distance after the last moving position", "Distance après la dernière position en mouvement"), "${Format.num(it * 100, 0)} cm") }
                result.speedMps?.let { KeyValueRow(tr("Observed speed", "Vitesse observée"), "${Format.num(it, 2)} m/s") }
            }
        }
        SettingsGroup(title = tr("Test settings", "Réglages de l'essai")) {
            // Default "No test": already stated by the last result.
            if (test.status.isNotBlank() && test.status != SilenceTestUi().status)
                StatusNote(test.status, tone = if (test.running) NoteTone.WARNING else NoteTone.INFO)
            if (test.running) KeyValueRow("Phase", test.phase)
            test.driftM?.let { KeyValueRow(tr("Deviation since the cutoff", "Écart depuis la coupure"), "${Format.num(it * 100, 0)} cm") }
            SliderRow(tr("Forward speed", "Vitesse d'avance"), linear.toFloat(), { linear = it.toInt() }, 10f..120f, valueLabel = tr("$linear units", "$linear unités"), steps = 10, enabled = !test.running)
            LubaCheckRow(tr("I am supervising on site, ${Format.num(CommandSilenceProbe.CLEARANCE_M, 1)} m clear in front of the robot", "Je supervise sur place, ${Format.num(CommandSilenceProbe.CLEARANCE_M, 1)} m dégagés devant le robot"), confirmed, { confirmed = it }, enabled = !test.running)
        }
        InfoNote(tr("Sequence: 3 s of slow forward movement with the blades stopped, then no movement command at all (no speed, no STOP). " +
            "Stopping on its own is recorded after three stationary positions; otherwise the app sends STOP after " +
            "${CommandSilenceProbe.MAX_SILENCE_MS / 1000} s or ${Format.num(CommandSilenceProbe.MAX_DRIFT_M * 100, 0)} cm. " +
            "Positions at 1 Hz: delay bounded to the nearest second. Demonstrated if the stop happens within " +
            "${Format.num(CommandSilenceProbe.ACCEPTABLE_BOUND_MS / 1000.0, 1)} s.",
            "Déroulé : 3 s d'avance lente lames arrêtées, puis plus aucune commande de mouvement (ni vitesse, ni STOP). " +
            "Arrêt seul constaté après trois positions immobiles ; sinon STOP de l'application après " +
            "${CommandSilenceProbe.MAX_SILENCE_MS / 1000} s ou ${Format.num(CommandSilenceProbe.MAX_DRIFT_M * 100, 0)} cm. " +
            "Positions à 1 Hz : délai borné à la seconde près. Démontré si l'arrêt intervient en " +
            "${Format.num(CommandSilenceProbe.ACCEPTABLE_BOUND_MS / 1000.0, 1)} s au plus."))
        InfoNote(tr("Simulates a frozen app with Bluetooth kept up. A complete loss of link (phone off, out of range) is not covered: observe it separately, physical stop within reach.", "Simule une application figée, Bluetooth maintenu. Une perte complète de liaison (téléphone éteint, hors de portée) n'est pas couverte : à observer à part, arrêt physique à portée."))
    }
}

@Composable
internal fun NavigationPage(robot: RobotBle, state: RobotUi, back: () -> Unit, busy: Boolean, header: @Composable ColumnScope.() -> Unit) {
    var angular by remember { mutableIntStateOf(300) }
    var automatic by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(300) }
    var radius by remember { mutableFloatStateOf(5f) }
    var confirmed by remember { mutableStateOf(false) }
    var cameraReset by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.ready, state.robotMap.complete, radius) {
        while (true) {
            if (state.ready) robot.previewNavTest(radius.toDouble())
            delay(2000)
        }
    }
    OnLeaveScreen(robot) { robot.stopNavTest(tr("Left the Navigation screen", "Sortie écran Navigation"), userInitiated = true) }
    state.navTest.interruption?.let { reason ->
        LubaDialog(onDismissRequest = robot::acknowledgeNavInterruption, title = tr("Navigation interrupted", "Navigation interrompue"), content = {
            Text(reason)
            Text(tr("The robot received STOP. Distance covered: ${Format.num(state.navTest.observedMeters, 2)} m.", "Le robot a reçu STOP. Distance parcourue : ${Format.num(state.navTest.observedMeters, 2)} m."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }, footer = {
            PrimaryPillButton(tr("Got it", "Compris"), robot::acknowledgeNavInterruption, Modifier.fillMaxWidth())
        })
    }
    val t = state.telemetry
    val enabled = !state.robotRoute.running && !state.manualActive && !state.mowing.active && confirmed && state.ready &&
        !state.campaign.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading
    fun config(auto: Boolean) = NavConfig(linearSpeed = PathFollower.linearFor(state.trajectorySpeedPercent), angularSpeed = angular, tonte = false,
        heightMm = t.bladeHeightMm ?: 60, maxDurationMs = duration * 1000L, maxRadiusM = radius.toDouble(), automatic = auto)
    PushedPage(ServicePage.NAVIGATION.title, back, bottomBar = {
        if (automatic) {
            if (state.navTest.running) StopAction({ robot.stopNavTest(tr("STOP manual run", "STOP marche manuel"), userInitiated = true) }, Modifier.align(Alignment.End))
            else PrimaryPillButton(tr("Start · ${durationLabel(duration)} · blades stopped", "Démarrer · ${durationLabel(duration)} · lames arrêtées"), { robot.startNavTest(config(true)) }, Modifier.fillMaxWidth(),
                enabled = enabled, icon = Icons.Rounded.PlayArrow)
        } else HoldToRunButton(tr("Hold to drive (${durationLabel(duration)} max)", "Maintenir pour rouler (${durationLabel(duration)} max)"), tr("Release to stop", "Relâcher pour arrêter"), state.navTest.running, enabled,
            onStart = { robot.startNavTest(config(false)) }, onStop = { robot.stopNavTest(tr("Button released", "Bouton relâché"), userInitiated = true) },
            onCancel = { robot.stopNavTest(tr("Gesture interrupted by the system (finger left the button or navigation gesture)", "Geste interrompu par le système (doigt sorti du bouton ou geste de navigation)")) })
    }) {
        header()
        SafetyNotice(tr("The robot really moves. Clear area, no people or animals, physical stop within reach. Automatic stop if tracking deviates by more than 30 cm.", "Le robot roule réellement. Zone dégagée, personne ni animal, arrêt physique accessible. Arrêt automatique si le suivi s'écarte de plus de 30 cm."))
        SettingsGroup(title = tr("Planned route", "Parcours prévu")) {
            Box(Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
                MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                    trail = state.navTest.trail.map { Point(it.x, it.y) }, start = state.navTest.trail.firstOrNull()?.let { Point(it.x, it.y) },
                    obstacles = state.obstacles, planned = state.navTest.planned, plannedBands = state.navTest.plannedBands,
                    plannedBorders = state.navTest.plannedBorders, interactive = true, cameraReset = cameraReset, modifier = Modifier.fillMaxSize())
                MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (t.x == null || t.y == null) StatusNote(tr("Robot position unavailable: test impossible.", "Position du robot indisponible : essai impossible."), tone = NoteTone.WARNING)
            if (state.navTest.status.isNotBlank()) StatusNote(state.navTest.status,
                tone = when { state.navTest.outcome in setOf("ABORTED", "FAILED") -> NoteTone.DANGER; state.navTest.running -> NoteTone.WARNING; else -> NoteTone.INFO })
            if (!state.navTest.running && state.navTest.preview.isNotBlank()) InfoNote(state.navTest.preview)
            KeyValueRow("Segment", if (state.navTest.running) "${state.navTest.segmentIndex}/${state.navTest.segmentCount}" else "—")
            KeyValueRow(tr("Distance covered", "Distance parcourue"), "${Format.num(state.navTest.observedMeters, 2)} m")
            KeyValueRow(tr("Observed stop delay", "Délai d'arrêt observé"), state.navTest.stopDelayMs?.let { "$it ms" } ?: "—")
            TextAction(tr("Refresh the planned route", "Actualiser le parcours prévu"), { robot.previewNavTest(radius.toDouble()) }, Modifier.offset(x = (-12).dp), enabled = state.ready && !state.navTest.running)
        }
        SettingsGroup(title = tr("Test settings", "Réglages de l'essai")) {
            Text(tr("Maximum duration", "Durée maximale"), style = MaterialTheme.typography.bodyMedium)
            Segmented(listOf(60, 120, 300, 600).map { it to durationLabel(it) }, duration, { duration = it }, enabled = !state.navTest.running)
            SliderRow(tr("Forward speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
                valueLabel = "${state.trajectorySpeedPercent} %")
            SliderRow(tr("Maximum rotation speed", "Vitesse de rotation maximale"), angular.toFloat(), { angular = it.toInt() }, 60f..450f,
                valueLabel = "≈ ${(angular * 0.12).toInt()} °/s", steps = 38, enabled = !state.navTest.running)
            SliderRow(tr("Maximum radius around the start", "Rayon maximal autour du départ"), radius, { radius = it }, 2f..15f,
                valueLabel = "${Format.num(radius.toDouble())} m", steps = 12, enabled = !state.navTest.running)
            ToggleRow(tr("Automatic start", "Démarrage automatique"), automatic, { automatic = it }, subtitle = tr("Without holding the button", "Sans maintenir le bouton"), enabled = !state.navTest.running)
            LubaCheckRow(tr("I am supervising on site, area clear", "Je supervise sur place, zone dégagée"), confirmed, { confirmed = it }, enabled = !state.navTest.running)
        }
        if (state.navTest.results.isNotEmpty()) SettingsGroup(title = tr("Results", "Résultats")) {
            state.navTest.results.asReversed().forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
        }
        InfoNote(tr("Three short stripes, a nearby border and exclusion if reachable, starting from the actual position with a 0.9 m margin. " +
            "Speeds in robot units, not calibrated: start low.",
            "Trois bandes courtes, une bordure et une exclusion proches si accessibles, départ à la position réelle avec une marge de 0,9 m. " +
            "Vitesses en unités du robot, non étalonnées : commencez bas."))
    }
}

@Composable
internal fun CampaignPage(robot: RobotBle, state: RobotUi, back: () -> Unit, exportArchive: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    var duration by remember { mutableIntStateOf(30) }
    var zeros by remember { mutableStateOf(true) }
    var cycles by remember { mutableStateOf(true) }
    var exception by remember { mutableStateOf(true) }
    var watchdog by remember { mutableStateOf(true) }
    var confirmed by remember { mutableStateOf(false) }
    val running = state.campaign.running
    PushedPage(ServicePage.CAMPAIGN.title, back, bottomBar = {
        PrimaryPillButton(tr("Run diagnostics", "Lancer le diagnostic"), { robot.startCampaign(CampaignConfig(duration, zeros, cycles, watchdog, exception = exception)) },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow,
            enabled = confirmed && state.ready && !state.robotRoute.running && !state.manualActive && !running && !state.mowing.active &&
                !state.navTest.running && !state.teach.running && !state.bladeTestActive)
    }) {
        header()
        SafetyNotice(tr("Robot stationary, area clear, blades stopped. Link, zero commands, interruptions and reconnections follow one another " +
            "in a single visit (about 2 min), then monitoring.",
            "Robot immobile, zone dégagée, lames arrêtées. Liaison, commandes nulles, coupures et reconnexions s'enchaînent " +
            "en une seule visite (environ 2 min), puis surveillance."))
        SettingsGroup(title = tr("Tested cases", "Cas testés")) {
            LubaCheckRow(tr("30 s of zero commands at 5 Hz", "30 s de commandes nulles à 5 Hz"), zeros, { zeros = it }, enabled = !running)
            LubaCheckRow(tr("3 STOP / reconnection cycles", "3 cycles STOP / reconnexion"), cycles, { cycles = it }, enabled = !running)
            LubaCheckRow(tr("Hide reports (monitoring)", "Masquer les rapports (surveillance)"), watchdog, { watchdog = it }, enabled = !running)
            LubaCheckRow(tr("Local error → STOP → reconnection", "Erreur locale → STOP → reconnexion"), exception, { exception = it }, enabled = !running)
        }
        SettingsGroup(title = tr("Test settings", "Réglages de l'essai")) {
            Text(tr("Final monitoring", "Surveillance finale"), style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
            ChipChoice(listOf(1, 5, 10, 30, 60, 0).map { it to if (it == 0) tr("Unlimited", "Illimitée") else "$it min" }, duration, { duration = it }, enabled = !running)
            LubaCheckRow(tr("Robot stationary, blades stopped: I am supervising", "Robot immobile, lames arrêtées : je supervise"), confirmed, { confirmed = it }, enabled = !running)
        }
        SettingsGroup(title = tr("Results", "Résultats")) {
            KeyValueRow(tr("Status", "Statut"), "${state.campaign.outcome} · ${Format.duration(state.campaign.elapsedMs)}")
            if (state.campaign.step.isNotBlank()) KeyValueRow(tr("Current case", "Cas en cours"), state.campaign.step)
            if (state.campaign.summary.isNotBlank()) Text(state.campaign.summary, style = MaterialTheme.typography.bodySmall)
            if (state.diagnosticSummary.isNotBlank()) Text(state.diagnosticSummary, style = MaterialTheme.typography.bodySmall)
            state.campaign.results.forEach { ResultRow(it.outcome, it.title, detail = it.detail, meta = Format.duration(it.durationMs)) }
            SecondaryPillButton(tr("Export (ZIP)", "Exporter (ZIP)"), exportArchive, Modifier.fillMaxWidth(), enabled = !running, icon = Icons.Rounded.IosShare)
        }
        SettingsGroup(title = tr("Tests without robot", "Tests sans robot")) {
            KeyValueRow(tr("Passed", "Réussis"), "${state.localResults.count { it.outcome == "PASS LOCAL" }}/${state.localResults.size}")
            state.localResults.forEach { ResultRow(it.outcome, it.title) }
            SecondaryPillButton(tr("Run tests without robot", "Lancer les tests sans robot"), robot::runLocalChecks, Modifier.fillMaxWidth(), enabled = !running)
        }
        SettingsGroup(title = tr("Diagnostics history", "Historique des diagnostics")) {
            if (state.history.isNotBlank()) LogLines(state.history.lines().filter { it.isNotBlank() })
            TextAction(if (state.history.isBlank()) tr("Show the history", "Afficher l'historique") else tr("Refresh the history", "Actualiser l'historique"), robot::refreshHistory)
        }
        InfoNote(tr("A successful diagnostic validates neither a movement, nor mechanical stopping, nor a real radio loss.", "Un diagnostic réussi ne valide ni un déplacement, ni l'arrêt mécanique, ni une vraie perte radio."))
    }
}

/** Raw data entry (stream, field, report): name and counter, then abbreviated value. */
@Composable
private fun DataEntry(title: String, detail: String, maxLines: Int = 3) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Decoded states of the bumper and the 4 ultrasonic sensors (`sensor_status`, 3 bits each), live and during the capture. */
@Composable
private fun SensorSlotsGroup(sensorStatus: Int?, probe: SensorProbeSnapshot, bumperRest: Int = 0) {
    SettingsGroup(title = tr("Ultrasonic sensors and bumper (decoded sensor_status)", "Ultrasons et bumper (sensor_status décodé)")) {
        SensorSlot.entries.forEach { slot ->
            val live = sensorStatus?.let(slot::raw)
            val captured = probe.slots.firstOrNull { it.slot == slot }
            val history = captured?.takeIf { it.current != null }?.let { tr(" · max ${it.max} · ${it.changes} change(s)", " · max ${it.max} · ${it.changes} changement(s)") } ?: ""
            KeyValueRow(slot.label, SensorSlot.stateLabel(live) + history)
        }
        InfoNote(tr("0 = OK, 1 = warning, 2 to 7 = error or obstacle (Mammotion app decoding via PyMammotion).", "0 = OK, 1 = alerte, 2 à 7 = erreur ou obstacle (décodage de l'app Mammotion via PyMammotion)."))
        if (bumperRest != 0) InfoNote(tr("Bumper at $bumperRest since the connection, robot at rest, no contact: taken as this robot's resting value and ignored by the safety checks until it changes. Contacts (code 1014, self-check 25) still stop the robot.",
            "Pare-chocs à $bumperRest depuis la connexion, robot au repos, sans contact : pris comme valeur de repos de ce robot et ignoré par les contrôles de sécurité tant qu'il ne change pas. Les contacts (code 1014, self-check 25) arrêtent toujours le robot."))
    }
}

@Composable
private fun SensorVisionGroup(vision: ProbeVision) {
    SettingsGroup(title = tr("Camera perception (vision stream)", "Perception caméra (flux vision)")) {
        KeyValueRow(tr("Obstacle points (report / tunnel)", "Points d'obstacle (rapport / tunnel)"), "${vision.reportPoints} / ${vision.tunnelPoints}")
        KeyValueRow(tr("Visual odometry (VIO)", "Odométrie visuelle (VIO)"), vision.vio.toString())
        KeyValueRow(tr("Vision statistics", "Statistiques vision"), vision.statistics.toString())
        listOf(tr("Latest points", "Derniers points") to vision.lastPoints, tr("Latest VIO", "Dernière VIO") to vision.lastVio, tr("Latest statistics", "Dernières statistiques") to vision.lastStatistics)
            .filter { it.second.isNotBlank() }.forEach { (label, text) -> DataEntry(label, text, maxLines = 4) }
    }
}

@Composable
internal fun SensorsPage(robot: RobotBle, state: RobotUi, back: () -> Unit, exportArchive: () -> Unit) {
    val probe = state.sensorProbe
    PushedPage(ServicePage.SENSORS.title, back, bottomBar = {
        if (probe.running) PrimaryPillButton(tr("Stop the capture", "Arrêter la capture"), robot::stopSensorProbe, Modifier.fillMaxWidth())
        else PrimaryPillButton(tr("Start the capture", "Démarrer la capture"), robot::startSensorProbe, Modifier.fillMaxWidth(), enabled = state.ready, icon = Icons.Rounded.PlayArrow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Reset", "Remettre à zéro"), robot::resetSensorProbe, Modifier.weight(1f), enabled = !probe.running)
            SecondaryPillButton(tr("Export", "Exporter"), exportArchive, Modifier.weight(1f), icon = Icons.Rounded.IosShare)
        }
    }) {
        SafetyNotice(tr("No movement command: the capture only adds the vision streams to the telemetry (and, on request, firmware debugging). " +
            "Robot stationary, blades stopped, under supervision: place an obstacle in front of it, then move it.",
            "Aucune commande de mouvement : la capture ajoute seulement les flux vision à la télémétrie (et, sur demande, le débogage firmware). " +
            "Robot immobile, lames arrêtées, sous supervision : placez un obstacle devant lui puis déplacez-le."))
        InfoNote(tr("With the robot stopped, the firmware probably publishes neither ultrasonic nor perception data. The capture keeps running in the background: " +
            "start it, then run a route (signal survey, manual control with the joystick) and pass an obstacle in front of the robot before coming back here.",
            "Robot à l'arrêt, le firmware ne publie sans doute ni ultrasons ni perception. La capture continue en arrière-plan : " +
            "démarrez-la, puis lancez un trajet (relevé de signaux, pilotage manuel au joystick) et faites passer un obstacle devant le robot, avant de revenir ici."))
        if (!state.ready) StatusNote(tr("Robot not connected: connect it over Bluetooth to start the capture.", "Robot non connecté : connectez-le en Bluetooth pour lancer la capture."), tone = NoteTone.WARNING)
        SettingsGroup(title = tr("Result", "Résultat")) {
            KeyValueRow("Verdict", probe.verdict)
            KeyValueRow(tr("Messages received", "Messages reçus"), probe.messages.toString())
            KeyValueRow(tr("Robot states (sys_status × reports)", "États du robot (sys_status × rapports)"), probe.robotStatesLabel)
            KeyValueRow(tr("Perception (PEPT / DEV_PERCEPTION type)", "Perception (type PEPT / DEV_PERCEPTION)"), "${probe.peptPayloads} / ${probe.perceptionMessages}")
            KeyValueRow(tr("Fields unknown to the schema", "Champs inconnus du schéma"), probe.unknown.size.toString())
        }
        SettingsGroup(title = tr("Firmware debugging", "Débogage firmware")) {
            ToggleRow(tr("Robot debug reports", "Rapports de débogage du robot"), probe.debugEnabled, robot::setSensorProbeDebug, enabled = probe.running && state.ready,
                subtitle = tr("debug_enable=1 during the capture (reset to 0 when it stops): the firmware can then push module/key/value reports.", "debug_enable=1 le temps de la capture (remis à 0 à l'arrêt) : le firmware peut alors pousser des rapports module/clé/valeur."))
            if (probe.debugEntries.isEmpty()) InfoNote(tr("No debug report received.", "Aucun rapport de débogage reçu."))
            probe.debugEntries.takeLast(40).forEach { DataEntry("${it.source} · ${it.key} ×${it.count}", it.value) }
        }
        SettingsGroup(title = tr("Perception bridge (ZMQ, Wi-Fi)", "Pont perception (ZMQ, Wi-Fi)")) {
            val z = probe.zmq
            ToggleRow(tr("Robot DDS → ZMQ bridge", "Pont DDS → ZMQ du robot"), z.enabled, robot::setSensorProbeZmq, enabled = probe.running && state.ready,
                subtitle = tr("Mammotion app debug command: the robot republishes perception_post_result on its port 5555, " +
                    "listened to here over Wi-Fi (phone on the same network as the robot). Turned off when the capture stops.",
                    "Commande de débogage de l'app Mammotion : le robot republie perception_post_result sur son port 5555, " +
                    "écouté ici en Wi-Fi (téléphone sur le même réseau que le robot). Désactivé à l'arrêt de la capture."))
            KeyValueRow(tr("Status", "État"), z.status.ifEmpty { tr("Inactive", "Inactif") })
            if (z.network.isNotEmpty()) KeyValueRow(tr("Robot network", "Réseau du robot"), z.network)
            if (z.peer.isNotEmpty()) KeyValueRow(tr("Robot socket", "Socket du robot"), z.peer)
            KeyValueRow(tr("Messages received", "Messages reçus"), if (z.messages == 0L) "0" else tr("${z.messages} · ${z.bytes} B · ${z.minSize}..${z.maxSize} B", "${z.messages} · ${z.bytes} o · ${z.minSize}..${z.maxSize} o"))
            z.samples.forEach { DataEntry("Message", it, maxLines = 3) }
        }
        PerceptionMapGroup(state.perceptionMap)
        SensorSlotsGroup(state.telemetry.sensorStatus, probe, state.bumperRest)
        SensorVisionGroup(probe.vision)
        SettingsGroup(title = tr("Vision, SLAM and camera", "Vision, SLAM et caméra")) {
            val e = probe.extras
            KeyValueRow(tr("Vision state (tunnel 26, index 16)", "État vision (tunnel 26, index 16)"), e.visionState?.toString() ?: "—")
            KeyValueRow(tr("Localisation fusion", "Fusion de localisation"), e.fuseStatus?.toString() ?: "—")
            KeyValueRow(tr("Visual SLAM (vslam_status)", "SLAM visuel (vslam_status)"), e.vslamStatus?.toString() ?: "—")
            KeyValueRow(tr("VIO survival", "Survie VIO"), e.vioSurvivalM?.let { "%.2f m".format(it) } ?: "—")
            KeyValueRow("FPV (fpv_flag · Wi-Fi · 4G)", e.fpvFlag?.let { "$it · ${e.fpvWifi} · ${e.fpvMobile}" } ?: "absent")
            KeyValueRow(tr("Perception obstacles (PEPT)", "Obstacles de perception (PEPT)"), e.obstacleMessages.toString())
            if (e.lastObstacles.isNotBlank()) DataEntry(tr("Latest obstacles", "Derniers obstacles"), e.lastObstacles, maxLines = 4)
        }
        if (probe.arrays.isNotEmpty()) SettingsGroup(title = tr("Raw tunnels (varying indices)", "Tunnels bruts (indices qui varient)")) {
            probe.arrays.forEach {
                DataEntry(tr("${it.name} · ${it.length} values ×${it.count}", "${it.name} · ${it.length} valeurs ×${it.count}"), if (it.varying.isEmpty()) tr("No index varies", "Aucun indice ne varie") else it.varying.joinToString("  "), maxLines = 4)
            }
            InfoNote(tr("Move the obstacle closer, then away: an index that follows the distance will appear here (position and heading also vary when moving).", "Approchez puis éloignez l'obstacle : un indice qui suit la distance apparaîtra ici (la position et le cap varient aussi en mouvement)."))
        }
        SettingsGroup(title = tr("Sensor code (sensor_status)", "Code capteurs (sensor_status)")) {
            if (probe.sensorValues.isEmpty()) InfoNote(tr("No value received.", "Aucune valeur reçue."))
            probe.sensorValues.forEach { KeyValueRow("${it.value}", "${it.bits} · ×${it.count}") }
        }
        SettingsGroup(title = tr("Streams received (${probe.streams.size})", "Flux reçus (${probe.streams.size})")) {
            if (probe.streams.isEmpty()) InfoNote(tr("No stream received.", "Aucun flux reçu."))
            probe.streams.forEach { DataEntry("${it.key} ×${it.count}", it.lastSummary) }
        }
        if (probe.unknown.isNotEmpty()) SettingsGroup(title = tr("Unknown fields", "Champs inconnus")) {
            probe.unknown.forEach { DataEntry("${it.path} ×${it.count}", it.lastFields, maxLines = Int.MAX_VALUE) }
        }
        if (probe.keywordHits.isNotEmpty()) SettingsGroup(title = tr("Fields suggesting ultrasonic / distance", "Champs évoquant ultrason / distance")) {
            LogLines(probe.keywordHits)
        }
        if (probe.lastPerception.isNotBlank()) SettingsGroup(title = tr("Last perception message", "Dernier message de perception")) {
            LogLines(listOf(probe.lastPerception))
        }
    }
}
