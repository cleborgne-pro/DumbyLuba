package fr.luba.poc.relay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.lifecycle.ViewModelProvider
import fr.luba.poc.MainActivity
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

 data class RelayStatus(val running: Boolean = false, val message: String = tr("Relay stopped", "Relais arrêté"), val lastSentAt: Long? = null, val serverUrl: String = "")

class RelayService : Service() {
    private lateinit var robot: RobotBle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWake = object : Runnable {
        override fun run() {
            if (!accepting) return
            wake?.acquire(10 * 60 * 1000L)
            main.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    @Volatile private var accepting = false
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (accepting) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("relay", tr("Wi-Fi / BLE relay", "Relais Wi-Fi / BLE"), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "relay").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(tr("DumbyLuba — relay active", "DumbyLuba — relais actif"))
                .setContentText(tr("Sending to the home server · BLE with screen off", "Transmission au serveur maison · BLE écran éteint"))
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, tr("Stop the relay", "Arrêter le relais"), stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(8765, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(8765, notification)
            robot = ViewModelProvider(application as RelayApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RobotBle::class.java]
            val config = RelayConfig.read(this)
            config.validate()
            robot.background(keepConnection = true)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DumbyLuba:relay").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            val lock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DumbyLuba:relay")
            wifi = lock.apply { acquire() }
            accepting = true
            main.postDelayed(renewWake, 5 * 60 * 1000L)
            mutable.value = RelayStatus(true, tr("Sending…", "Transmission en cours…"), serverUrl = config.url)
            scope.launch { publishingLock.withLock { publish(config) } }
        } catch (error: Exception) {
            mutable.value = RelayStatus(message = tr("Start refused: ${error.javaClass.simpleName} — check Bluetooth and permissions",
                "Démarrage refusé : ${error.javaClass.simpleName} — vérifier Bluetooth et permissions"))
            stopSelf()
        }
        // Never silently reconnect or resume robot commands after process death.
        return START_NOT_STICKY
    }

    private suspend fun publish(config: RelayConfig) {
        val prefs = getSharedPreferences("relay-server", 0)
        val outbox = RelayOutbox(File(filesDir, "relay-latest.json"))
        // On a restart, send any durable pending snapshot before replacing it.
        var pending = runCatching { outbox.read() }.getOrNull()
        while (scope.isActive && accepting) {
            try {
                if (pending == null) {
                    val state = robot.state.value
                    val sequence = prefs.getLong("sequence", 0) + 1
                    check(prefs.edit().putLong("sequence", sequence).commit()) { tr("Counter not saved", "Compteur non sauvegardé") }
                    pending = RelaySnapshot.capture(state, fr.luba.poc.simulation.BaseStore(this, state.robotMap).read(),
                        config.id, sequence, System.currentTimeMillis()).toString()
                    outbox.write(pending)
                }
                transmit(config, pending)
                if (!scope.isActive || !accepting) break
                outbox.acknowledge()
                mutable.value = mutable.value.copy(message = tr("Information sent to the server", "Informations transmises au serveur"), lastSentAt = System.currentTimeMillis())
                pending = null
            } catch (error: Exception) {
                if (!scope.isActive || !accepting) break
                mutable.value = mutable.value.copy(message = tr("Sending suspended: ${error.message ?: error.javaClass.simpleName} — automatic retry",
                    "Transmission suspendue : ${error.message ?: error.javaClass.simpleName} — nouvel essai automatique"))
                // Keep only the latest observation during a network outage.
                pending = null
            }
            delay(3000)
        }
    }

    private fun transmit(config: RelayConfig, payload: String) {
        val connection = URL(config.url.trimEnd('/') + "/api/snapshots").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.setRequestProperty("X-Relay-Code", config.code)
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size); connection.doOutput = true
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        } finally { connection.disconnect() }
    }

    override fun onDestroy() {
        accepting = false
        main.removeCallbacks(renewWake)
        scope.cancel()
        if (::robot.isInitialized) {
            try { robot.background() } catch (_: Exception) { /* Locks must be released even after permission revocation. */ }
        }
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        mutable.value = mutable.value.copy(running = false, message = if (mutable.value.running) tr("Relay stopped", "Relais arrêté") else mutable.value.message)
        super.onDestroy()
    }

    companion object {
        private val publishingLock = Mutex()
        private val mutable = MutableStateFlow(RelayStatus())
        val status = mutable.asStateFlow()
    }
}
