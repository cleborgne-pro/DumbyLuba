package fr.luba.poc

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.luba.poc.alerts.LocalNotifications
import fr.luba.poc.alerts.NotificationUi
import fr.luba.poc.auth.AccountMode
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotShellUi
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.gateway.GatewayPairing
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.PairingConfirmDialog
import fr.luba.poc.gateway.rememberGatewayRole
import fr.luba.poc.i18n.AppLanguage
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.ManualContactSheet
import fr.luba.poc.map.MapExtraLayers
import fr.luba.poc.onboarding.SetupProgress
import fr.luba.poc.onboarding.SetupStep
import fr.luba.poc.onboarding.WizardExit
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.NotificationsDialog
import fr.luba.poc.ui.ServicePage
import fr.luba.poc.ui.components.StopAction
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.ThemeMode
import fr.luba.poc.update.UpdateCheck
import kotlinx.coroutines.delay

/** App shell under [MainActivity]: tabs, pushed pages, global STOP bar, dialogs and the content of each page. */
@Composable
internal fun RobotScreen(
    robot: RobotBle,
    alertOpenToken: Int,
    mapOpenToken: Int = 0,
    simulation: SimulationSession,
    cloud: CloudViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    pairing: GatewayPairing? = null,
    onPairingHandled: () -> Unit = {},
    /** Page asked by the first-launch assistant when it closes. */
    startAt: WizardExit? = null,
) {
    val cloudState by cloud.state.collectAsStateWithLifecycle()
    // Shell: connection and running activities only; the page content collects the full state ([RobotStateScope]).
    val shell by robot.shell.collectAsStateWithLifecycle()
    val alerts by robot.localAlerts.collectAsStateWithLifecycle()
    val update by UpdateCheck.flow.collectAsStateWithLifecycle()
    val localOnly by AccountMode.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = rememberAppNavigation()
    var showAlerts by remember { mutableStateOf(false) }
    LaunchedEffect(alertOpenToken) { if(alertOpenToken > 0) showAlerts=true }
    NotificationsHost(robot, alerts, showAlerts, onDismiss = { showAlerts = false })
    // Contact while driving with the joystick: the obstacle sheet opens over any screen (keep with its size, or dismiss).
    if (shell.manualContactId != null) RobotStateScope(robot) { state ->
        ManualContactSheet(state.manualContactId, state.difficulties, state.obstaclePhoto.photos, state.obstacles,
            actions = { action -> MapExtraLayers.actions?.invoke(action) }, onClose = robot::closeManualContact)
    }
    LaunchedEffect(mapOpenToken) { if (mapOpenToken > 0) nav.openDevice(DevicePage.MAP) }
    val role = rememberGatewayRole()
    LaunchedEffect(startAt) {
        when (startAt) {
            WizardExit.DEMO_MAP -> { nav.demoMap = true; nav.openDevice(DevicePage.MAP) }
            WizardExit.SETUP_CHECK -> nav.openService(ServicePage.SETUP.key)
            else -> Unit
        }
    }
    pairing?.let { link ->
        PairingConfirmDialog(link, linkIdle = shell.connection == ConnectionState.Disconnected,
            isGateway = role == GatewayRole.GATEWAY,
            onConfirm = { GatewayPairing.apply(context, link); onPairingHandled(); nav.openService(ServicePage.RELAY.key) },
            onDismiss = onPairingHandled)
    }
    ShellEffects(robot, shell, nav)
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    AccountPositionRefresh(cloud, accountLinked = cloudState.connected && !shell.ready, nav)
    val robotActive = shell.robotActive
    // Brief gateway dropout (automatic reconnection within 2 to 10 s): the STOP bar stays visible for 15 s,
    // so the map is not resized on every micro-dropout. STOP there cancels the pending reconnection.
    val stopLive = shell.connection != ConnectionState.Disconnected || robotActive
    var stopGrace by remember { mutableStateOf(false) }
    LaunchedEffect(stopLive) { if (stopLive) stopGrace = true else { delay(15_000); stopGrace = false } }
    // Map at rest: the link (Bluetooth or gateway) is already in the status pill at the top, so the bar is hidden
    // to give the space back to the map. As soon as a movement or a test is running, the global STOP returns.
    val mapIdle = nav.tab == Tab.Appareil && nav.page == DevicePage.MAP && !robotActive
    val stopAvailable = (stopLive || stopGrace) && !mapIdle
    Scaffold(
        containerColor = MammotionColors.Background,
        bottomBar = {
            RobotBottomBar(shell, nav, stopAvailable = stopAvailable, tabBar = !nav.pushed(localOnly),
                updateAvailable = update.available != null, onStop = robot::stop)
        },
    ) { padding ->
        // The home screen extends under the status bar (robot showcase); the other screens keep the margin.
        val direction = LocalLayoutDirection.current
        val homeEdge = nav.tab == Tab.Appareil && nav.page == DevicePage.HOME
        Box(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
            top = if (homeEdge) 0.dp else padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            RobotPages(robot, nav, simulation, cloud, cloudState, localOnly, isGateway = role == GatewayRole.GATEWAY,
                alerts = alerts, onShowAlerts = { showAlerts = true }, me = MeTabSettings(themeMode, onThemeModeChange, onLanguageChange, update))
        }
    }
}

