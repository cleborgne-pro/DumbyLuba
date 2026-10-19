package fr.luba.poc.relay

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import fr.luba.poc.gateway.SilenceKeeper
import fr.luba.poc.gateway.WirelessDebugKeeper

/** The activity and the foreground service share one BLE owner for the process. */
class RelayApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        // Interface language chosen in the app, applied before any text (notifications, services) is built.
        fr.luba.poc.i18n.LanguageStore.init(this)
        // Wireless debugging switched back on when Android turns it off, whatever the phone's role
        // (WRITE_SECURE_SETTINGS permission granted by tools/deploy.sh; without it, nothing happens).
        WirelessDebugKeeper.install(this)
        // Gateway: phone kept silent (Android turns the volume back up); no effect in the other roles.
        SilenceKeeper.install(this)
        // Pilot: phone Wi-Fi state, quoted in the log when the gateway becomes unreachable (tests of 2026-10-14).
        fr.luba.poc.gateway.PhoneWifi.install(this)
    }
}
