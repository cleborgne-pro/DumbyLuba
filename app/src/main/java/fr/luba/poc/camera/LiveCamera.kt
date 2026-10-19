package fr.luba.poc.camera

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.components.MapSideButton
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.i18n.tr

/**
 * Live camera: shown as a thumbnail on the map (a single one, top right) as soon as the robot moves, and large in manual control.
 * The stream is requested automatically (camera saved in the settings, Camera → Obstacle photo) then stopped shortly
 * after the end of the movement. With the robot stopped, the camera button at the top right of the map (always visible)
 * shows the view on demand; it also hides it.
 *
 * Limited cost: stream only during a movement, manual control or an explicit request, never over 4G (Mammotion quota),
 * thumbnail outside the map screen (no map recomposition), a single shared player ([CameraController]). The decision
 * is made by [CameraHost]; this object only holds the shared state.
 */
object LiveCamera {
    private const val FILE = "live_camera"
    private const val KEY_VISIBLE = "visible"
    /** End of movement: stream kept for a while (stop followed by a resume, pivot, bypass). */
    const val LINGER_MS = 15_000L
    /** Displayed frame frozen for this long: the camera is shown again in a new video element. */
    const val STALL_REPLAY_MS = 6_000L
    /** Still frozen: the stream is restarted with a new token. */
    const val STALL_RESTART_MS = 12_000L
    /** Automatic restarts of a frozen stream before giving up (until the next return to the screen or tap). */
    const val MAX_RECONNECTS = 3

