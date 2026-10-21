package fr.luba.poc.difficulty

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Difficulty met on the ground during an autonomous move of the robot. [weight]: weight in the heatmap (1 = recent
 * contact or blockage at the very point).
 */
enum class DifficultyKind(val code: String, private val labelEn: String, private val labelFr: String, val weight: Double) {
    CONTACT("contact", "Bumper contact", "Contact pare-chocs", 1.0),
    BLOCKED("blocked", "Blockage", "Blocage", 1.0),
    SLIP("slip", "Wheel slip", "Patinage", 0.6),
    LIFT("lift", "Lift", "Soulèvement", 0.8),
    NO_PROGRESS("no_progress", "No progress", "Absence de progression", 0.7),
    DEVIATION("deviation", "Route deviation", "Écart au trajet", 0.4);

    /** Display label in the current language. */
    val label: String get() = tr(labelEn, labelFr)

    companion object {
        fun of(code: String): DifficultyKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Geolocated event (map frame, metres). For a contact, [obstacleX]/[obstacleY] give the estimated centre of the
 * object hit (in front of the bumper) and [contactAtMs] the contact time, which links the event to the photo taken
 * after the back-off. [side] / [inner]: side of the chosen bypass.
 * [dismissed]: dismissed by the user (object removed, false contact), it no longer counts anywhere.
 */
data class DifficultyEvent(
    val id: String,
    val atMs: Long,
    val kind: DifficultyKind,
    val x: Double,
    val y: Double,
    val robotId: String = "",
    val code: Int? = null,
    val note: String = "",
    val headingDeg: Double? = null,
    val obstacleX: Double? = null,
    val obstacleY: Double? = null,
    val contactAtMs: Long? = null,
    val source: String = "",
    val side: Int? = null,
    val inner: Boolean? = null,
    val dismissed: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { tr("Empty difficulty id", "Identifiant de difficulté vide") }
        require(x.isFinite() && y.isFinite()) { tr("Invalid difficulty position", "Position de difficulté invalide") }
        require((obstacleX == null) == (obstacleY == null)) { tr("Incomplete obstacle center", "Centre d'obstacle incomplet") }
        require(obstacleX == null || (obstacleX.isFinite() && obstacleY!!.isFinite())) { tr("Invalid obstacle center", "Centre d'obstacle invalide") }
        require(note.length <= 300) { tr("Difficulty note too long", "Note de difficulté trop longue") }
    }
    /** Heatmap point: the object hit for a contact, otherwise the robot position. */
    val point: Point get() = Point(obstacleX ?: x, obstacleY ?: y)
}

/** Difficulty heatmap cell (1 m), centred on [point]. */
data class DifficultyCell(val point: Point, val score: Double, val count: Int, val dominant: DifficultyKind)

/** Classification of stop causes and robot error codes into ground difficulties. */
object DifficultyClassifier {
    /** Mammotion wheel-slip codes (leaving the station, reversing, pivot). */
    val SLIP_CODES = setOf(1218, 11132, 11134)
    /** Mammotion codes for robot blocked, stuck, passage blocked, wheel stuck, wheel motor blocked. */
    val STUCK_CODES = setOf(1008, 1028, 1201, 1214, 1412, 1421, 11112, 11113, 11122, 2000701, 6001501, 6001601)
    /** Lift or tilt (same list as the lift detection). */
    val LIFT_CODES = setOf(1004, 1030, 2502, 2801, 11131, 11133)

    fun fromErrorCode(code: Int): DifficultyKind? = when (abs(code)) {
        in SLIP_CODES -> DifficultyKind.SLIP
        in STUCK_CODES -> DifficultyKind.BLOCKED
        in LIFT_CODES -> DifficultyKind.LIFT
        else -> null
    }

