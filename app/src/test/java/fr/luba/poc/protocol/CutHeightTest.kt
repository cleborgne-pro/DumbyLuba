package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlDriverProto.DrvKnifeHeight
import fr.luba.poc.proto.MctrlDriverProto.MctlDriver
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.device_product_type_info_t
import org.junit.Assert.*
import org.junit.Test

class CutHeightTest {
    @Test fun `standard and H ranges`() {
        val standard = CutHeightRange.STANDARD
        assertEquals(25..70, standard.intRange())
        assertFalse(standard.high)
        assertEquals(60, standard.defaultMm)
        assertEquals(listOf(25, 30, 40, 50, 60, 70), standard.presets)
        assertEquals(44, standard.sliderSteps)
        assertNull(standard.refusal(70))
        assertEquals("Hauteur hors plage 25–70 mm", standard.refusal(80))

        val high = CutHeightRange.HIGH
        assertEquals(55..100, high.intRange())
        assertTrue(high.high)
        assertEquals(60, high.defaultMm)
        assertEquals(listOf(55, 60, 70, 80, 90, 100), high.presets)
        assertEquals(100, high.clamp(140))
        assertEquals(55, high.clamp(10))
        assertEquals(60f, high.clamp(Float.NaN))
    }

    @Test fun `protocol bound covers every model`() {
        val any = CutHeightRange.ANY
        listOf(CutHeightRange.STANDARD, CutHeightRange.HIGH, CutHeightRange.LUBA_1, CutHeightRange.LUBA_1_HIGH, CutHeightRange.MINI)
            .forEach { r -> assertTrue(r.minMm in any && r.maxMm in any) }
    }

    @Test fun `model code tells the H versions apart`() {
        // LUBA 2 AWD 5000 and 5000H: same Bluetooth prefix, same product key, different code.
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF", "HM030070LBVAWD50OMNI"))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VSABCDEF", "HM060100LBVAWD50OMNIH"))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VSABCDEF", "6"))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VPABCDEF", "HM060100LB2PAWD50OMNIH"))
        // LUBA 3 numeric identifiers, LUBA mini H, LUBA 1 H.
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VAABCDEF", "61"))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VAABCDEF", " 62 "))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-MNABCDEF", "HM055100LB2MINIAWD15OMNIHLD"))
        assertEquals(CutHeightRange.MINI, CutHeightRange.forRobot("Luba-MNABCDEF", "HM020065LB2MINIAWD08OMNI"))
        assertEquals(CutHeightRange.LUBA_1_HIGH, CutHeightRange.forRobot("Luba-1234", "hm060100lbawd50omnih"))
        // Unlisted Luba code ending with H: H version of the family.
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VSABCDEF", "HM060100LBVAWD20OMNIH"))
        // Unknown code: family of the name.
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF", "999"))
        assertNull(CutHeightRange.forProductType("HM020080YKMINI06"))
        assertNull(CutHeightRange.forProductType(""))
    }

    @Test fun `without a code the family of the name applies`() {
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF"))
        assertEquals(CutHeightRange.LUBA_1, CutHeightRange.forRobot("Luba-1234"))
        assertEquals(CutHeightRange.MINI, CutHeightRange.forRobot("Luba-LAABCDEF"))
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot(null))
    }

    @Test fun `a height reported above the standard maximum identifies an H version`() {
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF", reportedMm = 60))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VSABCDEF", reportedMm = 85))
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-MNABCDEF", reportedMm = 70))
        assertEquals(CutHeightRange.LUBA_1_HIGH, CutHeightRange.forRobot("Luba-1234", reportedMm = 90))
        // Impossible value: ignored. A known code wins over the reported height.
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF", reportedMm = 250))
        assertEquals(CutHeightRange.STANDARD, CutHeightRange.forRobot("Luba-VSABCDEF", "HM030070LBVAWD50OMNI", 85))
    }

    @Test fun `model query is read only and its reply is decoded`() {
        val query = LubaMsg.parseFrom(Commands().productModel(1))
        assertEquals(1, query.sys.deviceProductTypeInfo.result)
        assertTrue(ReadOnlyPolicy.allows(Commands().productModel(1)))

        val reducer = TelemetryReducer()
        val reply = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setDeviceProductTypeInfo(
            device_product_type_info_t.newBuilder().setResult(1).setMainProductType("Luba2AWD5000H").setSubProductType("HM060100LBVAWD50OMNIH"))).build()
        reducer.accept(reply, 1, 1)
        assertEquals("HM060100LBVAWD50OMNIH", reducer.value.productType)
        assertEquals("Luba2AWD5000H", reducer.value.productName)
        // Empty reply: the known code is kept.
        reducer.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setDeviceProductTypeInfo(device_product_type_info_t.newBuilder())).build(), 2, 2)
        assertEquals("HM060100LBVAWD50OMNIH", reducer.value.productType)
        assertEquals(CutHeightRange.HIGH, CutHeightRange.forRobot("Luba-VSABCDEF", reducer.value.productType))
        // An H robot reporting 90 mm: accepted by the decoding.
        reducer.accept(LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder().setBidireKnifeHeightReport(DrvKnifeHeight.newBuilder().setKnifeHeight(90))).build(), 3, 3)
        assertEquals(90, reducer.value.bladeHeightMm)
    }
}
