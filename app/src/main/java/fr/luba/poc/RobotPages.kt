package fr.luba.poc

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import fr.luba.poc.alerts.NotificationUi
import fr.luba.poc.auth.AccountMapDisplay
import fr.luba.poc.auth.AccountMode
import fr.luba.poc.auth.AccountScreen
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ble.withCorrectedMap
import fr.luba.poc.camera.CameraHost
import fr.luba.poc.gateway.GatewayHomeScreen
import fr.luba.poc.history.MowingReportDialog
import fr.luba.poc.history.TaskDetailPage
import fr.luba.poc.i18n.AppLanguage
import fr.luba.poc.i18n.tr
import fr.luba.poc.manual.DrivenRecordPage
import fr.luba.poc.map.BorderTypeEditorHost
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.StationPresence
import fr.luba.poc.mapsync.MapSyncHost
import fr.luba.poc.onboarding.FirstMowingCard
import fr.luba.poc.onboarding.FirstMowingChecklist
import fr.luba.poc.onboarding.SetupProgress
import fr.luba.poc.onboarding.SetupTarget
import fr.luba.poc.onboarding.rememberSetupDone
import fr.luba.poc.planning.FinishingOffer
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.FinishingDraft
import fr.luba.poc.ui.HomeScreen
import fr.luba.poc.ui.ManualPage
import fr.luba.poc.ui.MeScreen
import fr.luba.poc.ui.RobotPanel
import fr.luba.poc.ui.ServicePage
import fr.luba.poc.ui.components.CLOUD_CHARGE_MAX_AGE_MS
import fr.luba.poc.ui.components.PushedPage
import fr.luba.poc.ui.theme.ThemeMode
import fr.luba.poc.update.UpdateCheck
import fr.luba.poc.update.UpdateState

/** Settings and update state shown on the Me tab. */
internal class MeTabSettings(
    val themeMode: ThemeMode,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onLanguageChange: (AppLanguage) -> Unit,
    val update: UpdateState,
)

/**
 * Content of the current tab and page, with the dialogs shown over it: the only part of the screen that follows the
 * full robot state (telemetry included). Before the first robot link, the account map stands in for the robot map.
 */
@Composable
internal fun BoxScope.RobotPages(
    robot: RobotBle,
    nav: AppNavigation,
    simulation: SimulationSession,
    cloud: CloudViewModel,
    cloudState: CloudUi,
    localOnly: Boolean,
    isGateway: Boolean,
    alerts: NotificationUi,
    onShowAlerts: () -> Unit,
    me: MeTabSettings,
) = RobotStateScope(robot) { state ->
    val displayCloud = AccountMapDisplay.showAccountMap(state.ready, localOnly, nav.useCloudMap, cloudState.map.shapes.size,
        cloudState.map.complete, state.robotMap.shapes.size)
    // Account map: same local corrections as the Bluetooth map (shapes targeted by their Mammotion identifier),
    // applied once per map or corrections change rather than on every state emission: a new map instance
    // each second made every map layer draw again.
    val cloudMap = cloudState.map
    val cloudCorrected = remember(displayCloud, cloudMap, state.mapCorrections) {
        if (displayCloud) MapCorrections.apply(cloudMap, state.mapCorrections) else null
    }
    val shown = if (cloudCorrected != null) state.withCorrectedMap(cloudMap, cloudCorrected).copy(telemetry = Telemetry(), lastKnown = cloudState.lastKnown, positionSaveError = cloudState.positionSaveError, connection = ConnectionState.Disconnected) else state
    val matchingCloudRobot = displayCloud || (state.selected?.name ?: state.lastKnown?.robotName)?.equals(cloudState.selected?.name ?: cloudState.lastKnown?.robotName,ignoreCase=true) == true
    val panels = state.copy(robotMap = shown.robotMap, mammotionMap = shown.mammotionMap, correctedMap = shown.correctedMap, lastKnown = shown.lastKnown)
    // Finishing of a mowing's gaps: only for the connected robot of that mowing with its complete map; opens the
    // trial mowing page targeting the gaps after closing the report ([close]).
    val finishingFor = { robotId: String?, close: () -> Unit ->
        if (state.robotMap.complete && robotId != null && state.selected?.address == robotId) { offer: FinishingOffer ->
            FinishingDraft.set(state.robotMap, offer)
            close()
            nav.openService(ServicePage.MOW_DEBUG.key)
        } else null
    }
    when (nav.tab) {
        Tab.Appareil -> DevicePages(robot, nav, simulation, cloud, cloudState, state, panels, DeviceAccountView(displayCloud, matchingCloudRobot),
            isGateway, alertsBadge = !alerts.enabled || alerts.unseen > 0, onShowAlerts)
        Tab.Service -> RobotPanel(
            robot = robot,
            tab = nav.serviceTab,
            onTabChange = { nav.serviceTab = it },
            state = panels,
            cloud = cloud,
            cloudState = cloudState,
            onShowSignalsOnMap = { nav.openDevice(DevicePage.MAP) },
            onSignalSurveyRoute = { nav.openSurvey = true; nav.openDevice(DevicePage.MAP) },
        )
        Tab.Moi -> MeTab(nav, cloud, cloudState, localOnly, alerts, onShowAlerts, me)
    }
    RobotOverlays(robot, state, cloud, cloudState, finishingFor)
}

