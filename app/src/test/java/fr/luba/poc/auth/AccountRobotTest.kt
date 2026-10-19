package fr.luba.poc.auth

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.protocol.Commands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountRobotTest {
    private fun report(build: report_info_data.Builder.() -> Unit): LubaMsg =
        LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().apply(build))).build()

    @Test fun `task encoded like Mammotion-HA`() {
        val r = RobotDetailsReducer()
        // area = 42 % << 16 | 380 m²; progress = 25 min left << 16 | 60 min in total.
        assertTrue(r.accept(report { setWork(rpt_work.newBuilder().setArea((42 shl 16) or 380).setProgress((25 shl 16) or 60)
            .setKnifeHeight(55).setManRunSpeed(40)) }, 1000L))
        val t = requireNotNull(r.value.task)
        assertEquals(42, t.percent); assertEquals(380, t.areaM2); assertEquals(60, t.totalMinutes)
        assertEquals(25, t.leftMinutes); assertEquals(35, t.elapsedMinutes); assertEquals(55, t.knifeHeightMm)
        assertEquals(0.4, t.speedMps!!, 1e-9); assertTrue(t.active)
    }

    @Test fun `maintenance rtk base and state`() {
        val r = RobotDetailsReducer()
        r.accept(report {
            setDev(rpt_dev_status.newBuilder().setSysStatus(11).setChargeState(1).setBatteryVal(77))
            setMaintain(rpt_maintain.newBuilder().setMileage(123_456).setWorkTime(7200).setBatCycles(31)
                .setBladeUsedTime(blade_used.newBuilder().setBladeUsedTime(36_000).setBladeUsedWarnTime(72_000)))
            setBasestationInfo(rpt_basestation_info.newBuilder().setVerMajor(1).setVerMinor(2).setVerPatch(3).setVerBuild(4))
            setRtk(rpt_rtk.newBuilder().setStatus(4).setAge(2).setDisStatus((19L shl 16) or (14L shl 24)).setCoViewStars((9 shl 8) or 12))
            setConnect(rpt_connect_status.newBuilder().setUsedNet(net_used_type.NET_USED_TYPE_WIFI).setWifiRssi(-61).setMnetRssi(0))
            setFwInfo(device_fw_info.newBuilder().setVersion("1.12.4").addMod(mod_fw_info.newBuilder().setType(5).setIdentify("rtk").setVersion("2.0")))
        }, 5000L)
        val d = r.value
        assertEquals("sur la base", RobotStates.label(d.sysStatus, d.chargeState)); assertEquals(77, d.battery)
        val m = requireNotNull(d.maintenance)
        assertEquals(123_456L, m.mileageMeters); assertEquals(31, m.batteryCycles); assertEquals(0.5, m.bladeWear!!, 1e-9)
        assertEquals("1.2.3.4", d.base?.version)
        val k = requireNotNull(d.rtk)
        assertEquals(19, k.l1); assertEquals(14, k.l2); assertEquals(12, k.coViewL1); assertEquals(9, k.coViewL2)
        assertEquals("Wi-Fi", RobotStates.net(d.network?.usedNet)); assertEquals(-61, d.network?.wifiRssi); assertNull(d.network?.mobileRssi)
        assertEquals("1.12.4", d.firmware); assertEquals("rtk", d.modules.single().identify)
        assertEquals(5000L, d.updatedAt)
        // A partial report keeps the rest.
        r.accept(report { setDev(rpt_dev_status.newBuilder().setSysStatus(13).setBatteryVal(70)) }, 6000L)
        assertEquals("tonte", RobotStates.label(r.value.sysStatus)); assertEquals(123_456L, r.value.maintenance?.mileageMeters)
        assertFalse(r.accept(LubaMsg.getDefaultInstance(), 7000L))
    }

    @Test fun `account history and firmware`() {
        val history = JSONObject("""{"code":0,"data":{"records":[
            {"workName":"Avant","workType":2,"workResult":5,"workArea":412.5,"workProgress":100,"workTimeUsed":95,"startWorkTime":1760000000,"endWorkTime":1760005700},
            {"workName":"Arrière","workType":1,"workResult":4,"workArea":120,"workProgress":40,"startWorkTime":1760100000000}]}}""")
        val records = AccountJson.workRecords(history)
        assertEquals(listOf("Arrière", "Avant"), records.map { it.name })
        assertEquals(1_760_000_000_000L, records[1].start); assertEquals("terminée", records[1].resultLabel); assertEquals("Planning", records[1].typeLabel)
        assertEquals("interrompue", records[0].resultLabel)
        assertTrue(AccountJson.workRecords(JSONObject("""{"code":0}""")).isEmpty())

        val fw = AccountJson.firmware(JSONObject("""{"code":0,"data":[{"deviceId":"other","currentVersion":"0"},
            {"deviceId":"iot1","currentVersion":"1.12.4","upgradeable":true,"isupgrading":false,
             "productVersionInfoVo":{"releaseVersion":"1.13.0","releaseNote":"Corrections"}}]}"""), "iot1")!!
        assertEquals("1.12.4", fw.current); assertEquals("1.13.0", fw.latest); assertTrue(fw.upgradeable); assertEquals("Corrections", fw.releaseNote)
        assertNull(AccountJson.firmware(JSONObject("""{"code":0,"data":[]}"""), "iot1"))
    }

    @Test fun `cloud safeguard accepts the maintenance subscription and always refuses commands`() {
        val c = Commands()
        CloudReadOnly.requireAllowed(c.reports(false, account = true))
        CloudReadOnly.requireAllowed(c.reports(true, account = true))
        val subs = LubaMsg.parseFrom(c.reports(false, account = true)).sys.todevReportCfg.subList
        assertTrue(subs.containsAll(listOf(rpt_info_type.RIT_MAINTAIN, rpt_info_type.RIT_BASESTATION_INFO)))
        // Another period or vision stays refused.
        for (bytes in listOf(c.reports(false, periodMs = 500, account = true), c.reports(false, vision = true), c.stop()))
            try { CloudReadOnly.requireAllowed(bytes); fail("Cloud command accepted") } catch (_: IllegalArgumentException) {}
    }
}
