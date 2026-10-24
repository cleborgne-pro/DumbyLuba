package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import kotlin.math.*
data class Point(val x: Double, val y: Double) {
    // Plain sqrt: bounded metric coordinates, no overflow risk (hypot is slow).
    fun distance(p: Point): Double { val dx=x-p.x; val dy=y-p.y; return sqrt(dx*dx+dy*dy) }
}
data class MapShape(val id: Long, val type: Int, val points: List<Point>, val name: String = "")

/**
 * Kind of a map element, from the upstream `type` code. Every type known to PyMammotion (`PathType`,
 * `pymammotion/data/model/hash_list.py`, commit c0e5ada) has its kind; any other type is [UNKNOWN]: kept, drawn,
 * avoided by the moves when it is a closed outline, and refusing the local planning. What each kind does is carried by
 * [isContour], [isExclusion], [avoidedByMoves], [refusesPlanning] and [informational], never by a type list elsewhere.
 */
enum class ShapeKind(val types: Set<Int>, private val en: String, private val fr: String) {
    /** Type 0, mowing zone. */
    ZONE(setOf(0), "zone", "zone"),
    /** Type 1, no-go zone (keep-out outline). */
    NO_GO(setOf(1), "no-go zone", "zone interdite"),
    /** Type 2, passage between zones. */
    PASSAGE(setOf(2), "passage", "passage"),
    /** Type 3, transfer zone in front of the dock (`TRANSFER_ZONE`, ephemeral, hash 0): drawn only. */
    TRANSFER_ZONE(setOf(3), "dock transfer zone", "zone de transfert de la base"),
    /** Type 10, planned mowing lines / breakpoint (`LINE`): drawn only. */
    MOW_LINE(setOf(10), "planned mowing lines", "lignes de tonte prévues"),
    /** Type 12, clippings dump spot (`DUMP`, models with a collector): drawn only. */
    DUMP(setOf(12), "clippings dump spot", "point de vidage"),
    /** Type 13, drawing (`SVG` pattern): no boundary, acknowledged by [MapTransfer], drawn only if it carries points. */
    DRAWING(setOf(13), "drawing", "dessin"),
    /** Type 18, path driven during the current mowing (`DYNAMICS_LINE`): drawn only. */
    MOW_PROGRESS(setOf(18), "mowing progress", "progression de la tonte"),
    /** Types 19 and 20, corridor line and waypoints between zones (`CORRIDOR_LINE` / `CORRIDOR_POINT`, Yuka mini): drawn
     * only, never used as a passage by the local routes. */
    CORRIDOR(setOf(19, 20), "corridor between zones", "couloir entre zones"),
    /** Type 21, user-drawn virtual wall (`VIRTUAL_WALL`): a line the robot must not cross; the local planning and moves
     * do not know how to respect a line, so they refuse the map. */
    VIRTUAL_WALL(setOf(21), "virtual wall", "mur virtuel"),
    /** Type 22, sibling of the rectangular no-go zones (`NO_GO_ZONE_VARIANT`), meaning not confirmed upstream: avoided by
     * the moves, planning refused, like [VISION_ZONE]. */
    NO_GO_VARIANT(setOf(22), "no-go zone variant (unconfirmed)", "variante de zone interdite (non confirmée)"),
    /** Type 23, user-drawn rectangular no-go zone (`NO_GO_ZONE`, APK `updateNoGoZone`, seen on a LUBA 3): an exclusion
     * like [NO_GO] for the planning and the moves when it is a closed outline; with fewer than 3 points it refuses them. */
    NO_GO_RECT(setOf(23), "rectangular no-go zone", "zone interdite rectangulaire"),
    /**
     * No-Stop zone (type 25, PyMammotion `VISUAL_SAFETY_ZONE`): the robot keeps going there even when its vision module
     * sees an obstacle (the bumper still stops it), as shown in the Mammotion app (DumbyLuba#1). Ordinary ground for
     * DumbyLuba: neither avoided nor refused by the planning, only drawn.
     */
    NO_STOP_ZONE(setOf(25), "No-Stop zone", "zone No-Stop"),
    /** Vision obstacle zone (type 26, `VISUAL_OBSTACLE_ZONE`): never observed, meaning not confirmed, avoided by the moves, planning refused. */
    VISION_ZONE(setOf(26), "vision obstacle zone", "zone d'obstacle vision"),
    /** Any type PyMammotion does not know either. */
    UNKNOWN(emptySet(), "unknown element", "élément inconnu");

