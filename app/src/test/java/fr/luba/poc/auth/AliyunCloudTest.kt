package fr.luba.poc.auth

import android.content.Context
import fr.luba.poc.Clock
import fr.luba.poc.auth.FakeMammotionServer.Companion.jwt
import fr.luba.poc.auth.FakeMammotionServer.Companion.ok
import fr.luba.poc.protocol.Commands
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Aliyun cloud client against a simulated cloud on a local port (DumbyLuba#3): sign-in chain of PyMammotion, signed
 * requests, robots of the account, read-only commands, token renewal. Fictitious values only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AliyunCloudTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val server = AliyunFakeCloud.server()
    private val store = FakeMammotionServer.store(context, "aliyun_session")
    private val steps = CopyOnWriteArrayList<AliyunCloud.Step>()

    private fun aliyun(savedCode: String? = "AUTH-SAVED"): AliyunCloud {
        store.save(AliyunFakeCloud.session(savedCode).toString())
        return AliyunCloud(MammotionCloud(store, server.open), { "seed-DEMO" }, server.open).also { it.onStep = { s -> steps += s } }
    }
    private fun body(path: String, index: Int = 0) = JSONObject(server.requests.filter { it.path == path }[index].body)
    private fun form(r: FakeMammotionServer.Request) = r.body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @After fun close() { server.close(); Clock.reset() }

    @Test fun `sign-in chain follows PyMammotion and lists the account robots`() {
        val robots = aliyun().robots()
        assertEquals(listOf(CloudRobot("MA5000DEMO000001", "iot-DEMO-1", "a1FbaU4Bqk5", CloudKind.ALIYUN), CloudRobot("RTK-DEMO2", "iot-DEMO-2", "a1RTKDEMO00", CloudKind.ALIYUN)), robots)
        assertEquals(listOf(AliyunCloud.AUTHORIZATION_CODE, "/living/account/region/get", "/api/prd/connect.json", "/api/prd/loginbyoauth.json",
            "/app/aepauth/handle", "/account/createSessionByAuthCode", "/uc/listBindingByAccount"), steps.map { it.endpoint })
        // Fixed domains, then the region endpoints given by region/get.
        val hosts = server.requests.associate { it.path to it.host }
        assertEquals("api.link.aliyun.com", hosts["/living/account/region/get"]); assertEquals("sdk.openaccount.aliyun.com", hosts["/api/prd/connect.json"])
        assertEquals("living-account.eu-central-1.aliyuncs.com", hosts["/api/prd/loginbyoauth.json"])
        listOf("/app/aepauth/handle", "/account/createSessionByAuthCode", "/uc/listBindingByAccount").forEach { assertEquals(it, "eu-central-1.api-iot.aliyuncs.com", hosts[it]) }
        // The fresh authorization code feeds the region and the open account sign-in; the country selects the region.
        val region = body("/living/account/region/get").getJSONObject("params")
        assertEquals("AUTH-FRESH", region.getString("authCode")); assertEquals("DE", region.getString("countryCode"))
        val login = server.requests.single { it.path == "/api/prd/loginbyoauth.json" }
        assertEquals("VID-DEMO", login.headers["vid"])
        JSONObject(form(login).getValue("loginByOauthRequest")).let { assertEquals("AUTH-FRESH", it.getString("authCode")); assertEquals("DE", it.getString("country")) }
        assertEquals("SID-DEMO", body("/account/createSessionByAuthCode").getJSONObject("params").getJSONObject("request").getString("authCode"))
        assertEquals("IOT-TOKEN-1", body("/uc/listBindingByAccount").getJSONObject("request").getString("iotToken"))
        assertEquals("1.0.8", body("/uc/listBindingByAccount").getJSONObject("request").getString("apiVer"))
    }

    @Test fun `every request carries a valid gateway signature and the aep sign matches the identity`() {
        aliyun().robots()
        server.requests.filter { it.host.endsWith("aliyun.com") || it.host.endsWith("aliyuncs.com") }.forEach { r ->
            val signedNames = r.headers.getValue("x-ca-signature-headers").split(',')
            val signed = (signedNames + listOf("accept", "content-md5", "content-type", "date")).mapNotNull { n -> r.headers[n]?.let { n to it } }.toMap()
            val query = r.query?.let { q -> "?" + q.split('&').joinToString("&") { it.substringBefore('=') + "=" + URLDecoder.decode(it.substringAfter('='), "UTF-8") } }
                ?: "?" + form(r).entries.single().let { "${it.key}=${it.value}" }
            assertEquals(r.path, AliyunSignature.gateway("POST", signed, r.path + query, "1ba85698bb10e19c6437413b61ba3445").signature, r.headers["x-ca-signature"])
            r.headers["content-md5"]?.let { assertEquals(r.path, AliyunSignature.contentMd5(r.body), it) }
        }
        val auth = body("/app/aepauth/handle").getJSONObject("params").getJSONObject("authInfo")
        assertEquals(8, auth.getString("clientId").length); assertEquals(32, auth.getString("deviceSn").length)
        assertEquals(AliyunSignature.aep(AliyunCloud.APP_KEY, auth.getString("clientId"), auth.getString("deviceSn"), auth.getString("timestamp"),
            "1ba85698bb10e19c6437413b61ba3445"), auth.getString("sign"))
    }

    @Test fun `the identity is stable for one installation and account`() {
        aliyun().robots()
        val first = body("/app/aepauth/handle").getJSONObject("params").getJSONObject("authInfo")
        val again = aliyun(); again.signIn()
        val second = body("/app/aepauth/handle", 1).getJSONObject("params").getJSONObject("authInfo")
        assertEquals(first.getString("clientId"), second.getString("clientId")); assertEquals(first.getString("deviceSn"), second.getString("deviceSn"))
    }

    @Test fun `refused fresh code falls back on the code of the last sign-in`() {
        server.status("/authorization/code", 500)
        aliyun().signIn()
        assertEquals("AUTH-SAVED", body("/living/account/region/get").getJSONObject("params").getString("authCode"))
        assertEquals(AliyunCloud.Step(AliyunCloud.AUTHORIZATION_CODE, "id.mammotion.com", 500, null), steps.first())
    }

    @Test fun `no code at all stops before the Aliyun cloud`() {
        server.status("/authorization/code", 500)
        val cloud = aliyun(savedCode = null)
        try { cloud.signIn(); fail("signed in without a code") } catch (e: IllegalStateException) { assertTrue(e.message, e.message.orEmpty().startsWith("Mammotion")) }
        assertFalse(server.paths().contains("/living/account/region/get"))
    }

    @Test fun `a region endpoint outside Aliyun is refused before any request to it`() {
        server.json("/living/account/region/get", AliyunFakeCloud.region(apiGateway = "api.example.com"))
        try { aliyun().signIn(); fail("untrusted endpoint accepted") } catch (e: IllegalArgumentException) { assertEquals("Domaine Aliyun HTTPS requis", e.message) }
        assertTrue(server.requests.none { it.host == "api.example.com" })
    }

    @Test fun `commands keep the read-only barrier and a refused token signs in again once`() {
        val cloud = aliyun()
        try { cloud.invoke(Commands().returnToDock(), "iot-DEMO-1"); fail("native return sent without the explicit choice") } catch (_: IllegalArgumentException) { }
        assertFalse(server.paths().contains("/thing/service/invoke"))
        server.json("/thing/service/invoke", """{"code":460,"message":"iotToken expired"}""", """{"code":200,"data":{}}""")
        server.json("/account/createSessionByAuthCode", AliyunFakeCloud.sessionReply("IOT-TOKEN-1"), AliyunFakeCloud.sessionReply("IOT-TOKEN-2"))
        val sync = Commands().sync(syncType = 3)
        cloud.invoke(sync, "iot-DEMO-1")
        val invokes = server.requests.filter { it.path == "/thing/service/invoke" }.map { JSONObject(it.body) }
        assertEquals(listOf("IOT-TOKEN-1", "IOT-TOKEN-2"), invokes.map { it.getJSONObject("request").getString("iotToken") })
        val params = invokes.last().getJSONObject("params")
        assertEquals("device_protobuf_sync_service", params.getString("identifier")); assertEquals("iot-DEMO-1", params.getString("iotId"))
        assertTrue(Base64.getDecoder().decode(params.getJSONObject("args").getString("content")).contentEquals(sync))
    }

    @Test fun `an IoT token close to expiry is refreshed without a new sign-in`() {
        val start = 1_793_527_200_000L
        Clock.wall = { start }
        val cloud = aliyun()
        cloud.signIn()
        Clock.wall = { start + 72_000_000L - 1_800_000L }
        server.json("/account/checkOrRefreshSession", AliyunFakeCloud.sessionReply("IOT-TOKEN-R"))
        assertEquals("IOT-TOKEN-R", cloud.session().iotToken)
        assertEquals(1, server.paths().count { it == "/account/createSessionByAuthCode" })
        assertEquals("REFRESH-DEMO", body("/account/checkOrRefreshSession").getJSONObject("params").getJSONObject("request").getString("refreshToken"))
    }

    @Test fun `logged steps carry neither token, code nor identifier`() {
        aliyun().robots()
        val text = steps.joinToString { it.fields.toString() }
        listOf("AUTH-", "SID-", "IOT-TOKEN", "REFRESH-DEMO", "DEVSECRET", "VID-DEMO", "iot-DEMO", "MA5000DEMO").forEach { assertFalse(it, text.contains(it)) }
    }
}

