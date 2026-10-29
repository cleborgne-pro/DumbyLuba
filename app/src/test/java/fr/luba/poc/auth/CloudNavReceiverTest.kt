package fr.luba.poc.auth

import android.app.Application
import android.os.Looper
import android.util.Base64
import fr.luba.poc.auth.FakeMammotionServer.Companion.TOKEN_PATH
import fr.luba.poc.auth.FakeMammotionServer.Companion.ok
import fr.luba.poc.auth.FakeMammotionServer.Companion.session
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlNavProto.MctlNav
import fr.luba.poc.proto.MctrlNavProto.NavGetHashListAck
import kotlinx.coroutines.channels.Channel
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Account reading per robot model (DumbyLuba#3) against the simulated cloud: LUBA 2 frames unchanged, LUBA 1 map
 * reading addressed to the main controller; owned devices read only for an empty device page; no account number,
 * credentials or sign-in query in the status, the errors or the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudNavReceiverTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val server = FakeMammotionServer()
    private val key = FakeMammotionServer.testKey()
    private val sessionStore = FakeMammotionServer.store(app, "nav_session", key)
    private val accounts = AccountStore(FakeMammotionServer.store(app, "nav_account", key))
    private val links = CopyOnWriteArrayList<Channel<CloudViewModel.CloudEvent>>()
    private val invokePath = "/v1/mqtt/rpc/thing/service/invoke"
    private val devicesPath = "/v1/user/device/page"
    /** Fictitious account number, long enough not to collide with other numbers of the log. */
    private val account = 87654321

    private fun model() = CloudViewModel(app, MammotionCloud(sessionStore, server.open), accounts, { false }) { _, _ ->
        val events = Channel<CloudViewModel.CloudEvent>(64)
        links += events
        CloudViewModel.MqttLink(events)
    }
    private fun robots(vararg rows: Triple<String, String, String>) = ok(JSONObject().put("records", JSONArray().apply {
        rows.forEach { (name, id, pk) -> put(JSONObject().put("deviceName", name).put("iotId", id).put("productKey", pk)) } }))
    private fun sent() = server.requests.filter { it.path == invokePath }.map {
        LubaMsg.parseFrom(Base64.decode(JSONObject(it.body).getJSONObject("args").getString("content"), Base64.DEFAULT)) }
    private fun until(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out: $what")
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun logLines() = java.io.File(app.filesDir, "diagnostics").walkTopDown().filter { it.isFile && it.extension == "jsonl" }
        .flatMap { it.readLines().asSequence() }.toList()
    private fun logged(kind: String) = logLines().map { JSONObject(it) }.filter { it.getString("event") == kind }
    private fun connected(name: String, pk: String): CloudViewModel {
        sessionStore.save(session(account = "$account").toString())
        server.json(devicesPath, robots(Triple(name, "iot1", pk)))
        server.json(invokePath, ok(JSONObject()))
        return model().also { vm -> vm.refresh(); until("robots") { vm.state.value.robots.isNotEmpty() && !vm.state.value.busy } }
    }
    private fun normalized(m: LubaMsg) = m.toBuilder().setTimestamp(0).build().toByteArray().joinToString("") { "%02x".format(it) }

    @After fun close() = server.close()

    @Test fun `LUBA 2 map reading through the account sends the same frames as before`() {
        val vm = connected("Luba-VS1", "a1iMygIwxFC")
        vm.fetch(vm.state.value.robots.single())
        until("map request") { sent().size >= 3 }
        val before = Commands(account)
        val expected = listOf(before.sync(syncType = 3), before.reports(false, account = true), before.mapHashes()).map { LubaMsg.parseFrom(it) }
        assertEquals(expected.map(::normalized), sent().take(3).map(::normalized))
        assertEquals(MsgDevice.DEV_NAVIGATION, sent()[2].rcver)
        vm.logout()
    }

    @Test fun `LUBA 1 map reading through the account addressed to the main controller and accepted by the cloud barrier`() {
        val vm = connected("MA5000H23A0012345", "a1FbaU4Bqk5")
        vm.fetch(vm.state.value.robots.single())
        until("map request") { sent().any { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV } }
        val nav = sent().filter { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV }
        assertEquals(listOf(MsgDevice.DEV_MAINCTL), nav.map { it.rcver }.distinct())
        // Robot answer echoing the account number: the hash list acknowledgement goes to the main controller too.
        until("MQTT link") { links.isNotEmpty() }
        val answer = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_NAV).setSender(MsgDevice.DEV_MAINCTL).setRcver(MsgDevice.DEV_MOBILEAPP)
            .setSubtype(account).setNav(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setPver(1).setSubCmd(0)
                .setTotalFrame(2).setCurrentFrame(1).setDataHash(11L).addDataCouple(101L).addDataCouple(102L))).build()
        assertTrue(links.last().trySend(CloudViewModel.CloudEvent.Proto(answer.toByteArray())).isSuccess)
        until("acknowledgement or element request") { sent().count { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV } >= 2 }
        assertEquals(listOf(MsgDevice.DEV_MAINCTL), sent().filter { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV }.map { it.rcver }.distinct())
        vm.logout()
        until("log written") { logged("cloud_map_rx").isNotEmpty() }
        val lines = logLines()
        assertTrue(lines.none { it.contains("$account") })
    }

    // --- Owned devices (older Aliyun cloud) ------------------------------------------------------------

    @Test fun `owned devices not read when the device page lists a robot or only another device`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots(Triple("Luba-VS1", "iot1", "a1iMygIwxFC")))
        server.json(MammotionCloud.DEVICE_LIST, ok(JSONArray().put(JSONObject().put("iotId", "iot9").put("deviceName", "MA5000DEMO9"))))
        val vm = model()
        vm.refresh()
        until("robots") { !vm.state.value.busy && vm.state.value.robots.isNotEmpty() }
        assertEquals("1 LUBA dans le compte", vm.state.value.status)
        // Only an RTK station on the page: not empty, the owned devices are not read either.
        server.json(devicesPath, robots(Triple("RTK-AB1234", "iot6", "a1qXkZ5P39W")))
        vm.refresh()
        until("no mower") { !vm.state.value.busy && vm.state.value.robots.isEmpty() }
        assertTrue(vm.state.value.status, vm.state.value.status.startsWith("1 appareil dans le compte, aucun reconnu comme tondeuse Mammotion"))
        assertTrue(server.requests.none { it.path == MammotionCloud.DEVICE_LIST })
        assertTrue(logged("cloud_device_list").isEmpty())
    }

    @Test fun `empty device page and only RTK stations or pool cleaners owned keep No device in the account`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots())
        server.json(MammotionCloud.DEVICE_LIST, ok(JSONArray()
            .put(JSONObject().put("iotId", "iot1").put("deviceName", "RTK-DEMO000001").put("deviceType", "RTK"))
            .put(JSONObject().put("iotId", "iot2").put("deviceName", "RBS03ADEMO02"))
            .put(JSONObject().put("iotId", "iot3").put("deviceName", "Spino-S1DEMO3").put("deviceType", "SPINO"))
            .put(JSONObject().put("iotId", "iot4").put("deviceName", "DEMO4").put("productSeries", "Pool cleaner"))))
        val vm = model()
        vm.refresh()
        until("owned devices logged") { !vm.state.value.busy && logged("cloud_device_list").isNotEmpty() }
        assertEquals("Aucun appareil dans le compte", vm.state.value.status)
        assertEquals(0, logged("cloud_device_list").single().getJSONObject("fields").getInt("missingFromPage"))
    }

    @Test fun `owned devices refused by the API or unreadable keep No device in the account`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots())
        // HTTP 200 with an API refusal, then a reply that is not JSON, then an unknown shape.
        server.json(MammotionCloud.DEVICE_LIST, """{"code":500,"msg":"error"}""", "not json", """{"code":0,"data":{"items":[{"deviceName":"Luba-VS1"}]}}""")
        val vm = model()
        repeat(3) { i ->
            vm.refresh()
            until("attempt $i") { !vm.state.value.busy && logged("cloud_device_list").size == i + 1 }
            assertEquals("attempt $i", "Aucun appareil dans le compte", vm.state.value.status)
            assertTrue(vm.state.value.robots.isEmpty())
        }
        assertEquals(500, logged("cloud_device_list").first().getJSONObject("fields").getInt("apiCode"))
        assertEquals(MammotionCloud.DEVICE_LIST, logged("cloud_device_list").first().getJSONObject("fields").getString("endpoint"))
    }

    @Test fun `Aliyun message shown for a robot owned but missing from the page`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots())
        server.json(MammotionCloud.DEVICE_LIST, ok(JSONArray()
            .put(JSONObject().put("iotId", "iot1").put("deviceName", "Luba-ABC123"))
            .put(JSONObject().put("iotId", "iot2").put("deviceName", "RTK-DEMO000002").put("deviceType", "RTK"))))
        val vm = model()
        vm.refresh()
        until("Aliyun") { !vm.state.value.busy && vm.state.value.status.contains("Aliyun") }
        assertTrue(vm.state.value.status.startsWith("Votre LUBA est sur l'ancien cloud Mammotion (Aliyun)"))
    }

    // --- Errors without credentials ---------------------------------------------------------------------

    @Test fun `sign-in refused, the error names the path without the query or the credentials`() {
        server.status(TOKEN_PATH, 500)
        val vm = model()
        vm.login("jean.dupont@example.fr", "MotDePasseSecret")
        until("failure") { !vm.state.value.busy && vm.state.value.status.startsWith("Échec cloud") }
        val status = vm.state.value.status
        assertTrue(status, status.contains("/oauth2/token"))
        val secrets = listOf("jean.dupont", "MotDePasseSecret", Base64.encodeToString("MotDePasseSecret".toByteArray(), Base64.NO_WRAP),
            "username", "password", "grant_type", "?")
        secrets.forEach { assertFalse("$it in $status", status.contains(it)) }
        // The query did reach the (simulated) server, so it was there to leak.
        assertTrue(server.requests.single { it.path == TOKEN_PATH }.query.orEmpty().contains("password="))
        logLines().forEach { line -> secrets.dropLast(1).forEach { assertFalse("$it in $line", line.contains(it)) } }
    }
}
