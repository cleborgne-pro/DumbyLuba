package fr.luba.poc.simulation

import fr.luba.poc.diagnostics.MapNavFollower
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point

/**
 * Drift tolerated during a return to base (slope, wet grass…): the robot may deviate from the planned route without
 * being stopped, up to [MAX_M], as long as it stays inside the safe surface (whole chassis in the free physical
 * surface) and outside the base safety zone. Elsewhere (mowing, links, leaving the base, move to a point), the maximum
 * deviation stays [MapNavFollower.MAX_DEVIATION_M].
 *
 * Shared by the pilot tracking (Fold) and the gateway's on-board tracking: same thresholds, same decision.
 */
object ReturnDrift {
    const val MAX_M = 1.0

    /** Base safety zone: station and radius of the forbidden disc outside the docking corridor. */
    data class BaseZone(val station: Point, val radiusM: Double) {
        init { require(station.x.isFinite() && station.y.isFinite() && radiusM.isFinite() && radiusM > 0) { tr("Invalid base zone", "Zone de la base invalide") } }
    }

    /**
     * First edge of the return to base in [path]: those following the last cutting pass, if the route ends at the
     * approach point [baseApproach]. [path].size: no return (resume link, leaving the base, route not reaching the
     * base). A route without a cutting pass that reaches the base ("Recharge", return during mowing) is entirely a
     * return.
     */
    fun returnFrom(path: List<SimEdge>, baseApproach: Point?): Int {
        if (baseApproach == null || path.isEmpty() || path.last().b.distance(baseApproach) > 1e-6) return path.size
        return path.indexOfLast { it.cutting } + 1
    }

    /**
     * Stop reason for the deviation [deviationM] measured at edge [edge], or null to continue.
     * [returning]: edge of the return to base ([returnFrom]). [bodySafe]: chassis inside the safe surface at the
     * measured position (evaluated only beyond the normal deviation). [base]: base safety zone; unknown, no tolerance
     * (normal deviation).
     */
    fun failure(deviationM: Double, edge: Int, returning: Boolean, position: Point, base: BaseZone?,
                /** Pivot in progress or the reconvergence following it ([PivotSlide.active]): slide tolerated. */
                slide: Boolean = false, bodySafe: (Point) -> Boolean): String? {
        if (deviationM <= MapNavFollower.MAX_DEVIATION_M) return null
        val cm = "%.0f cm".format(deviationM * 100)
        if (slide && !(returning && base != null)) {
            if (deviationM > PivotSlide.MAX_M) return tr("Pivot slide >${(PivotSlide.MAX_M * 100).toInt()} cm ($cm, edge ${edge + 1})",
                "Glissement au pivot >${(PivotSlide.MAX_M * 100).toInt()} cm ($cm, arête ${edge + 1})")
            if (base != null && position.distance(base.station) < base.radiusM) return tr("Slide of $cm inside the base safety zone (edge ${edge + 1})",
                "Glissement de $cm dans la zone de sécurité de la base (arête ${edge + 1})")
            if (!bodySafe(position)) return tr("Slide of $cm outside the safe zone (edge ${edge + 1})", "Glissement de $cm hors de la zone sûre (arête ${edge + 1})")
            return null
        }
        if (!returning || base == null) return tr("Route deviation >30 cm ($cm, edge ${edge + 1})", "Écart au trajet >30 cm ($cm, arête ${edge + 1})")
        if (deviationM > MAX_M) return tr("Route deviation >${(MAX_M * 100).toInt()} cm during the return ($cm, edge ${edge + 1})",
            "Écart au trajet >${(MAX_M * 100).toInt()} cm pendant le retour ($cm, arête ${edge + 1})")
        if (position.distance(base.station) < base.radiusM) return tr("Drift of $cm inside the base safety zone (edge ${edge + 1})",
            "Dérive de $cm dans la zone de sécurité de la base (arête ${edge + 1})")
        if (!bodySafe(position)) return tr("Drift of $cm outside the safe zone (edge ${edge + 1})", "Dérive de $cm hors de la zone sûre (arête ${edge + 1})")
        return null
    }
}

/**
 * Slide during an in-place pivot, on a slope: the robot pivots on the grass and slides downhill.
 *
 * Mowing of 2026-10-15 (Jardin d'Eden, 09:06–09:52): at the bottom of the zone, each U-turn pivot moved the published
 * position 20 to 45 cm down the slope (5 to 15 cm at the top of the zone, 10 cm at most on the flat), and the gateway
 * stopped with "Écart au trajet >30 cm" at almost every U-turn: 12 stops and resumes in 10 minutes, then a protection
 * 41 during a resume link.
 *
 * During a pivot ([PathFollower.pivoting]) and the reconvergence following it, the tolerated deviation goes from 30 cm
 * to [MAX_M], chassis inside the safe surface and outside the base safety zone ([ReturnDrift.failure]). The tolerance
 * ends as soon as the robot is back on the line after at least [RETURN_MIN_M] of travel, or after [SETTLE_M] of
 * travel: beyond that, the normal deviation applies. The robot's envelope, obstacle and protection checks do not
 * change.
 *
 * One instance per tracking (gateway, pilot, simulation), kept from one stretch to the next: a U-turn chains two
 * pivots separated by a link with blades stopped, each in its own stretch.
 */
class PivotSlide {
    companion object {
        /** Maximum deviation tolerated during and after a pivot. */
        const val MAX_M = 0.6
        /** Travel after the end of the pivot beyond which the tolerance ends. */
        const val SETTLE_M = 2.0
        /** Minimum travel after the pivot before noting the return to the line. */
        const val RETURN_MIN_M = 0.3
        /** Deviation below which the robot is back on the line. */
        const val BACK_ON_LINE_M = 0.15
    }

    /** Tolerance in progress: pivot, or reconvergence after a pivot. */
    var active = false
        private set
    private var pivotEnd: Point? = null

    /** At each check: [pivoting] for the current command, measured [position] and [deviationM]. */
    fun update(pivoting: Boolean, position: Point, deviationM: Double): Boolean {
        if (pivoting) { active = true; pivotEnd = null; return true }
        if (!active) return false
        val end = pivotEnd ?: position.also { pivotEnd = it }
        val travelled = position.distance(end)
        if (travelled > SETTLE_M || (travelled >= RETURN_MIN_M && deviationM <= BACK_ON_LINE_M)) { active = false; pivotEnd = null }
        return active
    }
}
