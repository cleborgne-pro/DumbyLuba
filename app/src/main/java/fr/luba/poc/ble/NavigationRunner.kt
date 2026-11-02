@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.autonomy.LegObstacle
import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.diagnostics.*
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Moves without mowing: navigation tests, obstacle courses, move to a point (with resume) and map route tests.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class NavigationRunner(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var bladeHeightRequest
        get() = host.bladeHeightRequest
        set(value) { host.bladeHeightRequest = value }
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var bladeOffPending
        get() = host.bladeOffPending
        set(value) { host.bladeOffPending = value }
    private var bladeOffWrittenAt
        get() = host.bladeOffWrittenAt
        set(value) { host.bladeOffWrittenAt = value }
    private var bladeStopVerified
        get() = host.bladeStopVerified
        set(value) { host.bladeStopVerified = value }
    private val calibration get() = host.calibration
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var confirmedHeading: Double?
        get() = host.confirmedHeading
        set(value) { host.confirmedHeading = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private val difficulties get() = host.difficulties
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private val legs get() = host.legs
    private var localIncidentPending
        get() = host.localIncidentPending
        set(value) { host.localIncidentPending = value }
    private val log get() = host.log
    private var motionWrittenAt
        get() = host.motionWrittenAt
        set(value) { host.motionWrittenAt = value }
    private var navBumperMode
        get() = host.navBumperMode
        set(value) { host.navBumperMode = value }
    private var navConfig
        get() = host.navConfig
        set(value) { host.navConfig = value }
    private var navContact: RouteContact?
        get() = host.navContact
        set(value) { host.navContact = value }
    private var navCourse: LocalObstacle?
        get() = host.navCourse
        set(value) { host.navCourse = value }
    private var navJob: Job?
        get() = host.navJob
        set(value) { host.navJob = value }
    private var navMapPlan: MapNavPlan?
        get() = host.navMapPlan
        set(value) { host.navMapPlan = value }
    private var navStarted
        get() = host.navStarted
        set(value) { host.navStarted = value }
    internal var navTestId = ""
    /**
     * Obstacle created at the last contact during a move to a point, estimated from the position published when the code
     * was received (up to 1 s old): moved in front of the stopped position before the back-off.
     */
    internal var navContactObstacle: String? = null
    private val obstaclePhotos get() = host.obstaclePhotos
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private val pointResumeWrites get() = host.pointResumeWrites
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private val queue get() = host.queue
    private var routeBladeGate: RouteBladeGate?
        get() = host.routeBladeGate
        set(value) { host.routeBladeGate = value }
    private val signalSurveys get() = host.signalSurveys
    private var slideField
        get() = host.slideField
        set(value) { host.slideField = value }
    private val state get() = host.state

    private var motionStarvedLogged = false
    private var navStart: NavSample? = null
    /** Heading gap confirmed at the end of the last drive to a point that reached its end ([NavConfig.keepHeading]). */
    private var navHeadingMemory: NavHeadingMemory? = null
    private var navCourseFar: Point? = null
    private var navCourseHome: Point? = null
    /** Obstacles already excluded by the current plan: only the others trigger the preventive stop. */
    private var navPlanObstacles: Set<LocalObstacle> = emptySet()
    /** Move to a point started by the frontal test itself (the only drive allowed during the test). */
    internal var borderPositioning = false

    fun startNavTest(config: NavConfig) = startMapNavigation(config)

    /** Bypass preview from the received position, without any motor command. */
    fun previewObstacleCourse(obstacleId: String) {
        if (state.value.navTest.running) return
        try {
            val (obstacle, plan) = obstacleCourse(obstacleId)
            val center = ObstacleCourse.plannedCenterDistance(plan.points, obstacle)
            update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedBands = emptyList(), plannedBorders = emptyList(), courseObstacleId = obstacle.id,
                preview = tr("Preview: %.1f m round trip · passing %.2f m from the center (reserve %.2f m). Recalculated at launch.", "Aperçu : %.1f m aller-retour · passage à %.2f m du centre (réserve %.2f m). Recalculé au lancement.").format(plan.meters, center, ObstacleCourse.keepOutM(obstacle)))) }
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(planned = emptyList(), plannedBands = emptyList(), plannedBorders = emptyList(),
                courseObstacleId = obstacleId, preview = tr("Route unavailable: ${error.message}", "Parcours indisponible : ${error.message}"))) }
        }
    }

    /**
     * Real bypass of a saved obstacle: drive to the opposite side and back, blades stopped, bumper detection
     * active, same guards as driving to a point. Result: minimum observed gap.
     */
    fun startObstacleCourse(obstacleId: String) {
        if (state.value.navTest.running || navJob != null) return
        try {
            val (obstacle, plan) = obstacleCourse(obstacleId)
            val t = state.value.telemetry
            navCourse = obstacle; navCourseHome = Point(requireNotNull(t.x), requireNotNull(t.y))
            navCourseFar = ObstacleCourse.farSide(requireNotNull(navCourseHome), obstacle)
            event("obstacle_course_plan", tr("Obstacle bypass prepared", "Contournement d'obstacle préparé"), mapOf("obstacleId" to obstacle.id, "x" to obstacle.x, "y" to obstacle.y,
                "keepOutM" to ObstacleCourse.keepOutM(obstacle), "plannedCenterDistanceM" to ObstacleCourse.plannedCenterDistance(plan.points, obstacle)))
            startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
                angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), preset = plan)
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(status = tr("Navigation refused: ${error.message}", "Navigation refusée : ${error.message}"))) }
            event("nav_test_refused", error.message ?: tr("Bypass refused", "Contournement refusé"))
        }
        if (!state.value.navTest.running) clearNavCourse()
    }

    private fun obstacleCourse(obstacleId: String): Pair<LocalObstacle, MapNavPlan> {
        val t = state.value.telemetry
        check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime()-positionPrevious, t.x, t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
        val obstacle = state.value.obstacles.firstOrNull { it.id == obstacleId } ?: error(tr("Obstacle not found", "Obstacle introuvable"))
        val start = Point(requireNotNull(t.x), requireNotNull(t.y))
        ObstacleCourse.startFailure(start, obstacle)?.let { error(it) }
        return obstacle to ObstacleCoursePlanner.plan(state.value.robotMap, start, ObstacleCourse.farSide(start, obstacle), start, state.value.obstacles)
    }

    /**
     * Full operation while moving: height set and reported, then a short route on the map (stripes and borders
     * with blades running, links with blades stopped), with the test-route guards; on end, stop or anomaly:
     * STOP and confirmed blade stop.
     */
    fun startBladeMoveTest(radiusM: Double, heightMm: Int, maxDurationS: Int) = startMapNavigation(NavConfig(
        linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
        angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent).coerceAtMost(Commands.MAX_ANGULAR_SPEED),
        tonte = true, heightMm = heightMm, automatic = true, maxDurationMs = maxDurationS * 1000L, maxRadiusM = radiusM))

    fun acknowledgeNavInterruption() = update { it.copy(navTest = it.navTest.copy(interruption = null)) }

    fun startPointNavigation(target: Point) {
        val busy = state.value.navTest.running || navJob != null
        startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
            angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), target)
        // Drive started: destination saved, resume possible after a stop or an app restart.
        val robotId = state.value.selected?.address
        if (!busy && state.value.navTest.running && robotId != null) {
            pointNavRunning = true
            val resumed = state.value.pointResume?.takeIf { it.robotId == robotId && it.target == target }
            setPointResume(robotId, PointResume(robotId, target, Clock.now()))
            event(if (resumed != null) "point_resume" else "point_resume_saved",
                if (resumed != null) tr("Resuming the interrupted move to a point", "Reprise du déplacement au point interrompu") else tr("Move-to-point destination saved", "Destination du déplacement au point sauvegardée"),
                mapOf("x" to target.x, "y" to target.y))
        }
    }

    /** Explicit abandonment of the interrupted move to a point. */
    fun discardPointResume() {
        if (state.value.navTest.running) return
        val id = state.value.pointResume?.robotId ?: return
        setPointResume(id, null)
        event("point_resume_discarded", tr("Interrupted move to a point abandoned by the user", "Déplacement au point interrompu abandonné par l'utilisateur"))
    }

    /** Move to a point in progress (not a navigation test): its end updates the saved state. */
    private var pointNavRunning = false

    internal fun setPointResume(robotId: String, value: PointResume?) {
        update { it.copy(pointResume = value) }
        pointResumeWrites.trySend(robotId to value)
    }

    /** End of a move to a point: saved state cleared if it succeeded, otherwise kept with its reason. */
    private fun pointNavigationEnded(outcome: String, reason: String) {
        if (!pointNavRunning) return
        pointNavRunning = false
        val current = state.value.pointResume ?: return
        setPointResume(current.robotId, PointResume.after(current, outcome, reason))
    }

    internal fun startMapNavigation(requested: NavConfig, target: Point? = null, preset: MapNavPlan? = null) {
        // Blades stopped (move to a point, bypass, border positioning, map sync, navigation test): no height chosen, the
        // deck stays at the reported height instead of `NavConfig`'s 60 mm ([HoldHeight], DumbyLuba#2). Cutting test: its height.
        val config = if (requested.tonte) requested
            else requested.copy(heightMm = HoldHeight.mm(state.value.telemetry, bladeHeightRequest, state.value.cutHeights))
        if (state.value.robotRoute.running) { host.status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { host.status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { host.status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.navTest.running || navJob != null) return
        if (state.value.borderFrontal.running && !borderPositioning) { host.status(tr("Finish the frontal border test first", "Terminer l'essai frontal de bordure")); return }
        try {
            val t = state.value.telemetry
            // Robot on its base: charging states accepted, it leaves in a straight line first ([BaseDeparture]).
            val charging = t.chargeState in setOf(1,2)
            val failure = NavSafety.startFailure(
                state.value.selected?.name,
                if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
                // Recorded outline (sync with the Mammotion app): the robot is in its recording state, not READY.
                t.battery, if ((charging && t.robotState in setOf(11,15,39)) || (config.drawState != null && t.robotState == config.drawState)) 11 else t.robotState,
                host.guardSensors(t.sensorStatus), t.selfCheck,
                state.value.campaign.running, state.value.bladeTestActive, state.value.mapLoading,
                config.heightMm, config.batteryFloor, ignoreOffMapLatch = config.pointDestination && !config.tonte && host.manualDrive.offMapLatchIgnored(t),
                heights = state.value.cutHeights)
            if (failure != null) throw IllegalArgumentException(failure)
            check(!state.value.taskStopPending && gatt != null && state.value.ready && !closing && log.healthy) { tr("BLE / log not ready", "BLE / journal non prêt") }
            check(!state.value.teach.running && heightTarget == null && !bladeMayBeOn && !bladeOffPending) { tr("Finish the obstacle / height / blade-stop test first", "Terminer le test obstacle / hauteur / arrêt lames") }
            check(queue.size <= 4) { tr("BLE queue busy: wait for the operations to finish", "File BLE occupée : attendre la fin des opérations") }
            check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime()-positionPrevious, t.x, t.y) == null) { tr("Fresh position required to navigate on the map", "Position fraîche obligatoire pour naviguer sur la carte") }
            check(t.heading?.isFinite() == true) { tr("Heading missing or invalid", "Cap absent ou invalide") }
            check(!config.tonte || target == null) { tr("Move to a point: blades always stopped", "Déplacement au point : lames toujours arrêtées") }
            navConfig = config
            localIncidentPending = false
            navContactObstacle = null
            navContact = null
            val here = Point(requireNotNull(t.x),requireNotNull(t.y))
            val navMap = state.value.robotMap
            val store = BaseStore(host.getApplication(), navMap)
            navBase = runCatching { store.resolve(navMap) }.getOrNull()
            navAccesses = runCatching { store.accesses() }.getOrDefault(emptyList())
            // Robot on its base or in its safety zone: straight exit to the link point, the path starts there.
            val departure = BaseDeparture.required(navBase, here, charging)
            if (departure != null) require(preset == null) { tr("Robot on its base: leave the station first (Charging station → Leave the station)",
                "Robot sur sa base : sortir d'abord de la station (Station de charge → Sortir de la station)") }
            navMapPlan = preset ?: if (target == null) MapNavPlanner.plan(navMap, departure?.approach ?: here, config.maxRadiusM, state.value.obstacles).copy(departure = departure)
                else RoutePlanning.point(navMap, here, target, state.value.obstacles,
                    offMap = config.pointDestination, base = navBase, charging = charging, accesses = navAccesses)
            navPlanObstacles = state.value.obstacles.toSet()
            navStarted = SystemClock.elapsedRealtime()
            navTestId = UUID.randomUUID().toString()
            navStart = t.x?.let { x -> t.y?.let { y -> NavSample(x, y, t.heading, 0) } }
            bladeHeightRequest = config.heightMm
            if (config.tonte) { bladeOffWrittenAt = 0L; bladeStopVerified = false }
            val cutMeters = if (config.tonte) requireNotNull(navMapPlan).cuttingMeters else 0.0
            update {
                it.copy(navTest = NavTestUi(id = navTestId, running = true, tonte = config.tonte, preview = it.navTest.preview, plannedCutMeters = cutMeters, courseObstacleId = navCourse?.id,
                    segmentCount = requireNotNull(navMapPlan).points.size-1, outcome = "RUNNING",
                    planned = requireNotNull(navMapPlan).points, plannedBands = requireNotNull(navMapPlan).bands,
                    plannedOffMap = requireNotNull(navMapPlan).offMap,
                    plannedExit = requireNotNull(navMapPlan).departure?.let { d -> listOf(d.from, d.approach) }.orEmpty(),
                    plannedBorders = requireNotNull(navMapPlan).borders,
                    status = if (navCourse != null) tr("Obstacle bypass · %.1f m round trip", "Contournement de l'obstacle · %.1f m aller-retour").format(requireNotNull(navMapPlan).meters)
                        else if (target != null) tr("Move to a point · %.1f m", "Déplacement au point · %.1f m").format(requireNotNull(navMapPlan).meters)
                        else if (config.tonte) tr("Cutting test while moving: ${requireNotNull(navMapPlan).bands.size} stripes · ${requireNotNull(navMapPlan).borders.size} borders · %.1f m including %.1f m with blades running", "Essai de coupe en déplacement : ${requireNotNull(navMapPlan).bands.size} bandes · ${requireNotNull(navMapPlan).borders.size} bordures · %.1f m dont %.1f m lames en marche").format(requireNotNull(navMapPlan).meters, cutMeters)
                        else tr("Map route: ${requireNotNull(navMapPlan).bands.size} stripes · ${requireNotNull(navMapPlan).borders.size} borders · %.1f m", "Parcours sur carte : ${requireNotNull(navMapPlan).bands.size} bandes · ${requireNotNull(navMapPlan).borders.size} bordures · %.1f m").format(requireNotNull(navMapPlan).meters)))
            }
            motionWrittenAt = 0L; motionStarvedLogged = false
            event("nav_test_start", if (target == null) tr("Supervised map navigation", "Navigation sur carte supervisée") else tr("Supervised move to a point", "Déplacement au point supervisé"), mapOf("testId" to navTestId,
                "robot" to state.value.selected?.name, "firmware" to t.firmware,
                "planned" to requireNotNull(navMapPlan).points.map { mapOf("x" to it.x,"y" to it.y) },
                "linearSpeedRaw" to config.linearSpeed, "angularSpeedRaw" to config.angularSpeed,
                "tonte" to config.tonte, "heightMm" to config.heightMm, "automatic" to config.automatic,
                "maxDurationMs" to config.maxDurationMs, "maxRadiusM" to config.maxRadiusM,
                "startX" to navStart?.x, "startY" to navStart?.y, "destinationX" to target?.x, "destinationY" to target?.y))
            // Move beyond the mapped zones: recalled by a notification, the section has no border check.
            if (target != null && requireNotNull(navMapPlan).offMap.isNotEmpty()) {
                val offMeters = requireNotNull(navMapPlan).offMapMeters
                event("point_off_map", tr("Move to a point beyond the mapped zones", "Déplacement au point au-delà des zones cartographiées"),
                    mapOf("testId" to navTestId, "offMapM" to offMeters, "sections" to requireNotNull(navMapPlan).offMap.size))
                host.alerts.alert(AlertKind.OFF_MAP, Severity.WARNING, tr("Move to a point: %.1f m beyond the mapped zones (dotted on the map), without border check",
                    "Déplacement au point : %.1f m au-delà des zones cartographiées (en pointillé sur la carte), sans contrôle des bords")
                    .format(I18n.locale, offMeters))
            }
            persistNav("RUNNING", tr("Map route planned", "Parcours sur carte planifié"))
            host.send(host.reportsCommand(false, periodMs = 200), "NAV_RPT_FAST")
            navJob = host.viewModelScope.launch { runMapNavTest(config, requireNotNull(navMapPlan)) }
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(status = tr("Navigation refused: ${error.message}", "Navigation refusée : ${error.message}"))) }
            event("nav_test_refused", error.message ?: tr("Drive refused", "Marche refusée"))
        }
    }

    /** User stop of the drive: STOP (+ BLADES_OFF when mowing), the link stays open. */
    fun stopNavTest(reason: String = tr("Release / manual stop", "Relâchement / arrêt manuel"), userInitiated: Boolean = false) {
        if (!state.value.navTest.running && navTestId.isBlank()) return
        if (!userInitiated && state.value.navTest.running) {
            host.alerts.alertMotionFailure(reason)
            host.alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        }
        abandonNav(reason, userInitiated)
        navStopAndCleanup(reason)
    }

    /** Base and manual accesses of the current move (planning and straight exit from the station). */
    private var navBase: MapBase? = null
    private var navAccesses: List<List<Point>> = emptyList()

    /** Fresh state during a straight exit from the base: charging states accepted, as for routes. */
    private fun navExitState(): Telemetry {
        if (!log.healthy || !state.value.ready || closing) throw RobotBle.NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
        check(queue.size <= 4) { tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée") }
        val now = SystemClock.elapsedRealtime()
        val live = state.value.telemetry
        NavSafety.tickFailure(if (devicePrevious == 0L) null else now - devicePrevious, live.battery, host.guardSensors(live.sensorStatus), false, -1)?.let { throw RobotBle.NavAbort(it) }
        if (!SelfCheck.nominal(live.selfCheck)) throw RobotBle.NavAbort(tr("Robot protection active", "Protection robot active"))
        if (live.robotState !in listOf(11,15,20,39)) throw RobotBle.NavAbort(tr("Robot state incompatible with the station (state ${live.robotState})", "État robot incompatible avec la station (état ${live.robotState})"))
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else now - positionPrevious, live.x, live.y)?.let { throw RobotBle.NavAbort(it) }
        return live
    }

    /**
     * Move started on the base (or inside its safety zone): slow straight exit to the link point ([DockExitRunner.straightDockExit],
     * same exit as routes), blades OFF, before any pivot; the path then starts from the link point.
     */
    private suspend fun navDockExit(config: NavConfig, exit: BaseDeparture.Exit) {
        val base = requireNotNull(navBase) { tr("Base unknown", "Base inconnue") }
        val began = SystemClock.elapsedRealtime()
        update { it.copy(navTest = it.navTest.copy(status = tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF"))) }
        host.purgeQueuedCommands(); host.send(commands.stop(), "NAV_DOCK_EXIT_PREPARE")
        val routes = PointPlanner.stationRoutes(state.value.robotMap, state.value.obstacles, base, navAccesses)
        val result = try {
            host.dockExit.straightDockExit(exit.base, exit.approach, routes, config.heightMm, RouteRecharge.RESERVE_PERCENT,
                obstacles = { state.value.obstacles }, live = { navExitState() }, onPosition = {},
                onGateway = { update { it.copy(navTest = it.navTest.copy(status = tr("Leaving the base in a straight line, slowly · blades OFF · run by the gateway",
                    "Sortie de base en ligne droite, lentement · lames OFF · exécutée par la passerelle"))) } },
                stopLabel = "NAV_DOCK_EXIT", navProgress = {})
        } catch (abort: RobotBle.NavAbort) { throw abort }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { throw RobotBle.NavAbort(tr("Base exit interrupted: ${error.message}", "Sortie de base interrompue : ${error.message}")) }
        update { it.copy(navTest = it.navTest.copy(plannedExit = emptyList())) }
        event("nav_dock_exit", tr("Slow straight exit to the link point before the move", "Sortie rectiligne lente jusqu'au point de liaison avant le déplacement"),
            mapOf("testId" to navTestId, "durationMs" to SystemClock.elapsedRealtime() - began, "viaGateway" to result.viaGateway,
                "headingJumpDeg" to result.headingJump, "fromX" to exit.from.x, "fromY" to exit.from.y, "approachX" to exit.approach.x, "approachY" to exit.approach.y))
    }

    private suspend fun runMapNavTest(config: NavConfig, initialPlan: MapNavPlan) {
        var plan = initialPlan
        // Kept after a recovery (new path from the actual position, built without the flag).
        val alignAtStart = initialPlan.alignAtStart
        var index = 1
        var repairs = 0
        var recoveries = 0
        // Fused heading kept from one replanning to the next (offset between published heading and travelled direction).
        // Navigation test: pose predicted at the moment the command takes effect (BLE + firmware delay).
        val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.NAV_COMMAND_DELAY_MS, calibration = calibration.saved(),
            publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M).also { it.slide = difficulties.slideVelocity() }
        // Chained drive (sync with the Mammotion app): heading gap of the previous drive taken over when the robot has not
        // moved since and it was measured over a long enough straight line. Confirmed again over 15 cm of the short link
        // before the outline, it was off by 10° once in ten draws of the bench (1 cm of RTK noise), and the outline then
        // drifted into the element ([MapSyncSweepTest]).
        val remembered = navHeadingMemory.also { navHeadingMemory = null }
        if (config.keepHeading && remembered != null) {
            val t = state.value.telemetry
            val reason = remembered.refusal(SystemClock.elapsedRealtime(), t.x?.let { x -> t.y?.let { y -> Point(x, y) } }, t.heading)
            if (reason == null) reckoning.inheritHeading(remembered.offset, remembered.supportM)
            event("nav_heading_kept", if (reason == null) tr("Heading gap of the previous drive kept", "Écart de cap du trajet précédent conservé")
                else tr("Heading confirmed again: $reason", "Cap reconfirmé : $reason"), mapOf("testId" to navTestId, "offset" to remembered.offset, "supportM" to remembered.supportM, "kept" to (reason == null)))
        }
        val navSlideMeter = PivotSlideMeter()
        var fixAt = 0L
        var recovery: String? = null
        try {
            // Robot on its base: slow straight exit to the link point before anything else (no pivot near the station).
            plan.departure?.let { exit -> navDockExit(config, exit); plan = plan.copy(departure = null); navMapPlan = plan }
            // Manual mode (state 20) carries the height: in the READY state the setpoint alone is ignored.
            // Recorded outline: the manual mode would end the recording; the recording state is checked instead.
            if (config.tracing) confirmDrawState(config) else enterNavBumperMode(config)
            if (config.tonte) navSetHeight(config.heightMm)
            while (index < plan.points.size) {
                // Blades running: no automatic bypass, the test stops and cuts the blades.
                if (config.tonte && localIncidentPending) throw RobotBle.NavAbort(tr("Obstacle or contact during the cutting test: stopped, blades cut", "Obstacle ou contact pendant l'essai de coupe : arrêt, lames coupées"))
                recovery?.let { reason ->
                    // Recorded outline: a new path would be drawn into the element; stop instead.
                    if (config.tracing) throw RobotBle.NavAbort(tr("$reason: outline left, recording abandoned", "$reason : contour quitté, enregistrement abandonné"))
                    // Recovery: STOP, fresh still position, new path from the actual position.
                    recovery = null
                    if (++recoveries > MAX_NAV_RECOVERIES) throw RobotBle.NavAbort(tr("$reason; $MAX_NAV_RECOVERIES recoveries already attempted", "$reason ; $MAX_NAV_RECOVERIES rattrapages déjà tentés"))
                    host.purgeQueuedCommands(); host.send(commands.stop(), "NAV_RECOVERY_STOP")
                    reckoning.sent(SystemClock.elapsedRealtime(), 0, 0)
                    update { it.copy(navTest = it.navTest.copy(status = tr("Recovery $recoveries/$MAX_NAV_RECOVERIES: $reason", "Rattrapage $recoveries/$MAX_NAV_RECOVERIES : $reason"))) }
                    val stoppedAt = positionPrevious
                    delay(1300)
                    navTick(config, null)
                    val t = state.value.telemetry
                    if (positionPrevious <= stoppedAt || t.x == null || t.y == null) throw RobotBle.NavAbort(tr("$reason; no fresh position after STOP", "$reason ; position fraîche absente après STOP"))
                    val here = Point(t.x, t.y)
                    plan = try {
                        val course = navCourse
                        // Bypass: still head for the opposite side if it was not reached, then come back.
                        if (course != null) ObstacleCoursePlanner.plan(state.value.robotMap, here, requireNotNull(navCourseFar), requireNotNull(navCourseHome),
                            state.value.obstacles, includeFar = plan.labels.drop(index).contains(ObstacleCourse.FAR_LABEL))
                        else RoutePlanning.point(state.value.robotMap, here, plan.points.last(), state.value.obstacles,
                            offMap = config.pointDestination, base = navBase, charging = t.chargeState in setOf(1,2), accesses = navAccesses) }
                        catch (e: Exception) { throw RobotBle.NavAbort(tr("$reason; no safe path from the position reached (${e.message})", "$reason ; aucun chemin sûr depuis la position atteinte (${e.message})")) }
                    navPlanObstacles = state.value.obstacles.toSet()
                    index = 1
                    navMapPlan = plan
                    update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedOffMap = plan.offMap, segmentCount = plan.points.size-1,
                        status = tr("Recovery $recoveries/$MAX_NAV_RECOVERIES: new path from the actual position", "Rattrapage $recoveries/$MAX_NAV_RECOVERIES : nouveau chemin depuis la position réelle"))) }
                    event("nav_recovery", reason, mapOf("recovery" to recoveries, "x" to here.x, "y" to here.y,
                        "headingOffset" to reckoning.headingOffset, "headingConfirmed" to reckoning.headingConfirmed,
                        "points" to plan.points.map { mapOf("x" to it.x, "y" to it.y) }))
                }
                if (localIncidentPending) {
                    localIncidentPending = false
                    if (config.tracing) throw RobotBle.NavAbort(tr("Obstacle or contact on the outline: stopped, no bypass while recording", "Obstacle ou contact sur le contour : arrêt, pas de contournement pendant un enregistrement"))
                    if (++repairs > 3) throw RobotBle.NavAbort(tr("Three repairs: stop maintained, correct the map", "Trois réparations : arrêt maintenu, corriger la carte"))
                    // New telemetry required after STOP: two still positions published after it takes effect
                    // (1 Hz, delay ≈ 0.6 s); no resume on the old report.
                    val stopped = StopConfirmation(SystemClock.elapsedRealtime(), DeadReckoning.NAV_COMMAND_DELAY_MS)
                    var t = state.value.telemetry
                    while (true) {
                        navTick(config, if (plan.physicalGuard) null else plan.zone)
                        t = state.value.telemetry
                        val result = if (t.x == null || t.y == null) false else stopped.observe(SystemClock.elapsedRealtime(), positionPrevious, Point(t.x,t.y))
                        if (result == true) break
                        if (result == false) throw RobotBle.NavAbort(tr("Physical stop not confirmed by the positions: resume refused", "Arrêt physique non confirmé par les positions : reprise refusée"))
                        delay(100)
                    }
                    val here = Point(requireNotNull(t.x),requireNotNull(t.y))
                    val contact = navContact
                    navContact = null
                    plan = if (contact != null) {
                        // Contact while driving to a point: back-off along the trail, then a path recomputed around
                        // the saved obstacle (same navigation as mowing: borders, no-go zones, obstacles).
                        // Obstacle created at contact: moved in front of the stopped position (two still positions), as
                        // during mowing. Estimated from the last published position (1 Hz), it could overlap the chassis
                        // stopped 10 to 20 cm further on: no back-off possible, drive stopped (end-to-end bench).
                        navContactObstacle?.let { id -> state.value.obstacles.firstOrNull { it.id == id } }?.let { touched ->
                            val center = TeachObstacle.estimateCenter(TeachContact(here.x, here.y, contact.heading, Clock.now()), touched.radiusM)
                            host.saveObstacle(touched.copy(x = center.x, y = center.y))
                        }
                        navContactObstacle = null
                        val obstacles = state.value.obstacles
                        val trail = state.value.navTest.trail.map { Point(it.x, it.y) }.takeLast(400)
                        // Contact beyond the map: back-off along the corridor of the trace driven there.
                        val physical = if (config.pointDestination) RoutePlanning.backOffPhysical(state.value.robotMap, obstacles, here, trail)
                            else RoutePlanning.physical(state.value.robotMap, obstacles)
                        val destination = plan.points.last()
                        val options = withContext(Dispatchers.Default) {
                            ObstacleBypass.backOffCandidates(physical, here, contact.heading, trail).mapNotNull { q ->
                                runCatching { q to RoutePlanning.point(state.value.robotMap, q, destination, obstacles,
                                    offMap = config.pointDestination, base = navBase, accesses = navAccesses) }.getOrNull()
                            }
                        }
                        val (back, next) = options.firstOrNull { (q, p) -> p.routes?.supportsPivot(q) == true } ?: options.firstOrNull()
                            ?: throw RobotBle.NavAbort(tr("Bumper contact: no back-off along the trail and no safe path to the destination, stop maintained", "Contact bumper : aucun recul sur la trace ni chemin sûr vers la destination, arrêt maintenu"))
                        update { it.copy(navTest = it.navTest.copy(status = tr("Contact: %.0f cm back-off in a straight line", "Contact : recul de %.0f cm en ligne droite").format(back.distance(here)*100))) }
                        try {
                            host.safetyTests.reverseStraight(here, back, contact.heading, physical, config.heightMm, "NAV", afterContact = true)
                            update { it.copy(navTest = it.navTest.copy(status = tr("Obstacle photo · robot still, blades OFF", "Photo de l'obstacle · robot immobile, lames OFF"))) }
                            obstaclePhotos.holdForObstaclePhoto("NAV", config.heightMm)
                            host.safetyTests.awaitCollisionLatchCleared("NAV", config.heightMm)
                        }
                        catch (error: IllegalStateException) { throw RobotBle.NavAbort(error.message ?: tr("Back-off interrupted", "Recul interrompu")) }
                        event("nav_obstacle_bypass", tr("Path recomputed around the obstacle hit", "Chemin recalculé autour de l'obstacle touché"), mapOf("backOffM" to back.distance(here),
                            "points" to next.points.map { mapOf("x" to it.x, "y" to it.y) }))
                        next
                    } else LocalReplanner.repair(plan, here, index, state.value.obstacles)
                    navPlanObstacles = state.value.obstacles.toSet()
                    index = 1
                    navMapPlan = plan
                    update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedOffMap = plan.offMap, segmentCount = plan.points.size-1,
                        status = if (contact != null) tr("Obstacle bypassed: new path from the back-off", "Obstacle contourné : nouveau chemin depuis le recul") else tr("Safe local bypass computed", "Contournement local sûr calculé"))) }
                    if (contact == null) event("nav_local_replan", tr("Local link computed", "Raccord local calculé"), mapOf("windowM" to LocalReplanner.WINDOW_M,
                        "points" to plan.points.map { mapOf("x" to it.x, "y" to it.y) }))
                }
                // New path from a position inside the base safety zone: straight exit to the link point first.
                plan.departure?.let { exit -> navDockExit(config, exit); plan = plan.copy(departure = null); navMapPlan = plan; index = 1 }
                // Gateway mounted on the robot: same tracking as mowing (LegRunner, over Bluetooth on the robot), without
                // depending on Wi-Fi for each setpoint. Contact, added obstacle and deviation are still handled here as before.
                // Recorded outline: tracked from here (the gateway tracking enters the manual mode).
                if (!config.tonte && !config.tracing && plan.physicalGuard && plan.routes != null && host.autonomousLegsAvailable()) {
                    val outcome = try {
                        followNavRemote(plan, index) { target ->
                            // Points passed: result recorded as with tracking from here.
                            while (index < target) {
                                recordNavSegment(index, plan.labels[index], "PASS", SystemClock.elapsedRealtime()-navStarted, tr("Point passed (tracked by the gateway)", "Point franchi (suivi par la passerelle)"))
                                index++
                            }
                        }
                    }
                        catch (encounter: ObstacleEncounter) {
                            val contact = encounter.contact
                            if (contact != null) {
                                // Contact seen by the gateway (Wi-Fi down: never reported here): obstacle saved at the stop.
                                if (!localIncidentPending) host.recovery.captureLocalIncident(contact.code)
                                if (navContact == null) navContact = contact
                            }
                            localIncidentPending = true
                            if (!state.value.navTest.running) throw RobotBle.NavAbort(tr("${encounter.message}: stop maintained", "${encounter.message} : arrêt maintenu"))
                            continue
                        }
                        catch (failure: IllegalStateException) {
                            val reason = failure.message ?: tr("Tracking interrupted by the gateway", "Suivi interrompu par la passerelle")
                            // Deviation or envelope: same recovery as tracking from here (STOP, new path from the actual position).
                            if (RemoteNavOutcome.recoverable(reason)) { recovery = reason; continue }
                            throw RobotBle.NavAbort(reason)
                        }
                    if (outcome == null) event("gateway_leg_fallback", tr("Drive too large for the gateway: tracked from here", "Déplacement trop volumineux pour la passerelle : suivi d'ici"))
                    else {
                        val now = SystemClock.elapsedRealtime()
                        while (index < plan.points.size) {
                            recordNavSegment(index, plan.labels[index], "PASS", now-navStarted, tr("Point reached (tracked by the gateway)", "Point atteint (suivi par la passerelle)"))
                            index++
                        }
                        continue
                    }
                }
                // Continuous tracking of all remaining points: pivot only at marked corners.
                val path = plan.points.zipWithNext().map { (a,b) -> SimEdge(a,b,false) }
                // Cutting test: one range per blade state (stripe / border running, link stopped).
                val rangeEnd = if (config.tonte) plan.bladeRangeEnd(index-1) else path.size
                val endPoint = rangeEnd
                val blades = config.tonte && plan.cutting(index-1)
                val follower = PathFollower(path,index-1,rangeEnd,RangeArrival.TARGET_M).also { it.slide = difficulties.slideVelocity(); if (alignAtStart) it.alignFirst() }
                val speedCaps = plan.routes?.takeIf { SmartFeatures.adaptiveSpeed }?.speedZones?.caps(path).orEmpty()
                // End of range confirmed by a position measured after the stop (the robot used to stop short).
                val arrival = RangeArrival(DeadReckoning.NAV_COMMAND_DELAY_MS)
                var started = SystemClock.elapsedRealtime()
                val gate = if (config.tonte) startNavBladeRange(index, blades, config, reckoning) else null
                val gateAt = SystemClock.elapsedRealtime()
                var bladeObserved = !blades
                var offPending = config.tonte && !blades && bladeMayBeOn
                var cutterQueryAt = 0L
                var bestDistance = Double.POSITIVE_INFINITY
                var bestIndex = follower.index
                var progressAt = started
                var nextErrorQuery = started
                val motionProgress=MotionProgressGuard()
                fun showPoint() = update { it.copy(navTest = it.navTest.copy(segmentIndex = index,
                    status = "${plan.labels[index]} · point $index/${plan.points.size-1}")) }
                showPoint()
                var finished = false
                while (true) {
                    if (localIncidentPending) break
                    navTick(config,if (plan.physicalGuard) null else plan.zone)
                    val now = SystemClock.elapsedRealtime()
                    if (now-navStarted >= config.maxDurationMs) throw RobotBle.NavAbort(tr("Maximum duration reached: route incomplete (point $index/${plan.points.size-1})", "Durée maximale atteinte : parcours incomplet (point $index/${plan.points.size-1})"))
                    val t = state.value.telemetry
                    NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else now-positionPrevious,t.x,t.y)?.let { throw RobotBle.NavAbort(it) }
                    if (gate != null) {
                        val bits = ((t.sensorStatus ?: -1) shr 9) and 7
                        if (gate.confirmed(devicePrevious, bits)) {
                            if (blades && !bladeObserved) { bladeObserved = true
                                recordNavSegment(index, tr("Blades running", "Lames en marche"), "PASS", now-gateAt, tr("Rotation confirmed by the robot in ${now-gateAt} ms", "Rotation confirmée par le robot en ${now-gateAt} ms")) }
                            if (offPending) { offPending = false; bladeMayBeOn = false
                                recordNavSegment(index, tr("Blades stopped", "Lames arrêtées"), "PASS", now-gateAt, tr("Stop confirmed by the robot in ${now-gateAt} ms", "Arrêt confirmé par le robot en ${now-gateAt} ms")) }
                        }
                        if (offPending) {
                            if (now-gateAt > 3000) throw RobotBle.NavAbort(tr("Blade stop not confirmed within 3 s before the link", "Arrêt des lames non confirmé en 3 s avant la liaison"))
                            delay(200); continue
                        }
                        if (!blades && bits != 0) throw RobotBle.NavAbort(tr("Blades active during a link", "Lames actives pendant une liaison"))
                        if (blades && bladeObserved && bits != 1) throw RobotBle.NavAbort(tr("Blade rotation interrupted", "Rotation des lames interrompue"))
                        if (blades && !bladeObserved && now-gateAt > 5000) throw RobotBle.NavAbort(tr("Blade rotation not confirmed within 5 s", "Rotation des lames non confirmée en 5 s"))
                        if (blades && now >= cutterQueryAt) { host.send(commands.cutterQuery(), "NAV_CUTTER_QUERY"); cutterQueryAt = now+2000 }
                    }
                    val published = Point(requireNotNull(t.x),requireNotNull(t.y))
                    val reportedHeading = t.heading?.takeIf { it.isFinite() } ?: throw RobotBle.NavAbort(tr("Heading missing or invalid", "Cap absent ou invalide"))
                    // Positions at 1 Hz: heading realigned on the travelled direction, command based on the predicted pose.
                    val fixed = positionPrevious != fixAt
                    if (fixed) { fixAt = positionPrevious; reckoning.fix(published,reportedHeading,fixAt); calibration.save(reckoning) }
                    val heading = reckoning.heading(reportedHeading)
                    // Rotation centre: the published point is 8 cm behind it ([DeadReckoning.PUBLISHED_POINT_BEHIND_M]).
                    val position = reckoning.measured ?: reckoning.center(published,heading)
                    if (fixed) navSlideMeter.update(position,Clock.now(),reckoning.rotationSlide)?.let { difficulties.learnSlide(it,"local") }
                    val headingTrusted = reckoning.headingConfirmed
                    confirmedHeading = heading.takeIf { headingTrusted }
                    MapNavGuard.positionFailure(plan,position,heading,headingTrusted)?.let { reason ->
                        event("nav_margin", reason, mapOf("segment" to index, "x" to position.x, "y" to position.y, "heading" to heading,
                            "reportedHeading" to reportedHeading, "headingConfirmed" to headingTrusted))
                        throw RobotBle.NavAbort(reason)
                    }
                    val predicted = reckoning.predict(now) ?: RobotPose(position,heading)
                    // Heading not confirmed by motion: slow advance until the first direction measurement.
                    // After a pivot: slow advance until the position that measures the heading reached.
                    // Recorded outline: speed capped by the drive (closer tracking, evenly spaced recorded points).
                    val chosenLinear = AdaptiveSpeed.setLinear(state.value.trajectorySpeedPercent,speedCaps,path,follower.index,predicted.position,t,Clock.now())
                        .let { if (config.tracing) minOf(it, config.linearSpeed) else it }
                    val linearLimit = if (!headingTrusted) minOf(chosenLinear, DeadReckoning.UNCONFIRMED_LINEAR) else reckoning.postPivotLinear(chosenLinear)
                    val command = follower.command(predicted.position,predicted.headingDegrees,linearLimit,
                        minOf(config.angularSpeed,Commands.MAX_ANGULAR_SPEED),reckoning.degreesPerUnit,reckoning.metersPerUnit,
                        RangeArrival.APPROACH_MIN_LINEAR,follower.deviation(position),reckoning.rotationCalibrated)
                    // Very short range: wait while stopped for blade confirmation rather than leaving it.
                    if (command.done && blades && !bladeObserved) {
                        motionProgress.reset(); progressAt = now
                        host.send(commands.movement(0,0), "NAV_WAIT_BLADES"); reckoning.sent(now,0,0); delay(200); continue
                    }
                    var arrived = false
                    if (command.done) {
                        when (arrival.check(now, fixAt, position, follower, path[rangeEnd-1].b)) {
                            null -> {
                                // Stopped: a position taken after the stop is awaited before changing the blade state.
                                if (now >= nextErrorQuery) { navKeepAlive(blades, config); nextErrorQuery = now+500 }
                                // Commanded stop: standing still is not a blockage.
                                motionProgress.reset(); progressAt = now
                                host.send(commands.movement(0,0), "NAV_SETTLE point=$endPoint"); reckoning.sent(now,0,0); delay(200); continue
                            }
                            false -> {
                                event("nav_settle", tr("Stopped before the point: approach resumed", "Arrêt avant le point : approche reprise"), mapOf("testId" to navTestId, "point" to endPoint,
                                    "approach" to arrival.approaches, "distanceM" to arrival.distanceM, "remainingM" to arrival.remainingM,
                                    "x" to position.x, "y" to position.y))
                                continue
                            }
                            true -> {
                                arrived = true
                                event("nav_arrival", tr("End-of-range point confirmed by the measured position", "Point de fin de plage confirmé par la position mesurée"), mapOf("testId" to navTestId,
                                    "point" to endPoint, "approaches" to arrival.approaches, "distanceM" to arrival.distanceM,
                                    "remainingM" to arrival.remainingM, "x" to position.x, "y" to position.y))
                            }
                        }
                    }
                    // Points passed without stopping: result recorded for each one.
                    while (index < follower.index+1 || (arrived && index <= endPoint)) {
                        recordNavSegment(index,plan.labels[index],"PASS",now-started,tr("Point passed within 15 cm or overtaken on the route", "Point franchi à moins de 15 cm ou dépassé sur le trajet"))
                        started = now; index++
                        if (index > endPoint) break
                        showPoint()
                    }
                    if (arrived) { finished = true; break }
                    val edge = path[follower.index]
                    val deviation = follower.deviation(position)
                    if (deviation > MapNavFollower.MAX_DEVIATION_M) {
                        event("nav_deviation", tr("Stopped: deviation from the segment", "Arrêt : écart au segment"), mapOf("segment" to index,
                            "deviationM" to deviation, "limitM" to MapNavFollower.MAX_DEVIATION_M,
                            "x" to position.x, "y" to position.y, "fromX" to edge.a.x, "fromY" to edge.a.y,
                            "targetX" to edge.b.x, "targetY" to edge.b.y, "heading" to heading, "reportedHeading" to reportedHeading,
                            "headingOffset" to reckoning.headingOffset, "headingConfirmed" to headingTrusted,
                            "rawCompassHeading" to t.rawHeading, "positionAgeMs" to now-positionPrevious))
                        val reason = tr("Route deviation greater than 30 cm (%.1f cm measured)", "Écart au trajet supérieur à 30 cm (%.1f cm mesurés)").format(deviation*100)
                        if (plan.physicalGuard) { recovery = reason; break }
                        throw RobotBle.NavAbort(reason)
                    }
                    val blocked = MapNavGuard.unplannedObstacle(position,command.target,state.value.obstacles,navPlanObstacles)
                    if (blocked != null) {
                        host.recovery.captureLocalIncident(0, blocked)
                        break
                    }
                    val distance = position.distance(edge.b)
                    if (follower.index > bestIndex || distance < bestDistance-0.03) { bestIndex = follower.index; bestDistance = distance; progressAt = now }
                    val (linear,angular) = command.linear to command.angular
                    if (linear == 0) progressAt=now
                    if (now-progressAt > 15_000) throw RobotBle.NavAbort(tr("No progress towards the point for 15 s", "Aucune progression vers le point depuis 15 s"))
                    if (motionProgress.blocked(position,positionPrevious,now,linear)) throw RobotBle.NavAbort(tr("Presumed blockage for 3 s: bumper contact not confirmed", "Blocage présumé depuis 3 s : contact bumper non confirmé"))
                    // Envelope and corridor ahead of the robot, projected at most up to the anticipated target.
                    val motionFailure = MapNavGuard.motionFailure(plan,position,heading,command.target,linear,angular,headingTrusted)
                    if (motionFailure != null) {
                        event("nav_motion_guard", motionFailure, mapOf("segment" to index, "x" to position.x, "y" to position.y,
                            "heading" to heading, "reportedHeading" to reportedHeading, "linear" to linear, "angular" to angular,
                            "targetX" to command.target.x, "targetY" to command.target.y))
                        if (plan.physicalGuard) { recovery = motionFailure; break }
                        throw RobotBle.NavAbort(motionFailure)
                    }
                    // Bumper contact: error history polled twice per second, manual mode kept alive.
                    if (now >= nextErrorQuery) { navKeepAlive(blades, config); nextErrorQuery = now+500 }
                    // Link too slow (gateway): setpoints issued but no longer written, the robot stops by itself.
                    val motionGap = if (motionWrittenAt > 0) SystemClock.elapsedRealtime() - motionWrittenAt else 0L
                    if (linear != 0 && motionGap > 1500 && !motionStarvedLogged) {
                        motionStarvedLogged = true
                        event("nav_motion_starved", tr("No motion setpoint written for $motionGap ms: link too slow", "Aucune consigne de mouvement écrite depuis $motionGap ms : liaison trop lente"),
                            mapOf("testId" to navTestId, "segment" to index, "gapMs" to motionGap, "queueDepth" to queue.size,
                                "viaGateway" to state.value.viaGateway, "x" to position.x, "y" to position.y))
                    } else if (motionGap <= 1500) motionStarvedLogged = false
                    host.send(commands.movement(linear,angular), "NAV_MAP_MOVE linear=$linear angular=$angular point=$index")
                    reckoning.sent(now,linear,angular)
                    delay(200)
                }
                if (localIncidentPending || recovery != null) continue
                host.purgeQueuedCommands(); host.send(commands.stop(),"NAV_MAP_END_STOP")
                if (finished) index = endPoint+1
            }
            val end = state.value.telemetry
            navHeadingMemory = if (reckoning.headingConfirmed && end.x != null && end.y != null && end.heading?.isFinite() == true)
                NavHeadingMemory(reckoning.headingOffset, reckoning.headingSupportM, SystemClock.elapsedRealtime(), Point(end.x, end.y), end.heading) else null
            finishNav("PASS",(if (config.pointDestination) tr("Point reached, blades OFF · %.2f m observed", "Point atteint, lames OFF · %.2f m observés")
                else if (config.tonte) tr("Cutting test finished · %.2f m observed · blade stop requested", "Essai de coupe terminé · %.2f m observés · arrêt des lames demandé")
                else tr("Map route finished · %.2f m observed", "Parcours sur carte terminé · %.2f m observés")).format(state.value.navTest.observedMeters))
        } catch (abort: RobotBle.NavAbort) { finishNav("ABORTED",abort.motif) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { finishNav("FAILED",error.message ?: tr("Map navigation error", "Erreur navigation carte")) }
    }

    /**
     * Move to a point tracked by the gateway: route, physical surface and obstacles sent once, tracked by
     * [fr.luba.poc.autonomy.LegRunner] from point [fromPoint]. Returns the number of edges (route finished), or null if
     * the tracking is too large for one frame (tracked from here). Throws [ObstacleEncounter] or a safety-stop error.
     */
    private suspend fun followNavRemote(plan: MapNavPlan, fromPoint: Int, onPoint: (Int) -> Unit): Int? {
        val link = gatt as? GatewayLink ?: error(tr("No gateway link", "Liaison passerelle absente"))
        val path = plan.points.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }
        val routes = requireNotNull(plan.routes) { tr("Plan without a physical surface", "Plan sans surface physique") }
        val planned = navPlanObstacles.map { it.id }.toSet()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val routeId = legs.ensureRoute(path)
                val guardId = legs.ensureGuard(routes.physicalSurface)
                val spec = LegSpec(legs.nextLegId(), routeId, fromPoint - 1, cutting = false, shown = true,
                    watchBattery = false, departure = false, lastArrivalM = null, heightMm = state.value.cutHeights.clamp(navConfig.heightMm),
                    speedPercent = state.value.trajectorySpeedPercent.coerceIn(10, 100), returnPercent = RouteRecharge.RESERVE_PERCENT.coerceIn(10, 80),
                    baseApproach = null, guardId = guardId, physicalGuardId = guardId, calibration = calibration.saved(),
                    obstacles = state.value.obstacles.map { o -> LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in planned) },
                    sampling = false, slideField = difficulties.slideFor(path), speedCaps = PathSpeedCaps { routes.speedZones }.of(path).take(LegSpec.MAX_SPEED_CAPS),
                    offMapSections = if (navConfig.pointDestination && !navConfig.tonte) LegSpec.boundedSections(plan.offMap) else emptyList()).fitted()
                if (spec.encode().size > LegFrames.MAX_PAYLOAD) return null
                update { it.copy(navTest = it.navTest.copy(status = tr("Drive tracked by the gateway · point $fromPoint/${plan.points.size-1}", "Déplacement suivi par la passerelle · point $fromPoint/${plan.points.size-1}"))) }
                return host.routes.runRemoteLeg(link, spec, path, obstacles = { state.value.obstacles }, planned = { planned }, originOf = { -1 },
                    navProgress = { progress ->
                        val target = progress.index.coerceIn(1, plan.points.size - 1)
                        onPoint(target)
                        update { it.copy(navTest = it.navTest.copy(segmentIndex = target,
                            status = tr("${plan.labels[target]} · point $target/${plan.points.size-1} · gateway", "${plan.labels[target]} · point $target/${plan.points.size-1} · passerelle") +
                                if (progress.status.contains("Wi-Fi")) tr(" · Wi-Fi down, the robot continues on its own", " · Wi-Fi coupé, le robot poursuit seul") else "")) }
                    })
            } catch (refused: RemoteLegs.RefusedLeg) {
                if (attempt >= 2) throw refused
                event("gateway_leg_resend", tr("Drive refused (${refused.message}): route sent again", "Déplacement refusé (${refused.message}) : trajet renvoyé"))
            }
        }
    }

    /** Bumper contact: error history polled, manual mode (blades running or not) kept alive. */
    private fun navKeepAlive(blades: Boolean, config: NavConfig) {
        host.send(commands.errorQuery(), "NAV_ERROR_QUERY", periodic = true)
        // Recorded outline: no manual mode keep-alive, it would end the recording.
        if (config.tracing) return
        if (blades) host.send(commands.manualBlades(true,config.heightMm), "NAV_BLADES_KEEPALIVE", periodic = true)
        else host.send(commands.manualMow(1,0,config.heightMm), "NAV_BUMPER_KEEPALIVE_OFF", periodic = true)
    }

    /** Cutting test: height reported by the robot before any blade rotation.
     *  Called in manual mode with blades OFF ([enterNavBumperMode] has already sent the height). */
    private suspend fun navSetHeight(mm: Int) {
        val began = SystemClock.elapsedRealtime()
        val at = Clock.now()
        val deadline = began + HeightSequence.timeoutMs(state.value.telemetry.bladeHeightMm, mm)
        update { it.copy(navTest = it.navTest.copy(status = tr("Setting the cutting height to $mm mm", "Réglage de la hauteur de coupe à $mm mm"))) }
        host.send(commands.bladeHeight(mm), "NAV_HEIGHT_$mm")
        while (!HeightSequence.reached(state.value.telemetry, mm, at)) {
            if (!state.value.ready || closing || !log.healthy) throw RobotBle.NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw RobotBle.NavAbort(tr("Height $mm mm not reported by the robot", "Hauteur $mm mm non rapportée par le robot"))
            delay(200)
        }
        recordNavSegment(0, tr("Height $mm mm", "Hauteur $mm mm"), "PASS", SystemClock.elapsedRealtime()-began, tr("Height reported before cutting", "Hauteur rapportée avant la coupe"))
    }

    /** Start of a cutting-test range: STOP then the blade-state command, confirmed afterwards in the loop. */
    private fun startNavBladeRange(point: Int, blades: Boolean, config: NavConfig, reckoning: DeadReckoning): RouteBladeGate {
        host.purgeQueuedCommands(); host.send(commands.stop(), "NAV_BLADE_RANGE_STOP")
        reckoning.sent(SystemClock.elapsedRealtime(), 0, 0)
        val gate = RouteBladeGate.unique("NAV_BLADES_${point}_${if (blades) "ON" else "OFF"}", blades)
        routeBladeGate = gate
        if (blades) bladeMayBeOn = true
        host.send(if (blades) commands.manualBlades(true, config.heightMm) else commands.manualMow(1, 0, config.heightMm), gate.label)
        update { it.copy(navTest = it.navTest.copy(bladesOn = blades)) }
        event("nav_blade_range", if (blades) tr("Range with blades running", "Plage lames en marche") else tr("Range with blades stopped", "Plage lames arrêtées"), mapOf("testId" to navTestId, "point" to point, "blades" to blades))
        return gate
    }

    /** Manual mode with blades OFF (state 20): the only mode in which the LUBA 2 publishes bumper contact. */
    private suspend fun enterNavBumperMode(config: NavConfig) {
        host.purgeQueuedCommands(); host.send(commands.stop(), "NAV_BUMPER_MODE_STOP")
        val gate = RouteBladeGate.unique("NAV_BUMPER_MANUAL_OFF", false)
        routeBladeGate = gate
        host.send(commands.manualMow(1,0,config.heightMm), gate.label)
        navBumperMode = true
        update { it.copy(navTest = it.navTest.copy(status = tr("Enabling bumper detection (manual mode, blades OFF)", "Activation de la détection bumper (mode manuel, lames OFF)"))) }
        val deadline = SystemClock.elapsedRealtime()+5000
        while (state.value.telemetry.robotState != 20 || !gate.confirmed(devicePrevious, ((state.value.telemetry.sensorStatus ?: -1) shr 9) and 7)) {
            if (!state.value.ready || closing || !log.healthy) throw RobotBle.NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw RobotBle.NavAbort(tr("Bumper detection inactive: manual mode with blades OFF not confirmed within 5 s", "Détection bumper inactive : mode manuel lames OFF non confirmé en 5 s"))
            delay(200)
        }
        event("nav_bumper_mode", tr("Manual mode with blades OFF confirmed: bumper contact monitored", "Mode manuel lames OFF confirmé : contact bumper surveillé"), mapOf("testId" to navTestId))
    }

    /**
     * Recorded outline: the robot must be in the recording state of [NavConfig.drawState] (it was put there by
     * [MapSyncLink.startRecording]); bumper contacts stay reported through the error history.
     */
    private suspend fun confirmDrawState(config: NavConfig) {
        host.purgeQueuedCommands(); host.send(commands.stop(), "NAV_TRACE_STOP")
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (state.value.telemetry.robotState != config.drawState) {
            if (!state.value.ready || closing || !log.healthy) throw RobotBle.NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw RobotBle.NavAbort(tr("Robot no longer recording (state ${state.value.telemetry.robotState}, ${config.drawState} expected)",
                "Robot plus en enregistrement (état ${state.value.telemetry.robotState}, ${config.drawState} attendu)"))
            delay(200)
        }
        event("nav_trace_mode", tr("Recording state confirmed: outline driven without manual mode", "État d'enregistrement confirmé : contour parcouru sans mode manuel"),
            mapOf("testId" to navTestId, "robotState" to config.drawState))
    }

    /** Continuous check of the drive; any anomaly throws NavAbort and stops all movement. */
    private fun navTick(config: NavConfig, zone: List<Point>?) {
        if (!log.healthy || !state.value.ready || closing) throw RobotBle.NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
        val t = state.value.telemetry
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y)?.let { throw RobotBle.NavAbort(it) }
        if (t.x != null && t.y != null && state.value.robotMap.shapes.any { it.excludes && Geometry.inside(Point(t.x, t.y), it.points) }) throw RobotBle.NavAbort(tr("Entered a no-go zone", "Entrée dans une zone interdite"))
        // Move to a point beyond the mapped zones: 25 published there is not a contact ([offMapLatchIgnored]).
        if (t.robotState !in (config.drawState?.let { listOf(it) } ?: listOf(11, 20)) || !(SelfCheck.nominal(t.selfCheck) ||
                (config.pointDestination && !config.tonte && host.manualDrive.offMapLatchIgnored(t)))) throw RobotBle.NavAbort(tr("Robot state / protection changed", "État / protection robot changé"))
        val failure = NavSafety.tickFailure(
            if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
            t.battery, host.guardSensors(t.sensorStatus), config.tonte, config.batteryFloor,
            t.x, t.y, navStart, config.maxRadiusM, zone, NavSafety.ZONE_MARGIN_M)
        if (failure != null) throw RobotBle.NavAbort(failure)
    }

    private fun recordNavSegment(index: Int, title: String, outcome: String, durationMs: Long, detail: String) {
        val result = TestResult("${navTestId}_$index", title, outcome, durationMs, detail)
        update { it.copy(navTest = it.navTest.copy(results = it.navTest.results + result)) }
        event("nav_segment_result", detail, host.campaign.resultFields(result))
    }

    private fun finishNav(outcome: String, detail: String) {
        if (navTestId.isBlank()) return
        if (outcome in setOf("ABORTED", "FAILED")) {
            host.alerts.alertMotionFailure(detail)
            host.alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, detail)
            if(detail.contains(tr("resume", "reprise"),ignoreCase=true)) host.alerts.alert(AlertKind.RESUME_FAILED, Severity.CRITICAL, detail)
        }
        val elapsed = SystemClock.elapsedRealtime() - navStarted
        navStopAndCleanup(detail)
        signalSurveys.flushSurveys()
        val course = navCourse
        val courseGap = course?.let { o -> ObstacleCourse.observedSurfaceGap(state.value.navTest.trail.map { Point(it.x, it.y) }, o) }
        if (course != null) recordNavSegment(state.value.navTest.segmentCount, tr("Distance to the obstacle", "Distance à l'obstacle"),
            if (ObstacleCourse.passed(courseGap)) "PASS" else "FAILED", elapsed,
            courseGap?.let { tr("Minimum observed gap %.2f m between the robot reference point and the surface (robot flank: %.2f m)", "Écart minimal observé %.2f m entre le repère robot et la surface (flanc du robot : %.2f m)").format(it, RobotEnvelope.LUBA2.halfWidthM) } ?: tr("No position observed", "Aucune position observée"))
        val courseOutcome = if (course != null && outcome == "PASS" && !ObstacleCourse.passed(courseGap)) "FAILED" else outcome
        clearNavCourse()
        val result = TestResult(navTestId,
            if (course != null) tr("Obstacle bypass", "Contournement d'obstacle") else if (navConfig.tonte) tr("Cutting test while moving", "Essai de coupe en déplacement") else tr("Light map route", "Parcours léger sur carte"),
            courseOutcome, elapsed, if (course != null && outcome == "PASS") tr("Round trip finished · ${courseGap?.let { tr("minimum gap %.2f m", "écart minimal %.2f m").format(it) } ?: tr("gap unknown", "écart inconnu")}", "Aller-retour terminé · ${courseGap?.let { "écart minimal %.2f m".format(it) } ?: "écart inconnu"}") else detail)
        update { it.copy(navTest = it.navTest.copy(running = false, outcome = result.outcome, status = result.detail,
            interruption = if (result.outcome == "PASS") null else result.detail, results = it.navTest.results + result)) }
        persistNav(result.outcome, result.detail)
        pointNavigationEnded(result.outcome, result.detail)
        event("nav_test_result", detail, host.campaign.resultFields(result) + mapOf(
            "observedMeters" to state.value.navTest.observedMeters, "tonte" to navConfig.tonte))
        navJob = null; navTestId = ""
    }

    /** Abandonment recorded without any BLE write: STOP/BLADES_OFF is already handled by the caller. */
    private fun clearNavCourse() { navCourse = null; navCourseFar = null; navCourseHome = null }

    internal fun abandonNav(reason: String, userInitiated: Boolean = false) {
        // Drive tracked by the gateway: stop it before the STOP written from here (otherwise held until the tracking ends).
        if (navJob != null) host.routes.releaseRemoteLeg()
        navJob?.cancel(); navJob = null
        clearNavCourse()
        if (navTestId.isBlank()) return
        val elapsed = SystemClock.elapsedRealtime() - navStarted
        val result = TestResult(navTestId, tr("Light map route", "Parcours léger sur carte"), "ABORTED", elapsed, reason)
        update { it.copy(navTest = it.navTest.copy(running = false, outcome = "ABORTED", status = reason,
            interruption = if (userInitiated) null else reason, results = it.navTest.results + result)) }
        persistNav("ABORTED", reason)
        pointNavigationEnded("ABORTED", reason)
        event("nav_test_result", reason, host.campaign.resultFields(result))
        navTestId = ""
    }

    /** End-of-drive STOP; if the blades were in use, the supervised OFF path is taken again. */
    private fun navStopAndCleanup(reason: String) {
        confirmedHeading = null
        update { it.copy(navTest = it.navTest.copy(bladesOn = false)) }
        // Blades possibly running: supervised OFF path (OFF, STOP, wait for a fresh OFF state).
        if (bladeMayBeOn) { navBumperMode = false; host.stopBladeTest(reason); return }
        host.purgeQueuedCommands()
        host.send(commands.stop(), "NAV_STOP linear=0 angular=0")
        if (navBumperMode) { host.send(commands.manualBlades(false, navConfig.heightMm), "NAV_EXIT_MANUAL_OFF"); navBumperMode = false }
    }

    private fun persistNav(outcome: String, detail: String) {
        if (navTestId.isBlank()) return
        log.report(navTestId, mapOf("id" to navTestId, "kind" to "map_navigation", "outcome" to outcome,
            "step" to tr("Light map route", "Parcours léger sur carte"), "detail" to detail,
            "elapsedMs" to SystemClock.elapsedRealtime() - navStarted,
            "tonte" to navConfig.tonte, "heightMm" to navConfig.heightMm, "automatic" to navConfig.automatic,
            "initialLinearSpeedRaw" to navConfig.linearSpeed, "linearSpeedRaw" to PathFollower.linearFor(state.value.trajectorySpeedPercent), "angularSpeedRaw" to navConfig.angularSpeed,
            "maxDurationMs" to navConfig.maxDurationMs, "maxRadiusM" to navConfig.maxRadiusM,
            "batteryFloor" to navConfig.batteryFloor,
            "observedMeters" to state.value.navTest.observedMeters, "stopDelayMs" to state.value.navTest.stopDelayMs,
            "startX" to navStart?.x, "startY" to navStart?.y,
            "planned" to state.value.navTest.planned.map { mapOf("x" to it.x, "y" to it.y) },
            "trail" to state.value.navTest.trail.map { sample ->
                mapOf("xMeters" to sample.x, "yMeters" to sample.y, "headingDegrees" to sample.heading, "elapsedMs" to sample.elapsedMs) },
            "results" to state.value.navTest.results.map(host.campaign::resultFields),
            "robot" to state.value.selected?.name, "firmware" to state.value.telemetry.firmware,
            "batteryPercent" to state.value.telemetry.battery))
    }
}
