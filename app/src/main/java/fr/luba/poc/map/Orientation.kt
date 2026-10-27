package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import kotlin.math.abs
import kotlin.math.min

/**
 * Direction of the passes for the modes that sweep in straight rows ([usesOrientation]), chosen in the task settings.
 * Angles are the planner angles ([Preview.angle]): direction of the passes, counter-clockwise from the map's x axis, 0..179.
 */
enum class OrientationMode(private val en: String, private val fr: String, private val enHint: String, private val frHint: String) {
    /** Cheapest direction of each zone, as before. */
    AUTOMATIC("Automatic", "Automatique", "Each zone takes its quickest direction.", "Chaque zone prend sa direction la plus rapide."),
    /** At least 45° from the direction last mowed on each zone when it costs little, otherwise the rows are shifted. */
    EACH_SESSION("Change every session", "Changer à chaque séance",
        "At least 45° away from the last mowing of each zone when it costs at most 5 % more; otherwise the same direction with the rows shifted by a few centimetres.",
        "Au moins 45° d'écart avec la dernière tonte de chaque zone si cela coûte au plus 5 % de plus ; sinon la même direction avec les passes décalées de quelques centimètres."),
    /** The same angle on every zone of the task, never replaced. */
    MANUAL("Manual", "Manuelle", "The same direction on every zone, counter-clockwise from the map's x axis. Never replaced: if a zone cannot be mowed at this angle, the route is not validated.",
        "La même direction sur toutes les zones, dans le sens antihoraire depuis l'axe x de la carte. Jamais remplacée : si une zone ne peut pas être tondue à cet angle, le trajet n'est pas validé.");
    val label: String get() = tr(en, fr)
    val hint: String get() = tr(enHint, frHint)
    /** Short label of the three-way choice. */
    val choiceLabel: String get() = if (this == EACH_SESSION) tr("Each session", "Chaque séance") else label

    companion object {
        /** Step of the manual angle slider, in degrees (0 to 175). */
        const val MANUAL_STEP_DEG = 5
        const val MANUAL_MAX_DEG = 175
    }
}

/** Modes whose passes are straight rows of one direction per zone (two for the checkerboard): the orientation applies. */
val CoverageMode.usesOrientation: Boolean get() = this == CoverageMode.LINES || this == CoverageMode.CHECKERBOARD || this == CoverageMode.ZAMBONI

/** Period of the directions of [this] mode: 90 for the checkerboard (families a and a+90), 180 otherwise. */
val CoverageMode.orientationPeriod: Int get() = if (this == CoverageMode.CHECKERBOARD || this == CoverageMode.SURVEY_GRID) 90 else 180

/** [angle] folded into 0 until [period]. */
fun foldAngle(angle: Int, period: Int = 180): Int = ((angle % period) + period) % period

/** Note of the direction setting for the modes that do not use it. */
fun orientationUnusedNote(): String =
    tr("Not used by Optimized and Spiral: the direction follows the shape.", "Non utilisé par Optimisé et Spirale : la direction suit la forme.")

private fun zoneName(map: RobotMap, preview: Preview, index: Int): String =
    map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && it.id == preview.zoneIds[index] }?.name?.trim()?.ifBlank { null } ?: "Zone ${index + 1}"

/**
 * Direction of the passes of each zone of [preview], in mowing order ("Piscine 35° · Figuier 120°", both families for
 * the checkerboard), with the shift of the rows when they are not centred ([Preview.zonePhases]); null for the modes
 * whose direction follows the shape.
 */
fun directionLine(map: RobotMap, preview: Preview, spacingM: Double): String? {
    if (!preview.mode.usesOrientation || preview.zoneAngles.size != preview.zoneIds.size || preview.zoneIds.isEmpty()) return null
    return preview.zoneIds.indices.joinToString(" · ") { i ->
        val angle = preview.zoneAngles[i]
        val mode = preview.zoneModes.getOrNull(i) ?: preview.mode
        val degrees = if (mode == CoverageMode.CHECKERBOARD) "$angle°/${angle + 90}°" else "$angle°"
        val shift = preview.zonePhases.getOrNull(i)?.let { kotlin.math.abs(it - 0.5) } ?: 0.0
        val cm = kotlin.math.round(shift * spacingM * 100).toInt()
        "${zoneName(map, preview, i)} $degrees" + if (shift > 1e-9) tr(" (shifted by $cm cm)", " (décalée de $cm cm)") else ""
    }
}

/**
 * One warning per zone where the requested pattern could not be applied ([Preview.zoneModes]: "Spiral not possible on
 * Figuier: zigzag used"), and per Zamboni zone none of whose cells could be reordered or whose Zamboni order had no
 * safe link ([Preview.zoneReorderedCells] 0 or −1), and per spiral zone driven from the edge because no order from the
 * centre had safe links ([Preview.zoneReorderedCells] −1 in Spiral mode).
 */