/** How the account map relates to the robot of the Device pages. */
private class DeviceAccountView(
    /** The account map stands in for the robot map. */
    val displayCloud: Boolean,
    /** The account follows the robot of these pages (or shows its map): its telemetry is authoritative. */
    val matchingCloudRobot: Boolean,
)

/** Device tab: home screen, map, manual control and record by driving. */
@Composable
private fun DevicePages(
    robot: RobotBle,
    nav: AppNavigation,
    simulation: SimulationSession,
    cloud: CloudViewModel,
    cloudState: CloudUi,
    state: RobotUi,
    panels: RobotUi,
    account: DeviceAccountView,
    isGateway: Boolean,
    alertsBadge: Boolean,
    onShowAlerts: () -> Unit,
) {
    val context = LocalContext.current
    val map = panels.robotMap
    val displayCloud = account.displayCloud
    val matchingCloudRobot = account.matchingCloudRobot
    when (nav.page) {
        // Phone mounted on the robot: its home screen is the gateway's (pairing QR code, state, checks).
        DevicePage.HOME -> if (isGateway)
            GatewayHomeScreen(onOpenSettings = { nav.openService(ServicePage.RELAY.key) })
        else HomeScreen(
            state = panels,
            cloudState = cloudState,
            map = map,
            onOpenMap = { nav.openDevice(DevicePage.MAP) },
            onStartMowing = { nav.openMowing = !(state.mowing.active || state.robotRoute.running); nav.openDevice(DevicePage.MAP) },
            onFetch = { nav.useCloudMap = false; robot.fetchMap() },
            onAccount = { nav.openAccount() },
            onOpenRobot = { nav.openService(if (state.ready) "" else "Robot") },
            onOpenManual = { nav.openDevice(DevicePage.MANUAL) },
            onAlerts = onShowAlerts,
            alertsBadge = alertsBadge,
            // Account following this robot, or no known BLE robot: the API is authoritative.
            apiTelemetry = cloudState.telemetry.takeIf { matchingCloudRobot || (state.selected == null && state.lastKnown == null) },
            checklist = {
                val done = rememberSetupDone(context)
                if (!SetupProgress.checklistHidden(context)) FirstMowingCard(
                    FirstMowingChecklist.items(done, state.ready, map.complete, state.motorWatchdog != null),
                    onOpen = { target -> when (target) {
                        SetupTarget.CONNECTION -> nav.openService(ServicePage.CONNECTION.key)
                        SetupTarget.SETUP_CHECK -> nav.openService(ServicePage.SETUP.key)
                        SetupTarget.MAP -> nav.openDevice(DevicePage.MAP)
                        SetupTarget.COMMAND_CUTOFF -> nav.openService(ServicePage.WATCHDOG.key)
                        SetupTarget.MANUAL -> nav.openDevice(DevicePage.MANUAL)
                        SetupTarget.NAVIGATION -> nav.openService(ServicePage.NAVIGATION.key)
                    } },
                    onHide = { SetupProgress.hideChecklist(context) })
            },
        )
        DevicePage.MAP -> { MapScreen(
            simulation = simulation,
            openMowing = nav.openMowing,
            onMowingOpened = { nav.openMowing = false },
            openSurvey = nav.openSurvey,
            onSurveyOpened = { nav.openSurvey = false },
            state = panels,
            onSaveCorrections = robot::saveMapCorrections,
            onFetchMap = { nav.useCloudMap = false; robot.fetchMap() },
            onOpenRobot = { nav.openService("Robot") },
            onOpenAccount = { nav.openAccount() },
            onBack = { nav.page = DevicePage.HOME },
            onOpenManual = { nav.page = DevicePage.MANUAL },
            onOpenDrivenRecord = { nav.openRecord(DevicePage.MAP) },
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
                ?.copy(presence = StationPresence.of(cloudState.telemetry,
                    CLOUD_CHARGE_MAX_AGE_MS, Clock.now())),
            onCloudReturn = cloud::nativeReturnToBase,
            onOpenTask = robot::showTaskDetail,
            onOpenAllTasks = { nav.openService(ServicePage.HISTORY.key) },
            onCancelCloudReturn = cloud::cancelNativeReturn,
            cloudRobotName = cloudState.selected?.name,
            onStopPointNavigation = {
                // Fold folding: screen recreated, the move continues (global STOP available).
                if ((context as? Activity)?.isChangingConfigurations != true)
                    robot.stopNavTest(tr("Move to a point stopped / left the map", "Arrêt du déplacement au point / sortie carte"), userInitiated = true)
            },
            onStop = robot::stop,
            startDemo = nav.demoMap,
        )
            // Border types editor, opened from the Map sheet (kept outside MapScreen, at its register limit).
            BorderTypeEditorHost(panels.robotMap)
            // Sync with the Mammotion app, opened from the Map sheet (same reason).
            MapSyncHost(robot, state)
        }
        DevicePage.MANUAL -> ManualPage(robot, state, back = { nav.page = DevicePage.HOME },
            onOpenRecord = { nav.openRecord(DevicePage.MANUAL) })
        DevicePage.RECORD -> DrivenRecordPage(robot, state, back = { nav.page = nav.recordFrom })
    }
}

