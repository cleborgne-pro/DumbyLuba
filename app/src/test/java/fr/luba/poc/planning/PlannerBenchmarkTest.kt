package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in campaign: no duration is used as a CI performance assertion. */
class PlannerBenchmarkTest {
    @Test fun exportComparableMissions() {
        val output=System.getProperty("plannerBenchmarkOutput") ?: return
        val root=File(output).also { it.mkdirs() }
        val synthetic=linkedMapOf(
            "rectangle" to request(),
            "concave" to request(listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,3.0),Point(4.0,3.0),Point(4.0,8.0),Point(0.0,8.0))),
            "obstacle" to request(holes=listOf(rectangle(4.0,2.0,2.0,2.0))),
            "couloir" to request(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(4.0,2.7),Point(8.0,2.7),Point(8.0,0.0),Point(12.0,0.0),Point(12.0,6.0),Point(8.0,6.0),Point(8.0,3.3),Point(4.0,3.3),Point(4.0,6.0),Point(0.0,6.0))),
            "micro" to request(rectangle(w=0.7,h=0.7)),
            "degenere" to request(listOf(Point(0.0,0.0),Point(2.0,2.0),Point(0.0,2.0),Point(2.0,0.0))),
            "ilots" to request().let { r -> r.copy(map=r.map.copy(shapes=r.map.shapes+MapShape(8,0,rectangle(13.0,0.0,5.0,5.0))),zoneIds=listOf(1,8)) },
            "precision" to request(rectangle(0.0,0.0,10.000000001,7.0)),
            "couverture_totale_impossible" to request(config=PlanningConfig(spacingM=0.35,minimumCoverage=1.0)),
            "multizone" to request().let { r -> r.copy(map=r.map.copy(shapes=listOf(MapShape(1,0,rectangle(w=5.0)),MapShape(8,0,rectangle(5.0,0.0,5.0,7.0)))),zoneIds=listOf(1,8)) }
        )
        fun rotated(r: PlanningRequest,angle: Double): PlanningRequest {
            val radians=Math.toRadians(angle)
            fun rotate(p: Point) = Point(p.x*kotlin.math.cos(radians)-p.y*kotlin.math.sin(radians),
                p.x*kotlin.math.sin(radians)+p.y*kotlin.math.cos(radians))
            return r.copy(map=r.map.copy(shapes=r.map.shapes.map { it.copy(points=it.points.map(::rotate)) }),start=rotate(r.start))
        }
        for(angle in listOf(7.0,37.0,89.0)) synthetic["rotation-$angle"]=rotated(request(rectangle(w=14.0,h=5.0)),angle)
        synthetic["trapeze"]=request(listOf(Point(0.0,0.0),Point(10.0,0.0),Point(8.0,7.0),Point(2.0,7.0)))
        synthetic["u"]=request(listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,8.0),Point(7.0,8.0),Point(7.0,3.0),Point(3.0,3.0),Point(3.0,8.0),Point(0.0,8.0)))
        synthetic["obstacles-decales"]=request(holes=listOf(rectangle(3.0,1.5,1.0,1.5),rectangle(6.0,4.0,1.0,1.5)))
        synthetic["obstacle-mince"]=request(holes=listOf(rectangle(4.0,2.0,0.02,2.0)))
        for(rate in listOf(20.0,90.0)) synthetic["pivot-$rate"]=synthetic.getValue("obstacle").let { it.copy(config=it.config.copy(turnDegreesPerSecond=rate,stopSeconds=1.0)) }
        synthetic["incertitude"]=request(config=PlanningConfig(spacingM=0.35,minimumCoverage=0.65,uncertaintyM=0.1))
        synthetic["espacement-large"]=request(config=PlanningConfig(spacingM=0.6,minimumCoverage=0.95))
        synthetic["multizone-libre"]=synthetic.getValue("multizone").let { it.copy(config=it.config.copy(optimizeZoneOrder=true)) }
        synthetic["poids-temps"]=synthetic.getValue("obstacle").let { it.copy(config=it.config.copy(weights=ScoreWeights(0.0,1.0,0.0,0.0,0.0))) }
        synthetic["poids-demi-tours"]=synthetic.getValue("obstacle").let { it.copy(config=it.config.copy(weights=ScoreWeights(1.0,0.3,10.0,1.0,2.0))) }
        val chosen=if(System.getProperty("plannerBenchmarkStation")=="true") stationCases()
            else if (System.getProperty("plannerBenchmarkReference") == "true") referenceCases() else synthetic
        val requested=System.getProperty("plannerBenchmarkCases")?.split(",")?.toSet()
        val interactiveReference=System.getProperty("plannerBenchmarkInteractiveReference")=="true"
        require(!interactiveReference || System.getProperty("plannerBenchmarkReference")=="true")
        val cases=chosen.filterKeys { requested==null || it in requested }.mapValues { (_,r) ->
            val config=if(interactiveReference) r.config.copy(spacingM=0.25,minimumCoverage=0.95,candidateBudget=8,localSearchBudget=24) else r.config
            r.copy(config=config.copy(indexedCoverage=System.getProperty("plannerBenchmarkIndexed") != "false",
                candidateBudget=System.getProperty("plannerBenchmarkCandidateBudget")?.toInt() ?: config.candidateBudget,
                localSearchBudget=System.getProperty("plannerBenchmarkLocalSearchBudget")?.toInt() ?: config.localSearchBudget))
        }
        require(cases.isNotEmpty())
        val repeats=System.getProperty("plannerBenchmarkRepeats")?.toInt() ?: if (System.getProperty("plannerBenchmarkReference") == "true") 1 else 3
        val warmups=System.getProperty("plannerBenchmarkWarmups")?.toInt() ?: if (repeats==1) 0 else 1
        require(repeats in 1..100 && warmups in 0..20)
        val management=Class.forName("java.lang.management.ManagementFactory")
        val bean=management.getMethod("getThreadMXBean").invoke(null)
        val threadType=Class.forName("java.lang.management.ThreadMXBean")
        require(threadType.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) == true)
        threadType.getMethod("setThreadCpuTimeEnabled",Boolean::class.javaPrimitiveType).invoke(bean,true)
        fun cpuTime() = threadType.getMethod("getCurrentThreadCpuTime").invoke(bean) as Long
        val records=mutableListOf<JSONObject>()
        File(root,"results.jsonl").writeText("")
        val strategies=System.getProperty("plannerBenchmarkStrategies")?.split(",")?.map { PlannerStrategy.valueOf(it) } ?: PlannerStrategy.entries
        val production=System.getProperty("plannerBenchmarkProduction")=="true"
        val previous=System.getProperty("plannerBenchmarkPreviousProduction")=="true"
        require(!previous || production)
        require(!production || PlannerStrategy.EXPERIMENTAL_LNS in strategies)
        fun calculate(input: PlanningRequest,strategy: PlannerStrategy) = if(production && strategy==PlannerStrategy.EXPERIMENTAL_LNS)
            (if(previous) CoverageEngine.planPreviousAutomatic(input) {} else RoutePlanning.calculate(input)) else CoverageEngine.plan(input,strategy)
        // Alternate the strategies per repetition so the warm JIT is not reserved for the last engine.
        for ((name,input) in cases) {
            for(strategy in strategies) repeat(warmups) { calculate(input,strategy) }
            val hashes=mutableMapOf<PlannerStrategy,String>()
            repeat(repeats) { iteration ->
              val paired=mutableMapOf<PlannerStrategy,CandidateResult>()
              val ordered=if(iteration%2==0) strategies else strategies.reversed()
              for(strategy in ordered) {
                val cpu=cpuTime(); val wall=System.nanoTime()
                val result=calculate(input,strategy)
                val wallMs=(System.nanoTime()-wall)/1e6; val cpuMs=(cpuTime()-cpu)/1e6
                val selected=result.selected; val m=selected.metrics
                hashes[strategy]?.let { assertEquals("Determinism $name/$strategy",it,selected.planHash) }
                hashes[strategy]=selected.planHash
                paired[strategy]=selected
                if (selected.valid) assertEquals(0,m!!.geometricViolations)
                val baseline=result.candidates.first()
                if (baseline.valid && selected.valid) {
                    assertTrue(selected.score!! <= baseline.score!!+1e-6)
                    assertTrue(m!!.coveredAreaM2+1e-6 >= baseline.metrics!!.coveredAreaM2)
                }
                val row=JSONObject().put("case",name).put("strategy",strategy.name).put("iteration",iteration)
                    .put("snapshotHash",result.snapshotHash).put("planHash",selected.planHash)
                    .put("status",selected.status.name).put("winner",selected.name).put("score",selected.score ?: JSONObject.NULL)
                    .put("cpuMs",cpuMs).put("wallMs",wallMs).put("phaseNanos",JSONObject(result.phaseNanos))
                    .put("transitionQueries",result.transitionQueries).put("transitionCacheHits",result.transitionCacheHits)
                    .put("approachDistanceM",selected.mission.firstOrNull()?.takeIf { !it.cutting }?.path?.let(::length) ?: JSONObject.NULL)
                    .put("diagnostics",selected.diagnostics).put("assumptions",result.assumptions)
                    .put("candidateStatuses",result.candidates.map { "${it.name}:${it.status}" })
                    .put("candidateMetrics",result.candidates.map { candidate -> JSONObject()
                        .put("name",candidate.name).put("status",candidate.status.name)
                        .put("diagnostics",candidate.diagnostics).put("planHash",candidate.planHash)
                        .put("score",candidate.score ?: JSONObject.NULL)
                        .put("distanceM",candidate.metrics?.distanceM ?: JSONObject.NULL)
                        .put("durationSeconds",candidate.metrics?.durationSeconds ?: JSONObject.NULL)
                        .put("transitM",candidate.metrics?.transitM ?: JSONObject.NULL)
                        .put("uTurns",candidate.metrics?.uTurns ?: JSONObject.NULL)
                        .put("coveredAreaM2",candidate.metrics?.coveredAreaM2 ?: JSONObject.NULL)
                        .put("extraVisitAreaM2",candidate.metrics?.extraVisitAreaM2 ?: JSONObject.NULL) })
                    .put("taskCount",selected.preview?.cuttingPaths?.size ?: JSONObject.NULL)
                    .put("mission",selected.mission.map { leg -> JSONObject().put("cutting",leg.cutting)
                        .put("path",leg.path.map { listOf(it.x,it.y) }) })
                selected.preview?.let { preview ->
                    // Diagnostic outside the timing: origin of the pieces, not
                    // a surface approximation nor a physical mowing measurement.
                    val runs=preview.runs.map { line(it).buffer(1e-7) }
                    val sequence=preview.cuttingPaths.map { path ->
                        val shape=line(path)
                        val run=runs.indexOfFirst { it.covers(shape) }
                        if(run<0) null else preview.runCellIds.getOrNull(run) ?: run
                    }
                    fun returns(values: List<Int?>): Int {
                        val seen=mutableSetOf<Int>();var previous: Int?=null;var count=0
                        values.forEach { cell ->
                            if(cell!=null && cell!=previous && !seen.add(cell)) count++
                            previous=cell
                        }
                        return count
                    }
                    row.put("cellReturns",returns(sequence.filterNotNull()))
                        .put("interruptedCells",returns(sequence))
                        .put("fragmentedRuns",runs.sumOf { run ->
                            (preview.cuttingPaths.count { path -> run.covers(line(path)) }-1).coerceAtLeast(0)
                        })
                }
                // Oracle of the FINITE graph of historical blocks, without class/zone lock.
                // It is not a bound of the continuous coverage optimum.
                val paths=baseline.preview?.cuttingPaths
                if(iteration==0 && strategy==PlannerStrategy.BASELINE && paths!=null && paths.size in 1..8 && baseline.valid) {
                    val terrain=PlanningTerrain(input)
                    val evaluator=MissionEvaluator(terrain,TransitionPlanner(terrain))
                    val oracle=NeighborhoodSequencer(terrain,evaluator) {}.exact(paths)
                    if(oracle.second.isFinite()) row.put("fixedTaskRelaxedOptimum",oracle.second)
                        .put("baselineAdditiveCost",evaluator.travelCost(paths))
                }
                if (m!=null) row.put("distanceM",m.distanceM).put("transitM",m.transitM).put("uTurns",m.uTurns)
                    .put("pivots",m.pivots).put("turnDegrees",m.turnDegrees).put("durationSeconds",m.durationSeconds)
                    .put("targetAreaM2",m.targetAreaM2).put("coveredAreaM2",m.coveredAreaM2).put("uncoveredAreaM2",m.uncoveredAreaM2)
                    .put("repeatedAreaM2",m.repeatedAreaM2).put("extraVisitAreaM2",m.extraVisitAreaM2).put("geometricViolations",m.geometricViolations)
                records.add(row)
                File(root,"results.jsonl").appendText(row.toString()+"\n")
              }
              val phase2=paired[PlannerStrategy.OPTIMIZED]
              if(phase2?.valid==true) for(strategy in listOf(PlannerStrategy.EXPERIMENTAL_JOINT,PlannerStrategy.EXPERIMENTAL_LNS,PlannerStrategy.ADVANCED)) {
                  paired[strategy]?.let { assertTrue("Regression $name/$strategy",CoverageEngine.preservesReference(it,phase2)) }
              }
            }
        }
        val keys=listOf("case","strategy","iteration","status","winner","score","distanceM","transitM","approachDistanceM","uTurns","durationSeconds",
            "coveredAreaM2","uncoveredAreaM2","repeatedAreaM2","extraVisitAreaM2","geometricViolations","cpuMs","wallMs","planHash","snapshotHash")
        File(root,"results.csv").writeText(keys.joinToString(",")+"\n"+records.joinToString("\n") { row -> keys.joinToString(",") { key -> row.opt(key)?.toString().orEmpty() } }+"\n")
        File(root,"manifest.json").writeText(JSONObject().put("schema",1).put("level","B sans recharge ni accostage")
            .put("warmups",warmups).put("repeats",repeats).put("seed",0).put("productionEntry",production)
            .put("previousProductionEntry",previous).put("runtime",System.getProperty("java.runtime.version"))
            .put("interactiveReference",interactiveReference)
            .put("recordedStationContext",System.getProperty("plannerBenchmarkStation")=="true")
            .put("strategies",strategies.map { it.name }).put("order","forward/backward alternation per repetition")
            .put("os",System.getProperty("os.name").orEmpty()+" "+System.getProperty("os.arch").orEmpty())
            .put("cpuMeasurement","ThreadMXBean current thread, moteur monothread")
            .put("requests",JSONObject(cases.mapValues { it.value.toString() })).toString(2))
    }
    internal fun referenceCases(caseName: String? = null): Map<String,PlanningRequest> {
        val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes=json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s=a.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0),p.getJSONArray(j).getDouble(1)) },s.getString("name"))
        } }
        return shapes.filter { it.kind==ShapeKind.ZONE && (caseName==null || it.name==caseName) }.associate { zone ->
            val holes=shapes.filter { it.kind==ShapeKind.NO_GO }
            val seed=Geometry.plan(zone.points,holes.map { it.points },spacing=0.35,recordedBoundary=true)
            zone.name to PlanningRequest(RobotMap(listOf(zone)+holes,true,"reference"),listOf(zone.id),seed.cuttingPaths.first().first(),
                config=PlanningConfig(spacingM=0.35,minimumCoverage=0.65,boundary=BoundaryMeaning.RECORDED_TRACE,candidateBudget=3,localSearchBudget=4))
        }
    }
    internal fun stationCases(): Map<String,PlanningRequest> {
        val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes=json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s=a.getJSONObject(i);val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0),p.getJSONArray(j).getDouble(1)) },s.getString("name"))
        } }
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(shapes.joinToString {
            "${it.id}:${it.type}:${it.points}" }.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(key=="8aed98769b9f22f971256b5b253ac323ffb56d6958c7730c17641fc7610d66da") { "Map differs from the saved reference frame" }
        val base=fr.luba.poc.simulation.MapBase(Point(0.14231226759294913,1.2859193243581544),
            "Placée manuellement",1.5,152.8624890921028,1.5)
        val zone=shapes.single { it.name=="Aire de jeux" }
        return mapOf("Aire de jeux · base enregistrée" to PlanningRequest(RobotMap(shapes,true,"reference-station"),
            listOf(zone.id),base.point,config=PlanningConfig(spacingM=0.25,minimumCoverage=0.95,
                boundary=BoundaryMeaning.RECORDED_TRACE,candidateBudget=8,localSearchBudget=24),
            navigation=PlanningNavigation(emptyList(),base)))
    }
}
