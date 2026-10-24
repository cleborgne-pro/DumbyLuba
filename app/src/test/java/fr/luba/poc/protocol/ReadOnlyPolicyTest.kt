package fr.luba.poc.protocol

import fr.luba.poc.mowing.MowConfig
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
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
        assertEquals(fr.luba.poc.proto.LubaMsgProto.MsgDevice.DEV_NAVIGATION, LubaMsg.parseFrom(commands.startMapRecording(0)).rcver)
        listOf({ commands.startMapRecording(6) }, { commands.endMapRecording(-1) }, { commands.deleteMapElement(6, 1L) }, { commands.deleteMapElement(0, 0L) })
            .forEach { build -> assertTrue(runCatching { build() }.isFailure) }
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
