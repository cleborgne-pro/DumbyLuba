package fr.luba.poc.obstacles

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point

/** Obstacle teaching configuration: slow supervised advance, blades necessarily OFF. */
data class TeachConfig(
    /**
     * 80 units (≈ 7.6 cm/s): below ≈ 60 the LUBA 2 hardly moves ([fr.luba.poc.simulation.RangeArrival.APPROACH_MIN_LINEAR]).
     * At 20 units (until 2026-10-22) the robot stayed in place and the approach ended as "Presumed blockage".
     */
    val linearSpeed: Int = 80,
    val maxDurationMs: Long = 30_000,
    val batteryFloor: Int = 30,
) {
    init {
        require(linearSpeed in 1..MAX_TEACH_SPEED) { tr("Teaching speed out of bounds 1–$MAX_TEACH_SPEED", "Vitesse d'apprentissage hors bornes 1–$MAX_TEACH_SPEED") }
        require(maxDurationMs in 5_000..120_000) { tr("Duration out of range 5–120 s", "Durée hors plage 5–120 s") }
        require(batteryFloor in 10..80) { tr("Battery floor out of range 10–80 %", "Plancher batterie hors plage 10–80 %") }
    }

    companion object {
        /** Maximum raw teaching speed: deliberately slow move (uncalibrated device units). */
        const val MAX_TEACH_SPEED = 100
    }
}

/**
 * Local save proposal after a bumper contact, or for an obstacle pointed at without contact ([aheadM]: distance of the
 * centre in front of the robot reference point, adjustable; null = centre estimated from the contact and the radius).
 */
data class TeachProposal(val contact: TeachContact, val estimated: Point, val radiusM: Double, val aheadM: Double? = null) {
    /** Obstacle centre for the chosen [radiusM] and, without contact, the chosen distance [aheadM]. */
    fun center(radiusM: Double, aheadM: Double? = this.aheadM, robot: RobotEnvelope = RobotEnvelope.LUBA2): Point =
        if (aheadM == null) TeachObstacle.estimateCenter(contact, radiusM, robot)
        else ManualContact.ahead(Point(contact.x, contact.y), contact.headingDeg, aheadM)
}

/** Teach mode guards. The bumper is not a failure: it is the expected contact. */
object TeachSafety {

    /** Preconditions shown live (label, satisfied, refusal reason), in the order of [startFailure]. */
    fun checks(
        name: String?, stateAgeMs: Long?, battery: Int?, robotState: Int?,
        sensors: Int?, selfCheck: Long?, campaignRunning: Boolean, bladeTestRunning: Boolean,
        navRunning: Boolean, mapLoading: Boolean, batteryFloor: Int = 30,
        /** Model check: validated model, or a model trial for the command cutoff test ([fr.luba.poc.protocol.ModelTrial]). */
        modelAllowed: Boolean = fr.luba.poc.protocol.ModelSupport.motionValidated(name),
    ): List<Triple<String, Boolean, String>> = listOf(
        Triple("Robot LUBA 2", modelAllowed, tr("Reserved for a LUBA 2 identified as Luba-VS or Luba-VP (or a model trial armed)", "Réservé au LUBA 2 identifié Luba-VS ou Luba-VP (ou à un essai du modèle armé)")),
        Triple(tr("BLE diagnostics stopped", "Diagnostic BLE arrêté"), !campaignRunning, tr("Stop the BLE campaign before teaching", "Arrêter la campagne BLE avant l'apprentissage")),
        Triple(tr("No blade test", "Aucun essai de coupe"), !bladeTestRunning, tr("Finish the blade test before teaching", "Terminer l'essai de coupe avant l'apprentissage")),
        Triple(tr("No course running", "Aucun parcours en cours"), !navRunning, tr("Finish the random walk before teaching", "Terminer la marche aléatoire avant l'apprentissage")),
        Triple(tr("Map read", "Carte lue"), !mapLoading, tr("Wait for the map to be read", "Attendre la lecture de carte")),
        Triple(tr("Fresh robot state (≤ 2 s)", "État robot frais (≤ 2 s)"), stateAgeMs != null && stateAgeMs in 0..2000, tr("Robot state missing or older than 2 s", "État robot absent ou âgé de plus de 2 s")),
        Triple(tr("Battery > $batteryFloor %", "Batterie > $batteryFloor %"), battery != null && battery > batteryFloor, tr("Battery unknown or ≤$batteryFloor %", "Batterie inconnue ou ≤$batteryFloor %")),
        Triple(tr("Robot ready (state 11)", "Robot prêt (état 11)"), robotState == 11, tr("Robot not ready (MODE_READY=11 expected)", "Robot non prêt (MODE_READY=11 attendu)")),
        Triple(tr("No active protection", "Aucune protection active"), fr.luba.poc.protocol.SelfCheck.nominal(selfCheck), fr.luba.poc.protocol.SelfCheck.refusal(selfCheck)),
        Triple(tr("Bumper without alert", "Pare-chocs sans alerte"), sensors != null && sensors and 7 == 0, tr("Bumper health alert", "Alerte de santé bumper")),
        Triple(tr("Blades stopped", "Lames arrêtées"), sensors != null && sensors and (7 shl 9) == 0, tr("Blades not confirmed OFF by telemetry", "Lames non confirmées OFF par la télémétrie")),
    )

