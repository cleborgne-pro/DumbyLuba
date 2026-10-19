package fr.luba.poc.map

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp

class NoGoCoverageRegressionTest {
    @Test fun referencePlayAreaNoGoCoverage() {
        val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText())
        val array=json.getJSONArray("shapes")
        val shapes=(0 until array.length()).map { i ->
            val s=array.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { k ->
                val a=p.getJSONArray(k); Point(a.getDouble(0),a.getDouble(1)) },s.getString("name"))
        }
        val zone=shapes.first { it.name=="Aire de jeux" }
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val plan=Geometry.plan(zone.points,holes,spacing=0.25,recordedBoundary=true)
        val f=GeometryFactory()
        fun line(p:List<Point>)=f.createLineString(p.map { Coordinate(it.x,it.y) }.toTypedArray())
        val passes=UnaryUnionOp.union(plan.passes.map { line(it).buffer(Geometry.CUT_WIDTH_M/2,16) })
        val covered=passes.union(UnaryUnionOp.union(plan.borders.map { line(it).buffer(Geometry.CUT_WIDTH_M/2,16) }))
        val robot=fr.luba.poc.obstacles.RobotEnvelope.LUBA2
        var physical=RecordedBoundary.physicalArea(zone.points,robot)
        holes.forEach { physical=physical.difference(RecordedBoundary.forbiddenArea(it,robot)) }
        val maneuverable=physical.buffer(-(robot.turnClearanceM+0.01),16)
        val borders=plan.borders.drop(plan.outerLoops)
        assertTrue("The no-go zones of the reference map must be present",borders.isNotEmpty())
        borders.forEachIndexed { index,path ->
            val loop=line(path)
            val polygon=f.createPolygon(path.map { Coordinate(it.x,it.y) }.toTypedArray())
            val band=polygon.buffer(Geometry.CUT_WIDTH_M,16).difference(polygon.buffer(Geometry.CUT_WIDTH_M/2,16))
            val missing=band.intersection(maneuverable).difference(covered).area
            // Boundary = side of the chassis: pass ends at the pivot radius of the tolerated area, a few cm² near the corners.
            assertTrue("Drivable band not covered around no-go zone $index: $missing m²",missing < 0.005)
            // This large no-go zone near the edge shows the link defect with the zigzags.
            if (polygon.covers(f.createPoint(Coordinate(-2.6793620586395264,6.179102420806885)))) {
                val gap=band.difference(covered).area
                assertTrue("Discontinuity between no-go zone and zigzags: $gap m²",gap < 0.0001)
            }
        }
        assertTrue(plan.passes.isNotEmpty())
    }
}
