package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import kotlin.math.ceil

/**
 * "Never Die Outside": the robot heads back as soon as its battery nears the estimated cost of the return plus a
 * reserve, even above the low threshold. Near the base the low threshold decides; far from it, the return cost does.
 * Same rule for the simulation and the actual mowing.
 *
 * The return cost is [FinishPolicy.neededPercent] of the return duration ([FinishPolicy.restSeconds]: distance at the
 * estimate speed and duration factor, docking included, energy margin included). The reserve is the battery left on
 * arrival at the base that the rule aims for.
 */
object ReturnReserve {
    const val DEFAULT_PERCENT = 10
    /** Never below the safety reserve of the tracking ([RouteRecharge.RESERVE_PERCENT]). */
    const val MIN_PERCENT = RouteRecharge.RESERVE_PERCENT
    const val MAX_PERCENT = 30
    /** Return path / straight line to the base used before a real return path is known (garden, house around). */
    const val DEFAULT_DETOUR = 1.5
    const val MAX_DETOUR = 4.0
    /** Highest return threshold accepted by the tracking ([fr.luba.poc.autonomy.LegSpec], [RouteRecharge]). */
    const val MAX_THRESHOLD = 80
    /**
     * Points kept between the threshold and the resume threshold: each recharge then buys at least this much mowing,
     * instead of a robot leaving at 60 % to come straight back at 55 %.
     */
    const val MIN_CYCLE_POINTS = 15

    /**
     * Allowed reserves for the low threshold [low]: [MIN_PERCENT] to [MAX_PERCENT], and never above [low] (beyond, the
     * robot would come back above the low threshold even right next to the base).
     */
    fun range(low: Int): IntRange = MIN_PERCENT..maxOf(MIN_PERCENT, minOf(MAX_PERCENT, low))

    fun sanitize(reserve: Int, low: Int): Int = reserve.coerceIn(range(low))

    /** Refusal of a reserve for thresholds [low] / [high], or null if it is valid. */
    fun refusal(reserve: Int, low: Int, high: Int): String? = when {
        high - low < 5 -> tr("Invalid recharge thresholds", "Seuils de recharge invalides")
        reserve !in range(low) -> tr("Return reserve between ${range(low).first} and ${range(low).last} % with a low threshold of $low %",
            "Réserve de retour entre ${range(low).first} et ${range(low).last} % avec un seuil bas de $low %")
        else -> null
    }

    /**
     * Battery level at which the robot returns from where it is: the return cost [returnPercent] plus [reserve],
     * rounded up, never below the low threshold [low], kept [MIN_CYCLE_POINTS] under [high] so that a recharge always
     * buys some mowing, and at most [MAX_THRESHOLD].
     */
    fun threshold(low: Int, high: Int, returnPercent: Double, reserve: Int): Int {
        if (!returnPercent.isFinite() || returnPercent < 0) return low
        return maxOf(low, minOf(high - MIN_CYCLE_POINTS, MAX_THRESHOLD, ceil(returnPercent + reserve - 1e-9).toInt()))
    }

    /** Estimated return distance: straight line to the approach point times [detour], plus the docking corridor. */
    fun returnMeters(straightM: Double, detour: Double, approachM: Double): Double = straightM * detour.coerceIn(1.0, MAX_DETOUR) + approachM

    /** Detour of a real return path of [pathM] for a straight line of [straightM] (1 when they are equal). */
    fun detour(pathM: Double, straightM: Double): Double =
        if (straightM <= 1e-6 || !pathM.isFinite()) DEFAULT_DETOUR else (pathM / straightM).coerceIn(1.0, MAX_DETOUR)
}
