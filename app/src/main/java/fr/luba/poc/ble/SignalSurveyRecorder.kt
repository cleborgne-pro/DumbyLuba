@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Signal surveys: samples of a dedicated survey route and of the continuous signal map, published and saved at intervals.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class SignalSurveyRecorder(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private val state get() = host.state

    private val surveyStore = SignalSurveyStore(host.getApplication<Application>().filesDir)
    /** Survey writes off the main thread: survey and "last shown" (continuous map) or archive only (dedicated survey). */
    private val surveyWrites = Channel<Pair<SignalSurvey, Boolean>>(Channel.UNLIMITED)
    /** Contours sampled by the survey route in progress; empty outside a survey route. */
    var surveyContours: List<List<Point>> = emptyList()
        private set
    /** Dedicated survey in progress (signal survey route): archived separately, its samples also feed the continuous map. */
    private var surveySession: SignalSurvey? = null
    private var surveySavedAt = 0L
    /** Continuous signal map ([SignalLiveMap]): in-memory version, published and saved at intervals. */
    private var liveMap: SignalSurvey? = null
    private var liveLast: SignalSample? = null
    private var livePublishedAt = 0L
    private var liveSavedAt = 0L

    /** Sample of a dedicated survey (gateway or local tracking), at most one per second. */
    fun recordSurveySample(sample: SignalSample) {
        surveySession?.let { survey ->
            if (sample.at - (survey.samples.lastOrNull()?.at ?: 0) >= 1000) {
                val next = survey.copy(samples = survey.samples + sample)
                surveySession = next
                val now = SystemClock.elapsedRealtime()
                if (now - surveySavedAt >= SignalLiveMap.SAVE_MS) { surveySavedAt = now; surveyWrites.trySend(next to false) }
            }
        }
        recordLiveSample(sample)
    }

    /** Position received: continuous map sample if the robot has moved (outside a dedicated survey, which already feeds it). */
    fun recordLiveSignal() {
        val ui = state.value
        if (surveySession != null && ui.robotRoute.running) return
        val t = ui.telemetry
        if (t.chargeState in setOf(1, 2)) return
        val now = Clock.now()
        SignalCoverage.sample(t, now, null, RobotTilt.current(now))?.let(::recordLiveSample)
    }

    private fun recordLiveSample(sample: SignalSample) {
        val ui = state.value
        val robotId = ui.selected?.address ?: return
        val map = ui.robotMap
        if (!map.complete || map.source.contains("DÉMONSTRATION", true)) return
        if (!SignalLiveMap.accepts(liveLast, sample)) return
        liveLast = sample
        val wall = Clock.now()
        val current = liveMap?.takeIf { it.robotId == robotId } ?: ui.signalSurvey
        val version = SignalLiveMap.version(current, robotId, map, wall, SignalLiveMap.newId(wall, UUID.randomUUID().toString()))
        val opened = version.sessionId != current.sessionId
        if (opened) event("signal_live_version", tr("New version of the continuous signal map", "Nouvelle version de la carte continue des signaux"),
            mapOf("sessionId" to version.sessionId, "previous" to current.sessionId, "samples" to version.samples.size))
        val next = version.copy(samples = SignalLiveMap.merge(version.samples, sample))
        liveMap = next
        val now = SystemClock.elapsedRealtime()
        if (opened || now - livePublishedAt >= SignalLiveMap.PUBLISH_MS) { livePublishedAt = now; update { it.copy(signalSurvey = next) } }
        if (opened || now - liveSavedAt >= SignalLiveMap.SAVE_MS) { liveSavedAt = now; surveyWrites.trySend(next to true) }
    }

    /** End of activity: last samples published and saved (dedicated survey closed). */
    fun flushSurveys() {
        surveySession?.let { surveyWrites.trySend(it to false) }
        surveySession = null
        liveMap?.let { live ->
            if (state.value.signalSurvey !== live) update { it.copy(signalSurvey = live) }
            surveyWrites.trySend(live to true)
            liveSavedAt = SystemClock.elapsedRealtime(); livePublishedAt = liveSavedAt
        }
    }

    /** Survey saved for [robotId] (continuous map shown at startup), or null. */
    fun stored(robotId: String): SignalSurvey? = surveyStore.read(robotId)

    /** Background writer of the surveys; a failed save of the survey shown raises `surveySaveError`. */
    fun startWriter() {
        host.viewModelScope.launch(Dispatchers.IO) {
            for ((survey, latest) in surveyWrites) {
                val saved = runCatching { surveyStore.save(survey, latest); if (survey.live && latest) surveyStore.pruneLive() }.isSuccess
                host.handler.post { if (state.value.signalSurvey.sessionId == survey.sessionId) update { it.copy(surveySaveError = !saved) } }
            }
        }
    }

    /** Another robot selected: pending samples flushed, its own saved survey returned (empty if none). */
    fun switchRobot(robotId: String): SignalSurvey {
        flushSurveys(); liveMap = null; liveLast = null
        return surveyStore.read(robotId) ?: SignalSurvey()
    }

    /**
     * Route started: a survey route ([contours] not empty) opens a dedicated survey from [survey], archived separately;
     * the display stays the continuous map, completed with its measurements.
     */
    fun beginRoute(contours: List<List<Point>>, survey: () -> SignalSurvey) {
        surveyContours = contours
        surveySession = null
        if (surveyContours.isNotEmpty()) {
            val opened = survey()
            surveySession = opened; surveySavedAt = SystemClock.elapsedRealtime()
            update { it.copy(surveySaveError = false) }
            surveyWrites.trySend(opened to false)
        }
    }

    /** Model cleared: latest continuous-map measurements (under 30 s) written synchronously before the end. */
    fun saveOnClose() {
        liveMap?.let { live -> runCatching { surveyStore.save(live, true) } }
    }
}
