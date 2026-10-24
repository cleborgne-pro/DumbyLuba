package fr.luba.poc.planning

import fr.luba.poc.history.CoverageMap
import fr.luba.poc.history.TaskTrack
import fr.luba.poc.history.TrackMeta
import fr.luba.poc.history.TrackPhase
import fr.luba.poc.history.TrackSample
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.ceil

/** Finishing phase on the real gaps: offer, dedicated target, envelope and length checks, coverage after the pass. */
class FinishingPassTest {
    private val factory = GeometryFactory()

    /** Positions every [step] m along [points], one per second. */
    private fun drive(points: List<Point>, from: Long = 0L, step: Double = 0.1, phase: (Point) -> TrackPhase = { TrackPhase.CUTTING }): List<TrackSample> {
        val out = mutableListOf<TrackSample>()
        var t = from
        points.zipWithNext().forEach { (a, b) ->
            val n = ceil(a.distance(b) / step).toInt().coerceAtLeast(1)
            for (i in (if (out.isEmpty()) 0 else 1)..n) {
                val p = Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
                out += TrackSample(t, p.x, p.y, 0.0, phase(p)); t += 1_000
            }
        }
        return out
    }

    private fun stripes(x0: Double, x1: Double, y0: Double, y1: Double, spacing: Double): List<Point> {
        val out = mutableListOf<Point>()
        var y = y0; var right = true
        while (y <= y1 + 1e-9) {
            if (right) { out += Point(x0, y); out += Point(x1, y) } else { out += Point(x1, y); out += Point(x0, y) }
            right = !right; y += spacing
        }
        return out
    }

