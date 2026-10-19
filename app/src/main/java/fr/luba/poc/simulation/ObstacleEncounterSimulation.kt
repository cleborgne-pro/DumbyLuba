package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

/** Shape of the object placed on the map for the obstacle simulation. */
enum class ProbeShape(private val labelEn: String, private val labelFr: String) {
    SQUARE("Square", "Carré"), CIRCLE("Round", "Rond"), TRIANGLE("Triangle", "Triangle");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}

/**
 * Physical object unknown to the planner: square of side [sizeM], round of diameter [sizeM] or equilateral triangle of
 * side [sizeM], centred at [center], rotated by [rotationDeg].
 */
data class ProbeObject(val shape: ProbeShape, val center: Point, val sizeM: Double, val rotationDeg: Double = 0.0) {
    init { require(sizeM.isFinite() && sizeM in MIN_SIZE_M..MAX_SIZE_M) { tr("Size out of range", "Taille hors plage") } }
    companion object { const val MIN_SIZE_M = 0.1; const val MAX_SIZE_M = 3.0 }
    fun outline(): List<Point> {
        val (count, radius, phase) = when (shape) {
            ProbeShape.SQUARE -> Triple(4, sizeM / sqrt(2.0), 45.0)
            ProbeShape.CIRCLE -> Triple(32, sizeM / 2, 0.0)
            ProbeShape.TRIANGLE -> Triple(3, sizeM / sqrt(3.0), 90.0)
        }
        return (0 until count).map { i ->
            val a = Math.toRadians(phase + rotationDeg + i * 360.0 / count)
            Point(center.x + radius * cos(a), center.y + radius * sin(a))
        }
    }
    /** Radius of the circumscribed circle. */
    val extentM: Double get() = outline().maxOf { it.distance(center) }
}

/** Route of a zone prepared like a mowing (passes, links, return to the base approach point). */
data class EncounterRoute(val engine: MowingSimulation, val edges: List<SimEdge>, val physical: TrajectoryPlanner, val zones: List<List<Point>>)

enum class EncounterPhase(private val labelEn: String, private val labelFr: String) {
    FOLLOWING("Following the route", "Suivi du trajet"), BACKING_OFF("Back-off after contact", "Recul après contact"),
    DONE("Route finished", "Trajet terminé"), STOPPED("Stop kept", "Arrêt maintenu");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)
}

/** Immutable snapshot of the simulation (display while the computation advances on another thread). */
data class EncounterFrame(
    val pose: RobotPose, val phase: EncounterPhase, val message: String, val trail: List<Point>,
    /** Original route from the start, remaining route to follow (adapted after a contact). */
    val original: List<Point>, val remaining: List<Point>,
    val contacts: List<EncounterContact>, val discovered: List<LocalObstacle>, val log: List<String>,
    val elapsedS: Double, val meters: Double,
)

/** Simulated contact: stopped pose, estimated obstacle and repair obtained (or stop reason). */
data class EncounterContact(val pose: RobotPose, val estimated: LocalObstacle, val repair: RouteRepair?, val failure: String?)

/**
 * Simulation of an unknown obstacle met during mowing, with the same building blocks as actual mowing:
 * - **route tracking**: the actual mowing pilot ([PathFollower] on the pose predicted by [DeadReckoning], one range per
 *   blade state, STOP between two ranges, end of range confirmed by [RangeArrival], commands at 5 Hz, positions
 *   published at 1 Hz, deviation check [ReturnDrift]) drives a [SimulatedRobot] (delay, inertia, friction, actual gains
 *   unknown to the tracking);
 * - **contact** detected when the LUBA 2 footprint touches the object (bumper), obstacle estimated in front of the
 *   bumper ([ObstacleBypass.contactObstacle]), acceptance rules ([ObstacleBypass.contactFailure]), straight back-off
 *   and remaining route repaired ([ObstacleBypass.repair]).
 * Only the Bluetooth exchanges, the blades and the camera are missing.
 */
