package fr.luba.poc.protocol

import fr.luba.poc.auth.CloudReadOnly
import fr.luba.poc.mowing.MowConfig
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlNavProto.MctlNav
import fr.luba.poc.proto.MctrlNavProto.NavGetCommDataAck
import fr.luba.poc.proto.MctrlNavProto.NavTaskCtrl
import fr.luba.poc.proto.MctrlNavProto.svg_message_ack_t
import com.google.protobuf.Descriptors.FieldDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance of the navigation receiver per model (DumbyLuba#3):
 * - LUBA 2 and every non-LUBA 1 or unknown model: every command byte for byte identical to the commands built before
 *   the change (`Commands()`, navigation receiver `DEV_NAVIGATION`); only the timestamp, taken from the clock by the
 *   navigation messages, is set to the same value before comparing;
 * - LUBA 1: every navigation message (`nav()`) addressed to `DEV_MAINCTL`, everything else unchanged;
 * - read-only barriers (Bluetooth, trial, cloud): a navigation message to the wrong receiver for the model is refused,
 *   and a LUBA 1 gets exactly what a LUBA 2 gets, nothing more.
 */
class NavReceiverAcceptanceTest {
    private val now = 1_700_000_000_000L
    private val ack = NavGetCommDataAck.newBuilder().setAction(8).setType(0).setHash(42L).setTotalFrame(2).setCurrentFrame(1).build()
    private val svg = svg_message_ack_t.newBuilder().setTotalFrame(2).setCurrentFrame(1).setDataHash(7L).setPaternalHashA(9L).build()

    /** Every command of [Commands], with the variants of their arguments, in a fixed order (sequence numbers compared too). */
    private fun all(c: Commands): List<Pair<String, ByteArray>> = buildList {
        add("sync" to c.sync(now)); add("sync3" to c.sync(now, 3))
        add("stop" to c.stop(now)); add("movement" to c.movement(100, -50, now)); add("movement0" to c.movement(0, 0, now))
        add("bladeHeight" to c.bladeHeight(60, now))
        add("manualBlades" to c.manualBlades(true, 60, now)); add("manualBladesOff" to c.manualBlades(false, 60, now))
        add("manualMow" to c.manualMow(1, 0, 50, now)); add("knifeControl" to c.knifeControl(false, now)); add("cutterQuery" to c.cutterQuery(now))
        add("prepareMowing" to c.prepareMowing(MowConfig(listOf(1L, 2L))))
        listOf(1, 2, 3, 4, 5, 7).forEach { add("taskControl$it" to c.taskControl(it)) }
        add("returnToDock" to c.returnToDock()); add("cancelReturnToDock" to c.cancelReturnToDock()); add("leaveDock" to c.leaveDock())
        add("mowPathHashes" to c.mowPathHashes()); add("mowPaths" to c.mowPaths(listOf(1L, 2L), 7L))
        add("mapHashes" to c.mapHashes()); add("mapHashesAck" to c.mapHashesAck(3, 1)); add("mapData" to c.mapData(42L))
        add("mapDataAck" to c.mapDataAck(ack)); add("svgAck" to c.svgAck(svg))
        MAP_RECORD_TYPES.forEach { t ->
            add("startMapRecording$t" to c.startMapRecording(t)); add("endMapRecording$t" to c.endMapRecording(t))
            add("deleteMapElement$t" to c.deleteMapElement(t, 42L))
        }
        add("saveMapTask" to c.saveMapTask()); add("cancelMapRecording" to c.cancelMapRecording())
        add("errorQuery" to c.errorQuery(now)); add("selfCheckInfo" to c.selfCheckInfo(now))
        add("reports" to c.reports(false, now)); add("reportsKeep" to c.reports(true, now, periodMs = 200))
        add("reportsAccount" to c.reports(false, now, account = true)); add("reportsVision" to c.reports(false, now, vision = true))
        add("video" to c.video(true, now = now)); add("videoOff" to c.video(false, now = now)); add("refreshFpv" to c.refreshFpv(now))
        add("debugEnable" to c.debugEnable(true, now)); add("productModel" to c.productModel(now)); add("networkInfo" to c.networkInfo(now))
        add("perceptionZmq" to c.perceptionZmq(true, now))
    }

    /** Same bytes with the clock timestamp of the navigation messages fixed. */
    private fun normalized(bytes: ByteArray): ByteArray = LubaMsg.parseFrom(bytes).toBuilder().setTimestamp(now).build().toByteArray()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun isNav(bytes: ByteArray) = LubaMsg.parseFrom(bytes).msgtype == MsgCmdType.MSG_CMD_TYPE_NAV

    @Test fun everyCommandOfTheClassIsCoveredByThisTest() {
        // A new command producing a frame must be added to [all] (and so checked per model).
        val produced = Commands::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name && it.returnType == ByteArray::class.java }
            .map { it.name }.toSet()
        val covered = all(Commands()).map { (label, _) -> label.trimEnd { it.isDigit() }.removeSuffix("Off").removeSuffix("Keep").removeSuffix("Account").removeSuffix("Vision") }.toSet()
        assertEquals(produced.sorted(), (covered intersect produced).sorted())
    }

    @Test fun luba2AndOtherModelsSendTheSameBytesAsBefore() {
        val robots = listOf<Pair<String?, String?>>(
            "Luba-VS123456" to null, "Luba-VP123456" to null, "luba-vsabc123" to null, // LUBA 2 / X series, case ignored
            "Luba-XYZ" to "a1iMygIwxFC", "SERIAL0001" to "a1iMygIwxFC", // LUBA 2 known by its account key only
            "Luba-VS123456" to "a1FbaU4Bqk5", // name wins over a LUBA 1 key, as RobotModel.detect
            "Luba-VA123456" to null, "Luba-MN123456" to null, "Luba-LA123456" to null, "Luba-HM123456" to null,
            "Yuka-123456" to null, "Ezy-VT123" to null, // other models
            null to null, "" to null, "Mower" to null, "RTK-123456" to null, // unknown
        )
        for (account in listOf(0, 1234, -5)) {
            val before = all(Commands(account))
            robots.forEach { (name, key) ->
                val after = all(Commands.forRobot(name, key, account))
                assertEquals(MsgDevice.DEV_NAVIGATION, Commands.forRobot(name, key, account).navReceiver)
                assertEquals(before.map { it.first }, after.map { it.first })
                before.zip(after).forEach { (b, a) ->
                    assertEquals("$name/$key ${b.first}", hex(normalized(b.second)), hex(normalized(a.second)))
                    // Frames carrying the given time: raw bytes identical without any normalization.
                    if (!isNav(b.second)) assertEquals("$name/$key ${b.first} raw", hex(b.second), hex(a.second))
                }
            }
        }
        // Receiver value written on the wire for these models: DEV_NAVIGATION (17), as before the change.
        assertEquals(17, LubaMsg.parseFrom(Commands.forRobot("Luba-VS123456").mapHashes()).rcverValue)
    }

    @Test fun luba1SendsEveryNavigationMessageToTheMainControllerAndNothingElseChanges() {
        val robots = listOf<Pair<String?, String?>>("Luba-ABC123" to null, "Luba-SIM0001" to null, "LUBA-123" to null,
            "MA5000H23A0012345" to "a1FbaU4Bqk5", "" to "a1FbaU4Bqk5", "MA5000H23A0012345" to " a1FbaU4Bqk5 ")
        robots.forEach { (name, key) ->
            val luba1 = Commands.forRobot(name, key, 1234)
            assertEquals("$name/$key", MsgDevice.DEV_MAINCTL, luba1.navReceiver)
            val before = all(Commands(1234)); val after = all(luba1)
            var navigation = 0
            before.zip(after).forEach { (b, a) ->
                val old = LubaMsg.parseFrom(normalized(b.second)); val new = LubaMsg.parseFrom(normalized(a.second))
                if (old.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV) {
                    navigation++
                    assertEquals("$name ${b.first}", MsgDevice.DEV_MAINCTL, new.rcver)
                    // Only the receiver differs.
                    assertEquals("$name ${b.first}", hex(old.toBuilder().setRcver(MsgDevice.DEV_MAINCTL).build().toByteArray()), hex(new.toByteArray()))
                } else assertEquals("$name ${b.first}", hex(b.second), hex(a.second))
            }
            assertEquals(28, navigation)
        }
    }

    // --- Read-only barriers ------------------------------------------------------------------------

    private fun cloud(bytes: ByteArray, nativeReturn: Boolean, receiver: MsgDevice? = null) =
        runCatching { if (receiver == null) CloudReadOnly.requireAllowed(bytes, nativeReturn) else CloudReadOnly.requireAllowed(bytes, nativeReturn, receiver) }.isSuccess

    @Test fun barriersGiveALuba1ExactlyWhatALuba2GetsAndRefuseTheOtherReceiver() {
        val luba2 = all(Commands(1234)); val luba1 = all(Commands.forRobot("Luba-ABC123", null, 1234))
        luba2.zip(luba1).forEach { (two, one) ->
            val label = two.first
            // LUBA 2 unchanged: the call without receiver (former signature) and with its receiver agree.
            assertEquals(label, ReadOnlyPolicy.allows(two.second), ReadOnlyPolicy.allows(two.second, MsgDevice.DEV_NAVIGATION))
            assertEquals(label, ReadOnlyPolicy.allowsTrial(two.second), ReadOnlyPolicy.allowsTrial(two.second, MsgDevice.DEV_NAVIGATION))
            // LUBA 1: same answers as the LUBA 2 for the same command.
            assertEquals(label, ReadOnlyPolicy.allows(two.second, MsgDevice.DEV_NAVIGATION), ReadOnlyPolicy.allows(one.second, MsgDevice.DEV_MAINCTL))
            assertEquals(label, ReadOnlyPolicy.allowsTrial(two.second, MsgDevice.DEV_NAVIGATION), ReadOnlyPolicy.allowsTrial(one.second, MsgDevice.DEV_MAINCTL))
            for (native in listOf(false, true)) {
                assertEquals("$label cloud", cloud(two.second, native), cloud(two.second, native, MsgDevice.DEV_NAVIGATION))
                assertEquals("$label cloud", cloud(two.second, native, MsgDevice.DEV_NAVIGATION), cloud(one.second, native, MsgDevice.DEV_MAINCTL))
            }
            // Wrong receiver for the model: every navigation message refused, whatever it is.
            if (isNav(two.second)) {
                assertFalse("$label LUBA 2 frame on a LUBA 1", ReadOnlyPolicy.allows(two.second, MsgDevice.DEV_MAINCTL))
                assertFalse("$label LUBA 1 frame on a LUBA 2", ReadOnlyPolicy.allows(one.second, MsgDevice.DEV_NAVIGATION))
                assertFalse("$label LUBA 2 frame on a LUBA 1 trial", ReadOnlyPolicy.allowsTrial(two.second, MsgDevice.DEV_MAINCTL))
                assertFalse("$label LUBA 1 frame on a LUBA 2 trial", ReadOnlyPolicy.allowsTrial(one.second, MsgDevice.DEV_NAVIGATION))
                for (native in listOf(false, true)) {
                    assertFalse("$label cloud LUBA 2 frame on a LUBA 1", cloud(two.second, native, MsgDevice.DEV_MAINCTL))
                    assertFalse("$label cloud LUBA 1 frame on a LUBA 2", cloud(one.second, native, MsgDevice.DEV_NAVIGATION))
                    assertFalse("$label cloud LUBA 1 frame, default receiver", cloud(one.second, native))
                }
            }
        }
    }

    /** One navigation message per sub-message of [MctlNav], with default contents, then with the read values. */
    private fun navSamples(): List<Pair<String, MctlNav>> {
        val out = mutableListOf<Pair<String, MctlNav>>()
        MctlNav.getDescriptor().fields.forEach { field ->
            if (field.isRepeated) return@forEach
            val builder = MctlNav.newBuilder()
            when (field.javaType) {
                FieldDescriptor.JavaType.MESSAGE -> builder.setField(field, builder.newBuilderForField(field).build())
                FieldDescriptor.JavaType.INT -> builder.setField(field, 1)
                FieldDescriptor.JavaType.LONG -> builder.setField(field, 1L)
                FieldDescriptor.JavaType.BOOLEAN -> builder.setField(field, true)
                FieldDescriptor.JavaType.FLOAT -> builder.setField(field, 1f)
                FieldDescriptor.JavaType.DOUBLE -> builder.setField(field, 1.0)
                FieldDescriptor.JavaType.STRING -> builder.setField(field, "x")
                FieldDescriptor.JavaType.BYTE_STRING -> builder.setField(field, com.google.protobuf.ByteString.copyFromUtf8("x"))
                FieldDescriptor.JavaType.ENUM -> builder.setField(field, field.enumType.values.last())
                else -> return@forEach
            }
            out += field.name to builder.build()
        }
        // Task control: every action, the allowed ones included.
        (0..20).forEach { a -> out += "taskctrl$a" to MctlNav.newBuilder().setTodevTaskctrl(NavTaskCtrl.newBuilder().setType(1).setAction(a).setResult(0)).build() }
        all(Commands()).filter { isNav(it.second) }.forEach { (label, bytes) -> out += label to LubaMsg.parseFrom(bytes).nav }
        return out
    }

    @Test fun noNavigationMessageIsOpenedByTheReceiverOfTheModel() {
        val receivers = MsgDevice.values().filter { it != MsgDevice.UNRECOGNIZED }
        var opened = 0
        navSamples().forEach { (label, nav) ->
            fun message(rcver: MsgDevice) = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_NAV).setSender(MsgDevice.DEV_MOBILEAPP)
                .setRcver(rcver).setMsgattrValue(1).setSeqs(1).setVersion(1).setTimestamp(now).setNav(nav).build().toByteArray()
            // Reference: the LUBA 2 answer for this sub-message, addressed to its navigation board.
            val reference = ReadOnlyPolicy.allows(message(MsgDevice.DEV_NAVIGATION), MsgDevice.DEV_NAVIGATION)
            val referenceTrial = ReadOnlyPolicy.allowsTrial(message(MsgDevice.DEV_NAVIGATION), MsgDevice.DEV_NAVIGATION)
            val referenceCloud = listOf(false, true).map { cloud(message(MsgDevice.DEV_NAVIGATION), it, MsgDevice.DEV_NAVIGATION) }
            if (reference) opened++
            receivers.forEach { rcver ->
                val bytes = message(rcver)
                for (model in listOf(MsgDevice.DEV_NAVIGATION, MsgDevice.DEV_MAINCTL)) {
                    val match = rcver == model
                    assertEquals("$label to $rcver, model $model", match && reference, ReadOnlyPolicy.allows(bytes, model))
                    assertEquals("$label to $rcver, model $model (trial)", match && referenceTrial, ReadOnlyPolicy.allowsTrial(bytes, model))
                    listOf(false, true).forEachIndexed { i, native ->
                        assertEquals("$label to $rcver, model $model (cloud, native $native)", match && referenceCloud[i], cloud(bytes, native, model))
                    }
                }
                // Model not given: one of the two navigation receivers only.
                val known = rcver == MsgDevice.DEV_NAVIGATION || rcver == MsgDevice.DEV_MAINCTL
                assertEquals("$label to $rcver, model unknown", known && reference, ReadOnlyPolicy.allows(bytes))
            }
        }
        assertTrue("some reads are allowed", opened > 0)
    }
}
