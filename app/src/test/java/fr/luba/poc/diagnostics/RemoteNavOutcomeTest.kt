package fr.luba.poc.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RemoteNavOutcomeTest {
    /** Stop labels of [fr.luba.poc.autonomy.LegRunner]: deviation and envelope are recovered, the rest stops. */
    @Test fun deviationAndEnvelopeStopsAreRecoveredLikeTheLocalFollower() {
        assertTrue(RemoteNavOutcome.recoverable("Écart au trajet >30 cm (32 cm, arête 2)"))
        assertTrue(RemoteNavOutcome.recoverable("Enveloppe robot hors du couloir sûr"))
        assertFalse(RemoteNavOutcome.recoverable("Absence de progression depuis 15 s (arête 3)"))
        assertFalse(RemoteNavOutcome.recoverable("Protection / état robot changé (état 11, protection 3)"))
        assertFalse(RemoteNavOutcome.recoverable("Arrêt du robot non confirmé par la passerelle (FAILED)"))
    }
}
