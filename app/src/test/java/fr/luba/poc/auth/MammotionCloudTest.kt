package fr.luba.poc.auth

import android.content.Context
import android.util.Base64
import fr.luba.poc.auth.FakeMammotionServer.Companion.IOT
import fr.luba.poc.auth.FakeMammotionServer.Companion.TOKEN_PATH
import fr.luba.poc.auth.FakeMammotionServer.Companion.jwt
import fr.luba.poc.auth.FakeMammotionServer.Companion.ok
import fr.luba.poc.auth.FakeMammotionServer.Companion.session
import fr.luba.poc.protocol.Commands
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URLDecoder

/** Mammotion account HTTP client against a cloud simulated on a local ephemeral port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MammotionCloudTest {
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val server = FakeMammotionServer()
    private val store = FakeMammotionServer.store(context)
    private fun cloud(s: SessionStore = store) = MammotionCloud(s, server.open)
    private fun connected(refresh: String? = "refresh-1"): MammotionCloud { store.save(session(refresh = refresh).toString()); return cloud() }
    private val devicesPath = "/v1/user/device/page"
    private val records = ok(JSONObject().put("records", JSONArray().put(JSONObject().put("deviceName", "Luba-VS1").put("iotId", "iot1").put("productKey", "pk"))))
    private fun query(r: FakeMammotionServer.Request) = r.query.orEmpty().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @After fun close() = server.close()

    @Test fun `successful sign-in session encrypted and kept`() {
        server.json(TOKEN_PATH, ok(session()))
        val api = cloud()
        assertFalse(api.connected())
        api.login("moi@example.com", "secret")
        assertTrue(api.connected())
        val r = server.requests.single()
        assertEquals("id.mammotion.com", r.host); assertEquals("POST", r.method)
        val q = query(r)
        assertEquals("moi@example.com", q["username"]); assertEquals("password", q["grant_type"])
        assertEquals("secret", String(Base64.decode(q["password"], Base64.DEFAULT)))
        assertTrue(r.headers["ma-signature"].orEmpty().matches(Regex("[0-9a-f]{64}")))
        assertNotNull(r.headers["ma-timestamp"]); assertNotNull(r.headers["ma-app-key"])
        assertNull("no token sent to the OAuth server", r.headers["authorization"])
        // Session read back when the app restarts, password absent from storage.
        val restarted = cloud(); assertTrue(restarted.connected()); assertEquals(1234, restarted.userAccount())
        val raw = context.getSharedPreferences("test_session", Context.MODE_PRIVATE).getString("session", null)!!
        assertFalse(raw.contains("access_token"))
    }

    @Test fun `rejected credentials without session or renewal`() {
        server.status(TOKEN_PATH, 401)
        val api = cloud()
        val e = assertThrows(IllegalStateException::class.java) { api.login("moi@example.com", "faux") }
        assertEquals("Mammotion : e-mail ou mot de passe refusé (code 401)", e.message)
        assertEquals(401, (e.cause as CloudResponseException).code)
        assertEquals(1, server.requests.size)
        assertFalse(api.connected()); assertNull(store.read())
        // Application refusal (HTTP 200, Mammotion code) and empty session.
        server.json(TOKEN_PATH, """{"code":10001,"msg":"mot de passe"}""", ok(JSONObject().put("access_token", "")))
        assertEquals(10001, assertThrows(CloudResponseException::class.java) { api.login("moi@example.com", "faux") }.code)
        assertEquals("Session absente", assertThrows(IllegalStateException::class.java) { api.login("moi@example.com", "x") }.message)
        assertFalse(api.connected()); assertNull(store.read())
        assertThrows(IllegalArgumentException::class.java) { api.login(" ", "x") }
        assertThrows(IllegalArgumentException::class.java) { api.login("moi@example.com", "") }
        assertEquals(3, server.requests.size)
    }

    @Test fun `expired token renewed once then request replayed`() {
        val api = connected()
        val fresh = jwt(tag = "b")
        server.on(devicesPath, { _ -> FakeMammotionServer.Reply(401, ByteArray(0)) }, { _ -> FakeMammotionServer.Reply(200, records.toByteArray()) })
        // Response without a new refresh_token: the old one is kept, like the rest of the session.
        server.json(TOKEN_PATH, ok(JSONObject().put("access_token", fresh).put("refresh_token", "")))
        assertEquals("Luba-VS1", api.devices().getJSONObject(0).getString("deviceName"))
        assertEquals(listOf(devicesPath, TOKEN_PATH, devicesPath), server.paths())
        val (first, refresh, retry) = server.requests
        assertEquals("Bearer ${jwt()}", first.headers["authorization"])
        assertEquals("refresh_token", query(refresh)["grant_type"]); assertEquals("refresh-1", query(refresh)["refresh_token"])
        assertNull(refresh.headers["authorization"])
        assertEquals("Bearer $fresh", retry.headers["authorization"])
        val saved = JSONObject(store.read()!!)
        assertEquals(fresh, saved.getString("access_token")); assertEquals("refresh-1", saved.getString("refresh_token"))
        assertEquals(1234, api.userAccount())
    }

    @Test fun `code 460 invalid token in a 200 response also renews`() {
        val api = connected()
        server.json(devicesPath, """{"code":460}""", records)
        server.json(TOKEN_PATH, ok(JSONObject().put("access_token", jwt(tag = "c")).put("refresh_token", "refresh-2")))
        assertEquals(1, api.devices().length())
        assertEquals("refresh-2", JSONObject(store.read()!!).getString("refresh_token"))
    }

    @Test fun `refused or impossible renewal signs the account out`() {
        var api = connected()
        server.status(devicesPath, 401)
        server.status(TOKEN_PATH, 401)
        assertEquals("Mammotion : session expirée, reconnectez votre compte", assertThrows(IllegalStateException::class.java) { api.devices() }.message)
        assertFalse(api.connected()); assertNull(store.read())
        // Without refresh_token: no renewal request.
        api = connected(refresh = null); server.requests.clear()
        assertThrows(IllegalStateException::class.java) { api.devices() }
        assertEquals(listOf(devicesPath), server.paths()); assertFalse(api.connected())
    }

    @Test fun `persistent 401 after renewal without loop`() {
        val api = connected()
        server.status(devicesPath, 401)
        server.json(TOKEN_PATH, ok(JSONObject().put("access_token", jwt(tag = "d"))))
        assertEquals(401, assertThrows(CloudResponseException::class.java) { api.devices() }.code)
        assertEquals(listOf(devicesPath, TOKEN_PATH, devicesPath), server.paths())
    }

    @Test fun `malformed or too large responses without session loss`() {
        val api = connected()
        server.json(devicesPath, "<html>maintenance</html>")
        assertThrows(JSONException::class.java) { api.devices() }
        server.json(devicesPath, ok(JSONObject()))
        assertThrows(JSONException::class.java) { api.devices() }
        server.json(devicesPath, """{"code":500,"msg":"erreur"}""")
        assertEquals(500, assertThrows(CloudResponseException::class.java) { api.devices() }.code)
        server.on(devicesPath, { _ -> FakeMammotionServer.Reply(200, ByteArray(4 * 1024 * 1024 + 1) { ' '.code.toByte() }) })
        assertEquals("Réponse trop grande", assertThrows(IllegalStateException::class.java) { api.devices() }.message)
        // Redirect never followed (the token does not go elsewhere).
        server.status(devicesPath, 302)
        assertEquals(302, assertThrows(CloudResponseException::class.java) { api.devices() }.code)
        assertTrue(api.connected()); assertNotNull(store.read())
    }

    @Test fun `no network without invalidating the session`() {
        val api = connected()
        server.close()
        assertThrows(IOException::class.java) { api.devices() }
        assertTrue(api.connected()); assertNotNull(store.read())
    }

    @Test fun `non-Mammotion IoT domain refused before any request`() {
        for (iot in listOf("https://evil.example.com", "http://api-iot.mammotion.com", "https://user@api-iot.mammotion.com", "https://mammotion.com.evil.net")) {
            store.save(session(access = jwt(iot = iot)).toString())
            assertEquals(iot, "Domaine Mammotion HTTPS requis", assertThrows(IllegalArgumentException::class.java) { cloud().devices() }.message)
        }
        assertTrue(server.requests.isEmpty())
        store.clear()
        assertEquals("Connectez votre compte", assertThrows(IllegalStateException::class.java) { cloud().devices() }.message)
    }

    @Test fun `no native mowing through the account, native return only on explicit choice`() {
        val api = connected()
        val c = Commands()
        server.json("/v1/mqtt/rpc/thing/service/invoke", ok(JSONObject()))
        val mowing = listOf(1, 2, 3, 4, 7).map(c::taskControl) + listOf(c.leaveDock(), c.stop(), c.manualBlades(true, 60))
        for (bytes in mowing) for (native in listOf(false, true))
            assertThrows(IllegalArgumentException::class.java) { api.invoke(bytes, "Luba-VS1", "iot1", nativeReturn = native) }
        assertThrows(IllegalArgumentException::class.java) { api.invoke(c.returnToDock(), "Luba-VS1", "iot1") }
        assertTrue("nothing was sent to the cloud", server.requests.isEmpty())
        api.invoke(c.returnToDock(), "Luba-VS1", "iot1", nativeReturn = true)
        api.invoke(c.cancelReturnToDock(), "Luba-VS1", "iot1")
        assertEquals(2, server.requests.size)
        val sent = server.requests.map { JSONObject(it.body) }
        assertEquals("api-iot.mammotion.com", server.requests[0].host)
        assertEquals("device_protobuf_sync_service", sent[0].getString("identifier")); assertEquals("iot1", sent[0].getString("iotId"))
        val nav = fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(Base64.decode(sent[0].getJSONObject("args").getString("content"), Base64.DEFAULT)).nav
        assertEquals(CloudReadOnly.NATIVE_RETURN_ACTION, nav.todevTaskctrl.action)
    }

    @Test fun `account application calls`() {
        val api = connected()
        server.json("/device-server/v1/device/wakeup", """{"code":0,"data":true}""", """{"code":0,"data":false}""")
        assertTrue(api.wakeUp("Luba-VS1")); assertFalse(api.wakeUp("Luba-VS1"))
        assertEquals("Luba-VS1", JSONObject(server.requests[0].body).getString("deviceName"))
        assertEquals("domestic.mammotion.com", server.requests[0].host)
        server.json("/device-server/v1/ota/device/upgrade", ok(JSONObject()))
        assertThrows(IllegalArgumentException::class.java) { api.startFirmwareUpdate("iot1", " ") }
        assertThrows(IllegalArgumentException::class.java) { api.startFirmwareUpdate("", "1.13.0") }
        api.startFirmwareUpdate("iot1", "1.13.0")
        val upgrade = JSONObject(server.requests.last().body)
        assertEquals("iot1", upgrade.getString("deviceId")); assertEquals("1.13.0", upgrade.getString("version"))
        server.json("/device-server/v1/devices/version/check", ok(JSONArray()))
        api.firmwareCheck("iot1")
        assertEquals("iot1", JSONObject(server.requests.last().body).getJSONArray("deviceIds").getString(0))
        server.json("/device-server/v1/device/work-report/page", ok(JSONObject().put("records", JSONArray())))
        api.workReports("Luba-VS1", 5)
        assertEquals(5, JSONObject(server.requests.last().body).getInt("pageSize"))
        server.json("/v1/mqtt/auth/jwt", ok(JSONObject().put("host", "mqtt.mammotion.com")))
        assertEquals("mqtt.mammotion.com", api.mqtt().getString("host"))
        assertEquals(IOT + "/v1/mqtt/auth/jwt", "https://" + server.requests.last().host + server.requests.last().path)
    }

    @Test fun `sign-out erases the session`() {
        val api = connected()
        api.logout()
        assertFalse(api.connected()); assertNull(store.read()); assertEquals(0, api.userAccount())
    }
}
