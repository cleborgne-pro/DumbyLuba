package fr.luba.poc.auth
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
/** [key]: Android Keystore AES key; replaced only by JVM tests (no Keystore under Robolectric). */
class SessionStore internal constructor(context:Context, preferencesName:String, private val key:()->SecretKey) {
    constructor(context:Context, preferencesName:String="mammotion_secure_session"):this(context,preferencesName,::keystoreKey)
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    fun save(value:String) { val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());check(prefs.edit().putString("session",Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),Base64.NO_WRAP)).commit()) }
    fun read():String? { val s=prefs.getString("session",null) ?: return null;return try { val b=Base64.decode(s,Base64.DEFAULT);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));String(c.doFinal(b.copyOfRange(12,b.size))) } catch(_:Exception){clear();null} }
    fun clear(){prefs.edit().clear().commit()}
}
private fun keystoreKey():SecretKey {
    val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    return (ks.getKey("mammotion-session",null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder("mammotion-session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
    }.generateKey()
}
