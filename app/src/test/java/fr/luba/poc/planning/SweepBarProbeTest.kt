package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in bench (-PsweepBarProbe=true): cell order on the reference map (back and forth from one side to the
 * other, alternation of the checkerboard orientations), duration, transit and coverage, for each zone and mode. */
class SweepBarProbeTest {
    @Test fun probe() {
        assumeTrue(System.getProperty("sweepBarProbe")=="true")
        val json=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes=json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s=a.getJSONObject(i);val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0),p.getJSONArray(j).getDouble(1)) },s.getString("name"))
        } }
        val map=RobotMap(shapes,true,"reference-station")
        val base=MapBase(Point(0.14231226759294913,1.2859193243581544),"Placée manuellement",1.5,152.8624890921028,1.5)
        val filter=System.getProperty("sweepBarZones")?.split('|')
        val modes=System.getProperty("sweepBarModes")?.split('|')?.map { CoverageMode.valueOf(it) }
            ?: listOf(CoverageMode.LINES,CoverageMode.CHECKERBOARD,CoverageMode.OPTIMIZED)
        for(zone in shapes.filter { it.kind==ShapeKind.ZONE && (filter==null || it.name.trim() in filter) }) for(mode in modes) {
            val request=PlanningRequest(map,listOf(zone.id),base.point,
                config=PlanningConfig(spacingM=0.25,minimumCoverage=0.95,boundary=BoundaryMeaning.RECORDED_TRACE),
                navigation=PlanningNavigation(emptyList(),base))
            val begin=System.nanoTime()
            val result=RoutePlanning.calculate(request,mode)
            val ms=(System.nanoTime()-begin)/1e6
            val s=result.selected
            val m=s.metrics
            val order=s.preview?.let { SweepBarMetrics.of(it) }
            println("PROBE ${zone.name.trim()} $mode valid=${s.valid} name=${s.name} duration=${m?.durationSeconds?.let { "%.0f".format(it) }} " +
                "transit=${m?.transitM?.let { "%.1f".format(it) }} coverage=${m?.coverage?.let { "%.4f".format(it) }} uTurns=${m?.uTurns} " +
                "cells=${order?.cells} backtracks=${order?.backtracks} layerSwitches=${order?.layerSwitches} ms=${"%.0f".format(ms)}")
            System.getProperty("sweepBarRender")?.let { dir -> render(java.io.File(dir,"${zone.name.trim().replace(' ','_')}-$mode.svg"),
                listOf(zone)+shapes.filter { it.kind==ShapeKind.NO_GO },s.mission) }
            println("LEGS "+s.mission.filter { !it.cutting }.map { leg -> leg.path.zipWithNext().sumOf { (a,b) -> a.distance(b) } }.sortedDescending().take(12).joinToString { "%.1f".format(it) })
        }
    }

    private fun render(file: java.io.File,shapes: List<MapShape>,mission: List<MissionLeg>) {
        val zone=shapes.first().points
        val minX=zone.minOf { it.x }-1;val maxX=zone.maxOf { it.x }+1;val minY=zone.minOf { it.y }-1;val maxY=zone.maxOf { it.y }+1
        val scale=1400/maxOf(maxX-minX,maxY-minY)
        val w=((maxX-minX)*scale).toInt();val h=((maxY-minY)*scale).toInt()
        fun xy(p: Point)="%.1f,%.1f".format(java.util.Locale.ROOT,(p.x-minX)*scale,(maxY-p.y)*scale)
        val out=StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='$w' height='$h'><rect width='$w' height='$h' fill='white'/>")
        out.append("<polygon points='${zone.joinToString(" ") { xy(it) }}' fill='#dcf0dc'/>")
        shapes.drop(1).forEach { out.append("<polygon points='${it.points.joinToString(" ") { p -> xy(p) }}' fill='#f4b0b0'/>") }
        val cutting=mission.count { it.cutting }
        var k=0;var transit=0
        mission.forEach { leg ->
            val pts=leg.path.joinToString(" ") { xy(it) }
            if(leg.cutting) {
                val f=k++.toDouble()/maxOf(1,cutting-1)
                out.append("<polyline points='$pts' fill='none' stroke='hsl(${(240*(1-f)).toInt()},85%,42%)' stroke-width='2'/>")
            } else {
                transit++
                out.append("<polyline points='$pts' fill='none' stroke='black' stroke-opacity='0.75' stroke-width='3' stroke-dasharray='9,6'/>")
                if(leg.path.zipWithNext().sumOf { (a,b) -> a.distance(b) }>4) {
                    val mid=xy(leg.path[leg.path.size/2]).split(',')
                    out.append("<text x='${mid[0]}' y='${mid[1]}' font-size='26' font-weight='bold' font-family='sans-serif'>$transit</text>")
                }
            }
        }
        file.parentFile.mkdirs();file.writeText(out.append("</svg>").toString())
    }
}
/** Order measurements of a prepared route: cells started while a cell of the same orientation remained entirely
 * behind them (in the best sweep direction), and orientation changes between two cells. */
