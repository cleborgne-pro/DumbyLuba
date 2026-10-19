package fr.luba.poc.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterruptionSummaryTest {
    @Test fun realReasonsOfThe15thAreShortened() {
        val latch = InterruptionSummary.of("Trajet interrompu : Protection / état robot changé (état 20, protection 25) : protection " +
            "toujours active après 30 s (état 11, protection 25), arrêt maintenu")
        assertEquals("Contact avec un obstacle", latch.label)
        assertEquals("Protection / état robot changé (état 20, protection 25) : protection toujours active après 30 s (état 11, " +
            "protection 25), arrêt maintenu", latch.detail)
        assertEquals("Contact avec un obstacle : le robot n'a pas pu reculer", InterruptionSummary.of("Trajet interrompu : Contact bumper " +
            "(-1014) : Recul impossible après le contact : trace absente ou châssis bloqué").label)
        assertEquals("Robot bloqué : les roues tournent sans avancer", InterruptionSummary.of("Trajet interrompu : Soulèvement ou blocage " +
            "détecté : Roues commandées sans progression : 7 cm parcourus pour 38 cm attendus en 3,0 s").label)
        assertEquals("Robot bloqué : les roues tournent sans avancer", InterruptionSummary.of("Trajet interrompu : Plage trop longue : 0 m non parcourus en 128 s").label)
        assertEquals("Liaison perdue avec la passerelle", InterruptionSummary.of("Trajet interrompu : Suivi perdu : la passerelle n'a plus trace du suivi 3").label)
        assertEquals("Arrêtée avec le bouton STOP", InterruptionSummary.of("STOP manuel").label)
        assertEquals("Liaison perdue avec le robot", InterruptionSummary.of("FAILED — timeout GATT, STOP potentiellement indisponible").label)
        assertEquals("Liaison perdue avec le robot", InterruptionSummary.of("FAILED — timeout GATT (sync(2) sans confirmation en 5,0 s), STOP potentiellement indisponible").label)
    }

    @Test fun missingReasonMeansTheAppWasClosed() {
        val s = InterruptionSummary.of(null)
        assertEquals("Application fermée ou téléphone redémarré pendant la tonte", s.label)
        assertNull(s.detail)
    }
}
