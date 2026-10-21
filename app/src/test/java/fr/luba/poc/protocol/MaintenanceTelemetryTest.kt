package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.blade_used
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_status
import fr.luba.poc.proto.MctrlSysProto.rpt_info_type
import fr.luba.poc.proto.MctrlSysProto.rpt_maintain
import org.junit.Assert.*
import org.junit.Test

class MaintenanceTelemetryTest {
    @Test fun `maintenance always requested over Bluetooth, RTK base only with the account or the probe`() {
        val c = Commands()
        val plain = LubaMsg.parseFrom(c.reports(false)).sys.todevReportCfg.subList
        assertTrue(rpt_info_type.RIT_MAINTAIN in plain)
        assertFalse(rpt_info_type.RIT_BASESTATION_INFO in plain)
        for (bytes in listOf(c.reports(false, account = true), c.reports(true, vision = true))) {
            val subs = LubaMsg.parseFrom(bytes).sys.todevReportCfg.subList
            assertEquals(1, subs.count { it == rpt_info_type.RIT_MAINTAIN })
            assertTrue(rpt_info_type.RIT_BASESTATION_INFO in subs)
        }
        assertEquals(1, LubaMsg.parseFrom(c.reports(true, periodMs = 200)).sys.todevReportCfg.subList.count { it == rpt_info_type.RIT_MAINTAIN })
    }

    @Test fun `maintenance counters read from the report and kept between reports`() {
        val reducer = TelemetryReducer()
        val maintain = rpt_maintain.newBuilder().setMileage(778730).setWorkTime(4241952).setBatCycles(426)
            .setBladeUsedTime(blade_used.newBuilder().setBladeUsedTime(655140))
        fun report(withMaintain: Boolean) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(
            report_info_data.newBuilder().setDev(rpt_dev_status.newBuilder().setBatteryVal(77).setSysStatus(11))
                .apply { if (withMaintain) setMaintain(maintain) })).build()
        assertTrue(reducer.accept(report(true), 1000, 1))
        val m = requireNotNull(reducer.value.maintenance)
        assertEquals(778730L, m.mileageMeters); assertEquals(4241952, m.workSeconds); assertEquals(426, m.batteryCycles)
        assertEquals(655140, m.bladeUsedSeconds); assertEquals(0, m.bladeWarnSeconds)
        assertEquals(1000L, reducer.value.lastMaintenanceAt)
        reducer.accept(report(false), 2000, 2)
        assertEquals(m, reducer.value.maintenance); assertEquals(1000L, reducer.value.lastMaintenanceAt)
    }
}
