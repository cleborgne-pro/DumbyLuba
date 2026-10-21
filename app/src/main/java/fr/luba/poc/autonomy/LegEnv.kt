package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.EnvelopeCollision
import fr.luba.poc.map.PhoneGravity
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotPose
import fr.luba.poc.map.SignalSample
import fr.luba.poc.map.TrajectoryEnvelope
import fr.luba.poc.protocol.Telemetry

/** Consistent snapshot of what the host knows about the robot. */
data class RobotView(val telemetry: Telemetry, val devicePrevious: Long, val positionPrevious: Long)

/** Chassis envelope check against a free physical surface received from the pilot. */
class EnvelopeGuard(private val envelope: TrajectoryEnvelope) {
    fun motionCollision(from: RobotPose, to: RobotPose): EnvelopeCollision? = envelope.motionCollision(from, to)
    fun bodySafe(center: Point): Boolean = envelope.bodySafe(center)
    fun poseSafe(pose: RobotPose): Boolean = envelope.poseSafe(pose)
    fun leavesStraight(from: Point, to: Point, heading: Double): Boolean = envelope.leavesStraight(from, to, heading)
}

/** What the route tracking reports to the pilot and what it learns from it during the route. */
interface LegHooks {
    fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap())
    fun progress(progress: LegProgress)
    fun sample(sample: SignalSample)
    /** Known obstacles, updated by the pilot during the route tracking. */
    fun obstacles(): List<LegObstacle>
}

/**
 * What the on-board route tracking expects from its host: the robot (telemetry, command writes), the clock and the
 * report to the pilot. Same contract on the gateway (phone placed on the robot) and in tests (simulated robot).
 */
interface LegEnv : LegHooks {
    /** Monotonic clock in ms (SystemClock.elapsedRealtime). */
    fun now(): Long
    /** Wall clock in ms. */
    fun wall(): Long
    /**
     * Merged robot telemetry and instants ([now]) of its last state report and last position (0 if none), read
     * together. Read BEFORE [now] in a loop iteration: reports arrive on another thread, and a report more recent than
     * the time read would give a negative age.
     */
    fun view(): RobotView
    /** Robot link ready, short write queue, nothing abnormal on the host side. */
    fun linkHealthy(): Boolean
    /**
     * Reason why the link is unusable, for the log and the route outcome (write without response, saturated queue, robot
     * disconnected…), or null if it is healthy. By default, the generic reason [LINK_DOWN].
     */
    fun linkProblem(): String? = if (linkHealthy()) null else LINK_DOWN
    /** Host-specific stop reason (robot error, lift, pilot absent for too long, host battery), or null. */
    fun hostFailure(): String?
    /** Reason to forbid the blades (host battery too low): cutting stops, moving with blades OFF stays allowed; null otherwise. */
    fun cuttingFailure(): String? = null
    /** Bumper contact waiting to be handled (the robot has already been stopped by the host). */
    fun contact(): LegContact?
    /** Whether a bumper contact is handled as an obstacle to bypass (otherwise: stop). */
    fun armBypass(armed: Boolean)
    /** BluFi command to write; [label] identifies the write for the blade gates. */
    fun send(payload: ByteArray, label: String)
    /** Removes pending commands (except the message currently being written). */
    fun purge()
    /** Called when the write [label] is confirmed by GATT ([at]: [now] clock). */
    fun onWritten(listener: ((label: String, at: Long) -> Unit)?)
    suspend fun delay(ms: Long)
    /** Acceleration of the phone placed on the robot (measured less than 2 s ago), for the slope and terrain of surveys; null if absent. */
    fun tilt(): PhoneGravity?
    /** Heading confirmed by the motion (null: not confirmed), used for the pose of a bumper contact. */
    fun confirmedHeading(heading: Double?)

    companion object {
        /**
         * Generic reason for an unusable link. Ends up in the route outcome sent to the pilot; written in
         * the gateway phone's language, the pilot matches it in both languages ("BLE interrupted" / "BLE interrompu").
         */
        val LINK_DOWN: String get() = tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée")
    }
}

/** Obstacle discovered (bumper contact or addition): the route tracking stops, the pilot prepares the bypass. */
class LegEncounter(val index: Int, val contact: LegContact?, val added: List<LegObstacle>, message: String) : RuntimeException(message)
