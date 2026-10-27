package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import java.util.Locale

/**
 * Detects that the gateway phone is no longer on the robot's back (fallen on the lawn), from the Bluetooth signal it
 * measures to the robot.
 *
 * Mounted, the phone sits a few centimeters from the robot's antenna: its RSSI is strong and steady (−24 dBm measured
 * on 2026-10-11). Fallen, it stays where it fell while the robot drives on: the RSSI drops as the robot moves away
 * (about 20 dB at 2 m). The detector learns the mounted level (median of the readings consistent with it), remembers
 * the robot position at the last reading at that level (the drop point, within one reading period), and concludes when
 * [confirmReadings] consecutive readings are [dropDb] below the mounted level while the robot is at least [minDistanceM]
 * from that point. Both conditions are needed: a weak reading with the robot still next to the drop point, or a robot
 * moving with a steady signal, are not a loss.
 *
 * The mounted level only counts once confirmed: [learnReadings] readings within [MOUNTED_MARGIN_DB] of their median
 * while the robot drove at least [LEARN_DISTANCE_M], at [MOUNTED_MIN_DBM] or stronger. A phone left on the ground on
 * purpose (gateway used as a fixed relay) sees its signal change as the robot moves, or is too far to be that strong:
 * its level is never confirmed and nothing is ever concluded.
 *
 * The drop point must be current: the first weak reading of the streak comes at most [ANCHOR_FRESH_MS] after the last
 * mounted one, or with the robot still near it. Otherwise (readings stopped between two routes while the robot moved,
 * then a weaker level from the start of the next route) nothing tells where the phone would have fallen: the level is
 * learned again from these readings instead of stopping the robot. 2026-10-25 at 15:54:58: −19 dBm until the end of a
 * route, −51 dBm from the next one, "fallen near" a point 1.5 m back left a minute earlier, while the same phone then
 * drove the robot 12 m with a steady signal. After a verdict the drop point is known: the next route concludes again
 * from it ([rearm]).
 *
 * Bluetooth link closed during a route ([linkLost]): looser verdict ("probably") if the last readings were already
 * falling while the robot moved away; otherwise a plain Bluetooth loss.
 *
 * Positions are the robot's fresh positions (map frame, meters); null when unknown or stale. Clock: monotonic ms.
 */
