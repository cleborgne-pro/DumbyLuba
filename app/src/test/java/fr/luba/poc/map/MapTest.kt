package fr.luba.poc.map
import org.junit.Test
import org.junit.Assert.*
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.LubaMsgProto.*
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.proto.CommonProto.CommDataCouple
class MapTest {
    private val zone=listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,8.0),Point(0.0,8.0))
    @Test fun `refuses a link crossing an exclusion`() {
        val hole=listOf(Point(4.0,2.0),Point(6.0,2.0),Point(6.0,6.0),Point(4.0,6.0))
        assertFalse(Geometry.safe(Point(2.0,4.0),Point(8.0,4.0),zone,listOf(hole),0.5))
        assertTrue(Geometry.safe(Point(1.0,1.0),Point(9.0,1.0),zone,listOf(hole),0.5))
    }
    @Test fun `all computed segments respect boundaries and exclusions`() {
        val m=Geometry.demo();val z=m.shapes[0].points;val h=listOf(m.shapes[1].points)
        val p=Geometry.plan(z,h)
        assertTrue(p.meters>0)
        p.runs.forEach { run -> run.zipWithNext().forEach { (a,b)->assertTrue(Geometry.safe(a,b,z,h,0.6)) } }
    }
    @Test fun `no route in a zone that is too small`() {
        try {Geometry.plan(listOf(Point(0.0,0.0),Point(0.2,0.0),Point(0.2,0.2)),emptyList());fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun `read-only map request matches upstream`() {
        val c=Commands();val msg=LubaMsg.parseFrom(c.mapData(42))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_NAV,msg.msgtype)
        assertEquals(MsgDevice.DEV_NAVIGATION,msg.rcver)
        assertEquals(8,msg.nav.todevGetCommondata.action)
        assertEquals(1,msg.nav.todevGetCommondata.subCmd)
        assertEquals(42L,msg.nav.todevGetCommondata.hash)
    }
    private fun msg(n:MctlNav)=LubaMsg.newBuilder().setNav(n).build()
    @Test fun `does not publish a partial boundary and deduplicates frames`() {
        val t=MapTransfer();val c=Commands()
        val root=NavGetHashListAck.newBuilder().setTotalFrame(1).setCurrentFrame(1).addDataCouple(42).build()
        val requests=t.accept(msg(MctlNav.newBuilder().setToappGethashAck(root).build()),c)
        assertEquals(3,requests.size)
        assertFalse(t.snapshot().complete)
        fun frame(i:Int)=NavGetCommDataAck.newBuilder().setHash(42).setType(2).setAction(8).setTotalFrame(2).setCurrentFrame(i).addDataCouple(CommDataCouple.newBuilder().setX(i.toFloat()).setY(2f)).build()
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(2)).build()),c)
        assertFalse(t.snapshot().complete)
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(1)).build()),c)
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(1)).build()),c)
        assertTrue(t.snapshot().complete)
        assertEquals(ShapeKind.PASSAGE,t.snapshot().shapes.single().kind)
        assertFalse(t.snapshot().shapes.single().kind.isContour)
        assertEquals(listOf(Point(1.0,2.0),Point(2.0,2.0)),t.snapshot().shapes.single().points)
    }
    @Test fun `unknown type blocks the computation even if the transfer finishes`() {
        val t=MapTransfer();val c=Commands()
        t.accept(msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setTotalFrame(1).setCurrentFrame(1).addDataCouple(42)).build()),c)
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(NavGetCommDataAck.newBuilder().setHash(42).setType(23).setTotalFrame(1).setCurrentFrame(1)).build()),c)
        assertFalse(t.snapshot().complete)
    }
    @Test fun `vision zones of a Luba 2 AWD X complete the map`() {
        // Issue DumbyLuba#1 (Luba-VP, firmware 1.30.31.19): 4 zones, 3 no-go, 4 passages and 11 rectangles of type 25.
        val t=MapTransfer();val c=Commands()
        t.accept(msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setTotalFrame(1).setCurrentFrame(1).addDataCouple(41).addDataCouple(42).addDataCouple(43)).build()),c)
        fun pt(x:Float,y:Float)=CommDataCouple.newBuilder().setX(x).setY(y)
        fun frame(hash:Long,type:Int)=NavGetCommDataAck.newBuilder().setHash(hash).setType(type).setAction(8).setTotalFrame(1).setCurrentFrame(1)
            .addDataCouple(pt(0f,0f)).addDataCouple(pt(1f,0f)).addDataCouple(pt(1f,1f)).addDataCouple(pt(0f,1f)).addDataCouple(pt(0f,0f)).build()
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(41,0)).build()),c)
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(42,25)).build()),c)
        assertFalse(t.snapshot().complete)
        t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(frame(43,26)).build()),c)
        val map=t.snapshot()
        assertTrue(map.complete)
        assertFalse(map.source.contains("unsupported"))
        assertEquals(listOf(ShapeKind.ZONE,ShapeKind.VISION_ZONE,ShapeKind.VISION_ZONE),map.shapes.sortedBy { it.id }.map { it.kind })
        assertFalse(ShapeKind.VISION_ZONE.isContour)
    }
}
