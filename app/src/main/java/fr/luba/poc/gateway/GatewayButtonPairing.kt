package fr.luba.poc.gateway

import android.content.Context
import fr.luba.poc.i18n.tr
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pairing of the ESP32 gateway by its PWR button (firmware 0.3.1 and later), without the serial console: a short press
 * restarts the board, which then opens a 3 min window (LED double flash) during which `GET /pair` on its port 80 answers
 * `{"open":true,"link":"dumbyluba://pair?…"}`, the same link as the Android gateway's QR code. Outside the window:
 * 403, the code is never handed out. The window closes as soon as a pilot authenticates with the code.
 *
 * Plain HTTP over a socket (no HttpURLConnection): the app does not allow cleartext HTTP, and the code travels on the
 * home Wi-Fi as for the rest of the gateway protocol.
 */
object GatewayButtonPairing {
    const val PATH = "/pair"
    const val HTTP_PORT = 80
    private const val MAX_RESPONSE = 4096
    private val LINK = Regex("\"link\"\\s*:\\s*\"([^\"]*)\"")

    /** Pairing in the HTTP response of `GET /pair`; null if the window is closed (403) or the answer is not valid. */
    fun parseResponse(response: String?): GatewayPairing? {
        val raw = response ?: return null
        val status = raw.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
        if (status != "200") return null
        val body = raw.substringAfter("\r\n\r\n", "")
        return LINK.find(body)?.groupValues?.get(1)?.replace("\\/", "/")?.let { GatewayPairing.parse(it) }
    }

    /** Asks [host] for its pairing link; null if it has none to hand out (window closed, not an ESP32, unreachable). */
    fun fetch(host: String, port: Int = HTTP_PORT, timeoutMs: Int = 2000): GatewayPairing? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write("GET $PATH HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = socket.getInputStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (out.size() < MAX_RESPONSE) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, minOf(n, MAX_RESPONSE - out.size()))
            }
            parseResponse(out.toString(Charsets.UTF_8.name()))
        }
    }.getOrNull()

    /** First of [hosts] (saved address and gateways announced on the Wi-Fi, without duplicates) whose window is open. */
    fun first(hosts: List<String>, fetch: (String) -> GatewayPairing?): GatewayPairing? =
        hosts.map { it.trim() }.filter { it.isNotBlank() }.distinct().firstNotNullOfOrNull(fetch)

    /**
     * Pilot side: finds the ESP32 whose pairing window is open, checks its code with a real session (which closes the
     * window), then pilots through it from now on. Throws with a readable reason otherwise.
     */
    fun pair(context: Context): GatewayPairing {
        val config = GatewayConfig.read(context)
        val announced = GatewayClient.discoverAll(context, 3000)
        val pairing = first(announced.map { it.host } + config.host) { fetch(it) }
            ?: throw IllegalStateException(
                if (announced.isEmpty()) tr("no gateway found on this Wi-Fi: is the ESP32 on the same Wi-Fi?",
                    "aucune passerelle trouvée sur ce Wi-Fi : l'ESP32 est-elle sur le même Wi-Fi ?")
                else tr("no ESP32 open for pairing: briefly press its PWR button, wait for the double flash of the LED (about 20 s), then try again within 3 min",
                    "aucune ESP32 ouverte à l'appairage : appuyez brièvement sur son bouton PWR, attendez le double clignotement de la LED (une vingtaine de secondes), puis réessayez dans les 3 min"))
        val channel = GatewayChannel.open(pairing.host, pairing.port, pairing.code)
        runCatching { channel.send(GatewayProtocol.CLOSE) }
        channel.close()
        GatewayPairing.apply(context, pairing)
        return pairing
    }
}
