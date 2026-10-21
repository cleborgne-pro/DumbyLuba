package fr.luba.poc.mapsync

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the sync needs from the robot link ([fr.luba.poc.ble.RobotBle] in the app, a fake in the tests). Every
 * suspending call returns null on success, otherwise the reason of the failure.
 */
interface MapSyncPort {
    /** Reason why the sync cannot start now (robot not connected, model not validated, robot busy…), null if it can. */
    fun refusal(): String?
    fun robotPosition(): Point?
    /** Current differences between the robot map and the corrected map, with their traces checked. */
    suspend fun differences(): List<SyncItem>
    /** Element removed by the robot with a replaced zone, as a sync item (traces checked on the current map). */
    fun recreate(shape: fr.luba.poc.map.MapShape): SyncItem?
    /** Move, blades stopped, by the usual move to a point, to the first of [targets] that the move accepts. */
    suspend fun driveTo(targets: List<Point>): String?
    /** Puts the robot in recording mode for [kind] (robot state [SyncKind.drawState] confirmed). */
    suspend fun startRecording(kind: SyncKind): String?
    /** Index of the first of [paths] that the moves accept (surface, footprint, pivots), null if none. */
    fun firstDrivable(paths: List<List<Point>>): Int?
    /** Drives [path] exactly, blades stopped, without recording (join onto the outline). */
    suspend fun driveExact(path: List<Point>): String?
    /** Drives [trace] exactly while the robot records; [progress] receives 0..1. */
    suspend fun driveTrace(trace: List<Point>, kind: SyncKind, progress: (Double) -> Unit): String?
    /** Ends the recording: the robot saves the element. */
    suspend fun endRecording(kind: SyncKind): String?
    /** Explicit save of the mapping (APK `saveTask`), only sent when the element is missing after [endRecording]. */
    suspend fun saveRecording(): String?
    /** Abandons the recording in progress, nothing saved. Never throws. */
    fun cancelRecording()
    suspend fun deleteElement(kind: SyncKind, hash: Long): String?
    /** Reads the Mammotion map from the robot again; null if the reading failed. */
    suspend fun readMap(): RobotMap?
    fun corrections(): List<MapCorrection>
    fun saveCorrections(corrections: List<MapCorrection>): Boolean
    /** Immediate stop of any movement. Never throws. */
    fun stopMotion(reason: String)
    fun log(kind: String, text: String, fields: Map<String, Any?> = emptyMap())
}

enum class SyncPhase(private val en: String, private val fr: String) {
    IDLE("Ready", "Prêt"), READING("Reading the robot map", "Lecture de la carte du robot"),
    TRANSIT("Driving to the start of the outline", "Trajet vers le début du contour"),
    STARTING("Starting the recording in the robot", "Démarrage de l'enregistrement dans le robot"),
    RECORDING("Driving the outline, the robot records", "Parcours du contour, le robot enregistre"),
    SAVING("The robot saves the element", "Le robot enregistre l'élément"),
    VERIFYING("Checking the robot map", "Vérification de la carte du robot"),
    DELETING("Deleting the former element", "Suppression de l'ancien élément"),
    DONE("Finished", "Terminé"), STOPPED("Stopped", "Arrêté"), FAILED("Stopped on a failure", "Arrêté sur un échec");
    val label: String get() = tr(en, fr)
    val active: Boolean get() = this !in setOf(IDLE, DONE, STOPPED, FAILED)
}

enum class ItemStatus(private val en: String, private val fr: String) {
    PENDING("Waiting", "En attente"), ACTIVE("In progress", "En cours"), DONE("In the robot", "Dans le robot"),
    FAILED("Failed", "Échec"), SKIPPED("Not done", "Non fait");
    val label: String get() = tr(en, fr)
}

data class SyncRunItem(val item: SyncItem, val status: ItemStatus = ItemStatus.PENDING, val note: String? = null)

