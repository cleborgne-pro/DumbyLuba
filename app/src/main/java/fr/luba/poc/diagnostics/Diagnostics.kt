package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.*
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*

 data class CampaignConfig(val durationMinutes: Int = 30, val zeros: Boolean = true,
    val cycles: Boolean = true, val watchdog: Boolean = true, val batteryFloor: Int = 30,
    val exception: Boolean = true) {
    init { require(durationMinutes in listOf(0, 1, 5, 10, 30, 60)); require(batteryFloor in 10..80) }
}
 data class DiagnosticStep(val id: String, val title: String, val seconds: Long)
 data class TestResult(val id: String, val title: String, val outcome: String,
    val durationMs: Long, val detail: String)
 data class CampaignUi(val id: String = "", val running: Boolean = false,
    val outcome: String = tr("Not started", "Non démarrée"), val step: String = "", val elapsedMs: Long = 0,
    val results: List<TestResult> = emptyList(), val summary: String = "")

object DiagnosticPlan {
    fun steps(config: CampaignConfig): List<DiagnosticStep> = buildList {
        add(DiagnosticStep("baseline", tr("Initial telemetry", "Télémétrie initiale"), 30))
        if (config.zeros) add(DiagnosticStep("zero_5hz", tr("Zero speed repeated at 5 Hz", "Vitesse nulle répétée à 5 Hz"), 30))
        if (config.cycles) repeat(3) { add(DiagnosticStep("cycle_${it + 1}", tr("STOP / reconnection ${it + 1}/3", "STOP / reconnexion ${it + 1}/3"), 0)) }
        if (config.watchdog) add(DiagnosticStep("watchdog", tr("Telemetry silence simulated locally", "Silence télémétrie simulé localement"), 0))
        if (config.exception) add(DiagnosticStep("exception", tr("Simulated application exception", "Exception applicative simulée"), 0))
        add(DiagnosticStep("stability", tr("BLE stability ${if (config.durationMinutes == 0) "unlimited" else "${config.durationMinutes} min"}",
            "Stabilité BLE ${if (config.durationMinutes == 0) "illimitée" else "${config.durationMinutes} min"}"), config.durationMinutes * 60L))
        add(DiagnosticStep("final_stop", tr("Final STOP confirmed by GATT", "STOP final confirmé par GATT"), 0))
    }
}

/** Memory-bounded statistics: 1 ms histograms for p50/p95, exact maximum. */
class TimingStats {
    private val histogram = LongArray(10001)
    var count = 0L; private set
    var maxMs = 0L; private set
    private var total = 0.0
    fun add(ms: Long) {
        require(ms >= 0)
        histogram[ms.coerceAtMost(10000).toInt()]++
        count++; maxMs = maxOf(maxMs, ms); total += ms
    }
    val meanMs: Double get() = if (count == 0L) 0.0 else total / count
    fun percentile(percent: Int): Long? {
        require(percent in 1..100)
        if (count == 0L) return null
        val target = kotlin.math.ceil(count * percent / 100.0).toLong()
        var cumulative = 0L
        histogram.forEachIndexed { value, samples -> cumulative += samples; if (cumulative >= target) return value.toLong() }
        return 10000
    }
    fun fields(): Map<String, Any?> = mapOf("count" to count, "meanMs" to meanMs,
        "p50Ms" to percentile(50), "p95MsCappedAt10000" to percentile(95), "maxMs" to maxMs)
}

