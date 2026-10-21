package fr.luba.poc.auth

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local mode (without Mammotion account): saved setting and choice of the map shown while the robot is not linked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountModeTest {
    @After fun reset() = AccountMode.setLocalOnly(false)

    @Test fun `local mode saved on the phone`() {
        val context = RuntimeEnvironment.getApplication()
        AccountMode.init(context)
        assertFalse(AccountMode.localOnly)
        AccountMode.setLocalOnly(true)
        assertTrue(AccountMode.flow.value)
        assertTrue(context.getSharedPreferences("account_mode", Context.MODE_PRIVATE).getBoolean("local_only", false))
        AccountMode.setLocalOnly(false)
        assertFalse(context.getSharedPreferences("account_mode", Context.MODE_PRIVATE).getBoolean("local_only", true))
    }

    private fun show(ready: Boolean = false, local: Boolean = false, wanted: Boolean = true, account: Int = 0, complete: Boolean = account > 0, robot: Int = 0) =
        AccountMapDisplay.showAccountMap(ready, local, wanted, account, complete, robot)

    @Test fun `without account the Bluetooth map stays shown before the robot link`() {
        // Startup without account: the empty account map no longer hides the cached Bluetooth map.
        assertFalse(show(account = 0, robot = 12))
        assertFalse(show(account = 0, robot = 0))
        // Local mode: never the account map, even an old one read before.
        assertFalse(show(local = true, account = 43, robot = 12))
        assertFalse(show(local = true, account = 43, robot = 0))
    }

    @Test fun `account map shown when chosen or when the phone has no Bluetooth map`() {
        assertTrue(show(account = 43, robot = 12))
        assertTrue(show(wanted = false, account = 43, robot = 0))
        assertFalse("robot map chosen", show(wanted = false, account = 43, robot = 12))
        assertFalse("incomplete account map, not chosen", show(wanted = false, account = 5, complete = false, robot = 0))
        assertFalse("robot linked: its map", show(ready = true, account = 43, robot = 12))
    }
}
