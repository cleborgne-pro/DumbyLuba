package fr.luba.poc.simulation

import fr.luba.poc.map.CoverageMode
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import fr.luba.poc.map.RobotMap
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Progress of a local mowing, saved on disk during BLE tracking to resume after a STOP, a link loss, the app being
 * closed or the phone restarting.
 *
 * The plan depends on the robot's start position: the preview itself ([preview]) and the fingerprint of the prepared
 * mowing edges ([planFingerprint]) are therefore kept. On resume, the route is prepared again from this preview on the
 * current map, base, accesses and obstacles; any fingerprint difference refuses the resume rather than mowing another
 * plan.
 *
 * [edge] = index in [MowingSimulation.edges] of the current edge; [point] = position projected onto that edge (null
 * until the first mowing edge is reached).
 *
 * [avoidance] = presumed obstacles and difficult areas counted when the plan was prepared. They change by themselves
 * (each contact creates a presumed obstacle for 4 h): the resume prepares again with those of the start, and the
 * control bypasses the presumed obstacles that appeared since. Null for a save older than 0.40.2.
 *
 * [base] = base of the start (station, docking axis, approach), reused by the resume as long as the current station
 * only differs from it by observation drift: on docking again, the station observed while charging (RTK noise,
 * docking axis off by a few degrees) replaced the base, the re-prepared route changed and the resume was refused.
 * Null: save older than 0.64.2, re-preparation on the current base.
 *
 * [skippedMeters] = cutting metres skipped by "Resume further on", accumulated from resume to resume: they are counted
 * in [doneMeters] (position in the plan) but were not mowed.
 */
data class MissionCheckpoint(
    val robotId: String, val mapKey: String, val planFingerprint: String, val edgeCount: Int,
    val preview: Preview, val cutting: Boolean, val heightMm: Int, val returnPercent: Int, val resumePercent: Int,
    val edge: Int = 0, val point: Point? = null, val doneMeters: Double = 0.0, val totalMeters: Double,
    val startedAt: Long, val updatedAt: Long = startedAt, val rechargeCycles: Int = 0, val resumes: Int = 0,
    val interruption: String? = null,
    val avoidance: MissionAvoidance? = null,
    val skippedMeters: Double = 0.0,
    /** Continuous-blade zones of the preparation (painted zone of a trial mowing); null: those of the preview. */
    val bladeZones: List<List<Point>>? = null,
    /** Base used to prepare the start plan; null: older save. */
    val base: MapBase? = null,
) {
    init {
        require(edge in 0..edgeCount && heightMm in 25..70 && totalMeters >= 0 && doneMeters >= 0 && skippedMeters >= 0)
        require(point == null || (point.x.isFinite() && point.y.isFinite()))
        require(bladeZones == null || bladeZones.all { zone -> zone.all { it.x.isFinite() && it.y.isFinite() } })
    }
    val progress: Double get() = if (totalMeters <= 0) 0.0 else (doneMeters / totalMeters).coerceIn(0.0, 1.0)
    /**
     * Re-preparation base: the start one if [current] is the same station, moved by at most
     * [MissionProgress.BASE_DRIFT_M] with a docking axis off by at most [MissionProgress.BASE_DRIFT_DEG]; otherwise
     * [current] (station moved: the plan changes and the fingerprint refuses the resume).
     */
    fun planningBase(current: MapBase): MapBase = base?.takeIf { MissionProgress.sameStation(it, current) } ?: current
    /** Saved task, for the resume panel and the history. */
    val task: MissionTask get() = MissionTask.of(preview, cutting, bladeZones)
}

/**
 * Task tracked by the robot whose progress is saved: any coverage task launched from the map or the supervised tests
 * (2026-10-16: only mowing with blades running was resumed after an app restart).
 */
