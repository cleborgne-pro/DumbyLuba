package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Station route ("Leave the station", "Recharge") on the reference map, base outside the zones. */
class StationRouteTest {
    private val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
    private val map=json.getJSONArray("shapes").let { a -> RobotMap((0 until a.length()).map { i ->
        val s=a.getJSONObject(i); val p=s.getJSONArray("points")
        MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { k ->
            val v=p.getJSONArray(k); Point(v.getDouble(0),v.getDouble(1)) },s.getString("name"))
    },true) }
    private val station=json.getJSONObject("station")
    private val base=MapBase(Point(station.getDouble("x"),station.getDouble("y")),"Position et axe observés en recharge",
        entryHeadingDegrees=station.getDouble("heading"))
    private val routes=RoutePlanning.mission(map,emptyList(),emptyList(),base)
    private fun engine(robot: Point): MowingSimulation =
        RoutePlanning.station(routes,map,base,robot,SimConfig(baseEntryHeadingDegrees=base.entryHeadingDegrees,instantCharging=true))

    @Test(timeout=60000) fun dockedRobotOnBaseOutsideZonesGetsStationRoute() {
        assertTrue("The reference base is outside the zones",
            map.shapes.filter { it.kind==ShapeKind.ZONE }.none { Geometry.inside(base.point,it.points) })
        // Robot charging: its position is exactly the base one (frame observed while charging).
        for(robot in listOf(base.point,Point(base.point.x+0.05,base.point.y),Point(base.point.x,base.point.y-0.1))) {
            val engine=engine(robot)
            assertTrue(engine.edges.isNotEmpty())
            assertEquals(base.point,engine.base)
            assertNull(routes.baseZoneViolation(listOf(engine.edges.first().a)+engine.edges.map { it.b }))
        }
    }

    @Test(timeout=60000) fun robotInZoneKeepsItsOwnPosition() {
        val zone=map.shapes.first { it.kind==ShapeKind.ZONE && it.name=="Piscine" }
        val robot=org.locationtech.jts.geom.GeometryFactory().createPolygon((zone.points+zone.points.first())
            .map { org.locationtech.jts.geom.Coordinate(it.x,it.y) }.toTypedArray()).interiorPoint.let { Point(it.x,it.y) }
        assertTrue(routes.safe(robot,robot))
        assertEquals(robot,engine(robot).edges.last().b)
    }
}
