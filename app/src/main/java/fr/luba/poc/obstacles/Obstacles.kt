package fr.luba.poc.obstacles

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Kind of local point obstacle chosen by the user. */
enum class ObstacleKind(private val labelEn: String, private val labelFr: String) {
    SWING("Swing leg", "Pied de balançoire"),
    TREE("Tree", "Arbre"),
    POLE("Pole", "Poteau"),
    HOLE("Hole", "Trou"),
    LOW_BRANCH("Low branch", "Branche basse"),
    OTHER("Other", "Autre");

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)

    /** True when [text] starts with this kind's label in either language (labels saved before a language change). */
    fun matches(text: String): Boolean = text.startsWith(labelEn) || text.startsWith(labelFr)

    /** Estimated physical radius suggested on creation, in metres. */
    val defaultRadiusM: Double
        get() = when (this) {
            SWING -> 0.12
            TREE -> 0.30
            POLE -> 0.10
            HOLE -> 0.35
            LOW_BRANCH -> 0.15
            OTHER -> 0.20
        }

    companion object {
        fun from(name: String): ObstacleKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Incident attached to a local obstacle: bumper contact, rubbing, near miss… */
data class ObstacleIncident(val atMs: Long, val kind: String, val note: String = "", val x: Double? = null, val y: Double? = null) {
    init {
        require(kind.isNotBlank()) { tr("Empty incident kind", "Type d'incident vide") }
        require(note.length <= 500) { tr("Incident note too long", "Note d'incident trop longue") }
        require(x == null || x.isFinite()) { tr("Invalid incident position", "Position d'incident invalide") }
        require(y == null || y.isFinite()) { tr("Invalid incident position", "Position d'incident invalide") }
    }
}

/**
 * Local point obstacle: position, estimated physical radius, safety margin and incident history. Strictly local to
 * the app — never sent to the robot nor converted into a Mammotion no-go zone.
 */
data class LocalObstacle(
    val id: String,
    val kind: ObstacleKind,
    val x: Double,
    val y: Double,
    val radiusM: Double,
    val marginM: Double,
    val incidents: List<ObstacleIncident> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val label: String = "",
) {
    init {
        require(id.isNotBlank()) { tr("Empty obstacle id", "Identifiant d'obstacle vide") }
        require(x.isFinite() && y.isFinite()) { tr("Invalid obstacle position", "Position d'obstacle invalide") }
        require(radiusM.isFinite() && radiusM in MIN_RADIUS_M..MAX_RADIUS_M) { tr("Physical radius out of range $MIN_RADIUS_M–$MAX_RADIUS_M m", "Rayon physique hors plage $MIN_RADIUS_M–$MAX_RADIUS_M m") }
        require(marginM.isFinite() && marginM in 0.0..MAX_MARGIN_M) { tr("Safety margin out of range 0–$MAX_MARGIN_M m", "Marge de sécurité hors plage 0–$MAX_MARGIN_M m") }
        require(label.length <= 120) { tr("Obstacle label too long", "Libellé d'obstacle trop long") }
        require(incidents.size <= MAX_INCIDENTS) { tr("Too many incidents for one obstacle", "Trop d'incidents pour un obstacle") }
    }

    /** Obstacle surface enlarged by the safety margin, without the robot envelope. */
    val exclusionRadiusM: Double get() = radiusM + marginM

    /**
     * Disc to keep out of reach of the robot reference point: physical radius, safety margin and the LUBA turning
     * envelope (the corners sweep this half-diagonal during turns).
     */
    fun keepOutDisc(robot: RobotEnvelope): ObstacleDisc =
        ObstacleDisc(x, y, exclusionRadiusM + robot.turnClearanceM)

    /** Adds an incident to the history, never losing the previous ones. */
    fun withIncident(incident: ObstacleIncident): LocalObstacle =
        copy(incidents = incidents + incident, updatedAtMs = maxOf(updatedAtMs, incident.atMs))

    /** Manual edit of the position, radius, margin, kind or label. */
    fun edited(
        x: Double = this.x,
        y: Double = this.y,
        radiusM: Double = this.radiusM,
        marginM: Double = this.marginM,
        kind: ObstacleKind = this.kind,
        label: String = this.label,
        atMs: Long = updatedAtMs,
    ): LocalObstacle = copy(x = x, y = y, radiusM = radiusM, marginM = marginM, kind = kind, label = label, updatedAtMs = maxOf(updatedAtMs, atMs))

    companion object {
        const val MIN_RADIUS_M = 0.05
        const val MAX_RADIUS_M = 2.0
        const val MAX_MARGIN_M = 2.0
        const val MAX_INCIDENTS = 200
    }
}

/**
 * Robot envelope, to account for the actual volume of the LUBA around obstacles. The LUBA 2 AWD 1000 dimensions
 * come from the manufacturer's data sheet (690 × 513 × 273 mm); the front bumper is estimated at half the length
 * from the telemetry reference point.
 */
data class RobotEnvelope(val lengthM: Double, val widthM: Double, val frontM: Double,
    val minimumTurnRadiusM: Double = 0.0, val heightM: Double = 0.273) {
    init {
        require(lengthM.isFinite() && lengthM > 0 && widthM.isFinite() && widthM > 0) { tr("Invalid robot dimensions", "Dimensions robot invalides") }
        require(heightM.isFinite() && heightM > 0) { tr("Invalid robot height", "Hauteur robot invalide") }
        require(minimumTurnRadiusM.isFinite() && minimumTurnRadiusM >= 0) { tr("Invalid turning radius", "Rayon de rotation invalide") }
        require(frontM.isFinite() && frontM in 0.0..lengthM) { tr("Bumper distance outside the chassis", "Distance bumper hors châssis") }
    }

    /** Overhangs from the position reference point: makes no assumption about the axle positions. */
    val frontOverhangM: Double get() = frontM
    val rearOverhangM: Double get() = lengthM - frontM
    val rearM: Double get() = rearOverhangM

    /** Half-width: straight-line passage along an obstacle. */
    val halfWidthM: Double get() = widthM / 2.0

    /** Turning envelope: distance from the reference point to the farthest corner, swept by the corners during turns. */
    val turnClearanceM: Double get() = hypot(maxOf(frontM, rearM), halfWidthM)

    companion object {
        val LUBA2 = RobotEnvelope(lengthM = 0.69, widthM = 0.513, frontM = 0.345, heightM = 0.273)
    }
}

/** Reserve disc: the robot reference point must not enter it. */
data class ObstacleDisc(val x: Double, val y: Double, val radiusM: Double) {
    init {
        require(x.isFinite() && y.isFinite() && radiusM.isFinite() && radiusM > 0) { tr("Invalid reserve disc", "Disque de réserve invalide") }
    }

    fun center(): Point = Point(x, y)
}

/** Raw bumper contact position captured during teaching. */
data class TeachContact(val x: Double, val y: Double, val headingDeg: Double, val atMs: Long) {
    init {
        require(x.isFinite() && y.isFinite() && headingDeg.isFinite()) { tr("Invalid teaching measurement", "Mesure d'apprentissage invalide") }
    }
}

/** Obstacle position estimate from a bumper contact. */
object TeachObstacle {
    /**
     * Estimated obstacle centre: the front bumper touches the surface at `RTK position + frontM × heading`, the
     * centre is assumed `radius` further along the same axis. Heading assumed in degrees, counter-clockwise around +x:
     * to be checked against an observed move (see MAMMOTION_PROTOCOL.md) before trusting it.
     */
    fun estimateCenter(contact: TeachContact, radiusM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): Point {
        require(radiusM.isFinite() && radiusM in LocalObstacle.MIN_RADIUS_M..LocalObstacle.MAX_RADIUS_M) { tr("Radius out of range", "Rayon hors plage") }
        val radians = Math.toRadians(contact.headingDeg)
        val distance = robot.frontM + radiusM
        return Point(contact.x + distance * cos(radians), contact.y + distance * sin(radians))
    }
}
