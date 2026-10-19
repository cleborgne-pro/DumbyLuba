package fr.luba.poc.gateway

import fr.luba.poc.autonomy.GatewayAutonomy
import fr.luba.poc.autonomy.LegContact
import fr.luba.poc.autonomy.LegEnv
import fr.luba.poc.autonomy.LegHooks
import fr.luba.poc.autonomy.LegHost
import fr.luba.poc.autonomy.PilotSink
import fr.luba.poc.autonomy.RobotObserver
import fr.luba.poc.autonomy.RobotView
import fr.luba.poc.ble.MotionSetpoints
import fr.luba.poc.ble.RobotLink
import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Blufi
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.protocol.Commands
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Event handed to the pilot: safety stop written by the gateway while it was away (log `gateway_emergency_stop`). */
const val EMERGENCY_STOP_EVENT = "emergency_stop"

/** Pilot-side reading of the gateway stop report. */
object GatewayStopReport {
    /**
     * True if every stop frame was confirmed by GATT ("STOP écrit par la passerelle …" on the wire). Also accepts the
     * English event prefix and the English display ([GatewayWireText.display]) of the report.
     */
    fun confirmed(text: String) = text.removePrefix("Passerelle : ").removePrefix("Gateway: ")
        .let { it.startsWith(STOP_WRITTEN) || it.startsWith(STOP_WRITTEN_EN) }
    /** Wire text (French, compared by the pilot): unchanged. */
    const val STOP_WRITTEN = "STOP écrit par la passerelle"
    private const val STOP_WRITTEN_EN = "STOP written by the gateway"
}

/** Pilot phone plugged into the gateway robot link. Non-blocking callbacks. */
interface GatewayPilot : PilotSink {
    /** Autonomous tracking frame (progress, surveys, outcome) to pass to the pilot. */
    override fun frame(type: Int, payload: ByteArray) {}
    fun discovering()
    fun ready(mtu: Int)
    fun lost(code: Int, reason: String)
    fun failure(reason: String)
    /** [gattMs]: GATT write duration on the gateway side (Bluetooth leg only), 0 if unknown. */
    fun written(code: Int, gattMs: Int = 0)
    fun rssi(rssi: Int, code: Int)
    fun notification(bytes: ByteArray)
    fun event(kind: String, text: String)
}

 data class GatewayRobotState(val address: String? = null, val name: String? = null, val connecting: Boolean = false,
    val ready: Boolean = false, val mtu: Int = 23, val pilot: Boolean = false) {
    val label: String get() = when {
        address == null -> tr("No robot: connect it from the Bluetooth connection page of this phone",
            "Aucun robot : le connecter depuis la page Connexion Bluetooth de ce téléphone")
        ready && pilot -> tr("${name ?: address} connected over Bluetooth · controlled from the other phone",
            "${name ?: address} connecté en Bluetooth · piloté depuis l'autre téléphone")
        ready -> tr("${name ?: address} connected over Bluetooth · waiting for the pilot phone",
            "${name ?: address} connecté en Bluetooth · en attente du téléphone de pilotage")
        connecting -> tr("Bluetooth connection to ${name ?: address}…", "Connexion Bluetooth à ${name ?: address}…")
        else -> tr("${name ?: address} off or out of range: automatic retry", "${name ?: address} éteint ou hors de portée : nouvel essai automatique")
    }
}

/**
 * Robot Bluetooth link held by the gateway, independent of the control sessions.
 *
 * - Connection requested on the gateway (or by the pilot) then maintained: automatic reconnection while the robot is
 *   kept, periodic sync when no pilot is plugged in.
 * - The gateway alone numbers the BluFi frames (sequence byte rewritten): its own frames (keepalive, safety stop) and
 *   those of successive pilots form a single sequence.
 * - One GATT operation at a time; the answer goes back to its sender.
 * - A pilot that arrives only receives notifications from the start of a message.
 *
 * Reasons and outcomes sent to the pilot (refusals, losses, stop reports, docking results) are wire texts and stay in
 * French; the pilot translates them at display ([GatewayWireText]). Texts for this phone's own screen use [tr].
 */
