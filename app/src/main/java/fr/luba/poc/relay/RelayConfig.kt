package fr.luba.poc.relay

import android.content.Context
import fr.luba.poc.i18n.tr
import java.net.URI

 data class RelayConfig(val url: String, val code: String, val id: String) {
    fun validate() {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { tr("Expected address: http://server-address:8765", "Adresse attendue : http://adresse-du-serveur:8765") }
        require(code.matches(Regex("[0-9]{6}"))) { tr("Expected code: six digits", "Code attendu : six chiffres") }
    }
    companion object {
        fun read(context: Context): RelayConfig {
            val prefs = context.getSharedPreferences("relay-server", 0)
            val code = prefs.getString("code", null) ?: "%06d".format(java.util.Locale.ROOT, java.security.SecureRandom().nextInt(1_000_000))
            val id = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString()
            prefs.edit().putString("code", code).putString("id", id).apply()
            return RelayConfig(prefs.getString("url", "")!!, code, id)
        }
        fun save(context: Context, value: RelayConfig) {
            value.validate()
            context.getSharedPreferences("relay-server", 0).edit().putString("url", value.url.trimEnd('/')).putString("code", value.code).putString("id", value.id).apply()
        }
    }
}
