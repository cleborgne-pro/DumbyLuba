package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.diagnostics.MotorWatchdogResult
import fr.luba.poc.diagnostics.TestResult
import fr.luba.poc.gateway.GatewayConfig
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in screenshots (-PuiShots=folder) of the Service tab: index and pushed pages. No pixel assertions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsServiceTest {
    private val out: String? = System.getProperty("uiShots")
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(out != null); base.evaluate() } }
    }).around(compose)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val robot by lazy { fr.luba.poc.ble.RobotBle(ApplicationProvider.getApplicationContext()) }

    private fun state(connected: Boolean) = RobotUi(
        connection = if (connected) ConnectionState.Receiving else ConnectionState.Disconnected, ready = connected,
        selected = if (connected) SeenRobot("Luba-VS-DEMO", "C4:5B:BE:00:11:22", -61) else null, rssi = if (connected) -61 else null,
        telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4, firmware = "1.12.4"))

    private fun role(role: GatewayRole) = GatewayConfig.save(context, GatewayConfig.read(context).copy(role = role))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(dark) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun page(name: String, key: String, ui: RobotUi = state(true), dark: Boolean = false) =
        shot(name, dark) { RobotPanel(robot, ui, key, {}) }

    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun hub() { role(GatewayRole.OFF); page("service-hub", "") }
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun hubDark() { role(GatewayRole.OFF); page("service-hub-dark", "", state(false), dark = true) }
    @Test fun connectionConnected() { role(GatewayRole.OFF); page("service-connexion", "Robot") }
    @Test fun connectionScan() {
        role(GatewayRole.OFF)
        page("service-connexion-recherche", "Robot", state(false).copy(status = tr("BLE search; 1 paired LUBA available", "Recherche BLE ; 1 LUBA appairé(s) disponible(s)"),
            devices = listOf(SeenRobot("Luba-VS-DEMO", "C4:5B:BE:00:11:22", -58), SeenRobot("Luba-2AWD-1234", "C4:5B:BE:00:33:44", null))))
    }
    /** Service → Signal surveys: continuous map fed during moves, preview and history. */
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun signals() {
        val map = fr.luba.poc.map.RobotMap(listOf(fr.luba.poc.map.MapShape(1, 0, listOf(fr.luba.poc.map.Point(0.0, 0.0),
            fr.luba.poc.map.Point(20.0, 0.0), fr.luba.poc.map.Point(20.0, 12.0), fr.luba.poc.map.Point(0.0, 12.0)), "Pelouse")), true, "test")
        val samples = (0 until 200).map { i -> val x = (i % 20) + 0.5; val y = (i / 20) * 1.2 + 0.5
            fr.luba.poc.map.SignalSample(fr.luba.poc.map.Point(x, y), 1_760_000_000_000 + i * 1000L, wifi = (-45 - x * 2).toInt(),
                ble = -70, mobile = null, rtkLevel = if (i % 7 == 0) 1 else 0, satellites = 24) }
        page("service-releves-signaux", "Signaux", state(true).copy(robotMap = map, signalSurvey = fr.luba.poc.map.SignalSurvey("r",
            fr.luba.poc.map.SignalCoverage.mapKey(map), listOf(1), samples, "live-1", 1_760_000_000_000, live = true, mapSnapshot = map)))
    }
    @Test fun connectionIdle() { role(GatewayRole.OFF); page("service-connexion-vide", "Robot", state(false)) }
    @Test fun connectionGateway() { role(GatewayRole.GATEWAY); page("service-connexion-passerelle", "Robot", state(false)) }
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    @Test fun manual() = page("service-pilotage", "Pilotage")
    /** Record by driving (folded Fold): map following the robot, camera at the top, joysticks at the bottom. */
    @Config(qualifiers = "w412dp-h860dp-xxhdpi")
    @Test fun drivenRecordStart() {
        val lawn = fr.luba.poc.map.MapShape(1, 0, listOf(fr.luba.poc.map.Point(0.0, 0.0), fr.luba.poc.map.Point(10.0, 0.0),
            fr.luba.poc.map.Point(10.0, 8.0), fr.luba.poc.map.Point(0.0, 8.0)), "Pelouse")
        val map = fr.luba.poc.map.RobotMap(listOf(lawn), true, "test")
        page("service-pilotage-enregistrement-depart", "EnregistrementPilote", state(true).copy(robotMap = map, mammotionMap = map,
            telemetry = Telemetry(battery = 87, x = 9.0, y = 4.0, heading = 120.0, rtkStatus = 4, positioningLevel = 4, firmware = "1.12.4")))
    }
    /** Record by driving while a DumbyLuba zone is being driven: trace, closing circle and robot followed. */
    @Config(qualifiers = "w412dp-h860dp-xxhdpi")
    @Test fun manualRecording() {
        val lawn = fr.luba.poc.map.MapShape(1, 0, listOf(fr.luba.poc.map.Point(0.0, 0.0), fr.luba.poc.map.Point(10.0, 0.0),
            fr.luba.poc.map.Point(10.0, 8.0), fr.luba.poc.map.Point(0.0, 8.0)), "Pelouse")
        val map = fr.luba.poc.map.RobotMap(listOf(lawn), true, "test")
        val corners = listOf(fr.luba.poc.map.Point(9.5, 0.5), fr.luba.poc.map.Point(16.0, 0.5), fr.luba.poc.map.Point(16.0, 7.5), fr.luba.poc.map.Point(10.5, 7.5))
        val trace = corners.zipWithNext().flatMap { (a, b) -> (0 until 20).map { i -> fr.luba.poc.map.Point(a.x + (b.x - a.x) * i / 20, a.y + (b.y - a.y) * i / 20) } }
        val driven = fr.luba.poc.map.DrivenTrace(fr.luba.poc.map.DrivenKind.ZONE, 1L, trace)
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { RobotPanel(robot, state(true).copy(robotMap = map, mammotionMap = map, driven = driven,
            telemetry = Telemetry(battery = 87, x = trace.last().x, y = trace.last().y, heading = 180.0, rtkStatus = 4, positioningLevel = 4, firmware = "1.12.4")),
            "EnregistrementPilote", {}) } } }
        // Supervision confirmed: joysticks unlocked.
        compose.onNodeWithText(tr("I am supervising the robot", "Je supervise le robot")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/service-pilotage-enregistrement.png")
    }
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun watchdog() {
        val now = System.currentTimeMillis()
        page("service-coupure", "Watchdog", state(true).copy(motorWatchdog = MotorWatchdogResult("r", now, "1.12.4", 30,
            MotorWatchdogResult.ROBOT_STOPPED, 1800, 0.18, 0.12, tr("Stopped on its own 1.8 s after the last command.", "Arrêt seul constaté 1,8 s après la dernière commande."))))
    }
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun watchdogNone() = page("service-coupure-vide", "Watchdog", state(false))
    @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    @Test fun navigation() = page("service-navigation", "Navigation")
    @Test fun refusal() = page("service-refus", "Navigation", state(true).copy(navTest = fr.luba.poc.ble.NavTestUi(
        status = tr("Navigation refused: robot less than one meter from a limit", "Navigation refusée : robot à moins d'un mètre d'une limite"))))
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun campaign() = page("service-diagnostic", "Tests", state(true).copy(
        localResults = listOf(TestResult("a", tr("Frame decoding", "Décodage des trames"), "PASS LOCAL", 12, ""), TestResult("b", "Garde STOP", "PASS LOCAL", 4, "")),
        history = "c-1760000000 · PASS · Surveillance\nc-1759990000 · ABORTED · Cycles STOP"))
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun sensors() = page("service-capteurs", "Capteurs")
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun telemetry() = page("service-telemetrie", "Télémétrie")
    @Test fun logs() = page("service-journaux", "Logs", state(true).copy(events = List(30) { tr("12:00:${"%02d".format(it)} status Report received #$it", "12:00:${"%02d".format(it)} status Rapport reçu n°$it") }))
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    @Test fun homeServer() { role(GatewayRole.OFF); page("service-serveur-maison", "Serveur", state(false)) }
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun obstaclesList() = page("service-obstacles", "Obstacles")
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    @Test fun advanced() = page("service-reglages", "Réglages", state(false))
    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun relayDirect() { role(GatewayRole.OFF); page("service-passerelle-direct", "Passerelle", state(false)) }
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    @Test fun relayGateway() { role(GatewayRole.GATEWAY); page("service-passerelle-passerelle", "Passerelle", state(false)) }
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun relayClient() { role(GatewayRole.CLIENT); page("service-passerelle-pilote", "Passerelle", state(true)) }
    /** First-launch assistant: what this phone is for. */
    @Config(qualifiers = "w412dp-h1200dp-xxhdpi")
    @Test fun setupWizard() = shot("assistant-accueil") { fr.luba.poc.onboarding.SetupWizard(onExit = {}) }

    /** "Before the first mowing" card of the home screen, two steps done. */
    @Test fun firstMowingCard() = shot("accueil-premiere-tonte") {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            fr.luba.poc.onboarding.FirstMowingCard(fr.luba.poc.onboarding.FirstMowingChecklist.items(
                setOf(fr.luba.poc.onboarding.SetupStep.ROBOT_CONNECTED), ready = true, mapComplete = true, cutoffTested = false), onOpen = {}, onHide = {})
        }
    }

    /** Home screen of the phone mounted on the robot: pairing QR code, state and phone checks. */
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun gatewayHome() {
        role(GatewayRole.GATEWAY)
        fr.luba.poc.gateway.GatewayService.showcase(fr.luba.poc.gateway.GatewayStatus(running = true,
            message = tr("Waiting for the pilot phone", "En attente du téléphone de pilotage"), address = "192.168.1.42",
            robot = fr.luba.poc.gateway.GatewayRobotState("C4:5B:BE:00:11:22", "Luba-VS-DEMO", ready = true)))
        try { shot("passerelle-accueil") { fr.luba.poc.gateway.GatewayHomeScreen(onOpenSettings = {}) } }
        finally { fr.luba.poc.gateway.GatewayService.showcase(fr.luba.poc.gateway.GatewayStatus()) }
    }
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    @Test fun relayClientDark() { role(GatewayRole.CLIENT); page("service-passerelle-pilote-dark", "Passerelle", state(false), dark = true) }
}
