package fr.luba.poc.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Signal survey overlaid on the map, shared between the map and Service → Signal surveys: the continuous map
 * (automatic survey while the robot moves) by default, or a survey chosen in the history.
 */
object SignalOverlay {
    private val pinnedFlow = MutableStateFlow<SignalSurvey?>(null)
    /** Survey chosen in the history; null: continuous map. */
    val pinned: StateFlow<SignalSurvey?> = pinnedFlow
    private val revealFlow = MutableStateFlow(false)
    /** Request to show the layers at the next opening of the map (consumed by [consumeReveal]). */
    val reveal: StateFlow<Boolean> = revealFlow

    /** Shows [survey] on the map (null: back to the continuous map) and opens the layers. */
    fun show(survey: SignalSurvey?) { pinnedFlow.value = survey; revealFlow.value = true }
    fun clear() { pinnedFlow.value = null }
    fun consumeReveal(): Boolean = revealFlow.value.also { revealFlow.value = false }

    /**
     * Survey to overlay on [map]: the one chosen in the history, otherwise the continuous map [live] if it is about this
     * map — same fingerprint, or same frame when the map was read again or comes from the Mammotion account.
     */
    fun resolve(map: RobotMap, live: SignalSurvey, pinned: SignalSurvey?): SignalSurvey? {
        pinned?.takeIf { it.samples.isNotEmpty() }?.let { return it }
        if (live.samples.isEmpty() || live.simulated || map.shapes.isEmpty()) return null
        if (live.mapKey == SignalCoverage.mapKey(map)) return live
        return live.takeIf { covers(map, it.samples) }
    }

    /** True if most readings fall within the extent of [map], grown by [marginM]. */
    fun covers(map: RobotMap, samples: List<SignalSample>, marginM: Double = 2.0): Boolean {
        val points = map.shapes.flatMap { it.points }
        if (points.isEmpty() || samples.isEmpty()) return false
        val minX = points.minOf { it.x } - marginM; val maxX = points.maxOf { it.x } + marginM
        val minY = points.minOf { it.y } - marginM; val maxY = points.maxOf { it.y } + marginM
        val inside = samples.count { it.point.x in minX..maxX && it.point.y in minY..maxY }
        return inside * 2 > samples.size
    }
}
