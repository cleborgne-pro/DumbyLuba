package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.diagnostics.LiftGuard
import fr.luba.poc.map.Point
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.RobotErrorCatalog
import fr.luba.poc.protocol.RobotErrorMonitor
import fr.luba.poc.protocol.RobotErrorResponse
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.protocol.TelemetryReducer

/**
 * What the pilot derives from each robot message during a route (RobotBle.receive), redone on the gateway so that the
 * on-board route tracking stays safe without a pilot: merged telemetry, instants of the last state and position
 * reports, error codes (bumper contact, lift, stops), lift detected by sensors and by wheels without progress.
 *
 * Urgent decisions ([onUrgent]) are made as soon as the message arrives, before the next tracking tick: the host
 * stops the robot right away, as the pilot does in its receive callback.
 *
 * The stop reasons become the route outcome sent to the pilot, in the gateway phone's language ("Bumper contact" /
 * "Contact bumper" is matched in both languages by the pilot).
 */
class RobotObserver(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    /** Immediate stop requested by the observer: [contact] set = bumper contact to bypass (STOP only). */
    private val onUrgent: (reason: String, contact: LegContact?) -> Unit,
) {
    private val reducer = TelemetryReducer()
    private val errors = RobotErrorMonitor()
    private val lift = LiftGuard()

    /** Robot state published as a whole (telemetry and instants of the last reports). */
    @Volatile var view = RobotView(Telemetry(), 0L, 0L); private set
    val telemetry: Telemetry get() = view.telemetry
    val devicePrevious: Long get() = view.devicePrevious
    val positionPrevious: Long get() = view.positionPrevious
    /** Route tracking in progress: errors and lift stop the robot. */
    @Volatile var active = false
    /** Bumper contact handled as an obstacle to bypass (otherwise stop). */
    @Volatile var bypassArmed = false
    @Volatile var confirmedHeading: Double? = null
    @Volatile private var pendingContact: LegContact? = null
    @Volatile private var failure: String? = null

    fun contact(): LegContact? = pendingContact
    fun failure(): String? = failure
    fun clearFailure() { failure = null; pendingContact = null }

    /** Speed command written: feeds the wheel-based lift detection. */
    fun commanded(linear: Int) { lift.commanded(linear, elapsed()) }

    fun accept(message: LubaMsg) {
        val now = elapsed()
        errors.accept(message).forEach { error -> handle(error.code, now) }
        val accepted = reducer.accept(message, wall(), now)
        val positioned = accepted && message.sys.toappReportData.locationsCount > 0
        view = RobotView(reducer.value,
            if (accepted && message.sys.toappReportData.hasDev()) now else view.devicePrevious,
            if (positioned) now else view.positionPrevious)
        if (!accepted || !active) return
        val t = reducer.value
        val sensorLift = t.sensorStatus?.let { lift.sensors(it, now) }
        val wheelLift = if (positioned && t.x != null && t.y != null) lift.position(Point(t.x, t.y), now) else null
        (sensorLift ?: wheelLift)?.let { urgent(tr("Lift or blockage detected: $it", "Soulèvement ou blocage détecté : $it")) }
    }

    private fun handle(code: Int, now: Long) {
        val info = RobotErrorCatalog.describe(code)
        if (LiftGuard.liftCode(code)) {
            val reason = tr("Lift reported by the robot: ${LiftGuard.liftLabel(code)} ($code)", "Soulèvement signalé par le robot : ${LiftGuard.liftLabel(code)} ($code)")
            if (active) urgent(reason)
            return
        }
        if (!active) return
        val contact = code in setOf(1014, -1014)
        if (contact) {
            val pose = if (bypassArmed && pendingContact == null) contactPose(code) else null
            when {
                pendingContact != null -> onUrgent(tr("Bumper contact repeated", "Contact bumper réitéré"), pendingContact)   // bounce: stop repeated
                pose != null -> { pendingContact = pose; onUrgent(tr("Bumper contact ($code)", "Contact bumper ($code)"), pose) }
                else -> urgent(tr("Bumper contact ($code): robot stopped, blades OFF", "Contact bumper ($code) : robot arrêté, lames OFF"))
            }
            return
        }
        if (code in setOf(1207, -1207)) {
            urgent(tr("Bumper fault ($code): robot stopped, blades OFF", "Défaut bumper ($code) : robot arrêté, lames OFF")); return
        }
        if (info.response == RobotErrorResponse.STOP) urgent(tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"))
    }

    private fun contactPose(code: Int): LegContact? {
        val t = reducer.value
        val current = view
        if (current.positionPrevious == 0L || elapsed() - current.positionPrevious !in 0..2000) return null
        val heading = (confirmedHeading ?: t.heading)?.takeIf { it.isFinite() } ?: return null
        val x = t.x?.takeIf { it.isFinite() } ?: return null
        val y = t.y?.takeIf { it.isFinite() } ?: return null
        return LegContact(code, x, y, heading, wall())
    }

    private fun urgent(reason: String) {
        if (failure == null) failure = reason
        onUrgent(reason, null)
    }
}
