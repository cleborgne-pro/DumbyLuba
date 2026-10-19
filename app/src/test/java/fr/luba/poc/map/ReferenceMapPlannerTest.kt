package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.atan2

/** Real map kept: each configuration runs on each of the seven zones. */
@RunWith(Parameterized::class)
class ReferenceMapPlannerTest(private val zoneName: String, private val zone: MapShape, private val config: Settings) {
    data class Settings(val spacing: Double, val mode: CoverageMode, val margin: Double = 0.6,
        val outer: Int = 1, val noGo: Int = 1, val borders: Boolean = true)

    @Test fun coverageAndBorderManeuversStayInsideTheRecordedFreeSurface() {
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val robot = RobotEnvelope.LUBA2
        val base = Point(-0.4975,1.13)
        val factory = GeometryFactory()
        fun polygon(points: List<Point>) = factory.createPolygon(
            (points + points.first()).map { Coordinate(it.x,it.y) }.toTypedArray())
        val zoneArea = polygon(zone.points)
        // Independent geometric proof: boundary = side of the chassis, a no-go loop passes at half a width plus 5 cm;
        // if it would leave the zone (no-go zone near the boundary), it is skipped, without refusal.
        val tour = config.mode == CoverageMode.TOUR
        val loopsOutside = if (config.mode.isSurvey || !config.borders || config.noGo == 0) 0 else holes.count { hole ->
            val exclusion = polygon(hole)
            zoneArea.covers(exclusion) && !zoneArea.covers(exclusion.buffer(RecordedBoundary.noGoLoopOffsetM(robot),16).boundary)
        }
        val preview = Geometry.plan(zone.points,holes,config.spacing,config.margin,config.mode,
            config.borders,config.outer,config.noGo,robot=robot,recordedBoundary=true,basePoint=base)
        if (config.mode.isSurvey) assertEquals(0,preview.noGoLoops)
        if (tour) {
            assertTrue(preview.runs.isEmpty())
            assertTrue(preview.passes.isEmpty())
            assertTrue(preview.borders.isNotEmpty())
            assertEquals(0,preview.noGoLoops)
            assertTrue(preview.borderKinds.none { it })
            assertEquals(0,preview.skippedBorderLoops)
        } else assertTrue("No pass",preview.runs.isNotEmpty())
        assertTrue(preview.meters.isFinite() && preview.meters > 0)
        assertEquals(config.mode,preview.mode)
        val nearest = preview.cuttingPaths.flatMap { it.zipWithNext() }.minOf { (a,b) -> Geometry.distance(base,a,b) }
        if (!tour) assertEquals("Start nearest to the station",nearest,preview.cuttingPaths.first().first().distance(base),1e-6)
        if (config.margin == 1.0 && zoneName.trim() in listOf("Figuier","Enfer","Fosse")) {
            // Fragments skipped by the geometry (passage narrower than the chassis, no-go zone near the boundary), never
            // by the zigzag margin: same number as with the default margin.
            val reference = Geometry.plan(zone.points,holes,config.spacing,0.6,config.mode,
                config.borders,config.outer,config.noGo,robot=robot,recordedBoundary=true,basePoint=base)
            assertEquals("The zigzag margin must no longer remove border fragments",reference.skippedBorderLoops,preview.skippedBorderLoops)
            assertTrue(preview.skippedBorderLoops >= loopsOutside)
        }
        if (!config.mode.isSurvey && !config.borders) assertTrue(preview.borders.isEmpty())
        else if (config.mode.isSurvey || config.outer > 0) assertTrue("Outer border missing",preview.outerLoops > 0)
        if (!config.mode.isSurvey && config.outer == 0) assertEquals(0,preview.outerLoops)
        if (config.noGo == 0) assertEquals(0,preview.noGoLoops)
        var centers: org.locationtech.jts.geom.Geometry = polygon(zone.points)
        var physical = RecordedBoundary.physicalArea(zone.points,robot)
        holes.forEach {
            centers = centers.difference(polygon(it))
            physical = physical.difference(RecordedBoundary.forbiddenArea(it,robot))
        }
        val envelope = TrajectoryEnvelope(physical,robot)
        val scheduled = listOf(false,true).flatMap { first -> listOf(false,true).flatMap { nearby ->
            val ordered = MowingOrder.apply(preview,first,nearby,base)
            assertEquals(preview.meters,ordered.cuttingPaths.sumOf { path ->
                path.zipWithNext().sumOf { (a,b) -> a.distance(b) }
            },1e-5)
            ordered.cuttingPaths
        } }
        (preview.runs + preview.borders + scheduled).forEach { path ->
            assertTrue(path.size >= 2)
            assertTrue(path.all { it.x.isFinite() && it.y.isFinite() })
            val line = factory.createLineString(path.map { Coordinate(it.x,it.y) }.toTypedArray())
            assertTrue("Reference point path outside the zone or inside an exclusion",centers.covers(line))
            val heading = if (path.first().distance(path.last()) < 1e-8)
                Math.toDegrees(atan2(path[1].y-path[0].y,path[1].x-path[0].x)) else null
            assertNull("Rectangle collision, including loop closures",
                envelope.pathCollision(path,finalHeading=heading))
        }
    }

    companion object {
        private val shapes: List<MapShape> = run {
            val json = JSONObject(requireNotNull(ReferenceMapPlannerTest::class.java
                .getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
            val array = json.getJSONArray("shapes")
            (0 until array.length()).map { i ->
                val shape = array.getJSONObject(i)
                val points = shape.getJSONArray("points")
                MapShape(shape.getString("id").toLong(),shape.getInt("type"),
                    (0 until points.length()).map { p ->
                        val point = points.getJSONArray(p)
                        Point(point.getDouble(0),point.getDouble(1))
                    },shape.getString("name"))
            }
        }
        @JvmStatic @Parameterized.Parameters(name="{index}: {0} — {2}")
        fun cases(): List<Array<Any>> {
            val zones = shapes.filter { it.kind == ShapeKind.ZONE }
            check(zones.size == 7)
            val settings = CoverageMode.entries.filter { !it.isSurvey }.flatMap { mode ->
                listOf(0.05,0.25,0.35).flatMap { spacing ->
                    listOf(1 to 1,2 to 4,4 to 2,4 to 4).map { (outer,noGo) ->
                        Settings(spacing,mode,outer=outer,noGo=noGo)
                    }
                } + listOf(Settings(0.25,mode,margin=0.3),Settings(0.25,mode,margin=1.0),
                    Settings(0.25,mode,borders=false),Settings(0.25,mode,outer=0,noGo=0),
                    Settings(0.25,mode,outer=0,noGo=1),Settings(0.25,mode,outer=1,noGo=0))
            }
            val tourSettings = listOf(0.05,0.25,0.35).flatMap { spacing ->
                listOf(0.3,0.6,1.0).map { margin -> Settings(spacing,CoverageMode.TOUR,margin,outer=4,noGo=4,borders=false) }
            }
            val gridSettings = listOf(1.0,3.0,8.0).flatMap { spacing ->
                listOf(0.3,0.6,1.0).map { margin -> Settings(spacing,CoverageMode.SURVEY_GRID,margin,outer=4,noGo=4,borders=false) }
            }
            return zones.flatMap { zone -> (settings+tourSettings+gridSettings).map { arrayOf(zone.name,zone,it) } }
        }
    }
}
