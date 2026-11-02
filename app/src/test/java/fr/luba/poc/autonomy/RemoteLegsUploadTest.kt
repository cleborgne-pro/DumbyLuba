package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.simulation.SimEdge
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fold test of 2026-11-02: before each tracking handed to the ESP32 gateway the robot waited 2.1 to 2.6 s, standing
 * still, for the route and guard surfaces to be uploaded. They are now sent ahead and an upload is never sent twice.
 */
class RemoteLegsUploadTest {
    private val route = List(30) { SimEdge(Point(it.toDouble(), 0.0), Point(it + 1.0, 0.0), true) }

    /** Gateway that confirms each upload, or refuses the first [refusals]; counts the uploads begun. */
    private class Gateway(var refusals: Int = 0) {
        var begun = 0
        val pending = ArrayList<Int>()
        lateinit var legs: RemoteLegs
        val transport = LegTransport { type, payload ->
            if (type == LegFrames.BLOB_BEGIN) begun++
            if (type == LegFrames.BLOB_END) pending += LegFrames.int(payload)
            true
        }
        fun answer() {
            pending.toList().forEach { id ->
                pending.remove(id)
                if (refusals > 0) { refusals--; legs.onFrame(LegFrames.BLOB_REJECTED, LegFrames.blobOk(id)) }
                else legs.onFrame(LegFrames.BLOB_OK, LegFrames.blobOk(id))
            }
        }
    }

    private fun gateway(refusals: Int = 0) = Gateway(refusals).also { it.legs = RemoteLegs(it.transport) }

    @Test fun aSubListOfTheSameEdgesIsNotSentAgain() = runBlocking {
        val gateway = gateway()
        val first = async { gateway.legs.ensureRoute(route.subList(0, 20)) }
        yield(); gateway.answer()
        val id = first.await()
        assertEquals(id, gateway.legs.ensureRoute(route.subList(0, 20)))
        assertEquals(1, gateway.begun)
    }

    @Test fun aTrackingWaitsForTheUploadSentAheadInsteadOfSendingItTwice() = runBlocking {
        val gateway = gateway()
        val ahead = async { gateway.legs.ensureRoute(route) }
        yield()
        val needed = async { gateway.legs.ensureRoute(route.toList()) }
        yield(); gateway.answer()
        assertEquals(ahead.await(), needed.await())
        assertEquals(1, gateway.begun)
    }

    @Test fun aFailedUploadSentAheadIsSentAgainByTheTracking() = runBlocking {
        val gateway = gateway(refusals = 1)
        val ahead = async { runCatching { gateway.legs.ensureRoute(route) } }
        yield()
        val needed = async { gateway.legs.ensureRoute(route) }
        yield(); gateway.answer()
        assertTrue(ahead.await().isFailure)
        yield(); gateway.answer()
        needed.await()
        assertEquals(2, gateway.begun)
    }

    @Test fun uploadsSentBeforeTheGatewayRestartedAreSentAgain() = runBlocking {
        val gateway = gateway()
        val first = async { gateway.legs.ensureRoute(route) }
        yield(); gateway.answer(); first.await()
        gateway.legs.forgetUploads()
        val again = async { gateway.legs.ensureRoute(route) }
        yield(); gateway.answer(); again.await()
        assertEquals(2, gateway.begun)
    }
}