class GatewayRobot(
    private val links: GatewayLinkFactory,
    private val report: (String) -> Unit,
    private val keepaliveMs: Long = 5000,
    private val ackTimeoutMs: Long = 1500,
    private val retryMs: Long = 10_000,
    private val connectTimeoutMs: Long = 20_000,
    private val onState: (GatewayRobotState) -> Unit = {},
    /** Robot kept (address, name): to remember so as to reconnect after a restart, even when requested by the pilot. */
    private val onRemember: (address: String, name: String?) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Failure of the phone mounted on the robot that forces an autonomous tracking stop (critical overheating…). */
    private val hostCheck: () -> String? = { null },
    /** Reason to forbid the blades (battery of the phone mounted on the robot too low); blades-OFF moves allowed. */
    private val cuttingCheck: () -> String? = { null },
    /** Acceleration of the phone mounted on the robot (fresh reading), attached to signal surveys for slope and terrain. */
    private val tilt: () -> fr.luba.poc.map.PhoneGravity? = { null },
    private val orphanLimitMs: Long = GatewayAutonomy.ORPHAN_LIMIT_MS,
) {
    /** Last safety stop written by the gateway, handed to the next pilot. */
    private class StopRecord(val at: Long, val outcome: String)
    private sealed class Op(val owner: GatewayPilot?, val done: LinkedBlockingQueue<Int>?) {
        /** [label], [batch] and [last]: autonomous tracking writes (one message = several frames of the same batch). */
        class Write(val frame: ByteArray, owner: GatewayPilot?, done: LinkedBlockingQueue<Int>? = null,
            val label: String? = null, val batch: Int = 0, val last: Boolean = true,
            val setpoint: MotionSetpoints.Setpoint? = null) : Op(owner, done)
        class Rssi(owner: GatewayPilot?) : Op(owner, null)
    }

    private val lock = Object()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-robot").apply { isDaemon = true } }
    private var address: String? = null
    private var name: String? = null
    private var link: RobotLink? = null
    private var generation = 0
    private var ready = false
    private var mtu = 23
    private var connectingSince = 0L
    private var blufi = Blufi()
    private var safety = GatewaySafety()
    private val commands = Commands()
    private val queue = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var inFlightSince = 0L
    private var sent: ByteArray? = null
    private var pilot: GatewayPilot? = null
    private var pilotSynced = false
    private var lastStop: StopRecord? = null
    @Volatile private var closed = false
    private var batchCounter = 0
    @Volatile private var ownWritten: ((String, Long) -> Unit)? = null
    /** GATT code of the first tracking write refused by the robot, or null. */
    @Volatile private var ownWriteFailedCode: Int? = null
    private fun monotonicMs() = System.nanoTime() / 1_000_000
    private fun newObserver() = RobotObserver(::monotonicMs, clock, ::urgentStop)
    @Volatile private var observer: RobotObserver = newObserver()
    /** Gateway fallen off the robot, from its own Bluetooth signal to the robot (read during route trackings). Under lock. */
    private val detachment = fr.luba.poc.autonomy.GatewayDetachment()
    /** Last own RSSI read requested ([monotonicMs]). */
    private var ownRssiAt = 0L

    /** Autonomous tracking: route prepared by the pilot, run here, without depending on Wi-Fi during tracking. */
    val autonomy = GatewayAutonomy(LegHostImpl(), report, orphanLimitMs = orphanLimitMs, wall = clock)

    init {
        timer.scheduleWithFixedDelay({ runCatching { tick() } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    val state: GatewayRobotState get() = synchronized(lock) { snapshot() }
    private fun snapshot() = GatewayRobotState(address, name, link != null && !ready, ready, mtu, pilot != null)
    private fun publish() { val s = snapshot(); onState(s) }

    /** Keeps [address] and connects to it; the link is then maintained. */
    fun connect(address: String, name: String? = null) {
        synchronized(lock) {
            if (this.address != null && this.address != address) { closeLink("autre robot choisi"); this.name = null; detachment.reset() }
            val changed = this.address != address || (name != null && name != this.name)
            this.address = address; if (name != null) this.name = name
            if (changed) runCatching { onRemember(address, this.name) }
            if (link == null) open()
            publish()
        }
    }

    /** Releases the robot (other phones, Mammotion app): no more automatic reconnection. Refusal (wire text) or null. */
    fun release(): String? = synchronized(lock) {
        if (pilot != null) return "Un téléphone de pilotage est branché : l'arrêter d'abord"
        closeLink("robot libéré"); address = null; name = null; publish(); null
    }

    /**
     * Connected robot (to answer a search while it no longer advertises). A connection only attempted (robot off or
     * out of range) does not count: the Bluetooth search then tells whether the robot is visible.
     */
    fun connected(): Triple<String, String, Int>? = synchronized(lock) {
        val a = address ?: return null
        if (link == null || !ready) null else Triple(name ?: "Luba", a, 0)
    }

    /**
     * Stops the gateway (button, notification, role change). Pilot plugged in: the safety stop is written first (blades
     * OFF if needed, zero speed, confirmed frames), then the pilot is told the gateway is stopping, before Bluetooth is
     * closed. Returns the stop outcome (wire text), or null without a pilot.
     */
    fun shutdown(): String? {
        autonomy.shutdown()
        val current = synchronized(lock) { pilot }
        val outcome = current?.let { p ->
            val result = runCatching { detach(p, emergency = true) }.getOrElse { "arrêt non transmis (${it.javaClass.simpleName})" }
            p.lost(GatewayLink.LOST_STOPPED, "passerelle arrêtée sur le téléphone posé sur le robot ; ${result ?: "aucun arrêt nécessaire"}")
            result
        }
        closed = true
        synchronized(lock) { closeLink("passerelle arrêtée") }
        timer.shutdownNow()
        return outcome
    }

    /** Plugs in the pilot; null if accepted, otherwise the refusal (wire text). */
    fun attach(newPilot: GatewayPilot, requested: String, requestedName: String? = null): String? {
        val refused = attachLocked(newPilot, requested, requestedName)
        // Outside the lock: autonomous tracking immediately hands its state to the pilot.
        if (refused == null) autonomy.pilotAttached(newPilot)
        return refused
    }

    private fun attachLocked(newPilot: GatewayPilot, requested: String, requestedName: String?): String? = synchronized(lock) {
        if (pilot != null) return "Passerelle occupée par un autre pilote"
        if (address != null && address != requested && link != null) return "La passerelle tient déjà un autre robot ($name)"
        pilot = newPilot; pilotSynced = false
        if (address != requested || link == null || (requestedName != null && requestedName != name)) connect(requested, requestedName)
        // Safety stop written while the pilot was away: tell it (it reported it as "not guaranteed").
        lastStop?.let { stop ->
            lastStop = null
            if (clock() - stop.at in 0..STOP_REPORT_MS) newPilot.event(EMERGENCY_STOP_EVENT, "${stop.outcome} (${time(stop.at)})")
        }
        if (ready) newPilot.ready(mtu) else if (link != null) newPilot.discovering()
        publish()
        null
    }

    /**
     * Unplugs the pilot. [emergency]: pilot lost, stop written by the gateway (blades OFF if needed, zero speed), each
     * frame confirmed. Returns the stop outcome, or null.
     */
    fun detach(old: GatewayPilot, emergency: Boolean): String? = detachWithResult(old, emergency).outcome

    fun detachWithResult(old: GatewayPilot, emergency: Boolean): Detached {
        // Pilot lost during autonomous tracking (Wi-Fi down): tracking goes on, no safety stop is written.
        // Intentional departure: tracking is interrupted (the robot is stopped by the tracking itself).
        val owned = synchronized(lock) { pilot === old }
        val continues = owned && autonomy.pilotDetached(intentional = !emergency)
        val outcome = release(old, emergency && !continues)
        if (emergency && !continues && outcome != null) synchronized(lock) { lastStop = StopRecord(clock(), outcome) }
        return Detached(outcome, continues)
    }

    /** Pilot unplugged: [outcome] of the safety stop (see [detach]) and whether autonomous tracking went on without it (no stop written). */
    class Detached(val outcome: String?, val missionContinued: Boolean)

    private fun release(old: GatewayPilot, emergency: Boolean): String? {
        val gen = synchronized(lock) {
            if (pilot !== old) return null
            pilot = null
            queue.removeAll { it.owner === old }
            publish()
            if (!emergency) return null
            if (!ready) return "robot non connecté : aucun arrêt à écrire"
            generation
        }
        return emergencyWrite(gen)
    }

    /** Safety stop written by the gateway: blades OFF if they may spin, then zero speed, each frame confirmed. Wire text. */
    private fun emergencyWrite(gen: Int): String {
        // Let the current write finish: it sets the sequence and the blade state.
        val deadline = System.currentTimeMillis() + ackTimeoutMs
        while (synchronized(lock) { inFlight != null && generation == gen } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val pending = synchronized(lock) {
            if (generation != gen || !ready) return "liaison robot perdue : arrêt non transmis"
            if (inFlight != null) { closeLink("écriture bloquée"); return "écriture GATT bloquée : liaison fermée, arrêt non garanti" }
            val payloads = safety.emergencyPayloads()
            if (payloads == null) {
                closeLink("message fragmenté interrompu")
                return "message fragmenté en cours : arrêt non injectable, liaison fermée (arrêt non garanti)"
            }
            // Tracking writes still pending (setpoint, keepalive blades ON…): removed, they would go after the stop.
            purgeOwnLocked()
            val ops = payloads.flatMap { blufi.fragment(it, mtu) }.map { frame -> Op.Write(frame, null, LinkedBlockingQueue()) }
            queue.addAll(0, ops)
            pump()
            ops.map { it.done!! }
        }
        for (done in pending) {
            val code = done.poll(ackTimeoutMs * 2, TimeUnit.MILLISECONDS)
            if (code != RobotLink.GATT_SUCCESS) return "STOP non confirmé (${code ?: "délai dépassé"})"
        }
        return "${GatewayStopReport.STOP_WRITTEN} (${pending.size} trame(s)), liaison Bluetooth conservée"
    }

    /** Safety stop at the end of autonomous tracking (or on its interruption). */
    private fun ownEmergencyStop(): String = try {
        val gen = synchronized(lock) { if (!ready) return "robot non connecté : aucun arrêt à écrire"; generation }
        emergencyWrite(gen)
    } finally { observer.active = false }

    /** Writes [payloads] at the end of the queue and waits for their GATT confirmation; failure reason, or null. */
    private fun writeConfirmed(payloads: List<ByteArray>): String? {
        val pending = synchronized(lock) {
            if (!ready || link == null) return "robot non connecté"
            if (safety.midMessage) return "message BluFi en cours d'écriture"
            val ops = payloads.flatMap { blufi.fragment(it, mtu) }.map { frame -> Op.Write(frame, null, LinkedBlockingQueue()) }
            queue.addAll(ops); pump()
            ops.map { it.done!! }
        }
        for (done in pending) {
            val code = done.poll(ackTimeoutMs * 2, TimeUnit.MILLISECONDS)
            if (code != RobotLink.GATT_SUCCESS) return "écriture non confirmée (${code ?: "délai dépassé"})"
        }
        return null
    }

    /**
     * Docking without a pilot ([fr.luba.poc.autonomy.OrphanDock]): same sequence as the pilot's docking. Blades OFF and exit
     * from manual mode (the official return is ignored in state 20), zero speed, then the official Mammotion return; the
     * firmware then docks by itself. No motion setpoint is written by the gateway. The result is a wire text (French).
     */
    private fun dockAlone(approach: fr.luba.poc.map.Point): String {
        fr.luba.poc.autonomy.OrphanDock.refusal(observer.view, monotonicMs(), approach)?.let { return "accostage non demandé : $it" }
        val commands = Commands()
        val height = (observer.view.telemetry.bladeHeightMm ?: 60).coerceIn(25, 70)
        // Reports renewed: the position must stay fresh while leaving manual mode.
        writeConfirmed(listOf(commands.reports(true, periodMs = 200), commands.manualMow(0, 0, height)))?.let { return "accostage non demandé : lames OFF $it" }
        val deadline = monotonicMs() + 4000
        while (observer.view.telemetry.robotState == 20 && monotonicMs() < deadline) Thread.sleep(200)
        if (observer.view.telemetry.robotState == 20) return "accostage non demandé : mode manuel non quitté"
        fr.luba.poc.autonomy.OrphanDock.refusal(observer.view, monotonicMs(), approach)?.let { return "accostage non demandé : $it" }
        writeConfirmed(listOf(commands.stop(), commands.returnToDock()))?.let { return "accostage non confirmé : $it" }
        return "${fr.luba.poc.autonomy.OrphanDock.RESULT_PREFIX} (pilote absent), à %.0f cm du point d'approche".format(
            observer.view.telemetry.let { t -> fr.luba.poc.map.Point(t.x ?: approach.x, t.y ?: approach.y).distance(approach) * 100 })
    }

    fun write(from: GatewayPilot, frame: ByteArray) {
        // A pilot writing during autonomous tracking takes control back: tracking stops (robot stopped) first.
        if (autonomy.running) autonomy.abort(0, "Écriture du pilote pendant le suivi autonome")
        synchronized(lock) {
            if (pilot !== from || !ready) { from.written(-1); return@synchronized }
            queue.addLast(Op.Write(frame, from)); pump()
        }
    }

    fun readRssi(from: GatewayPilot) = synchronized(lock) {
        if (pilot !== from || !ready) { from.rssi(0, -1); return@synchronized }
        queue.addLast(Op.Rssi(from)); pump()
    }

    // --- Autonomous tracking ---

    private fun currentBatch(): Int = (inFlight as? Op.Write)?.batch ?: 0

    /** Removes the tracking commands still pending, except the rest of the message being written. Under lock. */
    private fun purgeOwnLocked() {
        val keep = currentBatch()
        queue.removeAll { it is Op.Write && it.owner == null && it.done == null && !(keep != 0 && it.batch == keep) }
    }

    /** Writes a tracking command: contiguous frames of one batch, the latest motion setpoint replaces the previous ones. */
    private fun submitOwn(payload: ByteArray, label: String) = synchronized(lock) {
        if (!ready || link == null) return@synchronized
        val setpoint = MotionSetpoints.of(payload)
        setpoint?.let { observer.commanded(it.linear) }
        val keep = currentBatch()
        MotionSetpoints.coalesce(queue, setpoint, { (it as? Op.Write)?.setpoint }, { (it as? Op.Write)?.batch == keep && keep != 0 })
        val batch = ++batchCounter
        val frames = blufi.fragment(payload, mtu)
        frames.forEachIndexed { index, frame -> queue.addLast(Op.Write(frame, null, null, label, batch, index == frames.lastIndex, setpoint)) }
        pump()
    }

    /**
     * Immediate stop requested by the robot observer (bumper contact, lift, error): called in the notification callback,
     * under lock. Contact to bypass: zero speed only; otherwise blades OFF if needed then zero speed.
     */
    private fun urgentStop(reason: String, contact: LegContact?) {
        synchronized(lock) {
            if (!ready || link == null) return
            purgeOwnLocked()
            val payloads = if (contact != null) listOf(Commands().stop()) else (safety.emergencyPayloads() ?: listOf(Commands().stop()))
            val keep = queue.count { it is Op.Write && it.batch != 0 && it.batch == currentBatch() }
            queue.addAll(keep, payloads.flatMap { blufi.fragment(it, mtu) }.map { Op.Write(it, null) })
            pump()
        }
        report(tr("Immediate stop of autonomous tracking: ${GatewayWireText.display(reason)}", "Arrêt immédiat du suivi autonome : $reason"))
    }

    private inner class LegHostImpl : LegHost {
        override fun env(hooks: LegHooks): LegEnv {
            observer.clearFailure(); observer.active = true; ownWriteFailedCode = null
            synchronized(lock) { detachment.rearm() }
            return GatewayLegEnv(hooks)
        }
        override fun emergencyStop(): String = ownEmergencyStop()
        override fun nativeDock(approach: fr.luba.poc.map.Point): String = dockAlone(approach)
        override fun refusal(): String? = synchronized(lock) {
            when {
                link == null || !ready -> "Robot non connecté en Bluetooth sur la passerelle"
                // Read-only mode (docs/features/supported-models.md): a known robot name that is not validated never
                // gets an autonomous leg. An unknown name is left to the pilot, which checks the robot it selected.
                name != null && !fr.luba.poc.protocol.ModelSupport.motionValidated(name) -> "Modèle de robot en lecture seule : suivi autonome refusé"
                safety.midMessage -> "Message BluFi en cours d'écriture : réessayer"
                // Same preconditions as starting a route on the pilot: fresh robot state and position (≤ 2 s).
                observer.view.let { v -> v.devicePrevious == 0L || monotonicMs() - v.devicePrevious !in 0..2000 } ->
                    "État du robot absent ou périmé sur la passerelle"
                observer.view.let { v -> v.positionPrevious == 0L || monotonicMs() - v.positionPrevious !in 0..2000 } ->
                    "Position du robot absente ou périmée sur la passerelle"
                else -> null
            }
        }
        override fun hostFailure(): String? = hostCheck()
        override fun cuttingFailure(): String? = cuttingCheck()
    }

    /** The robot as seen by autonomous tracking: observer telemetry, writes through the gateway GATT queue. */
    private inner class GatewayLegEnv(hooks: LegHooks) : LegEnv, LegHooks by hooks {
        override fun now() = monotonicMs()
        override fun wall() = clock()
        override fun view(): RobotView = observer.view
        override fun linkHealthy(): Boolean = linkProblem() == null
        override fun linkProblem(): String? {
            val problem = synchronized(lock) {
                linkProblem(ready && link != null, ownWriteFailedCode, queue.size, inFlight?.let { System.currentTimeMillis() - inFlightSince }, ackTimeoutMs * 2)
            } ?: return null
            // Link lost because the gateway fell off: the reason says so (with where to find it).
            return detached() ?: problem
        }
        override fun hostFailure(): String? = detached() ?: observer.failure()

        private var announced = false
        /** Gateway fallen off the robot: stop reason, announced once to the pilot with the position (outside the robot lock). */
        private fun detached(): String? {
            val verdict = synchronized(lock) { detachment.verdict } ?: return null
            if (!announced) {
                announced = true
                runCatching { event(fr.luba.poc.autonomy.GatewayDetachment.EVENT, verdict.text, verdict.fields()) }
            }
            return verdict.text
        }
        override fun contact(): LegContact? = observer.contact()
        override fun armBypass(armed: Boolean) { observer.bypassArmed = armed }
        override fun send(payload: ByteArray, label: String) = submitOwn(payload, label)
        override fun purge() = synchronized(lock) { purgeOwnLocked() }
        override fun onWritten(listener: ((label: String, at: Long) -> Unit)?) { ownWritten = listener }
        override suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
        override fun confirmedHeading(heading: Double?) { observer.confirmedHeading = heading }
        override fun tilt(): fr.luba.poc.map.PhoneGravity? = this@GatewayRobot.tilt()
    }

    // --- Internal (under lock) ---

    private fun open() {
        val target = address ?: return
        val gen = ++generation
        ready = false; mtu = 23; blufi = Blufi(); queue.clear(); inFlight = null
        observer = newObserver().also { fresh -> safety = GatewaySafety().also { it.onMessage = fresh::accept } }
        ownWriteFailedCode = null
        connectingSince = System.currentTimeMillis()
        report(tr("Bluetooth connection to the robot ${name ?: target}…", "Connexion Bluetooth au robot ${name ?: target}…"))
        val opened = runCatching { links.open(target, Listener(gen)) }.getOrNull()
        if (gen != generation) { opened?.let { runCatching { it.close() } }; return }
        if (opened == null) {
            report(tr("Bluetooth unavailable on the gateway", "Bluetooth indisponible sur la passerelle"))
            pilot?.failure("Bluetooth indisponible sur la passerelle"); return
        }
        link = opened
    }

    private fun closeLink(reason: String) {
        val old = link ?: return
        generation++; link = null; ready = false
        failPending()
        runCatching { old.close() }
        report(tr("Robot link closed: ${GatewayWireText.display(reason)}", "Liaison robot fermée : $reason"))
        publish()
    }

    /** Pending operations that can no longer get an answer. */
    private fun failPending() {
        (listOfNotNull(inFlight) + queue).forEach { op ->
            op.done?.offer(-1)
            when (op) { is Op.Write -> op.owner?.written(-1); is Op.Rssi -> op.owner?.rssi(0, -1) }
        }
        queue.clear(); inFlight = null
    }

    private fun pump() {
        if (inFlight != null || !ready) return
        val l = link ?: return
        val op = queue.removeFirstOrNull() ?: return
        inFlight = op; inFlightSince = System.currentTimeMillis()
        val accepted = runCatching {
            when (op) {
                is Op.Write -> { val frame = blufi.forWrite(op.frame); sent = frame; l.write(frame) }
                is Op.Rssi -> l.readRssi()
            }
        }.getOrDefault(false)
        if (!accepted) { inFlight = null; complete(op, -1); pump() }
    }

    private fun complete(op: Op, code: Int, rssi: Int = 0) {
        op.done?.offer(code)
        when (op) {
            is Op.Write -> {
                op.owner?.written(code, (System.currentTimeMillis() - inFlightSince).toInt().coerceAtLeast(0))
                if (op.label != null) {
                    if (code != RobotLink.GATT_SUCCESS) { if (ownWriteFailedCode == null) ownWriteFailedCode = code }
                    else if (op.last) ownWritten?.invoke(op.label, monotonicMs())
                }
            }
            is Op.Rssi -> if (op.owner != null) op.owner.rssi(rssi, code) else ownRssi(rssi, code)
        }
    }

    /** Robot position less than 2 s old, or null. */
    private fun freshPosition(): fr.luba.poc.map.Point? {
        val v = observer.view
        if (v.positionPrevious == 0L || monotonicMs() - v.positionPrevious !in 0..2000) return null
        val x = v.telemetry.x?.takeIf { it.isFinite() } ?: return null
        val y = v.telemetry.y?.takeIf { it.isFinite() } ?: return null
        return fr.luba.poc.map.Point(x, y)
    }

    /** Own RSSI reading during a route tracking (under lock): a gateway fallen off the robot stops it at once. */
    private fun ownRssi(rssi: Int, code: Int) {
        if (code != RobotLink.GATT_SUCCESS || !observer.active || detachment.verdict != null) return
        val verdict = detachment.reading(monotonicMs(), rssi, freshPosition()) ?: return
        // The robot would come back over the phone with its blades: stopped now, the route tracking ends on it at the next tick.
        urgentStop(verdict.text, null)
    }

    private fun tick() = synchronized(lock) {
        if (closed) return@synchronized
        val now = System.currentTimeMillis()
        if (inFlight != null && now - inFlightSince > ackTimeoutMs * 2) { drop("opération GATT sans réponse", now); return@synchronized }
        if (link != null && !ready && now - connectingSince > connectTimeoutMs) { drop("connexion sans réponse", now); return@synchronized }
        if (link == null && address != null && now - connectingSince > retryMs) open()
        // Route tracking: own Bluetooth signal read every second, to notice a gateway fallen off the robot.
        val mono = monotonicMs()
        if (ready && link != null && observer.active && mono - ownRssiAt >= fr.luba.poc.autonomy.GatewayDetachment.READ_PERIOD_MS &&
            (listOfNotNull(inFlight) + queue).none { it is Op.Rssi && it.owner == null }) {
            ownRssiAt = mono
            queue.addLast(Op.Rssi(null)); pump()
        }
        if (ready && pilot == null && queue.isEmpty() && inFlight == null && now - lastKeepalive >= keepaliveMs) {
            lastKeepalive = now
            blufi.fragment(commands.sync(), mtu).forEach { queue.addLast(Op.Write(it, null)) }
            pump()
        }
    }
    private var lastKeepalive = 0L

    /**
     * Link closed by the gateway (timeout): new attempt after [retryMs], not at the next tick; the pilot is told as for a
     * Bluetooth loss (code 8, connection timeout).
     */
    private fun drop(reason: String, now: Long) {
        val p = pilot
        closeLink(reason)
        connectingSince = now
        p?.lost(GATT_TIMEOUT, reason)
    }

    private inner class Listener(private val gen: Int) : RobotLink.Listener {
        private inline fun live(block: () -> Unit) = synchronized(lock) { if (gen == generation) block() }
        override fun onDiscovering(link: RobotLink) = live { pilot?.discovering() }
        override fun onReady(link: RobotLink, mtu: Int) = live {
            ready = true; this@GatewayRobot.mtu = mtu; lastKeepalive = System.currentTimeMillis()
            report(tr("Robot ${name ?: address} connected over Bluetooth (MTU $mtu)", "Robot ${name ?: address} connecté en Bluetooth (MTU $mtu)"))
            pilot?.ready(mtu)
            publish(); pump()
        }
        override fun onLost(link: RobotLink, code: Int, reason: String) = live {
            // Link lost during a route while the signal was already falling and the robot moving away: gateway fallen off.
            if (observer.active) detachment.linkLost(freshPosition())?.let { report(tr("Gateway lost: ${it.text}", "Passerelle perdue : ${it.text}")) }
            report(tr("Robot Bluetooth link lost ($code): new attempt in ${retryMs / 1000} s", "Liaison Bluetooth robot perdue ($code) : nouvel essai dans ${retryMs / 1000} s"))
            val p = pilot
            closeLink(reason)
            connectingSince = System.currentTimeMillis()
            p?.lost(code, reason)
        }
        override fun onFailure(link: RobotLink, reason: String) = live {
            report(tr("Bluetooth failure: $reason", "Échec Bluetooth : $reason"))
            val p = pilot
            closeLink(reason)
            connectingSince = System.currentTimeMillis()
            p?.failure(reason)
        }
        override fun onWritten(link: RobotLink, code: Int) = live {
            val op = inFlight as? Op.Write ?: return@live
            inFlight = null
            if (code == RobotLink.GATT_SUCCESS) sent?.let(safety::written)
            complete(op, code); pump()
        }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) = live {
            val op = inFlight as? Op.Rssi ?: return@live
            inFlight = null; complete(op, code, rssi); pump()
        }
        override fun onNotification(link: RobotLink, bytes: ByteArray) = live {
            val boundary = !safety.incomingAssembling
            safety.notified(bytes)
            val p = pilot ?: return@live
            if (!pilotSynced && boundary) pilotSynced = true
            if (pilotSynced) p.notification(bytes)
        }
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) = live { pilot?.event(kind, text) }
    }

    companion object {
        /**
         * Reason why the robot link cannot be used for autonomous tracking, or null if healthy: robot disconnected, write
         * refused ([failedCode]), queue of more than 4 frames ([queued]), write in flight for [inFlightMs] ≥ [stallMs].
         * On 2026-10-16, the single logged "BLE interrupted or queue saturated" did not say which of the four.
         * Autonomous tracking stop reason sent to the pilot, in the gateway phone's language (the pilot matches "BLE
         * interrupted" / "BLE interrompu"); French texts from older gateways are translated at display by [GatewayWireText].
         */
        fun linkProblem(connected: Boolean, failedCode: Int?, queued: Int, inFlightMs: Long?, stallMs: Long): String? = when {
            !connected -> tr("BLE interrupted: robot Bluetooth link closed", "BLE interrompu : liaison Bluetooth du robot fermée")
            failedCode != null -> tr("BLE interrupted: write refused by the robot (code $failedCode)", "BLE interrompu : écriture refusée par le robot (code $failedCode)")
            inFlightMs != null && inFlightMs >= stallMs ->
                tr("BLE interrupted: write unconfirmed by the robot for %.1f s", "BLE interrompu : écriture sans confirmation du robot depuis %.1f s")
                    .format(if (fr.luba.poc.i18n.I18n.french) java.util.Locale.FRANCE else java.util.Locale.US, inFlightMs / 1000.0)
            queued > 4 -> tr("BLE interrupted: write queue saturated ($queued frames pending)", "BLE interrompu : file d'écriture saturée ($queued trames en attente)")
            else -> null
        }
        /** Bluetooth code of a connection timeout (GATT_CONN_TIMEOUT), sent to the pilot when the gateway gives up. */
        private const val GATT_TIMEOUT = 8
        /** Safety stop handed to a pilot returning within this delay; beyond it, the information is stale. */
        private const val STOP_REPORT_MS = 30 * 60 * 1000L
        private fun time(at: Long): String = java.text.SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(at))
    }
}
