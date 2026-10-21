package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.diagnostics.HeightSequence
import fr.luba.poc.diagnostics.MotionProgressGuard
import fr.luba.poc.diagnostics.NavSafety
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotPose
import fr.luba.poc.map.SignalCoverage
import fr.luba.poc.protocol.CollisionLatch
import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.SelfCheck
import fr.luba.poc.simulation.DeadReckoning
import fr.luba.poc.simulation.DriftRecovery
import fr.luba.poc.simulation.DockExit
import fr.luba.poc.simulation.PathFollower
import fr.luba.poc.simulation.PivotSlide
import fr.luba.poc.simulation.RangeArrival
import fr.luba.poc.simulation.RouteBladeGate
import fr.luba.poc.simulation.ReturnDrift
import fr.luba.poc.simulation.RouteRecharge
import fr.luba.poc.simulation.SimEdge

/**
 * Tracking of a route prepared by the pilot, run by the host (gateway placed on the robot).
 *
 * This is the pilot's `followAll` / `followPath` loop, same guards, same thresholds, same commands: successive blade
 * ranges, manual mode (bumper), blade gate confirmed by telemetry, height, 1 Hz positions with predicted pose, 30 cm
 * deviation (drift up to 1 m tolerated on the return to base, [ReturnDrift]), lack of progress, chassis envelope, added
 * obstacles. It decides no bypass, recharge or docking: anything other than "continue" stops the robot (STOP, blades OFF
 * done by the host) and goes back to the pilot as an outcome ([LegOutcome]).
 *
 * Language: the stop reasons, event texts and progress statuses below are sent to the pilot in the gateway phone's
 * language. Reasons the pilot compares use the shared English wording (e.g. "Robot envelope outside the safe corridor",
 * "Bumper contact"); [DriftRecovery.OBSTACLE_RESERVE] and ProtectionPause texts come from their own packages.
 */
