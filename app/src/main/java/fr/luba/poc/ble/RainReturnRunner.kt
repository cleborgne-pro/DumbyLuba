package fr.luba.poc.ble

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.i18n.tr
import fr.luba.poc.simulation.DockResume
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.RouteIntent
import fr.luba.poc.weather.RainReturn
import fr.luba.poc.weather.RainWait
import fr.luba.poc.weather.WeatherService
import fr.luba.poc.weather.WeatherState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Automatic return to the base on confirmed rain and resume once the rain is over ([RainReturn]).
 *
 * The return is the one of the end of the working hours ([RobotBle.workWindowEnding]): the mission stops at the next
 * tracking check, goes back blades OFF and docks, its progress kept. Then, robot on the base, the mowing resumes by
 * itself through "Resume" once [RainReturn.resume] allows it, at most [RainReturn.MAX_RESUMES] times per mowing.
 */
internal class RainReturnRunner(private val host: RobotBle) {
    private val state get() = host.state
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)

    /** The mission in progress stops for the rain (not for the working hours). */
    private var raining = false
    /** Mowing ([fr.luba.poc.simulation.MissionCheckpoint.startedAt]) and its automatic resumes after rain. */
    private var mowing = 0L
    private var resumes = 0
    private var job: Job? = null

    /** The pending mission stop is a rain return. */
    val stopping: Boolean get() = raining && host.workWindowEnding

    /** Every second: confirmed rain during a tracked mowing, setting on → same stop as at the end of the working hours. */
    fun observe(ui: RobotUi, weather: WeatherState) {
        if (!ui.robotRoute.running || host.routeIntent != RouteIntent.FOLLOW || host.workWindowEnding) return
        if (!RainReturn.shouldReturn(weather.monitoring && weather.rainReturn && weather.location != null, weather.assessment.level)) return
        raining = true
        host.workWindowEnding = true
        event("rain_return", tr("Rain confirmed: mowing stopped and return to base", "Pluie confirmée : arrêt de la tonte et retour à la base"),
            mapOf("reason" to weather.assessment.reason))
        host.status(tr("Rain confirmed: return to base", "Pluie confirmée : retour à la base"))
    }

    /** The end of the working hours stops the mission: not a rain return. */
    fun windowEnding() { raining = false }

    /** Mission stopped on the base by a rain return: wait for the end of the rain, then resume with [engine]. */
    fun docked(engine: MowingSimulation) {
        if (!raining) return
        raining = false
        val checkpoint = state.value.missionCheckpoint ?: return
        if (checkpoint.startedAt != mowing) { mowing = checkpoint.startedAt; resumes = 0 }
        job?.cancel()
        job = host.viewModelScope.launch { waitAndResume(engine) }
    }

    private suspend fun waitAndResume(engine: MowingSimulation) {
        val since = SystemClock.elapsedRealtime()
        var refusals = 0
        fun done(text: String) { update { it.copy(autoResumeStatus = "") }; host.status(text) }
        while (true) {
            delay(POLL_MS)
            val s = state.value
            if (s.robotRoute.running) { update { it.copy(autoResumeStatus = "") }; return }
            val checkpoint = s.missionCheckpoint ?: return done(tr("Rain return: no mowing left to resume", "Retour pluie : plus de tonte à reprendre"))
            val waited = SystemClock.elapsedRealtime() - since
            val level = WeatherService.flow.value.assessment.level
            when (RainReturn.resume(level, waited, resumes)) {
                RainWait.WAIT -> update { it.copy(autoResumeStatus = RainReturn.waitingStatus(level, waited)) }
                RainWait.GIVE_UP -> {
                    event("rain_resume_given_up", tr("Rain return: no automatic resume, mowing to be resumed by hand", "Retour pluie : pas de reprise automatique, tonte à reprendre à la main"),
                        mapOf("waitedMs" to waited, "resumes" to resumes))
                    return done(tr("Rain: mowing to be resumed by hand", "Pluie : tonte à reprendre à la main"))
                }
                RainWait.RESUME -> {
                    val t = s.telemetry
                    if (!s.ready || !DockResume.docked(t.robotState, t.chargeState) || checkpoint.startedAt != mowing) continue
                    if (host.resumeRobotRoute(engine)) {
                        resumes++
                        event("rain_resumed", tr("Rain over: mowing resumed", "Pluie terminée : tonte reprise"), mapOf("waitedMs" to waited, "resumes" to resumes))
                        return done(tr("Rain over: mowing resumed", "Pluie terminée : tonte reprise"))
                    }
                    // Resume refused (link, map, robot state): a few more tries, then by hand.
                    if (++refusals >= MAX_REFUSALS) return done(tr("Rain over: automatic resume refused, resume by hand (${s.robotRoute.status})",
                        "Pluie terminée : reprise automatique refusée, reprenez à la main (${s.robotRoute.status})"))
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val MAX_REFUSALS = 3
    }
}
