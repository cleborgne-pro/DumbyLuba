package fr.luba.poc

import androidx.compose.runtime.LaunchedEffect
import android.os.Bundle
import android.os.Build
import fr.luba.poc.ui.brand.DumbyIntro
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import fr.luba.poc.ui.components.StopAction
import fr.luba.poc.ble.ConnectionState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.auth.AccountScreen
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.camera.CameraHost
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.withMammotionMap
import fr.luba.poc.map.MapScreen
import fr.luba.poc.ui.HomeScreen
import fr.luba.poc.ui.RobotPanel
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.ThemeMode
import fr.luba.poc.ui.theme.ThemeStore
import fr.luba.poc.i18n.tr

/** Bottom bar tabs, as in the Mammotion app: Device / Service / Me. */
private enum class Tab(private val labelEn: String, private val labelFr: String, val icon: ImageVector) {
    Appareil("Device", "Appareil", Icons.Rounded.Home),
    Service("Service", "Service", Icons.Rounded.SupportAgent),
    Moi("Me", "Moi", Icons.Rounded.Person);

    val label: String get() = tr(labelEn, labelFr)
}

/** Pages pushed from the Device tab (tab bar hidden, back arrow). */
private enum class DevicePage { HOME, MAP, MANUAL, RECORD }

class MainActivity : ComponentActivity() {
    private var alertOpenToken by mutableStateOf(0)
    /** "open_map" extra: opens the map (deployment check, shortcuts). */
    private var mapOpenToken by mutableStateOf(0)
    /** Gateway pairing link opened (dumbyluba://pair, QR code read by another app): confirmed before being applied. */
    private var pendingPairing by mutableStateOf<fr.luba.poc.gateway.GatewayPairing?>(null)
    private lateinit var robot: RobotBle
    private lateinit var themeStore: ThemeStore
    private lateinit var simulation: fr.luba.poc.simulation.SimulationSession

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_DumbyLuba)
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("local_alerts", false)) alertOpenToken++
        if(intent.getBooleanExtra("open_map", false)) mapOpenToken++
        if (savedInstanceState == null) pendingPairing = fr.luba.poc.gateway.GatewayPairing.parse(intent?.dataString)
        // Gateway phone mounted on the robot: listening for the pilot resumes on every launch.
        if (fr.luba.poc.gateway.GatewayConfig.read(this).role == fr.luba.poc.gateway.GatewayRole.GATEWAY) fr.luba.poc.gateway.GatewayService.start(this)
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.remove()
            }
        }
        enableEdgeToEdge()
        fr.luba.poc.map.OuterOffsetSetting.init(applicationContext)
        fr.luba.poc.maintenance.BladeMaintenanceSettings.init(applicationContext)
        fr.luba.poc.auth.AccountMode.init(applicationContext)
        fr.luba.poc.mowing.WorkWindowSetting.init(applicationContext)
        fr.luba.poc.weather.WeatherService.init(applicationContext)
        fr.luba.poc.update.UpdateCheck.init(applicationContext)
        fr.luba.poc.map.SurveyOffsetSetting.init(applicationContext)
        fr.luba.poc.map.MapExtraLayers.init(applicationContext)
        fr.luba.poc.map.MapDisplaySettings.init(applicationContext)
        fr.luba.poc.map.BorderTypeRepository.init(applicationContext)
        robot = ViewModelProvider(application as fr.luba.poc.relay.RelayApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RobotBle::class.java]
        // Card of an obstacle encountered on the map: save it as an obstacle or dismiss the contact.
        robot.let { pilot -> fr.luba.poc.map.MapExtraLayers.actions = { action ->
            when (action) {
                is fr.luba.poc.map.DifficultyAction.Dismiss -> pilot.dismissDifficulty(action.id)
                is fr.luba.poc.map.DifficultyAction.Register -> pilot.saveObstacle(action.obstacle)
                is fr.luba.poc.map.DifficultyAction.Delete -> pilot.deleteObstacle(action.id)
                is fr.luba.poc.map.DifficultyAction.DismissPresumed -> pilot.dismissPresumedObstacle(action.id)
            }
        } }
        simulation = ViewModelProvider(this)[fr.luba.poc.simulation.SimulationSession::class.java]
        simulation.onTransit=robot::recordSimulationTransit
        val cloud = ViewModelProvider(this)[CloudViewModel::class.java]
        themeStore = ThemeStore(this)
        setContent {
            val activity = this@MainActivity
            var intro by remember { mutableStateOf(savedInstanceState == null) }
            // First launch of a fresh installation: assistant after the intro (role, permissions, pairing).
            var wizard by rememberSaveable { mutableStateOf(fr.luba.poc.onboarding.SetupProgress.showWizard(activity)) }
            var wizardExit by rememberSaveable { mutableStateOf<fr.luba.poc.onboarding.WizardExit?>(null) }
            var themeMode by rememberSaveable { mutableStateOf(themeStore.mode) }
            val dark = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                activity.enableEdgeToEdge(
                    statusBarStyle = if (dark || intro) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }
            LubaTheme(dark) {
                Crossfade(targetState = intro, animationSpec = tween(220), label = "DumbyLuba startup") { showIntro ->
                    if (showIntro) DumbyIntro(onFinished = { intro = false })
                    else if (wizard) fr.luba.poc.onboarding.SetupWizard(onExit = { wizardExit = it; wizard = false })
                    else RobotScreen(
                        robot = robot,
                        alertOpenToken = alertOpenToken,
                        mapOpenToken = mapOpenToken,
                        simulation = simulation,
                        cloud = cloud,
                        themeMode = themeMode,
                        onThemeModeChange = { themeMode = it; themeStore.mode = it },
                        // New language: the activity is recreated so every screen is rebuilt with the new texts
                        // (a configuration change for onStop: the mission and the BLE link are kept).
                        onLanguageChange = { fr.luba.poc.i18n.LanguageStore.set(activity, it); activity.recreate() },
                        pairing = pendingPairing,
                        onPairingHandled = { pendingPairing = null },
                        startAt = wizardExit,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.getBooleanExtra("local_alerts", false)) alertOpenToken++
        if(intent.getBooleanExtra("open_map", false)) mapOpenToken++
        fr.luba.poc.gateway.GatewayPairing.parse(intent.dataString)?.let { pendingPairing = it }
    }

    override fun onResume() {
        super.onResume()
        if (::robot.isInitialized) robot.refreshNotifications()
        // New version on GitHub: at most once a day, published build only, if enabled (Me → About).
        fr.luba.poc.update.UpdateCheck.maybeCheck()
    }

    override fun onStop() {
        // Folding / unfolding the Fold or rotating: the activity is recreated at once and the app stays in the
        // foreground. Do not stop the mission or drop BLE for a mere screen change.
        if (isChangingConfigurations) { super.onStop(); return }
        simulation.pauseInBackground()
        robot.background(keepConnection = fr.luba.poc.relay.RelayService.status.value.running)
        super.onStop()
    }
}

@Composable
private fun RobotScreen(
    robot: RobotBle,
    alertOpenToken: Int,
    mapOpenToken: Int = 0,
    simulation: fr.luba.poc.simulation.SimulationSession,
    cloud: CloudViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (fr.luba.poc.i18n.AppLanguage) -> Unit,
    pairing: fr.luba.poc.gateway.GatewayPairing? = null,
    onPairingHandled: () -> Unit = {},
    /** Page asked by the first-launch assistant when it closes. */
    startAt: fr.luba.poc.onboarding.WizardExit? = null,
) {
    val cloudState by cloud.state.collectAsStateWithLifecycle()
    val state by robot.state.collectAsStateWithLifecycle()
    val alerts by robot.localAlerts.collectAsStateWithLifecycle()
    val update by fr.luba.poc.update.UpdateCheck.flow.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAlerts by remember { mutableStateOf(false) }
    LaunchedEffect(alertOpenToken) { if(alertOpenToken > 0) showAlerts=true }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { robot.refreshNotifications() }
    // Panel open: the displayed notifications count as seen (bell badge off), including those arriving while reading.
    LaunchedEffect(showAlerts, alerts.entries.firstOrNull()?.at) { if (showAlerts) robot.markNotificationsSeen() }
    // Back from Android settings: re-read the channel state.
    androidx.lifecycle.compose.LifecycleResumeEffect(showAlerts) { if (showAlerts) robot.refreshNotifications(); onPauseOrDispose { } }
    if(showAlerts) fr.luba.poc.ui.NotificationsDialog(alerts, onDismiss = { showAlerts=false }, onTest = { robot.testNotification(it) }, onSettings = {
        if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
    }, onChannelSettings = { level ->
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, fr.luba.poc.alerts.LocalNotifications.channelId(level)))
    }, onClear = robot::clearNotificationHistory)
    // Contact while driving with the joystick: the obstacle sheet opens over any screen (keep with its size, or dismiss).
    fr.luba.poc.map.ManualContactSheet(state.manualContactId, state.difficulties, state.obstaclePhoto.photos, state.obstacles,
        actions = { action -> fr.luba.poc.map.MapExtraLayers.actions?.invoke(action) }, onClose = robot::closeManualContact)
    var useCloudMap by remember { mutableStateOf(true) }
    // Account map: only before the first robot link (or on request). Once the robot is linked, a gateway
    // dropout or reconnection keeps the Bluetooth map, its heatmap and its framing.
    LaunchedEffect(state.ready) { if (state.ready) useCloudMap = false }
    // Connected robot without any known map (new installation, cache cleared): its map is read over BLE by itself,
    // once per connection, instead of an empty map that the account map cannot fill while the robot is linked.
    val receiving = state.connection == fr.luba.poc.ble.ConnectionState.Receiving
    LaunchedEffect(receiving, state.selected?.address, state.mammotionMap.shapes.isEmpty()) {
        if (receiving && state.mammotionMap.shapes.isEmpty()) robot.autoFetchMap()
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Appareil) }
    var page by rememberSaveable { mutableStateOf(DevicePage.HOME) }
    // Page the "record by driving" page returns to (map or manual control).
    var recordFrom by rememberSaveable { mutableStateOf(DevicePage.MAP) }
    var accountPage by rememberSaveable { mutableStateOf(false) }
    var openMowing by remember { mutableStateOf(false) }
    var openSurvey by remember { mutableStateOf(false) }
    var robotTab by rememberSaveable { mutableStateOf("") }
    fun openDevice(target: DevicePage) { tab = Tab.Appareil; page = target }
    LaunchedEffect(mapOpenToken) { if (mapOpenToken > 0) openDevice(DevicePage.MAP) }
    fun openService(sub: String) { robotTab = sub; tab = Tab.Service }
    // Finishing of a mowing's gaps: only for the connected robot of that mowing with its complete map; opens the
    // trial mowing page targeting the gaps after closing the report ([close]).
    fun finishingFor(robotId: String?, close: () -> Unit): ((fr.luba.poc.planning.FinishingOffer) -> Unit)? =
        if (state.robotMap.complete && robotId != null && state.selected?.address == robotId) { offer ->
            fr.luba.poc.ui.FinishingDraft.set(state.robotMap, offer)
            close()
            openService(fr.luba.poc.ui.ServicePage.MOW_DEBUG.key)
        } else null
    val role = fr.luba.poc.gateway.rememberGatewayRole()
    var demoMap by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startAt) {
        when (startAt) {
            fr.luba.poc.onboarding.WizardExit.DEMO_MAP -> { demoMap = true; openDevice(DevicePage.MAP) }
            fr.luba.poc.onboarding.WizardExit.SETUP_CHECK -> openService(fr.luba.poc.ui.ServicePage.SETUP.key)
            else -> Unit
        }
    }
    // First-mowing list: steps remembered once seen done (robot linked, manual control, route with blades stopped).
    LaunchedEffect(state.ready) { if (state.ready) fr.luba.poc.onboarding.SetupProgress.mark(context, fr.luba.poc.onboarding.SetupStep.ROBOT_CONNECTED) }
    LaunchedEffect(state.manualActive) { if (state.manualActive) fr.luba.poc.onboarding.SetupProgress.mark(context, fr.luba.poc.onboarding.SetupStep.MANUAL_TRIED) }
    val bladesOffRoute = (state.navTest.running && !state.navTest.tonte) || (state.robotRoute.running && state.robotRoute.cutProgress == null && !state.mowing.active)
    LaunchedEffect(bladesOffRoute) { if (bladesOffRoute) fr.luba.poc.onboarding.SetupProgress.mark(context, fr.luba.poc.onboarding.SetupStep.BLADES_OFF_ROUTE) }
    pairing?.let { link ->
        fr.luba.poc.gateway.PairingConfirmDialog(link, linkIdle = state.connection == ConnectionState.Disconnected,
            isGateway = role == fr.luba.poc.gateway.GatewayRole.GATEWAY,
            onConfirm = { fr.luba.poc.gateway.GatewayPairing.apply(context, link); onPairingHandled(); openService(fr.luba.poc.ui.ServicePage.RELAY.key) },
            onDismiss = onPairingHandled)
    }
    // Background Bluetooth search every time the app returns to the foreground, whatever the screen:
    // direct connection to the only robot found, otherwise a message and the selection page.
    val btPermissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val btPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) robot.scan(autoConnect = true) }
    var btPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        val s = latestState
        if (s.connection == ConnectionState.Disconnected && !s.scanning && !s.campaign.running && !s.bladeTestActive) {
            if (btPermissions.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) robot.scan(autoConnect = true)
            else if (!btPermissionAsked) { btPermissionAsked = true; btPermissionLauncher.launch(btPermissions) }
        }
        onStopOrDispose { }
    }
    // Control via the gateway: automatic reconnection handled by RobotBle (foreground or not).
    LaunchedEffect(Unit) {
        robot.robotChoice.collect {
            android.widget.Toast.makeText(context, tr("Several robots found: choose the one to connect.", "Plusieurs robots détectés : choisissez celui à connecter."), android.widget.Toast.LENGTH_LONG).show()
            openService("Robot")
        }
    }
    // System back: pushed pages first, then back to the Device tab.
    androidx.activity.compose.BackHandler(enabled = (tab == Tab.Appareil && page != DevicePage.HOME) || (tab == Tab.Moi && accountPage) || tab != Tab.Appareil) {
        when {
            tab == Tab.Appareil -> page = DevicePage.HOME
            tab == Tab.Moi && accountPage -> accountPage = false
            else -> tab = Tab.Appareil
        }
    }
    val view = LocalView.current
    DisposableEffect(state.campaign.running, state.bladeTestActive, state.navTest.running, state.mowing.active, (state.manualActive || state.robotRoute.running)) {
        view.keepScreenOn = state.campaign.running || state.bladeTestActive || state.navTest.running || state.mowing.active || (state.manualActive || state.robotRoute.running)
        onDispose { view.keepScreenOn = false }
    }
    val localOnly by fr.luba.poc.auth.AccountMode.flow.collectAsStateWithLifecycle()
    val displayCloud = fr.luba.poc.auth.AccountMapDisplay.showAccountMap(state.ready, localOnly, useCloudMap, cloudState.map.shapes.size,
        cloudState.map.complete, state.robotMap.shapes.size)
    // Account map: same local corrections as the Bluetooth map (shapes targeted by their Mammotion identifier).
    val shown = if (displayCloud) state.withMammotionMap(cloudState.map).copy(telemetry = fr.luba.poc.protocol.Telemetry(), lastKnown = cloudState.lastKnown, positionSaveError = cloudState.positionSaveError, connection = fr.luba.poc.ble.ConnectionState.Disconnected) else state
    // Mammotion account reading (position, battery, charging contact) every 30 s on the home screen
    // and the map, without a BLE link and only while the app is in the foreground.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tab, page, cloudState.connected, state.ready) {
        if (tab == Tab.Appareil && page in setOf(DevicePage.HOME, DevicePage.MAP) && cloudState.connected && !state.ready) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    cloud.refreshPosition()
                    kotlinx.coroutines.delay(30000)
                }
            }
        }
    }
    val matchingCloudRobot = displayCloud || (state.selected?.name ?: state.lastKnown?.robotName)?.equals(cloudState.selected?.name ?: cloudState.lastKnown?.robotName,ignoreCase=true) == true
    val map = shown.robotMap
    val panels = state.copy(robotMap = shown.robotMap, mammotionMap = shown.mammotionMap, correctedMap = shown.correctedMap, lastKnown = shown.lastKnown)
    val robotActive = (state.manualActive || state.robotRoute.running) || state.mowing.active || state.navTest.running || state.bladeTestActive || state.teach.running || state.campaign.running
    // Brief gateway dropout (automatic reconnection within 2 to 10 s): the STOP bar stays visible for 15 s,
    // so the map is not resized on every micro-dropout. STOP there cancels the pending reconnection.
    val stopLive = state.connection != ConnectionState.Disconnected || robotActive
    var stopGrace by remember { mutableStateOf(false) }
    LaunchedEffect(stopLive) { if (stopLive) stopGrace = true else { kotlinx.coroutines.delay(15_000); stopGrace = false } }
    // Map at rest: the link (Bluetooth or gateway) is already in the status pill at the top, so the bar is hidden
    // to give the space back to the map. As soon as a movement or a test is running, the global STOP returns.
    val mapIdle = tab == Tab.Appareil && page == DevicePage.MAP && !robotActive
    val stopAvailable = (stopLive || stopGrace) && !mapIdle
    val pushed = (tab == Tab.Appareil && page != DevicePage.HOME) || (tab == Tab.Moi && accountPage && !localOnly) || (tab == Tab.Service && robotTab.isNotEmpty())
    Scaffold(
        containerColor = MammotionColors.Background,
        bottomBar = {
            Column {
                // Stop always reachable as soon as a link or a test is active.
                if (stopAvailable) androidx.compose.material3.HorizontalDivider(color = MammotionColors.Hairline)
                if (stopAvailable) Surface(color = MammotionColors.Surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when {
                            state.robotRoute.running -> tr("Local mowing in progress", "Tonte locale en cours")
                            state.manualActive -> tr("Manual control", "Pilotage manuel")
                            state.mowing.active -> state.mowing.phase.label
                            state.navTest.running -> tr("Navigation in progress", "Navigation en cours")
                            state.bladeTestActive -> tr("Blade test", "Essai des lames")
                            state.teach.running -> tr("Obstacle test", "Essai obstacle")
                            state.campaign.running -> tr("Diagnostics in progress", "Diagnostic en cours")
                            else -> if (state.viaGateway) state.connection.label.replace("BLE", tr("Gateway", "Passerelle")) else state.connection.label
                        }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MammotionColors.TextSecondary)
                        StopAction(robot::stop)
                    }
                }
                if (!pushed) androidx.compose.material3.HorizontalDivider(color = MammotionColors.Hairline)
                if (!pushed) NavigationBar(containerColor = MammotionColors.Surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                // Me: dot when a new version is published on GitHub (Me → About → Updates).
                                if (entry == Tab.Moi && update.available != null) androidx.compose.material3.BadgedBox(
                                    badge = { androidx.compose.material3.Badge(containerColor = MammotionColors.Green) }) {
                                    Icon(entry.icon, contentDescription = entry.label + tr(", new version available", ", nouvelle version disponible"))
                                } else Icon(entry.icon, contentDescription = entry.label)
                            },
                            label = { Text(entry.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MammotionColors.Ink,
                                selectedTextColor = MammotionColors.Ink,
                                indicatorColor = MammotionColors.GreenSoft,
                                unselectedIconColor = MammotionColors.TextTertiary,
                                unselectedTextColor = MammotionColors.TextTertiary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The home screen extends under the status bar (robot showcase); the other screens keep the margin.
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        val homeEdge = tab == Tab.Appareil && page == DevicePage.HOME
        Box(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
            top = if (homeEdge) 0.dp else padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            when (tab) {
                Tab.Appareil -> when (page) {
                    // Phone mounted on the robot: its home screen is the gateway's (pairing QR code, state, checks).
                    DevicePage.HOME -> if (role == fr.luba.poc.gateway.GatewayRole.GATEWAY)
                        fr.luba.poc.gateway.GatewayHomeScreen(onOpenSettings = { openService(fr.luba.poc.ui.ServicePage.RELAY.key) })
                    else HomeScreen(
                        state = panels,
                        cloudState = cloudState,
                        map = map,
                        onOpenMap = { openDevice(DevicePage.MAP) },
                        onStartMowing = { openMowing = !(state.mowing.active || state.robotRoute.running); openDevice(DevicePage.MAP) },
                        onFetch = { useCloudMap = false; robot.fetchMap() },
                        onAccount = { tab = Tab.Moi; accountPage = true },
                        onOpenRobot = { openService(if (state.ready) "" else "Robot") },
                        onOpenManual = { openDevice(DevicePage.MANUAL) },
                        onAlerts = { showAlerts = true },
                        alertsBadge = !alerts.enabled || alerts.unseen > 0,
                        // Account following this robot, or no known BLE robot: the API is authoritative.
                        apiTelemetry = cloudState.telemetry.takeIf { matchingCloudRobot || (state.selected == null && state.lastKnown == null) },
                        checklist = {
                            val done = fr.luba.poc.onboarding.rememberSetupDone(context)
                            if (!fr.luba.poc.onboarding.SetupProgress.checklistHidden(context)) fr.luba.poc.onboarding.FirstMowingCard(
                                fr.luba.poc.onboarding.FirstMowingChecklist.items(done, state.ready, map.complete, state.motorWatchdog != null),
                                onOpen = { target -> when (target) {
                                    fr.luba.poc.onboarding.SetupTarget.CONNECTION -> openService(fr.luba.poc.ui.ServicePage.CONNECTION.key)
                                    fr.luba.poc.onboarding.SetupTarget.SETUP_CHECK -> openService(fr.luba.poc.ui.ServicePage.SETUP.key)
                                    fr.luba.poc.onboarding.SetupTarget.MAP -> openDevice(DevicePage.MAP)
                                    fr.luba.poc.onboarding.SetupTarget.COMMAND_CUTOFF -> openService(fr.luba.poc.ui.ServicePage.WATCHDOG.key)
                                    fr.luba.poc.onboarding.SetupTarget.MANUAL -> openDevice(DevicePage.MANUAL)
                                    fr.luba.poc.onboarding.SetupTarget.NAVIGATION -> openService(fr.luba.poc.ui.ServicePage.NAVIGATION.key)
                                } },
                                onHide = { fr.luba.poc.onboarding.SetupProgress.hideChecklist(context) })
                        },
                    )
                    DevicePage.MAP -> { MapScreen(
                        simulation = simulation,
                        openMowing = openMowing,
                        onMowingOpened = { openMowing = false },
                        openSurvey = openSurvey,
                        onSurveyOpened = { openSurvey = false },
                        state = panels,
                        onSaveCorrections = robot::saveMapCorrections,
                        onFetchMap = { useCloudMap = false; robot.fetchMap() },
                        onOpenRobot = { openService("Robot") },
                        onOpenAccount = { tab = Tab.Moi; accountPage = true },
                        onBack = { page = DevicePage.HOME },
                        onOpenManual = { page = DevicePage.MANUAL },
                        onOpenDrivenRecord = { recordFrom = DevicePage.MAP; page = DevicePage.RECORD },
                        cloud = displayCloud,
                        apiPosition = cloudState.telemetry.takeIf { matchingCloudRobot },
                        apiKnown = cloudState.lastKnown.takeIf { matchingCloudRobot },
                        onPreview = robot::recordMapPreview,
                        onSimulationPreparation = robot::recordSimulationPreparation,
                        onStartRobotRoute = robot::startRobotRoute,
                        onStartPointNavigation = robot::startPointNavigation,
                        onDiscardPointResume = robot::discardPointResume,
                        onUnlockRobot = robot::unlockAfterStop,
                        onTrajectorySpeed = robot::setTrajectorySpeed,
                        onBaseAction = robot::baseAction,
                        onResumeRobotRoute = robot::resumeRobotRoute,
                        onBackOffBeforeResume = robot::backOffBeforeResume,
                        onStopBackOff = robot::stopBackOff,
                        onDiscardMissionCheckpoint = robot::discardMissionCheckpoint,
                        onStopMammotionTask = robot::stopMammotionTask,
                        // Without BLE: native return through the account, if it follows this robot (or if no BLE robot is known).
                        cloudReturn = cloudState.nativeReturn.takeIf { cloudState.connected && cloudState.selected != null &&
                            (matchingCloudRobot || (state.selected == null && state.lastKnown == null)) }
                            ?.copy(presence = fr.luba.poc.map.StationPresence.of(cloudState.telemetry,
                                fr.luba.poc.ui.components.CLOUD_CHARGE_MAX_AGE_MS, System.currentTimeMillis())),
                        onCloudReturn = cloud::nativeReturnToBase,
                        onOpenTask = robot::showTaskDetail,
                        onOpenAllTasks = { openService(fr.luba.poc.ui.ServicePage.HISTORY.key) },
                        onCancelCloudReturn = cloud::cancelNativeReturn,
                        cloudRobotName = cloudState.selected?.name,
                        onStopPointNavigation = {
                            // Fold folding: screen recreated, the move continues (global STOP available).
                            if ((context as? android.app.Activity)?.isChangingConfigurations != true)
                                robot.stopNavTest(tr("Move to a point stopped / left the map", "Arrêt du déplacement au point / sortie carte"), userInitiated = true)
                        },
                        onStop = robot::stop,
                        startDemo = demoMap,
                    )
                        // Border types editor, opened from the Map sheet (kept outside MapScreen, at its register limit).
                        fr.luba.poc.map.BorderTypeEditorHost(panels.robotMap)
                        // Sync with the Mammotion app, opened from the Map sheet (same reason).
                        fr.luba.poc.mapsync.MapSyncHost(robot, state)
                    }
                    DevicePage.MANUAL -> fr.luba.poc.ui.ManualPage(robot, state, back = { page = DevicePage.HOME },
                        onOpenRecord = { recordFrom = DevicePage.MANUAL; page = DevicePage.RECORD })
                    DevicePage.RECORD -> fr.luba.poc.manual.DrivenRecordPage(robot, state, back = { page = recordFrom })
                }
                Tab.Service -> RobotPanel(
                    robot = robot,
                    tab = robotTab,
                    onTabChange = { robotTab = it },
                    state = panels,
                    cloud = cloud,
                    cloudState = cloudState,
                    onShowSignalsOnMap = { openDevice(DevicePage.MAP) },
                    onSignalSurveyRoute = { openSurvey = true; openDevice(DevicePage.MAP) },
                )
                Tab.Moi -> if (accountPage && !localOnly) fr.luba.poc.ui.components.PushedPage(tr("Mammotion account", "Compte Mammotion"), onBack = { accountPage = false }, contentPadding = 0.dp) {
                    AccountScreen(cloud, cloudState, onOpenMap = { useCloudMap = true; accountPage = false; openDevice(DevicePage.MAP) })
                } else fr.luba.poc.ui.MeScreen(
                    cloud = cloudState, themeMode = themeMode, onThemeModeChange = onThemeModeChange,
                    onLanguageChange = onLanguageChange,
                    onAccount = { accountPage = true }, onAlerts = { showAlerts = true },
                    localOnly = localOnly,
                    // Local mode: session deleted (saved credentials kept); refused while a return or an update is followed.
                    localOnlyLocked = cloudState.nativeReturn.active || cloudState.firmwareUpdate.active,
                    onLocalOnly = { enabled ->
                        if (!enabled) { fr.luba.poc.auth.AccountMode.setLocalOnly(false); cloud.leaveLocalMode() }
                        else if (cloud.enterLocalMode()) { fr.luba.poc.auth.AccountMode.setLocalOnly(true); accountPage = false; useCloudMap = false }
                    },
                    onOpenMap = { useCloudMap = true; openDevice(DevicePage.MAP) },
                    alertsSummary = if (!alerts.enabled) tr("Notifications blocked · allow", "Notifications bloquées · autoriser") else alerts.entries.firstOrNull()?.headline ?: tr("Local alerts enabled", "Alertes locales activées"),
                    update = update,
                    onCheckUpdate = fr.luba.poc.update.UpdateCheck::checkNow,
                    onOpenRelease = { release ->
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(release.pageUrl))) }
                    },
                    onAutomaticUpdates = fr.luba.poc.update.UpdateCheck::setAutomatic,
                )
            }
            // Shared video player: Camera page and obstacle photo after a bumper contact.
            CameraHost(robot, state, cloud, cloudState)
            // End-of-mowing report (or opened from the history), over the current screen.
            // Detail of a history task: replay of the actual route on the map and report, full screen.
            state.taskDetail?.let { id -> state.mowingHistory.firstOrNull { it.id == id } }?.let { record ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { robot.showTaskDetail(null) },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    fr.luba.poc.history.TaskDetailPage(record, state.mowingHistory,
                        fallbackMap = state.robotMap.takeIf { it.complete && state.selected?.address == record.robotId },
                        loadTrack = robot::loadTaskTrack, onExcluded = { robot.setMowingExcluded(record.id, it) },
                        onBack = { robot.showTaskDetail(null) },
                        onFinishing = finishingFor(record.robotId) { robot.showTaskDetail(null) })
                }
            }
            state.mowingReport?.let { report ->
                fr.luba.poc.history.MowingReportDialog(report, state.mowingHistory,
                    onExcluded = { robot.setMowingExcluded(report.id, it) }, onDismiss = { robot.showMowingReport(null) },
                    loadTrack = robot::loadTaskTrack,
                    fallbackMap = state.robotMap.takeIf { it.complete && state.selected?.address == report.robotId },
                    onFinishing = finishingFor(report.robotId) { robot.showMowingReport(null) })
            }
        }
    }
}

