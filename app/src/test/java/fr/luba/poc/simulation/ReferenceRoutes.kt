package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.json.JSONObject
import kotlin.math.atan2

/** Reference map, saved base and routes prepared as by the map screen (execution benches). */
object ReferenceRoutes {
    fun referenceMap(): Pair<RobotMap, MapBase> {
        val json = JSONObject(ReferenceRoutes::class.java.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        return RobotMap(shapes, true, "reference-station") to
            MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    }

    /** Edges followed by actual mowing from the first cutting point: prepared mission then return to the approach point. */
    fun routeEdges(map: RobotMap, base: MapBase, zone: MapShape, turnStyle: TurnStyle = TurnStyle.FAST): Pair<List<SimEdge>, Point> =
        routeEdges(map, base, zone, plan(map, base, zone, turnStyle))

    /** Mowing plan of [zone] as prepared by the map screen (spacing 25 cm, recorded boundaries). */
    fun plan(map: RobotMap, base: MapBase, zone: MapShape, turnStyle: TurnStyle = TurnStyle.FAST): CoveragePlan =
        RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE, turnStyle = turnStyle),
            navigation = PlanningNavigation(emptyList(), base)))

    fun routeEdges(map: RobotMap, base: MapBase, zone: MapShape, plan: CoveragePlan): Pair<List<SimEdge>, Point> {
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        val last = engine.edges.last()
        val back = routes.between(last.b, engine.baseApproach, Math.toDegrees(atan2(last.b.y - last.a.y, last.b.x - last.a.x)),
            (engine.dockEntryHeading + 180) % 360)
        val edges = engine.edges + back.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-8 }.map { (a, b) -> SimEdge(a, b, false) }
        return edges to engine.baseApproach
    }

}
