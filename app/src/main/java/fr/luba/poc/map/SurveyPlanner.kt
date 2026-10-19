package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Union of the chosen zones: shared borders are not part of the outer loop. */
object SurveyPlanner {
    fun plan(zones: List<MapShape>, holes: List<List<Point>>, spacing: Double, margin: Double,
        mode: CoverageMode, obstacles: List<ObstacleDisc>, robot: RobotEnvelope, recordedBoundary: Boolean,
        basePoint: Point?, progress: (Int,Int,String) -> Unit, onLive: (Preview) -> Unit,
        outerOffsetM: Double = CoveragePlanner.RECORDED_OUTER_OFFSET_M): Preview {
        require(mode.isSurvey)
        require(zones.isNotEmpty()) { tr("Select at least one zone", "Sélectionnez au moins une zone") }
        require(zones.map { it.id }.distinct().size == zones.size && zones.all { it.kind == ShapeKind.ZONE })
        val factory = GeometryFactory()
        val polygons = zones.map { zone ->
            require(zone.points.size >= 3) { tr("Incomplete zone boundary", "Contour de zone incomplet") }
            factory.createPolygon((zone.points+zone.points.first()).map { Coordinate(it.x,it.y) }.toTypedArray())
                .also { require(it.isValid) { tr("Invalid zone boundary", "Contour de zone invalide") } }
        }
        val union = UnaryUnionOp.union(polygons)
        val components = (0 until union.numGeometries).map { union.getGeometryN(it) as Polygon }
        fun points(line: LineString) = line.coordinates.dropLast(1).map { Point(it.x,it.y) }
        val mergedZones = components.mapIndexed { index, polygon -> MapShape(index.toLong(),0,points(polygon.exteriorRing),tr("Merged boundary ${index+1}", "Contour fusionné ${index+1}")) }
        // Gaps between surrounding zones stay excluded from the grid.
        val exclusions = holes + components.flatMap { polygon -> (0 until polygon.numInteriorRing).map { points(polygon.getInteriorRingN(it)) } }
        val results = mutableListOf<Preview>()
        fun combined(parts: List<Preview>) = MultiZonePlanner.combine(mergedZones,parts,mode,basePoint)
            .copy(zoneIds=zones.map { it.id })
        mergedZones.forEachIndexed { index, zone ->
            progress(index,mergedZones.size,zone.name)
            val plan = Geometry.plan(zone.points,exclusions,spacing,margin,mode,true,1,0,obstacles,robot,recordedBoundary,basePoint,outerOffsetM)
            val from = results.lastOrNull()?.cuttingPaths?.lastOrNull()?.lastOrNull() ?: basePoint
            results.add(MowingOrder.apply(plan,true,false,from))
            onLive(combined(results))
        }
        progress(mergedZones.size,mergedZones.size,tr("Signal survey route ready", "Parcours des signaux prêt"))
        return combined(results)
    }
}
