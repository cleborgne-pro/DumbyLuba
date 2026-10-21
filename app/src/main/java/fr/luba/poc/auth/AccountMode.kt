package fr.luba.poc.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import fr.luba.poc.i18n.tr

/**
 * Local mode (Me → Mammotion account): the app works without the Mammotion account. No request is sent to the
 * Mammotion cloud ([CloudViewModel] refuses them all) and the screens no longer offer the account entries (account
 * map, camera, obstacle photo, native return without Bluetooth, firmware). Off by default; stored on the phone only.
 */
object AccountMode {
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow(false)
    val flow: StateFlow<Boolean> get() = state
    val localOnly: Boolean get() = state.value
    /** Reason shown wherever a Mammotion account feature is refused in local mode. */
    val message: String get() = tr("Local mode: Mammotion account not used (Me tab)", "Mode local : compte Mammotion non utilisé (onglet Moi)")

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { state.value = it.getBoolean(KEY, false) }
    }

    fun setLocalOnly(enabled: Boolean) {
        state.value = enabled
        prefs?.edit()?.putBoolean(KEY, enabled)?.apply()
    }

    /** Local mode read in a composable (recomposed when it changes). */
    @Composable
    fun observe(): Boolean = state.collectAsState().value

    private const val PREFS = "account_mode"
    private const val KEY = "local_only"
}

/** Map shown while the robot is not linked (Bluetooth or gateway). Pure. */
object AccountMapDisplay {
    /**
     * Account map instead of the phone's Bluetooth map (cache, last known position): only when the account is used
     * and its map has content, either chosen ([wanted]: before the first robot link, or "Open the map" from the
     * account) or because the phone has no Bluetooth map. An empty account map (no account, map not read yet) never
     * hides the Bluetooth map.
     */
    fun showAccountMap(robotReady: Boolean, localOnly: Boolean, wanted: Boolean, accountShapes: Int, accountComplete: Boolean, robotShapes: Int): Boolean =
        !robotReady && !localOnly && accountShapes > 0 && (wanted || (robotShapes == 0 && accountComplete))
}
