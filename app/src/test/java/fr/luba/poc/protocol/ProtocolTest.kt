package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.*
import fr.luba.poc.proto.MctrlSysProto.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `transmitted base frame kept without validating the watchdog`() {
        val reducer = TelemetryReducer()
        val msg = LubaMsg.newBuilder().setNav(fr.luba.poc.proto.MctrlNavProto.MctlNav.newBuilder()
            .setToappChgpileto(fr.luba.poc.proto.MctrlNavProto.chargePileType.newBuilder().setX(3.25f).setY(-4.5f))).build()
        assertFalse(reducer.accept(msg, 300, 300))
        assertEquals(3.25, reducer.value.baseX!!, 0.0)
        assertEquals(-4.5, reducer.value.baseY!!, 0.0)
        assertEquals(300L, reducer.value.lastBaseAt)
        assertNull(reducer.value.lastReportAt)
    }

    @Test fun `purging commands before STOP creates no TX sequence gap`() {
        val transport = Blufi()
        val pending = transport.fragment(ByteArray(30), 23)
        assertEquals(0, transport.forWrite(pending.first())[2].toInt())
        // The other fragments are dropped before being sent.
        val stop = transport.fragment(Commands().stop(1), 512).single()
        assertEquals(1, transport.forWrite(stop)[2].toInt())
    }
    @Test fun `watchdog expires without a first report and stays armed after a report`() {
        val watchdog = TelemetryWatchdog()
        watchdog.start(10000)
        assertFalse(watchdog.expired(19999, 10000))
        assertTrue(watchdog.expired(20000, 10000))
        watchdog.recordValidReport(15000)
        assertFalse(watchdog.expired(24999, 10000))
        assertTrue(watchdog.expired(25000, 10000))
    }
    @Test fun `frame and CRC identical to vectors run from PyMammotion`() {
        val source = requireNotNull(javaClass.getResource("/blufi-upstream.json")).readText()
        val payload = Regex("\"payload\": \"([0-9a-f]+)\"").find(source)!!.groupValues[1].bytes()
        val frame = Regex("\"frame\": \"([0-9a-f]+)\"").find(source)!!.groupValues[1].bytes()
        val crc = Regex("\"crc\": (\\d+)").find(source)!!.groupValues[1].toInt()
        assertArrayEquals(frame, Blufi().encode(payload, 512).single())
        assertEquals(crc, Blufi.crc(Blufi.crc(0, byteArrayOf(0, payload.size.toByte())), payload))
        val checked = frame.copyOf().also { it[1] = 2 } + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        assertArrayEquals(payload, Blufi().receive(checked))
    }
    @Test fun `fragmentation respects the ATT MTU even with MTU 23`() {
        for (mtu in listOf(23, 64, 247, 512)) {
            val payload = ByteArray(600) { it.toByte() }
            val frames = Blufi().encode(payload, mtu)
            assertTrue(frames.all { it.size <= mtu - 3 && (it[3].toInt() and 255) <= 255 })
            val receiver = Blufi()
            frames.dropLast(1).forEach { assertNull(receiver.receive(it)) }
            assertArrayEquals(payload, receiver.receive(frames.last()))
        }
    }
    @Test fun `corrupted CRC rejected`() {
        val payload = byteArrayOf(1, 2, 3)
        val crc = Blufi.crc(Blufi.crc(0, byteArrayOf(0, 3)), payload)
        val frame = byteArrayOf(0x4d, 2, 0, 3) + payload + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        frame[4] = 8
        assertThrows(IllegalArgumentException::class.java) { Blufi().receive(frame) }
    }
    @Test fun `encryption, length and sequence loss rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Blufi().receive(byteArrayOf(0x4d, 1, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Blufi().receive(byteArrayOf(0x4d, 0, 0, 5, 1)) }
        val receiver = Blufi()
        receiver.receive(byteArrayOf(0x4d, 0, 0, 1, 42))
        assertNull(receiver.receive(byteArrayOf(0x4d, 0, 0, 1, 42)))
        assertThrows(IllegalArgumentException::class.java) { receiver.receive(byteArrayOf(0x4d, 0, 2, 1, 42)) }
    }
    @Test fun `incomplete final fragment rejected and sequence wraps to zero`() {
        val receiver = Blufi()
        assertNull(receiver.receive(byteArrayOf(0x4d, 16, 255.toByte(), 3, 3, 0, 9)))
        assertArrayEquals(byteArrayOf(9, 8, 7), receiver.receive(byteArrayOf(0x4d, 0, 0, 2, 8, 7)))
        val incomplete = Blufi()
        incomplete.receive(byteArrayOf(0x4d, 16, 0, 3, 3, 0, 9))
        assertThrows(IllegalArgumentException::class.java) { incomplete.receive(byteArrayOf(0x4d, 0, 1, 1, 8)) }
    }
    @Test fun `STOP matches the real driver protobuf without blade command`() {
        val msg = LubaMsg.parseFrom(Commands().stop(1700000000000))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, msg.msgtype)
        assertEquals(MsgDevice.DEV_MOBILEAPP, msg.sender)
        assertEquals(MsgDevice.DEV_MAINCTL, msg.rcver)
        assertEquals(MsgAttr.MSG_ATTR_REQ, msg.msgattr)
        assertEquals(1700000000000, msg.timestamp)
        assertEquals(1, msg.seqs)
        assertTrue(msg.driver.hasTodevDevmotionCtrl())
        assertEquals(0, msg.driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(0, msg.driver.todevDevmotionCtrl.setAngularSpeed)
        assertFalse(msg.driver.hasMowCtrlByHand())
        // Zero values proto3 omitted, embedded empty DrvMotionCtrl retained.
        assertArrayEquals("08f3011007180120012801300162020a007880d095ffbc31".bytes(), msg.toByteArray())
    }
    @Test fun `movement bounded and STOP stays strictly zero`() {
        val move = LubaMsg.parseFrom(Commands().movement(50, -30, 1700000000000))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, move.msgtype)
        assertTrue(move.driver.hasTodevDevmotionCtrl())
        assertEquals(50, move.driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(30, move.driver.todevDevmotionCtrl.setAngularSpeed)
        assertFalse(move.driver.hasMowCtrlByHand())
        val stop = LubaMsg.parseFrom(Commands().stop(1))
        assertEquals(0, stop.driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(0, stop.driver.todevDevmotionCtrl.setAngularSpeed)
        assertThrows(IllegalArgumentException::class.java) { Commands().movement(Commands.PROTOCOL_MAX_LINEAR + 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Commands().movement(0, -Commands.PROTOCOL_MAX_ANGULAR - 1) }
        assertThrows(IllegalArgumentException::class.java) { Commands().movement(Int.MAX_VALUE, Int.MIN_VALUE) }
    }
    @Test fun `sync and subscription use the upstream fields`() {
        val c = Commands()
        val sync = LubaMsg.parseFrom(c.sync(1))
        assertEquals(2, sync.net.todevBleSync)
        assertEquals(MsgDevice.DEV_COMM_ESP, sync.rcver)
        val start = LubaMsg.parseFrom(c.reports(false, 2)).sys.todevReportCfg
        assertEquals(rpt_act.RPT_START, start.act)
        assertEquals(0, start.count)
        assertEquals(1000, start.period)
        assertEquals(10000, start.timeout)
        assertTrue(start.subList.contains(rpt_info_type.RIT_DEV_LOCAL))
        assertEquals(rpt_act.RPT_KEEP, LubaMsg.parseFrom(c.reports(true, 3)).sys.todevReportCfg.act)
        val vision = listOf(rpt_info_type.RIT_VISION_POINT, rpt_info_type.RIT_VIO, rpt_info_type.RIT_VISION_STATISTIC)
        assertTrue(vision.none { it in start.subList })
        val probe = LubaMsg.parseFrom(c.reports(false, 4, vision = true)).sys.todevReportCfg
        assertTrue(probe.subList.containsAll(vision + start.subList))
    }
    @Test fun `ACK and empty report do not refresh telemetry`() {
        val reducer = TelemetryReducer()
        assertFalse(reducer.accept(LubaMsg.parseFrom(Commands().sync(1)), 1, 1))
        val empty = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.getDefaultInstance())).build()
        assertFalse(reducer.accept(empty, 2, 2))
        assertNull(reducer.value.lastReportAt)
    }
    @Test fun `local position sign, scale and rate measured on receptions`() {
        val reducer = TelemetryReducer()
        val location = rpt_dev_location.newBuilder().setRealPosX(-123450).setRealPosY(234560).setRealToward(924000)
        val msg = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().addLocations(location))).build()
        assertTrue(reducer.accept(msg, 100, 0))
        assertTrue(reducer.accept(msg, 200, 1000))
        assertEquals(-12.345, reducer.value.x!!, 0.000001)
        assertEquals(23.456, reducer.value.y!!, 0.000001)
        assertEquals(357.6, reducer.value.heading!!, 0.000001)
        assertEquals(1.0, reducer.value.positionRate, 0.000001)
        assertEquals(0.0, reducer.rate(12000), 0.000001)
        assertNull(reducer.value.battery)
    }
    @Test fun `partial state keeps the position but does not refresh its timestamp`() {
        val reducer = TelemetryReducer()
        val loc = rpt_dev_location.newBuilder().setRealPosX(10000)
        val pos = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().addLocations(loc))).build()
        reducer.accept(pos, 100, 100)
        val dev = rpt_dev_status.newBuilder().setBatteryVal(72).setSysStatus(3)
        val report = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().setDev(dev))).build()
        reducer.accept(report, 200, 200)
        assertEquals(100L, reducer.value.lastPositionAt)
        assertEquals(200L, reducer.value.lastDeviceAt)
        assertEquals(72, reducer.value.battery)
        assertEquals(1.0, reducer.value.x!!, 0.0)
        val invalid = report.toBuilder().setSys(report.sys.toBuilder().setToappReportData(report.sys.toappReportData.toBuilder().setDev(dev.setBatteryVal(101)))).build()
        assertThrows(IllegalArgumentException::class.java) { reducer.accept(invalid, 300, 300) }
        assertEquals(200L, reducer.value.lastReportAt)
    }
}
private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
