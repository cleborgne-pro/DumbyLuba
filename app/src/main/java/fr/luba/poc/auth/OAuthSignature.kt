package fr.luba.poc.auth
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
object OAuthSignature {
    fun sign(client:String,secret:String,timestamp:Long,compactJson:String):String {
        val md5=MessageDigest.getInstance("MD5").digest(secret.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(md5.toByteArray(),"HmacSHA256"))
        return mac.doFinal("$client$timestamp/oauth2/token$compactJson".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
