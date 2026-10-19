package fr.luba.poc.history

import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MowingEstimate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Outcome of a task recorded in the history. */
enum class MowingOutcome(private val labelEn: String, private val labelFr: String) {
    RUNNING("In progress", "En cours"), COMPLETE("Finished", "Terminée"), INTERRUPTED("Interrupted", "Interrompue");

    val label: String get() = tr(labelEn, labelFr)
}

/**
 * Type of a history task: real mowing (blades), signal survey run, or route followed with blades stopped. Only
 * mowings adjust the estimates and open the end-of-mowing report.
 */
enum class TaskKind(private val labelEn: String, private val labelFr: String) {
    MOW("Mowing", "Tonte"), SURVEY("Signal survey", "Relevé des signaux"), FOLLOW("Run with blades stopped", "Parcours lames arrêtées");

    val label: String get() = tr(labelEn, labelFr)
}

/**
 * Estimate frozen when the mowing starts: total duration and planned recharges, with the parameters that produced
 * it. [travelSeconds] is the raw run time (distance / speed) before adjustment; [timeFactor], [autonomyMinutes] and
 * [chargeMinutes] are the values actually used (settings or calibration).
 */
data class MowingForecast(
    val totalSeconds: Double, val recharges: Int, val travelSeconds: Double,
    val autonomyMinutes: Double, val chargeMinutes: Double, val timeFactor: Double,
    val battery: Double, val calibratedFrom: Int = 0,
)

/**
 * Measurements accumulated during the mowing. [activeSeconds]: robot off charge (mowing, links, returns, docking);
 * [chargingSeconds]: charging contact established. [batteryUsed] and [batteryCharged] are the battery points lost
 * off charge and gained on charge, over the same intervals as the durations.
 */
data class MowingTotals(
    val activeSeconds: Double = 0.0, val chargingSeconds: Double = 0.0,
    val batteryUsed: Double = 0.0, val batteryCharged: Double = 0.0,
) {
    val totalSeconds: Double get() = activeSeconds + chargingSeconds
}

/** A recorded task (real mowing with blades, survey or run): estimate at start, actual figures and outcome. */
data class MowingRecord(
    val id: String, val robotId: String, val robotName: String? = null,
    val startedAt: Long, val endedAt: Long? = null,
    val outcome: MowingOutcome = MowingOutcome.RUNNING, val reason: String? = null,
    val zoneNames: List<String> = emptyList(), val areaM2: Double = 0.0, val mode: String = "",
    val heightMm: Int = 60, val speedMps: Double = 0.3,
    val returnPercent: Int = 20, val resumePercent: Int = 90,
    /** Total distance of the prepared route, and cutting metres to cover / covered. */
    val plannedMeters: Double = 0.0, val cuttingMeters: Double = 0.0, val doneMeters: Double = 0.0,
    val forecast: MowingForecast,
    val totals: MowingTotals = MowingTotals(),
    val recharges: Int = 0, val resumes: Int = 0, val obstacles: Int = 0,
    val batteryStart: Int? = null, val batteryEnd: Int? = null,
    /** Excluded by the user from estimate calibration (test, abnormal mowing). */
    val excluded: Boolean = false,
    val kind: TaskKind = TaskKind.MOW,
    /** How the route was tracked, at each start or resume (empty for tasks recorded before 1.2.0). */
    val links: Set<TrackingLink> = emptySet(),
    /** Route trackings handed to the gateway on the robot (autonomous tracking). */
    val gatewayLegs: Int = 0,
) {
    val progress: Double get() = if (cuttingMeters <= 0) 0.0 else (doneMeters / cuttingMeters).coerceIn(0.0, 1.0)
    val finished: Boolean get() = outcome != MowingOutcome.RUNNING
}

