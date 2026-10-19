package fr.luba.poc.gateway

import android.content.Context
import fr.luba.poc.i18n.tr

/** Phone role: direct link, gateway lying on the robot, or pilot going through the gateway. */
enum class GatewayRole {
    OFF,
    GATEWAY,
    CLIENT;

    /** Display label in the current language. */
    val label: String get() = when (this) {
        OFF -> tr("Direct Bluetooth", "Bluetooth direct")
        GATEWAY -> tr("Gateway on the robot", "Passerelle sur le robot")
        CLIENT -> tr("Control via the gateway", "Pilotage via la passerelle")
    }
}

data class GatewayConfig(val role: GatewayRole = GatewayRole.OFF, val host: String = "", val port: Int = GatewayProtocol.PORT, val code: String = "") {
    fun validCode() = code.matches(Regex("[0-9]{6}"))

    companion object {
        private const val PREFS = "gateway"

        fun read(context: Context): GatewayConfig {
            val prefs = context.getSharedPreferences(PREFS, 0)
            var code = prefs.getString("code", null)
            if (code == null) {
                code = "%06d".format(java.util.Locale.ROOT, java.security.SecureRandom().nextInt(1_000_000))
                prefs.edit().putString("code", code).apply()
            }
            return GatewayConfig(
                role = runCatching { GatewayRole.valueOf(prefs.getString("role", null) ?: "OFF") }.getOrDefault(GatewayRole.OFF),
                host = prefs.getString("host", "")!!, port = prefs.getInt("port", GatewayProtocol.PORT), code = code)
        }

        /** Robot chosen by the gateway: (address, name) or null. */
        fun robot(context: Context): Pair<String, String?>? {
            val prefs = context.getSharedPreferences(PREFS, 0)
            return prefs.getString("robotAddress", null)?.let { it to prefs.getString("robotName", null) }
        }

        /**
         * Gateway screen kept on during a control session: off by default (2026-10-13). Screen off, the Redmi A1 Wi-Fi goes
         * into power saving (average ping 98 ms, peaks > 600 ms; screen on, 22-71 ms), but autonomous tracking took the
         * Wi-Fi out of the control loop, whereas the screen kept on, at full brightness in practice, drained the battery at
         * ~8 %/h (versus ~1.5-2 %/h screen off). New key: the old implicit value (on) is not carried over.
         */
        fun keepScreen(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean(KEEP_SCREEN, false)
        fun setKeepScreen(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEEP_SCREEN, value).apply() }
        private const val KEEP_SCREEN = "keepScreenOn"

        fun saveRobot(context: Context, address: String?, name: String?) {
            context.getSharedPreferences(PREFS, 0).edit().putString("robotAddress", address).putString("robotName", name).apply()
        }

        fun save(context: Context, value: GatewayConfig) {
            context.getSharedPreferences(PREFS, 0).edit().putString("role", value.role.name).putString("host", value.host.trim())
                .putInt("port", value.port).putString("code", value.code).apply()
            // Role possibly changed: silence keeping applied or released right away.
            SilenceKeeper.refresh()
        }
    }
}
