package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host bench (-PpipelineProfile=true): computation, navigation and preparation
 * chained exactly as MapScreen does, on the reference map and the saved base.
 * It does not replace the Android measurement; it locates the hot spots.
 */
class PipelineProfileTest {
    @Test fun profileFullPipeline() {
        assumeTrue(System.getProperty("pipelineProfile")=="true")
        val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes=json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s=a.getJSONObject(i);val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0),p.getJSONArray(j).getDouble(1)) },s.getString("name"))
        } }
        val map=RobotMap(shapes,true,"reference-station")
        val base=MapBase(Point(0.14231226759294913,1.2859193243581544),"Placée manuellement",1.5,152.8624890921028,1.5)
        val filter=System.getProperty("pipelineProfileZones")?.split('|')
        val repeats=System.getProperty("pipelineProfileRepeats")?.toInt() ?: 1
        val zones=shapes.filter { it.kind==ShapeKind.ZONE && (filter==null || it.name.trim() in filter) }
        // Option: a single mission chaining all the selected zones.
        val groups=if(System.getProperty("pipelineProfileTogether")=="true") listOf(zones) else zones.map { listOf(it) }
        repeat(repeats) { iteration -> for(group in groups) {
            val zone=group.first().copy(name=group.joinToString("+") { it.name.trim() })
            val request=PlanningRequest(map,group.map { it.id },base.point,
                config=PlanningConfig(spacingM=0.25,minimumCoverage=0.95,boundary=BoundaryMeaning.RECORDED_TRACE),
                navigation=PlanningNavigation(emptyList(),base))
            val row=JSONObject().put("zone",zone.name.trim()).put("iteration",iteration)
            fun <T> measured(key: String,block: () -> T): T {
                val t=System.nanoTime()
                try { return block() } finally { row.put(key+"Ms",(System.nanoTime()-t)/1e6) }
            }
            try {
                val plan=measured("planning") { RoutePlanning.coverage(request) }
                val report=requireNotNull(plan.report)
                row.put("status",report.selected.status.name).put("planHash",report.selected.planHash)
                    .put("phasesMs",JSONObject(report.phaseNanos.mapValues { it.value/1e6 }))
                    .put("transitionQueries",report.transitionQueries).put("transitionHits",report.transitionCacheHits)
                report.selected.metrics?.let { m -> row.put("durationS",m.durationSeconds).put("transitM",m.transitM)
                    .put("distanceM",m.distanceM).put("coverage",m.coverage).put("uTurns",m.uTurns) }
                check(report.selected.valid) { report.selected.diagnostics.joinToString() }
                val routes=measured("navigation") { RoutePlanning.navigation(map,RobotEnvelope.LUBA2.halfWidthM+0.05,
                    shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },emptyList(),base=base,recordedBoundary=true) }
                val stages=linkedMapOf<String,Double>()
                var previous="Assemblage";var began=System.nanoTime()
                val sim=measured("preparation") { MowingSimulation(routes,base.point,plan.preview,map,
                    SimConfig(baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM,
                        instantCharging=true)) { value ->
                    val key=value.substringBefore(" · ")
                    if(key!=previous) { val now=System.nanoTime();stages[previous]=(stages[previous] ?: 0.0)+(now-began)/1e6;previous=key;began=now }
                } }
                stages[previous]=(stages[previous] ?: 0.0)+(System.nanoTime()-began)/1e6
                row.put("stagesMs",JSONObject(stages)).put("edges",sim.edges.size).put("cuttingM",sim.total)
                val estimate=MowingEstimate.fromSimulation(sim.withConfig(sim.config.copy(initialProgress=0.0))) {}
                row.put("simulatedSeconds",estimate.totalSeconds)
            } catch(e: Exception) { row.put("error",e.message) }
            println("PIPELINE "+row)
        } }
    }
}