/** Local tests runnable inside the APK; no BLE write. */
object LocalChecks {
    fun run(): List<TestResult> {
        fun rejects(block: () -> Unit) { try { block() } catch (_: IllegalArgumentException) { return }; error(tr("Invalid frame accepted", "Trame invalide acceptée")) }
        val cases = listOf<Pair<String, () -> Unit>>(
            tr("Zero protobuf STOP, without blade command", "STOP protobuf nul, sans commande lame") to {
                val msg = LubaMsg.parseFrom(Commands().stop(1))
                check(msg.driver.hasTodevDevmotionCtrl())
                check(msg.driver.todevDevmotionCtrl.setLinearSpeed == 0 && msg.driver.todevDevmotionCtrl.setAngularSpeed == 0)
                check(!msg.driver.hasMowCtrlByHand())
            },
            tr("Fragmentation / reassembly MTU 23 and 512", "Fragmentation / reconstitution MTU 23 et 512") to {
                for (mtu in listOf(23, 512)) {
                    val bytes = ByteArray(600) { it.toByte() }; val rx = Blufi()
                    val frames = Blufi().encode(bytes, mtu)
                    check(frames.all { it.size <= mtu - 3 })
                    frames.dropLast(1).forEach { check(rx.receive(it) == null) }
                    check(bytes.contentEquals(rx.receive(frames.last())))
                }
            },
            tr("Corrupted CRC rejected", "CRC corrompu rejeté") to { rejects { Blufi().receive(byteArrayOf(0x4d, 2, 0, 1, 7, 0, 0)) } },
            tr("Encrypted BLE refused", "BLE chiffré refusé") to { rejects { Blufi().receive(byteArrayOf(0x4d, 1, 0, 0)) } },
            tr("Sequence loss rejected", "Perte de séquence rejetée") to {
                val rx = Blufi(); rx.receive(byteArrayOf(0x4d, 0, 0, 1, 7))
                rejects { rx.receive(byteArrayOf(0x4d, 0, 2, 1, 8)) }
            },
            tr("Missing final fragment rejected", "Fragment final manquant rejeté") to {
                val rx = Blufi(); rx.receive(byteArrayOf(0x4d, 16, 0, 3, 3, 0, 9))
                rejects { rx.receive(byteArrayOf(0x4d, 0, 1, 1, 8)) }
            },
            tr("Watchdog without first report / after report", "Watchdog sans premier rapport / après rapport") to {
                val w = TelemetryWatchdog(); w.start(100)
                check(!w.expired(1099, 1000)); check(w.expired(1100, 1000))
                w.recordValidReport(500); check(!w.expired(1499, 1000)); check(w.expired(1500, 1000))
            },
            tr("ACK and empty report do not refresh telemetry", "ACK et rapport vide ne rafraîchissent pas la télémétrie") to {
                val r = TelemetryReducer(); check(!r.accept(LubaMsg.parseFrom(Commands().sync(1)), 1, 1))
                val empty = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.getDefaultInstance())).build()
                check(!r.accept(empty, 2, 2)); check(r.value.lastReportAt == null)
            },
            tr("Negative position / heading / 10^-4 conversion", "Position négative / heading / conversion 10^-4") to {
                val loc = rpt_dev_location.newBuilder().setRealPosX(-123450).setRealPosY(234560).setRealToward(924000)
                val msg = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().addLocations(loc))).build()
                val r = TelemetryReducer(); check(r.accept(msg, 1, 1))
                check(r.value.x == -12.345 && r.value.y == 23.456 && kotlin.math.abs(requireNotNull(r.value.heading) - 357.6) < 1e-9 && r.value.rawHeading == 92.4)
            }
        )
        return cases.mapIndexed { index, (title, block) ->
            val start = System.nanoTime()
            try { block(); TestResult("local_$index", title, "PASS LOCAL", (System.nanoTime() - start) / 1000000, tr("Without robot", "Sans robot")) }
            catch (error: Exception) { TestResult("local_$index", title, "FAIL LOCAL", (System.nanoTime() - start) / 1000000, error.message ?: error.javaClass.simpleName) }
        }
    }
}

 data class PlanOutcome(val success: Boolean, val failure: String? = null)

/** Single order; any unexpected exception prevents the following cases. */
object CampaignRunner {
    suspend fun run(config: CampaignConfig, now: () -> Long,
        onStart: (DiagnosticStep) -> Unit, execute: suspend (DiagnosticStep) -> Unit,
        onResult: (TestResult) -> Unit): PlanOutcome {
        for (step in DiagnosticPlan.steps(config)) {
            onStart(step)
            val start = now()
            try { execute(step) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                onResult(TestResult(step.id, step.title, "FAILED", now() - start, detail))
                return PlanOutcome(false, detail)
            }
            onResult(TestResult(step.id, step.title, "PASS BLE", now() - start,
                if (step.id == "watchdog") tr("Local injection detected; STOP written; planned reconnection succeeded. No radio loss test.",
                    "Injection locale détectée ; STOP écrit ; reconnexion planifiée réussie. Aucun test de perte radio.")
                else tr("BLE software criteria met; proves neither motion nor mechanical stop.",
                    "Critères logiciels BLE satisfaits ; ne prouve ni mouvement ni arrêt mécanique.")))
        }
        return PlanOutcome(true)
    }
}
