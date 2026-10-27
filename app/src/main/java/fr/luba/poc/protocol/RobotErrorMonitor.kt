package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.LubaMsg

data class RobotError(val code: Int, val source: String, val timestamp: Long? = null)

/** Instant errors and upstream type 2 history, code/timestamp pairs at indices 3/4…
 * The first history is the baseline: an old collision never confirms the bumper.
 * Index 2 counts the history entries: an entry whose timestamp alone is rewritten, counter unchanged, is not a new
 * error (2026-10-14 at 14:28: contact -1014 published with the internal clock 137498, then 5 s later with the time
 * 1791980897, counter 868 both times; taken for a second contact, it stopped the mowing during the bypass back-off).
 */
class RobotErrorMonitor {
    private var baseline = false
    private var activeDirectCode = 0
    private var historyCount: Long? = null
    private val seen = mutableSetOf<Pair<Int,Long>>()
    fun reset() { baseline = false; activeDirectCode = 0; historyCount = null; seen.clear() }
    fun accept(message: LubaMsg): List<RobotError> {
        if (!message.hasSys()) return emptyList()
        val sys = message.sys
        if (sys.hasToappErrCode()) {
            val code=sys.toappErrCode.errorCode
            val repeated=code==activeDirectCode
            activeDirectCode=code
            return if (code==0 || repeated) emptyList() else listOf(RobotError(code,"toapp_err_code"))
        }
        if (sys.hasDebugErrocodeReport()) {
            val report=sys.debugErrocodeReport
            if(report.code==0 || !seen.add(report.code to report.genTime)) return emptyList()
            return listOf(RobotError(report.code,"debug_errocode_report",report.genTime))
        }
        if (!sys.hasSystemUpdateBuf()) return emptyList()
        val data = sys.systemUpdateBuf.updateBufDataList
        if (data.size < 3 || data[0] != 2L || (data.size-3)%2 != 0) return emptyList()
        val pairs = (3 until data.size-1 step 2).map { data[it].toInt() to data[it+1] }.filter { it.first != 0 }
        val unseen = pairs.filter { it !in seen }
        val count = data[2]; val previous = historyCount
        // History from newest to oldest: counter increased by n, at most the first n unknown entries.
        val fresh = when {
            !baseline -> emptyList()
            previous != null && count == previous -> emptyList()
            previous != null && count > previous -> unseen.take((count - previous).coerceAtMost(unseen.size.toLong()).toInt())
            else -> unseen
        }
        baseline = true; historyCount = count; seen.addAll(pairs)
        return fresh.map { (code,time) -> RobotError(code,"systemUpdateBuf",time) }
    }
}

/** 1207 is a continuous-trigger fault: it never confirms a normal contact. */
object BumperEvents {
    fun confirmsContact(error: RobotError): Boolean = error.code in setOf(1014,-1014)
    fun latchedFault(error: RobotError): Boolean = error.code in setOf(1207,-1207)

    /** Oldest contact (s) still placed on the map at the robot's current position ([placedLate]). */
    const val MAP_FRESH_S = 15L

    /**
     * Contact dated by the robot (Unix seconds) more than [MAP_FRESH_S] before [nowMs]: the robot has moved since, its
     * current position is not where it hit. 2026-10-25: contacts of the obstacle learning trials on the swing poles
     * (14:53:13, 14:58:01, 15:00:25) read 20 to 63 s later while driving by hand, logged about 1 m away, then taken
     * for obstacles by every mowing computation. Robot clock (small values, not a date) or no time: not late.
     */
    fun placedLate(error: RobotError, nowMs: Long): Boolean {
        val at = error.timestamp ?: return false
        if (at < 1_000_000_000L) return false
        return nowMs / 1000 - at > MAP_FRESH_S
    }
}
