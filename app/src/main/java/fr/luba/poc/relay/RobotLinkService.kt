package fr.luba.poc.relay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.lifecycle.ViewModelProvider
import fr.luba.poc.MainActivity
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps the process (and thus the robot Bluetooth link) alive when the app goes to the
 * background or the screen turns off. It commands nothing: the link, telemetry, guards and stops stay in [RobotBle].
 * The notification carries a STOP button that stops the robot and closes the link; removing the app from the task list
 * does the same.
 */
class RobotLinkService : Service() {
    private var wake: PowerManager.WakeLock? = null
    /**
     * Wi-Fi kept active during the link (screen off, app in the background): the pilot talks to the gateway over Wi-Fi.
     * Tests of 2026-10-14: Fold in the background, screen off, access point lost at 10:33 then Wi-Fi turned off by Android.
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWake = object : Runnable {
        override fun run() {
            wake?.acquire(WAKE_MS)
            main.postDelayed(this, WAKE_MS / 2)
        }
    }
    override fun onBind(intent: Intent?) = null

    private fun robot() = ViewModelProvider(application as RelayApplication,
        ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RobotBle::class.java]

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Emergency stop from the notification: STOP, blades OFF then the link is closed.
            try { robot().stop() } catch (_: Exception) { }
            stopSelf()
            return START_NOT_STICKY
        }
        if (mutable.value) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Robot link", "Liaison robot"), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 2, Intent(this, RobotLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(tr("DumbyLuba — robot connected", "DumbyLuba — robot connecté"))
                .setContentText(tr("Bluetooth link kept in the background", "Liaison Bluetooth maintenue en arrière-plan"))
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "STOP robot", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DumbyLuba:link")
                .apply { setReferenceCounted(false); acquire(WAKE_MS) }
            main.postDelayed(renewWake, WAKE_MS / 2)
            if (wifiLock == null) wifiLock = runCatching {
                @Suppress("DEPRECATION")
                getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DumbyLuba:link")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            mutable.value = true
        } catch (_: Exception) {
            // Start refused (permission, app in the background): the link stays usable in the foreground.
            mutable.value = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** App removed from recent tasks: no supervision without a screen, so STOP and close. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try { robot().disconnect() } catch (_: Exception) { }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(renewWake)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        mutable.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 8766
        private const val ACTION_STOP = "STOP_ROBOT"
        private const val WAKE_MS = 10 * 60 * 1000L
        private val mutable = MutableStateFlow(false)
        /** True while the foreground service holds the link. */
        val active = mutable.asStateFlow()

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, RobotLinkService::class.java)) } catch (_: Exception) { }
        }
        fun stop(context: Context) {
            try { context.stopService(Intent(context, RobotLinkService::class.java)) } catch (_: Exception) { }
        }
    }
}