enum class MissionTask(private val titleEn: String, private val titleFr: String, private val doneEn: String, private val doneFr: String) {
    MOW("Mowing interrupted", "Tonte interrompue", "mowed", "tondus"),
    FOLLOW("Route with blades stopped interrupted", "Parcours lames arrêtées interrompu", "travelled", "parcourus"),
    SURVEY("Signal survey interrupted", "Relevé des signaux interrompu", "surveyed", "relevés"),
    BORDER("Border following interrupted", "Suivi de bordure interrompu", "travelled", "parcourus"),
    TRIAL("Trial mowing interrupted", "Tonte d'essai interrompue", "travelled", "parcourus");

    /** Display title in the current language. */
    val title: String get() = tr(titleEn, titleFr)
    /** Display label of the progress ("… % mowed"). */
    val doneLabel: String get() = tr(doneEn, doneFr)

    companion object {
        fun of(preview: Preview, cutting: Boolean, bladeZones: List<List<Point>>?): MissionTask = when {
            preview.mode.isSurvey -> SURVEY
            preview.plannerLabel == fr.luba.poc.map.BorderFollow.PLANNER_LABEL -> BORDER
            bladeZones != null -> TRIAL
            cutting -> MOW
            else -> FOLLOW
        }
    }
}

/** Presumed obstacles and areas avoided by transits, counted when a mowing plan is prepared. */
data class MissionAvoidance(val presumed: List<LocalObstacle>, val avoidAreas: List<List<Point>>) {
    /** Preparation obstacles: current saved ones ([registered]) and presumed ones of the start. */
    fun planningObstacles(registered: List<LocalObstacle>): List<LocalObstacle> = registered + presumed

    companion object {
        /** Context of a mowing launch: that of [difficulties], settings included. */
        fun of(difficulties: DifficultyUi) = MissionAvoidance(
            if (difficulties.avoidPresumed) difficulties.presumed else emptyList(), difficulties.transitAvoidance)

        /**
         * Re-preparation context of a resume: the saved one; otherwise (older save) the one recomputed at the start
         * date, without the difficulties that occurred since.
         *
         * Test of 2026-10-14 at 17:31: the mowing stops just after a bumper contact; this contact becomes a presumed
         * obstacle lying on the interrupted stripe, the re-preparation counted it and failed ("Trajet hors de la surface
         * sûre", stripe crossing its reserve) or gave another plan: the resume was impossible.
         */
        fun forResume(checkpoint: MissionCheckpoint, difficulties: DifficultyUi, registered: List<LocalObstacle>): MissionAvoidance =
            checkpoint.avoidance ?: of(DifficultyUi.derive(difficulties, difficulties.events.filter { it.atMs <= checkpoint.startedAt },
                registered, checkpoint.startedAt))
    }
}

object MissionProgress {
    /** Tolerated drift between the start base and the station observed on resume (RTK noise when docking again). */
    const val BASE_DRIFT_M = 0.25
    const val BASE_DRIFT_DEG = 15.0

    /** Same physical station: close position and docking axis; axis known on both sides or on neither. */
    fun sameStation(a: MapBase, b: MapBase): Boolean {
        if (a.point.distance(b.point) > BASE_DRIFT_M) return false
        val ha = a.entryHeadingDegrees; val hb = b.entryHeadingDegrees
        if ((ha == null) != (hb == null)) return false
        if (ha == null || hb == null) return true
        val delta = Math.abs(((ha - hb) % 360.0 + 540.0) % 360.0 - 180.0)
        return delta <= BASE_DRIFT_DEG
    }

    /** Step back along the interrupted edge: overlap so as to leave no stripe between the stop and the resume. */
    const val OVERLAP_M = 0.30

