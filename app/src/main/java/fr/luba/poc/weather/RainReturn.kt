package fr.luba.poc.weather

import fr.luba.poc.i18n.tr

/** What a mowing suspended by the rain does next, robot on the base ([RainReturn.resume]). */
enum class RainWait { WAIT, RESUME, GIVE_UP }

/**
 * Automatic return to the base on confirmed rain, then resume once it is over (docs/RAIN_WEATHER_FUSION.md).
 *
 * Off by default ([WeatherState.rainReturn]): the radar thresholds are not calibrated on real showers yet.
 * - Return only on [RainLevel.RAIN], the confirmed level of [RainMonitor] (two images at 23 dBZ or more within 1.5 km
 *   of the garden, or one at 30 dBZ): monitoring, imminent rain and an isolated echo never send the robot back.
 * - Resume only on [RainLevel.DRY], which [RainMonitor] grants after 30 min without rain, and not before [MIN_WAIT_MS]
 *   on the base: intermittent showers do not make a return-and-resume loop. Stale or missing weather
 *   ([RainLevel.UNKNOWN]) is never taken for dry weather: the robot keeps waiting.
 * - At most [MAX_RESUMES] automatic resumes per mowing, and no resume after [MAX_WAIT_MS] on the base: the mowing then
 *   stays to be resumed by hand, its progress kept.
 * The robot's protections, STOP and the link checks keep priority: the return is the same as at the end of the working
 * hours (route back blades OFF, official docking), the resume the same as "Resume".
 */
object RainReturn {
    const val MIN_WAIT_MS = 30 * 60_000L
    const val MAX_WAIT_MS = 6 * 60 * 60_000L
    const val MAX_RESUMES = 2

    /** A mowing in progress returns to the base now: setting on and rain confirmed. */
    fun shouldReturn(enabled: Boolean, level: RainLevel): Boolean = enabled && level == RainLevel.RAIN

    /** Robot on the base since [waitedMs] after a rain return, [resumes] automatic resumes already made in this mowing. */
    fun resume(level: RainLevel, waitedMs: Long, resumes: Int): RainWait = when {
        resumes >= MAX_RESUMES || waitedMs > MAX_WAIT_MS -> RainWait.GIVE_UP
        level == RainLevel.DRY && waitedMs >= MIN_WAIT_MS -> RainWait.RESUME
        else -> RainWait.WAIT
    }

    /** Status shown while the robot waits on the base. */
    fun waitingStatus(level: RainLevel, waitedMs: Long): String {
        val minutes = waitedMs / 60_000
        return when (level) {
            RainLevel.UNKNOWN -> tr("Rain return: weather unavailable, waiting on the base ($minutes min)",
                "Retour pluie : météo indisponible, attente sur la base ($minutes min)")
            RainLevel.DRY -> tr("Rain over: mowing resumes after ${MIN_WAIT_MS / 60_000} min on the base ($minutes min)",
                "Pluie terminée : reprise de la tonte après ${MIN_WAIT_MS / 60_000} min sur la base ($minutes min)")
            else -> tr("Rain return: ${level.title.lowercase()}, waiting on the base ($minutes min)",
                "Retour pluie : ${level.title.lowercase()}, attente sur la base ($minutes min)")
        }
    }
}
