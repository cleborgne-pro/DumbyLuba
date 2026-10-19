package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Generates opt-in (-PfollowerRouteOutput=path) the reference route replayed by the on-phone tracking bench
 * ([fr.luba.poc.planning.FollowerDeviceBenchmark]): mowing of all zones of the reference map in one mission, from
 * the actual base, as the Fold prepares it and would then send it to the gateway.
 */
class FollowerRouteExport {
    @Test fun exportAllZonesMission() {
        val output = System.getProperty("followerRouteOutput")
        assumeTrue(output != null)
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        val map = RobotMap(shapes, true, "reference-station")
        val station = json.getJSONObject("station")
        val base = MapBase(Point(station.getDouble("x"), station.getDouble("y")), "Station de référence",
            MapBase.DEFAULT_INFLUENCE_RADIUS_M, station.getDouble("heading"), 1.5)
        val zones = shapes.filter { it.kind == ShapeKind.ZONE }
        val began = System.nanoTime()
        val request = PlanningRequest(map, zones.map { it.id }, base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base))
        val plan = RoutePlanning.coverage(request)
        val report = requireNotNull(plan.report)
        check(report.selected.valid) { report.selected.diagnostics.joinToString() }
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val sim = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true)) {}
        val physical = RoutePlanning.physical(map, emptyList(), recordedBoundary = true)
        val mission = RobotRoute.missionEdges(sim, sim.baseApproach, (sim.dockEntryHeading + 180) % 360, physical)
        val edges = JSONArray()
        mission.edges.forEach { e -> edges.put(JSONArray().put(e.a.x).put(e.a.y).put(e.b.x).put(e.b.y)
            .put(if (e.cutting) 1 else 0).put(if (e.bladesOn) 1 else 0).put(if (e.escape) 1 else 0)) }
        val out = JSONObject().put("zones", JSONArray(zones.map { it.name.trim() }))
            .put("base", JSONArray().put(base.point.x).put(base.point.y).put(base.entryHeadingDegrees).put(base.approachDistanceM))
            .put("edgeCount", mission.edges.size)
            .put("meters", mission.edges.sumOf { it.meters })
            .put("cuttingMeters", mission.edges.filter { it.cutting }.sumOf { it.meters })
            .put("hostPreparationMs", (System.nanoTime() - began) / 1e6)
            .put("edges", edges)
        File(output!!).writeText(out.toString())
        println("FOLLOWER_ROUTE edges=${mission.edges.size} meters=${out.getDouble("meters")} ms=${out.getDouble("hostPreparationMs")}")
    }
}