/** Simulated Aliyun cloud (and the Mammotion authorization code) with fictitious replies. */
internal object AliyunFakeCloud {
    fun session(savedCode: String?) = JSONObject().put("access_token", jwt()).put("refresh_token", "refresh-1")
        .apply { if (savedCode != null) put("authorization_code", savedCode) }
        .put("userInformation", JSONObject().put("userAccount", "1234").put("domainAbbreviation", "DE"))

    fun region(apiGateway: String = "eu-central-1.api-iot.aliyuncs.com") = JSONObject().put("code", 200).put("data", JSONObject()
        .put("apiGatewayEndpoint", apiGateway).put("oaApiGatewayEndpoint", "living-account.eu-central-1.aliyuncs.com").put("regionId", "eu-central-1")
        .put("mqttEndpoint", "public.itls.eu-central-1.aliyuncs.com:1883").put("shortRegionId", "EU").put("pushChannelEndpoint", "x").put("regionEnglishName", "Germany")).toString()

    fun sessionReply(token: String) = JSONObject().put("code", 200).put("data", JSONObject().put("identityId", "ID-DEMO").put("iotToken", token)
        .put("iotTokenExpire", 72_000).put("refreshToken", "REFRESH-DEMO").put("refreshTokenExpire", 720_000)).toString()

