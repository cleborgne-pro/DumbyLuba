package fr.luba.poc.map

import fr.luba.poc.i18n.tr

/** Zones stay independent: no unvalidated shortcut between zones. */
object MultiZonePlanner {
    /** Merges the previews of several zones into a single global preview. */
    fun combine(zones: List<MapShape>, results: List<Preview>, mode: CoverageMode, basePoint: Point? = null): Preview {
        if (results.isEmpty()) return Preview(emptyList(),0.0,0,0,mode)
        val kept = zones.take(results.size)
        val runs = results.flatMap { it.runs }
        val borders = results.flatMap { it.borders }
        val kinds = results.flatMap { p -> p.borders.indices.map { p.borderKinds.getOrElse(it) { it >= p.outerLoops } } }
        val ordered = runs
        val orderedBorders = borders
        val borderStart = results.first().startWithBorders
        val cells=linkedMapOf<Pair<Int,Int>,Int>()
        val cellIds=if(results.all { it.runCellIds.size==it.runs.size }) results.flatMapIndexed { zone,p ->
            p.runCellIds.map { cell -> cells.getOrPut(zone to cell) { cells.size } }
        } else emptyList()
        return Preview(ordered,results.sumOf { it.meters },results.first().angle,
            results.sumOf { it.connectors },mode,orderedBorders,results.sumOf { it.outerLoops },results.sumOf { it.noGoLoops },
            kept.map { it.id },results.map { it.angle },results.flatMap { it.passes }, results.sumOf { it.skippedBorderLoops },borderStart,
            kinds, scheduledPaths = results.flatMap { it.cuttingPaths },runCellIds=cellIds,borderAnchors=results.flatMap { it.borderAnchors },localOnly=results.any { it.localOnly })
    }
    fun plan(zones: List<MapShape>, holes: List<List<Point>>, spacing: Double, margin: Double,
             mode: CoverageMode, borders: Boolean, borderPasses: Int = 1, noGoPasses: Int = 1,
             obstacles: List<fr.luba.poc.obstacles.ObstacleDisc> = emptyList(),
             robot: fr.luba.poc.obstacles.RobotEnvelope = fr.luba.poc.obstacles.RobotEnvelope.LUBA2,
             recordedBoundary: Boolean = false, basePoint: Point? = null, bordersFirst: Boolean? = null, nearbyNoGo: Boolean = false, progress: (Int,Int,String)->Unit = { _,_,_-> }, onLive: (Preview)->Unit = {},
             outerOffsetM: Double = CoveragePlanner.RECORDED_OUTER_OFFSET_M): Preview {
        if (mode.isSurvey) return SurveyPlanner.plan(zones,holes,spacing,margin,mode,obstacles,robot,
            recordedBoundary,basePoint,progress,onLive,outerOffsetM)
        require(zones.isNotEmpty()) { tr("Select at least one zone", "Sélectionnez au moins une zone") }
        require(zones.map { it.id }.distinct().size==zones.size && zones.all { it.kind == ShapeKind.ZONE })
        val done=mutableListOf<Preview>()
        fun schedule(p: Preview): Preview = if (bordersFirst == null) p else
            MowingOrder.apply(p,bordersFirst,nearbyNoGo,done.lastOrNull()?.cuttingPaths?.lastOrNull()?.lastOrNull() ?: basePoint)
        zones.forEachIndexed { index,zone ->
            progress(index,zones.size,zone.name.ifBlank { "Zone ${index+1}" })
            val entryPoint = done.lastOrNull()?.cuttingPaths?.lastOrNull()?.lastOrNull() ?: basePoint
            val result = try {
                // On each improvement of a zone (orientation, direction, order), the partial route is published again:
                // the map is drawn progressively, zone after zone.
                Geometry.plan(zone.points,holes,spacing,margin,mode,borders,borderPasses,noGoPasses,obstacles,robot,recordedBoundary,entryPoint,outerOffsetM) { partial ->
                    onLive(combine(zones,done+schedule(partial),mode,basePoint))
                }
            } catch(e:IllegalArgumentException) { throw IllegalArgumentException("${zone.name.ifBlank { "Zone ${index+1}" }} : ${e.message}",e) }
            done.add(schedule(result))
            onLive(combine(zones,done,mode,basePoint))
        }
        progress(zones.size,zones.size,tr("Done", "Terminé"))
        return combine(zones,done,mode,basePoint)
    }
}
