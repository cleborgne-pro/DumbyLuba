package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.SignalSample
import fr.luba.poc.map.TrajectoryEnvelope
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Channel to the pilot phone; absent (null) during a Wi-Fi outage. */
fun interface PilotSink { fun frame(type: Int, payload: ByteArray) }

/** The robot and the phone carrying it, as seen by the autonomous route tracking. */
interface LegHost {
    /** Environment of a route tracking: real (or simulated) robot; progress, survey samples and obstacles go through [hooks]. */
    fun env(hooks: LegHooks): LegEnv
    /** Safety stop written by the host (blades OFF if they may be spinning, then zero speed), blocking; returns a report. */
    fun emergencyStop(): String
    /** Reason to refuse a new route tracking (no robot link, fragmented message in progress…), or null. */
    fun refusal(): String?
    /** Host-specific failure that requires a stop (battery of the phone placed on the robot too low…), or null. */
    fun hostFailure(): String? = null
    /** Host-specific reason to forbid the blades (battery of the phone placed on the robot too low), or null. */
    fun cuttingFailure(): String? = null
    /**
     * Docking through the official Mammotion return from [approach], pilot absent ([OrphanDock]): blocking, report
     * starting with [OrphanDock.RESULT_PREFIX] if the request was written and confirmed (otherwise a reason for the gateway's
     * own log).
     */
    fun nativeDock(approach: fr.luba.poc.map.Point): String = tr("docking not supported by this host", "accostage non pris en charge par cet hôte")
}

/**
 * Autonomous route tracking on the gateway: receives the route and the envelope surfaces (blobs) from the pilot, runs the
 * route trackings ([LegRunner]) on its own thread, and reports back (progress, signal survey samples, outcome). With no
 * reachable pilot (Wi-Fi down), the tracking carries on; the report is kept and replayed when the pilot comes back.
 *
 * Safety: anything other than "continue" stops the robot (blades OFF, zero speed) before handing back the outcome;
 * a pilot write during a route tracking interrupts it; a pilot that left on purpose interrupts it; a pilot absent for
 * more than [orphanLimitMs] interrupts it.
 *
 * Language: texts are written in the gateway phone's language; the pilot matches the few it compares in both languages.
 * "STOP écrit" is the gateway's own French stop report ([fr.luba.poc.gateway.GatewayStopReport.STOP_WRITTEN]).
 */