    val label: String get() = tr(en, fr)
    /** Closed boundaries (zones and no-go) are filled and need 3 points for the map to be complete. */
    val isContour: Boolean get() = this == ZONE || this == NO_GO
    /** Holes of the planning and the moves (with at least 3 points, see [MapShape.excludes]). */
    val isExclusion: Boolean get() = this == NO_GO || this == NO_GO_RECT
    /** Avoided by the moves as holes when they are closed outlines. */
    val avoidedByMoves: Boolean get() = isExclusion || this == NO_GO_VARIANT || this == VISION_ZONE || this == UNKNOWN
    /** Present on the map, the local mowing computation is refused (meaning not confirmed or not representable). */
    val refusesPlanning: Boolean get() = this == VIRTUAL_WALL || this == NO_GO_VARIANT || this == VISION_ZONE || this == UNKNOWN
    /** Shown on the map, ignored by the planning and the moves. */
    val informational: Boolean get() = this in setOf(TRANSFER_ZONE, MOW_LINE, DUMP, DRAWING, MOW_PROGRESS, CORRIDOR, NO_STOP_ZONE)

    companion object {
        /** Upstream type of the No-Stop zones (seen on a Luba 2 AWD 3000X, firmware 1.30.31.19). */
        const val NO_STOP_TYPE = 25
        /** Upstream types of the vision module zones: No-Stop (25) and vision obstacle (26, not observed yet). */
        val VISION_TYPES = setOf(NO_STOP_TYPE, 26)
        private val byType: Map<Int, ShapeKind> = entries.flatMap { kind -> kind.types.map { it to kind } }.toMap()
        /** Upstream types the application knows (every PyMammotion `PathType`); any other one is [UNKNOWN]. */
        val SUPPORTED_TYPES: Set<Int> = byType.keys
        fun from(type: Int): ShapeKind = byType[type] ?: UNKNOWN
    }
}

/** Kind derived from the `type` code carried by the frame. */
val MapShape.kind: ShapeKind get() = ShapeKind.from(type)
/** Hole of the planning and the moves: a no-go zone, or a rectangular no-go zone with a closed outline. */
val MapShape.excludes: Boolean get() = kind == ShapeKind.NO_GO || (kind == ShapeKind.NO_GO_RECT && points.size >= 3)
/** Element that keeps the local mowing computation from running ([ShapeKind.refusesPlanning], or an exclusion that is not a closed outline). */
val MapShape.refusesPlanning: Boolean get() = kind.refusesPlanning || (kind == ShapeKind.NO_GO_RECT && points.size < 3)
/** Element the moves cannot avoid: a virtual wall, or an element to avoid that is not a closed outline. */
val MapShape.refusesMoves: Boolean get() = kind == ShapeKind.VIRTUAL_WALL || (kind.avoidedByMoves && kind != ShapeKind.NO_GO && points.size < 3)
/** Why the local planning refuses [this] map, null when it may run. */
fun RobotMap.planningRefusal(): String? {
    val refused = shapes.filter { it.refusesPlanning }
    if (refused.isEmpty()) return null
    val list = refused.groupBy { it.kind to it.type }.keys.sortedBy { it.second }.joinToString { (kind, type) -> "${kind.label} ($type)" }
    return tr("Map with $list: meaning not confirmed or not representable, planning disabled",
        "Carte avec $list : sens non confirmé ou non représentable, calcul désactivé")
}
/** Why the local moves refuse [this] map, null when they may run. */
fun RobotMap.movesRefusal(): String? {
    val refused = shapes.filter { it.refusesMoves }
    if (refused.isEmpty()) return null
    val list = refused.groupBy { it.kind to it.type }.keys.sortedBy { it.second }.joinToString { (kind, type) -> "${kind.label} ($type)" }
    return tr("Map with $list that cannot be avoided: moves disabled", "Carte avec $list impossible à contourner : déplacements désactivés")
}
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
