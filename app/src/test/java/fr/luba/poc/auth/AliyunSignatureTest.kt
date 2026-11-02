package fr.luba.poc.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Aliyun signatures against vectors computed with the reference code (`alibabacloud_apigateway_util` 0.0.2
 * `get_signature`, PyMammotion `CloudIOTGateway.sign`, `login_by_oauth` and `AliyunMQTTTransport._build_credentials`)
 * on fictitious values.
 */
class AliyunSignatureTest {
    private val secret = "1ba85698bb10e19c6437413b61ba3445"

    @Test fun apiGatewayRequestSignedLikeTheReference() {
        val body = """{"id":"req-1","params":{"pageSize":100,"pageNo":1},"request":{"apiVer":"1.0.8","language":"en-US","iotToken":"TOKEN-DEMO"},"version":"1.0"}"""
        val md5 = AliyunSignature.contentMd5(body)
        assertEquals("N+H1ZJnK8ziHR8hCVeVAKw==", md5)
        val headers = linkedMapOf("host" to "eu-central-1.api-iot.aliyuncs.com", "date" to "Fri, 01 Nov 2026 10:00:00 GMT", "x-ca-nonce" to "nonce-1",
            "x-ca-key" to "34231230", "x-ca-signaturemethod" to "HmacSHA256", "accept" to "application/json; charset=utf-8",
            "user-agent" to "ALIYUN-ANDROID-DEMO", "x-ca-timestamp" to "1793527200000000000", "content-type" to "application/octet-stream", "content-md5" to md5)
        val signed = AliyunSignature.gateway("POST", headers, "/uc/listBindingByAccount?x-ca-request-id=rid-1", secret)
        assertEquals("hcnjDdJQYmOa3dSYUGWHWRmGfoZiyioNZqJWODshw6U=", signed.signature)
        assertEquals("x-ca-key,x-ca-nonce,x-ca-signaturemethod,x-ca-timestamp", signed.signedHeaders)
    }

    @Test fun openAccountFormSignedOverTheRawValueWithTheVid() {
        val headers = mapOf("date" to "Fri, 01 Nov 2026 10:00:00 GMT", "x-ca-nonce" to "nonce-2", "x-ca-key" to "34231230",
            "x-ca-signaturemethod" to "HmacSHA256", "accept" to "application/json", "content-type" to "application/x-www-form-urlencoded; charset=utf-8",
            "user-agent" to "any", "vid" to "vid-1")
        val signed = AliyunSignature.gateway("POST", headers, """/api/prd/loginbyoauth.json?loginByOauthRequest={"a":1}""", secret)
        assertEquals("/u3ICTm4oyAz6izwAWH9iACgdrFx3/3XZKvxQUuDkNE=", signed.signature)
        assertEquals("vid,x-ca-key,x-ca-nonce,x-ca-signaturemethod", signed.signedHeaders)
    }

    @Test fun aepAndMqttSignaturesMatchPyMammotion() {
        assertEquals("9df2f8acf889bfea18d0969e8172c22ea5809ab6", AliyunSignature.aep("34231230", "ABCD1234", "SN-DEMO", "1793527200.5", secret))
        assertEquals("5fa0e4ac51c6dbae9bf5d933482ed062935c9ef4",
            AliyunSignature.mqttPassword("ABCD1234", "APPDEVICE", "a1APPPK", "1793527200", "DEVSECRET-DEMO"))
    }
}
