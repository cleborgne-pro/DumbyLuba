package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.simulation.ShortestRoutes
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.*

/** Repairs only the remaining prefix; no exit from the reserve, no reversing, no blind resume. */
object LocalReplanner {
    const val WINDOW_M = 4.0
    private val factory = GeometryFactory()
    private fun polygon(points: List<Point>) = factory.createPolygon(
        (points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    fun repair(plan: MapNavPlan, position: Point, next: Int, obstacles: List<LocalObstacle>,
               robot: RobotEnvelope = RobotEnvelope.LUBA2): MapNavPlan {
        require(next in 1 until plan.points.size)
        val clearance = max(MapNavPlanner.MARGIN_M, robot.turnClearanceM + robot.minimumTurnRadiusM) + 0.05
        var free = polygon(plan.zone).buffer(-clearance)
        plan.holes.forEach { free = free.difference(polygon(it).buffer(clearance)) }
        obstacles.forEach { obstacle ->
            // Repeated incidents widen the reserve; each past incident strengthens it.
            val reserve = obstacle.exclusionRadiusM + clearance + min(0.6, obstacle.incidents.size * 0.1)
            free = free.difference(factory.createPoint(Coordinate(obstacle.x, obstacle.y)).buffer(reserve, 32))

        }
        val window = factory.createPoint(Coordinate(position.x, position.y)).buffer(WINDOW_M, 32)
        val local = free.intersection(window)
        fun safe(a: Point, b: Point): Boolean = local.covers(if (a == b)
            factory.createPoint(Coordinate(a.x, a.y)) else factory.createLineString(arrayOf(Coordinate(a.x,a.y),Coordinate(b.x,b.y))))
        require(!local.isEmpty && safe(position, position)) { tr("Stop held: not enough room to turn at the contact", "Arrêt maintenu : espace de rotation insuffisant au contact") }
        val routes = ShortestRoutes(local, ::safe)
        for (index in next until plan.points.size) {
            val target = plan.points[index]
            if (position.distance(target) < 1.0 || position.distance(target) > WINDOW_M - 0.2) continue
            val bypass = runCatching { routes.between(position, target) }.getOrNull() ?: continue
            // The link must also be free: do not rejoin just before the same obstacle.
            val tail = plan.points.drop(index)
            if (tail.zipWithNext().takeWhile { (a,b) -> a.distance(position) <= WINDOW_M && b.distance(position) <= WINDOW_M }
                    .any { (a,b) -> !safe(a,b) }) continue
            val repaired = bypass + plan.points.drop(index + 1)
            require(repaired.zipWithNext().take(bypass.size - 1).all { (a,b) -> safe(a,b) })
            return plan.copy(points = repaired, labels = List(bypass.size) { BladeCourse.BYPASS } + plan.labels.drop(index + 1))
        }
        error(tr("Stop held: no safe link within 4 m", "Arrêt maintenu : aucun raccord sûr dans les 4 m"))
    }
}

/** Persistent heatmap derived from incidents, without automatic change to the robot map. */
data class IncidentHotspot(val x: Double, val y: Double, val count: Int, val correctionSuggested: Boolean)
fun List<LocalObstacle>.incidentHeatmap(): List<IncidentHotspot> = filter { it.incidents.isNotEmpty() }.map {
    IncidentHotspot(it.x, it.y, it.incidents.size, it.incidents.size >= 3)
}