    /**
     * Stop reason of a move (text of the tracking checks) → difficulty, or null (stop requested, link, battery,
     * software check unrelated to the ground). Matches the French texts and their English translations.
     */
    fun fromReason(reason: String): DifficultyKind? {
        val text = reason.lowercase()
        return when {
            "patin" in text || "slip" in text -> DifficultyKind.SLIP
            "roues commandées sans progression" in text || "blocage" in text || "bloqué" in text || "coincé" in text ||
                "wheels commanded without progress" in text || "blockage" in text || "blocked" in text || "stuck" in text -> DifficultyKind.BLOCKED
            "soulev" in text || "capteur de sécurité changé" in text || "inclin" in text ||
                "lift" in text || "safety sensor changed" in text || "tilt" in text -> DifficultyKind.LIFT
            "progression" in text || "plage trop longue" in text || "no progress" in text || "without progress" in text || "too long" in text -> DifficultyKind.NO_PROGRESS
            "écart au trajet" in text || "dérive" in text || "route deviation" in text || "deviation from the route" in text ||
                "drift" in text -> DifficultyKind.DEVIATION
            else -> null
        }
    }
}

/**
 * Difficulty heatmap: each non-dismissed event contributes around its point (Gaussian kernel, [SIGMA_M]) with its
 * weight, halved every [HALF_LIFE_DAYS] days. Used for display (1 m cells), for the cost of bypasses and for the
 * areas avoided by transits with blades stopped.
 */
class DifficultyField(events: List<DifficultyEvent>, private val now: Long) {
    companion object {
        const val CELL_M = 1.0
        const val SIGMA_M = 0.6
        const val REACH_M = 1.8
        const val HALF_LIFE_DAYS = 60.0
        /** Older events are ignored. */
        const val MAX_AGE_DAYS = 365.0
        /** Score of a cell to avoid in transit: ≈ a recent contact or blockage very close. */
        const val HOT_SCORE = 0.8
        /** Cost of one metre of bypass per score point (in equivalent metres). */
        const val COST_PER_SCORE = 2.0
        private const val DAY_MS = 86_400_000.0
        private const val BUCKET_M = 2.0

        fun decay(ageMs: Long): Double = 0.5.pow(max(0L, ageMs) / DAY_MS / HALF_LIFE_DAYS)
    }

    private class Weighted(val point: Point, val weight: Double, val kind: DifficultyKind)

    private val weighted: List<Weighted> = events.filter { !it.dismissed && now - it.atMs <= MAX_AGE_DAYS * DAY_MS }
        .map { Weighted(it.point, it.kind.weight * decay(now - it.atMs), it.kind) }
        .filter { it.weight > 1e-3 }
    private val buckets: Map<Pair<Int, Int>, List<Weighted>> =
        weighted.groupBy { floor(it.point.x / BUCKET_M).toInt() to floor(it.point.y / BUCKET_M).toInt() }

    val isEmpty: Boolean get() = weighted.isEmpty()

    private fun near(p: Point): Sequence<Weighted> {
        val bx = floor(p.x / BUCKET_M).toInt(); val by = floor(p.y / BUCKET_M).toInt()
        val reach = kotlin.math.ceil(REACH_M / BUCKET_M).toInt()
        return sequence {
            for (i in bx - reach..bx + reach) for (j in by - reach..by + reach) buckets[i to j]?.let { yieldAll(it) }
        }
    }

    /** Score at point [p] (0 far from any difficulty, ≈ 1 on a recent contact). */
    fun score(p: Point): Double = near(p).sumOf { w ->
        val d = hypot(w.point.x - p.x, w.point.y - p.y)
        if (d > REACH_M) 0.0 else w.weight * exp(-d * d / (2 * SIGMA_M * SIGMA_M))
    }

    /** Cost added to each metre travelled at [p] (equivalent metres). */
    fun costPerMeter(p: Point): Double = COST_PER_SCORE * score(p)

    /** 1 m cells with a significant score, for the map. */
    fun cells(minScore: Double = 0.05): List<DifficultyCell> {
        if (weighted.isEmpty()) return emptyList()
        val keys = HashSet<Pair<Int, Int>>()
        val spread = kotlin.math.ceil(REACH_M / CELL_M).toInt()
        weighted.forEach { w ->
            val cx = floor(w.point.x / CELL_M).toInt(); val cy = floor(w.point.y / CELL_M).toInt()
            for (i in cx - spread..cx + spread) for (j in cy - spread..cy + spread) keys += i to j
        }
        val inside = weighted.groupBy { floor(it.point.x / CELL_M).toInt() to floor(it.point.y / CELL_M).toInt() }
        return keys.mapNotNull { (i, j) ->
            val center = Point((i + 0.5) * CELL_M, (j + 0.5) * CELL_M)
            // Peak inside the cell: an event on a corner counts fully for the cell that contains it.
            val score = max(score(center), inside[i to j]?.maxOfOrNull { score(it.point) } ?: 0.0)
            if (score < minScore) return@mapNotNull null
            val close = near(center).filter { hypot(it.point.x - center.x, it.point.y - center.y) <= CELL_M }.toList()
            val dominant = close.groupBy { it.kind }.maxByOrNull { (_, list) -> list.sumOf { it.weight } }?.key
                ?: near(center).minByOrNull { hypot(it.point.x - center.x, it.point.y - center.y) }!!.kind
            DifficultyCell(center, score, close.size, dominant)
        }.sortedWith(compareBy({ it.point.y }, { it.point.x }))
    }

