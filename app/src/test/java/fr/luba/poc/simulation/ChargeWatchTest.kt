package fr.luba.poc.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recharge at the return threshold: a brief incident (late report, Wi-Fi outage) no longer stops the mission. */
class ChargeWatchTest {
    @Test fun nominalChargeStaysNominal() {
        val watch = ChargeWatch(1000)
        assertEquals(ChargeWatch.Step.Nominal, watch.observe(0, null))
        assertEquals(ChargeWatch.Step.Nominal, watch.observe(500, null))
    }

    @Test fun briefIncidentIsToleratedThenCleared() {
        val watch = ChargeWatch(1000)
        assertEquals(ChargeWatch.Step.Started("État robot absent ou périmé"), watch.observe(100, "État robot absent ou périmé"))
        assertEquals(ChargeWatch.Step.Ongoing("Wi-Fi de la passerelle coupé", 800), watch.observe(900, "Wi-Fi de la passerelle coupé"))
        assertEquals(ChargeWatch.Step.Cleared("Wi-Fi de la passerelle coupé", 1000), watch.observe(1100, null))
        assertEquals(ChargeWatch.Step.Nominal, watch.observe(1200, null))
    }

    @Test fun lastingIncidentStopsTheMission() {
        val watch = ChargeWatch(1000)
        watch.observe(0, "Contact de charge perdu")
        assertTrue(watch.observe(1000, "Contact de charge perdu") is ChargeWatch.Step.Ongoing)
        assertEquals(ChargeWatch.Step.Failed("Contact de charge perdu", 1001), watch.observe(1001, "Contact de charge perdu"))
    }

    @Test fun newIncidentRestartsTheGrace() {
        val watch = ChargeWatch(1000)
        watch.observe(0, "État robot absent ou périmé")
        watch.observe(900, null)
        assertEquals(ChargeWatch.Step.Started("Contact de charge perdu"), watch.observe(950, "Contact de charge perdu"))
        assertTrue(watch.observe(1900, "Contact de charge perdu") is ChargeWatch.Step.Ongoing)
    }

    @Test fun defaultGraceCoversTheLongestWifiOutageSeen() {
        // Access point change of 2026-10-14: 25 s outage.
        assertTrue(ChargeWatch.GRACE_MS >= 60_000)
    }
}
