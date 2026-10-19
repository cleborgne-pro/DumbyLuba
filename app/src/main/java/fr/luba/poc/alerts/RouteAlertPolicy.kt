package fr.luba.poc.alerts

import fr.luba.poc.simulation.RoutePhase

object RouteAlertPolicy {
    fun failure(phase: RoutePhase): AlertKind? = when(phase) {
        RoutePhase.RETURNING, RoutePhase.ALIGNING, RoutePhase.DOCKING -> AlertKind.RETURN_FAILED
        RoutePhase.CHARGING, RoutePhase.RESUMING -> AlertKind.RESUME_FAILED
        RoutePhase.FOLLOWING, RoutePhase.COMPLETE, RoutePhase.STOPPED -> null
    }
}
