package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import kotlin.math.*
data class Point(val x: Double, val y: Double) {
    // Plain sqrt: bounded metric coordinates, no overflow risk (hypot is slow).
    fun distance(p: Point): Double { val dx=x-p.x; val dy=y-p.y; return sqrt(dx*dx+dy*dy) }
}
data class MapShape(val id: Long, val type: Int, val points: List<Point>, val name: String = "")

/** Kind of a map boundary, from the upstream `type` code (0 = zone, 1 = no-go, 2 = passage). */
enum class ShapeKind {
    ZONE, NO_GO, PASSAGE, OBSTACLE;

    /** Closed boundaries (zones and no-go) are filled; obstacles stay drawn as lines. */
    val isContour: Boolean get() = this == ZONE || this == NO_GO

    companion object {
        fun from(type: Int): ShapeKind = when (type) {
            0 -> ZONE
            1 -> NO_GO
            2 -> PASSAGE
            else -> OBSTACLE
        }
    }
}

/** Kind derived from the `type` code carried by the frame. */
val MapShape.kind: ShapeKind get() = ShapeKind.from(type)
data class MapStation(val point: Point, val entryHeadingDegrees: Double?, val origin: String)
/**
 * Boundary map. [mammotionShapes]: original Mammotion boundaries when [shapes] carries local corrections
 * ([MapCorrections]); the data attached to the robot map (base, access, heatmap) stay keyed on them.
 */
data class RobotMap(val shapes: List<MapShape> = emptyList(), val complete: Boolean = false, val source: String = tr("No map", "Aucune carte"), val station: MapStation? = null,
    val mammotionShapes: List<MapShape>? = null) {
    /** Boundaries as Mammotion recorded them (same as [shapes] without local correction). */
    val originalShapes: List<MapShape> get() = mammotionShapes ?: shapes
}
data class Preview(val runs: List<List<Point>>, val meters: Double, val angle: Int, val connectors: Int, val mode: CoverageMode = CoverageMode.LINES, val borders: List<List<Point>> = emptyList(), val outerLoops: Int = 0, val noGoLoops: Int = 0, val zoneIds: List<Long> = emptyList(), val zoneAngles: List<Int> = emptyList(), val passes: List<List<Point>> = emptyList(), val skippedBorderLoops: Int = 0, val startWithBorders: Boolean = false, val borderKinds: List<Boolean> = emptyList(),
    val scheduledPaths: List<List<Point>>? = null, val runCellIds: List<Int> = emptyList(),
    /** Border entries verified by the planner for the recorded boundaries. */
    val borderAnchors: List<Point> = emptyList(),
    /** Experimental plan: preview and simulation only, never a robot command. */
    val localOnly: Boolean = false,
    /** Planner actually requested for this preview, kept while it runs (persisted in checkpoints and compared: not translated). */
    val plannerLabel: String = "Référence",
    /** Oriented links computed with the plan, between consecutive passes. Only a hint: the preparation validates them
     * again (area, chassis, headings) before use. */
    val links: List<List<Point>>? = null,
    /** Passes of decreasing height ([CutPasses]): height (mm) of each path of [cuttingPaths], empty for a single pass at
     * the task height. */
    val pathHeights: List<Int> = emptyList(),
    /** Heights of the successive passes, from the first to the last; empty for a single pass. */
    val cutHeights: List<Int> = emptyList()) {
    val cuttingPaths: List<List<Point>> get() = scheduledPaths ?: if (startWithBorders) borders + runs else runs + borders
}
object Geometry {
    /** Approximate cutting width of the LUBA 2 AWD 1000, in meters: used only for display (stripes). Adjustable in the interface. */
    const val CUT_WIDTH_M = 0.40
    fun inside(p: Point, poly: List<Point>): Boolean {
        var yes=false
        for(i in poly.indices) { val a=poly[i]; val b=poly[(i+1)%poly.size]
            if ((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) yes=!yes
        }; return yes
    }
    fun distance(p: Point,a: Point,b: Point): Double {
        val dx=b.x-a.x;val dy=b.y-a.y
        val t=if(dx*dx+dy*dy==0.0) 0.0 else (((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
        return p.distance(Point(a.x+t*dx,a.y+t*dy))
    }
    private fun cross(a:Point,b:Point,c:Point)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun segmentDistance(a:Point,b:Point,c:Point,d:Point):Double {
        if(cross(a,b,c)*cross(a,b,d)<0 && cross(c,d,a)*cross(c,d,b)<0) return 0.0
        return minOf(distance(a,c,d),distance(b,c,d),distance(c,a,b),distance(d,a,b))
    }
    fun safe(a:Point,b:Point,zone:List<Point>,holes:List<List<Point>>,margin:Double):Boolean {
        if(!inside(a,zone)||!inside(b,zone)||holes.any { inside(a,it)||inside(b,it) }) return false
        return (listOf(zone)+holes).all { poly -> poly.indices.all { i -> segmentDistance(a,b,poly[i],poly[(i+1)%poly.size]) >= margin } }
    }
    fun plan(zone:List<Point>,holes:List<List<Point>>,spacing:Double=0.5,margin:Double=0.6,
             mode:CoverageMode=CoverageMode.LINES,borders:Boolean=true,
             borderPasses:Int=1,noGoPasses:Int=1,
             obstacles:List<fr.luba.poc.obstacles.ObstacleDisc> = emptyList(),
             robot:fr.luba.poc.obstacles.RobotEnvelope = fr.luba.poc.obstacles.RobotEnvelope.LUBA2,
             recordedBoundary:Boolean=false, basePoint:Point?=null,
             outerOffsetM:Double=CoveragePlanner.RECORDED_OUTER_OFFSET_M, onProgress:(Preview)->Unit={}):Preview =
        CoveragePlanner.plan(zone,holes,spacing,margin,mode,borders,borderPasses,noGoPasses,obstacles,robot,recordedBoundary,basePoint,onProgress,outerOffsetM=outerOffsetM)
    fun demo()=RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(16.0,0.0),Point(16.0,11.0),Point(0.0,11.0))),MapShape(2,1,listOf(Point(6.0,4.0),Point(9.0,4.0),Point(9.0,7.0),Point(6.0,7.0)))),true,"DÉMONSTRATION · " + tr("fictional map", "carte fictive"))
}
