package fr.luba.poc.planning

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Test APK only. No BLE ViewModel, activity, service or robot command.
 * Measures the route, then the preparation actually used by MapScreen.
 */
class PlannerDeviceBenchmark : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options=arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
        // Banc de suivi de trajet (passerelle autonome) : -e mode follower [-e stepM 0.25]
        // Obstacle bypass bench on the gateway: -e mode bypass [-e samples 12]
        if(options.getString("mode")=="bypass") {
            val lock=(targetContext.getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Luba:BypassBenchmark")
            lock.acquire(30*60_000L)
            try {
                FollowerDeviceBenchmark(context) { line -> sendStatus(0,Bundle().apply { putString("stream",line+"\n") }) }
                    .runBypass(options.getString("samples","12")!!.toInt())
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","COMPLETED bypass\n") })
            } catch(e:Exception) {
                finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAILED ${e.stackTraceToString()}\n") })
            } finally { if(lock.isHeld) lock.release() }
            return
        }
        if(options.getString("mode")=="follower") {
            val lock=(targetContext.getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Luba:FollowerBenchmark")
            lock.acquire(30*60_000L)
            try {
                FollowerDeviceBenchmark(context) { line -> sendStatus(0,Bundle().apply { putString("stream",line+"\n") }) }
                    .run(options.getString("stepM","0.25")!!.toDouble())
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","COMPLETED follower\n") })
            } catch(e:Exception) {
                finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAILED ${e.stackTraceToString()}\n") })
            } finally { if(lock.isHeld) lock.release() }
            return
        }
        val label=options.getString("label","local")!!.also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val output=File(targetContext.filesDir,"planner-device-$label.jsonl")
        val lock=(targetContext.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Luba:PlannerBenchmark")
        val limit=options.getString("timeoutSeconds","900")!!.toLong()*1_000_000_000L
        lock.acquire(limit/1_000_000+30_000)
        try {
            check(!output.exists()) { "Campagne existante : $label" }
            val json=JSONObject(context.assets.open("planner-reference-map.json").bufferedReader().use { it.readText() })
            val shapes=json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
                val s=a.getJSONObject(i);val points=s.getJSONArray("points")
                MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until points.length()).map { k ->
                    val p=points.getJSONArray(k);Point(p.getDouble(0),p.getDouble(1)) },s.getString("name"))
            } }
            val map=RobotMap(shapes,true,"reference-station")
            val store=BaseStore(targetContext,map)
            val base=requireNotNull(store.read()) { "No saved base for this map" }
            val accesses=store.accesses()
            // The Kotlin internal name contains the variant name (app_debug / app_fold).
            // Same public counter in bytecode, independent of the APK packaging.
            val queriesGetter=TrajectoryPlanner::class.java.methods.single {
                it.name.startsWith("getGeometryQueries") && it.parameterCount==0
            }
            fun queries(routes:TrajectoryPlanner)=queriesGetter.invoke(routes) as Long
            val names=options.getString("zones","Aire de jeux")!!.split('|')
            val repeats=options.getString("repeats","1")!!.toInt().also { require(it in 1..5) }
            for(iteration in 0 until repeats) for(name in names) {
                // "A+B": a single mission chaining several zones.
                val zoneIds=name.split('+').map { part -> shapes.single { it.kind==ShapeKind.ZONE && it.name.trim()==part }.id }
                val request=PlanningRequest(map,zoneIds,base.point,
                    config=PlanningConfig(spacingM=0.25,minimumCoverage=0.95,boundary=BoundaryMeaning.RECORDED_TRACE),
                    navigation=PlanningNavigation(accesses,base))
                val row=JSONObject().put("case",name).put("iteration",iteration).put("snapshotHash",request.snapshot().hash)
                    .put("device",Build.MODEL).put("androidSdk",Build.VERSION.SDK_INT)
                    .put("runtime",System.getProperty("java.vm.version")).put("label",label)
                    .put("base",base.toString()).put("cpuWakeLock",true)
                    .put("threadPriority",android.os.Process.getThreadPriority(android.os.Process.myTid()))
                    .put("appVersion",targetContext.packageManager.getPackageInfo(targetContext.packageName,0).versionName)
                val began=SystemClock.elapsedRealtimeNanos()
                fun checkpoint() { check(SystemClock.elapsedRealtimeNanos()-began<limit) { "Measurement budget exceeded" } }
                fun <T> measured(key:String,block:()->T):T {
                    val wall=SystemClock.elapsedRealtimeNanos();val cpu=Debug.threadCpuTimeNanos()
                    try { return block() } finally {
                        row.put(key+"WallMs",(SystemClock.elapsedRealtimeNanos()-wall)/1e6)
                            .put(key+"CpuMs",(Debug.threadCpuTimeNanos()-cpu)/1e6)
                    }
                }
                try {
                    val plan=measured("planning") { RoutePlanning.coverage(request,checkpoint=::checkpoint) }
                    val result=requireNotNull(plan.report)
                    row.put("status",result.selected.status.name).put("planHash",result.selected.planHash)
                        .put("phaseNanos",JSONObject(result.phaseNanos)).put("transitionQueries",result.transitionQueries)
                        .put("transitionCacheHits",result.transitionCacheHits)
                    check(result.selected.valid) { result.selected.diagnostics.joinToString() }
                    val routes=measured("navigation") { RoutePlanning.navigation(map,RobotEnvelope.LUBA2.halfWidthM+0.05,
                        shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },accesses,base=base,recordedBoundary=true) }
                    val stages=linkedMapOf<String,Double>()
                    var previous="Assemblage";var stageBegan=SystemClock.elapsedRealtimeNanos()
                    fun stage(value:String) {
                        checkpoint()
                        val key=value.substringBefore(" · ")
                        if(key!=previous) {
                            val now=SystemClock.elapsedRealtimeNanos()
                            stages[previous]=(stages[previous] ?: 0.0)+(now-stageBegan)/1e6
                            previous=key;stageBegan=now
                            sendStatus(0,Bundle().apply { putString("stream","$label / $name : $key\n") })
                        }
                    }
                    val simulation=measured("preparation") { MowingSimulation(routes,base.point,plan.preview,map,
                        SimConfig(baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM,
                            instantCharging=true),onPreparationProgress=::stage) }
                    stages[previous]=(stages[previous] ?: 0.0)+(SystemClock.elapsedRealtimeNanos()-stageBegan)/1e6
                    row.put("preparationStagesMs",JSONObject(stages)).put("geometryQueries",queries(routes))
                        .put("edgeCount",simulation.edges.size).put("cuttingDistanceM",simulation.total)
                        .put("ready",true)
                    val readsBefore=queries(routes)
                    val paths=(0..99).joinToString("\n") { progress ->
                        val scenario=simulation.withConfig(simulation.config.copy(initialProgress=progress.toDouble()))
                        val state=scenario.start()
                        val edge=scenario.edges[state.edge]
                        val cap=Math.toDegrees(kotlin.math.atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
                        val returning=scenario.advance(state.copy(position=state.transit.last(),phase=SimPhase.MOWING,
                            battery=20.0,heading=cap),0.01,simulateSignals=false)
                        "$progress:${state.edge}:${state.transit}:${returning.phase}:${returning.transit}"
                    }
                    row.put("resumeAndReturnHash",java.security.MessageDigest.getInstance("SHA-256")
                        .digest(paths.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
                        .put("playbackGeometryQueries",queries(routes)-readsBefore)
                    check(queries(routes)==readsBefore) { "Geometry rebuilt during the read" }
                } catch(e:Exception) {
                    row.put("ready",false).put("error",e.stackTraceToString())
                    throw e
                } finally {
                    row.put("totalWallMs",(SystemClock.elapsedRealtimeNanos()-began)/1e6)
                    sendStatus(0,Bundle().apply { putString("stream","RESULT ${row}\n") })
                    output.appendText(row.toString()+"\n")
                }
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","COMPLETED ${output.name}\n") })
        } catch(e:Exception) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAILED ${e.stackTraceToString()}\n") })
        } finally { if(lock.isHeld) lock.release() }
    }
}
