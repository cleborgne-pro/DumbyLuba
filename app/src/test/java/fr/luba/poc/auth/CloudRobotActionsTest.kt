package fr.luba.poc.auth

import fr.luba.poc.proto.DevNetProto.DrvUpgradeReport
import fr.luba.poc.proto.LubaMsgProto.*
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.protocol.Commands
import org.junit.Assert.*
import org.junit.Test

class CloudRobotActionsTest {
    private val c = Commands()
    private fun refused(bytes: ByteArray, nativeReturn: Boolean = false) =
        runCatching { CloudReadOnly.requireAllowed(bytes, nativeReturn) }.isFailure

    // --- Cloud barrier -----------------------------------------------------------------------

    @Test fun `native return accepted only on explicit choice`() {
        assertTrue(refused(c.returnToDock()))
        CloudReadOnly.requireAllowed(c.returnToDock(), nativeReturn = true)
        // Return cancellation: a stop command, always allowed.
        CloudReadOnly.requireAllowed(c.cancelReturnToDock())
    }

    @Test fun `mowing resume pause and leaving the base refused even in native return`() {
        for (action in listOf(1, 2, 3, 4, 7)) assertTrue("action $action", refused(c.taskControl(action), nativeReturn = true))
        assertTrue(refused(c.leaveDock(), nativeReturn = true))
        assertTrue(refused(c.stop(), nativeReturn = true))
        assertTrue(refused(c.manualBlades(true, 60), nativeReturn = true))
    }

    @Test fun `malformed native return refused`() {
        fun nav(ctrl: NavTaskCtrl.Builder, receiver: MsgDevice = MsgDevice.DEV_NAVIGATION) = LubaMsg.newBuilder()
            .setMsgtype(MsgCmdType.MSG_CMD_TYPE_NAV).setRcver(receiver)
            .setNav(MctlNav.newBuilder().setTodevTaskctrl(ctrl)).build().toByteArray()
        assertFalse(refused(nav(NavTaskCtrl.newBuilder().setType(1).setAction(5)), nativeReturn = true))
        assertTrue(refused(nav(NavTaskCtrl.newBuilder().setType(2).setAction(5)), nativeReturn = true))
        assertTrue(refused(nav(NavTaskCtrl.newBuilder().setType(1).setAction(5).setResult(1)), nativeReturn = true))
        assertTrue(refused(nav(NavTaskCtrl.newBuilder().setType(1).setAction(5), MsgDevice.DEV_MAINCTL), nativeReturn = true))
    }

    @Test fun `navigation receiver of the robot model accepted, the other refused, nothing more opened`() {
        // LUBA 1 (DumbyLuba#3): navigation on the main controller.
        val luba1 = Commands.forRobot("MA5000DEMO0001", "a1FbaU4Bqk5")
        assertEquals(MsgDevice.DEV_MAINCTL, luba1.navReceiver)
        fun allowed(bytes: ByteArray, receiver: MsgDevice, nativeReturn: Boolean = false) =
            runCatching { CloudReadOnly.requireAllowed(bytes, nativeReturn, receiver) }.isSuccess
        fun reads(commands: Commands) = listOf(commands.mapHashes(), commands.mapHashesAck(2, 1), commands.mapData(42L), commands.cancelReturnToDock())
        reads(luba1).zip(reads(c)).forEachIndexed { i, (luba1Bytes, luba2Bytes) ->
            assertTrue("LUBA 1 read #$i", allowed(luba1Bytes, MsgDevice.DEV_MAINCTL))
            assertFalse("LUBA 1 read #$i checked with the LUBA 2 receiver", allowed(luba1Bytes, MsgDevice.DEV_NAVIGATION))
            assertTrue("LUBA 2 read #$i", allowed(luba2Bytes, MsgDevice.DEV_NAVIGATION))
            assertFalse("LUBA 2 read #$i checked with the LUBA 1 receiver", allowed(luba2Bytes, MsgDevice.DEV_MAINCTL))
        }
        assertTrue(allowed(luba1.returnToDock(), MsgDevice.DEV_MAINCTL, nativeReturn = true))
        assertFalse(allowed(luba1.returnToDock(), MsgDevice.DEV_MAINCTL))
        // Same refusals as on a LUBA 2: native tasks, leaving the base, map writing, motion.
        listOf(luba1.taskControl(1), luba1.taskControl(2), luba1.taskControl(4), luba1.leaveDock(), luba1.startMapRecording(0),
            luba1.deleteMapElement(0, 42L), luba1.saveMapTask(), luba1.prepareMowing(fr.luba.poc.mowing.MowConfig(listOf(1))),
            luba1.movement(100, 0), luba1.manualBlades(true, 60))
            .forEachIndexed { i, bytes -> assertFalse("LUBA 1 refused #$i", allowed(bytes, MsgDevice.DEV_MAINCTL, nativeReturn = true)) }
        // A main-controller message that is not navigation stays refused.
        assertFalse(allowed(luba1.stop(), MsgDevice.DEV_MAINCTL, nativeReturn = true))
    }

