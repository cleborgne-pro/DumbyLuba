package fr.luba.poc.protocol

import fr.luba.poc.mowing.MowConfig
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlNavProto.NavGetCommDataAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPolicyTest {
    private val commands = Commands()

    @Test fun readsStopsAndCameraStayAllowed() {
        listOf(
            commands.stop(), commands.sync(), commands.reports(false), commands.reports(true, account = true),
            commands.errorQuery(), commands.selfCheckInfo(), commands.cutterQuery(), commands.networkInfo(),
            commands.mapHashes(), commands.mapHashesAck(3, 1), commands.mapData(42L), commands.mowPathHashes(),
            commands.mowPaths(listOf(1L, 2L), 7L), commands.manualBlades(false, 60), commands.manualMow(0, 0, 50),
            commands.knifeControl(false), commands.taskControl(2), commands.taskControl(4), commands.cancelReturnToDock(),
            commands.video(true), commands.video(false), commands.refreshFpv(),
        ).forEachIndexed { index, payload -> assertTrue("read or stop command #$index", ReadOnlyPolicy.allows(payload)) }
    }

    @Test fun motionBladesHeightAndNativeTasksAreRefused() {
        listOf(
            commands.movement(1, 0), commands.movement(0, -1), commands.movement(500, 450),
            commands.manualBlades(true, 60), commands.manualMow(1, 0, 50), commands.manualMow(0, 1, 50),
            commands.knifeControl(true), commands.bladeHeight(50), commands.prepareMowing(MowConfig(listOf(1))),
            commands.taskControl(1), commands.taskControl(3), commands.taskControl(7), commands.returnToDock(),
            commands.leaveDock(), commands.debugEnable(true), commands.perceptionZmq(true),
        ).forEachIndexed { index, payload -> assertFalse("motion, blade or native command #$index", ReadOnlyPolicy.allows(payload)) }
    }

    @Test fun modelTrialOpensOnlyTheUnlockingTestPayloads() {
        // Command cutoff and joystick within the trial ceilings, blade test (mode, discs, alternative knife command).
        listOf(
            commands.movement(120, 0), commands.movement(-ModelTrial.MAX_LINEAR, ModelTrial.MAX_ANGULAR), commands.movement(0, -ModelTrial.MAX_ANGULAR),
            commands.manualMow(1, 0, 50), commands.manualMow(1, 1, 50), commands.manualBlades(true, 60), commands.knifeControl(true),
            commands.stop(), commands.reports(false), commands.cutterQuery(),
        ).forEachIndexed { index, payload -> assertTrue("trial payload #$index", ReadOnlyPolicy.allowsTrial(payload)) }
        // Faster than the trial, height setting, native tasks, return, map writing: still refused.
        listOf(
            commands.movement(ModelTrial.MAX_LINEAR + 1, 0), commands.movement(0, ModelTrial.MAX_ANGULAR + 1), commands.movement(1000, 450),
            commands.bladeHeight(50), commands.prepareMowing(MowConfig(listOf(1))), commands.taskControl(1), commands.returnToDock(),
            commands.leaveDock(), commands.startMapRecording(0), commands.deleteMapElement(0, 42L), commands.debugEnable(true),
            byteArrayOf(1, 2, 3),
        ).forEachIndexed { index, payload -> assertFalse("refused during a trial #$index", ReadOnlyPolicy.allowsTrial(payload)) }
    }

    @Test fun mapWritingIsRefusedButMapReadingStaysAllowed() {
        listOf(commands.startMapRecording(0), commands.startMapRecording(1), commands.startMapRecording(2), commands.endMapRecording(0),
            commands.cancelMapRecording(), commands.deleteMapElement(0, 42L), commands.deleteMapElement(1, 42L), commands.saveMapTask())
            .forEachIndexed { index, payload -> assertFalse("map write #$index", ReadOnlyPolicy.allows(payload)) }
        val ack = fr.luba.poc.proto.MctrlNavProto.NavGetCommDataAck.newBuilder().setAction(8).setType(0).setHash(42L).setTotalFrame(2).setCurrentFrame(1).build()
        assertTrue(ReadOnlyPolicy.allows(commands.mapDataAck(ack)))
        assertTrue(ReadOnlyPolicy.allows(commands.mapData(42L)))
    }

    @Test fun mapWritingCommandsCarryTheApkFields() {
        fun data(payload: ByteArray) = LubaMsg.parseFrom(payload).nav.todevGetCommondata
        data(commands.startMapRecording(1)).let { assertEquals(listOf(1, 0, 1, 0), listOf(it.pver, it.action, it.type, it.subCmd)) }
        data(commands.endMapRecording(2)).let { assertEquals(listOf(1, 1, 2, 0), listOf(it.pver, it.action, it.type, it.subCmd)) }
        data(commands.cancelMapRecording()).let { assertEquals(listOf(1, 7, 0), listOf(it.pver, it.action, it.subCmd)) }
        data(commands.deleteMapElement(0, 7L)).let { assertEquals(listOf(6, 0), listOf(it.action, it.type)); assertEquals(7L, it.hash) }
        assertEquals(MsgDevice.DEV_NAVIGATION, LubaMsg.parseFrom(commands.startMapRecording(0)).rcver)
        assertEquals(MsgDevice.DEV_MAINCTL, LubaMsg.parseFrom(Commands.forRobot("Luba-DEMO0001").startMapRecording(0)).rcver)
        listOf({ commands.startMapRecording(6) }, { commands.endMapRecording(-1) }, { commands.deleteMapElement(6, 1L) }, { commands.deleteMapElement(0, 0L) })
            .forEach { build -> assertTrue(runCatching { build() }.isFailure) }
    }

    @Test fun navigationReceiverFollowsTheRobotModel() {
        // PyMammotion get_msg_device: DEV_NAVIGATION only for LUBA 2 and later; the LUBA 1 keeps DEV_MAINCTL.
        assertEquals(MsgDevice.DEV_MAINCTL, Commands.navReceiverFor(fr.luba.poc.ui.RobotModel.LUBA_1))
        assertEquals(MsgDevice.DEV_NAVIGATION, Commands.navReceiverFor(fr.luba.poc.ui.RobotModel.LUBA_2))
        assertEquals(MsgDevice.DEV_NAVIGATION, Commands.navReceiverFor(fr.luba.poc.ui.RobotModel.UNKNOWN))
        val luba1 = Commands.forRobot("Luba-DEMO0001")
        val luba1ByKey = Commands.forRobot("MA5000DEMO0001", "a1FbaU4Bqk5")
        val luba2 = Commands.forRobot("Luba-VSDEMO01")
        val unknown = Commands.forRobot(null)
        val other = Commands.forRobot("Mower-DEMO")
        fun receivers(c: Commands) = listOf(c.mapHashes(), c.mapHashesAck(2, 1), c.mapData(42L), c.mapDataAck(NavGetCommDataAck.newBuilder().setAction(8).setHash(42L).setTotalFrame(1).setCurrentFrame(1).build()),
            c.mowPathHashes(), c.mowPaths(listOf(1L), 7L), c.taskControl(2), c.returnToDock(), c.cancelReturnToDock(), c.leaveDock(),
            c.prepareMowing(MowConfig(listOf(1))), c.startMapRecording(0), c.saveMapTask()).map { LubaMsg.parseFrom(it).rcver }.distinct()
        assertEquals(listOf(MsgDevice.DEV_MAINCTL), receivers(luba1))
        assertEquals(listOf(MsgDevice.DEV_MAINCTL), receivers(luba1ByKey))
        assertEquals(listOf(MsgDevice.DEV_NAVIGATION), receivers(luba2))
        assertEquals(listOf(MsgDevice.DEV_NAVIGATION), receivers(unknown))
        assertEquals(listOf(MsgDevice.DEV_NAVIGATION), receivers(other))
        // Driver, system and network messages keep their receivers.
        assertEquals(MsgDevice.DEV_MAINCTL, LubaMsg.parseFrom(luba2.stop()).rcver)
        assertEquals(MsgDevice.DEV_COMM_ESP, LubaMsg.parseFrom(luba1.sync()).rcver)
    }

    @Test fun readOnlyPolicyAcceptsTheModelNavigationReceiverOnly() {
        val luba1 = Commands.forRobot("Luba-DEMO0001")
        val reads = listOf(luba1.mapHashes(), luba1.mapData(42L), luba1.mowPathHashes(), luba1.taskControl(4), luba1.cancelReturnToDock())
        reads.forEachIndexed { i, payload ->
            assertTrue("LUBA 1 read #$i", ReadOnlyPolicy.allows(payload, MsgDevice.DEV_MAINCTL))
            assertTrue("LUBA 1 read #$i, model not given", ReadOnlyPolicy.allows(payload))
            assertFalse("LUBA 1 read #$i checked against DEV_NAVIGATION", ReadOnlyPolicy.allows(payload, MsgDevice.DEV_NAVIGATION))
            assertTrue("LUBA 1 read #$i during a trial", ReadOnlyPolicy.allowsTrial(payload, MsgDevice.DEV_MAINCTL))
        }
        listOf(commands.mapHashes(), commands.mapData(42L)).forEach { payload ->
            assertTrue(ReadOnlyPolicy.allows(payload, MsgDevice.DEV_NAVIGATION))
            assertFalse(ReadOnlyPolicy.allows(payload, MsgDevice.DEV_MAINCTL))
        }
        // Nothing more opened on a LUBA 1: native tasks, return, leaving the base, map writing.
        listOf(luba1.taskControl(1), luba1.returnToDock(), luba1.leaveDock(), luba1.startMapRecording(0), luba1.saveMapTask(),
            luba1.prepareMowing(MowConfig(listOf(1))))
            .forEachIndexed { i, payload ->
                assertFalse("LUBA 1 refused #$i", ReadOnlyPolicy.allows(payload, MsgDevice.DEV_MAINCTL))
                assertFalse("LUBA 1 refused during a trial #$i", ReadOnlyPolicy.allowsTrial(payload, MsgDevice.DEV_MAINCTL))
            }
        // A map read addressed to another board is refused.
        val stray = LubaMsg.parseFrom(luba1.mapHashes()).toBuilder().setRcver(MsgDevice.DEV_COMM_ESP).build().toByteArray()
        assertFalse(ReadOnlyPolicy.allows(stray))
        assertFalse(ReadOnlyPolicy.allows(stray, MsgDevice.DEV_MAINCTL))
    }

    @Test fun mapWritingNeverGoesThroughTheCloud() {
        listOf(commands.startMapRecording(0), commands.endMapRecording(0), commands.cancelMapRecording(), commands.deleteMapElement(0, 42L), commands.saveMapTask())
            .forEach { payload -> assertTrue(runCatching { fr.luba.poc.auth.CloudReadOnly.requireAllowed(payload) }.isFailure) }
        assertEquals(1, LubaMsg.parseFrom(commands.saveMapTask()).nav.todevSaveTask)
    }

    @Test fun writeSystemQueryEmptyAndGarbageMessagesAreRefused() {
        val query = LubaMsg.parseFrom(commands.errorQuery())
        val write = query.toBuilder().setSys(query.sys.toBuilder().setBidireCommCmd(query.sys.bidireCommCmd.toBuilder().setRw(0))).build()
        assertFalse(ReadOnlyPolicy.allows(write.toByteArray()))
        assertFalse(ReadOnlyPolicy.allows(LubaMsg.getDefaultInstance().toByteArray()))
        assertFalse(ReadOnlyPolicy.allows(byteArrayOf(0x7f, 0x01, 0x02)))
    }

    @Test fun onlyTheLuba2LubaVsAndItsXSeriesLubaVpAreValidated() {
        assertTrue(ModelSupport.motionValidated("Luba-VSDEMO01"))
        assertTrue(ModelSupport.motionValidated(" luba-vsDEMO01"))
        // LUBA 2 AWD X series (3000X of DumbyLuba#1): same platform and firmware as the validated LUBA 2.
        assertTrue(ModelSupport.motionValidated("Luba-VPDEMO01"))
        assertTrue(ModelSupport.motionValidated("luba-vp3K8A2B"))
        assertNull(ModelSupport.refusal("Luba-VPDEMO01"))
        assertNull(ModelSupport.readOnlyNote("Luba-VPDEMO01", "LUBA 2 AWD"))
        listOf("Luba-VADEMO01", "Luba-MNDEMO01", "Luba-LADEMO01", "LubaDEMO01", "Yuka-MNDEMO01",
            "Ezy-VTDEMO01", "Spino-S1DEMO", null, "").forEach { assertFalse("$it", ModelSupport.motionValidated(it)) }
        assertNull(ModelSupport.refusal("Luba-VSDEMO01"))
        assertTrue(ModelSupport.refusal("Yuka-MNDEMO01")!!.isNotBlank())
    }

    @Test fun readOnlyNoteOnlyForAKnownRobotThatIsNotValidated() {
        assertNull(ModelSupport.readOnlyNote("Luba-VSDEMO01", "LUBA 2 AWD"))
        assertNull(ModelSupport.readOnlyNote(null, "LUBA"))
        assertNull(ModelSupport.readOnlyNote(" ", "LUBA"))
        val note = ModelSupport.readOnlyNote("Yuka-MNDEMO01", "Yuka")!!
        assertTrue(note.contains("Yuka") && note.contains(ModelSupport.SUPPORT_PAGE))
    }

    @Test fun scanListsEveryMammotionMowerButNotPoolCleanersOrStations() {
        listOf("Luba-VSDEMO01", "LubaDEMO01", "Luba-VADEMO01", "Yuka-DEMO01", "Yuka-MNDEMO01", "Ezy-LDDEMO01",
            "Kumar-MKDEMO01", "Maston-RDEMO01").forEach { assertTrue("$it", ModelSupport.isMower(it)) }
        listOf("Spino-S1DEMO", "RBSA1DEMO", "NBDEMO", "Phone", null).forEach { assertFalse("$it", ModelSupport.isMower(it)) }
        assertEquals(listOf("Luba-VS", "Luba-VP"), ModelSupport.VALIDATED_PREFIXES)
    }
}
