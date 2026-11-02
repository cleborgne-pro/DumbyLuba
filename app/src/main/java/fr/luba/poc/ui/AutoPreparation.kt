package fr.luba.poc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pause after a change before computing a route: a slider dragged over several steps computes once (Service pages, Mow). */
internal const val AUTO_PREPARATION_DELAY_MS = 400L

/** Planning step run off the main thread: [checkpoint] throws once the result is no longer wanted, [progress] names the stage. */
internal typealias Preparing<T> = (checkpoint: () -> Unit, progress: (String) -> Unit) -> T

/**
 * Route of a Service test page prepared without a button ([rememberAutoPreparation]): [result] is the route of the
 * current inputs, null while it is computed or when it failed ([error]).
 */
@Stable
internal class AutoPreparation<T : Any> {
    var result by mutableStateOf<T?>(null); private set
    var computing by mutableStateOf(false); private set
    var stage by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    /** Latest run: an abandoned run finishing late leaves the state of the current one alone. */
    private var generation = 0

    suspend fun run(prepare: Preparing<T>?, onReady: (T) -> Unit) = coroutineScope {
        val run = ++generation
        val main = this
        result = null; error = ""; stage = tr("Preparation", "Préparation")
        computing = prepare != null
        if (prepare == null) return@coroutineScope
        try {
            delay(AUTO_PREPARATION_DELAY_MS)
            val prepared = withContext(Dispatchers.Default) {
                val coroutine = currentCoroutineContext()
                prepare({ coroutine.ensureActive() }, { name -> main.launch { stage = name } })
            }
            result = prepared
            onReady(prepared)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (run == generation) error = e.message ?: tr("Computation impossible", "Calcul impossible") }
        finally { if (run == generation) computing = false }
    }
}

/**
 * Prepares the route as soon as the page opens and again after every change of [keys] (map, settings, obstacles,
 * base, start…), abandoning the computation in progress; [prepare] null (base or target missing) prepares nothing.
 * Shared by the finishing of the gaps and the border following; [MowDebugActions] shows its state above Mow.
 */
@Composable
internal fun <T : Any> rememberAutoPreparation(keys: List<Any?>, prepare: Preparing<T>?, onReady: (T) -> Unit = {}): AutoPreparation<T> {
    val preparation = remember { AutoPreparation<T>() }
    // The list compares by content: an equal emission of the robot state does not restart the computation.
    LaunchedEffect(keys) { preparation.run(prepare, onReady) }
    return preparation
}