    /** Automatic view during movements (saved); hidden: no automatic stream. */
    var visible by mutableStateOf(true); private set
    /** Movement in progress (or manual control page open) with a possible stream: Mammotion account connected, robot not on 4G. */
    var relevant by mutableStateOf(false); internal set
    /** Stream possible (account connected, robot not on 4G); otherwise [blocked] gives the reason. */
    var eligible by mutableStateOf(false); internal set
    var blocked by mutableStateOf(""); internal set
    /** View requested by hand (camera button), robot stopped: stream held until it is hidden or left. */
    var onDemand by mutableStateOf(false); private set
    /** View closed by the user: stream stopped immediately, without the [LINGER_MS] delay. */
    var closedByUser = false; internal set
    /** Stream currently wanted by the live view (decided by [CameraHost]): the Camera page does not cut it when leaving. */
    var wantedNow by mutableStateOf(false); internal set
    /**
     * New attempt allowed after a failure: return to the app, return to the map or manual control, tap on the view,
     * new movement. Without it, a failure is never retried in a loop.
     */
    var retryRequested by mutableStateOf(false); internal set
    /** Restarts of a frozen stream since the last allowed attempt. */
    var reconnects = 0; internal set
    /** Manual control page open. */
    var manualPage by mutableStateOf(false)
    /** Stream requested automatically by the live view (not by the Camera page or an obstacle photo). */
    var owned by mutableStateOf(false); internal set
    /** Stream open (token received) and readable stream state. */
    var streaming by mutableStateOf(false); internal set
    var status by mutableStateOf(""); internal set
    /**
     * Screens showing the view (map, manual control), player or loading: the [CameraHost] thumbnail disappears.
     * Counted as soon as the view is shown, before the stream arrives, so the two thumbnails never fight over the player.
     */
    var embedded by mutableIntStateOf(0); private set
    /** Screens offering the view (map, manual control): when none is left, the manual request is dropped. */
    var hosts by mutableIntStateOf(0); private set

    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        visible = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VISIBLE, true)
    }

    fun setVisible(context: Context, shown: Boolean) {
        visible = shown
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VISIBLE, shown).apply()
    }

    /** View shown: movement in progress, manual request or automatic stream still open (end of movement). */
    val shown: Boolean get() = visible && (relevant || onDemand || (owned && streaming))

    /** Camera button: shows the view (and requests it again when stopped), or hides it if it is shown. */
    fun toggle(context: Context) {
        if (shown) close(context) else open(context)
    }

    fun open(context: Context) {
        retry()
        if (!visible) setVisible(context, true)
        // Outside a movement (or stream impossible: the view gives the reason), the view is held on demand.
        if (!relevant) onDemand = true
    }

    /**
     * Hides the view. A view requested by hand while stopped closes without changing the setting; a hidden automatic
     * view (movement) stays hidden for the next movements, until the next press of the button.
     */
    fun close(context: Context) {
        closedByUser = true
        if (onDemand && !relevant) onDemand = false
        else { onDemand = false; setVisible(context, false) }
    }

    /** Allows a new stream request (after a failure, a frozen frame or a return to the screen). */
    fun retry() { retryRequested = true; reconnects = 0 }

    /** Decision of the live view supervisor ([CameraHost]). */
    enum class Supervision {
        /** Nothing to do: stream not wanted, request in progress, obstacle photo in progress, failure not retried. */
        NONE,
        /** Request a stream (cloud token). */
        REQUEST,
        /** A stream is already open (obstacle photo, Camera page closed): the live view keeps it for itself. */
        ADOPT,
    }

    /**
     * Supervisor: while the view is wanted, a stream stopped by something else (photo finished, Camera page left, app
     * sent to the background, restart) is requested again, except after a failure ([failed]) as long as a new attempt
     * is not allowed ([retry]). Before 2026-10-15, the stream was only requested at the start of the movement or of the
     * request: if stopped in between, the view stayed loading until it was hidden.
     */
    fun supervise(wanted: Boolean, playing: Boolean, busy: Boolean, photoPending: Boolean, pageVisible: Boolean,
                  failed: Boolean, retryRequested: Boolean): Supervision = when {
        !wanted || photoPending || pageVisible -> Supervision.NONE
        playing -> Supervision.ADOPT
        busy -> Supervision.NONE
        failed && !retryRequested -> Supervision.NONE
        else -> Supervision.REQUEST
    }

    /** Last request ended in failure (the stream status says so until another request is started). */
    fun failed(playing: Boolean, busy: Boolean, status: String): Boolean = !playing && !busy && status.startsWith(tr("Video stream failed", "Échec"))

    /** Decision of the watchdog of a displayed stream. */
    enum class Watch { OK, REPLAY, RESTART, GIVE_UP }

    /**
     * Stream displayed on screen without a new frame for [stalledMs] (return to the app or to the map, robot left the
     * channel, Agora connection lost): the camera is first shown again, then the stream restarted with a new token,
     * [MAX_RECONNECTS] times at most. A cut Agora connection is restarted without waiting.
     */
    fun watch(stalledMs: Long, connection: String, replayed: Boolean, reconnects: Int): Watch {
        val lost = connection == "DISCONNECTED" || connection == "NONE"
        return when {
            !lost && stalledMs < STALL_REPLAY_MS -> Watch.OK
            !lost && !replayed && stalledMs < STALL_RESTART_MS -> Watch.REPLAY
            !lost && stalledMs < STALL_RESTART_MS -> Watch.OK
            reconnects >= MAX_RECONNECTS -> Watch.GIVE_UP
            else -> Watch.RESTART
        }
    }

    /** Stream to request: movement in progress (or manual control open) with the view shown, or manual request; stream possible. */
    fun wanted(visible: Boolean, moving: Boolean, manualPage: Boolean, eligible: Boolean, onDemand: Boolean = false): Boolean =
        eligible && (visible && (moving || manualPage) || onDemand)

    /** Reason why no stream is possible, empty otherwise. */
    fun blockedReason(connected: Boolean, mobile: Boolean): String = when {
        !connected -> tr("Mammotion account not connected", "Compte Mammotion non connecté")
        mobile -> tr("Robot on 4G: view off (quota)", "Robot en 4G : vue coupée (quota)")
        else -> ""
    }

    /** View content: player, animated loading (until the first frame) or stream impossible. */
    enum class Phase { PLAYER, LOADING, UNAVAILABLE }

    fun phase(eligible: Boolean, streaming: Boolean, pageVisible: Boolean, firstFrame: Boolean, status: String): Phase = when {
        !eligible -> Phase.UNAVAILABLE
        streaming && !pageVisible && firstFrame -> Phase.PLAYER
        !streaming && status.startsWith(tr("Video stream failed", "Échec")) -> Phase.UNAVAILABLE
        else -> Phase.LOADING
    }

    /**
     * Fallback thumbnail of [CameraHost] (bottom left, the WebView must be displayed to decode): stream open outside
     * the Camera page and shown by no screen. Never for the live view stream on a screen that offers it (view hidden,
     * stream stopping): only the top thumbnail counts.
     */
    fun fallbackThumbnail(streaming: Boolean, pageVisible: Boolean, embedded: Int, hosts: Int, owned: Boolean): Boolean =
        streaming && !pageVisible && embedded == 0 && !(owned && hosts > 0)

    internal fun attach() { embedded++ }
    internal fun detach() { embedded = (embedded - 1).coerceAtLeast(0) }
    /** View screen opened (map, manual control): a previous failure may be retried. */
    internal fun hostEnter() { hosts++; retry() }
    /** Screen left: the manual request is dropped if no screen comes back within [LINGER_MS] ([CameraHost]). */
    internal fun hostLeave() { hosts = (hosts - 1).coerceAtLeast(0) }
    /** No view screen for [LINGER_MS]: the manual request is dropped, the stream stops. */
    internal fun dropOnDemand() { onDemand = false }
}