/** Observed duration and recharges compared with the estimate, and actual parameters derived from the mowing. */
data class MowingComparison(
    /** Estimate scaled down to the part mowed (interrupted mowing); full estimate otherwise. */
    val expectedSeconds: Double, val actualSeconds: Double,
    val expectedRecharges: Int, val actualRecharges: Int,
    /** Time off charge / raw run time of the part mowed: pivots, links, returns, slowdowns. */
    val observedTimeFactor: Double?,
    /** Minutes per 100 % of battery off charge and on charge, if the mowing measured enough. */
    val observedAutonomyMinutes: Double?, val observedChargeMinutes: Double?,
) {
    /** Relative duration deviation (+0.2 = 20 % longer than planned). */
    val durationDelta: Double? get() = if (expectedSeconds > 60) actualSeconds / expectedSeconds - 1 else null
    val rechargeDelta: Int get() = actualRecharges - expectedRecharges
    val durationVerdict: String get() = durationDelta?.let { d ->
        when {
            abs(d) <= MowingCalibration.TOLERANCE -> tr("As estimated", "Conforme à l'estimation")
            d > 0 -> tr("%d %% longer than planned", "Plus longue que prévu de %d %%").format(Math.round(d * 100))
            else -> tr("%d %% shorter than planned", "Plus courte que prévu de %d %%").format(Math.round(-d * 100))
        }
    } ?: tr("Too short to compare", "Trop courte pour comparer")
    val rechargeVerdict: String get() = when {
        rechargeDelta == 0 -> tr("As estimated", "Conforme à l'estimation")
        rechargeDelta > 0 -> tr("${Math.abs(rechargeDelta)} more recharge${if (rechargeDelta > 1) "s" else ""} than planned",
            "${Math.abs(rechargeDelta)} recharge${if (rechargeDelta > 1) "s" else ""} de plus que prévu")
        else -> tr("${Math.abs(rechargeDelta)} fewer recharge${if (rechargeDelta < -1) "s" else ""} than planned",
            "${Math.abs(rechargeDelta)} recharge${if (rechargeDelta < -1) "s" else ""} de moins que prévu")
    }

    companion object {
        fun of(record: MowingRecord): MowingComparison {
            val p = if (record.outcome == MowingOutcome.COMPLETE) 1.0 else record.progress
            val t = record.totals
            // Recharges expected for the part mowed: rounded down (a recharge planned at 60 % of the route is not
            // due at 50 %).
            val expectedRecharges = if (p >= 1.0) record.forecast.recharges else kotlin.math.floor(record.forecast.recharges * p + 1e-9).toInt()
            return MowingComparison(record.forecast.totalSeconds * p, t.totalSeconds, expectedRecharges, record.recharges,
                MowingCalibration.timeFactor(record), MowingCalibration.autonomy(t), MowingCalibration.charge(t))
        }
    }
}

/**
 * Estimate calibration on the robot's recent real mowings: duration factor (measured time off charge / raw run
 * time), observed runtime and recharge duration. Each quantity is only adjusted if there are enough measurements;
 * otherwise the user's setting is still used.
 */
