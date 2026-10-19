package fr.luba.poc.auth

import android.content.Context
import org.json.JSONObject

data class SavedAccount(val email: String, val password: String) {
    override fun toString() = "SavedAccount([hidden])"
}

object AccountEncoding {
    fun encode(account: SavedAccount): String {
        require(account.email.isNotBlank() && account.password.isNotEmpty())
        return JSONObject().put("email",account.email.trim()).put("password",account.password).toString()
    }
    fun decode(value: String): SavedAccount? = runCatching {
        val json=JSONObject(value)
        SavedAccount(json.getString("email"),json.getString("password")).also {
            require(it.email.isNotBlank() && it.password.isNotEmpty())
        }
    }.getOrNull()
}

/** AES-GCM credentials with an Android Keystore key, separate from the OAuth session. */
class AccountStore internal constructor(private val encrypted: SessionStore) {
    constructor(context: Context) : this(SessionStore(context,"mammotion_secure_account"))
    fun read(): SavedAccount? = encrypted.read()?.let { value ->
        AccountEncoding.decode(value).also { if(it==null) encrypted.clear() }
    }
    fun save(account: SavedAccount) = encrypted.save(AccountEncoding.encode(account))
    fun clear() = encrypted.clear()
}