/** Local notifications panel, opened from the bell or a notification, and the Android settings it leads to. */
@Composable
private fun NotificationsHost(robot: RobotBle, alerts: NotificationUi, show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { robot.refreshNotifications() }
    // Panel open: the displayed notifications count as seen (bell badge off), including those arriving while reading.
    LaunchedEffect(show, alerts.entries.firstOrNull()?.at) { if (show) robot.markNotificationsSeen() }
    // Back from Android settings: re-read the channel state.
    LifecycleResumeEffect(show) { if (show) robot.refreshNotifications(); onPauseOrDispose { } }
    if(show) NotificationsDialog(alerts, onDismiss = onDismiss, onTest = { robot.testNotification(it) }, onSettings = {
        if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
    }, onChannelSettings = { level ->
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, LocalNotifications.channelId(level)))
    }, onClear = robot::clearNotificationHistory)
}

/**
 * What the shell does by itself, whatever the page: map reading and account map choice, first-mowing steps,
 * background Bluetooth search, robot choice and keeping the screen on during an activity.
 */
@Composable
private fun ShellEffects(robot: RobotBle, shell: RobotShellUi, nav: AppNavigation) {
    val context = LocalContext.current
    LaunchedEffect(shell.ready) { if (shell.ready) nav.useCloudMap = false }
    // Connected robot without any known map (new installation, cache cleared): its map is read over BLE by itself,
    // once per connection, instead of an empty map that the account map cannot fill while the robot is linked.
    val receiving = shell.connection == ConnectionState.Receiving
    LaunchedEffect(receiving, shell.selectedAddress, shell.mammotionMapEmpty) {
        if (receiving && shell.mammotionMapEmpty) robot.autoFetchMap()
    }
    // First-mowing list: steps remembered once seen done (robot linked, manual control, route with blades stopped).
    LaunchedEffect(shell.ready) { if (shell.ready) SetupProgress.mark(context, SetupStep.ROBOT_CONNECTED) }
    LaunchedEffect(shell.manualActive) { if (shell.manualActive) SetupProgress.mark(context, SetupStep.MANUAL_TRIED) }
    val bladesOffRoute = (shell.navRunning && !shell.navMowing) || (shell.routeRunning && !shell.routeCutting && !shell.mowingActive)
    LaunchedEffect(bladesOffRoute) { if (bladesOffRoute) SetupProgress.mark(context, SetupStep.BLADES_OFF_ROUTE) }
    // Background Bluetooth search every time the app returns to the foreground, whatever the screen:
    // direct connection to the only robot found, otherwise a message and the selection page.
    val btPermissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) robot.scan(autoConnect = true) }
    var btPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val latestShell by rememberUpdatedState(shell)
    LifecycleStartEffect(Unit) {
        val s = latestShell
        if (s.connection == ConnectionState.Disconnected && !s.scanning && !s.campaignRunning && !s.bladeTestActive) {
            if (btPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) robot.scan(autoConnect = true)
            else if (!btPermissionAsked) { btPermissionAsked = true; btPermissionLauncher.launch(btPermissions) }
        }
        onStopOrDispose { }
    }
    // Control via the gateway: automatic reconnection handled by RobotBle (foreground or not).
    LaunchedEffect(Unit) {
        robot.robotChoice.collect {
            Toast.makeText(context, tr("Several robots found: choose the one to connect.", "Plusieurs robots détectés : choisissez celui à connecter."), Toast.LENGTH_LONG).show()
            nav.openService("Robot")
        }
    }
    val view = LocalView.current
    DisposableEffect(shell.campaignRunning, shell.bladeTestActive, shell.navRunning, shell.mowingActive, (shell.manualActive || shell.routeRunning)) {
        view.keepScreenOn = shell.campaignRunning || shell.bladeTestActive || shell.navRunning || shell.mowingActive || (shell.manualActive || shell.routeRunning)
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Mammotion account reading (position, battery, charging contact) every 30 s on the home screen and the map, while
 * the account follows the robot without a BLE link ([accountLinked]) and only while the app is in the foreground.
 */
@Composable
private fun AccountPositionRefresh(cloud: CloudViewModel, accountLinked: Boolean, nav: AppNavigation) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shown = nav.tab == Tab.Appareil && nav.page in setOf(DevicePage.HOME, DevicePage.MAP)
    LaunchedEffect(shown, accountLinked) {
        if (shown && accountLinked) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    cloud.refreshPosition()
                    delay(30000)
                }
            }
        }
    }
}

