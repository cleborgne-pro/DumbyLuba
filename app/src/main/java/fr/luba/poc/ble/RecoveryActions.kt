@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recovery after a stop: local incident capture, collision latch unlock, back-off before resuming, Mammotion task stop and native return.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class RecoveryActions(private val host: RobotBle) {
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
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var connectionId
        get() = host.connectionId
        set(value) { host.connectionId = value }
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
    private var localIncidentPending
        get() = host.localIncidentPending
        set(value) { host.localIncidentPending = value }
    private val log get() = host.log
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var preparationJob: Job?
        get() = host.preparationJob
        set(value) { host.preparationJob = value }
    private val queue get() = host.queue
    private val safetyTests get() = host.safetyTests
    private val state get() = host.state

    private var navTestId
        get() = host.navigation.navTestId
        set(value) { host.navigation.navTestId = value }
    private var navContactObstacle: String?
        get() = host.navigation.navContactObstacle
        set(value) { host.navigation.navContactObstacle = value }

    internal fun captureLocalIncident(code: Int, known: LocalObstacle? = null) {
        host.purgeQueuedCommands()
        host.send(commands.stop(), "OBSTACLE_IMMEDIATE_STOP")
        localIncidentPending = true
        val t = state.value.telemetry
        val now = Clock.now()
        event("navigation_incident", tr("Obstacle STOP", "STOP obstacle"), mapOf("code" to code, "x" to t.x, "y" to t.y,
            "heading" to t.heading, "positionAt" to t.lastPositionAt, "deviceAt" to t.lastDeviceAt,
            "segment" to state.value.navTest.segmentIndex, "testId" to navTestId, "nativeMissionId" to state.value.mowing.id))
        if (t.x == null || t.y == null || t.heading == null || positionPrevious == 0L ||
            SystemClock.elapsedRealtime()-positionPrevious !in 0..2000) {
            host.stopNavTest(tr("Incident without a fresh position: stop maintained", "Incident sans position fraîche : arrêt maintenu"))
            return
        }
        try {
            val center = TeachObstacle.estimateCenter(TeachContact(t.x,t.y,requireNotNull(host.contactHeading(t.heading)),now), 0.2)
            if (code != 0) difficulties.recordDifficulty(DifficultyKind.CONTACT, tr("Bumper contact ($code)", "Contact pare-chocs ($code)"), Point(t.x,t.y), code,
                host.contactHeading(t.heading), center, state.value.obstaclePhoto.request?.contactAt ?: now)
            val existing = state.value.obstacles.minByOrNull { Point(it.x,it.y).distance(center) }
                ?.takeIf { Point(it.x,it.y).distance(center) <= it.exclusionRadiusM + 0.5 }
            val obstacle = known ?: existing ?: LocalObstacle(UUID.randomUUID().toString(), ObstacleKind.OTHER,
                center.x, center.y, 0.2, 0.3, createdAtMs = now, label = tr("Obstacle detected during navigation", "Obstacle détecté en navigation"))
                    .also { if (code != 0) navContactObstacle = it.id }
            host.saveObstacle(obstacle.withIncident(ObstacleIncident(now, if (code == 0) "obstacle_corridor" else "collision_$code",
                "Cap=${t.heading}; segment=${state.value.navTest.segmentIndex}; mission=$navTestId", t.x,t.y)))
            if (obstacle.incidents.size + 1 >= 3) event("incident_heatmap", tr("Permanent map correction proposed", "Correction permanente de carte proposée"),
                mapOf("id" to obstacle.id, "x" to obstacle.x, "y" to obstacle.y, "count" to obstacle.incidents.size+1))
        } catch (error: Exception) {
            host.stopNavTest(tr("Incident not recorded: stop maintained (${error.message})", "Incident non enregistré : arrêt maintenu (${error.message})"))
        }
    }

    internal var unlockWrittenAt = 0L

    /**
     * Unlock attempt after the robot's STOP button (self-check 11), see [StopUnlock]: zero speed, task cancellation,
     * error re-read; no motion command.
     * Successful only if a report received after sending shows self-check 0 or 10.
     */
    fun unlockAfterStop() {
        val ui = state.value
        val busy = ui.robotRoute.running || ui.navTest.running || ui.manualActive || ui.teach.running ||
            ui.bladeTestActive || ui.campaign.running || ui.mowing.active || ui.taskStopPending || ui.unlockPending || ui.backOffPending
        val age = if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious
        val refusal = StopUnlock.refusal(ui.ready && gatt != null && !closing && log.healthy, busy, age,
            ui.telemetry.selfCheck, host.guardSensors(ui.telemetry.sensorStatus))
        if (refusal != null) {
            update { it.copy(unlockStatus = tr("Unlock refused: $refusal", "Déverrouillage refusé : $refusal")) }
            event("stop_unlock_refused", refusal, mapOf("selfCheck" to ui.telemetry.selfCheck)); return
        }
        if (CollisionLatch.latched(ui.telemetry.selfCheck)) { unlockCollisionLatch(); return }
        update { it.copy(unlockPending = true, unlockStatus = tr("Unlock requested from the robot…", "Déverrouillage demandé au robot…")) }
        event("stop_unlock_start", tr("Unlock requested (self-check ${ui.telemetry.selfCheck})", "Déverrouillage demandé (self-check ${ui.telemetry.selfCheck})"), mapOf("selfCheck" to ui.telemetry.selfCheck,
            "robotState" to ui.telemetry.robotState, "sensorStatus" to ui.telemetry.sensorStatus))
        unlockWrittenAt = 0L
        host.purgeQueuedCommands()
        host.send(commands.stop(), "UNLOCK_ZERO")
        host.send(commands.taskControl(4), "UNLOCK_TASK_CANCEL")
        host.send(commands.errorQuery(), "UNLOCK_ERROR_QUERY")
        if (!StopUnlock.locked(ui.telemetry.selfCheck)) host.send(commands.selfCheckInfo(), "UNLOCK_SELF_CHECK_INFO")
        val session = connectionId
        host.viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + StopUnlock.TIMEOUT_MS
            while (connectionId == session && state.value.ready && !closing && SystemClock.elapsedRealtime() < deadline) {
                val t = state.value.telemetry
                if (StopUnlock.confirmed(unlockWrittenAt, devicePrevious, SystemClock.elapsedRealtime() - devicePrevious, t.selfCheck)) {
                    update { it.copy(unlockPending = false, unlockStatus = tr("Robot unlocked (self-check ${t.selfCheck})", "Robot déverrouillé (self-check ${t.selfCheck})")) }
                    event("stop_unlock_done", tr("Nominal self-check received after unlocking", "Self-check nominal reçu après déverrouillage"), mapOf("selfCheck" to t.selfCheck))
                    return@launch
                }
                delay(200)
            }
            val t = state.value.telemetry
            update { it.copy(unlockPending = false, unlockStatus =
                tr("The robot stays locked (self-check ${t.selfCheck ?: "missing"}): it does not accept remote unlocking. ${StopUnlock.physical(t.selfCheck)}.", "Le robot reste verrouillé (self-check ${t.selfCheck ?: "absent"}) : il n'accepte pas le déverrouillage à distance. ${StopUnlock.physical(t.selfCheck)}.")) }
            event("stop_unlock_failed", tr("Self-check still not nominal after ${StopUnlock.TIMEOUT_MS} ms", "Self-check toujours non nominal après ${StopUnlock.TIMEOUT_MS} ms"),
                mapOf("selfCheck" to t.selfCheck, "writtenConfirmed" to (unlockWrittenAt > 0)))
        }
    }

    /**
     * Collision lock (self-check 25, [CollisionLatch]): the robot stays against the object it hit, and neither zero speed
     * nor task cancellation releases it (10-15, 11:00 → 11:10). Unlocked by a straight back-off of at most
     * [CollisionLatch.UNLOCK_BACK_OFF_M], heading unchanged, blades OFF, along the travelled trace (otherwise along a free
     * line of the map: borders, no-go zones and obstacles at their physical radius), with the guards of the back-off after
     * contact, then a wait for the lock to be released. The robot may refuse to move: it must then be freed by hand.
     */
    private fun unlockCollisionLatch() {
        val t = state.value.telemetry
        update { it.copy(unlockPending = true, unlockStatus = tr("Collision lock: back-off of %.0f cm along the trace, blades OFF…", "Verrou de collision : recul de %.0f cm sur la trace, lames OFF…").format(CollisionLatch.UNLOCK_BACK_OFF_M * 100)) }
        event("stop_unlock_start", tr("Releasing the collision lock with a back-off (self-check ${t.selfCheck})", "Déverrouillage du verrou de collision par un recul (self-check ${t.selfCheck})"), mapOf("selfCheck" to t.selfCheck,
            "robotState" to t.robotState, "sensorStatus" to t.sensorStatus, "mode" to "collision_back_off"))
        val heightMm = t.bladeHeightMm ?: bladeHeightRequest
        host.viewModelScope.launch {
            try {
                check(NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
                val here = Point(requireNotNull(t.x), requireNotNull(t.y))
                val heading = requireNotNull(t.heading?.takeIf { it.isFinite() }) { tr("Robot heading unknown", "Cap du robot inconnu") }
                val obstacles = state.value.obstacles
                // Trace of a route or of a move to a point (the latter was ignored: no trace after a move beyond the map).
                val recorded = listOf(state.value.robotRoute.trail.takeLast(400), state.value.navTest.trail.takeLast(400).map { Point(it.x, it.y) })
                    .filter { it.isNotEmpty() && it.last().distance(here) <= 0.5 }.minByOrNull { it.last().distance(here) }
                // Beyond the mapped zones, the trace driven there is the only known ground ([RoutePlanning.backOffPhysical]).
                val physical = withContext(Dispatchers.Default) {
                    RoutePlanning.backOffPhysical(state.value.robotMap, obstacles, here, recorded ?: emptyList()) }
                // No trace (app restarted): straight line behind the robot, checked against the map like the trace.
                val rad = Math.toRadians(heading)
                val trail = recorded ?: listOf(Point(here.x - 0.6 * cos(rad), here.y - 0.6 * sin(rad)), here)
                val back = withContext(Dispatchers.Default) {
                    ObstacleBypass.backOffCandidates(physical, here, heading, trail, CollisionLatch.UNLOCK_BACK_OFF_M).lastOrNull() }
                    ?: error(if (recorded == null && state.value.robotMap.shapes.none { it.kind == ShapeKind.ZONE && it.points.size >= 3 && Geometry.inside(here, it.points) })
                        tr("robot beyond the mapped zones without a recorded trace, no known ground behind it", "robot hors des zones cartographiées sans trace enregistrée, aucun sol connu derrière lui")
                        else tr("no safe back-off behind the robot (trace, borders, no-go zones or obstacles)", "aucun recul sûr derrière le robot (trace, bords, no-go ou obstacles)"))
                event("stop_unlock_back_off", tr("Unlock back-off computed", "Recul de déverrouillage calculé"), mapOf("x" to here.x, "y" to here.y, "heading" to heading,
                    "toX" to back.x, "toY" to back.y, "meters" to back.distance(here), "trail" to (recorded != null)))
                host.purgeQueuedCommands(); host.send(commands.stop(), "UNLOCK_ZERO")
                safetyTests.reverseStraight(here, back, heading, physical, heightMm, "UNLOCK", afterContact = true)
                update { it.copy(unlockStatus = tr("Back-off done: waiting for the robot protection to be released…", "Recul effectué : attente de la levée de la protection du robot…")) }
                safetyTests.awaitCollisionLatchCleared("UNLOCK", heightMm)
                val selfCheck = state.value.telemetry.selfCheck
                update { it.copy(unlockPending = false, unlockStatus = tr("Robot unlocked (self-check $selfCheck) after a back-off of %.0f cm", "Robot déverrouillé (self-check $selfCheck) après un recul de %.0f cm").format(back.distance(here) * 100)) }
                event("stop_unlock_done", tr("Collision lock released after the back-off", "Verrou de collision levé après le recul"), mapOf("selfCheck" to selfCheck, "meters" to back.distance(here)))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                host.purgeQueuedCommands(); host.send(commands.stop(), "UNLOCK_BACK_OFF_STOP")
                val reason = failure.message ?: failure.javaClass.simpleName
                update { it.copy(unlockPending = false, unlockStatus = tr("The robot stays locked (self-check ${state.value.telemetry.selfCheck ?: "missing"}): ", "Le robot reste verrouillé (self-check ${state.value.telemetry.selfCheck ?: "absent"}) : ") +
                    "$reason${if (reason.contains(StopUnlock.PROTECTION_PHYSICAL)) "" else ". ${StopUnlock.PROTECTION_PHYSICAL}"}.") }
                event("stop_unlock_failed", tr("Unlock back-off impossible: $reason", "Recul de déverrouillage impossible : $reason"), mapOf("selfCheck" to state.value.telemetry.selfCheck))
            }
        }
    }

    internal var backOffJob: Job? = null

    /**
     * "Unblock" from the interrupted-mowing panel: straight back-off of at most [ResumeBackOff.MAX_M], heading unchanged,
     * blades OFF, to the nearest point where the robot can pivot and travel with the mowing margins ([engine]: the resume's
     * re-prepared route, its margins and its plan), guided by the actual trace, otherwise by the route mowed before the
     * stop, otherwise by a free line behind the robot ([ResumeBackOff]). Same guards as the back-off after contact
     * ([reverseStraight]). Under the collision lock (25), the back-off is allowed as after a contact, then the lock
     * release is awaited. The robot then stays stopped: resuming remains a user action.
     */
    fun backOffBeforeResume(engine: MowingSimulation) {
        val ui = state.value
        val t = ui.telemetry
        val busy = ui.robotRoute.running || ui.navTest.running || ui.manualActive || ui.teach.running || ui.bladeTestActive ||
            ui.campaign.running || ui.mowing.active || ui.taskStopPending || ui.unlockPending || ui.backOffPending || ui.silenceTest.running ||
            preparationJob?.isActive == true || heightTarget != null || bladeMayBeOn || bladeOffPending
        val latched = CollisionLatch.latched(t.selfCheck)
        val refusal = when {
            !(ui.ready && gatt != null && !closing && log.healthy && queue.size <= 4) -> tr("BLE not ready", "BLE non prêt")
            busy -> tr("finish the current activity", "terminer l'activité en cours")
            devicePrevious == 0L || SystemClock.elapsedRealtime() - devicePrevious > 2000 -> tr("robot state stale", "état du robot périmé")
            t.chargeState in setOf(1, 2) -> tr("robot on the base, nothing to free", "robot sur la base, rien à dégager")
            StopUnlock.locked(t.selfCheck) -> tr("robot locked after STOP: “Unlock” first", "robot verrouillé après STOP : « Déverrouiller » d'abord")
            !CollisionLatch.reverseAllowed(t.robotState, t.selfCheck, afterContact = true) ->
                tr("protection or robot state incompatible (state ${t.robotState ?: "?"}, self-check ${t.selfCheck ?: "?"})", "protection ou état du robot incompatible (état ${t.robotState ?: "?"}, self-check ${t.selfCheck ?: "?"})")
            else -> NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y)
                ?: if (t.heading?.isFinite() != true) tr("robot heading unknown", "cap du robot inconnu") else null
        }
        if (refusal != null) {
            update { it.copy(backOffStatus = tr("Unblock refused: $refusal", "Déblocage refusé : $refusal")) }
            event("resume_back_off_refused", refusal, mapOf("selfCheck" to t.selfCheck, "robotState" to t.robotState)); return
        }
        val checkpoint = ui.missionCheckpoint
        val heightMm = t.bladeHeightMm ?: bladeHeightRequest
        update { it.copy(backOffPending = true, backOffStatus = tr("Backing off to a safe position, blades OFF…", "Recul vers une position sûre, lames OFF…")) }
        backOffJob = host.viewModelScope.launch {
            try {
                val here = Point(requireNotNull(t.x), requireNotNull(t.y))
                val heading = requireNotNull(t.heading)
                val obstacles = state.value.planningObstacles
                val (physical, planned) = withContext(Dispatchers.Default) {
                    RoutePlanning.physical(state.value.robotMap, obstacles,
                        recordedBoundary = !engine.map.source.contains("DÉMONSTRATION", true)) to
                        (checkpoint?.let { ResumeBackOff.plannedBefore(engine.edges, it.edge, it.point) } ?: emptyList())
                }
                val (guide, trail) = ResumeBackOff.guide(here, heading, state.value.robotRoute.trail, planned)
                val back = withContext(Dispatchers.Default) {
                    ResumeBackOff.target(engine.routes, physical, here, heading, trail) }
                    ?: error(tr("no safe back-off behind the robot (trace, borders, no-go zones or obstacles): free it by hand", "aucun recul sûr derrière le robot (trace, bords, no-go ou obstacles) : le dégager à la main"))
                val safe = engine.routes.safe(back, back) && engine.routes.supportsPivot(back)
                event("resume_back_off_start", tr("Clearance before resume: back-off computed", "Dégagement avant reprise : recul calculé"), mapOf("x" to here.x, "y" to here.y, "heading" to heading,
                    "toX" to back.x, "toY" to back.y, "meters" to back.distance(here), "guide" to guide.name, "safe" to safe,
                    "selfCheck" to t.selfCheck, "latched" to latched))
                host.purgeQueuedCommands(); host.send(commands.stop(), "RESUME_BACKOFF_ZERO")
                safetyTests.reverseStraight(here, back, heading, physical, heightMm, "RESUME", afterContact = latched)
                if (latched) {
                    update { it.copy(backOffStatus = tr("Back-off done: waiting for the robot protection to be released…", "Recul effectué : attente de la levée de la protection du robot…")) }
                    safetyTests.awaitCollisionLatchCleared("RESUME", heightMm)
                }
                val meters = back.distance(here)
                update { it.copy(backOffPending = false, backOffStatus = tr("Robot backed off by %.0f cm%s: Resume, or Resume further on to avoid the obstacle.", "Robot reculé de %.0f cm%s : Reprendre, ou Reprendre plus loin pour éviter l'obstacle.")
                    .format(meters * 100, if (safe) "" else tr(" (tight position: the resume will plan a clearance)", " (position serrée : la reprise prévoira un dégagement)"))) }
                event("resume_back_off_done", tr("Clearance before resume finished", "Dégagement avant reprise terminé"), mapOf("meters" to meters, "safe" to safe, "selfCheck" to state.value.telemetry.selfCheck))
            } catch (cancel: CancellationException) {
                host.purgeQueuedCommands(); host.send(commands.stop(), "RESUME_BACKOFF_STOP")
                update { it.copy(backOffPending = false, backOffStatus = tr("Unblock stopped", "Déblocage arrêté")) }
                event("resume_back_off_stopped", tr("Clearance before resume stopped", "Dégagement avant reprise arrêté"))
                throw cancel
            } catch (failure: Exception) {
                host.purgeQueuedCommands(); host.send(commands.stop(), "RESUME_BACKOFF_STOP")
                val reason = failure.message ?: failure.javaClass.simpleName
                update { it.copy(backOffPending = false, backOffStatus = tr("Unblock impossible: $reason", "Déblocage impossible : $reason")) }
                event("resume_back_off_failed", tr("Clearance before resume impossible: $reason", "Dégagement avant reprise impossible : $reason"), mapOf("selfCheck" to state.value.telemetry.selfCheck))
            } finally { backOffJob = null }
        }
    }

    /** Stops the unblock in progress (the panel's STOP button). */
    fun stopBackOff() { backOffJob?.cancel() }

    /** Supervised navigation: release, manual STOP and leaving the screen stop everything. */
    internal var nativeCancelWrittenAt = 0L

    fun stopMammotionTask() {
        try {
            check(state.value.ready && gatt != null && !closing && log.healthy) { tr("BLE connection required", "Connexion BLE requise") }
            check(!state.value.robotRoute.running && !state.value.navTest.running && !state.value.manualActive &&
                !state.value.teach.running && !state.value.bladeTestActive && !state.value.campaign.running) { tr("Stop the local route or test first", "Arrêter le trajet ou l’essai local d’abord") }
            check(!state.value.taskStopPending) { tr("Cancellation already in progress", "Annulation déjà en cours") }
            check(devicePrevious>0 && SystemClock.elapsedRealtime()-devicePrevious<=2000) { tr("Robot state stale", "État robot périmé") }
            if (!NativeTaskStop.present(state.value.telemetry.robotState)) {
                update { it.copy(taskStopStatus=tr("No active or paused Mammotion task observed", "Aucune tâche Mammotion active ou en pause observée")) }; return
            }
            update { it.copy(taskStopPending=true,taskStopStatus=tr("Mammotion task cancellation requested; waiting for READY and blades OFF", "Annulation de la tâche Mammotion demandée ; attendre READY et lames OFF")) }
            nativeCancelWrittenAt=0L
            host.purgeQueuedCommands()
            if(state.value.telemetry.robotState==14) host.send(commands.cancelReturnToDock(),"NATIVE_RETURN_CANCEL")
            host.send(commands.taskControl(2),"NATIVE_TASK_PAUSE")
            host.send(commands.taskControl(4),"NATIVE_TASK_CANCEL")
            host.send(commands.manualBlades(false,state.value.telemetry.bladeHeightMm ?: 60),"NATIVE_TASK_BLADES_OFF")
            host.send(commands.stop(),"NATIVE_TASK_ZERO")
            val session=connectionId
            host.viewModelScope.launch {
                val deadline=SystemClock.elapsedRealtime()+15000
                while(connectionId==session && state.value.ready && !closing && SystemClock.elapsedRealtime()<deadline) {
                    val t=state.value.telemetry
                    if(NativeTaskStop.confirmed(nativeCancelWrittenAt,devicePrevious,SystemClock.elapsedRealtime()-devicePrevious,t.robotState,t.sensorStatus)) {
                        update { it.copy(taskStopPending=false,taskStopStatus=tr("Task stopped: READY and blades OFF received", "Tâche arrêtée : READY et lames OFF reçus")) }
                        event("native_task_stopped",tr("READY / OFF observed after cancellation", "READY / OFF observés après annulation"))
                        return@launch
                    }
                    delay(200)
                }
                update { it.copy(taskStopPending=false,taskStopStatus=tr("Cancellation not confirmed: check the state in Mammotion; no local resume", "Annulation non confirmée : vérifier l’état dans Mammotion ; aucune reprise locale")) }
            }
        } catch(e: Exception) { update { it.copy(taskStopStatus=tr("Task stop refused: ${e.message}", "Arrêt tâche refusé : ${e.message}")) } }
    }

    /**
     * Official Mammotion return, on the user's explicit choice, triggered only once the robot has stopped in
     * front of the base (native [baseAction]). Blades off and zero speed first; tracking waits for the RETURN
     * state, then the charging contact. "Stop the Mammotion task" cancels it.
     */
    internal fun nativeReturnToBase() {
        try {
            check(state.value.ready && gatt != null && !closing && log.healthy) { tr("BLE connection required", "Connexion BLE requise") }
            check(!state.value.robotRoute.running && !state.value.navTest.running && !state.value.manualActive &&
                !state.value.teach.running && !state.value.bladeTestActive && !state.value.campaign.running) { tr("Stop the local route or test first", "Arrêter le trajet ou l’essai local d’abord") }
            check(!state.value.nativeReturnActive && !state.value.taskStopPending) { tr("Native return or cancellation already in progress", "Retour natif ou annulation déjà en cours") }
            check(devicePrevious>0 && SystemClock.elapsedRealtime()-devicePrevious<=2000) { tr("Robot state stale", "État robot périmé") }
            val t=state.value.telemetry
            if (t.chargeState in setOf(1,2)) { update { it.copy(nativeReturnStatus=tr("Already on the base: charging observed", "Déjà sur la base : charge observée")) }; return }
            check(t.robotState != 14) { tr("Mammotion return already in progress", "Retour Mammotion déjà en cours") }
            host.purgeQueuedCommands()
            host.send(commands.manualBlades(false,t.bladeHeightMm ?: 60),"NATIVE_RETURN_BLADES_OFF")
            host.send(commands.stop(),"NATIVE_RETURN_ZERO")
            host.send(commands.returnToDock(),"NATIVE_RETURN_TO_DOCK",nativeReturn=true)
            val requestedAt=SystemClock.elapsedRealtime()
            update { it.copy(nativeReturnActive=true,nativeReturnStatus=tr("Native Mammotion return requested; waiting for the return state", "Retour natif Mammotion demandé ; attente de l’état retour")) }
            event("native_return_request",tr("Return to base by the Mammotion firmware (user choice)", "Retour à la base par le firmware Mammotion (choix utilisateur)"),
                mapOf("robotState" to t.robotState,"battery" to t.battery,"x" to t.x,"y" to t.y))
            val session=connectionId
            host.viewModelScope.launch {
                var returning=false
                try {
                    while(connectionId==session && state.value.ready && !closing) {
                        val live=state.value.telemetry
                        val now=SystemClock.elapsedRealtime()
                        if (devicePrevious>requestedAt && live.chargeState in setOf(1,2)) {
                            update { it.copy(nativeReturnStatus=tr("Native return finished: charging contact confirmed", "Retour natif terminé : contact de charge confirmé")) }
                            event("native_return_docked",tr("Charging contact after native return", "Contact de charge après retour natif")); return@launch
                        }
                        if (live.robotState==14 && !returning) {
                            returning=true
                            update { it.copy(nativeReturnStatus=tr("Native Mammotion return in progress · can be stopped here", "Retour natif Mammotion en cours · arrêt possible ici")) }
                        }
                        if (returning && live.robotState!=14 && devicePrevious>requestedAt) {
                            update { it.copy(nativeReturnStatus=tr("Native return interrupted (state ${live.robotState}) without charging contact", "Retour natif interrompu (état ${live.robotState}) sans contact de charge")) }
                            event("native_return_interrupted",tr("Native return interrupted", "Retour natif interrompu"),mapOf("robotState" to live.robotState)); return@launch
                        }
                        if (!returning && now-requestedAt>15000) {
                            update { it.copy(nativeReturnStatus=tr("Native return not started by the robot (state ${live.robotState})", "Retour natif non démarré par le robot (état ${live.robotState})")) }
                            event("native_return_refused",tr("No return state observed", "Aucun état retour observé"),mapOf("robotState" to live.robotState)); return@launch
                        }
                        if (now-requestedAt>30*60*1000L) {
                            update { it.copy(nativeReturnStatus=tr("Native return taking too long: check the robot", "Retour natif trop long : vérifier le robot")) }; return@launch
                        }
                        delay(500)
                    }
                    update { it.copy(nativeReturnStatus=tr("Native return tracking lost (BLE): check the robot", "Suivi du retour natif perdu (BLE) : vérifier le robot")) }
                } finally { update { it.copy(nativeReturnActive=false) } }
            }
        } catch(e: Exception) { update { it.copy(nativeReturnStatus=tr("Native return refused: ${e.message}", "Retour natif refusé : ${e.message}")) } }
    }
}
