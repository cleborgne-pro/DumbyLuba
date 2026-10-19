package fr.luba.poc.map

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.proto.MctrlNavProto.*
import org.junit.Assert.*
import org.junit.Test

class StationReaderTest {
    private fun report(charge: Int, heading: Int, x: Int = 32500, y: Int = -45000) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder()
        .setToappReportData(report_info_data.newBuilder()
            .setDev(rpt_dev_status.newBuilder().setChargeState(charge))
            .addLocations(rpt_dev_location.newBuilder().setRealPosX(x).setRealPosY(y).setRealToward(heading)))).build()

    @Test fun `approach position, received before charging starts, ignored`() {
        val reader=StationReader(MapStation(Point(3.25,-4.5),180.0,"Transmise par le robot"))
        // Approach: position 23 cm away, heading 50° askew, robot not charging yet.
        reader.accept(report(0, 1400000, x=34800, y=-43500), 100)
        // Contacts made: status report only, the last position is still the approach one.
        val devOnly=LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
            .setDev(rpt_dev_status.newBuilder().setChargeState(1)))).build()
        assertEquals(Point(3.25,-4.5), reader.accept(devOnly, 300)!!.point)
        // First position received while charging: describes the station.
        val docked=reader.accept(report(1, 900000, x=33300), 1100)!!
        assertEquals(Point(3.33,-4.5), docked.point)
    }

    @Test fun `RTK noise while charging does not move the station, a real move does`() {
        val reader=StationReader()
        val first=reader.accept(report(1, 900000), 100)!!
        // 1 cm, 1°: same station (same instance, the robot map does not change).
        assertSame(first, reader.accept(report(1, 910000, x=32600, y=-44950), 200))
        assertSame(first, reader.accept(report(2, 890000, x=32450, y=-45030), 300))
        // 8 cm: station moved.
        val moved=reader.accept(report(1, 900000, x=33300), 400)!!
        assertEquals(Point(3.33,-4.5),moved.point)
        // Heading turned by 4°: same axis; by 8°: new entry axis.
        assertSame(moved, reader.accept(report(1, 940000, x=33300), 450))
        assertEquals(172.0,reader.accept(report(1, 980000, x=33300), 500)!!.entryHeadingDegrees!!,1e-9)
        // Initial station without axis: the first observation provides it even at the same place.
        val noAxis=StationReader(MapStation(Point(3.25,-4.5),null,"Transmise par le robot"))
        assertEquals(180.0,noAxis.accept(report(1, 900000), 100)!!.entryHeadingDegrees!!,0.0)
    }

    @Test fun `map reading keeps the initial station without a new station frame`() {
        val initial=MapStation(Point(3.25,-4.5),null,"Transmise par le robot")
        val transfer=MapTransfer(initialStation=initial)
        transfer.accept(LubaMsg.getDefaultInstance(),fr.luba.poc.protocol.Commands())
        assertEquals(initial,transfer.snapshot().station)
    }
    @Test fun `docked robot provides position and axis opposite to its heading`() {
        val reader=StationReader()
        val station=reader.accept(report(1, 900000), 100)!!
        assertEquals(Point(3.25,-4.5),station.point)
        assertEquals(180.0,station.entryHeadingDegrees!!,0.0)
        assertEquals(90.0,reader.accept(report(2,1800000),200)!!.entryHeadingDegrees!!,0.0)
    }
    @Test fun `robot not charging does not move the base`() {
        val reader=StationReader()
        assertNull(reader.accept(report(0,900000),100))
    }
    @Test fun `new station position invalidates the old axis without reusing a stale observation`() {
        val reader=StationReader()
        reader.accept(report(1,900000),100)
        val nav=LubaMsg.newBuilder().setNav(MctlNav.newBuilder().setToappChgpileto(
            chargePileType.newBuilder().setX(8f).setY(9f))).build()
        val station=reader.accept(nav,200)!!
        assertEquals(Point(8.0,9.0),station.point)
        assertNull(station.entryHeadingDegrees)
    }
}