/** Me tab: settings, local alerts, updates, and the Mammotion account page pushed over it. */
@Composable
private fun MeTab(
    nav: AppNavigation,
    cloud: CloudViewModel,
    cloudState: CloudUi,
    localOnly: Boolean,
    alerts: NotificationUi,
    onShowAlerts: () -> Unit,
    me: MeTabSettings,
) {
    val context = LocalContext.current
    val themeMode = me.themeMode
    val onThemeModeChange = me.onThemeModeChange
    val onLanguageChange = me.onLanguageChange
    val update = me.update
    if (nav.accountPage && !localOnly) PushedPage(tr("Mammotion account", "Compte Mammotion"), onBack = { nav.accountPage = false }, contentPadding = 0.dp) {
        AccountScreen(cloud, cloudState, onOpenMap = { nav.useCloudMap = true; nav.accountPage = false; nav.openDevice(DevicePage.MAP) })
    } else MeScreen(
        cloud = cloudState, themeMode = themeMode, onThemeModeChange = onThemeModeChange,
        onLanguageChange = onLanguageChange,
        onAccount = { nav.accountPage = true }, onAlerts = onShowAlerts,
        localOnly = localOnly,
        // Local mode: session deleted (saved credentials kept); refused while a return or an update is followed.
        localOnlyLocked = cloudState.nativeReturn.active || cloudState.firmwareUpdate.active,
        onLocalOnly = { enabled ->
            if (!enabled) { AccountMode.setLocalOnly(false); cloud.leaveLocalMode() }
            else if (cloud.enterLocalMode()) { AccountMode.setLocalOnly(true); nav.accountPage = false; nav.useCloudMap = false }
        },
        onOpenMap = { nav.useCloudMap = true; nav.openDevice(DevicePage.MAP) },
        alertsSummary = if (!alerts.enabled) tr("Notifications blocked · allow", "Notifications bloquées · autoriser") else alerts.entries.firstOrNull()?.headline ?: tr("Local alerts enabled", "Alertes locales activées"),
        update = update,
        onCheckUpdate = UpdateCheck::checkNow,
        onOpenRelease = { release ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri())) }
                .onFailure { Toast.makeText(context, tr("No app to open ${release.pageUrl}", "Aucune application pour ouvrir ${release.pageUrl}"), Toast.LENGTH_LONG).show() }
        },
        onAutomaticUpdates = UpdateCheck::setAutomatic,
    )
}

/** Shared video player, task detail and end-of-mowing report, over the current page. */
@Composable
private fun BoxScope.RobotOverlays(
    robot: RobotBle,
    state: RobotUi,
    cloud: CloudViewModel,
    cloudState: CloudUi,
    finishingFor: (String?, () -> Unit) -> ((FinishingOffer) -> Unit)?,
) {
    // Shared video player: Camera page and obstacle photo after a bumper contact.
    CameraHost(robot, state, cloud, cloudState)
    // End-of-mowing report (or opened from the history), over the current screen.
    // Detail of a history task: replay of the actual route on the map and report, full screen.
    state.taskDetail?.let { id -> state.mowingHistory.firstOrNull { it.id == id } }?.let { record ->
        Dialog(onDismissRequest = { robot.showTaskDetail(null) },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            TaskDetailPage(record, state.mowingHistory,
                fallbackMap = state.robotMap.takeIf { it.complete && state.selected?.address == record.robotId },
                loadTrack = robot::loadTaskTrack, onExcluded = { robot.setMowingExcluded(record.id, it) },
                onBack = { robot.showTaskDetail(null) },
                onFinishing = finishingFor(record.robotId) { robot.showTaskDetail(null) })
        }
    }
    state.mowingReport?.let { report ->
        MowingReportDialog(report, state.mowingHistory,
            onExcluded = { robot.setMowingExcluded(report.id, it) }, onDismiss = { robot.showMowingReport(null) },
            loadTrack = robot::loadTaskTrack,
            fallbackMap = state.robotMap.takeIf { it.complete && state.selected?.address == report.robotId },
            onFinishing = finishingFor(report.robotId) { robot.showMowingReport(null) })
    }
}
