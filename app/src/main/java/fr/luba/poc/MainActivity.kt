package fr.luba.poc

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import fr.luba.poc.auth.AccountMode
import fr.luba.poc.auth.CloudViewModel
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.gateway.GatewayConfig
import fr.luba.poc.gateway.GatewayPairing
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.GatewayService
import fr.luba.poc.i18n.LanguageStore
import fr.luba.poc.maintenance.BladeMaintenanceSettings
import fr.luba.poc.map.BorderTypeRepository
import fr.luba.poc.map.DifficultyAction
import fr.luba.poc.map.MapDisplaySettings
import fr.luba.poc.map.MapExtraLayers
import fr.luba.poc.map.OuterOffsetSetting
import fr.luba.poc.map.SurveyOffsetSetting
import fr.luba.poc.mowing.WorkWindowSetting
import fr.luba.poc.onboarding.SetupProgress
import fr.luba.poc.onboarding.SetupWizard
import fr.luba.poc.onboarding.WizardExit
import fr.luba.poc.relay.RelayApplication
import fr.luba.poc.relay.RelayService
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.simulation.SmartFeatures
import fr.luba.poc.ui.brand.DumbyIntro
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.ui.theme.ThemeStore
import fr.luba.poc.update.UpdateCheck
import fr.luba.poc.weather.WeatherService

class MainActivity : ComponentActivity() {
    private var alertOpenToken by mutableIntStateOf(0)
    /** "open_map" extra: opens the map (deployment check, shortcuts). */
    private var mapOpenToken by mutableIntStateOf(0)
    /** Gateway pairing link opened (dumbyluba://pair, QR code read by another app): confirmed before being applied. */
    private var pendingPairing by mutableStateOf<GatewayPairing?>(null)
    private lateinit var robot: RobotBle
    private lateinit var themeStore: ThemeStore
    private lateinit var simulation: SimulationSession

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_DumbyLuba)
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("local_alerts", false)) alertOpenToken++
        if(intent.getBooleanExtra("open_map", false)) mapOpenToken++
        if (savedInstanceState == null) pendingPairing = GatewayPairing.parse(intent?.dataString)
        // Gateway phone mounted on the robot: listening for the pilot resumes on every launch.
        if (GatewayConfig.read(this).role == GatewayRole.GATEWAY) GatewayService.start(this)
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.remove()
            }
        }
        enableEdgeToEdge()
        OuterOffsetSetting.init(applicationContext)
        BladeMaintenanceSettings.init(applicationContext)
        AccountMode.init(applicationContext)
        WorkWindowSetting.init(applicationContext)
        SmartFeatures.init(applicationContext)
        WeatherService.init(applicationContext)
        UpdateCheck.init(applicationContext)
        SurveyOffsetSetting.init(applicationContext)
        MapExtraLayers.init(applicationContext)
        MapDisplaySettings.init(applicationContext)
        BorderTypeRepository.init(applicationContext)
        robot = ViewModelProvider(application as RelayApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RobotBle::class.java]
        // Card of an obstacle encountered on the map: save it as an obstacle or dismiss the contact.
        robot.let { pilot -> MapExtraLayers.actions = { action ->
            when (action) {
                is DifficultyAction.Dismiss -> pilot.dismissDifficulty(action.id)
                is DifficultyAction.Register -> pilot.saveObstacle(action.obstacle)
                is DifficultyAction.Delete -> pilot.deleteObstacle(action.id)
                is DifficultyAction.DismissPresumed -> pilot.dismissPresumedObstacle(action.id)
            }
        } }
        simulation = ViewModelProvider(this)[SimulationSession::class.java]
        simulation.onTransit=robot::recordSimulationTransit
        val cloud = ViewModelProvider(this)[CloudViewModel::class.java]
        themeStore = ThemeStore(this)
        setContent {
            val activity = this@MainActivity
            var intro by remember { mutableStateOf(savedInstanceState == null) }
            // First launch of a fresh installation: assistant after the intro (role, permissions, pairing).
            var wizard by rememberSaveable { mutableStateOf(SetupProgress.showWizard(activity)) }
            var wizardExit by rememberSaveable { mutableStateOf<WizardExit?>(null) }
            var themeMode by rememberSaveable { mutableStateOf(themeStore.mode) }
            val dark = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                activity.enableEdgeToEdge(
                    statusBarStyle = if (dark || intro) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            LubaTheme(dark) {
                Crossfade(targetState = intro, animationSpec = tween(220), label = "DumbyLuba startup") { showIntro ->
                    if (showIntro) DumbyIntro(onFinished = { intro = false })
                    else if (wizard) SetupWizard(onExit = { wizardExit = it; wizard = false })
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
                        onLanguageChange = { LanguageStore.set(activity, it); activity.recreate() },
                        pairing = pendingPairing,
                        onPairingHandled = { pendingPairing = null },
                        startAt = wizardExit,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.getBooleanExtra("local_alerts", false)) alertOpenToken++
        if(intent.getBooleanExtra("open_map", false)) mapOpenToken++
        GatewayPairing.parse(intent.dataString)?.let { pendingPairing = it }
    }

    override fun onResume() {
        super.onResume()
        if (::robot.isInitialized) robot.refreshNotifications()
        // New version on GitHub: at most once a day, published build only, if enabled (Me → About).
        UpdateCheck.maybeCheck()
    }

    override fun onStop() {
        // Folding / unfolding the Fold or rotating: the activity is recreated at once and the app stays in the
        // foreground. Do not stop the mission or drop BLE for a mere screen change.
        if (isChangingConfigurations) { super.onStop(); return }
        simulation.pauseInBackground()
        robot.background(keepConnection = RelayService.status.value.running)
        super.onStop()
    }
}
