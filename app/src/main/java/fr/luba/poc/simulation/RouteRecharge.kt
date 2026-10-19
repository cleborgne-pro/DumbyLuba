package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import kotlin.math.abs

/** Decisions based only on the robot reports, never on the simulated battery. */
data class RouteRecharge(val low: Int, val high: Int) {
    init {
        require(low in 10..80 && high in 15..100 && high - low >= 5) { tr("Invalid recharge thresholds", "Seuils de recharge invalides") }
    }
    fun returnRequired(battery: Int): Boolean = battery <= low
    fun resumeAllowed(battery: Int, charge: Int?, stateAgeMs: Long): Boolean =
        stateAgeMs in 0..2000 && charge in setOf(1, 2) && battery >= high

    /**
     * Departure of a route launched while the robot is charging (mowing, survey, debug mowing of a painted zone or
     * border): immediate exit from the base as soon as the battery is clearly above the return threshold; otherwise
     * recharge up to [high] (the route would come straight back). The return during a route is still driven by
     * [returnRequired].
     */
    fun departureAllowed(battery: Int?): Boolean = battery != null && battery >= minOf(high, low + DEPARTURE_MARGIN_PERCENT)

    companion object {
        const val RESERVE_PERCENT = 5
        /** Margin above the return threshold to leave the base directly. */
        const val DEPARTURE_MARGIN_PERCENT = 10
        fun headingError(target: Double, heading: Double): Double =
            ((target - heading + 540.0) % 360.0 + 360.0) % 360.0 - 180.0

        /** Straight reversing only: no improvised pivot in the station corridor. */
        fun reverseCommand(engine: MowingSimulation, position: Point, heading: Double): Pair<Int, Int> {
            val axis = (engine.dockEntryHeading + 180.0) % 360.0
            require(abs(headingError(axis, heading)) <= 8.0) { tr("Heading lost while reversing", "Cap perdu pendant le recul") }
            require(Geometry.distance(position, engine.baseApproach, engine.base) <= 0.10) { tr("Deviation from the reversing corridor >10 cm", "Écart au couloir de recul >10 cm") }
            require(position.distance(engine.baseApproach) <= engine.baseApproach.distance(engine.base) + 0.10) { tr("Base overshot", "Dépassement de la base") }
            return if (position.distance(engine.base) <= 0.08) 0 to 0 else -10 to 0
        }

        /** Station exit advance: slow (≈ 10 cm/s, simulated docking speed). */
        const val EXIT_LINEAR = 100

        /**
         * Exit after recharge: slow straight forward motion along the station axis, from the base to the link point
         * in front of it. No pivot on the base; (0, 0) once the point is reached.
         */
        fun exitCommand(engine: MowingSimulation, position: Point, heading: Double): Pair<Int, Int> {
            val axis = (engine.dockEntryHeading + 180.0) % 360.0
            val length = engine.base.distance(engine.baseApproach)
            val ux = (engine.baseApproach.x - engine.base.x) / length
            val uy = (engine.baseApproach.y - engine.base.y) / length
            val along = (position.x - engine.base.x) * ux + (position.y - engine.base.y) * uy
            val lateral = abs((position.x - engine.base.x) * uy - (position.y - engine.base.y) * ux)
            require(lateral <= 0.10) { tr("Deviation from the exit corridor >10 cm", "Écart au couloir de sortie >10 cm") }
            if (along >= length - 0.05) return 0 to 0
            require(abs(headingError(axis, heading)) <= 10.0) { tr("Robot not aligned with the exit axis: straight-line exit refused",
                "Robot non aligné sur l'axe de sortie : sortie en ligne droite refusée") }
            return EXIT_LINEAR to 0
        }
    }
}

data class RechargePaths(val returning: List<SimEdge>, val resuming: List<SimEdge>)

/** Link from the interrupted point, then return to exactly the same point, blades OFF. */
/** [routes]: navigation of the current mission (discovered obstacles included), by default the prepared one. */
fun RobotRoute.rechargePaths(engine: MowingSimulation, position: Point, heading: Double,
                             routes: TrajectoryPlanner = engine.routes): RechargePaths {
    val axis = (engine.dockEntryHeading + 180.0) % 360.0
    val returning = routes.between(position, engine.baseApproach, heading, axis)
    val resuming = routes.between(engine.baseApproach, position, axis, heading)
    routes.requirePath(returning, heading, axis)
    routes.requirePath(resuming, axis, heading)
    fun edges(points: List<Point>) = points.zipWithNext().filter { (a,b) -> a.distance(b) > 1e-8 }
        .map { (a,b) -> SimEdge(a,b,false) }
    return RechargePaths(edges(returning), edges(resuming))
}