/** Camera button at the top right of the map, always visible: shows or hides the live view. */
@Composable
fun LiveCameraButton(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LiveCamera.load(context) }
    val shown = LiveCamera.shown
    // Always the same camera icon (never crossed out): green when the view is shown.
    MapSideButton(Icons.Rounded.Videocam, if (shown) tr("Hide camera", "Masquer la caméra") else tr("Show camera", "Afficher la caméra"),
        { LiveCamera.toggle(context) }, modifier, enabled = enabled, tint = if (shown) MammotionColors.Green else MammotionColors.TextPrimary)
}

/**
 * Live camera view: shared player when the stream is open, animated loading until the first frame, or a short reason
 * when no stream is possible. Hidden (or robot stopped without request): nothing, or only a small camera button if
 * [button] (manual control); on the map, the top-right button ([LiveCameraButton]) shows and hides it, so the
 * thumbnail has no close button.
 * [large]: full width (manual control, with a hide button); otherwise a thumbnail of [width].
 * On the map, use [LiveCameraWindow] (movable and resizable thumbnail).
 */
@Composable
fun LiveCameraView(modifier: Modifier = Modifier, large: Boolean = false, width: Dp = 168.dp, button: Boolean = large) {
    if (!liveCameraShown(button, large, modifier)) return
    LiveCameraContent(modifier.then(if (large) Modifier.fillMaxWidth() else Modifier.width(width)), large)
}

/**
 * Live camera thumbnail on the map, anchored at the top right (original margins in [modifier]): drag to move it, pull
 * the bottom-left corner or pinch to resize it, double-tap that corner to put it back ([CameraWindow]). Position and
 * size saved.
 */
@Composable
fun BoxScope.LiveCameraWindow(modifier: Modifier = Modifier, width: Dp = 168.dp) {
    if (!liveCameraShown(button = false, large = false, modifier)) return
    val window = rememberCameraWindowState("map", width)
    CameraWindow(window, Alignment.TopEnd, modifier) { LiveCameraContent(Modifier.fillMaxWidth(), large = false) }
}

/** View to show? Otherwise, a button to show it if [button]. Counts the screen as a host of the view. */
@Composable
private fun liveCameraShown(button: Boolean, large: Boolean, modifier: Modifier): Boolean {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LiveCamera.load(context) }
    DisposableEffect(Unit) { LiveCamera.hostEnter(); onDispose { LiveCamera.hostLeave() } }
    val live = LiveCamera
    if (!live.shown) {
        // Large view (manual control): full-width labelled pill; map thumbnail: round button.
        if (button && large) fr.luba.poc.ui.components.SecondaryPillButton(tr("Show camera", "Afficher la caméra"), { live.open(context) }, modifier, icon = Icons.Rounded.Videocam)
        else if (button) MapSideButton(Icons.Rounded.Videocam, tr("Show camera", "Afficher la caméra"), { live.open(context) }, modifier)
        return false
    }
    return true
}