data class MowingCalibration(
    val timeFactor: Double? = null, val autonomyMinutes: Double? = null, val chargeMinutes: Double? = null,
    val records: Int = 0,
) {
    val empty: Boolean get() = timeFactor == null && autonomyMinutes == null && chargeMinutes == null

    companion object {
        /** Tolerated deviation before reporting a duration different from the estimate. */
        const val TOLERANCE = 0.10
        /** Recent mowings taken into account: the robot, blades and lawn change over time. */
        const val RECENT = 8
        const val MIN_ACTIVE_S = 300.0
        const val MIN_PROGRESS = 0.05
        const val MIN_USED = 5.0
        const val MIN_CHARGED = 10.0

        fun timeFactor(record: MowingRecord): Double? {
            val p = if (record.outcome == MowingOutcome.COMPLETE) 1.0 else record.progress
            val raw = record.forecast.travelSeconds * p
            if (record.totals.activeSeconds < MIN_ACTIVE_S || p < MIN_PROGRESS || raw <= 0) return null
            return (record.totals.activeSeconds / raw).coerceIn(0.5, 4.0)
        }
        /**
         * Cautious model for decisions during mowing ([fr.luba.poc.simulation.FinishPolicy]: finish below the low
         * threshold, leave before the high threshold): shortest runtime and longest duration factor between the estimate
         * frozen at start and the measurements of the current mowing. Returns (runtime in minutes, duration factor).
         */
        fun finishModel(record: MowingRecord): Pair<Double, Double> {
            val autonomy = listOfNotNull(record.forecast.autonomyMinutes.takeIf { it > 0 }, autonomy(record.totals)).minOrNull() ?: 60.0
            val factor = maxOf(1.0, record.forecast.timeFactor, timeFactor(record.copy(outcome = MowingOutcome.RUNNING)) ?: 0.0)
            return autonomy to factor
        }
        fun autonomy(t: MowingTotals): Double? =
            if (t.batteryUsed < MIN_USED || t.activeSeconds < MIN_ACTIVE_S) null else (t.activeSeconds / 60 / t.batteryUsed * 100).coerceIn(10.0, 600.0)
        fun charge(t: MowingTotals): Double? =
            if (t.batteryCharged < MIN_CHARGED || t.chargingSeconds <= 0) null else (t.chargingSeconds / 60 / t.batteryCharged * 100).coerceIn(10.0, 600.0)

        /** Finished or interrupted mowings of robot [robotId] (all robots if it has none), not excluded; surveys and blades-stopped runs left out. */
        fun from(history: List<MowingRecord>, robotId: String?): MowingCalibration {
            val usable = history.filter { it.finished && !it.excluded && it.kind == TaskKind.MOW }
            val own = usable.filter { it.robotId == robotId }.ifEmpty { usable }
            val recent = own.sortedByDescending { it.endedAt ?: it.startedAt }.take(RECENT)
            // Averages weighted by measured duration: a long mowing counts more than a short test.
            val factors = recent.mapNotNull { r -> timeFactor(r)?.let { it to r.totals.activeSeconds } }
            val factor = factors.takeIf { it.isNotEmpty() }?.let { f -> f.sumOf { it.first * it.second } / f.sumOf { it.second } }
            val drained = recent.filter { autonomy(it.totals) != null }.map { it.totals }
            val autonomy = drained.takeIf { it.isNotEmpty() }?.let { autonomy(MowingTotals(activeSeconds = it.sumOf { t -> t.activeSeconds }, batteryUsed = it.sumOf { t -> t.batteryUsed })) }
            val charged = recent.filter { charge(it.totals) != null }.map { it.totals }
            val charge = charged.takeIf { it.isNotEmpty() }?.let { charge(MowingTotals(chargingSeconds = it.sumOf { t -> t.chargingSeconds }, batteryCharged = it.sumOf { t -> t.batteryCharged })) }
            val used = recent.count { timeFactor(it) != null || autonomy(it.totals) != null || charge(it.totals) != null }
            return MowingCalibration(factor, autonomy, charge, used)
        }
    }
}

/** Mowing estimate: same formula as [MowingEstimate.fromDistance], adjusted by the calibration. */
object MowingForecaster {
    fun forecast(meters: Double, speedMps: Double, autonomyMinutes: Double, chargeMinutes: Double, battery: Double,
                 lowerPercent: Double, upperPercent: Double, calibration: MowingCalibration?): MowingForecast {
        val factor = calibration?.timeFactor ?: 1.0
        val autonomy = calibration?.autonomyMinutes ?: autonomyMinutes
        val charge = calibration?.chargeMinutes ?: chargeMinutes
        val estimate = MowingEstimate.fromDistance(meters * factor, speedMps, autonomy, charge, battery.coerceIn(0.0, 100.0), lowerPercent, upperPercent)
        return MowingForecast(estimate.totalSeconds, estimate.recharges, meters / speedMps, autonomy, charge, factor, battery,
            if (calibration == null || calibration.empty) 0 else calibration.records)
    }

    fun estimate(meters: Double, speedMps: Double, autonomyMinutes: Double, chargeMinutes: Double, battery: Double,
                 lowerPercent: Double, upperPercent: Double, calibration: MowingCalibration?): MowingEstimate =
        forecast(meters, speedMps, autonomyMinutes, chargeMinutes, battery, lowerPercent, upperPercent, calibration)
            .let { MowingEstimate(it.totalSeconds, it.recharges) }

    /** Sentence giving the source of the estimate, shown under the preview. */
    fun note(calibration: MowingCalibration?): String = if (calibration == null || calibration.empty)
        tr("Simplified estimate: recharging included, return trips and pivots excluded. It will adjust on the next real mowings.",
            "Estimation simplifiée : recharge incluse, trajets de retour et pivots exclus. Elle s'ajustera sur les prochaines tontes réelles.")
    else tr("Estimate adjusted on ${calibration.records} real mowing${if (calibration.records > 1) "s" else ""}: ",
            "Estimation ajustée sur ${calibration.records} tonte${if (calibration.records > 1) "s" else ""} réelle${if (calibration.records > 1) "s" else ""} : ") +
        listOfNotNull(calibration.timeFactor?.let { tr("duration ×%s", "durée ×%s").format(num(it, 2)) },
            calibration.autonomyMinutes?.let { tr("runtime ${Math.round(it)} min", "autonomie ${Math.round(it)} min") },
            calibration.chargeMinutes?.let { tr("recharge ${Math.round(it)} min for 100 %", "recharge ${Math.round(it)} min pour 100 %") }).joinToString(" · ") + "."

