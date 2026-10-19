package fr.luba.poc.map

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor

/**
 * Signal map kept up to date whenever the robot moves (mowing, move to a point, return to base, joystick), in addition
 * to the dedicated surveys.
 *
 * Cost: at most one reading per second and every 30 cm (telemetry already received, no command to the robot); size
 * bounded by the garden area, each 1 m cell keeping only its [PER_CELL] most recent readings; screen refreshed every
 * [PUBLISH_MS] and disk every [SAVE_MS]. A new version is opened every day (or when the robot map changes), starting
 * from the readings of the previous one: the last [KEEP_VERSIONS] versions stay in the heatmap history for comparison.
 */
object SignalLiveMap {
    const val CELL_M = 1.0
    const val PER_CELL = 6
    const val MIN_MOVE_M = 0.3
    const val MIN_INTERVAL_MS = 1000L
    const val PUBLISH_MS = 10_000L
    const val SAVE_MS = 30_000L
    const val KEEP_VERSIONS = 10
    const val ID_PREFIX = "live-"

    /** Reading kept only if the robot moved since the previous one. */
    fun accepts(last: SignalSample?, sample: SignalSample): Boolean =
        last == null || sample.at < last.at ||
            (sample.at - last.at >= MIN_INTERVAL_MS && sample.point.distance(last.point) >= MIN_MOVE_M)

    private fun cell(p: Point) = floor(p.x / CELL_M).toLong() to floor(p.y / CELL_M).toLong()

    /** Adds [sample]; its cell keeps only its [perCell] most recent readings. */
    fun merge(samples: List<SignalSample>, sample: SignalSample, perCell: Int = PER_CELL): List<SignalSample> {
        require(perCell >= 1)
        val key = cell(sample.point)
        val same = samples.withIndex().filter { cell(it.value.point) == key }
        if (same.size < perCell) return samples + sample
        val dropped = same.sortedBy { it.value.at }.take(same.size - perCell + 1).map { it.index }.toSet()
        return samples.filterIndexed { index, _ -> index !in dropped } + sample
    }

    /**
     * Current version for [robotId] on [map]: [current] if it is already today's version of this map, otherwise a new
     * version ([id]) starting from the readings of [current] when they come from the same robot.
     */
    fun version(current: SignalSurvey, robotId: String, map: RobotMap, now: Long, id: String,
                zone: TimeZone = TimeZone.getDefault()): SignalSurvey {
        val key = SignalCoverage.mapKey(map)
        val sameRobot = current.robotId == robotId && !current.simulated
        if (sameRobot && current.live && current.mapKey == key && sameDay(current.startedAt, now, zone)) return current
        return SignalSurvey(robotId, key, map.shapes.filter { it.kind == ShapeKind.ZONE }.map { it.id },
            samples = if (sameRobot) current.samples else emptyList(), sessionId = id, startedAt = now,
            mode = CoverageMode.TOUR, mapSnapshot = map, live = true)
    }

    fun newId(now: Long, random: String): String = ID_PREFIX + now + "-" + random.filter { it.isLetterOrDigit() }.take(8)

    /** Creation time carried by a version identifier ([newId]), null for a dedicated survey. */
    fun startedAt(id: String): Long? = id.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.substringBefore('-')?.toLongOrNull()

    /** Versions to delete: all but the [keep] most recent. Dedicated surveys are never deleted. */
    fun expired(ids: List<String>, keep: Int = KEEP_VERSIONS): List<String> =
        ids.mapNotNull { id -> startedAt(id)?.let { id to it } }.sortedByDescending { it.second }.drop(keep).map { it.first }

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
        fun day(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
        return day(a) == day(b)
    }
}