    // --- Return tracking ------------------------------------------------------------------------

    @Test fun `return tracked until the charging contact`() {
        val t = CloudReturnTracker(1_000)
        assertEquals(CloudReturnPhase.REQUESTED, t.value.phase)
        // Report older than the request: ignored.
        t.accept(11, 0, 900)
        assertEquals(CloudReturnPhase.REQUESTED, t.value.phase)
        t.accept(14, 0, 5_000)
        assertEquals(CloudReturnPhase.RETURNING, t.value.phase)
        assertTrue(t.value.cancellable)
        t.tick(5_000 + CloudReturnTracker.START_TIMEOUT_MS * 2)
        assertEquals(CloudReturnPhase.RETURNING, t.value.phase)
        t.accept(11, 1, 200_000)
        assertEquals(CloudReturnPhase.DOCKED, t.value.phase)
        assertTrue(t.done)
    }

    @Test fun `return not started or interrupted`() {
        val idle = CloudReturnTracker(0)
        idle.accept(11, 0, 10_000)
        idle.tick(CloudReturnTracker.START_TIMEOUT_MS + 1)
        assertEquals(CloudReturnPhase.FAILED, idle.value.phase)

        val broken = CloudReturnTracker(0)
        broken.accept(14, 0, 5_000)
        broken.accept(19, 0, 20_000)
        assertEquals(CloudReturnPhase.FAILED, broken.value.phase)
        assertTrue(broken.value.status.contains("en pause"))
    }

    @Test fun `cancellation through the account`() {
        val t = CloudReturnTracker(0)
        t.accept(14, 0, 3_000)
        t.cancelRequested(4_000)
        assertTrue(t.value.active)
        t.accept(11, 0, 8_000)
        assertEquals(CloudReturnPhase.CANCELLED, t.value.phase)
    }

    @Test fun `return already in progress tracked without a new command`() {
        val t = CloudReturnTracker(0, alreadyReturning = true)
        assertEquals(CloudReturnPhase.RETURNING, t.value.phase)
        t.tick(CloudReturnTracker.START_TIMEOUT_MS + 1)
        assertEquals(CloudReturnPhase.RETURNING, t.value.phase)
    }

    // --- Firmware update -------------------------------------------------------------------

    @Test fun `otaProgress property flat object or string and Aliyun form`() {
        val flat = OtaProgressJson.parse("""{"method":"thing.event.property.post","params":{"otaProgress":{"otaId":"ota-42","version":"1.16.0.1101","progress":37,"result":2,"message":""}}}""")!!
        assertEquals(37, flat.progress); assertEquals("ota-42", flat.otaId); assertFalse(flat.complete); assertFalse(flat.failed)
        val text = OtaProgressJson.parse("""{"params":{"otaProgress":"{\"progress\":100,\"result\":0}"}}""")!!
        assertTrue(text.complete)
        val aliyun = OtaProgressJson.parse("""{"params":{"items":{"otaProgress":{"time":1,"value":{"progress":12}}}}}""")!!
        // Missing result: in progress, never success.
        assertEquals(12, aliyun.progress); assertFalse(aliyun.complete)
        assertNull(OtaProgressJson.parse("""{"params":{"batteryPercentage":50}}"""))
        assertNull(OtaProgressJson.parse("pas du json"))
        assertEquals(16, OtaProgressJson.deviceState("""{"params":{"deviceState":16}}"""))
        assertEquals(22, OtaProgressJson.deviceState("""{"params":{"items":{"deviceState":{"value":22}}}}"""))
    }

