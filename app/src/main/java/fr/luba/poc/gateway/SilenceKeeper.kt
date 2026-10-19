package fr.luba.poc.gateway

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import fr.luba.poc.i18n.tr

/** Pure decisions for keeping the gateway phone silent. */
internal object SilencePolicy {
    /** Muted audio streams: ring, notifications, alarms, system sounds, media, call. */
    val STREAMS = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)

    data class Stream(val type: Int, val volume: Int, val min: Int)

    /** Streams above their minimum: (stream, target volume). */
    fun lowered(streams: List<Stream>): List<Pair<Int, Int>> = streams.filter { it.volume > it.min }.map { it.type to it.min }

    /**
     * "Do not disturb" (total silence): true = turn it on, false = release it (only if the app turned it on),
     * null = nothing. [active]: keeping requested and phone in the gateway role.
     */
    fun dnd(active: Boolean, granted: Boolean, silentNow: Boolean, ownedByApp: Boolean): Boolean? = when {
        !granted -> null
        active && !silentNow -> true
        !active && ownedByApp && silentNow -> false
        else -> null
    }
}

/**
 * Gateway phone lying on the robot: Android puts the ringer, notifications and alarms back to a loud volume (restart,
 * updates, keys pressed by mistake). While keeping is on and the phone is a gateway, every change of volume, ringer mode
 * or "Do not disturb" is undone at once, and checked every 30 s. "Do not disturb" needs the access granted by
 * `tools/deploy.sh` (`adb shell cmd notification allow_dnd fr.luba.poc`) or in the settings; without it, only the
 * volumes are lowered.
 */
class SilenceKeeper(private val context: Context, private val report: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val check = Runnable { evaluate() }
    private val periodic = object : Runnable {
        override fun run() { evaluate(); main.postDelayed(this, PERIOD_MS) }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { main.removeCallbacks(check); main.postDelayed(check, SETTLE_MS) }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED); addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
        main.post(periodic)
    }

    fun evaluate() {
        val audio = audio ?: return
        val active = keep(context) && GatewayConfig.read(context).role == GatewayRole.GATEWAY
        val granted = policyGranted(context)
        val changes = mutableListOf<String>()
        // Volumes first: in silent mode, ringer and notifications read 0 and their real volume (still loud) would come
        // back as soon as "Do not disturb" ends.
        if (active) {
            val streams = SilencePolicy.STREAMS.map { type -> SilencePolicy.Stream(type, audio.getStreamVolume(type), minVolume(audio, type)) }
            for ((type, target) in SilencePolicy.lowered(streams)) {
                val before = audio.getStreamVolume(type)
                runCatching { audio.setStreamVolume(type, target, 0) }
                val after = audio.getStreamVolume(type)
                if (after < before) changes += "${label(type)} $before → $after"
            }
            if (granted && audio.ringerMode != AudioManager.RINGER_MODE_SILENT &&
                runCatching { audio.ringerMode = AudioManager.RINGER_MODE_SILENT }.isSuccess) changes += tr("silent mode", "mode silencieux")
        }
        val silentNow = notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        when (SilencePolicy.dnd(active, granted, silentNow, owned(context))) {
            true -> if (runCatching { notifications?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }.isSuccess) {
                setOwned(context, true); changes += tr("\"Do not disturb\" on", "« Ne pas déranger » activé")
            }
            false -> if (runCatching { notifications?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }.isSuccess) {
                setOwned(context, false); report(tr("Silence keeping stopped: \"Do not disturb\" released", "Maintien du silence arrêté : « Ne pas déranger » rendu"))
            }
            null -> Unit
        }
        if (changes.isNotEmpty()) report(tr("Sound muted: ", "Son coupé : ") + changes.joinToString(", "))
    }

    private fun minVolume(audio: AudioManager, type: Int) = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(type)
        else if (type == AudioManager.STREAM_ALARM || type == AudioManager.STREAM_VOICE_CALL) 1 else 0

    private fun label(type: Int) = when (type) {
        AudioManager.STREAM_RING -> tr("ringer", "sonnerie")
        AudioManager.STREAM_NOTIFICATION -> "notifications"
        AudioManager.STREAM_ALARM -> tr("alarms", "alarmes")
        AudioManager.STREAM_SYSTEM -> tr("system", "système")
        AudioManager.STREAM_MUSIC -> tr("media", "médias")
        AudioManager.STREAM_VOICE_CALL -> tr("call", "appel")
        else -> tr("stream $type", "flux $type")
    }

    companion object {
        /** System broadcast (non-public) sent on every volume change. */
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val PREFS = "gateway"
        private const val KEY = "keep_silent"
        private const val OWNED = "silent_dnd_owned"
        private const val SETTLE_MS = 300L
        private const val PERIOD_MS = 30_000L

        private var installed: SilenceKeeper? = null
        private val events = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
        /** Latest interventions, shown in Service → Gateway. */
        val lastEvents = events

        /** A single watcher per process; no effect outside the gateway role. */
        fun install(context: Context) {
            if (installed != null) return
            installed = SilenceKeeper(context.applicationContext) { text ->
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date())
                events.value = (events.value + "$stamp $text").takeLast(5)
                android.util.Log.i("DumbyLubaSilence", text)
            }.also { it.start() }
        }

        /** Setting or role changed: apply right away. */
        fun refresh() { installed?.let { keeper -> keeper.main.post { keeper.evaluate() } } }

        fun keep(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEY, true)
        fun setKeep(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply(); refresh() }
        fun policyGranted(context: Context) =
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        private fun owned(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(OWNED, false)
        private fun setOwned(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(OWNED, value).apply() }
    }
}
