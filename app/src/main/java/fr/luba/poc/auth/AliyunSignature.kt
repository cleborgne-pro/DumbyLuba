package fr.luba.poc.auth

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signatures of Mammotion's older Aliyun IoT cloud, ported from PyMammotion (`aliyun/cloud_gateway.py`,
 * `aliyun/client.py`, `transport/aliyun_mqtt.py`) and `alibabacloud_apigateway_util` 0.0.2 (`get_signature`).
 */
internal object AliyunSignature {
    /** Headers left out of the signed list (`MOVE_HEADERS` of the API gateway util). */
    private val UNSIGNED_HEADERS = setOf("x-ca-signature", "x-ca-signature-headers", "accept", "content-md5", "content-type", "date", "host", "token", "user-agent")

    /** `x-ca-signature` and `x-ca-signature-headers` of an API gateway request. */
    data class Gateway(val signature: String, val signedHeaders: String)

    /**
     * API gateway signature: HMAC-SHA256 (Base64) of the method, `accept`, `content-md5`, `content-type`, `date`, the
     * other headers sorted by name (`name:value`, one per line) and [pathAndQuery] (query not URL-encoded).
     */
    fun gateway(method: String, headers: Map<String, String>, pathAndQuery: String, secret: String): Gateway {
        val signed = headers.filterKeys { it !in UNSIGNED_HEADERS }.toSortedMap()
        val toSign = listOf(method, headers["accept"].orEmpty(), headers["content-md5"].orEmpty(), headers["content-type"].orEmpty(),
            headers["date"].orEmpty(), signed.entries.joinToString("\n") { "${it.key}:${it.value}" }, pathAndQuery).joinToString("\n")
        return Gateway(Base64.getEncoder().encodeToString(hmac("HmacSHA256", secret, toSign)), signed.keys.joinToString(","))
    }

    /** `content-md5` of a request body: Base64 of the MD5 of its UTF-8 bytes. */
    fun contentMd5(body: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.toByteArray()))

    /** `authInfo.sign` of `/app/aepauth/handle`: HMAC-SHA1 (hex) of `appKey…clientId…deviceSn…timestamp…`. */
    fun aep(appKey: String, clientId: String, deviceSn: String, timestamp: String, secret: String): String =
        hex(hmac("HmacSHA1", secret, "appKey${appKey}clientId${clientId}deviceSn${deviceSn}timestamp$timestamp"))

    /** MQTT password of the app identity (`securemode=2,signmethod=hmacsha1`): HMAC-SHA1 (hex) keyed by the device secret. */
    fun mqttPassword(clientId: String, deviceName: String, productKey: String, timestamp: String, deviceSecret: String): String =
        hex(hmac("HmacSHA1", deviceSecret, "clientId${clientId}deviceName${deviceName}productKey${productKey}timestamp$timestamp"))

    private fun hmac(algorithm: String, key: String, data: String): ByteArray =
        Mac.getInstance(algorithm).apply { init(SecretKeySpec(key.toByteArray(), algorithm)) }.doFinal(data.toByteArray())

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
