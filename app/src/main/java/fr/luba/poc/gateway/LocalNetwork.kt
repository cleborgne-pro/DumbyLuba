package fr.luba.poc.gateway

import fr.luba.poc.i18n.tr

/**
 * Addresses the plain-TCP gateway protocol may use.
 *
 * The pilot's pairing code and every robot frame cross the Wi-Fi **in clear** (the ESP32 and the gateway phone have no
 * certificate), so the gateway is only reachable on the local network: loopback, the private IPv4 ranges, the IPv6
 * unique-local and link-local ranges, and the mDNS `.local` names. A public address is refused before any connection,
 * which keeps a wrong or hostile address from receiving the code (audit of 2026-10-30).
 */
object LocalNetwork {
    /** True for an address or name that stays on the local network. Never resolves a name (no DNS, no delay). */
    fun allows(host: String): Boolean {
        val name = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (name.isEmpty()) return false
        if (name == "localhost" || name == "::1") return true
        if (name.endsWith(".local")) return true
        ipv4(name)?.let { return it }
        if (name.contains(':')) return ipv6Local(name)
        // A plain name (router DNS, hostname typed by hand): not local by construction, refused.
        return false
    }

    /** Dotted-quad IPv4 in a private, loopback or link-local range; null if [name] is not an IPv4 literal. */
    private fun ipv4(name: String): Boolean? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 } || parts.any { it.length > 1 && it.startsWith('0') }) return null
        val (a, b) = octets
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            a == 0 -> true
            else -> false
        }
    }

    /** IPv6 unique-local (fc00::/7), link-local (fe80::/10) and IPv4-mapped private addresses. */
    private fun ipv6Local(name: String): Boolean {
        val head = name.substringBefore(':')
        if (head.startsWith("fc") || head.startsWith("fd") || head.startsWith("fe8") || head.startsWith("fe9") ||
            head.startsWith("fea") || head.startsWith("feb")) return true
        if (name.startsWith("::ffff:")) return ipv4(name.removePrefix("::ffff:")) == true
        return false
    }

    /** Refusal shown when the configured gateway address leaves the local network. */
    fun refusal(host: String): String = tr(
        "Gateway address $host is not on the local network: the gateway protocol is unencrypted, use its local IP or its .local name",
        "Adresse de passerelle $host hors du réseau local : le protocole de la passerelle n'est pas chiffré, utiliser son IP locale ou son nom .local",
    )
}
