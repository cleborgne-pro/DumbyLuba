package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

/** The fast index must never accept what JTS refuses (beyond the tolerance),
 * and must agree with JTS on almost all real queries.
 */
class AreaIndexTest {
    private val factory = GeometryFactory()
    private val shapes = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    /** Free area of the reference point, as in navigation: recorded zone, exclusions, margin. */
    private fun free(name: String): org.locationtech.jts.geom.Geometry {
        val robot = RobotEnvelope.LUBA2
        val zone = shapes.single { it.name.trim() == name }
        var physical = RecordedBoundary.physicalArea(zone.points, robot)
        shapes.filter { it.kind == ShapeKind.NO_GO }.forEach { physical = physical.difference(RecordedBoundary.forbiddenArea(it.points, robot)) }
        return physical.buffer(-robot.halfWidthM)
    }
    private fun line(a: Point, b: Point) = factory.createLineString(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y)))

    @Test fun segmentsAndTrianglesAgreeWithJts() {
        for (name in listOf("Fosse", "Piscine", "Enfer")) {
            val area = free(name)
            val index = AreaIndex(area, 1e-5)
            val exact = PreparedGeometryFactory.prepare(area.buffer(1e-5))
            val loose = PreparedGeometryFactory.prepare(area.buffer(1e-4))
            val env = area.envelopeInternal
            val random = java.util.Random(name.hashCode().toLong())
            fun point() = Point(env.minX + random.nextDouble() * env.width, env.minY + random.nextDouble() * env.height)
            // Edge vertices: tangent segments typical of the visibility graph.
            val vertices = area.coordinates.map { Point(it.x, it.y) }
            var disagreements = 0; var total = 0; var covered = 0
            repeat(4000) { k ->
                val a = if (k % 3 == 0) vertices[random.nextInt(vertices.size)] else point()
                val b = if (k % 2 == 0) vertices[random.nextInt(vertices.size)] else
                    Point(a.x + (random.nextDouble() - 0.5) * 12, a.y + (random.nextDouble() - 0.5) * 12)
                val fast = index.covers(a, b)
                if (fast) assertTrue("$name: segment $a → $b accepted outside the area", loose.covers(line(a, b)))
                if (fast != exact.covers(line(a, b))) disagreements++
                if (fast) covered++
                total++
                val c = point()
                val triangle = factory.createPolygon(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y), Coordinate(c.x, c.y), Coordinate(a.x, a.y)))
                val hull = triangle.convexHull()
                val fastTriangle = index.covers(a, b, c)
                if (fastTriangle) assertTrue("$name: triangle accepted outside the area", loose.covers(hull))
                if (fastTriangle != exact.covers(hull)) disagreements++
                total++
                val p = point()
                assertEquals(exact.covers(factory.createPoint(Coordinate(p.x, p.y))), index.covers(p))
            }
            println("AREAINDEX $name: $disagreements disagreements out of $total, $covered segments covered")
            assertTrue("Sample too trivial", covered > 300)
            assertTrue("$name: $disagreements disagreements out of $total", disagreements <= total / 200)
        }
    }

    @Test fun convexClearanceIsAtLeastAsStrictAsJts() {
        val area = free("Fosse")
        val index = AreaIndex(area, 0.0)
        val prepared = PreparedGeometryFactory.prepare(area)
        val env = area.envelopeInternal
        val random = java.util.Random(7)
        var accepted = 0; var disagreements = 0
        repeat(4000) {
            val cx = env.minX + random.nextDouble() * env.width; val cy = env.minY + random.nextDouble() * env.height
            val heading = random.nextDouble() * 2 * PI
            val corners = listOf(0.4 to 0.26, 0.4 to -0.26, -0.3 to -0.26, -0.3 to 0.26).map { (x, y) ->
                Coordinate(cx + x * cos(heading) - y * sin(heading), cy + x * sin(heading) + y * cos(heading)) }
            val polygon = factory.createPolygon((corners + corners.first()).toTypedArray())
            val clearance = if (random.nextBoolean()) 0.0 else random.nextDouble() * 0.05
            val fast = index.containsConvex(DoubleArray(4) { corners[it].x }, DoubleArray(4) { corners[it].y }, 4, clearance)
            // Reference: polygon grown exactly by the margin (fine disc), strict inclusion.
            val reference = prepared.containsProperly(if (clearance == 0.0) polygon else polygon.buffer(clearance, 32))
            if (fast) { accepted++; assertTrue("Pose accepted too close to the edge", reference) }
            if (fast != reference) disagreements++
            val r = random.nextDouble() * 0.6
            val disc = index.containsDisc(cx, cy, r)
            if (disc) assertTrue(prepared.containsProperly(factory.createPoint(Coordinate(cx, cy)).buffer(r, 32)))
        }
        println("AREAINDEX convex: $accepted accepted, $disagreements disagreements")
        assertTrue(accepted > 100)
        assertTrue("$disagreements disagreements", disagreements <= 20)
    }

    @Test fun emptyAndDisjointSurfaces() {
        assertFalse(AreaIndex(factory.createPolygon()).covers(Point(0.0, 0.0)))
        val two = factory.createMultiPolygon(arrayOf(
            factory.createPolygon(arrayOf(Coordinate(0.0, 0.0), Coordinate(4.0, 0.0), Coordinate(4.0, 4.0), Coordinate(0.0, 4.0), Coordinate(0.0, 0.0))),
            factory.createPolygon(arrayOf(Coordinate(8.0, 0.0), Coordinate(12.0, 0.0), Coordinate(12.0, 4.0), Coordinate(8.0, 4.0), Coordinate(8.0, 0.0)))))
        val index = AreaIndex(two)
        assertTrue(index.covers(Point(1.0, 1.0), Point(3.0, 3.0)))
        assertFalse(index.covers(Point(2.0, 2.0), Point(10.0, 2.0)))
        assertTrue("Edge included within the tolerance", index.covers(Point(0.0, 0.0), Point(4.0, 0.0)))
        assertFalse(index.covers(Point(1.0, 1.0), Point(3.0, 1.0), Point(9.0, 1.0)))
        assertTrue(index.covers(Point(1.0, 1.0), Point(1.0, 1.0), Point(3.0, 3.0)))
    }
}