class LegRunner(
    private val env: LegEnv,
    private val spec: LegSpec,
    private val path: List<SimEdge>,
    /** Envelope surface of the main route; null: no envelope check. */
    private val guard: EnvelopeGuard?,
    /** Physical surface for escapes (`escape` edges); null: the main surface. */
    private val physicalGuard: EnvelopeGuard?,
) {
    private val commands = Commands()
    private val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS, calibration = spec.calibration,
        publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M)
    private val recharge = RouteRecharge(maxOf(spec.returnPercent, 10), maxOf(spec.returnPercent + 5, 15))
    private val trail = ArrayDeque<Point>()
    /** Pivot slide on a slope, tracked from one range of the route tracking to the next. */
    private val slide = PivotSlide()
    /** Slides known to the pilot ([LegSpec.slideField]): prediction and corner vertices. */
    private val slideVelocity: ((Point) -> Point?)? = spec.slideField?.takeUnless { it.isEmpty }?.let { field -> field::velocity }
    /** Slides measured during pivots, handed to the pilot (`pivot_slide` event). */
    private val slideMeter = fr.luba.poc.simulation.PivotSlideMeter()

    init { reckoning.slide = slideVelocity }

    private fun reportSlide(sample: fr.luba.poc.simulation.SlideSample) =
        env.event(PIVOT_SLIDE_EVENT, tr("Pivot slide measured", "Glissement au pivot mesuré"), mapOf("x" to sample.x, "y" to sample.y, "vx" to sample.vx,
            "vy" to sample.vy, "seconds" to sample.seconds, "atMs" to sample.atMs))
    private var index = 0
    /** Movement commands waiting to be written (label → queueing time), observed write delay. */
    private val queuedMoves = LinkedHashMap<String, Long>()
    private var moveSeq = 0L
    private var writeMaxMs = 0L
    private var writeCount = 0
    private var writeTotalMs = 0L
    /** Last measured and predicted pose (diagnostics of a stop). */
    private var lastDiagnostic: Map<String, Any?> = emptyMap()

    private fun moveWritten(label: String, at: Long) = synchronized(queuedMoves) {
        val queued = queuedMoves.remove(label) ?: return@synchronized
        val latency = (at - queued).coerceAtLeast(0)
        writeMaxMs = maxOf(writeMaxMs, latency); writeCount++; writeTotalMs += latency
    }

    /** Queueing → GATT write delay of movement commands (slow gateway: real delay under-compensated). */
    private fun writeSummary(): Map<String, Any?> = synchronized(queuedMoves) {
        mapOf("moveWrites" to writeCount, "moveWriteAvgMs" to if (writeCount > 0) writeTotalMs / writeCount else null,
            "moveWriteMaxMs" to writeMaxMs, "movesNotWritten" to queuedMoves.size)
    }

    /** Track of the last measured points (every 3 cm), for the back-off after a contact. */
    fun recentTrail(): List<Point> = trail.toList()

    fun calibration() = reckoning.calibration

    /** Follows the route from [LegSpec.from] to the end; the outcome of a safety stop is a FAILED [LegResult]. */
    suspend fun run(): LegResult {
        require(spec.from in path.indices) { tr("Start edge ${spec.from} outside the route of ${path.size} edges", "Arête de départ ${spec.from} hors du trajet de ${path.size} arêtes") }
        return try {
            if (spec.dockExit) return exitDock()
            val reached = followAll(spec.from)
            if (reached >= path.size) LegResult(spec.legId, LegOutcome.DONE, path.size, calibration = calibration())
            else LegResult(spec.legId, LegOutcome.LOW_BATTERY, reached, tr("Low battery threshold ${recharge.low} %", "Seuil bas de batterie ${recharge.low} %"), calibration = calibration())
        } catch (encounter: LegEncounter) {
            if (encounter.contact != null)
                LegResult(spec.legId, LegOutcome.CONTACT, encounter.index, encounter.message.orEmpty(), encounter.contact, calibration = calibration(), trail = recentTrail())
            else LegResult(spec.legId, LegOutcome.OBSTACLE_ADDED, encounter.index, encounter.message.orEmpty(),
                obstacleIds = encounter.added.map { it.id }, calibration = calibration(), trail = recentTrail())
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Requested interruption: it propagates (as an IllegalStateException it would be taken for a safety stop).
            throw cancel
        } catch (failure: IllegalStateException) {
            failed(failure.message ?: tr("Safety stop", "Arrêt de sécurité"))
        } catch (failure: IllegalArgumentException) {
            // Missing or invalid robot data (position, heading, state absent): same handling as a refused check.
            failed(tr("Invalid robot data: ${failure.message ?: failure.javaClass.simpleName}", "Donnée robot invalide : ${failure.message ?: failure.javaClass.simpleName}"))
        }
    }

    /**
     * Leaving the station run here (same loop as the pilot over direct Bluetooth): slow forward motion along the
     * base → link point axis, no pivot, 10 cm corridor, envelope and obstacles checked, then STOP and loss of the charging
     * contact confirmed. Bumper not armed (leaving the station), states 11/15/20/39 accepted.
     */
    private suspend fun exitDock(): LegResult {
        check(path.size == 1) { tr("Station exit: a single edge expected (${path.size})", "Sortie de station : une seule arête attendue (${path.size})") }
        val exit = DockExit(path[0].a, path[0].b)
        val begin = env.now()
        val motionProgress = MotionProgressGuard()
        var exitOverlapLogged = false
        var nextSync = begin; var nextReports = begin
        var seq = 0L
        if (spec.shown) env.progress(LegProgress(spec.legId, 1, 1, tr("Station exit", "Sortie de station"), false))
        while (true) {
            val view = env.view()
            val now = env.now()
            env.hostFailure()?.let { error(it) }
            env.linkProblem()?.let { error(it) }
            if (now >= nextSync) { env.send(commands.sync(), "ROUTE_SYNC"); nextSync = now + 5000 }
            if (now >= nextReports) { env.send(commands.reports(true, periodMs = 200), "ROUTE_REPORTS_KEEP"); nextReports = now + 8000 }
            val live = view.telemetry
            NavSafety.tickFailure(now - view.devicePrevious, live.battery, live.sensorStatus, false, -1)?.let { error(it) }
            check(SelfCheck.nominal(live.selfCheck)) { tr("Robot protection active (${live.selfCheck})", "Protection robot active (${live.selfCheck})") }
            check(live.robotState in listOf(11, 15, 20, 39)) { tr("Robot state incompatible with the station (state ${live.robotState})", "État robot incompatible avec la station (état ${live.robotState})") }
            NavSafety.automaticPositionFailure(now - view.positionPrevious, live.x, live.y)?.let { error(it) }
            val position = Point(requireNotNull(live.x), requireNotNull(live.y))
            val reported = requireNotNull(live.heading).also { check(it.isFinite()) }
            if (trail.lastOrNull()?.distance(position)?.let { it > 0.03 } != false) {
                trail.addLast(position); while (trail.size > TRAIL_POINTS) trail.removeFirst()
            }
            lastDiagnostic = mapOf("x" to position.x, "y" to position.y, "reportedHeading" to reported, "axis" to exit.axis,
                "positionAgeMs" to now - view.positionPrevious, "robotState" to live.robotState, "chargeState" to live.chargeState)
            val step = exit.step(position, reported)
            if (step.done) break
            check(now - begin < 60_000) { tr("Station exit timed out: link point not reached", "Sortie de station expirée : point de liaison non atteint") }
            check(!motionProgress.blocked(position, view.positionPrevious, now, step.linear)) { tr("Station exit stuck for 3 s", "Sortie de station bloquée depuis 3 s") }
            val rad = Math.toRadians(step.heading)
            val reach = minOf(0.10, position.distance(path[0].b))
            val next = RobotPose(Point(position.x + reach * kotlin.math.cos(rad), position.y + reach * kotlin.math.sin(rad)), step.heading)
            val envelopeGuard = physicalGuard ?: guard
            if (envelopeGuard != null && envelopeGuard.motionCollision(RobotPose(position, step.heading), next) != null) {
                // Chassis already overlapping the free surface on its station (learnt obstacle or no-go zone near the
                // base, RTK offset): same rule as a robot overlapping a border, the remaining straight exit must only
                // move it away and end inside the free surface ([TrajectoryEnvelope.leavesStraight]).
                val overlapping = !envelopeGuard.poseSafe(RobotPose(position, step.heading))
                check(overlapping && envelopeGuard.leavesStraight(position, path[0].b, step.heading)) { DockExit.envelopeRefusal(position, path[0].a, overlapping) }
                if (!exitOverlapLogged) {
                    exitOverlapLogged = true
                    env.event("route_dock_exit_overlap", tr("Chassis overlapping the free surface on the base: straight exit allowed while it only moves away",
                        "Châssis débordant de la surface libre sur la base : sortie tout droit admise tant qu'elle ne fait que s'en éloigner"),
                        mapOf("x" to position.x, "y" to position.y, "heading" to step.heading, "baseDistanceM" to position.distance(path[0].a)))
                }
            }
            check(obstacleHits(position, next.position, false).isEmpty()) { tr("Obstacle on the station exit", "Obstacle sur la sortie de station") }
            env.send(commands.movement(step.linear, 0), "ROUTE_DOCK_EXIT #${++seq}")
            env.delay(200)
        }
        env.purge(); env.send(commands.stop(), "ROUTE_DOCK_EXIT_STOP")
        val deadline = env.now() + 5000
        while (env.view().telemetry.chargeState != 0) {
            check(env.now() < deadline) { tr("Station exit not confirmed (charging contact still reported)", "Sortie de station non confirmée (contact de charge toujours signalé)") }
            env.delay(200)
        }
        env.event("route_dock_exit", tr("Slow straight exit to the link point", "Sortie rectiligne lente jusqu'au point de liaison"), mapOf("durationMs" to env.now() - begin,
            "headingJumpDeg" to exit.headingJump))
        return LegResult(spec.legId, LegOutcome.DONE, path.size, calibration = calibration(), trail = recentTrail())
    }

    /** Safety stop: pose, delays and track reported to the pilot (Fold log) for the test analysis. */
    private fun failed(text: String): LegResult {
        runCatching { env.event("leg_failure", tr("Autonomous route tracking stopped: $text", "Suivi autonome arrêté : $text"), lastDiagnostic + writeSummary() + mapOf("edge" to index + 1, "edges" to path.size)) }
        return LegResult(spec.legId, LegOutcome.FAILED, index, text, calibration = calibration(), trail = recentTrail())
    }

    private suspend fun followAll(from: Int): Int {
        var at = from
        while (at < path.size) {
            // Blades continuous within a zone: stop only when the zone changes and for the base.
            val blades = spec.cutting && path[at].bladesOn
            var to = at + 1
            while (to < path.size && (spec.cutting && path[to].bladesOn) == blades) to++
            val reached = if (spec.lastArrivalM != null && to == path.size) followPath(at, to, spec.lastArrivalM) else followPath(at, to, null)
            if (reached < to) return reached
            at = to
        }
        return path.size
    }

    private fun obstacleHits(position: Point, next: Point, escaping: Boolean): List<LegObstacle> =
        env.obstacles().filter { obstacle ->
            fr.luba.poc.obstacles.FollowObstacleGuard.intrudes(Point(obstacle.x, obstacle.y), position, next, obstacle.radiusM,
                obstacle.exclusionRadiusM, obstacle.planned, escaping)
        }

    /** Follows path[from until to) with a single blade state, without STOP at aligned vertices. Returns the index reached. */
    private suspend fun followPath(from: Int, to: Int, lastArrivalM: Double?): Int {
        val heightMm = spec.heightMm
        val atBase = spec.baseApproach != null && path[to - 1].b == spec.baseApproach
        val finalArrivalM = lastArrivalM ?: if (atBase) 0.05 else RangeArrival.TARGET_M
        index = from
        if (spec.watchBattery) {
            val start = env.view(); val startAt = env.now()
            val live = start.telemetry
            NavSafety.tickFailure(startAt - start.devicePrevious, live.battery, live.sensorStatus, true, RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
            if (recharge.returnRequired(requireNotNull(live.battery))) return from
        }
        val blades = spec.cutting && path[from].bladesOn
        check((from until to).all { (spec.cutting && path[it].bladesOn) == blades }) { tr("Inconsistent blade range", "Plage de lames incohérente") }
        fun showIndex(i: Int) {
            if (spec.shown) env.progress(LegProgress(spec.legId, i + 1, path.size, "${if (blades) tr("Cutting", "Coupe") else tr("Transit, blades OFF", "Transit lames OFF")} · ${i + 1}/${path.size}", blades))
        }
        showIndex(from)
        // Manual mode (state 20) also with blades OFF: the firmware only publishes the bumper contact (-1014) that way.
        // Except when leaving the station.
        val bumperWatch = !spec.departure
        // Blades refused before any command (gateway battery too low); blades-OFF ranges are still followed.
        if (blades) env.cuttingFailure()?.let { error(it) }
        if ((blades || bumperWatch) && env.view().telemetry.robotState != 20) {
            env.send(commands.manualMow(1, 0, heightMm), "ROUTE_MANUAL_MODE")
            val modeDeadline = env.now() + 3000
            while (env.view().telemetry.robotState != 20) {
                env.linkProblem()?.let { error(it) }
                check(env.now() < modeDeadline) {
                    if (blades) tr("Manual mowing mode not confirmed", "Mode tonte manuelle non confirmé") else tr("Bumper detection inactive: manual mode with blades OFF not confirmed", "Détection bumper inactive : mode manuel lames OFF non confirmé") }
                env.hostFailure()?.let { error(it) }
                env.delay(200)
            }
        }
        // Stop only when the blade state changes; OFF confirmed before any link.
        env.send(commands.stop(), "ROUTE_WAYPOINT_STOP")
        reckoning.sent(env.now(), 0, 0)
        val bladeGate = RouteBladeGate.unique("ROUTE_BLADES_${from}_${if (blades) "ON" else "OFF"}", blades)
        env.onWritten { label, at -> bladeGate.written(label, at); if (label.startsWith("ROUTE_MOVE")) moveWritten(label, at) }
        env.send(if (blades || !bumperWatch) commands.manualBlades(blades, heightMm) else commands.manualMow(1, 0, heightMm), bladeGate.label)
        val begin = env.now()
        val meters = (from until to).sumOf { path[it].meters }
        // Long range (full border): proportional budget, at least 5 cm/s on average.
        val budgetMs = 120_000L + (meters / 0.05 * 1000).toLong()
        val motionProgress = MotionProgressGuard()
        val follower = PathFollower(path, from, to, finalArrivalM).also { it.slide = slideVelocity }
        val returnFrom = ReturnDrift.returnFrom(path, spec.baseApproach)
        // End of range confirmed by a position measured after the stop (except the base approach, followed by docking).
        val arrival = if (atBase) null else RangeArrival(DeadReckoning.ROUTE_COMMAND_DELAY_MS)
        var fixAt = 0L
        var progressAt = begin; var best = Double.POSITIVE_INFINITY; var bestIndex = from
        var bladeObserved = !blades; var keepalive = 0L; var queryAt = 0L; var lastProgressAt = 0L
        // Robot link kept alive here as the pilot does (sync every 5 s, report subscription every 8 s): during an autonomous
        // route tracking, the pilot writes nothing. Renewed from the start: the pilot's subscription expires 10 s after its last
        // renewal (up to 8 s before the launch), otherwise positions stop at the start of the tracking.
        var nextSync = begin; var nextReports = begin
        val heightSince = env.wall()
        val heightBudgetMs = HeightSequence.timeoutMs(env.view().telemetry.bladeHeightMm, heightMm)
        var heightReady = !blades
        /** Protection raised while waiting for the contact code: time and stop reason if no contact follows. */
        var latch: Pair<Long, String>? = null
        // Bumper contact on the main edges: bypass rather than stopping the mission.
        env.armBypass(spec.shown)
        try {
            while (true) {
                // Robot view before the time: a report arriving in between (other thread) would have a negative age.
                val view = env.view()
                val now = env.now()
                env.contact()?.let { contact -> throw LegEncounter(follower.index, contact, emptyList(), tr("Bumper contact (${contact.code})", "Contact bumper (${contact.code})")) }
                env.hostFailure()?.let { error(it) }
                if (blades) env.cuttingFailure()?.let { error(it) }
                env.linkProblem()?.let { error(it) }
                if (now >= nextSync) { env.send(commands.sync(), "ROUTE_SYNC"); nextSync = now + 5000 }
                if (now >= nextReports) { env.send(commands.reports(true, periodMs = 200), "ROUTE_REPORTS_KEEP"); nextReports = now + 8000 }
                val live = view.telemetry
                NavSafety.automaticPositionFailure(now - view.positionPrevious, live.x, live.y)?.let { error(it) }
                NavSafety.tickFailure(now - view.devicePrevious, live.battery, live.sensorStatus, true, RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                // Move beyond the map: self-check 25 published on its sections is not a contact ([OffMapLatch]).
                val offMapLatch = spec.offMapSections.isNotEmpty() && !blades && fr.luba.poc.protocol.OffMapLatch.ignored(live.selfCheck, live.sensorStatus,
                    fr.luba.poc.protocol.OffMapLatch.onSections(live.x?.let { x -> live.y?.let { y -> Point(x, y) } }, spec.offMapSections))
                val protectionUp = !(live.robotState in (if (spec.departure) listOf(11, 15, 20, 39) else listOf(11, 20)) && (SelfCheck.nominal(live.selfCheck) || offMapLatch))
                // Protection waiting for the contact code: cleared, or delay elapsed without contact → stop on the protection.
                latch?.let { (since, reason) -> if (!protectionUp || now - since >= CollisionLatch.CONTACT_GRACE_MS) error(reason) }
                if (protectionUp) {
                    val reason = fr.luba.poc.simulation.ProtectionPause.reason(live.robotState, live.selfCheck)
                    // Collision lock (25) published before the contact code (2026-10-15, 11:00:06): immediate stop, then the
                    // contact read from the error history gives the back-off and the bypass ([CollisionLatch]).
                    if (latch == null) {
                        if (!CollisionLatch.mayAnnounceContact(live.robotState, live.selfCheck)) error(reason)
                        latch = now to reason
                        env.purge(); env.send(commands.movement(0, 0), "ROUTE_LATCH_STOP"); reckoning.sent(now, 0, 0)
                        env.event("route_latch_wait", tr("Robot protection: stopped, waiting for the contact code", "Protection du robot : arrêt, code de contact attendu"), mapOf("selfCheck" to live.selfCheck,
                            "robotState" to live.robotState, "edge" to follower.index, "graceMs" to CollisionLatch.CONTACT_GRACE_MS))
                        keepalive = 0L; queryAt = 0L
                    }
                    // Blades stopped, manual mode kept alive (the robot only publishes the contact that way), errors queried.
                    if ((blades || bumperWatch) && now >= keepalive) { env.send(commands.manualMow(1, 0, heightMm), "ROUTE_LATCH_BUMPER_OFF"); keepalive = now + 500 }
                    if (now >= queryAt) { env.send(commands.errorQuery(), "ROUTE_LATCH_ERRORS"); queryAt = now + 500 }
                    env.delay(100); continue
                }
                val published = Point(requireNotNull(live.x), requireNotNull(live.y))
                val reportedHeading = requireNotNull(live.heading).also { check(it.isFinite()) }
                val bladeBits = (requireNotNull(live.sensorStatus) shr 9) and 7
                if (blades && bladeGate.confirmed(view.devicePrevious, bladeBits)) bladeObserved = true
                if (!blades && !(bladeGate.confirmed(view.devicePrevious, bladeBits))) {
                    check(now - begin < 3000) { tr("Blade stop not confirmed", "Arrêt lames non confirmé") }
                    env.delay(200); continue
                }
                if (!heightReady) {
                    if (HeightSequence.reached(live, heightMm, heightSince)) {
                        heightReady = true
                        env.event("route_height", tr("Height $heightMm mm reported before cutting", "Hauteur $heightMm mm rapportée avant la coupe"), mapOf("waitMs" to now - begin))
                    } else {
                        check(now - begin < heightBudgetMs) { tr("Height $heightMm mm not reported (${live.bladeHeightMm ?: "—"} mm)", "Hauteur $heightMm mm non rapportée (${live.bladeHeightMm ?: "—"} mm)") }
                        if (blades && bladeObserved) check(bladeBits == 1) { tr("Blade rotation interrupted", "Rotation des lames interrompue") }
                        if (spec.shown) env.progress(LegProgress(spec.legId, from + 1, path.size, tr("Height setting $heightMm mm · ${live.bladeHeightMm ?: "—"} mm reported", "Réglage hauteur $heightMm mm · ${live.bladeHeightMm ?: "—"} mm rapportés"), blades))
                        progressAt = now
                        env.delay(200); continue
                    }
                }
                if (spec.watchBattery && recharge.returnRequired(requireNotNull(live.battery))) {
                    env.purge(); env.send(commands.stop(), "ROUTE_LOW_BATTERY_STOP")
                    return follower.index
                }
                if (blades && bladeObserved) check(bladeBits == 1) { tr("Blade rotation interrupted", "Rotation des lames interrompue") }
                if (blades) check(bladeObserved || now - begin < 5000) { tr("Blade rotation not confirmed", "Rotation lames non confirmée") }
                // Positions at 1 Hz: command on the predicted pose, checks on the measured pose.
                // Published heading realigned on the travelled direction (the LUBA 2 may publish a wrong heading when stopped).
                val fixed = view.positionPrevious != fixAt
                if (fixed) { fixAt = view.positionPrevious; reckoning.fix(published, reportedHeading, fixAt) }
                val heading = reckoning.heading(reportedHeading)
                // Rotation centre: the published point is 8 cm behind it ([DeadReckoning.PUBLISHED_POINT_BEHIND_M]).
                val position = reckoning.measured ?: reckoning.center(published, heading)
                if (fixed) slideMeter.update(position, env.wall(), reckoning.rotationSlide)?.let(::reportSlide)
                val headingTrusted = reckoning.headingConfirmed
                env.confirmedHeading(heading.takeIf { headingTrusted })
                val predicted = reckoning.predict(now) ?: RobotPose(position, heading)
                lastDiagnostic = mapOf("x" to position.x, "y" to position.y, "publishedX" to published.x, "publishedY" to published.y,
                    "reportedHeading" to reportedHeading, "heading" to heading,
                    "headingTrusted" to headingTrusted, "predictedX" to predicted.position.x, "predictedY" to predicted.position.y,
                    "predictedHeading" to predicted.headingDegrees, "positionAgeMs" to now - view.positionPrevious,
                    "deviationM" to follower.deviation(position), "robotState" to live.robotState)
                val previousIndex = follower.index
                val speedPercent = spec.speedPercent
                // Heading not confirmed, or pivot not measured yet: slow advance until the next position.
                val linearLimit = if (!headingTrusted) minOf(PathFollower.linearFor(speedPercent), DeadReckoning.UNCONFIRMED_LINEAR)
                    else reckoning.postPivotLinear(PathFollower.linearFor(speedPercent))
                val command = follower.command(predicted.position, predicted.headingDegrees, linearLimit,
                    PathFollower.angularLimit(speedPercent), reckoning.degreesPerUnit, reckoning.metersPerUnit,
                    RangeArrival.APPROACH_MIN_LINEAR, follower.deviation(position), reckoning.rotationCalibrated)
                index = follower.index
                if (follower.index != previousIndex) showIndex(follower.index)
                val edge = path[follower.index]
                // Return to base: drift tolerated up to 1 m, chassis inside the safe surface and outside the base zone.
                // Pivot on a slope: slide tolerated up to 60 cm during the pivot and the reconvergence (2026-10-15).
                val sliding = slide.update(follower.pivoting, position, follower.deviation(position))
                ReturnDrift.failure(follower.deviation(position), follower.index, follower.index >= returnFrom, position, spec.baseZone, sliding) { p ->
                    guard?.bodySafe(p) ?: false }?.let { error(it) }
                if (spec.sampling && spec.shown && edge.cutting) {
                    val near = (maxOf(from, follower.index - 1)..minOf(to - 1, follower.index + 1)).map { path[it] }.filter { it.cutting }.map { listOf(it.a, it.b) }
                    SignalCoverage.sample(live, env.wall(), near, env.tilt())?.let { sample -> env.sample(sample) }
                }
                if (trail.lastOrNull()?.distance(position)?.let { it > 0.03 } != false) {
                    trail.addLast(position); while (trail.size > TRAIL_POINTS) trail.removeFirst()
                }
                if (spec.shown && now - lastProgressAt >= 1000) {
                    lastProgressAt = now
                    env.progress(LegProgress(spec.legId, follower.index + 1, path.size, "${if (blades) tr("Cutting", "Coupe") else tr("Transit, blades OFF", "Transit lames OFF")} · ${follower.index + 1}/${path.size}", blades))
                }
                if (command.done) {
                    // Very short range: wait for blade confirmation while stopped rather than failing.
                    if (blades && !bladeObserved) { env.send(commands.movement(0, 0), "ROUTE_WAIT_BLADES"); reckoning.sent(now, 0, 0); env.delay(200); continue }
                    when (RangeArrival.settle(arrival, now, fixAt, position, follower, path[to - 1].b)) {
                        null -> {
                            // Commanded stop: standing still is normal, blades and bumper detection kept alive.
                            motionProgress.reset(); progressAt = now
                            if (blades && now >= keepalive) { env.send(commands.manualBlades(true, heightMm), "ROUTE_BLADES_KEEPALIVE"); keepalive = now + 600 }
                            if (!blades && bumperWatch && now >= keepalive) { env.send(commands.manualMow(1, 0, heightMm), "ROUTE_BUMPER_KEEPALIVE_OFF"); keepalive = now + 500 }
                            if (now >= queryAt) { env.send(commands.errorQuery(), "ROUTE_ERRORS"); queryAt = now + 500 }
                            env.send(commands.movement(0, 0), "ROUTE_SETTLE $to"); reckoning.sent(now, 0, 0); env.delay(200); continue
                        }
                        false -> {
                            env.event("route_settle", tr("Stopped before the end point of the range: approach resumed", "Arrêt avant le point de fin de plage : approche reprise"), mapOf("edge" to to, "approach" to arrival?.approaches,
                                "distanceM" to arrival?.distanceM, "remainingM" to arrival?.remainingM, "x" to position.x, "y" to position.y))
                            continue
                        }
                        true -> break
                    }
                }
                val distance = position.distance(edge.b)
                if (follower.index > bestIndex || distance < best - 0.03) { bestIndex = follower.index; best = distance; progressAt = now }
                val (linear, angular) = command.linear to command.angular
                if (linear == 0) progressAt = now
                check(now - progressAt < 15000) { tr("No progress for 15 s (edge ${follower.index + 1})", "Absence de progression depuis 15 s (arête ${follower.index + 1})") }
                check(now - begin < budgetMs) { tr("Range too long: %.0f m not covered in %d s", "Plage trop longue : %.0f m non parcourus en %d s").format(meters, budgetMs / 1000) }
                check(!motionProgress.blocked(position, view.positionPrevious, now, linear)) { tr("Presumed blockage: no translation for 3 s, bumper contact not confirmed", "Blocage présumé : aucune translation depuis 3 s, contact bumper non confirmé") }
                val rad = Math.toRadians(heading)
                // Projection in front of the robot: distance travelled before the next position (1 Hz) and the reaction,
                // bounded by the next stop (corner or end of range).
                val reach = minOf(maxOf(0.25, linear * reckoning.metersPerUnit * 1.5), command.stopDistanceM)
                val next = if (linear == 0) RobotPose(position, heading + if (angular > 0) 6.0 else -6.0)
                    else RobotPose(Point(position.x + reach * kotlin.math.cos(rad), position.y + reach * kotlin.math.sin(rad)), heading)
                // Escape out of an obstacle reserve: same checks, obstacles at their physical radius.
                val escaping = follower.escaping(position)
                val intruding = obstacleHits(position, next.position, escaping)
                // Obstacle added during the mission (absent from the prepared route): bypass.
                val added = intruding.filter { !it.planned }
                if (spec.shown && added.isNotEmpty()) throw LegEncounter(follower.index, null, added, tr("Obstacle added on the route", "Obstacle ajouté sur le trajet"))
                // Obstacle already bypassed by the route: the pilot resumes with a back-off and a link ([DriftRecovery]).
                check(intruding.isEmpty()) { if (added.isEmpty()) DriftRecovery.OBSTACLE_RESERVE else tr("Local obstacle added inside the robot envelope", "Obstacle local ajouté dans l'enveloppe robot") }
                val envelopeGuard = if (escaping) physicalGuard ?: guard else guard
                // Oriented envelope only with a confirmed heading; otherwise slow advance, centre and deviation checked.
                // Escape in a straight line of a chassis already overlapping an edge: allowed while it only moves away.
                val leaving = escaping && next.position != position && envelopeGuard?.leavesStraight(position, next.position, heading) == true
                check(!headingTrusted || envelopeGuard == null || leaving || envelopeGuard.motionCollision(RobotPose(position, heading), next) == null) { tr("Robot envelope outside the safe corridor", "Enveloppe robot hors du couloir sûr") }
                if (blades && now >= keepalive) { env.send(commands.manualBlades(true, heightMm), "ROUTE_BLADES_KEEPALIVE"); keepalive = now + 600 }
                if (!blades && bumperWatch && now >= keepalive) { env.send(commands.manualMow(1, 0, heightMm), "ROUTE_BUMPER_KEEPALIVE_OFF"); keepalive = now + 500 }
                // Bumper contact read from the error history: queried twice per second.
                if (now >= queryAt) { env.send(commands.errorQuery(), "ROUTE_ERRORS"); queryAt = now + 500 }
                val moveLabel = "ROUTE_MOVE ${follower.index} $linear $angular #${++moveSeq}"
                synchronized(queuedMoves) { queuedMoves[moveLabel] = now; while (queuedMoves.size > 50) queuedMoves.remove(queuedMoves.keys.first()) }
                env.send(commands.movement(linear, angular), moveLabel)
                reckoning.sent(now, linear, angular)
                env.delay(200)
            }
        } finally { env.armBypass(false); env.onWritten(null); env.confirmedHeading(null); slideMeter.flush()?.let(::reportSlide) }
        env.purge(); env.send(commands.stop(), "ROUTE_SEGMENT_STOP")
        reckoning.sent(env.now(), 0, 0)
        return to
    }

    companion object {
        const val TRAIL_POINTS = 400
        /** Event for a measured pivot slide (fields x, y, vx, vy, seconds, atMs), relayed to the pilot. */
        const val PIVOT_SLIDE_EVENT = "pivot_slide"
    }
}
