package fr.luba.poc.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.luba.poc.Clock
import fr.luba.poc.i18n.tr
import java.io.FileNotFoundException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Largest base64 payload accepted from the page for an obstacle photo (about 3 MB of JPEG). */
private const val MAX_SNAPSHOT_BASE64 = 4 * 1024 * 1024

/** Origin of the player page: HTTPS (secure context required by WebRTC and the Agora SDK), served from `assets/camera`. */
internal const val CAMERA_PAGE_ORIGIN = "https://appassets.androidplatform.net/camera/"

private val CAMERA_ASSET_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
private val CAMERA_ASSET_TYPES = mapOf("js" to "text/javascript", "html" to "text/html")

/**
 * File of `assets/camera` requested by the player page under [CAMERA_PAGE_ORIGIN] (404 if absent), null for any
 * other URL. The origin is not a real host: without this, the vendored Agora SDK (relative `<script src>`) is fetched
 * from the network, never loads, and the player stays on "Video SDK missing" (regression of 2026-10-30).
 */
internal fun cameraPageAsset(context: Context, url: String): WebResourceResponse? {
    if (!url.startsWith(CAMERA_PAGE_ORIGIN)) return null
    val name = url.removePrefix(CAMERA_PAGE_ORIGIN).substringBefore('?').substringBefore('#')
    val mime = CAMERA_ASSET_TYPES[name.substringAfterLast('.', "")]
    if (mime == null || !CAMERA_ASSET_NAME.matches(name)) return cameraAssetNotFound()
    return try {
        WebResourceResponse(mime, "utf-8", context.assets.open("camera/$name"))
    } catch (_: FileNotFoundException) {
        cameraAssetNotFound()
    }
}

private fun cameraAssetNotFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)

/**
 * Player frame: JPEG, time (phone clock) of the last displayed frame — null if the browser cannot track it, 0 if no
 * frame has been displayed since tracking started — and end-to-end stream delay measured by Agora (null if unknown).
 */
class CameraSnapshot(val jpeg: ByteArray, val frameAt: Long?, val delayMs: Long?)

/**
 * Player state for the live view watchdog: time of the last displayed frame (0: none since tracking started,
 * -1: tracking impossible), Agora connection state (`CONNECTED`, `DISCONNECTED`…, `NONE` outside a channel) and
 * number of published cameras.
 */
data class CameraHealth(val lastFrameAt: Long, val connection: String, val users: Int) {
    companion object {
        /** Answer of `health()` (JSON returned by `evaluateJavascript`), null if unreadable. */
        fun parse(json: String?): CameraHealth? = runCatching {
            val o = org.json.JSONObject(json ?: return null)
            CameraHealth(o.optDouble("at", -1.0).toLong(), o.optString("state", "NONE"), o.optInt("users", 0))
        }.getOrNull()
    }
}

/**
 * The app's single video player (WebView + Agora Web SDK, `assets/camera/agora.html`).
 *
 * Shared by the Camera page (debug) and the obstacle photo taken after a bumper contact: a single audience member
 * joins the channel (two sessions with the same uid get kicked out). The WebView must be displayed (Camera page or
 * [CameraHost] thumbnail) to decode frames. Main thread only. The WebView is created on the application context
 * ([webView]), so holding it here keeps no activity alive.
 */
@SuppressLint("StaticFieldLeak")
object CameraController {
    var ready by mutableStateOf(false); private set
    /** Agora SDK loaded in the page (null: page not loaded yet). Without the SDK, the player is reloaded by [reload]. */
    var sdkLoaded by mutableStateOf<Boolean?>(null); private set
    var status by mutableStateOf(tr("Video SDK loading", "SDK vidéo en chargement")); private set
    var users by mutableStateOf(emptyList<Int>()); private set
    var stats by mutableStateOf(""); private set
    var peerLeft by mutableIntStateOf(0); private set
    /** Time (ms) of the first frame decoded since the last [start], null while there is none. */
    var firstFrameAt by mutableStateOf<Long?>(null); private set
    /** Cameras (uid) with a frame decoded since the last [start]. */
    var framedUids by mutableStateOf(emptySet<Int>()); private set
    /** Token session already joined (avoids joining the same channel twice). */
    var joinedSession by mutableLongStateOf(0L); private set
    /** Displayed camera: Agora uid 1 (front/left) or 2 (right). */
    var target by mutableIntStateOf(1)
    /** Camera page open: it shows the WebView, the [CameraHost] thumbnail disappears. */
    var pageVisible by mutableStateOf(false)
    /** Stream started for an obstacle photo (stopped on its own when no longer needed). */
    var photoOwned by mutableStateOf(false)

    private var view: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val snapshots = HashMap<Int, (CameraSnapshot?) -> Unit>()
    private var nextSnapshot = 0