    /** Start preconditions: LUBA 2 identified, ready, bumper clear, blades OFF, no other test. */
    fun startFailure(
        name: String?, stateAgeMs: Long?, battery: Int?, robotState: Int?,
        sensors: Int?, selfCheck: Long?, campaignRunning: Boolean, bladeTestRunning: Boolean,
        navRunning: Boolean, mapLoading: Boolean, batteryFloor: Int = 30,
        modelAllowed: Boolean = fr.luba.poc.protocol.ModelSupport.motionValidated(name),
    ): String? = checks(name, stateAgeMs, battery, robotState, sensors, selfCheck, campaignRunning, bladeTestRunning,
        navRunning, mapLoading, batteryFloor, modelAllowed).firstOrNull { !it.second }?.third

    /** Continuous check during teaching; the bumper contact is handled elsewhere. */
    fun tickFailure(stateAgeMs: Long?, battery: Int?, sensors: Int?, batteryFloor: Int): String? {
        if (stateAgeMs == null || stateAgeMs !in 0..2000) return tr("Robot state missing or stale", "État robot absent ou périmé")
        if (battery == null || battery <= batteryFloor) return tr("Battery unknown or ≤$batteryFloor %", "Batterie inconnue ou ≤$batteryFloor %")
        if (sensors == null) return tr("Sensors missing", "Capteurs absents")
        if (sensors and (7 shl 9) != 0) return tr("Unexpected active blades", "Lames actives inattendues")
        return null
    }

    fun approachFailure(positionAgeMs: Long?, x: Double?, y: Double?, startX: Double?, startY: Double?): String? {
        if (positionAgeMs == null || positionAgeMs !in 0..2000 || x == null || y == null || startX == null || startY == null ||
            !x.isFinite() || !y.isFinite() || !startX.isFinite() || !startY.isFinite()) return tr("Position missing or stale", "Position absente ou périmée")
        if (kotlin.math.hypot(x - startX, y - startY) >= 1.0) return tr("1 m approach limit reached", "Limite d'approche de 1 m atteinte")
        return null
    }

    /** Bumper health alert: these bits do not prove a physical contact. */
    fun bumperHealthAlert(sensors: Int?): Boolean = sensors != null && sensors and 7 != 0

    /** Travel before the robot halts that makes a stop without contact code a probable object ahead (BLOCKED_AHEAD). */
    const val APPROACH_MIN_TRAVEL_M = 0.05

    /** Contact reported, or robot stopped in front of an object after moving ([APPROACH_MIN_TRAVEL_M]). */
    fun canProposeObstacle(approach: Boolean, outcome: String, positionAgeMs: Long): Boolean =
        approach && outcome in setOf("CONTACT_DETECTED", "BLOCKED_AHEAD") && positionAgeMs in 0..2000

    /** Builds the proposal from a contact, with the chosen physical radius. */
    fun proposal(contact: TeachContact, radiusM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): TeachProposal =
        TeachProposal(contact, TeachObstacle.estimateCenter(contact, radiusM, robot), radiusM)

    /** Obstacle pointed at without contact, from the robot pose [pose]: touching the bumper by default, distance adjustable. */
    fun pointedProposal(pose: TeachContact, radiusM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): TeachProposal {
        val ahead = ManualContact.defaultAheadM(radiusM, robot)
        return TeachProposal(pose, ManualContact.ahead(Point(pose.x, pose.y), pose.headingDeg, ahead), radiusM, ahead)
    }
}