    /** Number with [decimals] decimals, decimal comma in French and decimal point in English. */
    internal fun num(value: Double, decimals: Int) = String.format(if (I18n.french) java.util.Locale.FRANCE else java.util.Locale.ENGLISH, "%.${decimals}f", value)
}

/**
 * Accumulates durations and battery from telemetry samples. The interval since the previous sample is attributed to
 * that sample's state (charging or not); a gap of more than [MAX_GAP_MS] (app paused, link lost) counts neither in
 * duration nor in battery.
 */
class MowingTracker(start: MowingTotals = MowingTotals()) {
    private var lastAt: Long? = null
    private var lastBattery: Int? = null
    private var lastCharging = false
    var totals: MowingTotals = start
        private set

    fun sample(atMs: Long, battery: Int?, charging: Boolean): MowingTotals {
        val previous = lastAt
        if (previous != null && atMs > previous && atMs - previous <= MAX_GAP_MS) {
            val dt = (atMs - previous) / 1000.0
            val delta = if (battery != null && lastBattery != null) (battery - lastBattery!!).toDouble() else 0.0
            totals = if (lastCharging) totals.copy(chargingSeconds = totals.chargingSeconds + dt, batteryCharged = totals.batteryCharged + delta.coerceAtLeast(0.0))
            else totals.copy(activeSeconds = totals.activeSeconds + dt, batteryUsed = totals.batteryUsed + (-delta).coerceAtLeast(0.0))
        }
        if (previous == null || atMs >= previous) {
            lastAt = atMs; lastCharging = charging
            if (battery != null) lastBattery = battery
        }
        return totals
    }

    /** Resume after an interruption: the next measurement becomes the new reference. */
    fun reset() { lastAt = null; lastBattery = null }

    companion object { const val MAX_GAP_MS = 30_000L }
}

/** Task history: one JSON file for all robots, atomic write. */
class MowingHistoryStore(directory: File) {
    private val file = File(directory, "mowing-history.json")