/** Global STOP bar (as soon as a link or a test is active) above the tab bar (hidden under a pushed page). */
@Composable
private fun RobotBottomBar(shell: RobotShellUi, nav: AppNavigation, stopAvailable: Boolean, tabBar: Boolean, updateAvailable: Boolean, onStop: () -> Unit) {
    Column {
        if (stopAvailable) HorizontalDivider(color = MammotionColors.Hairline)
        if (stopAvailable) Surface(color = MammotionColors.Surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(activityLabel(shell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MammotionColors.TextSecondary)
                StopAction(onStop)
            }
        }
        if (tabBar) HorizontalDivider(color = MammotionColors.Hairline)
        if (tabBar) NavigationBar(containerColor = MammotionColors.Surface, tonalElevation = 0.dp) {
            Tab.entries.forEach { entry ->
                NavigationBarItem(
                    selected = nav.tab == entry,
                    onClick = { nav.tab = entry },
                    icon = {
                        // Me: dot when a new version is published on GitHub (Me → About → Updates).
                        if (entry == Tab.Moi && updateAvailable) BadgedBox(
                            badge = { Badge(containerColor = MammotionColors.Green) }) {
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
}

/** Activity shown beside the global STOP, or the link when nothing runs. */
private fun activityLabel(shell: RobotShellUi): String = when {
    shell.routeRunning -> tr("Local mowing in progress", "Tonte locale en cours")
    shell.manualActive -> tr("Manual control", "Pilotage manuel")
    shell.mowingActive -> shell.mowingPhase.label
    shell.navRunning -> tr("Navigation in progress", "Navigation en cours")
    shell.bladeTestActive -> tr("Blade test", "Essai des lames")
    shell.teachRunning -> tr("Obstacle test", "Essai obstacle")
    shell.campaignRunning -> tr("Diagnostics in progress", "Diagnostic en cours")
    else -> if (shell.viaGateway) shell.connection.label.replace("BLE", tr("Gateway", "Passerelle")) else shell.connection.label
}

/** Collects the full robot state in its own recomposition scope: only [content] follows the telemetry. */
@Composable
internal fun RobotStateScope(robot: RobotBle, content: @Composable (RobotUi) -> Unit) {
    val state by robot.state.collectAsStateWithLifecycle()
    content(state)
}
