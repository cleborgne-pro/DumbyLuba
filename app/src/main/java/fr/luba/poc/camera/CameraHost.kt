package fr.luba.poc.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.luba.poc.auth.CameraCommand
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import fr.luba.poc.i18n.tr

/** Video command through the robot link (Bluetooth / gateway) if it is ready, otherwise through the cloud. */
fun cameraCommand(robot: RobotBle, cloud: CloudViewModel, kind: CameraCommand) {
    val sent = when (kind) {
        CameraCommand.VIDEO_ON -> robot.cameraVideo(true)
        CameraCommand.VIDEO_OFF -> robot.cameraVideo(false)
        CameraCommand.REFRESH -> robot.cameraRefresh()
        CameraCommand.HEARTBEAT -> false // MQTT heartbeat: only meaningful through the cloud
    }
    if (sent) cloud.cameraCommandSent(tr("${kind.label} via the robot link: sent", "${kind.label} par la liaison robot : envoyé")) else cloud.cloudCameraCommand(kind)
}

/** Activity hosting [this] (Compose context, possibly wrapped). */
internal fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/** Player timeouts after the token is received. */
object CameraWatch {
    const val PLAYER_MS = 20_000L
    /** Measured on 2026-10-12: first frame ≈ 13 s after opening. */
    const val FIRST_FRAME_MS = 25_000L
    const val RETRY_FRAME_MS = 20_000L
}

/** Stops the stream: leave the channel, then vi_switch=0 (in that order), token forgotten. */
fun stopCamera(robot: RobotBle, cloud: CloudViewModel, cloudState: CloudUi) {
    CameraController.stop()
    if (cloudState.camera.credentials != null) cameraCommand(robot, cloud, CameraCommand.VIDEO_OFF)
    cloud.stopCamera()
}

/**
 * Host of the video player, always present in the main screen:
 * - joins the channel on each new token (Camera page or obstacle photo), `vi_switch=1` for old firmware,
 *   `refresh_fpv` every 3 s over 4G;
 * - obstacle photo: on contact, requests the stream; after the back-off, takes the photo as soon as a displayed frame
 *   shows the robot stopped (frame time minus the stream delay, [ObstaclePhotoUi.sceneAfterStop]) and hands it to [RobotBle];
 * - stream thumbnail when the Camera page is closed (the WebView must be displayed to decode);
 * - stops on its own a stream started for a photo once it is no longer needed.
 */