    @Test fun `monotonic progress and end on success`() {
        val t = FirmwareUpdateTracker("1.16.0", 0)
        assertEquals(FirmwarePhase.REQUESTED, t.value.phase)
        t.accept(OtaProgress(25, 2, otaId = "a", message = "dl fw:SocMidware, 1/2", component = "soc"), 1_000)
        assertEquals(FirmwarePhase.RUNNING, t.value.phase); assertEquals(25, t.value.progress)
        assertTrue(t.value.stage.startsWith("Téléchargement"))
        t.accept(OtaProgress(10, 2, otaId = "a"), 2_000)
        assertEquals(25, t.value.progress)
        t.accept(OtaProgress(27, 0, version = "1.16.0", otaId = "a"), 3_000)
        assertEquals(FirmwarePhase.DONE, t.value.phase); assertEquals(100, t.value.progress)
    }

    @Test fun `failure robot report and state 23`() {
        val report = DrvUpgradeReport.newBuilder().setProgress(40).setResult(-3).setMessage("flash").setOtaid("b").build()
        val t = FirmwareUpdateTracker("2.0", 0)
        t.accept(OtaProgressJson.fromReport(report), 1_000)
        assertEquals(FirmwarePhase.FAILED, t.value.phase)
        val s = FirmwareUpdateTracker("2.0", 0)
        s.acceptState(16, 1_000); assertEquals(FirmwarePhase.RUNNING, s.value.phase)
        s.acceptState(23, 2_000); assertEquals(FirmwarePhase.FAILED, s.value.phase)
    }

    @Test fun `HTTP re-read ends only on the target version`() {
        val t = FirmwareUpdateTracker("1.16.0", 0)
        // The cloud lags: "no update in progress" does not end the tracking.
        t.acceptCheck(FirmwareCheck("1.15.0", "1.16.0", true, false, 0, null), 15_000)
        assertEquals(FirmwarePhase.REQUESTED, t.value.phase)
        t.acceptCheck(FirmwareCheck("1.15.0", "1.16.0", true, true, 30, null), 30_000)
        assertEquals(FirmwarePhase.RUNNING, t.value.phase); assertEquals(30, t.value.progress)
        t.acceptCheck(FirmwareCheck("1.16.0", null, false, false, 0, null), 45_000)
        assertEquals(FirmwarePhase.DONE, t.value.phase)
    }

    @Test fun `tracking abandoned without news`() {
        val t = FirmwareUpdateTracker("1.16.0", 0)
        t.tick(FirmwareUpdateTracker.START_TIMEOUT_MS + 1)
        assertEquals(FirmwarePhase.LOST, t.value.phase)
        val r = FirmwareUpdateTracker("1.16.0", 0, alreadyRunning = true)
        r.accept(OtaProgress(50, 2), 60_000)
        r.tick(60_000 + FirmwareUpdateTracker.SILENCE_TIMEOUT_MS - 1)
        assertTrue(r.value.active)
        r.tick(60_000 + FirmwareUpdateTracker.SILENCE_TIMEOUT_MS + 1)
        assertEquals(FirmwarePhase.LOST, r.value.phase)
    }

    @Test fun `start refused robot busy or without update`() {
        val available = FirmwareCheck("1.15.0", "1.16.0", true, false, 0, null)
        assertNull(FirmwareUpdatePolicy.refusal(available, RobotDetails(sysStatus = 11, chargeState = 1)))
        assertNotNull(FirmwareUpdatePolicy.refusal(available, RobotDetails(sysStatus = 13)))
        assertNotNull(FirmwareUpdatePolicy.refusal(available.copy(upgradeable = false), RobotDetails()))
        assertNotNull(FirmwareUpdatePolicy.refusal(available.copy(upgrading = true), RobotDetails()))
        assertNotNull(FirmwareUpdatePolicy.refusal(null, RobotDetails()))
    }

    @Test fun `cloud cause read from version check`() {
        val json = org.json.JSONObject("""{"code":0,"data":[{"deviceId":"iot1","currentVersion":"1.15.0","upgradeable":false,"isupgrading":false,"progress":0,"causeMsg":"Batterie trop faible"}]}""")
        assertEquals("Batterie trop faible", AccountJson.firmware(json, "iot1")?.cause)
    }
}