    /** Stable fingerprint (mm) of the prepared edges: same map, base, accesses and obstacles ⇒ same plan. */
    fun fingerprint(edges: List<SimEdge>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun mm(v: Double) = Math.round(v * 1000)
        // Height of a successive pass included (absent for a single pass: earlier fingerprints unchanged).
        edges.forEach { e -> digest.update("${mm(e.a.x)},${mm(e.a.y)},${mm(e.b.x)},${mm(e.b.y)},${e.cutting},${e.bladesOn}${e.heightMm?.let { ",h$it" } ?: ""};".toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Stable map key (outlines and identifiers), independent of the process. */
    fun mapKey(map: RobotMap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        map.shapes.sortedBy { it.id }.forEach { s ->
            digest.update("${s.id}|${s.type}|".toByteArray())
            s.points.forEach { p -> digest.update("${Math.round(p.x * 1000)},${Math.round(p.y * 1000)};".toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Cutting metres of edges [0, edge) then of the portion of edge [edge] up to [point]. */
    fun cuttingMetersBefore(edges: List<SimEdge>, edge: Int, point: Point?): Double {
        var meters = 0.0
        for (i in 0 until minOf(edge, edges.size)) if (edges[i].cutting) meters += edges[i].meters
        if (point != null && edge in edges.indices && edges[edge].cutting) meters += edges[edge].a.distance(project(edges[edge], point))
        return meters
    }
    fun totalCuttingMeters(edges: List<SimEdge>) = edges.filter { it.cutting }.sumOf { it.meters }

    fun project(edge: SimEdge, point: Point): Point {
        val dx = edge.b.x - edge.a.x; val dy = edge.b.y - edge.a.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return edge.a
        val t = (((point.x - edge.a.x) * dx + (point.y - edge.a.y) * dy) / l2).coerceIn(0.0, 1.0)
        return Point(edge.a.x + t * dx, edge.a.y + t * dy)
    }

    /**
     * Remaining edges from [edge]/[point], the first one truncated [OVERLAP_M] before the stop point (without going
     * back before its start) if [overlap]. A missing point resumes the whole edge.
     */
    fun remaining(edges: List<SimEdge>, edge: Int, point: Point?, overlap: Boolean = true): List<SimEdge> {
        require(edge in 0..edges.size)
        if (edge == edges.size) return emptyList()
        val current = edges[edge]
        val rest = edges.drop(edge + 1)
        if (point == null) return listOf(current) + rest
        val projected = project(current, point)
        // Overlap only when cutting: a link with blades stopped resumes at the point reached.
        val back = maxOf(0.0, current.a.distance(projected) - if (current.cutting && overlap) OVERLAP_M else 0.0)
        val length = current.meters
        val from = if (length <= 0.0) current.a else Point(current.a.x + (current.b.x - current.a.x) * back / length,
            current.a.y + (current.b.y - current.a.y) * back / length)
        return if (from.distance(current.b) < 0.01) rest else listOf(current.copy(a = from)) + rest
    }

    /**
     * Monotonic progress: the resume point is never advanced beyond the actually observed position, nor moved back on
     * a transient back-off.
     */
    fun advance(previous: MissionCheckpoint, edges: List<SimEdge>, edge: Int, position: Point, now: Long): MissionCheckpoint? {
        require(edge in edges.indices)
        val point = project(edges[edge], position)
        val before = previous.point?.let { project(edges[previous.edge.coerceAtMost(edges.size - 1)], it) }
        val ahead = edge > previous.edge || (edge == previous.edge && (before == null || edges[edge].a.distance(point) > edges[edge].a.distance(before) + 0.01))
        if (!ahead) return null
        return previous.copy(edge = edge, point = point, doneMeters = cuttingMetersBefore(edges, edge, point), updatedAt = now)
    }

    /** Cutting metres remaining after [edge]/[point]. */
    fun remainingCuttingMeters(edges: List<SimEdge>, edge: Int, point: Point?): Double =
        maxOf(0.0, totalCuttingMeters(edges) - cuttingMetersBefore(edges, edge, point))

    /**
     * "Resume further on": point of the plan [meters] of cutting after [edge]/[point], along the remaining stripes
     * (links with blades stopped do not count). The point obtained is always on a cutting edge and the resume starts
     * there without overlap. 0 m (or less): normal resume, with overlap. Null beyond the last stripe.
     */
    fun skipAhead(edges: List<SimEdge>, edge: Int, point: Point?, meters: Double): MissionResume? {
        require(edge in 0..edges.size && meters.isFinite())
        if (meters <= 0.0) return MissionResume(edge, point)
        var left = meters
        var i = edge
        var offset = if (point != null && edge in edges.indices) edges[edge].a.distance(project(edges[edge], point)) else 0.0
        while (i < edges.size) {
            val e = edges[i]
            if (e.cutting) {
                val available = e.meters - offset
                if (left <= available) {
                    val at = offset + left
                    val p = if (e.meters <= 0.0) e.a else Point(e.a.x + (e.b.x - e.a.x) * at / e.meters, e.a.y + (e.b.y - e.a.y) * at / e.meters)
                    return MissionResume(i, p, overlap = false)
                }
                left -= maxOf(0.0, available)
            }
            offset = 0.0
            i++
        }
        return null
    }

    /**
     * Cutting stripes between [from] and [to] (edge portions), to show on the map what "Resume further on" leaves out.
     * Empty if [to] is not after [from].
     */
    fun cuttingBetween(edges: List<SimEdge>, from: MissionResume, to: MissionResume): List<Pair<Point, Point>> {
        if (to.edge < from.edge || to.edge !in edges.indices) return emptyList()
        val out = mutableListOf<Pair<Point, Point>>()
        for (i in from.edge..to.edge) {
            val e = edges.getOrNull(i) ?: continue
            if (!e.cutting) continue
            val a = if (i == from.edge) from.point?.let { project(e, it) } ?: e.a else e.a
            val b = if (i == to.edge) to.point?.let { project(e, it) } ?: e.a else e.b
            if (e.a.distance(b) > e.a.distance(a) + 1e-6) out += a to b
        }
        return out
    }

    /**
     * Resume point closest to [tap] on the cutting stripes remaining after [edge]/[point], within [maxDistance]; its
     * cutting distance from the stop point (for the "Resume further on" slider).
     */
    fun skipToward(edges: List<SimEdge>, edge: Int, point: Point?, tap: Point, maxDistance: Double = 3.0): Double? {
        val before = cuttingMetersBefore(edges, edge, point)
        var best: Pair<Double, Double>? = null
        for (i in edge until edges.size) {
            val e = edges[i]
            if (!e.cutting) continue
            // Interrupted stripe: the already mowed part brings back to the stop point (nothing skipped).
            val projected = project(e, tap)
            val skip = cuttingMetersBefore(edges, i, projected) - before
            val p = if (skip < 0.0) point?.let { project(e, it) } ?: e.a else projected
            val d = p.distance(tap)
            if (d > maxDistance || (best != null && d >= best.first)) continue
            best = d to maxOf(0.0, skip)
        }
        return best?.second
    }

    /**
     * Progress of a "further on" resume: [checkpoint] moved to point [to] of the prepared edges [edges] (on a cutting
     * stripe, never before the stop point), skipped metres accumulated. [to] null: progress unchanged.
     */
    fun skipped(checkpoint: MissionCheckpoint, edges: List<SimEdge>, to: MissionResume?): MissionCheckpoint {
        if (to == null) return checkpoint
        val target = requireNotNull(to.point) { tr("Resume point missing", "Point de reprise absent") }
        check(to.edge in edges.indices && edges[to.edge].cutting) { tr("Resume point outside the mowing stripes", "Point de reprise hors des bandes de tonte") }
        val point = project(edges[to.edge], target)
        val before = cuttingMetersBefore(edges, checkpoint.edge, checkpoint.point)
        val after = cuttingMetersBefore(edges, to.edge, point)
        check(to.edge >= checkpoint.edge && after >= before - 0.01) { tr("Resume point before the stop point", "Point de reprise avant le point d'arrêt") }
        return checkpoint.copy(edge = to.edge, point = point, doneMeters = after,
            skippedMeters = checkpoint.skippedMeters + maxOf(0.0, after - before))
    }

    /** Mission without remaining mowing: the resume would amount to a return to base. */
    fun mowingDone(edges: List<SimEdge>, edge: Int, point: Point?): Boolean {
        if (edges.drop(edge + 1).any { it.cutting }) return false
        if (edge !in edges.indices || !edges[edge].cutting) return true
        return point != null && project(edges[edge], point).distance(edges[edge].b) < 0.05
    }
}

/** Complete serialisation of the preview: the resume prepares exactly the same plan again. */
object PreviewJson {
    private fun points(list: List<Point>) = JSONArray().also { a -> list.forEach { a.put(it.x); a.put(it.y) } }
    internal fun paths(list: List<List<Point>>) = JSONArray().also { a -> list.forEach { a.put(points(it)) } }
    private fun readPoints(a: JSONArray): List<Point> {
        require(a.length() % 2 == 0)
        return (0 until a.length() / 2).map { i -> Point(a.getDouble(2 * i), a.getDouble(2 * i + 1)).also { require(it.x.isFinite() && it.y.isFinite()) } }
    }
    internal fun readPaths(a: JSONArray) = (0 until a.length()).map { readPoints(a.getJSONArray(it)) }

    fun encode(p: Preview): JSONObject = JSONObject().put("runs", paths(p.runs)).put("meters", p.meters).put("angle", p.angle)
        .put("connectors", p.connectors).put("mode", p.mode.name).put("borders", paths(p.borders)).put("outerLoops", p.outerLoops)
        .put("noGoLoops", p.noGoLoops).put("zoneIds", JSONArray(p.zoneIds)).put("zoneAngles", JSONArray(p.zoneAngles))
        .put("passes", paths(p.passes)).put("skippedBorderLoops", p.skippedBorderLoops).put("startWithBorders", p.startWithBorders)
        .put("borderKinds", JSONArray(p.borderKinds)).put("scheduledPaths", p.scheduledPaths?.let(::paths) ?: JSONObject.NULL)
        .put("runCellIds", JSONArray(p.runCellIds)).put("borderAnchors", points(p.borderAnchors)).put("localOnly", p.localOnly)
        .put("plannerLabel", p.plannerLabel).put("links", p.links?.let(::paths) ?: JSONObject.NULL)
        .put("pathHeights", JSONArray(p.pathHeights)).put("cutHeights", JSONArray(p.cutHeights))

    fun decode(j: JSONObject): Preview {
        fun longs(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getLong(it) } }
        fun ints(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getInt(it) } }
        fun nullablePaths(key: String) = if (j.isNull(key)) null else readPaths(j.getJSONArray(key))
        // Height passes missing from an older save: a single pass.
        fun optionalHeights(key: String) = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it).also { h -> require(h in 25..70) } } }.orEmpty()
        return Preview(readPaths(j.getJSONArray("runs")), j.getDouble("meters"), j.getInt("angle"), j.getInt("connectors"),
            CoverageMode.valueOf(j.getString("mode")), readPaths(j.getJSONArray("borders")), j.getInt("outerLoops"), j.getInt("noGoLoops"),
            longs("zoneIds"), ints("zoneAngles"), readPaths(j.getJSONArray("passes")), j.getInt("skippedBorderLoops"),
            j.getBoolean("startWithBorders"), j.getJSONArray("borderKinds").let { a -> (0 until a.length()).map { a.getBoolean(it) } },
            nullablePaths("scheduledPaths"), ints("runCellIds"), readPoints(j.getJSONArray("borderAnchors")), j.getBoolean("localOnly"),
            j.getString("plannerLabel"), nullablePaths("links"), optionalHeights("pathHeights"), optionalHeights("cutHeights"))
    }
}

/** One file per robot, atomic write with fsync; unreadable or inconsistent ⇒ ignored. */
class MissionCheckpointStore(private val directory: File) {
    private fun file(id: String) = File(directory, "mission-checkpoint-" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")

    @Synchronized fun save(value: MissionCheckpoint) {
        val json = JSONObject().put("version", 1).put("robotId", value.robotId).put("mapKey", value.mapKey)
            .put("planFingerprint", value.planFingerprint).put("edgeCount", value.edgeCount)
            .put("preview", PreviewJson.encode(value.preview)).put("cutting", value.cutting).put("heightMm", value.heightMm)
            .put("returnPercent", value.returnPercent).put("resumePercent", value.resumePercent).put("edge", value.edge)
            .put("x", value.point?.x ?: JSONObject.NULL).put("y", value.point?.y ?: JSONObject.NULL)
            .put("doneMeters", value.doneMeters).put("totalMeters", value.totalMeters).put("startedAt", value.startedAt)
            .put("updatedAt", value.updatedAt).put("rechargeCycles", value.rechargeCycles).put("resumes", value.resumes)
            .put("interruption", value.interruption ?: JSONObject.NULL).put("skippedMeters", value.skippedMeters)
            .put("avoidance", value.avoidance?.let { a -> JSONObject().put("presumed", JSONObject(ObstacleJson.encode(a.presumed)))
                .put("avoidAreas", PreviewJson.paths(a.avoidAreas)) } ?: JSONObject.NULL)
            .put("bladeZones", value.bladeZones?.let(PreviewJson::paths) ?: JSONObject.NULL)
            .put("base", value.base?.let { b -> JSONObject().put("x", b.point.x).put("y", b.point.y).put("origin", b.origin)
                .put("influenceRadiusM", b.influenceRadiusM ?: JSONObject.NULL).put("entryHeadingDegrees", b.entryHeadingDegrees ?: JSONObject.NULL)
                .put("approachDistanceM", b.approachDistanceM) } ?: JSONObject.NULL)
        val target = file(value.robotId)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(target)) { tr("Failed to save the mowing progress", "Échec sauvegarde de l'avancement de tonte") }
    }

    fun read(id: String): MissionCheckpoint? = runCatching {
        val f = file(id); require(f.length() in 1..8_000_000)
        val j = JSONObject(f.readText())
        require(j.getInt("version") == 1 && j.getString("robotId") == id)
        val point = if (j.isNull("x") || j.isNull("y")) null else Point(j.getDouble("x"), j.getDouble("y"))
        MissionCheckpoint(id, j.getString("mapKey"), j.getString("planFingerprint"), j.getInt("edgeCount"),
            PreviewJson.decode(j.getJSONObject("preview")), j.getBoolean("cutting"), j.getInt("heightMm"),
            j.getInt("returnPercent"), j.getInt("resumePercent"), j.getInt("edge"), point, j.getDouble("doneMeters"),
            j.getDouble("totalMeters"), j.getLong("startedAt"), j.getLong("updatedAt"), j.getInt("rechargeCycles"),
            j.getInt("resumes"), if (j.isNull("interruption")) null else j.getString("interruption"),
            j.optJSONObject("avoidance")?.let { a -> MissionAvoidance(ObstacleJson.decode(a.getJSONObject("presumed").toString()),
                PreviewJson.readPaths(a.getJSONArray("avoidAreas"))) }, j.optDouble("skippedMeters", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
            j.optJSONArray("bladeZones")?.let(PreviewJson::readPaths),
            j.optJSONObject("base")?.let { b -> runCatching { MapBase(Point(b.getDouble("x"), b.getDouble("y")), b.getString("origin"),
                if (b.isNull("influenceRadiusM")) null else b.getDouble("influenceRadiusM"),
                if (b.isNull("entryHeadingDegrees")) null else b.getDouble("entryHeadingDegrees"), b.getDouble("approachDistanceM")) }.getOrNull() })
    }.getOrNull()

    @Synchronized fun delete(id: String) { file(id).delete() }
}

/**
 * Resume requested for a route: start point in the prepared mowing edges. [overlap]: start again
 * [MissionProgress.OVERLAP_M] before the point (resume at the stop point); without overlap for "Resume further on".
 */
data class MissionResume(val edge: Int, val point: Point?, val overlap: Boolean = true)

/** Mapping between the edges sent to the robot and the prepared mowing edges. */
data class MissionEdges(val edges: List<SimEdge>, val mowingFrom: Int, val mowingTo: Int, val engineFrom: Int) {
    /** Prepared edge followed at route index [index], or null outside mowing (departure link, return). */
    fun engineEdge(index: Int): Int? = if (index in mowingFrom until mowingTo) engineFrom + (index - mowingFrom) else null
}
