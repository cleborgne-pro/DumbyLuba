package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*
import org.junit.Assert.*
import org.junit.Test

class RobotErrorMonitorTest {
    @org.junit.Test fun signedFirmwareCollisionStillConfirmsContact() {
        org.junit.Assert.assertTrue(BumperEvents.confirmsContact(RobotError(-1014,"systemUpdateBuf",1791460131)))
        org.junit.Assert.assertFalse(BumperEvents.confirmsContact(RobotError(-1207,"direct")))
        org.junit.Assert.assertTrue(BumperEvents.latchedFault(RobotError(-1207,"direct")))
        org.junit.Assert.assertFalse(BumperEvents.confirmsContact(RobotError(1104,"direct")))
    }

    private fun history(vararg data: Long) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder()
        .setSystemUpdateBuf(systemUpdateBuf_msg.newBuilder().addAllUpdateBufData(data.toList()))).build()
    @Test fun `read-only error query and fast report rate`() {
        val command = LubaMsg.parseFrom(Commands().errorQuery(1)).sys.bidireCommCmd
        assertEquals(5,command.id); assertEquals(2,command.context); assertEquals(1,command.rw)
        val reports = LubaMsg.parseFrom(Commands().reports(false,1,200)).sys.todevReportCfg
        assertEquals(200,reports.period); assertEquals(200,reports.noChangePeriod)
    }
    @Test fun `old collision does not confirm detection, only a new pair is emitted`() {
        val monitor = RobotErrorMonitor()
        assertTrue(monitor.accept(history(2,23,1,1014,100)).isEmpty())
        assertTrue(monitor.accept(history(2,23,1,1014,100)).isEmpty())
        assertEquals(listOf(RobotError(1014,"systemUpdateBuf",101)),monitor.accept(history(2,23,2,1014,101,1014,100)))
        assertTrue(monitor.accept(history(2,23,2,1014,101,1014,100)).isEmpty())
        monitor.reset()
        assertTrue(monitor.accept(history(2,23,2,1014,101,1014,100)).isEmpty())
    }
    @Test fun `contact republished with another timestamp and unchanged counter is not a second contact`() {
        // Real frames of 2026-10-14 (14:28:16 then 14:28:21), entry counter 868 in both.
        val monitor = RobotErrorMonitor()
        assertTrue(monitor.accept(history(2,43,867,-1014,1791980185,-1014,1791965339)).isEmpty())
        assertEquals(listOf(RobotError(-1014,"systemUpdateBuf",137498)),
            monitor.accept(history(2,43,868,-1014,137498,-1014,1791980185,-1014,1791965339)))
        assertTrue(monitor.accept(history(2,43,868,-1014,1791980897,-1014,1791980185,-1014,1791965339)).isEmpty())
        // Next contact (14:35:26): counter 869, a single new entry.
        assertEquals(listOf(RobotError(-1014,"systemUpdateBuf",1791981327)),
            monitor.accept(history(2,43,869,-1014,1791981327,-1014,1791980897,-1014,1791980185)))
        // Rewrite and new entry together: only the most recent counts.
        assertEquals(listOf(RobotError(-1014,"systemUpdateBuf",500)),
            monitor.accept(history(2,43,870,-1014,500,-1014,1791981328,-1014,1791980897)))
    }
    @Test fun `direct error handled even without a device report, incomplete messages ignored`() {
        val monitor = RobotErrorMonitor()
        val msg=LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappErrCode(SysDevErrCode.newBuilder().setErrorCode(1014))).build()
        assertEquals(1014,monitor.accept(msg).single().code)
        assertTrue(monitor.accept(history(2,23,1,1014)).isEmpty())
        assertTrue(monitor.accept(history(1,23,1,1014,100)).isEmpty())
    }
    private fun direct(code: Int)=LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappErrCode(SysDevErrCode.newBuilder().setErrorCode(code))).build()
    private fun debug(code: Int,time: Long)=LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setDebugErrocodeReport(debug_errocode_report_t.newBuilder().setCode(code).setGenTime(time))).build()
    @Test fun repeatedDirectErrorDoesNotValidateANewTestUntilCleared() {
        val monitor=RobotErrorMonitor()
        assertEquals(1,monitor.accept(direct(1014)).size)
        assertTrue(monitor.accept(direct(1014)).isEmpty())
        assertTrue(monitor.accept(direct(0)).isEmpty())
        assertEquals(1,monitor.accept(direct(1014)).size)
    }
    @Test fun timestampDeduplicationWorksAcrossHistoryAndDebugReports() {
        val monitor=RobotErrorMonitor()
        monitor.accept(history(2,23,1,1014,100))
        assertTrue(monitor.accept(debug(1014,100)).isEmpty())
        assertEquals(1,monitor.accept(debug(1014,101)).size)
        assertTrue(monitor.accept(history(2,23,2,1014,101,1014,100)).isEmpty())
        assertTrue(monitor.accept(debug(1014,101)).isEmpty())
    }
    @Test fun latchedBumperFaultNeverConfirmsContact() {
        assertTrue(BumperEvents.confirmsContact(RobotError(1014,"direct")))
        assertFalse(BumperEvents.confirmsContact(RobotError(1207,"direct")))
        assertTrue(BumperEvents.latchedFault(RobotError(1207,"direct")))
        assertFalse(BumperEvents.confirmsContact(RobotError(0,"direct")))
    }
    @Test fun manualStationaryDiagnosticRequestsModeButNeitherBladesNorMotion() {
        val msg=LubaMsg.parseFrom(Commands().manualMow(1,0,60,1))
        assertEquals(1,msg.driver.mowCtrlByHand.mainCtrl)
        assertEquals(0,msg.driver.mowCtrlByHand.cutKnifeCtrl)
        assertFalse(msg.driver.hasTodevDevmotionCtrl())
        assertTrue(ExternalNavigationPolicy.allows(msg.toByteArray()))
    }
}
