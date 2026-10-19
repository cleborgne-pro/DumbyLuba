package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.SignalSample
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.locationtech.jts.geom.Geometry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Sends a frame to the gateway; false if the link is closed. */
fun interface LegTransport { fun send(type: Int, payload: ByteArray): Boolean }

/**
 * Pilot side: sends the route and the envelope surfaces to the gateway (once each), hands it the route trackings and
 * receives their progress, signal survey samples and outcome. Frames arrive through [onFrame] (a single thread); the
 * suspensions resume on the caller's thread.
 *
 * After a Wi-Fi outage, the gateway hands back the tracking state ([LegFrames.LEG_STATE]): the pilot then asks for what it
 * is missing (survey samples, outcome). A route tracking the pilot does not expect (app restarted) is interrupted at once.
 *
 * Language: the exception messages below end up in the route interruption reason, in the device language. Refusals
 * coming from the gateway are matched in both languages ("non reçu" / "not received").
 */
class RemoteLegs(private val transport: LegTransport, private val uploadTimeoutMs: Long = 60_000) {
    interface Callbacks {
        fun progress(progress: LegProgress)
        fun sample(sample: SignalSample)
        /**
         * Route tracking event reported by the gateway. Name distinct from any `event(kind, text)` of the caller:
         * an anonymous implementation that called `event(...)` called itself (infinite recursion, OOM of 2026-10-13).
         */
        fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>)
    }

    /** Route tracking handed to the gateway. */
    inner class Leg internal constructor(val spec: LegSpec, internal val callbacks: Callbacks) {
        val result = CompletableDeferred<LegResult>()
        internal var samplesHave = 0
        var lastProgress: LegProgress? = null; internal set
        /** Stop requested: the gateway stops the robot then hands back the outcome ([LegOutcome.ABORTED]). */
        fun abort() { transport.send(LegFrames.LEG_ABORT, LegFrames.legAbort(spec.legId)) }
        /** Up-to-date obstacle list. Too long for a frame: not sent (returns false), the tracking keeps the previous one. */
        fun obstacles(list: List<LegObstacle>): Boolean {
            val payload = LegFrames.obstacles(spec.legId, list)
            return payload.size <= LegFrames.MAX_PAYLOAD && transport.send(LegFrames.LEG_OBSTACLES, payload)
        }
    }

    private val ids = AtomicInteger((System.nanoTime() and 0x3FFFFFF).toInt() + 1)
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val routeCache = ArrayList<Pair<List<SimEdge>, Int>>()
    private val guardCache = ArrayList<Pair<Geometry, Int>>()
    @Volatile var current: Leg? = null; private set

    fun nextLegId(): Int = ids.incrementAndGet()

    /** Forgets the uploads already made (gateway restarted: it no longer has them). */
    fun forgetUploads() { synchronized(this) { routeCache.clear(); guardCache.clear() } }

    suspend fun ensureRoute(edges: List<SimEdge>): Int {
        synchronized(this) { routeCache.firstOrNull { it.first === edges }?.second }?.let { return it }
        val id = upload(LegBlobs.KIND_ROUTE, LegBlobs.encodeRoute(edges))
        synchronized(this) { routeCache.add(edges to id); while (routeCache.size > KEPT) routeCache.removeAt(0) }
        return id
    }

    suspend fun ensureGuard(surface: Geometry): Int {
        synchronized(this) { guardCache.firstOrNull { it.first === surface }?.second }?.let { return it }
        val id = upload(LegBlobs.KIND_GUARD, LegBlobs.encodeSurface(surface))
        synchronized(this) { guardCache.add(surface to id); while (guardCache.size > KEPT) guardCache.removeAt(0) }
        return id
    }

    private suspend fun upload(kind: Int, data: ByteArray): Int {
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<String?>()
        waiters[id] = waiter
        try {
            check(transport.send(LegFrames.BLOB_BEGIN, LegFrames.blobBegin(id, kind, data.size))) { tr("Gateway unreachable", "Passerelle injoignable") }
            var offset = 0
            while (offset < data.size) {
                val length = minOf(LegFrames.CHUNK_BYTES, data.size - offset)
                check(transport.send(LegFrames.BLOB_CHUNK, LegFrames.blobChunk(id, offset, data, offset, length))) { tr("Gateway unreachable", "Passerelle injoignable") }
                offset += length
            }
            check(transport.send(LegFrames.BLOB_END, LegFrames.blobEnd(id, data))) { tr("Gateway unreachable", "Passerelle injoignable") }
            val refusal = try { withTimeout(uploadTimeoutMs) { waiter.await() } }
                catch (_: TimeoutCancellationException) { error(tr("The gateway did not confirm receipt of the route (${data.size / 1024} KB)", "La passerelle n'a pas confirmé la réception du trajet (${data.size / 1024} Ko)")) }
            check(refusal == null) { tr("Upload refused by the gateway: $refusal", "Envoi refusé par la passerelle : $refusal") }
            return id
        } finally { waiters.remove(id) }
    }

    /** Hands a route tracking to the gateway. The outcome arrives in [Leg.result]. */
    fun start(spec: LegSpec, callbacks: Callbacks): Leg {
        check(current == null) { tr("A route is already being tracked by the gateway", "Un suivi est déjà confié à la passerelle") }
        val encoded = spec.encode()
        check(encoded.size <= LegFrames.MAX_PAYLOAD) { tr("Route tracking too large for one frame (${spec.obstacles.size} obstacles)", "Suivi trop volumineux pour une trame (${spec.obstacles.size} obstacles)") }
        val leg = Leg(spec, callbacks)
        current = leg
        if (!transport.send(LegFrames.LEG_START, encoded)) {
            current = null
            leg.result.completeExceptionally(IllegalStateException(tr("Gateway unreachable", "Passerelle injoignable")))
        }
        return leg
    }

    /** To be called when the route tracking is finished on the pilot side (outcome read, or abandoned). */
    fun release(leg: Leg) { if (current === leg) current = null }

    /** Frame from the gateway. Returns false if it is not an autonomous tracking frame. */
    fun onFrame(type: Int, payload: ByteArray): Boolean {
        if (type !in LegFrames.GATEWAY_TO_PILOT) return false
        try {
            val leg = current
            when (type) {
                LegFrames.BLOB_OK -> waiters[LegFrames.int(payload)]?.complete(null)
                LegFrames.BLOB_REJECTED -> waiters[LegFrames.int(payload)]?.complete(LegFrames.text(payload))
                LegFrames.LEG_REFUSED -> {
                    val reason = LegFrames.text(payload)
                    // Route or surface forgotten by the gateway (restarted): send them again on the next tracking.
                    // Missing upload refusal from the gateway (GatewayAutonomy), in either language.
                    if (reason.contains("non reçu") || reason.contains("not received")) forgetUploads()
                    // Refusal of another tracking, or of an invalid frame (number 0): does not concern the current tracking.
                    if (leg != null && LegFrames.int(payload) == leg.spec.legId && !leg.result.isCompleted) leg.result.completeExceptionally(RefusedLeg(reason))
                }
                LegFrames.LEG_PROGRESS -> if (leg != null) LegProgress.decode(payload).takeIf { it.legId == leg.spec.legId }?.let {
                    leg.lastProgress = it; leg.callbacks.progress(it) }
                LegFrames.LEG_SAMPLE -> if (leg != null) LegFrames.parseSample(payload).takeIf { it.legId == leg.spec.legId }?.let { frame ->
                    when {
                        frame.seq == leg.samplesHave -> { leg.samplesHave++; leg.callbacks.sample(frame.sample) }
                        // Gap (frame missed during an outage): ask again from the first missing survey sample.
                        frame.seq > leg.samplesHave -> transport.send(LegFrames.LEG_SYNC, LegFrames.legSync(leg.spec.legId, leg.samplesHave))
                    }
                }
                LegFrames.LEG_EVENT -> if (leg != null) LegFrames.parseLegEvent(payload).let { leg.callbacks.gatewayEvent(it.kind, it.text, it.fields) }
                LegFrames.LEG_RESULT -> {
                    val result = LegResult.decode(payload)
                    transport.send(LegFrames.LEG_ACK, LegFrames.legAck(result.legId))
                    if (leg != null && leg.spec.legId == result.legId) leg.result.complete(result)
                }
                LegFrames.LEG_STATE -> state(LegFrames.parseLegState(payload), leg)
            }
        } catch (error: Exception) {
            current?.result?.completeExceptionally(IllegalStateException(tr("Unreadable tracking frame: ${error.message ?: error.javaClass.simpleName}", "Trame de suivi illisible : ${error.message ?: error.javaClass.simpleName}")))
        }
        return true
    }

    private fun state(state: LegFrames.LegState, leg: Leg?) {
        when {
            // Nothing expected but the gateway is tracking or holds an outcome: take back control (stop) or acknowledge.
            leg == null -> when (state.state) {
                LegFrames.STATE_RUNNING -> transport.send(LegFrames.LEG_ABORT, LegFrames.legAbort(state.legId))
                LegFrames.STATE_FINISHED -> transport.send(LegFrames.LEG_ACK, LegFrames.legAck(state.legId))
            }
            state.state == LegFrames.STATE_NONE || state.legId != leg.spec.legId ->
                leg.result.completeExceptionally(LostLeg(leg.spec.legId))
            // Running or finished: ask for the missing survey samples, the progress and any outcome.
            else -> transport.send(LegFrames.LEG_SYNC, LegFrames.legSync(leg.spec.legId, leg.samplesHave))
        }
    }

    class RefusedLeg(message: String) : IllegalStateException(tr("Route tracking refused by the gateway: $message", "Suivi refusé par la passerelle : $message"))

    /**
     * The gateway has no record of the route tracking after the link came back: start order lost in a Wi-Fi outage
     * (test of 2026-10-15, 16:15:10: return to base never started) or gateway restarted. The robot is stopped by the
     * gateway; the pilot can resume the route from the real pose.
     */
    class LostLeg(val legId: Int) : IllegalStateException(tr("Route tracking lost: the gateway no longer knows tracking $legId (Wi-Fi cut or restart)", "Suivi perdu : la passerelle n'a plus trace du suivi $legId (coupure Wi-Fi ou redémarrage)"))

    private companion object { const val KEPT = 4 }
}