    /**
     * Areas avoided by transits with blades stopped: cells scoring ≥ [threshold], as [CELL_M] squares (outlines in
     * the map frame). Empty without any marked difficulty.
     */
    fun hotAreas(threshold: Double = HOT_SCORE): List<List<Point>> = DifficultyField.hotAreas(cells(threshold), threshold)
}

/** Squares of the cells scoring ≥ [threshold] (cells already computed). */
fun DifficultyField.Companion.hotAreas(cells: List<DifficultyCell>, threshold: Double = DifficultyField.HOT_SCORE): List<List<Point>> =
    cells.filter { it.score >= threshold }.map { c ->
        val h = DifficultyField.CELL_M / 2
        listOf(Point(c.point.x - h, c.point.y - h), Point(c.point.x + h, c.point.y - h),
            Point(c.point.x + h, c.point.y + h), Point(c.point.x - h, c.point.y + h))
    }

/**
 * Presumed obstacles: contacts repeated at the same place during distinct moves, or a contact less than [RECENT_MS]
 * old (the object hit is probably still there). They count as obstacles when the next routes are prepared (if the
 * option is on), without being saved: the user can save them as obstacles or dismiss the contacts.
 *
 * Recent contact (10-14): after a contact followed by a stop, the resume at 14:28:35 and the mowing restarted at
 * 14:35 headed straight back to the object hit, forgotten with the interrupted mission (two new contacts at the
 * same place).
 */
object PresumedObstacles {
    /** Contacts grouped within this distance (estimated centres). */
    const val CLUSTER_M = 0.75
    /** Distinct contacts required (at least [DISTINCT_MS] apart). */
    const val MIN_CONTACTS = 2
    const val DISTINCT_MS = 10 * 60_000L
    const val MAX_AGE_DAYS = 180.0
    /** A single contact is enough while it is less than 4 h old. */
    const val RECENT_MS = 4 * 3_600_000L
    const val ID_PREFIX = "presume-"

    data class Cluster(val center: Point, val contacts: List<DifficultyEvent>, val radiusM: Double)

    fun clusters(events: List<DifficultyEvent>, now: Long): List<Cluster> {
        val contacts = events.filter {
            it.kind == DifficultyKind.CONTACT && !it.dismissed && it.obstacleX != null &&
                now - it.atMs <= MAX_AGE_DAYS * 86_400_000.0
        }.sortedBy { it.atMs }
        val groups = mutableListOf<MutableList<DifficultyEvent>>()
        for (c in contacts) {
            val group = groups.firstOrNull { g -> centroid(g).distance(c.point) <= CLUSTER_M }
            if (group != null) group += c else groups += mutableListOf(c)
        }
        return groups.mapNotNull { g ->
            val distinct = g.map { it.atMs }.fold(mutableListOf<Long>()) { acc, t ->
                if (acc.isEmpty() || t - acc.last() >= DISTINCT_MS) acc += t; acc
            }.size
            if (distinct < MIN_CONTACTS && now - g.maxOf { it.atMs } > RECENT_MS) return@mapNotNull null
            val center = centroid(g)
            val spread = g.maxOf { it.point.distance(center) }
            Cluster(center, g, min(1.0, max(fr.luba.poc.simulation.ObstacleBypass.CONTACT_RADIUS_M, spread + 0.1)))
        }
    }

    private fun centroid(g: List<DifficultyEvent>) = Point(g.sumOf { it.point.x } / g.size, g.sumOf { it.point.y } / g.size)

    /** A saved obstacle hides the presumed obstacle of [cluster] (same place). */
    fun covers(obstacle: LocalObstacle, cluster: Cluster): Boolean =
        Point(obstacle.x, obstacle.y).distance(cluster.center) <= obstacle.exclusionRadiusM + 0.3

    /** Identifier of the presumed obstacle made by [cluster]. */
    fun id(cluster: Cluster): String = ID_PREFIX + cluster.contacts.minBy { it.atMs }.id