class GatewayAutonomy(
    private val host: LegHost,
    private val report: (String) -> Unit = {},
    private val orphanLimitMs: Long = ORPHAN_LIMIT_MS,
    private val wall: () -> Long = System::currentTimeMillis,
    /** Maximum wait for an interrupted route tracking to stop. */
    private val stopWaitMs: Long = 6000,
) {
    private class Upload(val kind: Int, val data: ByteArray) { var received = 0 }
    private class Active(val spec: LegSpec) {
        @Volatile var obstacles: List<LegObstacle> = spec.obstacles
        @Volatile var aborted = false
        @Volatile var abortReason = tr("Stop requested", "Arrêt demandé")
        @Volatile var job: Job? = null
        val samples = ArrayList<SignalSample>()
        @Volatile var progress: LegProgress? = null
        /** Released when the route tracking is finished, robot stopped and slot freed. */
        val done = java.util.concurrent.CountDownLatch(1)
    }
    private class Finished(val result: LegResult, val samples: List<SignalSample>)

    private val lock = Object()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gateway-follower").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2 } }
    private val prepare = Executors.newSingleThreadExecutor { r -> Thread(r, "gateway-prepare").apply { isDaemon = true } }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher())
    private val uploads = HashMap<Int, Upload>()
    private val routes = LinkedHashMap<Int, List<SimEdge>>()
    private val guards = LinkedHashMap<Int, EnvelopeGuard>()
    private var sink: PilotSink? = null
    private var active: Active? = null
    private var finished: Finished? = null
    private var orphanSince = 0L

    /** A route tracking is in progress: the host lets it run (pilot writes interrupt it). */
    val running: Boolean get() = synchronized(lock) { active != null }

    /** Pilot connected: route tracking state (running, outcome to acknowledge) handed over at once. */
    fun pilotAttached(pilot: PilotSink) {
        synchronized(lock) {
            uploads.clear()   // upload interrupted by the previous outage: the pilot starts over
            sink = pilot; orphanSince = 0
            val a = active; val f = finished
            when {
                a != null -> pilot.frame(LegFrames.LEG_STATE, LegFrames.legState(a.spec.legId, LegFrames.STATE_RUNNING, a.samples.size))
                f != null -> pilot.frame(LegFrames.LEG_STATE, LegFrames.legState(f.result.legId, LegFrames.STATE_FINISHED, f.samples.size))
                else -> pilot.frame(LegFrames.LEG_STATE, LegFrames.legState(0, LegFrames.STATE_NONE, 0))
            }
        }
    }

    /**
     * Pilot gone. [intentional]: deliberate close (the route tracking is interrupted). Otherwise (Wi-Fi down) the tracking
     * carries on and the result is true; the host then writes no safety stop.
     */
    fun pilotDetached(intentional: Boolean): Boolean {
        val continues = synchronized(lock) {
            sink = null
            val a = active
            if (a != null && !intentional && !a.aborted) { orphanSince = wall(); true } else false
        }
        if (continues) report(tr("Pilot absent: autonomous route tracking continues (automatic stop after ${orphanLimitMs / 60_000} min without a pilot)",
            "Pilote absent : suivi autonome poursuivi (arrêt automatique après ${orphanLimitMs / 60_000} min sans pilote)"))
        else abort(0, tr("Pilot gone", "Pilote parti"))
        return continues
    }

    /** Interrupts the route tracking (legId 0: the current one): the robot is stopped before returning. */
    fun abort(legId: Int, reason: String = tr("Stop requested by the pilot", "Arrêt demandé par le pilote")) {
        val a = synchronized(lock) { active?.takeIf { legId == 0 || it.spec.legId == legId } } ?: return
        a.abortReason = reason; a.aborted = true
        a.job?.cancel(CancellationException(reason))
        a.done.await(stopWaitMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown() { abort(0, tr("Gateway stopped", "Passerelle arrêtée")); scope.coroutineContext[Job]?.cancel(); executor.shutdownNow(); prepare.shutdownNow() }

    /** Frame from the pilot. Returns false if it is not an autonomous tracking frame. */
    fun onFrame(type: Int, payload: ByteArray): Boolean {
        if (type !in LegFrames.PILOT_TO_GATEWAY) return false
        try {
            when (type) {
                LegFrames.BLOB_BEGIN -> beginBlob(LegFrames.parseBlobBegin(payload))
                LegFrames.BLOB_CHUNK -> chunk(LegFrames.parseBlobChunk(payload))
                LegFrames.BLOB_END -> LegFrames.parseBlobEnd(payload).let { (id, crc) -> endBlob(id, crc) }
                LegFrames.LEG_START -> start(LegSpec.decode(payload))
                LegFrames.LEG_ABORT -> abort(LegFrames.int(payload))
                LegFrames.LEG_OBSTACLES -> LegFrames.parseObstacles(payload).let { (id, list) ->
                    synchronized(lock) { active?.takeIf { it.spec.legId == id }?.obstacles = list } }
                LegFrames.LEG_SYNC -> sync(LegFrames.int(payload, 0), LegFrames.int(payload, 1))
                LegFrames.LEG_ACK -> synchronized(lock) {
                    if (finished?.result?.legId == LegFrames.int(payload)) finished = null }
            }
        } catch (error: Exception) {
            report(tr("Tracking frame $type refused: ${error.message ?: error.javaClass.simpleName}", "Trame de suivi $type refusée : ${error.message ?: error.javaClass.simpleName}"))
            send(LegFrames.LEG_REFUSED, LegFrames.legRefused(0, tr("Invalid frame: ${error.message ?: error.javaClass.simpleName}", "Trame invalide : ${error.message ?: error.javaClass.simpleName}")))
        }
        return true
    }

    private fun send(type: Int, payload: ByteArray) { val target = synchronized(lock) { sink }; target?.frame(type, payload) }

    // --- Blobs ---

    private fun beginBlob(begin: LegFrames.BlobBegin) {
        // Abandoned uploads (pilot gone between BEGIN and END): at most a few uploads in progress, the oldest are dropped.
        synchronized(lock) { while (uploads.size >= MAX_UPLOADS) uploads.remove(uploads.keys.first()) }
        if (begin.size !in 1..LegFrames.MAX_BLOB_BYTES) { send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(begin.id, tr("Size ${begin.size} refused", "Taille ${begin.size} refusée"))); return }
        if (begin.kind != LegBlobs.KIND_ROUTE && begin.kind != LegBlobs.KIND_GUARD) { send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(begin.id, tr("Unknown type ${begin.kind}", "Type ${begin.kind} inconnu"))); return }
        synchronized(lock) { uploads[begin.id] = Upload(begin.kind, ByteArray(begin.size)) }
    }

    private fun chunk(chunk: LegFrames.BlobChunk) {
        val upload = synchronized(lock) { uploads[chunk.id] } ?: return
        if (chunk.offset != upload.received || chunk.offset + chunk.data.size > upload.data.size) {
            synchronized(lock) { uploads.remove(chunk.id) }
            send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(chunk.id, tr("Chunk out of sequence (received ${upload.received}, announced ${chunk.offset})", "Morceau hors séquence (reçu ${upload.received}, annoncé ${chunk.offset})"))); return
        }
        chunk.data.copyInto(upload.data, chunk.offset); upload.received += chunk.data.size
    }

    private fun endBlob(id: Int, crc: Int) {
        val upload = synchronized(lock) { uploads.remove(id) } ?: run { send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(id, tr("Unknown upload", "Envoi inconnu"))); return }
        if (upload.received != upload.data.size || LegFrames.crc(upload.data) != crc) { send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(id, tr("Upload incomplete or corrupted", "Envoi incomplet ou altéré"))); return }
        // Decoding (WKB, surface index) off the session thread: it must not delay frame reading.
        prepare.execute {
            try {
                if (upload.kind == LegBlobs.KIND_ROUTE) {
                    // Decoding outside the lock: the tracking safety loop takes it at every tick.
                    val edges = LegBlobs.decodeRoute(upload.data)
                    synchronized(lock) { routes[id] = edges; while (routes.size > MAX_KEPT) routes.remove(routes.keys.first()) }
                }
                if (upload.kind == LegBlobs.KIND_GUARD) {
                    val guard = EnvelopeGuard(TrajectoryEnvelope(LegBlobs.decodeSurface(upload.data), RobotEnvelope.LUBA2))
                    synchronized(lock) { guards[id] = guard; while (guards.size > MAX_KEPT) guards.remove(guards.keys.first()) }
                }
                send(LegFrames.BLOB_OK, LegFrames.blobOk(id))
            } catch (error: Exception) {
                send(LegFrames.BLOB_REJECTED, LegFrames.blobRejected(id, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    // --- Route tracking ---

    private fun start(spec: LegSpec) {
        // The host (robot link lock) is queried outside our lock: never nested locks in both directions.
        val hostRefusal = host.refusal()
        val leg = Active(spec)
        var refusal: String? = null
        var plan: Triple<List<SimEdge>, EnvelopeGuard?, EnvelopeGuard?>? = null
        // Checks, route resolution and slot taking in a single critical section: nothing can be evicted in between,
        // and "tracking in progress" is only set once everything is resolved.
        synchronized(lock) {
            val path = routes[spec.routeId]
            val guard = if (spec.guardId >= 0) guards[spec.guardId] else null
            val physical = if (spec.physicalGuardId >= 0) guards[spec.physicalGuardId] else null
            refusal = when {
                active != null -> tr("A route tracking is already running", "Un suivi est déjà en cours")
                path == null -> tr("Route ${spec.routeId} not received", "Trajet ${spec.routeId} non reçu")
                spec.guardId >= 0 && guard == null -> tr("Envelope surface ${spec.guardId} not received", "Surface d'enveloppe ${spec.guardId} non reçue")
                spec.physicalGuardId >= 0 && physical == null -> tr("Physical surface ${spec.physicalGuardId} not received", "Surface physique ${spec.physicalGuardId} non reçue")
                spec.from !in path.indices -> tr("Start edge ${spec.from} outside the route", "Arête de départ ${spec.from} hors du trajet")
                else -> hostRefusal
            }
            if (refusal == null) { finished = null; orphanSince = 0; active = leg; plan = Triple(requireNotNull(path), guard, physical) }
        }
        refusal?.let { send(LegFrames.LEG_REFUSED, LegFrames.legRefused(spec.legId, it)); return }
        val (path, guard, physical) = requireNotNull(plan)
        report(tr("Autonomous route tracking ${spec.legId}: ${path.size - spec.from} edge(s) from ${spec.from}", "Suivi autonome ${spec.legId} : ${path.size - spec.from} arête(s) depuis ${spec.from}"))
        // ATOMIC: a stop requested before the tracking thread takes over does not cancel the body, whose end frees the slot.
        leg.job = scope.launch(start = CoroutineStart.ATOMIC) {
            var result: LegResult
            if (leg.aborted) result = LegResult(spec.legId, LegOutcome.ABORTED, spec.from, leg.abortReason)
            else try {
                val env = environment(leg)
                result = LegRunner(env, spec, path, guard, physical).run()
            } catch (cancel: CancellationException) {
                result = LegResult(spec.legId, LegOutcome.ABORTED, leg.progress?.index?.minus(1)?.coerceAtLeast(0) ?: spec.from, cancel.message ?: tr("Stop requested", "Arrêt demandé"))
            } catch (error: Throwable) {
                result = LegResult(spec.legId, LegOutcome.FAILED, leg.progress?.index?.minus(1)?.coerceAtLeast(0) ?: spec.from,
                    tr("Internal tracking error: ${error.message ?: error.javaClass.simpleName}", "Erreur interne du suivi : ${error.message ?: error.javaClass.simpleName}"))
            }
            // Interrupted at the pilot's request: the outcome is "aborted", whichever guard saw the stop first.
            if (leg.aborted && result.outcome != LegOutcome.DONE) result = LegResult(spec.legId, LegOutcome.ABORTED, result.index, leg.abortReason,
                calibration = result.calibration)
            // In every case, robot stopped and blades OFF before handing back the outcome (even if the tracking was just cancelled).
            val stop = withContext(NonCancellable) { runCatching { host.emergencyStop() }.getOrElse { tr("stop not transmitted (${it.javaClass.simpleName})", "arrêt non transmis (${it.javaClass.simpleName})") } }
            val confirmed = stop.startsWith("STOP écrit")
            result = result.copy(stopConfirmed = confirmed, text = if (confirmed) result.text else "${result.text} · $stop".trim(' ', '·').take(LegResult.MAX_TEXT))
            // Return to base finished with no reachable pilot: the gateway docks by itself (tests of 2026-10-14).
            val approach = spec.baseApproach
            if (approach != null && OrphanDock.wanted(spec, path, result.outcome, confirmed, synchronized(lock) { sink != null })) {
                val dock = withContext(NonCancellable) { runCatching { host.nativeDock(approach) }.getOrElse { tr("docking not requested (${it.javaClass.simpleName})", "accostage non demandé (${it.javaClass.simpleName})") } }
                report(tr("Pilot absent at the end of the return: $dock", "Pilote absent en fin de retour : $dock"))
                if (dock.startsWith(OrphanDock.RESULT_PREFIX)) result = result.copy(text = dock.take(LegResult.MAX_TEXT))
            }
            try {
                report(tr("Autonomous route tracking ${spec.legId} finished: ${result.outcome} (edge ${result.index + 1})${if (result.text.isNotBlank()) " \"${result.text}\"" else ""} — $stop",
                    "Suivi autonome ${spec.legId} terminé : ${result.outcome} (arête ${result.index + 1})${if (result.text.isNotBlank()) " « ${result.text} »" else ""} — $stop"))
            } finally {
                // The slot is always freed, even if the report fails: never a ghost tracking that would refuse the next ones.
                synchronized(lock) {
                    finished = Finished(result, leg.samples.toList()); active = null
                    runCatching { sink?.frame(LegFrames.LEG_RESULT, result.encode()) }
                }
                leg.done.countDown()
            }
        }
    }

    private fun sync(legId: Int, samplesHave: Int) {
        val target = synchronized(lock) { sink } ?: return
        synchronized(lock) {
            val a = active; val f = finished
            val (id, samples) = when {
                a != null && a.spec.legId == legId -> a.spec.legId to a.samples.toList()
                f != null && f.result.legId == legId -> f.result.legId to f.samples
                else -> { target.frame(LegFrames.LEG_STATE, LegFrames.legState(0, LegFrames.STATE_NONE, 0)); return }
            }
            samples.drop(samplesHave.coerceAtLeast(0)).forEachIndexed { k, s -> target.frame(LegFrames.LEG_SAMPLE, LegFrames.sample(id, samplesHave.coerceAtLeast(0) + k, s)) }
            a?.progress?.let { target.frame(LegFrames.LEG_PROGRESS, it.encode()) }
            f?.let { target.frame(LegFrames.LEG_RESULT, it.result.encode()) }
        }
    }

    /** Environment of a route tracking: the host for the robot, this object for the report to the pilot. */
    private fun environment(leg: Active): LegEnv {
        val inner = host.env(object : LegHooks {
            override fun event(kind: String, text: String, fields: Map<String, Any?>) = send(LegFrames.LEG_EVENT, LegFrames.legEvent(kind, text, fields))
            override fun progress(progress: LegProgress) {
                val shown = progress.copy(orphan = synchronized(lock) { sink == null })
                leg.progress = shown
                send(LegFrames.LEG_PROGRESS, shown.encode())
            }
            override fun sample(sample: SignalSample) {
                synchronized(lock) {
                    // At most one survey sample per second (like the pilot): the tracking offers one per 200 ms tick.
                    if (leg.samples.lastOrNull()?.let { sample.at - it.at < MIN_SAMPLE_GAP_MS } == true) return
                    val seq = leg.samples.size
                    leg.samples.add(sample)
                    sink?.frame(LegFrames.LEG_SAMPLE, LegFrames.sample(leg.spec.legId, seq, sample))
                }
            }
            override fun obstacles(): List<LegObstacle> = leg.obstacles
        })
        return object : LegEnv by inner {
            override fun hostFailure(): String? = inner.hostFailure() ?: host.hostFailure() ?: synchronized(lock) {
                if (leg.aborted) tr("Stop requested", "Arrêt demandé")
                else if (orphanSince != 0L && wall() - orphanSince > orphanLimitMs) tr("Pilot absent for more than ${orphanLimitMs / 60_000} min: safety stop", "Pilote absent depuis plus de ${orphanLimitMs / 60_000} min : arrêt de sécurité")
                else null
            }
            override fun cuttingFailure(): String? = inner.cuttingFailure() ?: host.cuttingFailure()
        }
    }

    companion object {
        /** Maximum duration of a route tracking with no reachable pilot. */
        const val ORPHAN_LIMIT_MS = 20 * 60_000L
        private const val MAX_KEPT = 4
        private const val MAX_UPLOADS = 3
        const val MIN_SAMPLE_GAP_MS = 1000L
    }
}
