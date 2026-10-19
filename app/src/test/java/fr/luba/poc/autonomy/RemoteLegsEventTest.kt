package fr.luba.poc.autonomy

import fr.luba.poc.map.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Route tracking event reported by the gateway (LEG_EVENT): delivered once to the pilot.
 * Regression of 2026-10-13: the pilot callback called itself and filled the memory (Fold OOM).
 */
class RemoteLegsEventTest {
    private class Recorder : RemoteLegs.Callbacks {
        val events = mutableListOf<Pair<String, String>>()
        override fun progress(progress: LegProgress) {}
        override fun sample(sample: SignalSample) {}
        val fields = mutableListOf<Map<String, Any?>>()
        override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) { events += kind to text; this.fields += fields }
    }

    private fun spec(legId: Int) = LegSpec(legId, 1, 0, cutting = false, shown = true, watchBattery = false, departure = false,
        lastArrivalM = null, heightMm = 60, speedPercent = 40, returnPercent = 20, baseApproach = null, guardId = 1, physicalGuardId = 1,
        calibration = null, obstacles = emptyList(), sampling = false)

    @Test fun gatewayEventIsDeliveredOnceWithItsText() {
        val legs = RemoteLegs({ _, _ -> true })
        val recorder = Recorder()
        legs.start(spec(legs.nextLegId()), recorder)
        legs.onFrame(LegFrames.LEG_EVENT, LegFrames.legEvent("route_settle", "Arrêt avant le point de fin de plage : approche reprise"))
        legs.onFrame(LegFrames.LEG_EVENT, LegFrames.legEvent("route_dock_exit", "Sortie rectiligne lente\nsur deux lignes"))
        assertEquals(listOf("route_settle" to "Arrêt avant le point de fin de plage : approche reprise",
            "route_dock_exit" to "Sortie rectiligne lente\nsur deux lignes"), recorder.events)
    }

    /** Tests of 2026-10-14: "Écart au trajet >30 cm" reached the pilot without pose, heading or deviation. */
    @Test fun gatewayEventCarriesItsFields() {
        val legs = RemoteLegs({ _, _ -> true })
        val recorder = Recorder()
        legs.start(spec(legs.nextLegId()), recorder)
        legs.onFrame(LegFrames.LEG_EVENT, LegFrames.legEvent("leg_failure", "Suivi autonome arrêté :\nécart",
            mapOf("x" to 0.8123, "deviationM" to 0.33, "headingTrusted" to true, "robotState" to 20, "note" to null)))
        assertEquals(listOf("leg_failure" to "Suivi autonome arrêté :\nécart"), recorder.events)
        val fields = recorder.fields.single()
        assertEquals(0.8123, (fields["x"] as Number).toDouble(), 1e-9)
        assertEquals(0.33, (fields["deviationM"] as Number).toDouble(), 1e-9)
        assertEquals(true, fields["headingTrusted"])
        assertEquals(20, (fields["robotState"] as Number).toInt())
        assertEquals(null, fields["note"])
    }

    @Test fun eventWithoutFieldsKeepsTheOldFormat() {
        assertEquals("a\nb", LegFrames.legEvent("a", "b").toString(Charsets.UTF_8))
        val parsed = LegFrames.parseLegEvent("a\nb".toByteArray())
        assertEquals("a", parsed.kind); assertEquals("b", parsed.text); assertEquals(emptyMap<String, Any?>(), parsed.fields)
    }

    @Test fun gatewayEventWithoutLegIsIgnored() {
        val legs = RemoteLegs({ _, _ -> true })
        val recorder = Recorder()
        val leg = legs.start(spec(legs.nextLegId()), recorder)
        legs.release(leg)
        legs.onFrame(LegFrames.LEG_EVENT, LegFrames.legEvent("leg_failure", "Suivi autonome arrêté"))
        assertEquals(emptyList<Pair<String, String>>(), recorder.events)
    }
}