    /** Contacts making the presumed obstacle [id] (empty if it no longer exists). */
    fun contactsOf(events: List<DifficultyEvent>, id: String, now: Long): List<DifficultyEvent> =
        clusters(events, now).firstOrNull { id(it) == id }?.contacts.orEmpty()

    /**
     * Contacts hidden by the saved obstacle [obstacle] and by none of [others]: deleting it without dismissing them
     * brought the same obstacle back as a presumed obstacle, at the same place, without any way to delete it.
     */
    fun hiddenContacts(events: List<DifficultyEvent>, obstacle: LocalObstacle, others: List<LocalObstacle>, now: Long): List<DifficultyEvent> =
        clusters(events, now).filter { cluster -> covers(obstacle, cluster) && others.none { covers(it, cluster) } }.flatMap { it.contacts }

    /** Presumed obstacles outside the already saved obstacles ([known]). */
    fun obstacles(events: List<DifficultyEvent>, known: List<LocalObstacle>, now: Long): List<LocalObstacle> =
        clusters(events, now).filter { cluster -> known.none { o -> covers(o, cluster) } }.map { cluster ->
            val first = cluster.contacts.minOf { it.atMs }; val last = cluster.contacts.maxOf { it.atMs }
            LocalObstacle(id(cluster), ObstacleKind.OTHER,
                cluster.center.x, cluster.center.y, cluster.radiusM, fr.luba.poc.simulation.ObstacleBypass.CONTACT_MARGIN_M,
                createdAtMs = first, updatedAtMs = last, label = if (cluster.contacts.size == 1) tr("Recently hit obstacle", "Obstacle touché récemment")
                    else tr("Presumed obstacle (${cluster.contacts.size} contacts)", "Obstacle présumé (${cluster.contacts.size} contacts)"))
        }
}

/** Bounded log: the most recent ones, at most [MAX_EVENTS]. */
object DifficultyLog {
    const val MAX_EVENTS = 2000
    /** Same difficulty within [DUPLICATE_M] and [DUPLICATE_MS]: a single event (linked stop and error). */
    const val DUPLICATE_M = 2.0
    const val DUPLICATE_MS = 30_000L

    fun duplicate(events: List<DifficultyEvent>, candidate: DifficultyEvent): Boolean = events.any {
        it.kind == candidate.kind && abs(it.atMs - candidate.atMs) <= DUPLICATE_MS &&
            it.point.distance(candidate.point) <= DUPLICATE_M
    }

    fun add(events: List<DifficultyEvent>, event: DifficultyEvent): List<DifficultyEvent> =
        if (duplicate(events, event)) events else (events + event).sortedBy { it.atMs }.takeLast(MAX_EVENTS)

    /**
     * Same contact: within [DUPLICATE_M], logged within [DUPLICATE_MS] or with the same contact time (the bypass
     * refines it after the back-off and the wait for the photo, up to 25 s, sometimes more than 30 s later).
     */
    fun sameContact(a: DifficultyEvent, b: DifficultyEvent): Boolean =
        a.kind == DifficultyKind.CONTACT && b.kind == DifficultyKind.CONTACT && a.point.distance(b.point) <= DUPLICATE_M &&
            (abs(a.atMs - b.atMs) <= DUPLICATE_MS ||
                (a.contactAtMs != null && b.contactAtMs != null && abs(a.contactAtMs - b.contactAtMs) <= DUPLICATE_MS))

    /**
     * Contact already logged when the bumper code arrived (same contact within [DUPLICATE_M] and [DUPLICATE_MS]):
     * replaced by its version refined after the stop (stationary position, bypass side, note), under the same id and
     * the same contact time (record and photo unchanged). Other kind or new contact: [add].
     */
    fun refine(events: List<DifficultyEvent>, event: DifficultyEvent): List<DifficultyEvent> {
        if (event.kind != DifficultyKind.CONTACT) return add(events, event)
        val i = events.indexOfFirst { sameContact(it, event) }
        if (i < 0) return add(events, event)
        val old = events[i]
        val merged = event.copy(id = old.id, atMs = old.atMs, contactAtMs = old.contactAtMs ?: event.contactAtMs, code = event.code ?: old.code,
            headingDeg = event.headingDeg ?: old.headingDeg, obstacleX = event.obstacleX ?: old.obstacleX, obstacleY = event.obstacleY ?: old.obstacleY,
            side = event.side ?: old.side, inner = event.inner ?: old.inner, dismissed = old.dismissed)
        return events.toMutableList().also { it[i] = merged }
    }
}