class GatewayDetachment(
    private val dropDb: Int = DROP_DB,
    private val minDistanceM: Double = MIN_DISTANCE_M,
    private val confirmReadings: Int = CONFIRM_READINGS,
    private val learnReadings: Int = LEARN_READINGS,
) {
    /** Conclusion: [point] where the phone most likely lies (robot position at the last mounted-level reading). */
    data class Verdict(
        val point: Point, val at: Long, val rssiDbm: Int?, val mountedDbm: Int, val robot: Point?, val distanceM: Double?,
        /** True when concluded from a Bluetooth link loss rather than from confirmed weak readings. */
        val probable: Boolean,
    ) {
        /** Stop reason and alert text (phone language). */
        val text: String get() {
            val where = "x=%.1f m, y=%.1f m".format(Locale.US, point.x, point.y)
            val signal = rssiDbm?.let { tr("Bluetooth $it dBm instead of $mountedDbm dBm", "Bluetooth $it dBm au lieu de $mountedDbm dBm") }
                ?: tr("Bluetooth link lost (usually $mountedDbm dBm)", "liaison Bluetooth perdue (habituellement $mountedDbm dBm)")
            val away = distanceM?.let { tr(", robot %.1f m away", ", robot à %.1f m").format(Locale.US, it) }.orEmpty()
            return if (probable) tr("Gateway probably fell off the robot near $where ($signal$away)", "Passerelle probablement tombée du robot vers $where ($signal$away)")
            else tr("Gateway fell off the robot near $where ($signal$away)", "Passerelle tombée du robot vers $where ($signal$away)")
        }
        fun fields(): Map<String, Any?> = mapOf("x" to point.x, "y" to point.y, "rssiDbm" to rssiDbm, "mountedDbm" to mountedDbm,
            "robotX" to robot?.x, "robotY" to robot?.y, "distanceM" to distanceM, "probable" to probable)

        companion object {
            /** Verdict rebuilt by the pilot from the event [fields] (texts then in the pilot's language), or null if incomplete. */
            fun fromFields(fields: Map<String, Any?>, at: Long): Verdict? {
                fun num(key: String) = (fields[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
                val x = num("x") ?: return null; val y = num("y") ?: return null
                val mounted = num("mountedDbm")?.toInt() ?: return null
                val rx = num("robotX"); val ry = num("robotY")
                return Verdict(Point(x, y), at, num("rssiDbm")?.toInt(), mounted, if (rx != null && ry != null) Point(rx, ry) else null,
                    num("distanceM"), fields["probable"] == true)
            }
        }
    }

    private val mounted = ArrayDeque<Int>()
    /** First position of the current learning; the level is confirmed once the robot has driven [LEARN_DISTANCE_M] from it. */
    private var learnOrigin: Point? = null
    private var confirmed = false
    private var anchor: Point? = null
    private var anchorAt = 0L
    private var weak = 0
    /** A verdict was reached and no mounted-level reading came since: the drop point is known, never stale. */
    private var dropKnown = false
    private var lastRssi: Int? = null
    var verdict: Verdict? = null; private set

    private fun median(): Int? = if (mounted.isEmpty()) null else mounted.sorted()[mounted.size / 2]

    /** Confirmed mounted level (median of the readings consistent with it), or null while it is being learned. */
    val mountedDbm: Int? get() = if (confirmed) median() else null

    /** Valid RSSI reading ([rssiDbm] in −126..−1) with the robot's fresh position, or null. Returns the verdict once reached. */
    fun reading(at: Long, rssiDbm: Int, robot: Point?): Verdict? {
        verdict?.let { return it }
        if (rssiDbm !in -126..-1) return null
        lastRssi = rssiDbm
        val level = median()
        // While learning, a reading too far above the median also means a signal that depends on the robot's position.
        val tooStrong = !confirmed && level != null && rssiDbm > level + MOUNTED_MARGIN_DB
        if (level == null || (rssiDbm >= level - MOUNTED_MARGIN_DB && !tooStrong)) {
            // Consistent with the phone on the robot: learn, and move the drop point candidate to here.
            mounted.addLast(rssiDbm); while (mounted.size > WINDOW) mounted.removeFirst()
            weak = 0; dropKnown = false
            if (robot != null) {
                anchor = robot; anchorAt = at
                val origin = learnOrigin ?: robot.also { learnOrigin = it }
                if (!confirmed && mounted.size >= learnReadings && robot.distance(origin) >= LEARN_DISTANCE_M &&
                    (median() ?: Int.MIN_VALUE) >= MOUNTED_MIN_DBM) confirmed = true
            }
            return null
        }
        if (!confirmed) {
            // Signal that depends on where the robot is before any confirmation: not a phone riding on it. Learn again.
            mounted.clear(); mounted.addLast(rssiDbm); learnOrigin = robot; anchor = robot; anchorAt = at; weak = 0
            return null
        }
        if (rssiDbm > level - dropDb) { weak = 0; return null }
        val point = anchor ?: return null
        if (weak == 0 && !dropKnown && at - anchorAt > ANCHOR_FRESH_MS && (robot == null || robot.distance(point) >= minDistanceM / 2)) {
            // Weaker level found at the start of a streak, far in time and space from the last mounted reading: drop point
            // unknown, the level is learned again (a phone that shifted on the robot settles there; a fallen one never does).
            mounted.clear(); mounted.addLast(rssiDbm); confirmed = false; learnOrigin = robot; anchor = robot; anchorAt = at
            return null
        }
        weak++
        val distance = robot?.distance(point) ?: return null
        if (weak < confirmReadings || distance < minDistanceM) return null
        return Verdict(point, anchorAt, rssiDbm, level, robot, distance, probable = false).also { verdict = it; dropKnown = true }
    }

    /**
     * Robot Bluetooth link lost during a route: verdict if the last reading was already well below the mounted level
     * ([SUSPECT_DB]) and the robot had moved away from the drop point candidate; otherwise null (plain link loss).
     */
    fun linkLost(robot: Point?): Verdict? {
        verdict?.let { return it }
        val level = mountedDbm ?: return null
        val last = lastRssi ?: return null
        val point = anchor ?: return null
        val distance = robot?.distance(point)
        if (last > level - SUSPECT_DB || distance == null || distance < MIN_DISTANCE_M / 2) return null
        return Verdict(point, anchorAt, null, level, robot, distance, probable = true).also { verdict = it; dropKnown = true }
    }

    /** Other robot, or gateway restarted: everything is learned again. */
    fun reset() { mounted.clear(); learnOrigin = null; confirmed = false; anchor = null; anchorAt = 0; weak = 0; lastRssi = null; verdict = null; dropKnown = false }

    /**
     * New route after a verdict: concluded again only if the readings stay weak away from the same drop point (phone
     * still on the ground); a phone put back gives mounted-level readings that move the drop point candidate.
     */
    fun rearm() { verdict = null; weak = 0 }

    companion object {
        /** Drop below the mounted level that counts as a weak reading. About 20 dB at 2 m in free field. */
        const val DROP_DB = 15
        /** A reading within this margin of the mounted level is "mounted" (Bluetooth noise of a few dB). */
        const val MOUNTED_MARGIN_DB = 6
        /** Weakest mounted level (−24 dBm measured with the Redmi on the robot on 2026-10-11). */
        const val MOUNTED_MIN_DBM = -50
        /** Drop below the mounted level of the last reading before a link loss for a "probable" verdict. */
        const val SUSPECT_DB = 10
        const val MIN_DISTANCE_M = 1.5
        /** Longest gap between the last mounted reading and the first weak one for the drop point to be trusted. */
        const val ANCHOR_FRESH_MS = 3000L
        /** Robot displacement with a steady signal needed to confirm the mounted level. */
        const val LEARN_DISTANCE_M = 3.0
        const val CONFIRM_READINGS = 3
        const val LEARN_READINGS = 5
        private const val WINDOW = 20
        /** Reading period of the gateway during a route tracking. */
        const val READ_PERIOD_MS = 1000L
        /** Route tracking event sent to the pilot with the [Verdict.fields]. */
        const val EVENT = "gateway_detached"
    }
}
