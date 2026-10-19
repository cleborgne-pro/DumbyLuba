package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LastKnownPositionTest {
    /** [status]: `rtk.status` (4 = FIX); pos_level at 1 as on the real robot in FIX. */
    private fun report(at: Long = 10000, status: Int = 4) = Telemetry(x=12.0,y=4.0,heading=90.0,
        rtkStatus=status,positioningLevel=1,lastRtkAt=at,lastPositionAt=at,lastReportAt=at,battery=45,robotState=11)
    @Test fun rtkOnlyReportInvalidatesQualityWithoutRefreshingPosition() {
        val reducer=fr.luba.poc.protocol.TelemetryReducer()
        val report=fr.luba.poc.proto.MctrlSysProto.report_info_data.newBuilder()
        report.rtkBuilder.setPosLevel(2)
        val message=fr.luba.poc.proto.LubaMsgProto.LubaMsg.newBuilder().setSys(
            fr.luba.poc.proto.MctrlSysProto.MctlSys.newBuilder().setToappReportData(report)).build()
        assertFalse(reducer.accept(message,20000,20000))
        assertEquals(2,reducer.value.positioningLevel)
        assertNull(reducer.value.lastPositionAt)
    }
    @Test fun degradedFixDoesNotReplacePositionButUpdatesBatteryAndCommunication() {
        val first=LastKnownPosition("a","Luba").accept(report())
        val lost=first.accept(report(20000,2).copy(x=999.0,battery=0))
        assertEquals(first.last,lost.last)
        assertEquals(0,lost.battery)
        assertEquals(20000L,lost.communicationAt)
    }
    @Test fun staleOrMissingQualityAndInvalidCoordinatesAreRejected() {
        val first=LastKnownPosition("a","Luba").accept(report())
        assertEquals(first.last,first.accept(report(20000).copy(lastRtkAt=10000)).last)
        assertEquals(first.last,first.accept(report(20000).copy(x=Double.NaN)).last)
        assertNull(LastKnownPosition("a","Luba").accept(report().copy(rtkStatus=null)).last)
    }
    @Test fun traceIsBoundedAndFrozenDuringOutage() {
        var known=LastKnownPosition("a","Luba")
        for(i in 1..4000) known=known.accept(report(i*100L))
        assertTrue(known.points.size <= 301)
        assertTrue(known.last!!.at-known.points.first().at >= 299000)
        assertEquals(known.points,known.accept(report(900000,3)).points)
    }
    @Test fun survivesRestartAndSeparatesRobotsAndCorruption() {
        val directory=Files.createTempDirectory("positions").toFile()
        try {
            val store=LastKnownPositionStore(directory)
            val known=LastKnownPosition("a","Luba").accept(report())
            store.save(known)
            assertEquals(known,LastKnownPositionStore(directory).read("a"))
            assertNull(store.read("b"))
            directory.listFiles()!!.single().writeText("truncated")
            assertNull(store.read("a"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun realFixWithPosLevelOneIsKeptAndPosLevelZeroAloneIsNot() {
        // Reading of 2026-10-15: status 4, pos_level 1. The old reading (pos_level = 0) never kept this point.
        assertNotNull(LastKnownPosition("a","Luba").accept(report()).last)
        assertNull(LastKnownPosition("a","Luba").accept(report(status=5).copy(positioningLevel=0)).last)
    }
}
