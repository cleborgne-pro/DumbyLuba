package fr.luba.poc.relay

import android.content.Context
import androidx.core.content.edit
import fr.luba.poc.gateway.LocalNetwork
import fr.luba.poc.i18n.tr
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

 data class RelayConfig(val url: String, val code: String, val id: String) {
    fun validate() {
        val uri = URI(url)
        val host = uri.host.orEmpty()
        require(uri.scheme in setOf("http", "https") && host.isNotBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { tr("Expected address: http://server-address:8765", "Adresse attendue : http://adresse-du-serveur:8765") }
        // The six-digit code and the robot state cross a plain HTTP link in clear: only the local network, exactly like
        // the gateway protocol (audit of 2026-10-30). HTTPS may leave the LAN: the transport is then encrypted.
        require(uri.scheme != "http" || LocalNetwork.allows(host)) { LocalNetwork.refusal(host) }
        require(code.matches(Regex("[0-9]{6}"))) { tr("Expected code: six digits", "Code attendu : six chiffres") }
    }
    companion object {
        fun read(context: Context): RelayConfig {
            val prefs = context.getSharedPreferences("relay-server", 0)
            val code = prefs.getString("code", null) ?: "%06d".format(Locale.ROOT, SecureRandom().nextInt(1_000_000))
            val id = prefs.getString("id", null) ?: UUID.randomUUID().toString()
            prefs.edit { putString("code", code); putString("id", id) }
            return RelayConfig(prefs.getString("url", "").orEmpty(), code, id)
        }
        fun save(context: Context, value: RelayConfig) {
            value.validate()
            context.getSharedPreferences("relay-server", 0).edit { putString("url", value.url.trimEnd('/')); putString("code", value.code); putString("id", value.id) }
        }
    }
}
