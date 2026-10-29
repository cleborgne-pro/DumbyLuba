package fr.luba.poc.auth

import android.app.Application
import android.os.Looper
import android.util.Base64
import fr.luba.poc.auth.FakeMammotionServer.Companion.IOT
import fr.luba.poc.auth.FakeMammotionServer.Companion.TOKEN_PATH
import fr.luba.poc.auth.FakeMammotionServer.Companion.ok
import fr.luba.poc.auth.FakeMammotionServer.Companion.session
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.MctrlSysProto.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Account screen: sign-in, robots, reading and native return through the account, against a simulated HTTP cloud
 * (local ephemeral port) and fabricated MQTT events. Mainly checks that no mowing command goes through the cloud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudViewModelTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val server = FakeMammotionServer()
    private val key = FakeMammotionServer.testKey()
    private val sessionStore = FakeMammotionServer.store(app, "vm_session", key)
    private val accounts = AccountStore(FakeMammotionServer.store(app, "vm_account", key))
    /** MQTT links opened by the model, in order. */
    private val links = CopyOnWriteArrayList<Channel<CloudViewModel.CloudEvent>>()
    private val pendingEvents = mutableListOf<ByteArray>()
    private val invokePath = "/v1/mqtt/rpc/thing/service/invoke"
    private val devicesPath = "/v1/user/device/page"

    /** Local mode read by the model ([AccountMode] by default, false here). */
    private var localOnly = false
    private fun model() = CloudViewModel(app, MammotionCloud(sessionStore, server.open), accounts, { localOnly }) { _, _ ->
        val events = Channel<CloudViewModel.CloudEvent>(64)
        synchronized(pendingEvents) { pendingEvents.forEach { events.trySend(CloudViewModel.CloudEvent.Proto(it)) }; pendingEvents.clear() }
        links += events
        CloudViewModel.MqttLink(events)
    }
    /** Report to receive as soon as the next MQTT connection opens. */
    private fun preload(vararg bytes: ByteArray) = synchronized(pendingEvents) { pendingEvents += bytes }
    private fun push(bytes: ByteArray) = assertTrue(links.last().trySend(CloudViewModel.CloudEvent.Proto(bytes)).isSuccess)
    private fun report(status: Int, charge: Int = 0, battery: Int = 80, position: Boolean = false) = LubaMsg.newBuilder()
        .setMsgtype(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS).setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
            .setDev(rpt_dev_status.newBuilder().setSysStatus(status).setChargeState(charge).setBatteryVal(battery))
            .apply { if (position) addLocations(rpt_dev_location.newBuilder().setRealPosX(12_500).setRealPosY(-3_000).setPosType(4)) }))
        .build().toByteArray()
    private fun robots(vararg rows: Triple<String, String, String>) = ok(JSONObject().put("records", JSONArray().apply {
        rows.forEach { (name, id, pk) -> put(JSONObject().put("deviceName", name).put("iotId", id).put("productKey", pk)) } }))
    /** Protobuf commands sent through the cloud, decoded. */
    private fun sent() = server.requests.filter { it.path == invokePath }.map {
        LubaMsg.parseFrom(Base64.decode(JSONObject(it.body).getJSONObject("args").getString("content"), Base64.DEFAULT)) }
    private fun navActions() = sent().filter { it.hasNav() && it.nav.hasTodevTaskctrl() }.map { it.nav.todevTaskctrl.action }

    private fun until(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out: $what")
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun connected(): CloudViewModel {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots(Triple("Luba-VS1", "iot1", "pk1")))
        server.json(invokePath, ok(JSONObject()))
        return model().also { vm -> vm.refresh(); until("robots") { vm.state.value.robots.isNotEmpty() && !vm.state.value.busy } }
    }

    @After fun close() = server.close()

    @Test fun `remembered sign-in then list of the account LUBA`() {
        server.json(TOKEN_PATH, ok(session()))
        server.json(devicesPath, robots(Triple("Luba-VS1", "iot1", "pk1"), Triple("Yuka-02", "iot2", "pk2"), Triple("Spino-S1-04", "iot4", "pk4"), Triple("LUBA-2", "iot3", "pk1")))
        val vm = model()
        assertFalse(vm.state.value.connected); assertEquals("Compte non connecté", vm.state.value.status)
        vm.login(" moi@example.com ", "secret")
        until("sign-in") { !vm.state.value.busy && vm.state.value.robots.isNotEmpty() }
        val s = vm.state.value
        assertTrue(s.connected); assertTrue(s.accountSaved); assertEquals("moi@example.com", s.accountEmail)
        assertEquals("Mowers only (Yuka included, pool cleaner excluded)", listOf("Luba-VS1", "Yuka-02", "LUBA-2"), s.robots.map { it.name })
        assertEquals("iot1", s.selected?.id); assertEquals("3 LUBA dans le compte", s.status)
        assertEquals(SavedAccount("moi@example.com", "secret"), accounts.read())
        // Restart: session and credentials kept.
        val restarted = model().state.value
        assertTrue(restarted.connected); assertTrue(restarted.accountSaved); assertEquals("Session sécurisée conservée · actualiser les robots", restarted.status)
    }

    @Test fun `sign-in without remembering erases the previous credentials`() {
        accounts.save(SavedAccount("ancien@example.com", "x"))
        server.json(TOKEN_PATH, ok(session()))
        server.json(devicesPath, robots())
        val vm = model()
        assertTrue(vm.state.value.accountSaved)
        vm.login("moi@example.com", "secret", rememberAccount = false)
        until("sign-in") { !vm.state.value.busy && vm.state.value.status == "Aucun appareil dans le compte" }
        assertFalse(vm.state.value.accountSaved); assertNull(accounts.read()); assertTrue(vm.state.value.connected)
        assertNull(vm.state.value.selected)
    }

    @Test fun `LUBA 1 listed by its product key whatever its account name, other devices reported as unrecognized`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots(Triple("MA5000H23A0012345", "iot5", "a1FbaU4Bqk5"), Triple("RTK-AB1234", "iot6", "a1qXkZ5P39W")))
        val vm = model()
        vm.refresh()
        until("robots") { !vm.state.value.busy && vm.state.value.robots.isNotEmpty() }
        assertEquals(listOf("MA5000H23A0012345"), vm.state.value.robots.map { it.name }); assertEquals("1 LUBA dans le compte", vm.state.value.status)
        server.json(devicesPath, robots(Triple("RTK-AB1234", "iot6", "a1qXkZ5P39W")))
        vm.refresh()
        until("no mower") { !vm.state.value.busy && vm.state.value.robots.isEmpty() }
        assertTrue(vm.state.value.status, vm.state.value.status.startsWith("1 appareil dans le compte, aucun reconnu comme tondeuse Mammotion"))
    }

    @Test fun `LUBA 1 with an empty account name and a spaced key is listed, skipped devices logged without name nor iotId`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots(Triple("", "iot-luba1", " a1FbaU4Bqk5 "), Triple("", "iot-SECRET-0", "zzEmpty"),
            Triple("AB", "iot-SECRET-1", "zzShort"), Triple("XYZ", "iot-SECRET-2", "zzThree"),
            Triple("RTK-AB12345678", "iot-SECRET-3", "a1qXkZ5P39W")))
        val vm = model()
        vm.refresh()
        until("robots") { !vm.state.value.busy && vm.state.value.robots.isNotEmpty() }
        assertEquals(listOf("iot-luba1"), vm.state.value.robots.map { it.id }); assertEquals("iot-luba1", vm.state.value.selected?.id)
        assertEquals("1 LUBA dans le compte", vm.state.value.status)
        val diagnostics = java.io.File(app.filesDir, "diagnostics")
        fun skipped() = diagnostics.walkTopDown().filter { it.isFile && it.extension == "jsonl" }
            .flatMap { it.readLines().asSequence() }.map { JSONObject(it) }.filter { it.getString("event") == "cloud_device_skipped" }.toList()
        until("skipped devices logged") { skipped().size == 4 }
        val lines = skipped()
        lines.forEach { line -> (0..3).forEach { assertFalse(line.toString(), line.toString().contains("iot-SECRET-$it")) } }
        lines.forEach { assertFalse(it.toString(), it.toString().contains("RTK-AB12345678")); assertFalse(it.toString(), it.toString().contains("XYZ")) }
        assertEquals(listOf(Triple("zzEmpty", "", 0), Triple("zzShort", "A", 2), Triple("zzThree", "X", 3), Triple("a1qXkZ5P39W", "RTK-AB", 14)),
            lines.map { it.getJSONObject("fields") }.map { Triple(it.getString("productKey"), it.getString("namePrefix"), it.getInt("nameLength")) })
    }

    @Test fun `empty device page, robot owned on the older Aliyun cloud, explained and logged anonymously`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, ok(JSONObject().put("records", JSONArray()).put("total", 0)))
        server.json(MammotionCloud.DEVICE_LIST, ok(JSONArray()
            .put(JSONObject().put("iotId", "iot-SECRET-1").put("deviceName", "MA5000DEMO000001").put("deviceType", "LUBA").put("productKey", "a1FbaU4Bqk5"))
            .put(JSONObject().put("iotId", "iot-SECRET-2").put("deviceName", "RTK-DEMO000002").put("deviceType", "RTK"))))
        val vm = model()
        vm.refresh()
        until("Aliyun status") { !vm.state.value.busy && vm.state.value.status.startsWith("Votre LUBA est sur l'ancien cloud Mammotion (Aliyun)") }
        assertTrue(vm.state.value.robots.isEmpty())
        val list = server.requests.single { it.path == MammotionCloud.DEVICE_LIST }
        assertEquals("GET", list.method); assertEquals("domestic.mammotion.com", list.host); assertEquals("1", list.headers["client-type"])
        assertTrue(list.headers["authorization"].orEmpty().startsWith("Bearer "))
        until("device page and owned devices logged") { logged("cloud_device_page").isNotEmpty() && logged("cloud_device_list").isNotEmpty() }
        val page = logged("cloud_device_page").single().getJSONObject("fields")
        assertEquals("api-iot.mammotion.com", page.getString("host")); assertEquals(200, page.getInt("httpCode"))
        assertEquals(0, page.getInt("apiCode")); assertEquals(0, page.getInt("total")); assertEquals(0, page.getInt("records"))
        val owned = logged("cloud_device_list").single()
        assertFalse(owned.toString(), owned.toString().contains("SECRET") || owned.toString().contains("DEMO0000") || owned.toString().contains("a1FbaU4Bqk5"))
        assertEquals(2, owned.getJSONObject("fields").getInt("devices")); assertEquals(1, owned.getJSONObject("fields").getInt("missingFromPage"))
        assertEquals("a1", owned.getJSONObject("fields").getJSONArray("productKeyPrefixes").getString(0))
    }

    @Test fun `empty device page and owned devices unreadable keep the current status, refused page names its endpoint`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, ok(JSONObject().put("records", JSONArray())))
        server.status(MammotionCloud.DEVICE_LIST, 500)
        val vm = model()
        vm.refresh()
        until("empty account") { !vm.state.value.busy && vm.state.value.status == "Aucun appareil dans le compte" }
        until("owned devices failure logged") { logged("cloud_device_list").isNotEmpty() }
        val failure = logged("cloud_device_list").single().getJSONObject("fields")
        assertEquals(500, failure.getInt("httpCode")); assertEquals(MammotionCloud.DEVICE_LIST, failure.getString("endpoint"))
        assertEquals("domestic.mammotion.com", failure.getString("host"))
        // Refused device page (code seen in DumbyLuba#3): the error names the endpoint, logged with the domain.
        server.json(devicesPath, """{"code":40400,"msg":"not found"}""")
        vm.refresh()
        until("refusal") { !vm.state.value.busy && vm.state.value.status.startsWith("Échec cloud") }
        assertEquals("Échec cloud : Mammotion HTTP/API 40400 (/v1/user/device/page)", vm.state.value.status)
        until("refused page logged") { logged("cloud_device_page").size == 2 }
        val refused = logged("cloud_device_page").last().getJSONObject("fields")
        assertEquals(40400, refused.getInt("apiCode")); assertEquals(200, refused.getInt("httpCode")); assertEquals("api-iot.mammotion.com", refused.getString("host"))
    }

    /** Lines of the diagnostics log of [kind]. */
    private fun logged(kind: String) = java.io.File(app.filesDir, "diagnostics").walkTopDown().filter { it.isFile && it.extension == "jsonl" }
        .flatMap { it.readLines().asSequence() }.map { JSONObject(it) }.filter { it.getString("event") == kind }.toList()

    @Test fun `rejected credentials or missing network shown without session`() {
        server.status(TOKEN_PATH, 401)
        val vm = model()
        vm.login("moi@example.com", "faux")
        until("failure") { vm.state.value.status.startsWith("Échec cloud") && !vm.state.value.busy }
        assertEquals("Échec cloud : Mammotion : e-mail ou mot de passe refusé (code 401)", vm.state.value.status)
        assertFalse(vm.state.value.connected); assertNull(accounts.read()); assertNull(sessionStore.read())
        server.close()
        vm.login("moi@example.com", "secret")
        until("network") { vm.state.value.status == "Échec cloud : réseau indisponible (ConnectException)" && !vm.state.value.busy }
        assertFalse(vm.state.value.connected)
    }

    @Test fun `session expired during refresh asks to sign in again`() {
        val vm = connected()
        server.status(devicesPath, 401); server.status(TOKEN_PATH, 401)
        vm.refresh()
        until("expiration") { !vm.state.value.busy && vm.state.value.status.startsWith("Échec") }
        assertEquals("Échec cloud : Mammotion : session expirée, reconnectez votre compte", vm.state.value.status)
        assertFalse(vm.state.value.connected); assertNull(sessionStore.read())
    }

    @Test fun `session revoked by another sign-in renewed with the saved account`() {
        accounts.save(SavedAccount("moi@example.com", "secret"))
        val vm = connected()
        server.on(devicesPath, { _ -> FakeMammotionServer.Reply(401, ByteArray(0)) },
            { _ -> FakeMammotionServer.Reply(200, robots(Triple("Luba-VS1", "iot1", "pk1")).toByteArray()) })
        server.on(TOKEN_PATH, { _ -> FakeMammotionServer.Reply(401, ByteArray(0)) }, { _ -> FakeMammotionServer.Reply(200, ok(session()).toByteArray()) })
        vm.refresh()
        until("new sign-in") { !vm.state.value.busy && vm.state.value.status == "1 LUBA dans le compte" }
        assertTrue(vm.state.value.connected); assertNotNull(sessionStore.read())
    }

    @Test fun `sign-out then forgetting the credentials`() {
        accounts.save(SavedAccount("moi@example.com", "secret"))
        val vm = connected()
        vm.logout()
        var s = vm.state.value
        assertFalse(s.connected); assertTrue(s.robots.isEmpty()); assertNull(s.selected); assertEquals("Session supprimée", s.status)
        assertTrue("credentials kept to sign in again", s.accountSaved); assertNull(sessionStore.read())
        vm.forgetAccount()
        s = vm.state.value
        assertFalse(s.accountSaved); assertEquals("", s.accountEmail); assertNull(accounts.read())
        assertEquals("Compte et session sauvegardés supprimés", s.status)
    }

    @Test fun `read-only account reading history firmware position and battery`() {
        val vm = connected()
        server.json("/device-server/v1/device/work-report/page", ok(JSONObject().put("records", JSONArray().put(JSONObject()
            .put("workName", "Avant").put("workType", 2).put("workResult", 5).put("startWorkTime", 1760000000)))))
        server.json("/device-server/v1/devices/version/check", ok(JSONArray().put(JSONObject().put("deviceId", "iot1")
            .put("currentVersion", "1.12.4").put("upgradeable", true).put("productVersionInfoVo", JSONObject().put("releaseVersion", "1.13.0")))))
        preload(report(11, charge = 1, battery = 64, position = true))
        vm.refreshAccount()
        until("reading") { !vm.state.value.busy && vm.state.value.status.startsWith("Position et batterie") }
        val s = vm.state.value
        assertEquals("Position et batterie API reçues · Luba-VS1", s.status)
        assertEquals(listOf("Avant"), s.workRecords.map { it.name }); assertEquals("1.13.0", s.firmwareCheck?.latest)
        assertTrue(s.accountReadErrors.isEmpty()); assertNotNull(s.accountReadAt)
        assertEquals(64, s.telemetry.battery); assertEquals(1.25, s.telemetry.x!!, 1e-9); assertEquals(-0.3, s.telemetry.y!!, 1e-9)
        assertEquals(64, s.details.battery); assertNotNull(s.lastKnown)
        assertTrue("read-only", navActions().isEmpty())
        assertTrue(sent().all { it.msgtype in setOf(MsgCmdType.MSG_CMD_TYPE_ESP, MsgCmdType.MSG_CMD_TYPE_EMBED_SYS) })
    }

    @Test fun `partial reading account errors are listed`() {
        val vm = connected()
        server.status("/device-server/v1/device/work-report/page", 500)
        server.json("/device-server/v1/devices/version/check", ok(JSONArray()))
        preload(report(11, position = true))
        vm.refreshAccount()
        until("reading") { !vm.state.value.busy && vm.state.value.accountReadAt != null && vm.state.value.status.startsWith("Position") }
        assertEquals(listOf("Historique : code 500 (/device-server/v1/device/work-report/page)"), vm.state.value.accountReadErrors)
    }

    @Test fun `native return refused without robot or without account`() {
        val vm = model()
        vm.nativeReturnToBase()
        assertEquals(CloudReturnPhase.FAILED, vm.state.value.nativeReturn.phase)
        assertEquals("Aucun LUBA sélectionné dans le compte", vm.state.value.nativeReturn.status)
        // Session expired after reading the robots: the robot stays selected, the account is no longer connected.
        val linked = connected()
        server.status(devicesPath, 401); server.status(TOKEN_PATH, 401)
        linked.refresh(); until("expiration") { !linked.state.value.busy && !linked.state.value.connected }
        linked.nativeReturnToBase()
        assertEquals("Connectez votre compte Mammotion (onglet Moi)", linked.state.value.nativeReturn.status)
        assertTrue(links.isEmpty()); assertTrue(sent().isEmpty())
    }

    @Test fun `native return tracked until charging with a single return command`() {
        val vm = connected()
        preload(report(11))
        vm.nativeReturnToBase()
        until("request") { vm.state.value.nativeReturn.phase == CloudReturnPhase.REQUESTED }
        assertEquals(listOf(CloudReadOnly.NATIVE_RETURN_ACTION), navActions())
        push(report(14))
        until("return") { vm.state.value.nativeReturn.phase == CloudReturnPhase.RETURNING }
        // During tracking, no other account action starts (a single MQTT connection).
        vm.refresh(); assertFalse(vm.state.value.busy)
        push(report(11, charge = 1))
        until("docking") { vm.state.value.nativeReturn.phase == CloudReturnPhase.DOCKED }
        assertEquals("Retour natif terminé : contact de charge confirmé", vm.state.value.nativeReturn.status)
        assertEquals(listOf(CloudReadOnly.NATIVE_RETURN_ACTION), navActions())
        assertEquals(1, links.size); until("closing") { links.single().isClosedForSend }
    }

    @Test fun `robot already charging or already returning no command sent`() {
        val vm = connected()
        preload(report(11, charge = 1))
        vm.nativeReturnToBase()
        until("charging") { vm.state.value.nativeReturn.phase == CloudReturnPhase.DOCKED }
        assertEquals("Déjà sur la base : charge observée", vm.state.value.nativeReturn.status)
        preload(report(14))
        vm.nativeReturnToBase()
        until("already returning") { vm.state.value.nativeReturn.phase == CloudReturnPhase.RETURNING }
        push(report(11, charge = 2))
        until("docking") { vm.state.value.nativeReturn.phase == CloudReturnPhase.DOCKED }
        assertTrue(navActions().isEmpty())
    }

    @Test fun `return refused during an update or by the cloud`() {
        val vm = connected()
        preload(report(FirmwareUpdateTracker.UPGRADING))
        vm.nativeReturnToBase()
        until("refusal") { vm.state.value.nativeReturn.phase == CloudReturnPhase.FAILED }
        assertEquals("Retour natif refusé : mise à jour du robot en cours", vm.state.value.nativeReturn.status)
        server.status(invokePath, 500)
        vm.nativeReturnToBase()
        until("cloud refusal") { vm.state.value.nativeReturn.status == "Retour natif refusé : code 500 (/v1/mqtt/rpc/thing/service/invoke)" }
        assertTrue(navActions().isEmpty())
    }

    @Test fun `return cancellation through the account`() {
        val vm = connected()
        preload(report(11))
        vm.nativeReturnToBase()
        until("request") { vm.state.value.nativeReturn.phase == CloudReturnPhase.REQUESTED }
        push(report(14))
        until("return") { vm.state.value.nativeReturn.phase == CloudReturnPhase.RETURNING }
        vm.cancelNativeReturn()
        until("cancellation sent") { navActions().contains(CloudReadOnly.CANCEL_RETURN_ACTION) &&
            vm.state.value.nativeReturn.status == "Annulation du retour envoyée par le compte ; attente du robot" }
        push(report(11))
        until("cancelled") { vm.state.value.nativeReturn.phase == CloudReturnPhase.CANCELLED }
        assertEquals(listOf(CloudReadOnly.NATIVE_RETURN_ACTION, CloudReadOnly.CANCEL_RETURN_ACTION), navActions())
    }

    @Test fun `MQTT connection lost during the return resumed without resending the command`() {
        val vm = connected()
        preload(report(11))
        vm.nativeReturnToBase()
        until("request") { vm.state.value.nativeReturn.phase == CloudReturnPhase.REQUESTED }
        push(report(14))
        until("return") { vm.state.value.nativeReturn.phase == CloudReturnPhase.RETURNING }
        links.last().close(IllegalStateException("MQTT déconnecté"))
        until("reconnection") { links.size == 2 }
        assertEquals(CloudReturnPhase.RETURNING, vm.state.value.nativeReturn.phase)
        push(report(11, charge = 1))
        until("docking") { vm.state.value.nativeReturn.phase == CloudReturnPhase.DOCKED }
        assertEquals("a single return request", listOf(CloudReadOnly.NATIVE_RETURN_ACTION), navActions())
    }

    @Test fun `firmware update refused without account reading or by the cloud`() {
        val vm = connected()
        vm.startFirmwareUpdate()
        assertEquals("Mise à jour non lancée : Lisez d’abord le compte (Actualiser depuis le compte)", vm.state.value.firmwareUpdate.status)
        server.json("/device-server/v1/device/work-report/page", ok(JSONObject().put("records", JSONArray())))
        server.json("/device-server/v1/devices/version/check", ok(JSONArray().put(JSONObject().put("deviceId", "iot1")
            .put("currentVersion", "1.12.4").put("upgradeable", true).put("productVersionInfoVo", JSONObject().put("releaseVersion", "1.13.0")))))
        // Robot mowing (13): local refusal, no update request.
        preload(report(13, position = true))
        vm.refreshAccount()
        until("reading") { !vm.state.value.busy && vm.state.value.status.startsWith("Position") }
        vm.startFirmwareUpdate()
        assertTrue(vm.state.value.firmwareUpdate.status, vm.state.value.firmwareUpdate.status.startsWith("Mise à jour non lancée : Robot occupé"))
        preload(report(11, charge = 1, position = true))
        vm.refreshPosition()
        until("reading") { !vm.state.value.busy && vm.state.value.details.sysStatus == 11 }
        server.status("/device-server/v1/ota/device/upgrade", 500)
        vm.startFirmwareUpdate()
        until("refusal") { vm.state.value.firmwareUpdate.phase == FirmwarePhase.FAILED }
        assertEquals("Mise à jour refusée par le cloud : code 500 (/device-server/v1/ota/device/upgrade)", vm.state.value.firmwareUpdate.status)
        val upgrade = server.requests.single { it.path == "/device-server/v1/ota/device/upgrade" }
        assertEquals("1.13.0", JSONObject(upgrade.body).getString("version"))
    }

    @Test fun `camera without connected account failure message`() {
        val vm = model()
        vm.requestCamera()
        until("camera") { !vm.state.value.camera.busy && vm.state.value.camera.status.startsWith("Échec du flux vidéo") }
        assertNull(vm.state.value.camera.credentials)
        vm.stopCamera(); assertEquals("Caméra arrêtée", vm.state.value.camera.status)
    }

    @Test fun `selected robot kept then forgotten on sign-out`() {
        sessionStore.save(session().toString())
        server.json(devicesPath, robots(Triple("Luba-A", "iotA", "pk"), Triple("Luba-B", "iotB", "pk")))
        app.getSharedPreferences("mammotion_map_selection", android.content.Context.MODE_PRIVATE).edit().putString("robot", "iotB").commit()
        val vm = model()
        vm.refresh(); until("robots") { vm.state.value.robots.size == 2 && !vm.state.value.busy }
        assertEquals("iotB", vm.state.value.selected?.id)
        vm.logout()
        sessionStore.save(session().toString())
        val again = model()
        again.refresh(); until("robots") { again.state.value.robots.size == 2 && !again.state.value.busy }
        assertEquals("iotA", again.state.value.selected?.id)
    }

    private fun readyForUpdate(): CloudViewModel {
        val vm = connected()
        server.json("/device-server/v1/device/work-report/page", ok(JSONObject().put("records", JSONArray())))
        server.json("/device-server/v1/devices/version/check", ok(JSONArray().put(JSONObject().put("deviceId", "iot1")
            .put("currentVersion", "1.12.4").put("upgradeable", true).put("productVersionInfoVo", JSONObject().put("releaseVersion", "1.13.0")))))
        server.json("/device-server/v1/ota/device/upgrade", ok(JSONObject()))
        preload(report(11, charge = 1, position = true))
        vm.refreshAccount()
        until("reading") { !vm.state.value.busy && vm.state.value.status.startsWith("Position") }
        return vm
    }
    private fun property(json: String) = assertTrue(links.last().trySend(CloudViewModel.CloudEvent.Property(json)).isSuccess)

    @Test fun `firmware update started on request then tracked to the end`() {
        val vm = readyForUpdate()
        vm.startFirmwareUpdate()
        until("tracking") { links.size == 2 && vm.state.value.firmwareUpdate.phase == FirmwarePhase.REQUESTED }
        val upgrade = JSONObject(server.requests.single { it.path == "/device-server/v1/ota/device/upgrade" }.body)
        assertEquals("iot1", upgrade.getString("deviceId")); assertEquals("1.13.0", upgrade.getString("version"))
        property("""{"params":{"otaProgress":{"otaId":"o1","progress":40,"result":2}}}""")
        until("progress") { vm.state.value.firmwareUpdate.progress == 40 }
        assertEquals(FirmwarePhase.RUNNING, vm.state.value.firmwareUpdate.phase)
        // During the update: neither native return nor a second update.
        vm.nativeReturnToBase(); vm.startFirmwareUpdate()
        assertEquals(CloudReturnPhase.IDLE, vm.state.value.nativeReturn.phase)
        property("""{"params":{"otaProgress":{"otaId":"o1","progress":100,"result":0,"version":"1.13.0"}}}""")
        until("end") { vm.state.value.firmwareUpdate.phase == FirmwarePhase.DONE }
        assertEquals(1, server.requests.count { it.path == "/device-server/v1/ota/device/upgrade" })
        assertTrue("no movement command", navActions().isEmpty())
    }

    @Test fun `stopping the tracking lets the update continue`() {
        val vm = readyForUpdate()
        vm.startFirmwareUpdate()
        until("tracking") { links.size == 2 }
        vm.stopFirmwareFollow()
        assertEquals(FirmwarePhase.IDLE, vm.state.value.firmwareUpdate.phase)
        assertEquals("Suivi arrêté ; la mise à jour continue sur le robot", vm.state.value.firmwareUpdate.status)
        until("closing") { links.last().isClosedForSend }
    }

    @Test fun `local mode sends no request to the Mammotion cloud`() {
        sessionStore.save(session().toString())
        accounts.save(SavedAccount("moi@example.com", "secret"))
        server.json(TOKEN_PATH, ok(session()))
        server.json(devicesPath, robots(Triple("Luba-VS1", "iot1", "pk1")))
        localOnly = true
        val vm = model()
        // Session kept on the phone but not used: nothing connected, no account map.
        assertFalse(vm.state.value.connected); assertTrue(vm.state.value.map.shapes.isEmpty())
        assertEquals(AccountMode.message, vm.state.value.status)
        vm.login("moi@example.com", "secret"); vm.refresh(); vm.refreshPosition(); vm.refreshAccount()
        vm.requestCamera(); vm.cloudCameraCommand(CameraCommand.VIDEO_ON); vm.nativeReturnToBase(); vm.startFirmwareUpdate(); vm.followFirmwareUpdate()
        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertTrue("no request: ${server.paths()}", server.requests.isEmpty())
        assertFalse(vm.state.value.busy); assertFalse(vm.state.value.connected)
        assertEquals(CloudReturnPhase.FAILED, vm.state.value.nativeReturn.phase)
        assertEquals(AccountMode.message, vm.state.value.nativeReturn.status)
        assertFalse(vm.state.value.camera.busy); assertNull(vm.state.value.camera.credentials)
        assertTrue(vm.state.value.camera.status.endsWith(AccountMode.message))
        assertTrue(vm.state.value.firmwareUpdate.status.endsWith(AccountMode.message))
    }

    @Test fun `entering local mode deletes the session and keeps the saved credentials`() {
        accounts.save(SavedAccount("moi@example.com", "secret"))
        val vm = connected()
        assertTrue(vm.enterLocalMode())
        localOnly = true
        assertFalse(vm.state.value.connected); assertTrue(vm.state.value.robots.isEmpty()); assertTrue(vm.state.value.map.shapes.isEmpty())
        assertNull(sessionStore.read())
        assertTrue(vm.state.value.accountSaved); assertEquals(SavedAccount("moi@example.com", "secret"), accounts.read())
        assertEquals(AccountMode.message, vm.state.value.status)
        // Back to the account: signing in works again.
        localOnly = false
        vm.leaveLocalMode()
        assertEquals("Compte non connecté", vm.state.value.status)
        server.json(TOKEN_PATH, ok(session()))
        vm.login("moi@example.com", "secret")
        until("sign-in") { !vm.state.value.busy && vm.state.value.robots.isNotEmpty() }
        assertTrue(vm.state.value.connected)
    }

    @Test fun `local mode refused while a firmware update is followed`() {
        val vm = readyForUpdate()
        vm.startFirmwareUpdate()
        until("tracking") { links.size == 2 && vm.state.value.firmwareUpdate.active }
        assertFalse(vm.enterLocalMode())
        assertTrue(vm.state.value.connected); assertTrue(vm.state.value.firmwareUpdate.active)
        vm.stopFirmwareFollow()
    }
}