    @Synchronized fun read(): List<MowingRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("records")
        (0 until array.length()).mapNotNull { i -> runCatching { MowingHistoryJson.decode(array.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun save(records: List<MowingRecord>) {
        val kept = records.sortedByDescending { it.startedAt }.take(MAX_RECORDS)
        val json = JSONObject().put("version", 1).put("records", JSONArray(kept.map(MowingHistoryJson::encode)))
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { tr("Failed to save the mowing history", "Échec sauvegarde de l'historique des tontes") }
    }

    companion object {
        const val MAX_RECORDS = 200

        /**
         * Reason stored by [closeStale]. Kept in French in saved histories (older records already contain it);
         * [reasonText] translates it for display.
         */
        const val STALE_REASON = "Application fermée pendant la tonte"

        /** Task still "in progress" when loading: the app was closed during the task. */
        fun closeStale(records: List<MowingRecord>): List<MowingRecord> = records.map {
            if (it.outcome == MowingOutcome.RUNNING) it.copy(outcome = MowingOutcome.INTERRUPTED,
                reason = it.reason ?: STALE_REASON, endedAt = it.endedAt ?: it.startedAt + (it.totals.totalSeconds * 1000).toLong())
            else it
        }

        /** Inserts or replaces [record] (same id), newest first. */
        fun upsert(records: List<MowingRecord>, record: MowingRecord): List<MowingRecord> =
            (listOf(record) + records.filter { it.id != record.id }).sortedByDescending { it.startedAt }.take(MAX_RECORDS)
    }
}

/** Stored interruption reason as displayed: the reason written by [MowingHistoryStore.closeStale] is translated. */
fun reasonText(reason: String): String =
    if (reason == MowingHistoryStore.STALE_REASON) tr("App closed during mowing", MowingHistoryStore.STALE_REASON) else reason

/**
 * True when a stored completion reason only repeats the status line ("Tonte terminée…", "Trajet terminé…", or the
 * English texts "Mowing finished…", "Route finished…"): reasons are stored in the language of the time.
 */
internal fun finishedReason(reason: String, withRoute: Boolean): Boolean =
    reason.startsWith("Tonte terminée") || reason.startsWith("Mowing finished") ||
        (withRoute && (reason.startsWith("Trajet terminé") || reason.startsWith("Route finished")))

object MowingHistoryJson {
    fun encode(r: MowingRecord): JSONObject = JSONObject().put("id", r.id).put("robotId", r.robotId)
        .put("robotName", r.robotName ?: JSONObject.NULL).put("startedAt", r.startedAt).put("endedAt", r.endedAt ?: JSONObject.NULL)
        .put("outcome", r.outcome.name).put("reason", r.reason ?: JSONObject.NULL).put("zoneNames", JSONArray(r.zoneNames))
        .put("areaM2", r.areaM2).put("mode", r.mode).put("heightMm", r.heightMm).put("speedMps", r.speedMps)
        .put("returnPercent", r.returnPercent).put("resumePercent", r.resumePercent)
        .put("plannedMeters", r.plannedMeters).put("cuttingMeters", r.cuttingMeters).put("doneMeters", r.doneMeters)
        .put("forecast", JSONObject().put("totalSeconds", r.forecast.totalSeconds).put("recharges", r.forecast.recharges)
            .put("travelSeconds", r.forecast.travelSeconds).put("autonomyMinutes", r.forecast.autonomyMinutes)
            .put("chargeMinutes", r.forecast.chargeMinutes).put("timeFactor", r.forecast.timeFactor)
            .put("battery", r.forecast.battery).put("calibratedFrom", r.forecast.calibratedFrom))
        .put("totals", JSONObject().put("activeSeconds", r.totals.activeSeconds).put("chargingSeconds", r.totals.chargingSeconds)
            .put("batteryUsed", r.totals.batteryUsed).put("batteryCharged", r.totals.batteryCharged))
        .put("recharges", r.recharges).put("resumes", r.resumes).put("obstacles", r.obstacles)
        .put("batteryStart", r.batteryStart ?: JSONObject.NULL).put("batteryEnd", r.batteryEnd ?: JSONObject.NULL)
        .put("excluded", r.excluded).put("kind", r.kind.name)
        .put("links", JSONArray(r.links.map { it.name })).put("gatewayLegs", r.gatewayLegs)

    fun decode(j: JSONObject): MowingRecord {
        fun optLong(key: String) = if (j.isNull(key)) null else j.getLong(key)
        fun optInt(key: String) = if (!j.has(key) || j.isNull(key)) null else j.getInt(key)
        fun optString(key: String) = if (!j.has(key) || j.isNull(key)) null else j.getString(key)
        val f = j.getJSONObject("forecast"); val t = j.getJSONObject("totals")
        val zones = j.getJSONArray("zoneNames").let { a -> (0 until a.length()).map { a.getString(it) } }
        return MowingRecord(j.getString("id"), j.getString("robotId"), optString("robotName"), j.getLong("startedAt"), optLong("endedAt"),
            MowingOutcome.valueOf(j.getString("outcome")), optString("reason"), zones, j.getDouble("areaM2"), j.getString("mode"),
            j.getInt("heightMm"), j.getDouble("speedMps"), j.getInt("returnPercent"), j.getInt("resumePercent"),
            j.getDouble("plannedMeters"), j.getDouble("cuttingMeters"), j.getDouble("doneMeters"),
            MowingForecast(f.getDouble("totalSeconds"), f.getInt("recharges"), f.getDouble("travelSeconds"), f.getDouble("autonomyMinutes"),
                f.getDouble("chargeMinutes"), f.getDouble("timeFactor"), f.getDouble("battery"), f.optInt("calibratedFrom", 0)),
            MowingTotals(t.getDouble("activeSeconds"), t.getDouble("chargingSeconds"), t.getDouble("batteryUsed"), t.getDouble("batteryCharged")),
            j.getInt("recharges"), j.getInt("resumes"), j.getInt("obstacles"), optInt("batteryStart"), optInt("batteryEnd"),
            j.optBoolean("excluded", false),
            runCatching { TaskKind.valueOf(j.optString("kind", TaskKind.MOW.name)) }.getOrDefault(TaskKind.MOW),
            j.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { TrackingLink.valueOf(a.getString(i)) }.getOrNull() }.toSet() }.orEmpty(),
            j.optInt("gatewayLegs", 0))
    }
}
