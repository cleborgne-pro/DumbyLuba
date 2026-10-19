package fr.luba.poc.simulation

import androidx.lifecycle.ViewModel
import fr.luba.poc.i18n.tr
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SimulationUi(val engine:MowingSimulation?=null,val state:SimState?=null,val playing:Boolean=false,val speed:Int=20,val error:String="")
class SimulationSession:ViewModel() {
    @Volatile private var signalStore: fr.luba.poc.map.SignalSurveyStore? = null
    private val signalWrites = kotlinx.coroutines.channels.Channel<fr.luba.poc.map.SignalSurvey>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var lastSignalSave = 0L
    fun storeSurveys(store: fr.luba.poc.map.SignalSurveyStore) { signalStore=store; persistSignals(mutable.value,true) }
    private fun persistSignals(ui: SimulationUi, force: Boolean=false) {
        val engine=ui.engine ?: return; val state=ui.state ?: return
        if (!engine.preview.mode.isSurvey || state.signalSamples.isEmpty() || state.surveyId.isBlank() || signalStore==null) return
        val now=System.currentTimeMillis()
        if (!force && now-lastSignalSave<1000) return
        lastSignalSave=now
        signalWrites.trySend(fr.luba.poc.map.SignalSurvey("simulation",fr.luba.poc.map.SignalCoverage.mapKey(engine.map),engine.preview.zoneIds,
            state.signalSamples,state.surveyId,state.surveyStartedAt,true,engine.preview.mode,engine.map))
    }

    var onTransit:(MowingSimulation,SimState)->Unit = { _,_ -> }
    private val mutable=MutableStateFlow(SimulationUi())
    val ui=mutable.asStateFlow()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (survey in signalWrites) {
                val error=runCatching { signalStore?.save(survey) }.exceptionOrNull()
                if (error!=null) withContext(Dispatchers.Main) { mutable.value=mutable.value.copy(error=tr("Heatmap save: ${error.message}", "Sauvegarde heatmap : ${error.message}")) }
            }
        }
        viewModelScope.launch {
        while(true) {
            delay(100)
            val before=mutable.value; val engine=before.engine; val state=before.state
            if(before.playing && engine!=null && state!=null) {
                // Accelerated playback: one step may go through several phases (departure, return), all logged.
                val crossed = mutableListOf<SimState>()
                val next = try {
                    withContext(Dispatchers.Default) { engine.advance(state,before.speed*0.1) { if (it.phase in JOURNALED_PHASES) crossed += it } }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    state.copy(phase=SimPhase.BLOCKED,log=(state.log +
                        (e.message ?: tr("Simulation error", "Erreur de simulation"))).takeLast(30))
                }
                mutable.value=applySimulationAdvance(mutable.value,before,next)
                if (mutable.value.state===next && (next.signalSamples.size!=state.signalSamples.size || next.phase in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED)))
                    persistSignals(mutable.value,next.phase in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED))
                if(mutable.value.state===next) {
                    if (crossed.isEmpty() && next.phase!=state.phase && next.phase in JOURNALED_PHASES) crossed += next
                    crossed.forEach { onTransit(engine,it) }
                }
            }
        }
    } }
    fun start(engine:MowingSimulation, state:SimState=engine.start()) {
        persistSignals(mutable.value,true); lastSignalSave=0L
        mutable.value=SimulationUi(engine,state,state.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED),mutable.value.speed)
        if(state.phase in JOURNALED_PHASES) onTransit(engine,state)
    }
    fun pause() { persistSignals(mutable.value,true); mutable.value=mutable.value.copy(playing=false) }
    /** Pause forced by going to the background: noted in the log so it is not mistaken for a blockage. */
    fun pauseInBackground() {
        if(!mutable.value.playing) return
        persistSignals(mutable.value,true); mutable.value=pausedInBackground(mutable.value)
    }
    fun toggle() { if(mutable.value.playing) persistSignals(mutable.value,true); mutable.value=toggleSimulation(mutable.value) }
    fun speed(value:Int) { mutable.value=mutable.value.copy(speed=value.coerceIn(1,300)) }
    fun clear() { persistSignals(mutable.value,true); mutable.value=SimulationUi(speed=mutable.value.speed) }
}

// A speed change or a pause keeps the computed move; a replaced scenario
// must never receive the result of the previous one.
internal fun applySimulationAdvance(current: SimulationUi, before: SimulationUi, next: SimState): SimulationUi =
    if (current.engine === before.engine && current.state === before.state)
        current.copy(state=next,playing=current.playing && next.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED))
    else current

/** Phases logged in the diagnostics: station routes, blockage (with its cause) and end. */
internal val JOURNALED_PHASES = setOf(SimPhase.LEAVING_DOCK,SimPhase.DEPARTING,SimPhase.RETURNING,SimPhase.BLOCKED,SimPhase.COMPLETE)

internal fun pausedInBackground(ui: SimulationUi): SimulationUi {
    val state=ui.state ?: return ui.copy(playing=false)
    if(!ui.playing) return ui
    return ui.copy(playing=false,state=state.copy(log=(state.log+
        tr("${(state.elapsed/60).toInt()} min · Paused: app moved to the background, restart playback",
            "${(state.elapsed/60).toInt()} min · En pause : application passée en arrière-plan, relancer la lecture")).takeLast(30)))
}

internal fun toggleSimulation(ui: SimulationUi): SimulationUi =
    if (ui.engine==null || ui.state==null || ui.state.phase in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED)) ui
    else ui.copy(playing=!ui.playing)