    /** Player WebView. Insertion into a screen: [CameraPlayerView] only (never returned by a factory). */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(context: Context): WebView = view ?: WebView(context.applicationContext).apply {
        // Size imposed by the host screen. Without parameters, Compose (AndroidView) inserts the view as WRAP_CONTENT:
        // Chromium then sets the CSS viewport height to 0, the video (height 100 %) is decoded but invisible
        // (black screen with "Camera live", observed on 2026-10-13).
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        val appContext = context.applicationContext
        webViewClient = object : WebViewClient() {
            // No navigation outside the embedded page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) { ready = true }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                cameraPageAsset(appContext, request.url.toString())
        }
        addJavascriptInterface(Bridge(), "LubaCamera")
        html = context.assets.open("camera/agora.html").bufferedReader().use { it.readText() }
        view = this
        load(this)
    }

    private var html = ""
    private fun load(webView: WebView) {
        ready = false; sdkLoaded = null; joinedSession = 0; firstFrameAt = null; framedUids = emptySet(); users = emptyList(); stats = ""
        status = tr("Video SDK loading", "SDK vidéo en chargement")
        webView.loadDataWithBaseURL(CAMERA_PAGE_ORIGIN, html, "text/html", "utf-8", null)
    }

    /** Reloads the player page (Agora SDK missing or player frozen): the channel is left, the token will have to be joined again. */
    fun reload() { view?.let { snapshots.values.toList().forEach { it(null) }; snapshots.clear(); load(it) } }

    /** Joins the channel of session [session] (only once per token). */
    fun start(credentials: CameraCredentials, session: Long) {
        if (!ready || session == joinedSession) return
        joinedSession = session; firstFrameAt = null; framedUids = emptySet()
        view?.evaluateJavascript("start(${credentials.toPageJson(target)})", null)
    }

    fun select(uid: Int) { target = uid; if (ready) view?.evaluateJavascript("select($uid)", null) }

    /** Leaves the channel (the robot can then receive vi_switch=0). */
    fun stop() {
        view?.evaluateJavascript("stop()", null)
        joinedSession = 0; firstFrameAt = null; framedUids = emptySet(); users = emptyList(); stats = ""; photoOwned = false
        snapshots.values.toList().forEach { it(null) }; snapshots.clear()
    }

    /** Shows the camera again in a new video element (frozen frame after the player comes back on screen). */
    fun replay() { if (ready) view?.evaluateJavascript("replay()", null) }

    /** State of the displayed stream, null if the player is not loaded or does not answer. */
    suspend fun health(): CameraHealth? = suspendCancellableCoroutine { continuation ->
        val webView = view
        if (webView == null || !ready) { continuation.resume(null); return@suspendCancellableCoroutine }
        webView.evaluateJavascript("health()") { json -> if (continuation.isActive) continuation.resume(CameraHealth.parse(json)) }
    }

    /** JPEG photo of the displayed frame, null if no frame is decoded (or if the displayed camera is not [uid]). */
    suspend fun snapshot(uid: Int = 0): CameraSnapshot? = suspendCancellableCoroutine { continuation ->
        val webView = view
        if (webView == null || !ready) { continuation.resume(null); return@suspendCancellableCoroutine }
        val id = ++nextSnapshot
        snapshots[id] = { bytes -> if (continuation.isActive) continuation.resume(bytes) }
        continuation.invokeOnCancellation { main.post { snapshots.remove(id) } }
        webView.evaluateJavascript("snapshot($id, $uid)", null)
    }

    private class Bridge {
        @JavascriptInterface fun status(text: String) { post { status = text.take(200) } }
        @JavascriptInterface fun sdk(loaded: Boolean) { post { sdkLoaded = loaded } }
        @JavascriptInterface fun users(list: String) { post { users = list.split(',').mapNotNull { it.trim().toIntOrNull() } } }
        @JavascriptInterface fun stats(text: String) { post { stats = text.take(160) } }
        @JavascriptInterface fun peerLeft() { post { peerLeft++ } }
        @JavascriptInterface fun frame(uid: Int) { post { if (firstFrameAt == null) firstFrameAt = Clock.now(); framedUids = framedUids + uid } }
        @JavascriptInterface fun snapshot(id: Int, base64: String, width: Int, height: Int, frameAt: Double, delayMs: Double) {
            // Bounded: the page (and the Agora SDK loaded from its CDN) must not be able to exhaust the app's heap
            // with a huge payload (audit of 2026-10-30). 4 MB of base64 is about 3 MB of JPEG, far above a photo.
            if (base64.length > MAX_SNAPSHOT_BASE64) { post { snapshots.remove(id)?.invoke(null) }; return }
            val bytes = if (base64.isEmpty()) null else runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            val shot = bytes?.let { CameraSnapshot(it, frameAt.toLong().takeIf { at -> at >= 0 }, delayMs.toLong().takeIf { d -> d > 0 }) }
            post { snapshots.remove(id)?.invoke(shot) }
        }
        private fun post(block: () -> Unit) { main.post(block) }
    }
}
