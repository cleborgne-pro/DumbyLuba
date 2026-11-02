package fr.luba.poc.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import fr.luba.poc.alerts.*
import fr.luba.poc.autonomy.LegObstacle
import fr.luba.poc.autonomy.LegProgress
import fr.luba.poc.autonomy.LegRunner
import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.diagnostics.*
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Result of [DockExitRunner.straightDockExit]: run by the gateway, or tracked from here with the heading jump observed. */
internal data class DockExitResult(val viaGateway: Boolean, val headingJump: Double?)

/**
 * Base exits shared by route missions ([RouteOrchestrator]) and moves ([NavigationRunner]). Extracted from
 * [RouteOrchestrator] unchanged; the forwarders below list what it reads from the host.
 */
internal class DockExitRunner(private val host: RobotBle) {
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private val calibration get() = host.calibration
    private val commands get() = host.commands
    private val gatt: RobotLink? get() = host.gatt
    private val legs get() = host.legs
    private val positionPrevious get() = host.positionPrevious
    private val state get() = host.state

    /**
     * Slow straight exit from the base to the link point in front of it ([DockExit]), blades OFF, no pivot: shared by
     * routes (mowing, survey, leave the base) and moves (move to a point, map route test). Gateway on the robot: run by
     * it over Bluetooth ([fr.luba.poc.autonomy.LegRunner], dock exit leg), otherwise tracked from here at 200 ms with
     * the envelope ([routes]), [obstacles], blockage and 60 s checks, then the release of the charging contact.
     * [live]: fresh robot state (throws on a safety failure); [navProgress] for a move (navigation screen).
     */
    internal suspend fun straightDockExit(base: Point, approach: Point, routes: TrajectoryPlanner, heightMm: Int, returnPercent: Int,
                                         obstacles: () -> List<LocalObstacle>, live: () -> Telemetry,
                                         onPosition: (Point) -> Unit, onGateway: () -> Unit, stopLabel: String,
                                         navProgress: ((LegProgress) -> Unit)? = null): DockExitResult {
        val began=SystemClock.elapsedRealtime()
        // Gateway on the robot: the exit (commands every 200 ms) is run by it, over Bluetooth a few
        // centimetres away, rather than through Wi-Fi (jerks: -2804, stale positions, GATT delay).
        val link = gatt as? GatewayLink
        if (link != null && link.supportsDockExit && host.autonomousLegsAvailable()) {
            onGateway()
            val exitPath = listOf(SimEdge(base, approach, false))
            val routeId = legs.ensureRoute(exitPath)
            val guardId = legs.ensureGuard(routes.physicalSurface)
            val spec = LegSpec(legs.nextLegId(), routeId, 0, false, false, false, true, null,
                state.value.cutHeights.clamp(heightMm), state.value.trajectorySpeedPercent.coerceIn(10, 100), returnPercent.coerceIn(10, 80), approach, guardId, -1,
                calibration.saved(), obstacles().map { o -> LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, true) },
                sampling = false, dockExit = true)
            // Exit lost by the gateway (Wi-Fi outage at the start) or refused (route forgotten): sent again, the exit
            // resumes from the real pose in the base corridor ([DockExit]).
            var attempt=0
            while (true) {
                attempt++
                try {
                    val leg = if (attempt==1) spec else spec.copy(legId=legs.nextLegId(), routeId=legs.ensureRoute(exitPath), guardId=legs.ensureGuard(routes.physicalSurface))
                    host.routes.runRemoteLeg(link, leg, exitPath, obstacles = obstacles, planned = { state.value.obstacles.map { it.id }.toSet() }, originOf = { -1 }, navProgress = navProgress)
                    break
                } catch (retry: IllegalStateException) {
                    if (attempt>=3 || (retry !is RouteLegLost && retry !is RemoteLegs.RefusedLeg)) throw retry
                    event("route_dock_exit_retry",tr("Base exit sent again to the gateway: ${retry.message}", "Sortie de base renvoyée à la passerelle : ${retry.message}"),mapOf("attempt" to attempt))
                    delay(1000)
                }
            }
            return DockExitResult(true, null)
        }
        val exit=DockExit(base, approach)
        val motionProgress=MotionProgressGuard()
        var overlapLogged=false
        while (true) {
            val t=live()
            val now=SystemClock.elapsedRealtime()
            val position=Point(requireNotNull(t.x),requireNotNull(t.y))
            onPosition(position)
            val reported=requireNotNull(t.heading).also { check(it.isFinite()) }
            val step=exit.step(position,reported)
            if (step.done) break
            check(now-began<60000) { tr("Station exit timed out: link point not reached", "Sortie de station expirée : point de liaison non atteint") }
            check(!motionProgress.blocked(position,positionPrevious,now,step.linear)) { tr("Station exit blocked for 3 s", "Sortie de station bloquée depuis 3 s") }
            val rad=Math.toRadians(step.heading)
            val distance=minOf(0.10,position.distance(approach))
            val next=RobotPose(Point(position.x+distance*cos(rad),position.y+distance*sin(rad)),step.heading)
            if (routes.motionCollision(RobotPose(position,step.heading),next)!=null) {
                // Chassis already overlapping the free surface on its station: the remaining straight exit must only
                // move it away and end inside the free surface (same rule as the gateway, [fr.luba.poc.autonomy.LegRunner]).
                val overlapping=!routes.footprintSafe(RobotPose(position,step.heading))
                check(overlapping && routes.leavesStraight(position,approach,step.heading)) { DockExit.envelopeRefusal(position,base,overlapping) }
                if (!overlapLogged) {
                    overlapLogged=true
                    event("route_dock_exit_overlap",tr("Chassis overlapping the free surface on the base: straight exit allowed while it only moves away",
                        "Châssis débordant de la surface libre sur la base : sortie tout droit admise tant qu'elle ne fait que s'en éloigner"),
                        mapOf("x" to position.x,"y" to position.y,"heading" to step.heading,"baseDistanceM" to position.distance(base)))
                }
            }
            check(obstacles().none { obstacle -> Geometry.distance(Point(obstacle.x,obstacle.y),position,next.position) < obstacle.exclusionRadiusM+RobotEnvelope.LUBA2.turnClearanceM }) { tr("Obstacle on the station exit", "Obstacle sur la sortie de station") }
            host.send(commands.movement(step.linear,0),stopLabel)
            delay(200)
        }
        host.purgeQueuedCommands(); host.send(commands.stop(),"${stopLabel}_STOP")
        val deadline=SystemClock.elapsedRealtime()+5000
        while (live().chargeState!=0) {
            check(SystemClock.elapsedRealtime()<deadline) { tr("Station exit not confirmed", "Sortie de station non confirmée") }
            delay(200)
        }
        return DockExitResult(false, exit.headingJump)
    }
}
