package fr.luba.poc.gateway

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pairing of a pilot with a gateway, carried by the QR code shown on the gateway phone (or the same link opened from
 * elsewhere): `dumbyluba://pair?v=2&host=192.168.0.10&port=8766&code=123456&name=Redmi%20A1`.
 *
 * The code is the gateway's secret, as when it is read on its screen and typed: the QR is only shown on that screen.
 * [host] may be stale after a DHCP change; the pilot then finds the gateway again on the Wi-Fi with the same code.
 */
data class GatewayPairing(val host: String, val port: Int = GatewayProtocol.PORT, val code: String, val name: String = "") {
    fun uri(): String = "$SCHEME://$HOST?v=${GatewayProtocol.VERSION}&host=${enc(host)}&port=$port&code=$code" +
        (if (name.isBlank()) "" else "&name=${enc(name)}")

    /** Label shown to the user before pairing: the gateway's name, otherwise its address. */
    val label: String get() = name.ifBlank { host }

    companion object {
        const val SCHEME = "dumbyluba"
        const val HOST = "pair"
        private const val MAX_NAME = 40
        private val HOSTNAME = Regex("[A-Za-z0-9.-]{1,253}")
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * Pairing read from a QR code or a link; null if the text is not a valid DumbyLuba pairing (other QR code,
         * truncated link, code that is not six digits, address with unexpected characters).
         */
        fun parse(text: String?): GatewayPairing? {
            val raw = text?.trim() ?: return null
            val prefix = "$SCHEME://$HOST?"
            if (!raw.startsWith(prefix, ignoreCase = true)) return null
            val params = raw.substring(prefix.length).split('&').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).lowercase() to
                    runCatching { URLDecoder.decode(part.substring(index + 1), "UTF-8") }.getOrNull()
            }.filter { it.second != null }.associate { it.first to it.second!! }
            val host = params["host"]?.trim()?.takeIf { HOSTNAME.matches(it) } ?: return null
            val port = (params["port"]?.toIntOrNull() ?: GatewayProtocol.PORT).takeIf { it in 1..65535 } ?: return null
            val code = params["code"]?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: return null
            val name = params["name"].orEmpty().filter { it.isLetterOrDigit() || it in " -_.·()" }.trim().take(MAX_NAME)
            return GatewayPairing(host, port, code, name)
        }

        /** Pairing of the gateway running on this phone, for its QR code; null while its Wi-Fi address is unknown. */
        fun ofThisGateway(context: Context, address: String?): GatewayPairing? {
            val host = address?.takeIf { it.isNotBlank() } ?: return null
            return GatewayPairing(host, GatewayProtocol.PORT, GatewayConfig.read(context).code, android.os.Build.MODEL.orEmpty())
        }

        /** Pilot side: controls the robot through this gateway from now on (role, address, port and code saved). */
        fun apply(context: Context, pairing: GatewayPairing): GatewayConfig {
            val config = GatewayConfig.read(context).copy(role = GatewayRole.CLIENT, host = pairing.host, port = pairing.port, pilotCode = pairing.code)
            GatewayConfig.save(context, config)
            return config
        }
    }
}
