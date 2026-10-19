package fr.luba.poc.gateway

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import fr.luba.poc.MainActivity
import fr.luba.poc.ble.GattLink
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

 data class GatewayStatus(val running: Boolean = false, val message: String = tr("Gateway stopped", "Passerelle arrêtée"), val pilot: String? = null,
    val lastEvents: List<String> = emptyList(), val address: String? = null, val robot: GatewayRobotState = GatewayRobotState())

/**
 * Phone mounted on the robot: holds the robot Bluetooth link ([GatewayRobot], robot remembered and reconnected
 * automatically) and listens for the pilot over Wi-Fi (TCP 8766, mDNS announcement). A single control session at a
 * time. No command is decided here, except the stop (blades OFF, zero speed) when the pilot goes silent.
 */
class GatewayService : Service() {
    private var server: ServerSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var screen: PowerManager.WakeLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val limiter = AttemptLimiter()
    private val admission = GatewayAdmission(::report)
    private var robot: GatewayRobot? = null
    private var tilt: PhoneTiltSensor? = null
    private val main = Handler(Looper.getMainLooper())
    private val screenWatch = object : Runnable {
        override fun run() {
            if (robot?.autonomy?.running == true) main.postDelayed(this, 5_000)
            else if (admission.current == null) { holdScreen(false); wifi?.let { if (it.isHeld) it.release() } }
        }
    }
    private val renewWake = object : Runnable {
        override fun run() {
            wake?.acquire(WAKE_MS)
            if (screen?.isHeld == true) screen?.acquire(WAKE_MS)
            main.postDelayed(this, WAKE_MS / 2)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Robot gateway", "Passerelle robot"), NotificationManager.IMPORTANCE_LOW))
            val notification = notification(tr("Wi-Fi ↔ Bluetooth: waiting for the pilot phone", "Wi-Fi ↔ Bluetooth : en attente du téléphone de pilotage"))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DumbyLuba:gateway")
                .apply { setReferenceCounted(false); acquire(WAKE_MS) }
            main.postDelayed(renewWake, WAKE_MS / 2)
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DumbyLuba:gateway").apply { setReferenceCounted(false) }
            val shared = GatewayRobot({ address, listener ->
                GattLink.open(this, getSystemService(BluetoothManager::class.java)?.adapter, address, listener)
            }, ::report, onState = { robotState ->
                update { it.copy(robot = robotState) }
                // Notification: robot state (connected over Bluetooth, controlled or not) visible without opening the app.
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(robotState.label)) }
            }, onRemember = { address, name -> GatewayConfig.saveRobot(this, address, name) }, hostCheck = { health()?.autonomyFailure() }, cuttingCheck = { health()?.cuttingFailure() }, tilt = { tilt?.current()?.first })
            robot = shared
            tilt = PhoneTiltSensor(this).also { it.start() }
            instance = this
            GatewayConfig.robot(this)?.let { (address, name) -> shared.connect(address, name) }
            val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(listenPort)) }
            server = socket
            Thread({ accept(socket) }, "gateway-accept").apply { isDaemon = true }.start()
            register()
            update { it.copy(running = true, message = tr("Waiting for the pilot phone", "En attente du téléphone de pilotage"), address = localAddress()) }
        } catch (error: Exception) {
            update { it.copy(running = false, message = tr("Start refused: ${error.message ?: error.javaClass.simpleName}",
                "Démarrage refusé : ${error.message ?: error.javaClass.simpleName}")) }
            stopSelf()
        }
        // Restarted by the system if the process dies: it sends no command without a pilot.
        return START_STICKY
    }

    private var healthAt = 0L
    private var healthSeen: GatewayHealth? = null
    /**
     * Health of THIS phone for autonomous tracking, read again every 5 s: critical overheating → tracking stop; low battery
     * → blades forbidden only (the robot may return blades OFF down to 0 %, it stops by itself if the gateway shuts down).
     */
    @Synchronized private fun health(): GatewayHealth? {
        val now = SystemClock.elapsedRealtime()
        if (now - healthAt >= GatewayProtocol.HEALTH_MS) { healthAt = now; healthSeen = runCatching { GatewayHealthReader.read(this) }.getOrNull() }
        return healthSeen
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(tr("DumbyLuba — robot gateway", "DumbyLuba — passerelle robot")).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, tr("Stop the gateway", "Arrêter la passerelle"), stop).build()).build()
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (error: Exception) {
                if (socket.isClosed) break
                // Never exit silently: the system would still accept connections with nobody answering them.
                report(tr("Waiting for pilots: ", "Attente des pilotes : ") + "${error.javaClass.simpleName} ${error.message ?: ""}".trim())
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                continue
            }
            Thread({ serve(client) }, "gateway-session").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        val code = GatewayConfig.read(this).code
        val shared = robot ?: run { runCatching { client.close() }; return }
        val current = GatewaySession(client, code, limiter, shared, ::scan, ::report, health = { GatewayHealthReader.read(this) },
            tilt = tilt?.let { sensor -> { sensor.current() } })
        if (!admission.admit(current, client)) return
        wifi?.acquire()
        holdScreen(true)
        update { it.copy(pilot = current.peer, message = tr("Pilot connected: ${current.peer}", "Pilote connecté : ${current.peer}")) }
        try { current.run() } finally {
            admission.release(current)
            // Autonomous tracking continued without pilot: Wi-Fi kept active so the pilot can come back (released by screenWatch).
            if (!shared.autonomy.running && admission.current == null) wifi?.let { if (it.isHeld) it.release() }
            // Autonomous tracking still running without pilot (Wi-Fi down): the screen stays on until it ends, as while controlling.
            if (shared.autonomy.running) main.post(screenWatch) else holdScreen(false)
            // Connection refused (code, version, attempts) or dropped before authentication: the gateway keeps waiting
            // (the "Pilot connected" message used to stay without a pilot); the refusal reason is in the latest events.
            update { it.copy(pilot = null, message = tr("Waiting for the pilot phone", "En attente du téléphone de pilotage")) }
        }
    }

    /**
     * Screen on (dimmed) while controlling: with the screen off, the phone Wi-Fi goes into power saving and the tunnel
     * latency rises to several hundred milliseconds.
     */
    @Suppress("DEPRECATION")
    private fun holdScreen(on: Boolean) {
        try {
            if (on && GatewayConfig.keepScreen(this)) {
                val lock = screen ?: getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DumbyLuba:gateway-screen")
                    .apply { setReferenceCounted(false) }.also { screen = it }
                lock.acquire(WAKE_MS)
            } else screen?.let { if (it.isHeld) it.release() }
        } catch (error: Exception) { report(tr("Gateway screen: ${error.javaClass.simpleName}", "Écran de la passerelle : ${error.javaClass.simpleName}")) }
    }

    @SuppressLint("MissingPermission")
    private fun scan(durationMs: Long, onResult: (String, String, Int) -> Unit) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        // Messages sent to the pilot as a FAILURE (wire texts, translated at display by GatewayWireText).
        check(adapter?.isEnabled == true) { "Bluetooth désactivé sur la passerelle" }
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "Scanner Bluetooth indisponible" }
        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith("Luba", ignoreCase = true)) return
                if (synchronized(seen) { seen.add(result.device.address) }) onResult(name, result.device.address, result.rssi)
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try { Thread.sleep(durationMs) } finally { runCatching { scanner.stopScan(callback) } }
        report(if (seen.isEmpty()) tr("No LUBA visible: robot off, out of range, or already connected to another phone (Mammotion or DumbyLuba direct)",
            "Aucun LUBA visible : robot éteint, hors de portée, ou déjà connecté à un autre téléphone (Mammotion ou DumbyLuba direct)")
            else tr("Bluetooth search finished: ${seen.size} LUBA", "Recherche Bluetooth terminée : ${seen.size} LUBA"))
    }

    private fun register() {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = GatewayProtocol.SERVICE_NAME; serviceType = GatewayProtocol.SERVICE_TYPE; port = GatewayProtocol.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { report(tr("mDNS announcement refused ($errorCode)", "Annonce mDNS refusée ($errorCode)")) }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); nsdListener = listener }
    }

    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()

    private fun report(text: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date())
        update { it.copy(lastEvents = (it.lastEvents + "$stamp $text").takeLast(30), address = localAddress() ?: it.address) }
        android.util.Log.i("DumbyLubaGateway", text)
    }

    override fun onDestroy() {
        main.removeCallbacks(renewWake)
        tilt?.stop(); tilt = null
        if (instance === this) instance = null
        runCatching { server?.close() }; server = null
        nsdListener?.let { listener -> runCatching { getSystemService(NsdManager::class.java)?.unregisterService(listener) } }; nsdListener = null
        screen?.let { if (it.isHeld) it.release() }; screen = null
        // Pilot plugged in: safety stop written by the gateway, pilot told, then Bluetooth closed. Off the main thread
        // (up to a few seconds if GATT lags); CPU and Wi-Fi locks kept until the end.
        val shared = robot; robot = null
        val current = admission.current
        val cpu = wake; wake = null
        val radio = wifi; wifi = null
        Thread({
            try {
                shared?.shutdown()?.let { report(tr("Gateway stopped while controlling: ${GatewayWireText.display(it)}", "Passerelle arrêtée pendant le pilotage : $it")) }
                current?.close()
            } finally {
                radio?.let { if (it.isHeld) it.release() }
                cpu?.let { if (it.isHeld) it.release() }
            }
        }, "gateway-shutdown").start()
        update { it.copy(running = false, pilot = null, message = tr("Gateway stopped", "Passerelle arrêtée"), robot = GatewayRobotState()) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 8767
        private const val ACTION_STOP = "STOP_GATEWAY"
        private const val WAKE_MS = 10 * 60 * 1000L
        private val mutable = MutableStateFlow(GatewayStatus())
        val status = mutable.asStateFlow()
        private fun update(change: (GatewayStatus) -> GatewayStatus) { synchronized(mutable) { mutable.value = change(mutable.value) } }

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, GatewayService::class.java)) }
            catch (error: Exception) { update { it.copy(message = tr("Start refused: ${error.javaClass.simpleName}", "Démarrage refusé : ${error.javaClass.simpleName}")) } }
        }
        fun stop(context: Context) { runCatching { context.stopService(Intent(context, GatewayService::class.java)) } }

        @Volatile private var instance: GatewayService? = null

        /** Port listened to for pilots; JVM tests listen on a free port (0). */
        @Volatile internal var listenPort = GatewayProtocol.PORT
        /** Port actually opened by the running gateway (tests). */
        internal val boundPort: Int? get() = instance?.server?.localPort

        /** Gateway: keep this robot and connect to it over Bluetooth (link maintained). */
        fun connectRobot(context: Context, address: String, name: String?) {
            GatewayConfig.saveRobot(context, address, name)
            val running = instance?.robot
            if (running != null) running.connect(address, name) else start(context)
        }

        /** Gateway: release the robot (no more reconnection); message (current language) if refused. */
        fun releaseRobot(context: Context): String? {
            val refused = instance?.robot?.release()
            if (refused == null) GatewayConfig.saveRobot(context, null, null)
            return refused?.let(GatewayWireText::display)
        }
    }
}

/** Restart of the phone mounted on the robot: the gateway listens again, without any command. */
class GatewayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (GatewayConfig.read(context).role == GatewayRole.GATEWAY) GatewayService.start(context)
    }
}
