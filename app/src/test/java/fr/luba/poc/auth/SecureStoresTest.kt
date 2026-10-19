package fr.luba.poc.auth

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** OAuth session and remembered credentials: AES-GCM encrypted, erased if they become unreadable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureStoresTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val key = FakeMammotionServer.testKey()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun `encrypted session read back and erased`() {
        val store = FakeMammotionServer.store(context, "s1", key)
        assertNull(store.read())
        store.save("""{"access_token":"jeton-secret"}""")
        assertEquals("""{"access_token":"jeton-secret"}""", store.read())
        val raw = prefs("s1").getString("session", null)!!
        assertFalse("never in clear text", raw.contains("jeton-secret"))
        // Same value, different ciphertext (random initialisation vector).
        store.save("""{"access_token":"jeton-secret"}"""); assertNotEquals(raw, prefs("s1").getString("session", null))
        store.clear(); assertNull(store.read()); assertFalse(prefs("s1").contains("session"))
    }

    @Test fun `tampered session or changed key erased without crash`() {
        val store = FakeMammotionServer.store(context, "s2", key)
        store.save("valeur")
        val raw = prefs("s2").getString("session", null)!!
        // Keystore key regenerated (reinstall, restored backup): unreadable, session forgotten.
        assertNull(FakeMammotionServer.store(context, "s2").read())
        assertFalse(prefs("s2").contains("session"))
        // Truncated or modified data: GCM authentication refused.
        for (bad in listOf(raw.dropLast(4), "AAAA", "pas du base64 !")) {
            prefs("s2").edit().putString("session", bad).commit()
            assertNull(bad, store.read()); assertFalse(prefs("s2").contains("session"))
        }
    }

    @Test fun `remembered credentials separate from the session`() {
        val session = FakeMammotionServer.store(context, "session", key)
        val accounts = AccountStore(FakeMammotionServer.store(context, "account", key))
        assertNull(accounts.read())
        accounts.save(SavedAccount(" moi@example.com ", "mot de passe"))
        assertEquals(SavedAccount("moi@example.com", "mot de passe"), accounts.read())
        assertNull("the session stays empty", session.read())
        assertFalse(accounts.read().toString().contains("mot de passe"))
        assertThrows(IllegalArgumentException::class.java) { accounts.save(SavedAccount("", "x")) }
        accounts.clear(); assertNull(accounts.read())
    }

    @Test fun `unreadable credentials forgotten`() {
        val raw = FakeMammotionServer.store(context, "account2", key)
        raw.save("""{"email":"moi@example.com"}""")
        assertNull(AccountStore(raw).read())
        assertNull("value erased", raw.read())
    }
}
