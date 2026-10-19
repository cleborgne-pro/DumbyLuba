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

    @Test fun writeSystemQueryEmptyAndGarbageMessagesAreRefused() {
        val query = LubaMsg.parseFrom(commands.errorQuery())
        val write = query.toBuilder().setSys(query.sys.toBuilder().setBidireCommCmd(query.sys.bidireCommCmd.toBuilder().setRw(0))).build()
        assertFalse(ReadOnlyPolicy.allows(write.toByteArray()))
        assertFalse(ReadOnlyPolicy.allows(LubaMsg.getDefaultInstance().toByteArray()))
        assertFalse(ReadOnlyPolicy.allows(byteArrayOf(0x7f, 0x01, 0x02)))
    }

    @Test fun onlyTheLuba2LubaVsIsValidated() {
        assertTrue(ModelSupport.motionValidated("Luba-VSDEMO01"))
        assertTrue(ModelSupport.motionValidated(" luba-vsDEMO01"))
        listOf("Luba-VPDEMO01", "Luba-VADEMO01", "Luba-MNDEMO01", "Luba-LADEMO01", "LubaDEMO01", "Yuka-MNDEMO01",
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
        assertEquals(listOf("Luba-VS"), ModelSupport.VALIDATED_PREFIXES)
    }
}
