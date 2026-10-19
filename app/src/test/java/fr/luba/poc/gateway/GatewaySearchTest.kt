package fr.luba.poc.gateway

import org.junit.Assert.*
import org.junit.Test

class GatewaySearchTest {
    @Test fun savedAddressFirstThenAnnouncedGatewaysWithoutDuplicates() {
        val announced = listOf(GatewayEndpoint("DumbyLuba passerelle", "192.168.0.189", 8766),
            GatewayEndpoint("DumbyLuba passerelle (2)", "192.168.0.42", 8766))
        assertEquals(listOf("192.168.0.12", "192.168.0.189", "192.168.0.42"),
            GatewaySearch.candidates(" 192.168.0.12 ", 8766, announced).map { it.host })
        assertEquals(listOf("192.168.0.189", "192.168.0.42"), GatewaySearch.candidates("192.168.0.189", 8766, announced).map { it.host })
        assertEquals(announced, GatewaySearch.candidates("", 8766, announced))
    }

    @Test fun retriesEvery15sThenEveryMinuteWhileNoGatewayAnswers() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L), (0..4).map(GatewayRetry::delayMs))
        assertEquals(15_000L, GatewayRetry.delayMs(-1))
    }

    private val redmi = GatewayEndpoint("", "192.168.0.189", 8766)
    private val esp32 = GatewayEndpoint("DumbyLuba passerelle ESP32", "192.168.0.187", 8766)

    /** 2026-10-15, 18:23: saved Redmi silent (Wi-Fi −87 dBm), ESP32 with a different code; "Wrong code" was misleading. */
    @Test fun savedGatewayTimeoutIsNotMaskedByAnotherGatewayRefusal() {
        val failure = GatewaySearch.failure(redmi, listOf(redmi to java.net.SocketTimeoutException("Read timed out"),
            esp32 to GatewayDenied("Code de passerelle incorrect")), announced = 2)
        assertFalse(failure is GatewayDenied)
        assertEquals("192.168.0.189 injoignable (délai dépassé) ; autre passerelle 192.168.0.187 : Code de passerelle incorrect", failure.message)
        // No announcement: the saved address only.
        assertEquals("192.168.0.189 injoignable (connexion impossible) et passerelle introuvable sur le Wi-Fi",
            GatewaySearch.failure(redmi, listOf(redmi to java.net.ConnectException("failed")), announced = 0).message)
    }

    @Test fun refusalOfTheSavedGatewayItselfIsReportedAsIs() {
        val denied = GatewayDenied("Code de passerelle incorrect")
        assertSame(denied, GatewaySearch.failure(redmi, listOf(redmi to denied, esp32 to java.net.SocketTimeoutException()), announced = 2))
        // Without a saved address: the refusal of an announced gateway is still reported.
        assertSame(denied, GatewaySearch.failure(null, listOf(esp32 to denied), announced = 1))
        assertEquals("passerelle introuvable sur le Wi-Fi", GatewaySearch.failure(null, emptyList(), announced = 0).message)
    }

    @Test fun weakGatewayWifiIsRecalledInLossMessages() {
        assertEquals(" · Wi-Fi de la passerelle -87 dBm : trop faible, rapprocher le robot du point d'accès ou ajouter un répéteur",
            GatewayHealthStore.wifiHint(-87, 5_000))
        assertTrue(GatewayHealthStore.wifiHint(-89, 150_000).contains("-89 dBm (il y a 2 min)"))
        assertEquals("", GatewayHealthStore.wifiHint(-60, 5_000))
        assertEquals("", GatewayHealthStore.wifiHint(-87, GatewayHealthStore.WIFI_HINT_MS + 1))
    }
}
