package fr.luba.poc.mowing

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.i18n.tr

/** Native mission: the firmware chooses the points and links, no external joystick. */
data class MowConfig(val zones: List<Long>, val heightMm: Int = 60, val checkerboard: Boolean = false, val spacingCm: Int = 25, val speedMps: Float = 0.3f) {
    init {
        require(zones.isNotEmpty() && zones.size == zones.distinct().size && zones.none { it == 0L }) { tr("Invalid zones", "Zones invalides") }
        require(spacingCm in 5..35) { tr("Spacing out of range 5–35 cm", "Espacement hors plage 5–35 cm") }
        require(speedMps.isFinite() && speedMps in 0.2f..0.6f) { tr("Speed out of range 0.2–0.6 m/s", "Vitesse hors plage 0,2–0,6 m/s") }
        require(heightMm in 25..70) { tr("Height out of range 25–70 mm", "Hauteur hors plage 25–70 mm") }
    }
}
enum class MowPhase(private val labelEn: String, private val labelFr: String) {
    IDLE("No mowing prepared", "Aucune tonte préparée"), PREPARING("Preparation requested from the robot", "Préparation demandée au robot"),
    PREPARED("Robot route ready to start", "Parcours robot prêt à démarrer"), STARTING("Start requested — waiting for the robot", "Départ demandé — attente du robot"),
    WORKING("Native mowing in progress", "Tonte native en cours"), PAUSING("Pause requested", "Pause demandée"), PAUSED("Robot paused", "Robot en pause"),
    RETURN_REQUESTED("Return requested", "Retour demandé"), RETURNING("Return to the station observed", "Retour à la station observé"),
    DOCKED("Charging contact observed", "Contact de charge observé"), LEAVING("Leaving the station requested", "Sortie de station demandée"),
    UNDOCKED("Station exit observed — resume available", "Sortie de station observée — reprise disponible"),
    STOPPED("Mission interrupted — check that the robot has physically stopped", "Mission interrompue — arrêt physique à vérifier");

    val label: String get() = tr(labelEn, labelFr)
}
data class MowUi(
    val paths: List<List<fr.luba.poc.map.Point>> = emptyList(), val pathStatus: String = tr("Firmware route not read", "Parcours firmware non lu"),
    val id: String = "", val phase: MowPhase = MowPhase.IDLE, val config: MowConfig? = null,
    val status: String = MowPhase.IDLE.label, val observations: List<String> = emptyList(),
    val manualChecks: Set<String> = emptySet(), val moved: Boolean = false,
    val mowingObserved: Boolean = false, val returningObserved: Boolean = false,
    val dockedObserved: Boolean = false, val leftDockObserved: Boolean = false,
) {
    val active: Boolean get() = phase !in setOf(MowPhase.IDLE, MowPhase.STOPPED)
    val running: Boolean get() = active && phase != MowPhase.PREPARED
}
object MowSafety {
    fun failure(name: String?, ready: Boolean, busy: Boolean, t: Telemetry, now: Long): String? {
        if (!ready) return tr("Connect the robot over BLE", "Connecter le robot en BLE")
        if (name?.startsWith("Luba-VS", true) != true) return tr("Command reserved for the LUBA 2 Luba-VS", "Commande réservée au LUBA 2 Luba-VS")
        if (busy) return tr("Finish the tests / map reading first", "Terminer les essais / la lecture de carte")
        if (t.lastDeviceAt == null || now - t.lastDeviceAt !in 0..2000) return tr("Robot state missing or stale", "État robot absent ou périmé")
        if (!fr.luba.poc.protocol.SelfCheck.nominal(t.selfCheck)) return fr.luba.poc.protocol.SelfCheck.refusal(t.selfCheck)
        if (t.sensorStatus == null || t.sensorStatus and 7 != 0) return tr("Bumper health alert or unknown", "Santé bumper en alerte ou inconnue")
        if (t.robotState !in setOf(11, 13, 14, 15, 19, 39)) return tr("Incompatible robot state: ${t.robotState}", "État robot incompatible : ${t.robotState}")
        return null
    }
    fun startFailure(t: Telemetry, now: Long): String? {
        if (t.battery == null || t.battery <= 30) return tr("Battery unknown or ≤30 %", "Batterie inconnue ou ≤30 %")
        if (t.robotState !in setOf(11, 15, 19, 39)) return tr("Robot not available for the start", "Robot non disponible pour le départ")
        if (t.sensorStatus == null || (t.sensorStatus shr 9) and 7 != 0) return tr("Blades not confirmed OFF", "Lames non confirmées OFF")
        if (t.lastPositionAt == null || now - t.lastPositionAt !in 0..2000 ||
            t.x?.isFinite() != true || t.y?.isFinite() != true) return tr("Fresh position required", "Position fraîche obligatoire")
        return null
    }
    fun mapFailure(map: RobotMap, config: MowConfig): String? =
        if (!map.complete || config.zones.any { id -> map.shapes.none { it.id == id && it.kind == ShapeKind.ZONE && it.points.size >= 3 } })
            tr("Re-read this robot's BLE map and select its zones", "Relire la carte BLE de ce robot et sélectionner ses zones") else null
}

