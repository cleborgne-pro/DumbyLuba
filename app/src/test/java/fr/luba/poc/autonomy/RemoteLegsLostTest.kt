package fr.luba.poc.autonomy

import fr.luba.poc.map.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test of 2026-10-15, 16:15:10: return to base at the low threshold handed to the gateway, start order lost in a 2 s Wi-Fi
 * outage; when the link came back, the gateway had no record of any route tracking. The outcome is a recognisable
 * [RemoteLegs.LostLeg] (the pilot resumes the route from the real pose), not a generic failure that stopped the mission.
 */
class RemoteLegsLostTest {
    private class Quiet : RemoteLegs.Callbacks {
        override fun progress(progress: LegProgress) {}
        override fun sample(sample: SignalSample) {}
        override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) {}
    }

    private fun spec(legId: Int) = LegSpec(legId, 1, 0, cutting = false, shown = false, watchBattery = false, departure = false,
        lastArrivalM = null, heightMm = 60, speedPercent = 40, returnPercent = 20, baseApproach = null, guardId = 1, physicalGuardId = 1,
        calibration = null, obstacles = emptyList(), sampling = false)

    @Test fun gatewayWithoutTraceOfTheLegReportsALostLeg() {
        val legs = RemoteLegs({ _, _ -> true })
        val leg = legs.start(spec(legs.nextLegId()), Quiet())
        legs.onFrame(LegFrames.LEG_STATE, LegFrames.legState(0, LegFrames.STATE_NONE, 0))
        assertTrue(leg.result.isCompleted)
        val error = leg.result.getCompletionExceptionOrNull()
        assertTrue("outcome: $error", error is RemoteLegs.LostLeg)
        assertEquals(leg.spec.legId, (error as RemoteLegs.LostLeg).legId)
        assertFalse(error is RemoteLegs.RefusedLeg)
    }

    @Test fun gatewayReportingAnotherLegAlsoLosesTheCurrentOne() {
        val legs = RemoteLegs({ _, _ -> true })
        val leg = legs.start(spec(legs.nextLegId()), Quiet())
        legs.onFrame(LegFrames.LEG_STATE, LegFrames.legState(leg.spec.legId - 7, LegFrames.STATE_FINISHED, 0))
        assertTrue(leg.result.getCompletionExceptionOrNull() is RemoteLegs.LostLeg)
    }

    @Test fun runningLegIsSynchronisedNotLost() {
        val sent = mutableListOf<Int>()
        val legs = RemoteLegs({ type, _ -> sent += type; true })
        val leg = legs.start(spec(legs.nextLegId()), Quiet())
        legs.onFrame(LegFrames.LEG_STATE, LegFrames.legState(leg.spec.legId, LegFrames.STATE_RUNNING, 0))
        assertFalse(leg.result.isCompleted)
        assertEquals(LegFrames.LEG_SYNC, sent.last())
    }
}