    private fun rect(id: Long, x0: Double, y0: Double, x1: Double, y1: Double) =
        MapShape(id, 0, listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1)), "Z$id")

    // --- Offer ---

    private val lawn = rect(1, 0.0, 0.0, 10.0, 6.0)
    private val small = RobotMap(listOf(lawn), true, "test")

    /** Lawn mowed in stripes, blades stopped where [skip] is true. */
    private fun lawnCoverage(skip: (Point) -> Boolean): CoverageMap = requireNotNull(CoverageMap.of(TaskTrack(TrackMeta(small, zoneIds = listOf(1L)),
        drive(stripes(0.2, 9.8, 0.2, 5.8, 0.35)) { if (skip(it)) TrackPhase.TRAVEL else TrackPhase.CUTTING })))

    @Test fun offeredOnlyForAFinishedMowingWithWorthwhileGaps() {
        val full = lawnCoverage { false }
        assertNull("No gap, no finishing", FinishingPass.offer("t", full, complete = true))
        // 2 m × 1.5 m left unmowed in the middle.
        val holed = lawnCoverage { it.x in 4.0..6.0 && it.y in 2.0..3.5 }
        val offer = requireNotNull(FinishingPass.offer("t", holed, complete = true))
        assertEquals(1, offer.count)
        assertTrue("Gap ${offer.areaM2} m²", offer.areaM2 in 1.5..5.0)
        assertEquals(holed.areaM2, offer.zonesAreaM2, 1e-9)
        assertFalse(offer.area.isEmpty)
        assertNull("Interrupted mowing: resumed, not finished", FinishingPass.offer("t", holed, complete = false))
        assertNull("Below the threshold", FinishingPass.offer("t", holed, complete = true, minM2 = 10.0))
        // Half of the lawn left: mowing again, not a finishing.
        assertNull(FinishingPass.offer("t", lawnCoverage { it.x > 5.0 }, complete = true))
    }

    @Test fun targetCentresThePassesOnTheGap() {
        // Planner inset without border lap: half width + outer margin + half a cut.
        val inset = FinishingPass.targetInsetM(outerOffsetM = 0.05)
        assertTrue("Inset $inset", inset in 0.25..0.6)
        val offer = requireNotNull(FinishingPass.offer("t", lawnCoverage { it.x in 4.0..6.0 && it.y in 2.0..3.5 }, complete = true))
        val target = FinishingPass.target(offer, outerOffsetM = 0.05)
        assertTrue(target.covers(offer.area))
        val grow = inset + FinishingPass.OVERLAP_M
        assertEquals(offer.area.envelopeInternal.width + 2 * grow, target.envelopeInternal.width, 0.05)
    }

    // --- Planned and replayed on the reference map ---

    private val reference: RobotMap = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        RobotMap((0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }, true, "reference-station")
    }
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val playground = reference.shapes.first { it.name.contains("Aire de jeux") }
    private val ring = factory.createPolygon((playground.points + playground.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())

    /** Playground mowed in stripes except a band [width] m wide across its middle; finishing planned, checked and replayed. */
    private class Band(val track: TaskTrack, val before: CoverageMap, val plan: FinishingPlan, val after: CoverageMap)

    /** Blades-on edges of a prepared mission driven after [from]. */
    private fun replay(edges: List<fr.luba.poc.simulation.SimEdge>, from: Long): List<TrackSample> {
        val out = mutableListOf<TrackSample>()
        var at = from
        edges.filter { it.bladesOn }.forEach { e -> drive(listOf(e.a, e.b), from = at).also { out += it; at = it.last().atMs + 1_000 } }
        return out
    }

    private fun finishBand(width: Double): Band {
        val env = ring.envelopeInternal
        val mid = (env.minY + env.maxY) / 2
        fun inZone(p: Point) = ring.contains(factory.createPoint(Coordinate(p.x, p.y)))
        val samples = drive(stripes(env.minX, env.maxX, env.minY + 0.2, env.maxY - 0.2, 0.3)) { p ->
            if (inZone(p) && (p.y < mid - width / 2 || p.y > mid + width / 2)) TrackPhase.CUTTING else TrackPhase.TRAVEL }
        val track = TaskTrack(TrackMeta(reference, zoneIds = listOf(playground.id)), samples)
        val before = CoverageMap.of(track)!!
        val offer = requireNotNull(FinishingPass.offer("t", before, complete = true)) { "No offer for a $width m band" }
        val band = factory.toGeometry(Envelope(env.minX, env.maxX, mid - width / 2, mid + width / 2))
        assertTrue("Band ${ring.intersection(band).area} m², offer ${offer.areaM2} m²", offer.areaM2 > 0.5 * ring.intersection(band).area)
        val plan = FinishingPass.prepare(reference, offer, base.point, base, emptyList(), emptyList(), spacingM = 0.25, speedMps = 0.3)
        assertTrue(plan.mission.zones.all { it.name.startsWith("Finishing") || it.name.startsWith("Finition") })
        assertTrue("Lap around each finishing piece", plan.mission.preview.borders.isNotEmpty())
        // Finishing driven: its blades-on edges are recorded after the mowing.
        val after = CoverageMap.of(TaskTrack(track.meta, samples + replay(plan.mission.engine.edges, samples.last().atMs + 60_000)))!!
        return Band(track, before, plan, after)
    }

    private fun assertFinished(band: Band) {
        val before = band.before; val after = band.after
        val check = band.plan.check
        assertTrue("Envelope: ${check.collisions}", check.safe)
        assertTrue("Route ${check.routeM} m for a new mowing of ${check.newMowingM} m", check.short)
        assertTrue(check.cuttingM > 0 && check.cuttingM <= check.routeM)
        val gapsBefore = before.gaps().sumOf { it.areaM2 }
        val gapsAfter = after.gaps().sumOf { it.areaM2 }
        assertTrue("Coverage ${before.fraction} -> ${after.fraction}", after.fraction > before.fraction)
        val where = after.gaps().joinToString { g -> "%.2f m² at %.1f m from the edge".format(g.areaM2,
            ring.boundary.distance(factory.createPoint(Coordinate(g.center.x, g.center.y)))) }
        assertTrue("Gaps left $gapsAfter m² of $gapsBefore: $where", gapsAfter < 0.05 * gapsBefore)
    }

    @Test(timeout = 180_000) fun wideBandIsFinishedWithAShortCheckedRoute() {
        val band = finishBand(3.0)
        assertFinished(band)
        assertTrue("Coverage ${band.before.fraction} -> ${band.after.fraction}", band.after.fraction > band.before.fraction + 0.1)
    }

    @Test(timeout = 180_000) fun narrowBandOfAboutOneMetreIsFinishedToo() {
        // A painted zone of the same gap keeps its lap inside the gap: the dedicated target widens it so that the
        // lap runs on its edges and the passes cover it.
        assertFinished(finishBand(1.0))
    }

    @Test(timeout = 180_000) fun paintedPreparationOfTheSameNarrowGapLeavesMore() {
        val band = finishBand(1.0)
        // Former path (gaps as a painted area, 0.6 m margin and border lap): refused, or leaves clearly more.
        val painted = runCatching {
            PaintedZone.prepare(reference, band.before.gapArea(), base.point, base, emptyList(), emptyList(), spacingM = 0.25, borders = true, speedMps = 0.3)
        }.getOrNull() ?: return
        val samples = band.track.samples
        val paintedAfter = CoverageMap.of(TaskTrack(band.track.meta, samples + replay(painted.engine.edges, samples.last().atMs + 60_000)))!!
        val left = band.after.gaps().sumOf { it.areaM2 }
        val leftPainted = paintedAfter.gaps().sumOf { it.areaM2 }
        assertTrue("Finishing leaves $left m², painted area $leftPainted m²", left < leftPainted)
    }
}