    fun robots() = JSONObject().put("code", 200).put("data", JSONObject().put("total", 2).put("pageNo", 1).put("pageSize", 100).put("data", JSONArray()
        .put(JSONObject().put("deviceName", "MA5000DEMO000001").put("iotId", "iot-DEMO-1").put("productKey", "a1FbaU4Bqk5").put("owned", 1).put("status", 1))
        .put(JSONObject().put("deviceName", "RTK-DEMO2").put("iotId", "iot-DEMO-2").put("productKey", "a1RTKDEMO00").put("owned", 1).put("status", 1))
        .put(JSONObject().put("deviceName", "").put("iotId", "iot-DEMO-3")))).toString()

    /** Fake server answering the whole chain; tests override single routes. */
    fun server() = FakeMammotionServer().also(::routes)

    fun routes(server: FakeMammotionServer) = server.apply {
        json("/authorization/code", ok(JSONObject().put("code", "AUTH-FRESH")))
        json("/living/account/region/get", region())
        json("/api/prd/connect.json", """{"data":{"vid":"VID-DEMO","code":1,"data":{"device":{"data":{"deviceId":"DEVID-DEMO"}}}},"success":"true","api":"connect"}""")
        json("/api/prd/loginbyoauth.json", """{"data":{"code":1,"data":{"loginSuccessResult":{"sid":"SID-DEMO"}}},"success":"true"}""")
        json("/app/aepauth/handle", """{"code":200,"data":{"productKey":"a1APPPK0000","deviceName":"APPDEVICE-DEMO","deviceSecret":"DEVSECRET-DEMO"}}""")
        json("/account/createSessionByAuthCode", sessionReply("IOT-TOKEN-1"))
        json("/uc/listBindingByAccount", robots())
        json("/thing/service/invoke", """{"code":200,"data":{}}""")
    }
}