/** Reduction on a NEW state report only: no evidence taken from a GATT ACK. */
object MowObservation {
    fun accept(ui: MowUi, t: Telemetry, distanceFromStart: Double?, now: Long): MowUi {
        if (!ui.running || ui.phase == MowPhase.PREPARING || t.lastDeviceAt != now) return ui
        val phase = when {
            ui.phase == MowPhase.PAUSING -> if (t.robotState in setOf(19,39)) MowPhase.PAUSED else ui.phase
            ui.phase == MowPhase.LEAVING -> if (t.chargeState == 0 && distanceFromStart != null && distanceFromStart >= 0.2) MowPhase.UNDOCKED else ui.phase
            ui.phase in setOf(MowPhase.RETURNING, MowPhase.RETURN_REQUESTED, MowPhase.WORKING) && t.chargeState in setOf(1, 2) && t.robotState in setOf(11, 15, 39) -> MowPhase.DOCKED
            t.robotState == 14 -> MowPhase.RETURNING
            ui.phase == MowPhase.RETURN_REQUESTED -> ui.phase
            t.robotState == 13 -> MowPhase.WORKING
            ui.phase in setOf(MowPhase.PAUSING, MowPhase.WORKING) && t.robotState in setOf(19, 39) -> MowPhase.PAUSED
            else -> ui.phase
        }
        val mowing = t.robotState == 13 && t.sensorStatus?.let { (it shr 9) and 7 != 0 } == true
        val left = ui.dockedObserved && t.chargeState == 0 && distanceFromStart != null && distanceFromStart >= 0.2
        val changed = phase != ui.phase
        return ui.copy(phase = phase, status = if (changed) phase.label else ui.status,
            moved = ui.moved || (distanceFromStart != null && distanceFromStart >= 0.2),
            mowingObserved = ui.mowingObserved || mowing,
            returningObserved = ui.returningObserved || phase == MowPhase.RETURNING,
            dockedObserved = ui.dockedObserved || phase == MowPhase.DOCKED,
            leftDockObserved = ui.leftDockObserved || left,
            observations = (ui.observations + if (changed) listOf(tr("$now: ${phase.label} (state=${t.robotState}, charge=${t.chargeState})", "$now : ${phase.label} (état=${t.robotState}, charge=${t.chargeState})")) else emptyList()).takeLast(80))
    }
}

/** The firmware pads its fixed-size zone list with zeros (observed in 1.30.29.26). */
object MowPreparation {
    fun matches(route: fr.luba.poc.proto.MctrlNavProto.NavReqCoverPath, config: MowConfig): Boolean =
        route.subCmd == 0 && route.zoneHashsList.filter { it != 0L }.toSet() == config.zones.toSet() &&
            route.knifeHeight == config.heightMm
}

/** A task ACK does not prove movement, but a correlated refusal must end the wait. */
object MowTaskAck {
    fun rejection(expected: Int?, action: Int, result: Int): String? =
        if (expected != null && action == expected && result != 0)
            tr("Native action $action refused by the robot (result=$result)", "Action native $action refusée par le robot (résultat=$result)") else null
}