/** Live state of a sync, shown by the page. [progress]: share of the outline driven (recording phase only). */
data class MapSyncRun(
    val phase: SyncPhase = SyncPhase.IDLE,
    val items: List<SyncRunItem> = emptyList(),
    val currentKey: String? = null,
    val message: String = "",
    val progress: Double? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
) {
    val running: Boolean get() = phase.active
    val current: SyncRunItem? get() = items.firstOrNull { it.item.key == currentKey }
    val done: Int get() = items.count { it.status == ItemStatus.DONE }
}

/**
 * Runs a sync: fresh reading of the robot map, differences recomputed on it, then for each chosen element: move to the
 * start of its outline, recording started, outline driven, recording ended, element found in the robot map read again,
 * former element deleted (replacement), corrections removed from the phone. Stops at the first failure: the robot
 * stops, a recording in progress is abandoned, the elements already done stay in the robot.
 */
class MapSyncRunner(private val port: MapSyncPort, private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(MapSyncRun())
    val state: StateFlow<MapSyncRun> = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var recording = false

    /** Starts the sync of the elements whose key is in [keys]. Returns false (with a message) if it cannot start. */
    fun start(keys: Set<String>): Boolean {
        if (state.value.running) return false
        port.refusal()?.let { reason -> mutable.value = MapSyncRun(SyncPhase.FAILED, message = reason, finishedAtMs = now()); return false }
        if (keys.isEmpty()) { mutable.value = MapSyncRun(SyncPhase.FAILED, message = tr("Nothing selected", "Rien de sélectionné"), finishedAtMs = now()); return false }
        mutable.value = MapSyncRun(SyncPhase.READING, message = SyncPhase.READING.label, startedAtMs = now())
        port.log("map_sync_start", tr("Sync with the Mammotion map started", "Synchronisation avec la carte Mammotion démarrée"), mapOf("keys" to keys.toList()))
        job = scope.launch { run(keys) }
        return true
    }

    /** User stop: robot stopped, recording in progress abandoned. */
    fun stop(reason: String = tr("Stopped by the user", "Arrêtée par l'utilisateur")) {
        if (!state.value.running) return
        job?.cancel(CancellationException(reason))
        halt(reason)
        finish(SyncPhase.STOPPED, reason)
    }

    /** Clears a finished sync (back to the list). */
    fun reset() { if (!state.value.running) mutable.value = MapSyncRun() }

    private fun halt(reason: String) {
        port.stopMotion(reason)
        if (recording) { recording = false; port.cancelRecording() }
    }

    private suspend fun run(keys: Set<String>) {
        try {
            var before = port.readMap() ?: run { fail(null, tr("The robot map could not be read", "La carte du robot n'a pas pu être lue")); return }
            val all = port.differences()
            val chosen = all.filter { it.key in keys }
            val missing = keys - all.map { it.key }.toSet()
            val queue = ArrayDeque(chosen.filter { it.possible })
            val items = chosen.map { if (it.possible) SyncRunItem(it) else SyncRunItem(it, ItemStatus.SKIPPED, it.refusal) }
            mutable.update { it.copy(items = items, message = if (missing.isEmpty()) "" else
                tr("${missing.size} element(s) no longer differ from the robot map", "${missing.size} élément(s) ne diffèrent plus de la carte du robot")) }
            if (queue.isEmpty()) return finish(SyncPhase.DONE, if (missing.isEmpty()) tr("Nothing to send to the robot", "Rien à envoyer au robot")
                else tr("The robot map already holds the chosen elements", "La carte du robot contient déjà les éléments choisis"))
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val after = sync(item, before) ?: return
                before = after.map
                after.lost.mapNotNull(port::recreate).forEach { extra ->
                    queue.addLast(extra)
                    mutable.update { it.copy(items = it.items + SyncRunItem(extra, if (extra.possible) ItemStatus.PENDING else ItemStatus.SKIPPED, extra.refusal)) }
                }
            }
            val skipped = state.value.items.count { it.status == ItemStatus.SKIPPED }
            finish(SyncPhase.DONE, tr("${state.value.done} element(s) now in the Mammotion map", "${state.value.done} élément(s) désormais dans la carte Mammotion") +
                if (skipped > 0) tr(" · $skipped not possible", " · $skipped impossible(s)") else "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { halt(e.message ?: e.javaClass.simpleName) }
            fail(state.value.currentKey, tr("Unexpected error: ${e.message ?: e.javaClass.simpleName}", "Erreur inattendue : ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private class Synced(val map: RobotMap, val lost: List<fr.luba.poc.map.MapShape>)

    /** One element; null after a failure (already reported). */
    private suspend fun sync(item: SyncItem, before: RobotMap): Synced? {
        mark(item.key, ItemStatus.ACTIVE)
        var trace = MapSyncPlanner.startNear(item.trace, item.kind.closed, port.robotPosition())
        port.log("map_sync_item", item.title, mapOf("key" to item.key, "action" to item.action.name, "kind" to item.kind.name,
            "traceM" to item.traceM, "previous" to item.previous?.id, "corrections" to item.correctionIds))
        val here = port.robotPosition()
        if (here == null || here.distance(trace.first()) > ARRIVED_M) {
            phase(SyncPhase.TRANSIT, item.key)
            port.driveTo(MapSyncPlanner.approaches(trace, item.kind, item.outline))?.let { fail(item.key, tr("Move to the start refused or interrupted: $it", "Trajet vers le début refusé ou interrompu : $it")); return null }
        }
        // Join onto the outline before recording, in a straight line at a shallow angle (never facing the boundary).
        val from = port.robotPosition()
        if (from != null && from.distance(trace.first()) > MapSyncPlanner.JOINED_M) {
            val options = MapSyncPlanner.joins(trace, item.kind.closed, from)
            val chosen = port.firstDrivable(options.map { listOf(from) + it.take(2) })?.let { options[it] } ?: run {
                fail(item.key, tr("No safe way to join the outline from here: record it with the Mammotion app",
                    "Aucun raccord sûr vers le contour depuis ici : enregistrez-le avec l'app Mammotion")); return null
            }
            port.driveExact(listOf(from, chosen.first()))?.let { fail(item.key, tr("Join onto the outline interrupted: $it", "Raccord au contour interrompu : $it")); return null }
            trace = chosen
        }
        phase(SyncPhase.STARTING, item.key)
        recording = true
        port.startRecording(item.kind)?.let { reason ->
            recording = false; port.cancelRecording()
            fail(item.key, tr("The robot did not start recording: $reason", "Le robot n'a pas démarré l'enregistrement : $reason")); return null
        }
        phase(SyncPhase.RECORDING, item.key)
        mutable.update { it.copy(progress = 0.0) }
        port.driveTrace(trace, item.kind) { p -> mutable.update { it.copy(progress = p.coerceIn(0.0, 1.0)) } }?.let { reason ->
            recording = false; port.cancelRecording()
            fail(item.key, tr("Outline interrupted, recording abandoned: $reason", "Contour interrompu, enregistrement abandonné : $reason")); return null
        }
        phase(SyncPhase.SAVING, item.key)
        val ended = port.endRecording(item.kind)
        recording = false
        phase(SyncPhase.VERIFYING, item.key)
        val after = port.readMap() ?: run {
            fail(item.key, tr("The robot map could not be read again: check it in the Mammotion app", "La carte du robot n'a pas pu être relue : vérifiez-la dans l'app Mammotion"))
            return null
        }
        var read = after
        var created = MapSyncPlanner.created(before, read, item.kind, item.outline)
        if (created == null) {
            // Not saved by the end of the recording alone: explicit save of the mapping (BLE), then read again once.
            port.log("map_sync_save", tr("Element missing after the end of the recording: explicit save", "Élément absent après la fin de l'enregistrement : sauvegarde explicite"),
                mapOf("key" to item.key, "elements" to read.shapes.size))
            port.saveRecording()
            read = port.readMap() ?: read
            created = MapSyncPlanner.created(before, read, item.kind, item.outline)
        }
        if (created == null) {
            fail(item.key, (ended?.let { "$it · " } ?: "") + tr("the new element is not in the robot map: nothing was changed on the phone",
                "le nouvel élément n'est pas dans la carte du robot : rien n'a été modifié sur le téléphone"))
            return null
        }
        port.log("map_sync_recorded", tr("Element recorded by the robot", "Élément enregistré par le robot"), mapOf("key" to item.key, "hash" to created.id, "points" to created.points.size))
        var map = read
        var lost = emptyList<fr.luba.poc.map.MapShape>()
        val previous = item.previous
        if (previous != null) {
            phase(SyncPhase.DELETING, item.key)
            port.deleteElement(item.kind, previous.id)?.let { reason ->
                fail(item.key, tr("New element recorded but the former one was not deleted ($reason): delete it in the Mammotion app",
                    "Nouvel élément enregistré mais l'ancien n'a pas été supprimé ($reason) : supprimez-le dans l'app Mammotion"))
                return null
            }
            val cleaned = port.readMap() ?: run {
                fail(item.key, tr("The robot map could not be read after the deletion: check it in the Mammotion app",
                    "La carte du robot n'a pas pu être relue après la suppression : vérifiez-la dans l'app Mammotion"))
                return null
            }
            if (cleaned.shapes.any { it.id == previous.id }) {
                fail(item.key, tr("New element recorded but the former one is still in the robot: delete it in the Mammotion app",
                    "Nouvel élément enregistré mais l'ancien est toujours dans le robot : supprimez-le dans l'app Mammotion"))
                return null
            }
            lost = MapSyncPlanner.lost(read, cleaned, setOf(previous.id))
            if (lost.isNotEmpty()) port.log("map_sync_lost", tr("Elements removed by the robot with the former one", "Éléments supprimés par le robot avec l'ancien"),
                mapOf("key" to item.key, "lost" to lost.map { it.id }))
            map = cleaned
        }
        val kept = MapSyncPlanner.remaining(port.corrections(), item)
        val savedNote = if (item.correctionIds.isEmpty() || port.saveCorrections(kept)) null
            else tr("the corrections it replaces could not be removed from the phone: disable them", "les corrections qu'il remplace n'ont pas pu être retirées du téléphone : désactivez-les")
        mark(item.key, ItemStatus.DONE, listOfNotNull(tr("Mammotion element ${created.id}", "Élément Mammotion ${created.id}"), savedNote,
            if (lost.isNotEmpty()) tr("${lost.size} element(s) removed with it, recorded again next", "${lost.size} élément(s) supprimé(s) avec lui, réenregistré(s) ensuite") else null).joinToString(" · "))
        mutable.update { it.copy(progress = null) }
        return Synced(map, lost)
    }

    private fun phase(phase: SyncPhase, key: String?) = mutable.update { it.copy(phase = phase, currentKey = key, message = phase.label) }

    private fun mark(key: String, status: ItemStatus, note: String? = null) = mutable.update { run ->
        run.copy(currentKey = key, items = run.items.map { if (it.item.key == key) it.copy(status = status, note = note ?: it.note) else it })
    }

    private fun fail(key: String?, reason: String) {
        if (recording) { recording = false; port.cancelRecording() }
        port.stopMotion(reason)
        if (key != null) mark(key, ItemStatus.FAILED, reason)
        finish(SyncPhase.FAILED, reason)
    }

    private fun finish(phase: SyncPhase, message: String) {
        mutable.update { run ->
            run.copy(phase = phase, message = message, progress = null, finishedAtMs = now(),
                items = run.items.map { if (it.status == ItemStatus.PENDING || it.status == ItemStatus.ACTIVE) it.copy(status = ItemStatus.SKIPPED) else it })
        }
        port.log("map_sync_end", message, mapOf("phase" to phase.name, "done" to state.value.done, "items" to state.value.items.map { "${it.item.key}:${it.status.name}" }))
    }

    companion object {
        /** Robot already at the start of the outline: no move before the recording. */
        const val ARRIVED_M = 0.3
    }
}
