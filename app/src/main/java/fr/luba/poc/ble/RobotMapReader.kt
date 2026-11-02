package fr.luba.poc.ble

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapTransfer
import fr.luba.poc.map.RobotMap
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.ui.RobotModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Read-only reading of the Mammotion map held by the robot (outlines, then the base): requests, retries of the
 * elements that stop answering, end on a complete map, a failure or the overall timeout.
 *
 * Extracted from [RobotBle] like its other collaborators: the reading state is owned here, the forwarders below list
 * what it reads from the host.
 */
internal class RobotMapReader(private val host: RobotBle) {
    private val state get() = host.state
    private val commands get() = host.commands
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)

    private var transfer: MapTransfer? = null
    private var job: Job? = null

    /** Connection for which the automatic map reading was already attempted (one attempt per connection). */
    private var autoReadConnection = -1L

    /** True while a reading is in progress. */
    val reading: Boolean get() = transfer != null

    /** Reason why the BLE map cannot be read now, null when the reading may start. */
    fun refusal(): String? = when {
        state.value.robotRoute.running -> tr("Finish the BLE route first", "Terminer le trajet BLE")
        state.value.manualActive -> tr("Release the joystick before this action", "Relâcher le joystick avant cette action")
        state.value.mowing.active -> tr("Finish the mission before re-reading the map", "Terminer la mission avant de relire la carte")
        state.value.teach.running || state.value.navTest.running || !state.value.ready || state.value.mapLoading || state.value.campaign.running ||
            state.value.bladeTestActive || host.bladeMayBeOn || host.heightTarget != null -> tr("Map: finish the tests and connect the robot", "Carte : terminer les essais et connecter le robot")
        else -> null
    }

    /**
     * Automatic BLE reading when no map is known for the connected robot (new installation, cache cleared, robot
     * never read): without it the map stays empty while the robot is connected, the account map being shown only
     * without Bluetooth. Called by the screen once telemetry is received; at most once per connection, silent when
     * the robot is busy (the manual reading stays available). Returns true when the reading started.
     */
    fun autoFetch(): Boolean {
        if (autoReadConnection == host.connectionId || state.value.connection != ConnectionState.Receiving ||
            state.value.mammotionMap.shapes.isNotEmpty() || refusal() != null) return false
        autoReadConnection = host.connectionId
        event("map_auto_read", tr("No map known for this robot: automatic BLE reading", "Aucune carte connue pour ce robot : lecture BLE automatique"))
        fetch()
        return true
    }

    fun fetch() {
        refusal()?.let { host.status(it); return }
        job?.cancel()
        val reader = host.stationReader
        val transfer = MapTransfer(initialStation = reader.station ?: state.value.robotMap.station, clock = { SystemClock.elapsedRealtime() })
        this.transfer = transfer
        update { it.copy(mapLoading = true, mapReadFailure = null).withMammotionMap(RobotMap(source = tr("Reading the BLE map…", "Lecture de la carte BLE…"), station = reader.station ?: it.mammotionMap.station)) }
        host.send(commands.sync(), "MAP_SYNC"); host.send(commands.mapHashes(), "MAP_HASHES")
        event("map_start", tr("Read-only BLE outlines", "Lecture seule des contours BLE"), mapOf("model" to RobotModel.detect(state.value.selected?.name).name, "productType" to state.value.productType,
            "navReceiver" to commands.navReceiver.name, "viaGateway" to state.value.viaGateway))
        // Each element that stops answering is asked again then left out (MapTransfer.tick): the reading moves on
        // instead of stalling; the overall bound only covers a robot that keeps the link without ever finishing.
        job = host.viewModelScope.launch {
            val end = SystemClock.elapsedRealtime() + MAX_READ_MS
            while (this@RobotMapReader.transfer === transfer && SystemClock.elapsedRealtime() < end) {
                delay(250)
                if (this@RobotMapReader.transfer !== transfer) return@launch
                val retries = transfer.tick(commands)
                retries.forEach { host.send(it, "MAP_RETRY") }
                progress(transfer, retries.isNotEmpty())
            }
            if (this@RobotMapReader.transfer !== transfer) return@launch
            this@RobotMapReader.transfer = null
            val reason = tr("Map incomplete: ${MAX_READ_MS / 60_000} min timeout, restart the reading", "Carte incomplète : timeout ${MAX_READ_MS / 60_000} min, relancer la lecture")
            update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }
            event("map_timeout", tr("Map not confirmed complete", "Carte non confirmée complète"), mapOf("source" to transfer.snapshot().source,
                "navReceiver" to commands.navReceiver.name, "navFrames" to transfer.navFrames, "navMessages" to transfer.navSummary))
        }
    }

    /** Robot message received during a reading: acknowledged, then the reading progresses or fails. */
    fun accept(message: LubaMsg) {
        val transfer = transfer ?: return
        try {
            val replies = transfer.accept(message, commands)
            replies.forEach { host.send(it, "MAP_REQUEST_ACK") }
            progress(transfer, replies.isNotEmpty())
        } catch (error: Exception) {
            job?.cancel(); this.transfer = null
            val reason = tr("Map: ${error.message}", "Carte : ${error.message}")
            update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }; event("map_error", error.message ?: tr("Invalid map", "Carte invalide"))
        }
    }

    /** Link closed: the reading stops without a failure reason. */
    fun cancel() {
        job?.cancel(); transfer = null; update { it.copy(mapLoading = false, mapReadFailure = null) }
    }

    /** Records the reading steps and ends the reading when the map is complete or nothing more will come. */
    private fun progress(transfer: MapTransfer, changed: Boolean) {
        val notices = transfer.notices()
        notices.forEach { event(it.kind, it.detail, it.fields) }
        if (!changed && notices.isEmpty() && !transfer.finished) return
        val snapshot = transfer.snapshot()
        update { it.withMammotionMap(snapshot) }
        if (snapshot.complete) {
            host.cacheMammotionMap(snapshot)
            this.transfer = null; update { it.copy(mapLoading = false, mapReadFailure = null) }; event("map_complete", snapshot.source); job?.cancel()
        } else if (transfer.finished) {
            this.transfer = null
            val reason = transfer.failure?.let { tr("Map incomplete: $it, restart the reading", "Carte incomplète : $it, relancer la lecture") }
                ?: tr("Map incomplete: ${transfer.missing} element(s) not read (${snapshot.source}), restart the reading", "Carte incomplète : ${transfer.missing} élément(s) non lu(s) (${snapshot.source}), relancer la lecture")
            update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }
            event("map_incomplete", reason, mapOf("source" to snapshot.source, "missing" to transfer.missing,
                "navReceiver" to commands.navReceiver.name, "navFrames" to transfer.navFrames, "navMessages" to transfer.navSummary))
            job?.cancel()
        }
    }

    companion object {
        /** Bound of a BLE map reading that keeps going without ending (each element has its own retries). */
        const val MAX_READ_MS = 300_000L
    }
}