@Composable
fun BoxScope.CameraHost(robot: RobotBle, state: RobotUi, cloud: CloudViewModel, cloudState: CloudUi) {
    val context = LocalContext.current
    val controller = CameraController
    val camera = cloudState.camera
    val playing = camera.credentials != null
    val request = state.obstaclePhoto.request
    // Player created on first use only (the Agora SDK is downloaded at that moment).
    val needed = playing || camera.busy || request != null
    LaunchedEffect(needed) { if (needed) controller.webView(context) }
    val latestCloud by rememberUpdatedState(cloudState)
    val latestState by rememberUpdatedState(state)

    // Player loaded without the Agora SDK (CDN unreachable on first load): page reloaded on the next request,
    // otherwise the camera would stay broken until the app restarts.
    LaunchedEffect(camera.busy) {
        if (camera.busy && controller.sdkLoaded == false) controller.reload()
    }
    // After the token: each step has a timeout and a message; the state never stays stuck on "Token received".
    LaunchedEffect(camera.session) {
        if (camera.credentials == null) return@LaunchedEffect
        val session = camera.session
        fun stale() = latestCloud.camera.session != session || latestCloud.camera.credentials == null
        val loaded = withTimeoutOrNull(CameraWatch.PLAYER_MS) { snapshotFlow { controller.ready && controller.sdkLoaded != null }.first { it } }
        if (stale()) return@LaunchedEffect
        if (loaded == null || controller.sdkLoaded != true) {
            stopCamera(robot, cloud, latestCloud)
            controller.reload()
            cloud.cameraStatus(tr("Video stream failed: Agora player not loaded within ${CameraWatch.PLAYER_MS / 1000} s (phone Internet?) · retry", "Échec du flux vidéo : lecteur Agora non chargé en ${CameraWatch.PLAYER_MS / 1000} s (Internet du téléphone ?) · relancer"))
            robot.cameraEvent(tr("Camera: Agora SDK not loaded", "Caméra : SDK Agora non chargé"), mapOf("ready" to controller.ready, "sdk" to controller.sdkLoaded))
            return@LaunchedEffect
        }
        cloud.cameraStatus(tr("Token received · joining the video channel and waiting for the robot image…", "Jeton reçu · connexion au canal vidéo et attente de l'image du robot…"))
        if (withTimeoutOrNull(CameraWatch.FIRST_FRAME_MS) { snapshotFlow { controller.firstFrameAt }.first { it != null } } != null) {
            if (!stale()) cloud.cameraStatus(tr("Camera live", "Caméra en direct")); return@LaunchedEffect
        }
        if (stale()) return@LaunchedEffect
        // No image: publishing requested again from the robot in every case (fpv_flag may stay at 0 on recent firmware).
        cameraCommand(robot, cloud, CameraCommand.VIDEO_ON)
        cloud.cameraStatus(tr("No image within ${CameraWatch.FIRST_FRAME_MS / 1000} s · publishing requested again (vi_switch=1) · ${controller.status}", "Aucune image en ${CameraWatch.FIRST_FRAME_MS / 1000} s · publication redemandée (vi_switch=1) · ${controller.status}"))
        robot.cameraEvent(tr("Camera: no image", "Caméra : aucune image"), mapOf("player" to controller.status, "users" to controller.users.joinToString(),
            "fpv" to latestState.camera.fpvLabel, "net" to latestState.camera.netLabel))
        if (withTimeoutOrNull(CameraWatch.RETRY_FRAME_MS) { snapshotFlow { controller.firstFrameAt }.first { it != null } } != null) {
            if (!stale()) cloud.cameraStatus(tr("Camera live", "Caméra en direct")); return@LaunchedEffect
        }
        if (stale()) return@LaunchedEffect
        val player = controller.status
        stopCamera(robot, cloud, latestCloud)
        cloud.cameraStatus(tr("Video stream failed: no image from the robot within ${(CameraWatch.FIRST_FRAME_MS + CameraWatch.RETRY_FRAME_MS) / 1000} s " +
            "($player · robot network ${latestState.camera.netLabel} · ack ${CameraRobotState.ackLabel(latestState.camera.lastAck)})",
            "Échec du flux vidéo : aucune image du robot en ${(CameraWatch.FIRST_FRAME_MS + CameraWatch.RETRY_FRAME_MS) / 1000} s " +
            "($player · réseau du robot ${latestState.camera.netLabel} · accusé ${CameraRobotState.ackLabel(latestState.camera.lastAck)})"))
    }
    // New token: join the channel; old firmware: vi_switch=1; without robot link: MQTT heartbeat.
    LaunchedEffect(camera.session, controller.ready) {
        val credentials = camera.credentials ?: return@LaunchedEffect
        if (!controller.ready || controller.joinedSession == camera.session) return@LaunchedEffect
        controller.start(credentials, camera.session)
        if (!state.ready) cameraCommand(robot, cloud, CameraCommand.HEARTBEAT)
        if (state.camera.needsVideoSwitch) cameraCommand(robot, cloud, CameraCommand.VIDEO_ON)
        robot.cameraEvent(tr("Camera: connecting to the stream", "Caméra : connexion au flux"), mapOf("summary" to credentials.summary, "fpv" to state.camera.fpvLabel,
            "net" to state.camera.netLabel, "photo" to controller.photoOwned))
    }
    // Agora player state (channel joined, published cameras, errors) kept in the log to diagnose a stream without image.
    LaunchedEffect(controller.status, playing) {
        if (playing) robot.cameraEvent(tr("Camera: player", "Caméra : lecteur"), mapOf("player" to controller.status, "users" to controller.users.joinToString()))
    }
    // 4G: the encoder stops without refresh_fpv every 3 s (pyagorartc KEEPALIVE_INTERVAL_S).
    LaunchedEffect(playing, state.camera.mobileStream) {
        while (playing && state.camera.mobileStream) { cameraCommand(robot, cloud, CameraCommand.REFRESH); delay(3000) }
    }
    LaunchedEffect(controller.peerLeft) {
        if (controller.peerLeft > 0) { cloud.cameraStatus(tr("The robot left the video channel: restart the stream", "Le robot a quitté le canal vidéo : relancer le flux")); robot.cameraEvent(tr("Camera: publishing stopped by the robot", "Caméra : publication arrêtée par le robot")) }
    }

    // Obstacle photo, step 1 (contact): chosen camera displayed, stream requested (with the right camera if chosen).
    LaunchedEffect(request?.id) {
        val r = request ?: return@LaunchedEffect
        val camera = latestCloud.camera
        if (latestCloud.connected) controller.select(r.camera)
        when {
            fr.luba.poc.auth.AccountMode.localOnly -> robot.obstaclePhotoFailed(r.id, fr.luba.poc.auth.AccountMode.message)
            !latestCloud.connected -> robot.obstaclePhotoFailed(r.id, tr("Mammotion account not connected (Me tab)", "compte Mammotion non connecté (onglet Moi)"))
            camera.credentials == null && !camera.busy -> { controller.photoOwned = true; cloud.requestCamera(bothFront = r.camera == ObstaclePhotoUi.CAMERA_RIGHT) }
            // Stream already open without the right camera: restarted with both front cameras (the robot only publishes the requested ones).
            CameraChoice.needsRestart(r.camera, playing = camera.credentials != null, busy = camera.busy, sessionBothFront = camera.bothFront) -> {
                val owned = controller.photoOwned
                controller.stop(); controller.photoOwned = owned
                cloud.requestCamera(bothFront = true)
            }
        }
        // No back-off after the contact (route stopped): request abandoned, the stream will stop on its own.
        delay(60_000)
        latestState.obstaclePhoto.request?.takeIf { it.id == r.id && it.readyAt == null }?.let { robot.obstaclePhotoFailed(r.id, tr("no back-off after the contact", "aucun recul après le contact")) }
    }
    // Step 2 (after the back-off): photo as soon as the robot is still and a recent frame is decoded.
    LaunchedEffect(request?.id, request?.readyAt) {
        val r = request ?: return@LaunchedEffect
        val readyAt = r.readyAt ?: return@LaunchedEffect
        val deadline = readyAt + ObstaclePhotoUi.HOLD_MS - 500
        while (System.currentTimeMillis() < deadline) {
            val camera = latestCloud.camera
            if (camera.credentials == null && !camera.busy && camera.status.startsWith(tr("Video stream failed", "Échec"))) {
                robot.obstaclePhotoFailed(r.id, camera.status.removePrefix(tr("Video stream failed: ", "Échec du flux vidéo : "))); return@LaunchedEffect
            }
            val frame = controller.firstFrameAt
            val now = System.currentTimeMillis()
            // Chosen camera as soon as it has a frame; at the end of the wait, another camera already displayed is the fallback.
            val uid = ObstaclePhotoUi.photoCamera(r.camera, controller.framedUids, deadline - now)
            if (frame != null && uid != null && now >= frame + 1000) {
                if (controller.target != uid) controller.select(uid)
                val shot = controller.snapshot(uid)
                // Frame kept only if it shows the robot stopped after its back-off (stream delay subtracted); at the end of
                // the wait, the last displayed frame is taken anyway and flagged as possibly older.
                val taken = System.currentTimeMillis()
                val fresh = shot != null && ObstaclePhotoUi.sceneAfterStop(readyAt, taken, shot.frameAt, shot.delayMs)
                if (shot != null && (fresh || taken >= deadline - ObstaclePhotoUi.LAST_CHANCE_MS)) {
                    robot.obstaclePhotoCaptured(r.id, shot.jpeg, uid, ObstaclePhotoTiming(fresh, shot.frameAt?.takeIf { it > 0 }?.let { taken - it },
                        shot.delayMs, taken - readyAt))
                    return@LaunchedEffect
                }
            }
            delay(300)
        }
        robot.obstaclePhotoFailed(r.id, if (controller.firstFrameAt == null) tr("no image from the robot within ${ObstaclePhotoUi.HOLD_MS / 1000} s (screen off, robot offline?)", "aucune image du robot en ${ObstaclePhotoUi.HOLD_MS / 1000} s (écran éteint, robot hors ligne ?)")
            else tr("${ObstaclePhotoUi.cameraLabel(r.camera)} image not captured", "image de la ${ObstaclePhotoUi.cameraLabel(r.camera)} non capturée"))
    }
    // Stream started for a photo: stopped once no longer needed (60 s during a route, for the next contact; 10 s otherwise).
    val missionActive = state.robotRoute.running || state.navTest.running
    LaunchedEffect(controller.photoOwned, request == null, controller.pageVisible, playing, missionActive) {
        if (!controller.photoOwned || controller.pageVisible || request != null) return@LaunchedEffect
        if (!playing && !latestCloud.camera.busy) { controller.photoOwned = false; return@LaunchedEffect }
        delay(if (missionActive) 60_000 else 10_000)
        // Live view wanted in the meantime: it keeps the stream (supervisor), it is not cut.
        if (latestState.obstaclePhoto.request == null && !controller.pageVisible && controller.photoOwned && !LiveCamera.wantedNow)
            stopCamera(robot, cloud, cloud.state.value)
    }

    // Live camera ([LiveCamera]): stream requested as soon as the robot moves (or manual control is open), or on
    // demand (camera button, robot stopped), camera saved in the settings; stopped shortly after the end of the
    // movement or as soon as the view is hidden.
    LaunchedEffect(Unit) { LiveCamera.load(context) }
    val moving = state.robotRoute.running || state.navTest.running || state.manualActive || state.mowing.running || state.teach.running
    val eligible = cloudState.connected && !state.camera.mobileStream
    SideEffect {
        LiveCamera.relevant = eligible && (moving || LiveCamera.manualPage)
        LiveCamera.eligible = eligible
        LiveCamera.blocked = LiveCamera.blockedReason(cloudState.connected, state.camera.mobileStream)
        LiveCamera.streaming = playing
        LiveCamera.status = camera.status
    }
    val liveWanted = LiveCamera.wanted(LiveCamera.visible, moving, LiveCamera.manualPage, eligible, LiveCamera.onDemand)
    SideEffect { LiveCamera.wantedNow = liveWanted }
    LaunchedEffect(playing, camera.busy) { if (!playing && !camera.busy) LiveCamera.owned = false }
    // Supervisor: while the view is wanted, a missing stream is requested and an already open stream is kept, whatever
    // event stopped it (photo finished, Camera page left, background, restart). A failure is retried only after
    // coming back to the screen, a tap on the view or a new movement (never in a loop).
    val supervision = LiveCamera.supervise(liveWanted, playing, camera.busy, request != null, controller.pageVisible,
        LiveCamera.failed(playing, camera.busy, camera.status), LiveCamera.retryRequested)
    LaunchedEffect(supervision, LiveCamera.retryRequested) {
        when (supervision) {
            LiveCamera.Supervision.NONE -> Unit
            LiveCamera.Supervision.ADOPT -> if (!LiveCamera.owned || controller.photoOwned) {
                LiveCamera.owned = true; controller.photoOwned = false
                robot.cameraEvent(tr("Camera: open stream kept for the live view", "Caméra : flux ouvert gardé pour la vue en direct"), mapOf("camera" to controller.target))
            }
            LiveCamera.Supervision.REQUEST -> {
                // State read again when acting: the decision comes from the previous composition.
                val current = cloud.state.value.camera
                if (current.credentials != null || current.busy || robot.state.value.obstaclePhoto.request != null) return@LaunchedEffect
                if (LiveCamera.failed(false, false, current.status) && !LiveCamera.retryRequested) return@LaunchedEffect
                val chosen = latestState.obstaclePhoto.camera
                LiveCamera.retryRequested = false
                controller.select(chosen); controller.photoOwned = false; LiveCamera.owned = true
                cloud.requestCamera(bothFront = chosen == ObstaclePhotoUi.CAMERA_RIGHT)
                robot.cameraEvent(if (LiveCamera.onDemand && !moving) tr("Camera: live view requested, robot stopped", "Caméra : vue en direct demandée, robot à l'arrêt")
                    else tr("Camera: live view during the movement", "Caméra : vue en direct pendant le déplacement"), mapOf("camera" to chosen))
            }
        }
    }
    LaunchedEffect(liveWanted) {
        if (liveWanted) {
            // New movement or new request: a previous failure may be retried once.
            LiveCamera.closedByUser = false
            if (LiveCamera.failed(playing, camera.busy, latestCloud.camera.status)) LiveCamera.retry()
        } else if (LiveCamera.owned) {
            // End of movement: stream kept for a while; view closed by the user or no screen left: immediate stop.
            if (LiveCamera.visible && !LiveCamera.closedByUser && LiveCamera.hosts > 0) delay(LiveCamera.LINGER_MS)
            LiveCamera.closedByUser = false
            val current = cloud.state.value.camera
            if (LiveCamera.owned && !controller.pageVisible && latestState.obstaclePhoto.request == null &&
                (current.credentials != null || current.busy)) stopCamera(robot, cloud, cloud.state.value)
            LiveCamera.owned = false
        }
    }
    // Map and manual control left: the manual request holds for a while (quick return to the map without a cut).
    LaunchedEffect(LiveCamera.hosts == 0, LiveCamera.onDemand) {
        if (LiveCamera.hosts > 0 || !LiveCamera.onDemand) return@LaunchedEffect
        delay(LiveCamera.LINGER_MS)
        if (LiveCamera.hosts == 0) LiveCamera.dropOnDemand()
    }
    // App in the background (screen off, other app): the live view stream is cut immediately (the WebView no longer
    // decodes, the Agora session is lost); the supervisor requests it again on return. Folding the Fold (activity
    // recreated) cuts nothing.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (context.findActivity()?.isChangingConfigurations == true) return@LifecycleEventEffect
        val current = cloud.state.value
        if (LiveCamera.owned && !controller.pageVisible && robot.state.value.obstaclePhoto.request == null &&
            (current.camera.credentials != null || current.camera.busy)) {
            stopCamera(robot, cloud, current)
            LiveCamera.owned = false
            robot.cameraEvent(tr("Camera: live view cut, app in the background", "Caméra : vue en direct coupée, application en arrière-plan"))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { LiveCamera.retry() }
    // Watchdog of a displayed stream: frozen frame (player put back on screen, robot left the channel, Agora connection
    // lost) → camera shown again, then stream restarted with a new token; never in the background (no image).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val watched = playing && LiveCamera.owned && LiveCamera.embedded > 0 && !controller.pageVisible && request == null
    LaunchedEffect(watched, camera.session) {
        if (!watched) return@LaunchedEffect
        val watchedSince = System.currentTimeMillis()
        var since = watchedSince
        var replayed = false
        while (true) {
            delay(2_000)
            val now = System.currentTimeMillis()
            // Before the first frame: request timeouts (25 + 20 s). Background: no frame is displayed.
            if (controller.firstFrameAt == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { since = now; continue }
            // Frame tracking impossible in this browser (-1): no watchdog.
            val health = controller.health()?.takeIf { it.lastFrameAt >= 0 } ?: continue
            val last = maxOf(health.lastFrameAt, since)
            when (LiveCamera.watch(now - last, health.connection, replayed, LiveCamera.reconnects)) {
                // Stream healthy for a minute: past restarts no longer count (no give-up after hours).
                LiveCamera.Watch.OK -> if (now - last < 3_000 && now - watchedSince > 60_000) LiveCamera.reconnects = 0
                LiveCamera.Watch.REPLAY -> {
                    replayed = true; since = now
                    controller.replay()
                    robot.cameraEvent(tr("Camera: frozen frame, camera shown again", "Caméra : image figée, caméra réaffichée"), mapOf("stalledMs" to now - last,
                        "agora" to health.connection, "users" to health.users))
                }
                LiveCamera.Watch.RESTART -> {
                    LiveCamera.reconnects++
                    robot.cameraEvent(tr("Camera: frozen frame, stream restarted", "Caméra : image figée, flux relancé"), mapOf("stalledMs" to now - last,
                        "agora" to health.connection, "users" to health.users, "attempt" to LiveCamera.reconnects))
                    val bothFront = cloud.state.value.camera.bothFront
                    controller.stop(); cloud.requestCamera(bothFront = bothFront)
                    cloud.cameraStatus(tr("Frozen frame · reconnecting the video stream…", "Image figée · reconnexion du flux vidéo…"))
                    return@LaunchedEffect
                }
                LiveCamera.Watch.GIVE_UP -> {
                    stopCamera(robot, cloud, cloud.state.value)
                    cloud.cameraStatus(tr("Video stream failed: frozen frame after ${LiveCamera.MAX_RECONNECTS} restarts", "Échec du flux vidéo : image figée après ${LiveCamera.MAX_RECONNECTS} relances"))
                    robot.cameraEvent(tr("Camera: frozen frame, restarts abandoned", "Caméra : image figée, relances abandonnées"), mapOf("agora" to health.connection, "users" to health.users))
                    return@LaunchedEffect
                }
            }
        }
    }

    // Thumbnail: the WebView must stay displayed to decode frames (obstacle photo), unless the map or manual control
    // already show the view (even while loading) or deliberately hide their own stream. Same state as the map view
    // (LiveCamera.streaming, updated at the same moment): never two thumbnails for one stream.
    // First pass of a recreated tree (activity recreated, return to the app): the counters of the map views and of the
    // Camera page are only updated after composition. Thumbnail decided one frame later (crashes of 2026-10-14).
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; settled = true }
    if (settled && LiveCamera.fallbackThumbnail(LiveCamera.streaming, controller.pageVisible, LiveCamera.embedded, LiveCamera.hosts, LiveCamera.owned)) {
        // Movable (drag) and resizable (top-right corner, pinch) window, position saved.
        val window = rememberCameraWindowState("fallback", 132.dp)
        CameraWindow(window, Alignment.BottomStart, Modifier.padding(8.dp)) {
            Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(10.dp))) {
                CameraPlayerView(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Text(if (request != null) tr("Obstacle photo…", "Photo de l'obstacle…") else tr("Robot camera", "Caméra robot"), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}