fun zoneModeWarnings(map: RobotMap, preview: Preview): List<String> {
    val replaced = if (preview.zoneModes.size != preview.zoneIds.size) emptyList()
        else preview.zoneIds.indices.filter { preview.zoneModes[it] != preview.mode }.map { i ->
            val used = if (preview.zoneModes[i] == CoverageMode.LINES) "zigzag" else preview.zoneModes[i].label
            tr("${preview.mode.label} not possible on ${zoneName(map, preview, i)}: $used used",
                "${preview.mode.label} impossible sur ${zoneName(map, preview, i)} : $used utilisé")
        }
    val zigzagOrder = if (preview.mode != CoverageMode.ZAMBONI || preview.zoneReorderedCells.size != preview.zoneIds.size) emptyList()
        else preview.zoneIds.indices.filter { preview.zoneReorderedCells[it] <= 0 }.map { i ->
            if (preview.zoneReorderedCells[i] == 0) tr("Zamboni on ${zoneName(map, preview, i)}: no regular cell, zigzag order",
                "Zamboni sur ${zoneName(map, preview, i)} : aucune cellule régulière, ordre du zigzag")
            else tr("Zamboni on ${zoneName(map, preview, i)}: no safe link in the Zamboni order, zigzag order",
                "Zamboni sur ${zoneName(map, preview, i)} : ordre Zamboni sans raccord sûr, ordre du zigzag")
        }
    val spiralEdge = if (preview.mode != CoverageMode.SPIRAL || preview.zoneReorderedCells.size != preview.zoneIds.size ||
        preview.zoneModes.size != preview.zoneIds.size) emptyList()
        else preview.zoneIds.indices.filter { preview.zoneModes[it] == CoverageMode.SPIRAL && preview.zoneReorderedCells[it] < 0 }.map { i ->
            tr("Spiral from the centre not possible on ${zoneName(map, preview, i)}: spiral from the edge used",
                "Spirale depuis le centre impossible sur ${zoneName(map, preview, i)} : spirale depuis le bord utilisée")
        }
    return replaced + zigzagOrder + spiralEdge
}

/**
 * "Change every session" ([OrientationMode.EACH_SESSION]) for one zone: [avoid] are the directions last mowed there
 * (one, or two for a checkerboard), [phase] the row phase to use when no direction is far enough from them at an
 * acceptable cost. Pure and deterministic.
 */
class OrientationRule(val avoid: List<Int>, val phase: Double,
                      val minSeparationDeg: Int = 45, val maxExtraCost: Double = 0.05) {
    init {
        require(avoid.isNotEmpty() && avoid.all { it in 0..179 }) { tr("Invalid previous direction", "Direction précédente invalide") }
        require(phase in 0.1..0.9 && minSeparationDeg in 0..90 && maxExtraCost >= 0)
    }

    /** One orientation candidate: its value, planner angle, period of its directions (180, or 90 for two families) and cost. */
    data class Candidate<T>(val value: T, val angle: Int, val period: Int, val cost: Double)

    /** Smallest distance between the directions of [angle] (with [period] 90: angle and angle + 90) and [avoid], 0..90. */
    fun separation(angle: Int, period: Int = 180): Int = directions(angle, period).minOf { d -> avoid.minOf { distance(d, it) } }

    /**
     * Cheapest candidate at least [minSeparationDeg] away from [avoid] whose cost is at most [maxExtraCost] above the
     * cheapest candidate of all; null if none (the caller then keeps the cheapest direction and shifts its rows by
     * [phase]). Ties: first in the list order.
     */
    fun <T> pick(candidates: List<Candidate<T>>): Candidate<T>? {
        val best = candidates.minOfOrNull { it.cost } ?: return null
        val cap = best + abs(best) * maxExtraCost + 1e-9
        return candidates.filter { it.cost <= cap && separation(it.angle, it.period) >= minSeparationDeg }.minByOrNull { it.cost }
    }

    companion object {
        /** Row phases, in their order of use: centred, then shifted by 0.3 spacing to one side and to the other. */
        val PHASES = listOf(0.5, 0.2, 0.8)
        fun nextPhaseIndex(index: Int): Int = (Math.floorMod(index, PHASES.size) + 1) % PHASES.size
        /** Index of [phase] in [PHASES], 0 (centred) if it is not one of them. */
        fun phaseIndex(phase: Double): Int = PHASES.indexOfFirst { abs(it - phase) < 1e-9 }.coerceAtLeast(0)
        /** Distance between two pass directions, modulo 180: 0..90. */
        fun distance(a: Int, b: Int): Int { val d = foldAngle(a - b); return min(d, 180 - d) }
        /** Directions 45° and 135° away from each of [avoid], added to the candidates so that a separated one exists. */
        fun extraAngles(avoid: List<Int>): List<Int> = avoid.flatMap { listOf(foldAngle(it + 45), foldAngle(it + 135)) }.distinct()
        /** Pass directions of [angle] (0..179): itself, and angle + 90 with [period] 90 (checkerboard). */
        fun directions(angle: Int, period: Int = 180): List<Int> =
            if (period == 90) listOf(foldAngle(angle), foldAngle(angle + 90)) else listOf(foldAngle(angle))
    }
}
