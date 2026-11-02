@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.auth.AccountMode
import fr.luba.poc.camera.ObstaclePhotoRequest
import fr.luba.poc.camera.ObstaclePhotoTiming
import fr.luba.poc.camera.ObstaclePhotoUi
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Obstacle photo: robot held still in front of a presumed obstacle while the camera page takes a picture, stored with the incident.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class ObstaclePhotoRunner(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private val handler get() = host.handler
    private val log get() = host.log
    private var navConfig
        get() = host.navConfig
        set(value) { host.navConfig = value }
    private var navCourse: LocalObstacle?
        get() = host.navCourse
        set(value) { host.navCourse = value }
    private var navMapPlan: MapNavPlan?
        get() = host.navMapPlan
        set(value) { host.navMapPlan = value }
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private val photoPrefs get() = host.photoPrefs
    private val photoStore get() = host.photoStore
    private var readyAt
        get() = host.readyAt
        set(value) { host.readyAt = value }
    private var remoteLegActive
        get() = host.remoteLegActive
        set(value) { host.remoteLegActive = value }
    private var routeBypassArmed
        get() = host.routeBypassArmed
        set(value) { host.routeBypassArmed = value }
    private val state get() = host.state

    /** Last obstacle notification without a photo (photo disabled): bounces of the same contact are ignored. */
    private var lastObstacleAlertAt = 0L
    private var photoDone: CompletableDeferred<Boolean>? = null

    fun setObstaclePhotoEnabled(enabled: Boolean) {
        photoPrefs.edit { putBoolean("enabled", enabled) }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(enabled = enabled, request = if (enabled) it.obstaclePhoto.request else null)) }
        if (!enabled) photoDone?.complete(false)
    }

    /** Camera for the obstacle photo (1 front/left, 2 right); a pending request keeps its camera. */
    fun setObstaclePhotoCamera(camera: Int) {
        val uid = ObstaclePhotoUi.cameraOf(camera)
        photoPrefs.edit { putInt("camera", uid) }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(camera = uid)) }
    }

    /**
     * Bumper contact during a local route or a move to a point: the video stream is prepared right away
     * (cloud token, ≈ 13 s until the first frame) to photograph the obstacle after the back-off.
     */
    internal fun armObstaclePhoto(code: Int) {
        val photo = state.value.obstaclePhoto
        // Only when a back-off will follow the contact: local route (or gateway leg) with bypass,
        // move to a point with physical guard (not in a cutting test or an obstacle course).
        val reverseFollows = (state.value.robotRoute.running && (routeBypassArmed || remoteLegActive)) ||
            (state.value.navTest.running && navMapPlan?.physicalGuard == true && !navConfig.tonte && navCourse == null)
        if (!reverseFollows) return
        val now = Clock.now()
        // Bounce of the same contact: the pending request is enough.
        if (photo.request != null && now - photo.request.contactAt < ObstaclePhotoUi.HOLD_MS * 2) return
        val t = state.value.telemetry
        val request = ObstaclePhotoRequest(id = now, contactAt = now, code = code, x = t.x, y = t.y, camera = photo.camera)
        // Local mode: no camera (Mammotion cloud), handled like a disabled photo, the robot does not wait.
        val localOnly = AccountMode.localOnly
        if (!photo.enabled || localOnly) {
            // Photo disabled: the encounter is notified without an image (bounces of the same contact ignored).
            if (now - lastObstacleAlertAt >= ObstaclePhotoUi.HOLD_MS * 2) { lastObstacleAlertAt = now
                host.alerts.alertObstacle(request, if (localOnly) tr("local mode, no photo", "mode local, sans photo") else tr("photo disabled", "photo désactivée")) }
            return
        }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = request,
            status = tr("Contact: preparing the ${ObstaclePhotoUi.cameraLabel(photo.camera)}", "Contact : préparation de la ${ObstaclePhotoUi.cameraLabel(photo.camera)}"))) }
        event("obstacle_photo_request", tr("Bumper contact: obstacle photo requested after the back-off", "Contact pare-chocs : photo de l'obstacle demandée après le recul"),
            mapOf("code" to code, "x" to t.x, "y" to t.y, "camera" to photo.camera))
    }

    /**
     * After the back-off (robot stopped, blades OFF): waits for the photo for at most [ObstaclePhotoUi.HOLD_MS], robot
     * held still (zero speed, manual mode with blades stopped, errors polled). The route then resumes,
     * whether or not the photo was taken.
     */
    internal suspend fun holdForObstaclePhoto(prefix: String, heightMm: Int) {
        val request = state.value.obstaclePhoto.request ?: return
        if (!state.value.obstaclePhoto.enabled || AccountMode.localOnly) return
        val done = CompletableDeferred<Boolean>().also { photoDone = it }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = request.copy(readyAt = Clock.now()), status = tr("Obstacle photo · robot still", "Photo de l'obstacle · robot immobile"))) }
        val began = SystemClock.elapsedRealtime()
        var keepalive = 0L
        try {
            while (!done.isCompleted && SystemClock.elapsedRealtime() - began < ObstaclePhotoUi.HOLD_MS) {
                check(state.value.ready && !closing && log.healthy) { tr("BLE interrupted during the obstacle photo", "BLE interrompu pendant la photo de l'obstacle") }
                if (!state.value.robotRoute.running && !state.value.navTest.running) break
                val now = SystemClock.elapsedRealtime()
                if (now >= keepalive) {
                    host.send(commands.stop(), "${prefix}_PHOTO_STOP")
                    host.send(commands.manualMow(1, 0, heightMm), "${prefix}_PHOTO_BUMPER_OFF", periodic = true)
                    host.send(commands.errorQuery(), "${prefix}_PHOTO_ERRORS", periodic = true)
                    keepalive = now + 500
                }
                delay(100)
            }
        } finally { if (photoDone === done) photoDone = null }
        val taken = done.isCompleted && done.getCompleted()
        if (!taken) {
            val reason = state.value.obstaclePhoto.status.takeIf { it.startsWith(tr("Photo failed", "Photo impossible")) || it.startsWith(tr("Photo not saved", "Photo non enregistrée")) }
                ?: tr("Photo not taken within ${ObstaclePhotoUi.HOLD_MS / 1000} s", "Photo non prise en ${ObstaclePhotoUi.HOLD_MS / 1000} s")
            update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("$reason · route resumed", "$reason · trajet repris"))) }
            event("obstacle_photo_missed", reason, mapOf("code" to request.code, "waitMs" to SystemClock.elapsedRealtime() - began))
            host.alerts.alertObstacle(request, reason.replaceFirstChar { it.lowercase() })
        }
    }

    /** Photo received from the video player for request [id]: saved, then the route resumes. */
    fun obstaclePhotoCaptured(id: Long, jpeg: ByteArray, camera: Int = ObstaclePhotoUi.CAMERA_FRONT,
                              timing: ObstaclePhotoTiming = ObstaclePhotoTiming()) {
        val request = state.value.obstaclePhoto.request?.takeIf { it.id == id } ?: return
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("Obstacle photo saved", "Photo de l'obstacle enregistrée"))) }
        val done = photoDone
        host.viewModelScope.launch(Dispatchers.IO) {
            val takenAt = Clock.now()
            val saved = runCatching { photoStore.save(jpeg, takenAt, request.contactAt, request.code, request.x, request.y) }
            val photos = runCatching { photoStore.list() }.getOrDefault(emptyList())
            handler.post {
                saved.onSuccess { photo ->
                    val label = ObstaclePhotoUi.cameraLabel(camera)
                    val stale = if (timing.fresh) "" else tr(" · image possibly taken before the back-off (stream lagging)", " · image peut-être antérieure au recul (flux en retard)")
                    update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(photos = photos, status = tr("Obstacle photo saved ($label, ${photo.delaySeconds} s after the contact)", "Photo de l'obstacle enregistrée ($label, ${photo.delaySeconds} s après le contact)") +
                        (if (camera != request.camera) tr(" · ${ObstaclePhotoUi.cameraLabel(request.camera)} without image", " · ${ObstaclePhotoUi.cameraLabel(request.camera)} sans image") else "") + stale)) }
                    event("obstacle_photo", tr("Obstacle photo saved", "Photo de l'obstacle enregistrée"), mapOf("file" to photo.file.name, "bytes" to jpeg.size,
                        "code" to request.code, "x" to request.x, "y" to request.y, "delayMs" to takenAt - request.contactAt,
                        "camera" to camera, "wantedCamera" to request.camera, "fresh" to timing.fresh, "frameAgeMs" to timing.frameAgeMs,
                        "streamDelayMs" to timing.delayMs, "afterStopMs" to timing.afterStopMs))
                    host.alerts.alertObstacle(request, tr("photo ($label) ${photo.delaySeconds} s after the contact$stale", "photo ($label) ${photo.delaySeconds} s après le contact$stale"), photo.file)
                }.onFailure { error ->
                    update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(status = tr("Photo not saved: ${error.message ?: error.javaClass.simpleName}", "Photo non enregistrée : ${error.message ?: error.javaClass.simpleName}"))) }
                    event("obstacle_photo_error", tr("Photo not saved", "Photo non enregistrée"), mapOf("reason" to (error.message ?: error.javaClass.simpleName)))
                }
                done?.complete(saved.isSuccess)
            }
        }
    }

    /** Photo failed (account, network, stream): the robot waits no longer. */
    fun obstaclePhotoFailed(id: Long, reason: String) {
        val request = state.value.obstaclePhoto.request?.takeIf { it.id == id } ?: return
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("Photo failed: $reason", "Photo impossible : $reason"))) }
        event("obstacle_photo_failed", tr("Obstacle photo failed", "Photo de l'obstacle impossible"), mapOf("reason" to reason))
        // Before the back-off, no wait will notify the encounter; after it, holdForObstaclePhoto takes care of it.
        if (request.readyAt == null) host.alerts.alertObstacle(request, tr("photo failed: $reason", "photo impossible : $reason"))
        photoDone?.complete(false)
    }
}
