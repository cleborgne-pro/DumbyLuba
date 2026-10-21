package fr.luba.poc.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import fr.luba.poc.auth.CameraCommand
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.i18n.tr

/** Camera used for the obstacle photo (saved setting, shared by the Camera and Obstacles pages). */
@Composable
fun ObstaclePhotoCameraChoice(robot: RobotBle, state: RobotUi) {
    val camera = state.obstaclePhoto.camera
    val enabled = state.obstaclePhoto.enabled && !fr.luba.poc.auth.AccountMode.observe()
    Text(tr("Photo camera", "Caméra de la photo"), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        ChoiceTile(Icons.Rounded.Videocam, tr("Front / left", "Avant / gauche"), camera == ObstaclePhotoUi.CAMERA_FRONT,
            { robot.setObstaclePhotoCamera(ObstaclePhotoUi.CAMERA_FRONT) }, Modifier.weight(1f), enabled = enabled)
        ChoiceTile(Icons.Rounded.Videocam, tr("Right", "Droite"), camera == ObstaclePhotoUi.CAMERA_RIGHT,
            { robot.setObstaclePhotoCamera(ObstaclePhotoUi.CAMERA_RIGHT) }, Modifier.weight(1f), enabled = enabled)
    }
    Text(if (camera == ObstaclePhotoUi.CAMERA_RIGHT) tr("The right camera is requested together with the front camera; without an image from it, the front camera is the fallback at the end of the wait.", "La caméra droite est demandée avec la caméra avant ; sans image d'elle, la caméra avant sert de secours en fin d'attente.")
        else tr("Front camera, like the Mammotion app.", "Caméra avant, comme l'app Mammotion."), style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
}

/** Choice of the displayed camera. */
object CameraChoice {
    /** The right camera (uid 2) is only published if the token requests it: restart needed if the current stream does not include it. */
    fun needsRestart(target: Int, playing: Boolean, busy: Boolean, sessionBothFront: Boolean): Boolean =
        target == 2 && playing && !busy && !sessionBothFront
}

/**
 * Camera (debug): robot video stream through Agora, like the Mammotion app and Mammotion-HA.
 *
 * 1. `stream/token` token requested from the Mammotion cloud (account connected): on recent firmware (`fpv_info`),
 *    this request starts publishing; otherwise `vi_switch=1` is sent to the multimedia module.
 * 2. The shared player ([CameraController], driven by [CameraHost]) joins the channel as an audience member and
 *    shows the chosen camera (uid 1 or 2); the page shows that same player.
 * 3. Over 4G, `refresh_fpv` every 3 s; on stop, the channel is left then `vi_switch=0` is sent.
 *
 * Video commands go through the robot link (Bluetooth or gateway) if it is ready, otherwise through the cloud
 * ([fr.luba.poc.auth.CloudReadOnly] barrier). No movement or blade command.
 */
@Composable
fun CameraPage(robot: RobotBle, state: RobotUi, cloud: CloudViewModel, cloudState: CloudUi, back: () -> Unit) {
    val camera = cloudState.camera
    val robotCamera = state.camera
    val controller = CameraController
    var bothFront by rememberSaveable { mutableStateOf(controller.target == 2) }
    val playing = camera.credentials != null
    val viaRobot = state.ready
    val latestCloud by rememberUpdatedState(cloudState)
    val latestState by rememberUpdatedState(state)

    DisposableEffect(Unit) {
        controller.pageVisible = true
        onDispose {
            controller.pageVisible = false
            // The stream stays open only if it serves an obstacle photo in progress or the live view (movement in
            // progress): the latter takes it over instead of seeing it cut then requested again.
            if (latestState.obstaclePhoto.request == null && !controller.photoOwned && !LiveCamera.wantedNow &&
                (latestCloud.camera.credentials != null || latestCloud.camera.busy))
                stopCamera(robot, cloud, latestCloud)
        }
    }

    PushedPage(fr.luba.poc.ui.ServicePage.CAMERA.title, back, bottomBar = {
        Column(Modifier.fillMaxWidth().padding(Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            if (playing) PrimaryPillButton(tr("Stop the stream", "Arrêter le flux"), { stopCamera(robot, cloud, cloudState) }, Modifier.fillMaxWidth())
            else if (camera.busy) PrimaryPillButton(tr("Cancel the request", "Annuler la demande"), { cloud.stopCamera() }, Modifier.fillMaxWidth())
            else PrimaryPillButton(tr("Show camera", "Afficher la caméra"), { controller.photoOwned = false; cloud.requestCamera(bothFront) },
                Modifier.fillMaxWidth(), enabled = cloudState.connected)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("Restart (new token)", "Relancer (nouveau jeton)"), { controller.stop(); cloud.requestCamera(bothFront) },
                    Modifier.weight(1f), enabled = !camera.busy && cloudState.connected)
                SecondaryPillButton(tr("Force vi_switch=1", "Forcer vi_switch=1"), { cameraCommand(robot, cloud, CameraCommand.VIDEO_ON) }, Modifier.weight(1f), enabled = viaRobot || cloudState.connected)
            }
        }
    }) {
        InfoNote(tr("Video playback only: no movement or blade command. The stream goes through the Mammotion cloud and Agora: " +
            "the robot must be online (Wi-Fi or 4G) and the Mammotion account connected (Me tab). Over 4G, video time counts against the Mammotion quota.",
            "Lecture vidéo seule : aucune commande de mouvement ni de lame. Le flux passe par le cloud Mammotion et Agora : " +
            "le robot doit être en ligne (Wi-Fi ou 4G) et le compte Mammotion connecté (onglet Moi). En 4G, le temps de vidéo est décompté du quota Mammotion."))
        if (!cloudState.connected) SafetyNotice(tr("Mammotion account not connected: connect it in Me → Mammotion account to get the video token.", "Compte Mammotion non connecté : connectez-le dans Moi → Compte Mammotion pour obtenir le jeton vidéo."))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, MaterialTheme.shapes.medium)) {
            CameraPlayerView(Modifier.fillMaxSize())
        }
        SettingsGroup(tr("Stream", "Flux")) {
            KeyValueRow(tr("State", "État"), camera.status)
            KeyValueRow(tr("Player", "Lecteur"), controller.status)
            if (controller.stats.isNotBlank()) KeyValueRow(tr("Reception", "Réception"), controller.stats)
            KeyValueRow(tr("Published cameras", "Caméras publiées"), controller.users.joinToString().ifEmpty { "—" })
            camera.credentials?.let { KeyValueRow("Session", it.summary) }
            camera.robot?.let { KeyValueRow(tr("Account robot", "Robot du compte"), it.name) }
            if (camera.lastCommand.isNotBlank()) KeyValueRow(tr("Last command", "Dernière commande"), camera.lastCommand)
        }
        SettingsGroup(tr("Displayed camera", "Caméra affichée")) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                ChoiceTile(Icons.Rounded.Videocam, tr("Front / left (1)", "Avant / gauche (1)"), controller.target == 1, { controller.select(1) }, Modifier.weight(1f))
                ChoiceTile(Icons.Rounded.Videocam, tr("Right (2)", "Droite (2)"), controller.target == 2, {
                    controller.select(2); bothFront = true
                    // Token without the right camera: the robot does not publish it, stream restarted with both front cameras.
                    if (CameraChoice.needsRestart(2, playing, camera.busy, camera.bothFront)) {
                        controller.stop(); cloud.requestCamera(bothFront = true)
                    }
                }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Also request the right camera", "Demander aussi la caméra droite"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LubaSwitch(bothFront, { bothFront = it }, enabled = !playing)
            }
            Text(tr("The Mammotion app only requests the front camera. Choosing \"Right\" requests both cameras (stream restarted if needed).", "L'app Mammotion ne demande que la caméra avant. Choisir « Droite » demande les deux caméras (flux relancé si besoin)."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        SettingsGroup(tr("Obstacle photo after a contact", "Photo de l'obstacle après un contact")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Photograph the obstacle after the back-off", "Photographier l'obstacle après le recul"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LubaSwitch(state.obstaclePhoto.enabled, robot::setObstaclePhotoEnabled)
            }
            ObstaclePhotoCameraChoice(robot, state)
            if (state.obstaclePhoto.status.isNotBlank()) KeyValueRow(tr("Last photo", "Dernière photo"), state.obstaclePhoto.status)
            Text(tr("Photos taken: Service → Obstacles.", "Photos prises : Service → Obstacles."), style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        SettingsGroup(tr("Robot (telemetry)", "Robot (télémétrie)")) {
            KeyValueRow(tr("Video command link", "Liaison des commandes vidéo"), if (viaRobot) tr("robot (Bluetooth / gateway)", "robot (Bluetooth / passerelle)") else tr("Mammotion cloud", "cloud Mammotion"))
            KeyValueRow("FPV (fpv_info)", robotCamera.fpvLabel)
            KeyValueRow(tr("Robot network", "Réseau du robot"), robotCamera.netLabel + if (robotCamera.mobileStream) tr(" · refresh_fpv every 3 s", " · refresh_fpv toutes les 3 s") else "")
            if (robotCamera.usedNet == 0) Text(tr("Robot without Wi-Fi or 4G: the cloud will not be able to request the video from it.", "Robot sans Wi-Fi ni 4G : le cloud ne pourra pas lui demander la vidéo."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
            KeyValueRow(tr("Last vi_switch ack", "Dernier accusé vi_switch"), CameraRobotState.ackLabel(robotCamera.lastAck))
            if (!viaRobot) Text(tr("Robot not connected to the app: FPV and network unknown, video commands go through the cloud.", "Robot non connecté à l'application : FPV et réseau inconnus, les commandes vidéo passent par le cloud."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
    }
}