class ObstacleEncounterSimulation(
    private val routes: TrajectoryPlanner, private val physical: TrajectoryPlanner?, original: List<SimEdge>,
    private val obstacle: ProbeObject, private val zones: List<List<Point>>, startIndex: Int = 0,
    private val robot: RobotEnvelope = RobotEnvelope.LUBA2,
    /** Route forward speed (robot setting, 30 % by default). */
    private val speedPercent: Int = 30,
    /** Base approach point, if the route ends there: arrival within 5 cm without [RangeArrival], return drift tolerated. */
    private val baseApproach: Point? = null,
    dynamics: SimulatedRobot.Params = SimulatedRobot.Params(),
    private val backOffMps: Double = BACK_OFF_LINEAR * DeadReckoning.DEFAULT_METERS_PER_UNIT,
    /**
     * Projected footprint check of the actual tracking ("Robot envelope outside the safe corridor"). Disabled by
     * default: the simulated robot, which turns more than the real one while advancing, triggers it far more often
     * than in the field.
     */
    private val envelopeGuard: Boolean = false,
) {
    companion object {
        /** Back-off after contact: forward command of the actual pilot (≈ 10 cm/s). */
        const val BACK_OFF_LINEAR = 100
        /** Noise of the published positions (RTK) and published heading, fixed seed: reproducible playback. */
        private const val NOISE_M = 0.01
        private const val HEADING_NOISE_DEG = 1.0

        /** Prepares the mowing route of [zone] (same computation as the map), start at the base approach point. */
        fun route(map: RobotMap, zone: MapShape, base: MapBase, accesses: List<List<Point>>, obstacles: List<LocalObstacle>,
                  spacingM: Double = 0.25, checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): EncounterRoute {
            requireNotNull(base.entryHeadingDegrees) { tr("Unknown base axis: observe the robot while charging", "Axe de la base inconnu : observer le robot en charge") }
            val recorded = !map.source.contains("DÉMONSTRATION", true)
            val routes = RoutePlanning.mission(map, obstacles, accesses, base, recordedBoundary = recorded)
            val config = PlanningConfig(spacingM = spacingM, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = 0.95,
                borders = true, borderPasses = 1, noGoPasses = 1, bordersFirst = true, nearbyNoGo = true,
                boundary = if (recorded) BoundaryMeaning.RECORDED_TRACE else BoundaryMeaning.PHYSICAL, outerOffsetM = OuterOffsetSetting.meters)
            progress(tr("Computing passes", "Calcul des passes"))
            val planned = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point, config = config,
                obstacles = fr.luba.poc.planning.DockClearance.planning(obstacles, base),
                navigation = PlanningNavigation(accesses, base)), checkpoint = checkpoint)
            require(!planned.preview.localOnly) { tr("Plan not validated: ${planned.report?.selected?.diagnostics?.joinToString(" ; ") ?: "insufficient coverage"}",
                "Plan non validé : ${planned.report?.selected?.diagnostics?.joinToString(" ; ") ?: "couverture insuffisante"}") }
            checkpoint(); progress(tr("Checking links", "Vérification des raccords"))
            val engine = MowingSimulation(routes, base.point, planned.preview, map,
                SimConfig(instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM)) { checkpoint() }
            val last = engine.edges.last()
            val back = routes.between(last.b, engine.baseApproach, PathFollower.headingOf(last), (engine.dockEntryHeading + 180) % 360)
            val edges = engine.edges + back.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-8 }.map { (a, b) -> SimEdge(a, b, false) }
            return EncounterRoute(engine, edges, RoutePlanning.physical(map, obstacles, recorded), listOf(zone.points))
        }

        /** Start a few metres before the first pass near the object (shorter playback), otherwise 0. */
        fun startNear(edges: List<SimEdge>, obstacle: ProbeObject, leadM: Double = 4.0, robot: RobotEnvelope = RobotEnvelope.LUBA2): Int {
            val reach = obstacle.extentM + robot.turnClearanceM + 0.1
            val hit = edges.indexOfFirst { Geometry.distance(obstacle.center, it.a, it.b) <= reach }
            if (hit <= 0) return 0
            var meters = 0.0
            var i = hit - 1
            while (i > 0 && meters < leadM) { meters += edges[i].meters; i-- }
            return i
        }
    }

    private val factory = GeometryFactory()
    private val body = PreparedGeometryFactory.prepare(factory.createPolygon(
        (obstacle.outline() + obstacle.outline().first()).map { Coordinate(it.x, it.y) }.toTypedArray()))

    /** Original route (display) and followed route, possibly repaired. */
    val original: List<SimEdge> = original.drop(startIndex.coerceIn(0, original.lastIndex))
    var edges: List<SimEdge> = this.original; private set
    private var origins: List<Int>? = null
    /** Followed edge (index in [edges]). */
    var index = 0; private set
    private val mower = SimulatedRobot(RobotPose(this.original.first().a, PathFollower.headingOf(this.original.first())), dynamics)
    /** Actual pose of the simulated robot. */
    val pose: RobotPose get() = mower.pose
    var phase = EncounterPhase.FOLLOWING; private set
    var message = tr("Following the prepared route, object unknown to the planner", "Suivi du trajet préparé, objet inconnu du planificateur"); private set
    val trail = mutableListOf(pose.position)
    val contacts = mutableListOf<EncounterContact>()
    val discovered = mutableListOf<LocalObstacle>()
    val log = mutableListOf<String>()
    var elapsedS = 0.0; private set
    var meters = 0.0; private set
    /** Back-off in progress: target point and route to apply once reached. */
    private var backOff: Pair<Point, () -> Unit>? = null
    private var backOffStart: Point? = null
    private var bypasses = 0

    // Actual mowing pilot: predicted pose, current blade range, confirmed arrival, progress guard.
    private val random = java.util.Random(1)
    private var ms = 0L
    private var carryS = 0.0
    private var reckoning = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS, publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M)
    /** Point published by the simulated robot: like the LUBA 2, 8 cm behind its rotation centre, noisy. */
    private var published = behind(pose)
    /** Rotation centre measured by dead reckoning from the published point: the one checked by tracking and guards. */
    private var measured = pose.position
    private var fixAt = 0L
    private var follower: PathFollower? = null
    private var rangeEnd = 0
    private var arrival: RangeArrival? = null
    private val progress = fr.luba.poc.diagnostics.MotionProgressGuard()
    /** Navigation for the rest of the mission (discovered exclusions included) and physical surface of the escapes. */
    private var missionRoutes = routes
    private var missionPhysical = physical ?: routes
    /** Heading published by the simulated robot (noisy), realigned by dead reckoning as in actual mowing. */
    private var reportedHeading = pose.headingDegrees
    /** Route exits recovered ([DriftRecovery]). */
    var driftRecoveries = 0; private set
    private var lastDrift: Point? = null
    private var driftStreak = 0
    private var returnFrom = ReturnDrift.returnFrom(edges, baseApproach)
    private val baseZone = routes.baseSafety?.let { (station, _, radius) -> ReturnDrift.BaseZone(station, radius) }
    /** Pivot slide: same tolerance as actual mowing. */
    private val slide = PivotSlide()
    /** Maximum deviation of the published position from the followed route (actual mowing check). */
    var worstDeviationM = 0.0; private set

    init { reckoning.fix(published, pose.headingDegrees, 0); measured = reckoning.measured ?: pose.position }

    /** Published point for the actual pose [p]: [DeadReckoning.PUBLISHED_POINT_BEHIND_M] behind the centre, on the axis. */
    private fun behind(p: RobotPose): Point {
        val r = Math.toRadians(p.headingDegrees)
        return Point(p.position.x - DeadReckoning.PUBLISHED_POINT_BEHIND_M * cos(r), p.position.y - DeadReckoning.PUBLISHED_POINT_BEHIND_M * sin(r))
    }

    val running get() = phase == EncounterPhase.FOLLOWING || phase == EncounterPhase.BACKING_OFF
    /** Part of the followed route still to travel (display). */
    fun remaining(): List<Point> = if (!running) emptyList() else
        listOf(pose.position) + edges.drop(index).map { it.b }

    fun frame() = EncounterFrame(pose, phase, message, trail.toList(), listOf(original.first().a) + original.map { it.b },
        remaining(), contacts.toList(), discovered.toList(), log.takeLast(12), elapsedS, meters)

    /** Chassis footprint at [p]: front/rear/half-width rectangle of the LUBA 2. */
    fun footprint(p: RobotPose): List<Point> {
        val r = Math.toRadians(p.headingDegrees); val c = cos(r); val s = sin(r)
        return listOf(robot.frontM to robot.halfWidthM, robot.frontM to -robot.halfWidthM, -robot.rearM to -robot.halfWidthM, -robot.rearM to robot.halfWidthM)
            .map { (f, l) -> Point(p.position.x + f * c - l * s, p.position.y + f * s + l * c) }
    }
    private fun touches(p: RobotPose): Boolean = body.intersects(factory.createPolygon(
        footprint(p).let { it + it.first() }.map { Coordinate(it.x, it.y) }.toTypedArray()))

    private fun record(p: Point) { if (trail.last().distance(p) >= 0.03) trail += p }
    private fun note(text: String) { log += "%.0f s · %s".format(elapsedS, text) }

    /** Advances the simulation by [seconds] (simulated time), in 200 ms command periods. */
    fun advance(seconds: Double) {
        carryS += seconds
        val period = SimulatedRobot.PERIOD_MS / 1000.0
        while (carryS >= period - 1e-9 && running) {
            carryS -= period
            when (phase) {
                EncounterPhase.FOLLOWING -> follow()
                EncounterPhase.BACKING_OFF -> back()
                else -> Unit
            }
        }
        if (!running) carryS = 0.0
    }

    /** Playback to the end (or the stop), bounded. */
    fun finish(limitS: Double = 6 * 3600.0) { while (running && elapsedS < limitS) advance(60.0) }

    /** One period: simulated robot command, contact checked at each integration step, position published at 1 Hz. */
    private fun drive(linear: Int, angular: Int): Boolean {
        reckoning.sent(ms, linear, angular)
        val before = pose.position
        var touched = false
        mower.step(linear, angular) { next -> if (touches(next)) { touched = true; false } else true }
        ms += SimulatedRobot.PERIOD_MS; elapsedS = ms / 1000.0
        meters += pose.position.distance(before); record(pose.position)
        publish()
        if (touched) { contact(); return false }
        return true
    }

    private fun publish() {
        if (ms % 1000 != 0L) return
        val point = behind(pose)
        published = Point(point.x + random.nextGaussian() * NOISE_M, point.y + random.nextGaussian() * NOISE_M)
        fixAt = ms
        reportedHeading = pose.headingDegrees + random.nextGaussian() * HEADING_NOISE_DEG
        reckoning.fix(published, reportedHeading, ms)
        measured = reckoning.measured ?: published
    }

    /** Next range: consecutive edges with the same blade state, STOP on change, like actual mowing. */
    private fun startRange(): Boolean {
        if (index >= edges.size) {
            phase = EncounterPhase.DONE
            message = if (baseApproach != null && edges.last().b == baseApproach) tr("Route finished at the base approach point", "Trajet terminé au point d'approche de la base")
                else tr("Route finished", "Trajet terminé")
            note(message)
            return false
        }
        var to = index + 1
        while (to < edges.size && edges[to].bladesOn == edges[index].bladesOn) to++
        val towardBase = baseApproach != null && edges[to - 1].b == baseApproach
        follower = PathFollower(edges, index, to, if (towardBase) 0.05 else RangeArrival.TARGET_M)
        arrival = if (towardBase) null else RangeArrival(DeadReckoning.ROUTE_COMMAND_DELAY_MS)
        rangeEnd = to
        progress.reset()
        drive(0, 0)
        return true
    }

    private fun follow() {
        val f = follower ?: run { startRange(); return }
        val deviation = f.deviation(measured)
        worstDeviationM = max(worstDeviationM, deviation)
        ReturnDrift.failure(deviation, f.index, f.index >= returnFrom, measured, baseZone, slide.update(f.pivoting, measured, deviation)) { missionRoutes.bodySafe(it) }
            ?.let { return halt(it) }
        val predicted = reckoning.predict(ms) ?: RobotPose(measured, pose.headingDegrees)
        val nominal = PathFollower.linearFor(speedPercent)
        val limit = if (!reckoning.headingConfirmed) minOf(nominal, DeadReckoning.UNCONFIRMED_LINEAR) else reckoning.postPivotLinear(nominal)
        val c = f.command(predicted.position, predicted.headingDegrees, limit, PathFollower.angularLimit(speedPercent),
            reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR, deviation, reckoning.rotationCalibrated)
        index = f.index
        if (c.done) {
            when (RangeArrival.settle(arrival, ms, fixAt, measured, f, edges[rangeEnd - 1].b)) {
                null -> { progress.reset(); drive(0, 0) }
                false -> Unit
                true -> { index = rangeEnd; follower = null }
            }
            return
        }
        if (progress.blocked(measured, fixAt, ms, c.linear)) return halt(tr("Presumed blockage: no translation for 3 s", "Blocage présumé : aucune translation depuis 3 s"))
        // Projected footprint ahead of the robot on its realigned heading: same check as the actual tracking (Fold and gateway).
        if (envelopeGuard && reckoning.headingConfirmed) {
            val heading = reckoning.heading(reportedHeading)
            val rad = Math.toRadians(heading)
            val reach = min(max(0.25, c.linear * reckoning.metersPerUnit * 1.5), c.stopDistanceM)
            val next = if (c.linear == 0) RobotPose(measured, heading + if (c.angular > 0) 6.0 else -6.0)
                else RobotPose(Point(measured.x + reach * cos(rad), measured.y + reach * sin(rad)), heading)
            val guard = if (edges[f.index].escape) missionPhysical else missionRoutes
            if (guard.motionCollision(RobotPose(measured, heading), next) != null) return halt(tr("Robot envelope outside the safe corridor", "Enveloppe robot hors du couloir sûr"))
        }
        drive(c.linear, c.angular)
    }

    /** Straight back-off, heading unchanged, at the actual pilot's speed; contact impossible (the robot moves away). */
    private fun back() {
        val (target, then) = requireNotNull(backOff)
        val from = requireNotNull(backOffStart)
        val total = from.distance(target)
        val done = pose.position.distance(from)
        if (done >= total - 1e-6) {
            reckoning.sent(ms, 0, 0)
            val apply = then; backOff = null; backOffStart = null
            apply()
            return
        }
        val step = min(total - done, backOffMps * SimulatedRobot.PERIOD_MS / 1000.0)
        val ux = (target.x - from.x) / total; val uy = (target.y - from.y) / total
        reckoning.sent(ms, -BACK_OFF_LINEAR, 0)
        mower.place(if (done + step >= total - 1e-6) target else Point(pose.position.x + ux * step, pose.position.y + uy * step))
        ms += SimulatedRobot.PERIOD_MS; elapsedS = ms / 1000.0
        meters += step; record(pose.position)
        publish()
    }

    /** Contact: stop, estimated obstacle, acceptance rules then repair shared with actual mowing. */
    private fun contact() {
        mower.halt(); follower = null
        val estimated = ObstacleBypass.contactObstacle("simulation-${contacts.size + 1}", pose.position, pose.headingDegrees, ms)
        note(tr("Contact detected (%.1f; %.1f): stop, blades OFF", "Contact détecté (%.1f ; %.1f) : arrêt, lames OFF").format(pose.position.x, pose.position.y))
        val refusal = ObstacleBypass.contactFailure(discovered, estimated, bypasses)
        if (refusal != null) return stop(estimated, refusal)
        discovered += estimated
        val repair = try {
            ObstacleBypass.repair(routes, physical, edges, origins, index.coerceAtMost(edges.lastIndex), pose.position, pose.headingDegrees,
                discovered.toList(), zones, contact = true, trail = trail.takeLast(400))
        } catch (e: IllegalArgumentException) { return stop(estimated, e.message ?: tr("Bypass impossible", "Contournement impossible")) }
        bypasses++
        contacts += EncounterContact(pose, estimated, repair, null)
        val target = requireNotNull(repair.backOff)
        backOff = target to {
            follow(repair.edges, repair.origins)
            missionRoutes = repair.routes; repair.physical?.let { missionPhysical = it }
            message = tr("Route adapted${ObstacleBypass.sideLabel(repair.detours.firstOrNull())}: ${repair.bypasses} link(s), %.1f m of cutting left around",
                "Trajet adapté${ObstacleBypass.sideLabel(repair.detours.firstOrNull())} : ${repair.bypasses} raccord(s), %.1f m de coupe laissés autour").format(repair.skippedCuttingMeters)
            note(message)
        }
        backOffStart = pose.position
        phase = EncounterPhase.BACKING_OFF
        message = tr("Contact ${contacts.size}: back-off of %.0f cm then adapted route", "Contact ${contacts.size} : recul de %.0f cm puis trajet adapté").format(target.distance(pose.position) * 100)
        note(message)
    }

    /**
     * Followed route replaced (repair, resume): from its first edge, range recomputed. Like a new actual tracking (Fold
     * or gateway), dead reckoning restarts from the published position with the acquired calibration: heading to be
     * confirmed again by the motion (slow advance, footprint check suspended until then).
     */
    private fun follow(path: List<SimEdge>, pathOrigins: List<Int>) {
        reckoning = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS, calibration = reckoning.calibration,
            publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M)
        reckoning.fix(published, reportedHeading, fixAt)
        measured = reckoning.measured ?: published
        edges = path; origins = pathOrigins; index = 0
        returnFrom = ReturnDrift.returnFrom(edges, baseApproach)
        follower = null
        phase = EncounterPhase.FOLLOWING
    }

    /**
     * Tracking safety stop: like actual mowing, leaving the route (deviation, footprint outside the corridor) is recovered
     * by a back-off along the trail then a link to the route ([DriftRecovery]); any other stop (blockage) ends the
     * mission.
     */
    private fun halt(reason: String) {
        mower.halt(); follower = null
        if (DriftRecovery.recoverable(reason)) recover(reason) else stopFollowing(reason)
    }

    private fun stopFollowing(reason: String) {
        phase = EncounterPhase.STOPPED
        message = reason
        note(tr("Safety stop: $reason", "Arrêt de sécurité : $reason"))
    }

    private fun recover(reason: String) {
        val position = pose.position
        driftStreak = if (lastDrift?.let { it.distance(position) < DriftRecovery.SAME_SPOT_M } == true) driftStreak + 1 else 0
        lastDrift = position
        DriftRecovery.refusal(driftRecoveries, driftStreak)?.let { return stopFollowing(tr("$reason: $it", "$reason : $it")) }
        val plan = try {
            DriftRecovery.plan(missionRoutes, missionPhysical, edges, origins, index.coerceAtMost(edges.lastIndex), position,
                pose.headingDegrees, trail.takeLast(400), skipM = driftStreak * DriftRecovery.SAME_SPOT_M)
        } catch (e: IllegalArgumentException) { return stopFollowing(tr("$reason: ${e.message}", "$reason : ${e.message}")) }
        driftRecoveries++
        note(tr("Route exit ($reason): %s link of %.1f m blades OFF", "Sortie de trajectoire ($reason) : %s raccord de %.1f m lames OFF").format(
            plan.backOff?.let { tr("back-off of %.0f cm then", "recul de %.0f cm puis").format(it.distance(position) * 100) } ?: "", plan.rejoinMeters))
        val resume = {
            follow(plan.edges, plan.origins)
            message = tr("Resume after route exit · link of %.1f m blades OFF", "Reprise après sortie de trajectoire · raccord de %.1f m lames OFF").format(plan.rejoinMeters)
            note(message)
        }
        val target = plan.backOff
        if (target == null) { resume(); return }
        backOff = target to resume; backOffStart = position
        phase = EncounterPhase.BACKING_OFF
        message = tr("$reason: back-off of %.0f cm towards the safe surface", "$reason : recul de %.0f cm vers la surface sûre").format(target.distance(position) * 100)
    }

    private fun stop(estimated: LocalObstacle, reason: String) {
        contacts += EncounterContact(pose, estimated, null, reason)
        phase = EncounterPhase.STOPPED
        message = reason
        note(tr("Stop kept: $reason", "Arrêt maintenu : $reason"))
    }
}