internal data class SweepBarMetrics(val cells: Int,val backtracks: Int,val layerSwitches: Int) {
    companion object {
        fun of(preview: Preview,slack: Double = 0.5): SweepBarMetrics {
            // Direction of a path: that of the raw passes ([Preview.passes]) its segments cover; the perpendicular links
            // between passes do not count (they dominate in a crumb).
            fun angle(a: Point,b: Point)=((Math.round(Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))/15.0).toInt()*15)%180+180)%180
            val passes=preview.passes.filter { it.size>=2 }.map { it.first() to it.last() }
            fun onPass(a: Point,b: Point)=passes.firstOrNull { (p,q) -> Geometry.distance(a,p,q)<1e-6 && Geometry.distance(b,p,q)<1e-6 }
            fun layer(path: List<Point>): Int {
                val bins=path.zipWithNext().mapNotNull { (a,b) -> onPass(a,b)?.let { (p,q) -> angle(p,q) to a.distance(b) } }
                    .ifEmpty { path.zipWithNext().map { (a,b) -> angle(a,b) to a.distance(b) } }
                return bins.groupBy({ it.first },{ it.second }).maxBy { it.value.sum() }.key
            }
            data class Cell(val id: Int,val layer: Int,val paths: MutableList<List<Point>>)
            val visits=mutableListOf<Cell>()
            for(path in preview.cuttingPaths) {
                val index=preview.runs.indexOfFirst { it==path || it.reversed()==path }
                if(index<0) continue
                val id=preview.runCellIds.getOrNull(index) ?: index
                if(visits.lastOrNull()?.id==id) visits.last().paths.add(path) else visits.add(Cell(id,layer(path),mutableListOf(path)))
            }
            // Checkerboard: each cell assigned to the nearest of the two directions (that of the longest cell and its
            // perpendicular); a crumb dominated by its links does not count as a third direction.
            visits.maxByOrNull { c -> c.paths.sumOf { p -> p.zipWithNext().sumOf { (a,b) -> a.distance(b) } } }?.layer?.let { main ->
                visits.replaceAll { c -> c.copy(layer=if(angleDifference(c.layer.toDouble(),main.toDouble())%180 in 45.0..135.0) (main+90)%180 else main) }
            }
            val switches=visits.zipWithNext().count { (a,b) -> a.layer!=b.layer }
            var backtracks=0
            for(group in visits.groupBy { it.layer }.values) {
                // Axes perpendicular and parallel to the passes, each in its best direction, like production.
                backtracks+=listOf(90.0,0.0).sumOf { relative ->
                    val axis=Math.toRadians(group.first().layer+relative)
                    val ranges=group.map { c -> c.paths.flatten().map { it.x*kotlin.math.cos(axis)+it.y*kotlin.math.sin(axis) }.let { it.min() to it.max() } }
                    listOf(1,-1).minOf { sign ->
                        val signed=ranges.map { (a,b) -> if(sign>0) a to b else -b to -a }
                        signed.indices.count { i -> signed.drop(i+1).any { it.second<signed[i].first-slack } }
                    }
                }
            }
            return SweepBarMetrics(visits.size,backtracks,switches)
        }
    }
}