@Composable
private fun LiveCameraContent(modifier: Modifier, large: Boolean) {
    val context = LocalContext.current
    val live = LiveCamera
    val controller = CameraController
    // View shown: the CameraHost fallback thumbnail disappears, even before the stream arrives.
    DisposableEffect(Unit) { live.attach(); onDispose { live.detach() } }
    val shape = RoundedCornerShape(if (large) 14.dp else 10.dp)
    val phase = LiveCamera.phase(live.eligible, live.streaming, controller.pageVisible, controller.firstFrameAt != null, live.status)
    Box(modifier.aspectRatio(16f / 9f)
        .clip(shape).background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.25f), shape)) {
        // The Camera page already shows the player: an Android view can only have one parent. The player is inserted
        // as soon as the stream opens (it must be displayed to decode), the loading covers it until the first frame.
        if (live.streaming && !controller.pageVisible) CameraPlayerView(Modifier.fillMaxSize())
        AnimatedVisibility(phase != LiveCamera.Phase.PLAYER, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut(tween(450))) {
            // Stream failure (account connected, robot not on 4G): tapping the view starts a new request.
            if (phase == LiveCamera.Phase.UNAVAILABLE) CameraUnavailable(live.blocked.ifBlank { tr("Video unavailable · tap to retry", "Vidéo indisponible · toucher pour relancer") }, large,
                onRetry = if (live.eligible) ({ live.retry() }) else null)
            else CameraLoading(large)
        }
        if (phase == LiveCamera.Phase.PLAYER) Text(ObstaclePhotoUi.cameraLabel(controller.target).replaceFirstChar { it.uppercase() },
            Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.White)
        if (large) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(34.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)).clickable(role = Role.Button) { live.close(context) },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = tr("Hide camera", "Masquer la caméra"), tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Loading: a green arc spinning and stretching around a breathing camera, over a background swept by a reflection. */
@Composable
private fun CameraLoading(large: Boolean) {
    val green = MammotionColors.Green
    val motion = rememberInfiniteTransition(label = "camera-loading")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "turn")
    val sweep by motion.animateFloat(30f, 250f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    val breath by motion.animateFloat(0.45f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val shine by motion.animateFloat(-1f, 2f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "shine")
    val ring = if (large) 60.dp else 40.dp
    Box(Modifier.fillMaxSize().semantics { contentDescription = tr("Camera connecting", "Caméra en cours de connexion") }
        .background(Brush.verticalGradient(listOf(Color(0xFF151B1F), Color.Black)))
        .drawBehind {
            // Diagonal reflection crossing the thumbnail.
            val x = size.width * shine
            drawRect(Brush.linearGradient(listOf(Color.Transparent, green.copy(alpha = 0.10f), Color.Transparent),
                start = Offset(x - size.width * 0.4f, 0f), end = Offset(x + size.width * 0.4f, size.height)))
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ring)) {
            val stroke = (if (large) 4.dp else 3.dp).toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            rotate(turn) {
                drawArc(Brush.sweepGradient(listOf(green.copy(alpha = 0f), green)), -90f, sweep, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Color.White.copy(alpha = breath),
            modifier = Modifier.size(if (large) 24.dp else 16.dp).scale(0.9f + 0.1f * breath))
    }
}

/** Stream impossible (account not connected, robot on 4G, failure): icon and short reason. */
@Composable
private fun CameraUnavailable(reason: String, large: Boolean, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().then(if (onRetry != null) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier).background(Brush.verticalGradient(listOf(Color(0xFF1B1F22), Color.Black))).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(if (large) 28.dp else 20.dp))
        Spacer(Modifier.height(4.dp))
        Text(reason, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2, textAlign = TextAlign.Center)
    }
}
