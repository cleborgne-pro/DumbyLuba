package fr.luba.poc.camera

import java.io.File
import java.io.FileOutputStream
import fr.luba.poc.i18n.tr

/** Saved obstacle photo: JPEG file and bumper contact context. */
data class ObstaclePhoto(val file: File, val takenAt: Long, val contactAt: Long, val code: Int?, val x: Double?, val y: Double?) {
    /** Delay between the contact and the photo. */
    val delaySeconds: Long get() = ((takenAt - contactAt) / 1000).coerceAtLeast(0)
}

/**
 * Obstacle photos taken after a bumper contact: `diagnostics/obstacle-photos` (included in the diagnostics archive),
 * mirror copy in the app external folder (`adb pull`). The [MAX_PHOTOS] most recent ones are kept.
 * Name: `<photo ms>_<contact ms>_<code|x>_<x>_<y>.jpg` (position in mm, `n` if unknown).
 */
class ObstaclePhotoStore(filesDir: File, private val mirrorDir: File? = null) {
    val directory = File(filesDir, "diagnostics/obstacle-photos")

    fun save(jpeg: ByteArray, takenAt: Long, contactAt: Long, code: Int?, x: Double?, y: Double?): ObstaclePhoto {
        require(jpeg.size in 1..MAX_BYTES) { tr("Photo empty or too large", "Photo vide ou trop grande") }
        require(jpeg.size >= 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { tr("Invalid JPEG photo", "Photo JPEG invalide") }
        directory.mkdirs()
        val name = "${takenAt}_${contactAt}_${code ?: "x"}_${mm(x)}_${mm(y)}.jpg"
        val file = File(directory, name)
        val temp = File(directory, "$name.tmp")
        FileOutputStream(temp).use { it.write(jpeg); it.fd.sync() }
        check(temp.renameTo(file)) { tr("Photo not saved", "Photo non enregistrée") }
        runCatching { mirrorDir?.let { dir -> dir.mkdirs(); file.copyTo(File(dir, name), overwrite = true) } }
        prune(directory); mirrorDir?.let { runCatching { prune(it) } }
        return parse(file)!!
    }

    /** Most recent photos first. */
    fun list(): List<ObstaclePhoto> = directory.listFiles().orEmpty().mapNotNull(::parse).sortedByDescending { it.takenAt }

    private fun prune(dir: File) {
        dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedByDescending { it.name }.drop(MAX_PHOTOS).forEach { it.delete() }
    }

    companion object {
        const val MAX_PHOTOS = 50
        const val MAX_BYTES = 4 * 1024 * 1024
        private fun mm(v: Double?) = v?.takeIf { it.isFinite() }?.let { Math.round(it * 1000).toString() } ?: "n"
        fun parse(file: File): ObstaclePhoto? {
            if (file.extension != "jpg") return null
            val parts = file.nameWithoutExtension.split('_')
            if (parts.size != 5) return null
            val taken = parts[0].toLongOrNull() ?: return null
            val contact = parts[1].toLongOrNull() ?: return null
            return ObstaclePhoto(file, taken, contact, parts[2].toIntOrNull(), parts[3].toLongOrNull()?.div(1000.0), parts[4].toLongOrNull()?.div(1000.0))
        }
    }
}

/**
 * Photo freshness: [fresh] if the frame shows the robot stopped after its back-off; age of the last displayed frame,
 * stream delay measured by Agora and time since the end of the back-off (ms, null if unknown).
 */
data class ObstaclePhotoTiming(val fresh: Boolean = true, val frameAgeMs: Long? = null, val delayMs: Long? = null, val afterStopMs: Long? = null)

/**
 * Photo requested after a bumper contact during a local route or a move to a point. [readyAt]: robot stopped after
 * its back-off (photo possible); before that, the video stream is only being prepared.
 */
data class ObstaclePhotoRequest(val id: Long, val contactAt: Long, val code: Int, val x: Double?, val y: Double?, val readyAt: Long? = null,
    /** Camera to photograph with (Agora uid: 1 front/left, 2 right), fixed at the contact. */
    val camera: Int = ObstaclePhotoUi.CAMERA_FRONT)

data class ObstaclePhotoUi(
    /** Photograph the obstacle after the back-off (the robot waits at most [HOLD_MS] still, blades stopped). */
    val enabled: Boolean = true,
    /** Camera used for the photo: [CAMERA_FRONT] (front/left, default) or [CAMERA_RIGHT]. */
    val camera: Int = CAMERA_FRONT,
    val request: ObstaclePhotoRequest? = null,
    val status: String = "",
    val photos: List<ObstaclePhoto> = emptyList(),
) {
    companion object {
        /** Maximum wait after the back-off: stream start (≈ 13 s measured) and stable image. */
        const val HOLD_MS = 25_000L
        /** Delay after the stop before the photo when the stream delay is unknown (frame taken with the robot still). */
        const val SETTLE_MS = 1_500L
        /** Margin added to the end-to-end delay measured by Agora (robot encoding, display). */
        const val DELAY_MARGIN_MS = 500L
        /** Maximum stream delay used: beyond it, the measurement is considered aberrant. */
        const val MAX_DELAY_MS = 6_000L
        /** End of the wait: without a frame after the back-off, the last displayed frame is taken anyway (flagged). */
        const val LAST_CHANCE_MS = 2_000L
        const val CAMERA_FRONT = 1
        const val CAMERA_RIGHT = 2
        /** Last seconds of the wait: another camera already displayed is the fallback if the chosen one publishes nothing. */
        const val FALLBACK_MS = 4_000L
        fun cameraLabel(uid: Int): String = if (uid == CAMERA_RIGHT) tr("right camera", "caméra droite") else tr("front camera", "caméra avant")
        /** Saved value read safely (any other value: front camera). */
        fun cameraOf(stored: Int): Int = if (stored == CAMERA_RIGHT) CAMERA_RIGHT else CAMERA_FRONT

        /** Stream delay used: Agora end-to-end delay plus a margin, otherwise [SETTLE_MS]. */
        fun streamDelay(delayMs: Long?): Long =
            (delayMs?.takeIf { it > 0 }?.plus(DELAY_MARGIN_MS) ?: SETTLE_MS).coerceIn(SETTLE_MS / 2, MAX_DELAY_MS)

        /**
         * Does the displayed frame show the robot already stopped after its back-off ([readyAt])? The scene of a frame
         * displayed at [frameAt] dates from [frameAt] minus the stream delay ([streamDelay]). A frozen frame (stream
         * frozen, screen off) keeps its old time and is therefore not kept. [frameAt] null (frame tracking impossible):
         * stream delay awaited after the stop; 0 (no frame displayed since tracking started): no.
         */
        fun sceneAfterStop(readyAt: Long, now: Long, frameAt: Long?, delayMs: Long?): Boolean {
            val delay = streamDelay(delayMs)
            return when {
                frameAt == null -> now >= readyAt + maxOf(SETTLE_MS, delay)
                frameAt <= 0L -> false
                else -> frameAt - delay >= readyAt
            }
        }

        /**
         * Camera to photograph with now: the requested one as soon as one of its frames is decoded; otherwise, in the
         * last [FALLBACK_MS] milliseconds, another camera that already has a frame; null to wait.
         */
        fun photoCamera(wanted: Int, framed: Set<Int>, remainingMs: Long): Int? = when {
            wanted in framed -> wanted
            remainingMs <= FALLBACK_MS -> framed.minOrNull()
            else -> null
        }
    }
}

/** Photos taken within radius + margin + 0.5 m of the obstacle. */
fun List<ObstaclePhoto>.near(x: Double, y: Double, reachM: Double): List<ObstaclePhoto> =
    filter { p -> p.x != null && p.y != null && kotlin.math.hypot(p.x - x, p.y - y) <= reachM + 0.5 }
