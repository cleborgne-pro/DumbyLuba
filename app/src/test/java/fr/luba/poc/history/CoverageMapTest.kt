package fr.luba.poc.history

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.ceil

/** Real coverage of a task: cutting width swept around the recorded track, per zone, gaps usable by the planner. */
class CoverageMapTest {
    private val factory = GeometryFactory()
    private fun rect(id: Long, x0: Double, y0: Double, x1: Double, y1: Double, type: Int = 0, name: String = "Z$id") =
        MapShape(id, type, listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1)), name)

    /** Positions every [step] m along [points], one per second, all in [phase]. */
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

    /** Back-and-forth stripes over [x0, x1] every [spacing] m from [y0] to [y1]. */
    private fun stripes(x0: Double, x1: Double, y0: Double, y1: Double, spacing: Double): List<Point> {
        val out = mutableListOf<Point>()
        var y = y0; var right = true
        while (y <= y1 + 1e-9) {
            if (right) { out += Point(x0, y); out += Point(x1, y) } else { out += Point(x1, y); out += Point(x0, y) }
            right = !right; y += spacing
        }
        return out
    }

    private val zone = rect(1, 0.0, 0.0, 10.0, 6.0, name = "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")

    @Test fun fullStripesCoverTheZoneWithoutGaps() {
        val track = TaskTrack(TrackMeta(map, zoneIds = listOf(1L)), drive(stripes(0.2, 9.8, 0.2, 5.8, 0.35)))
        val c = requireNotNull(CoverageMap.of(track))
        assertEquals(60.0, c.areaM2, 0.5)
        assertTrue("Covered ${c.fraction}", c.fraction > 0.97)
        assertEquals(1, c.zones.size)
        assertEquals("Pelouse", c.zones[0].name)
        assertEquals(c.fraction, c.zones[0].fraction, 1e-9)
        // Overlap between neighbouring stripes (5 cm on each side) and the U-turns.
        assertTrue(c.repeatedM2 > 0.0)
        assertTrue("Gaps ${c.gaps()}", c.gaps().isEmpty())
        assertTrue(c.gapArea().isEmpty)
    }

    @Test fun skippedStripesLeaveAGapUsableAsATarget() {
        // Stripes between y = 2 and y = 3.4 not driven: a band of about 10 × 1 m left unmowed.
        val path = stripes(0.2, 9.8, 0.2, 1.95, 0.35) + stripes(0.2, 9.8, 3.6, 5.8, 0.35)
        val samples = drive(path) { p -> if (p.y in 2.0..3.5) TrackPhase.TRAVEL else TrackPhase.CUTTING }
        val c = requireNotNull(CoverageMap.of(TaskTrack(TrackMeta(map, zoneIds = listOf(1L)), samples)))
        val gaps = c.gaps()
        assertEquals("Gaps $gaps", 1, gaps.size)
        val gap = gaps.single()
        assertEquals(1L, gap.zoneId)
        assertTrue("Gap ${gap.areaM2} m²", gap.areaM2 in 9.0..15.0)
        assertTrue(gap.area.isValid)
        assertTrue(gap.area.contains(factory.createPoint(Coordinate(5.0, 2.7))))
        assertTrue(gap.center.y in 2.0..3.5)
        assertFalse(c.coveredAt(Point(5.0, 2.7)))
        assertTrue(c.coveredAt(Point(5.0, 1.0)))
        assertEquals(c.zones[0].areaM2 - c.zones[0].coveredM2, gap.areaM2, 3.0)
        assertTrue(gap.outlines.single().size >= 4)
    }

    @Test fun onlyBladesRunningAndJoinedPositionsCount() {
        val travel = drive(listOf(Point(1.0, 1.0), Point(9.0, 1.0))) { TrackPhase.TRAVEL }
        assertEquals(0.0, CoverageMap.of(TaskTrack(TrackMeta(map), travel))!!.coveredM2, 1e-9)
        // Two cutting positions 6 m apart (positions lost): not joined, only the discs under the robot count.
        val jump = listOf(TrackSample(0, 1.0, 3.0, 0.0, TrackPhase.CUTTING), TrackSample(1_000, 7.0, 3.0, 0.0, TrackPhase.CUTTING))
        val c = CoverageMap.of(TaskTrack(TrackMeta(map), jump))!!
        assertEquals(2 * Math.PI * 0.04, c.coveredM2, 0.06)
        assertFalse(c.coveredAt(Point(4.0, 3.0)))
        // Same distance within the gap limit in time and space: joined.
        val joined = listOf(TrackSample(0, 1.0, 3.0, 0.0, TrackPhase.CUTTING), TrackSample(1_000, 3.5, 3.0, 0.0, TrackPhase.CUTTING))
        assertTrue(CoverageMap.of(TaskTrack(TrackMeta(map), joined))!!.coveredAt(Point(2.2, 3.1)))
    }

    @Test fun eachZoneHasItsShareAndNoGoZonesAreExcluded() {
        val second = rect(2, 12.0, 0.0, 16.0, 4.0, name = "Fond")
        val noGo = rect(3, 4.0, 2.0, 6.0, 4.0, type = 1)
        val twoZones = RobotMap(listOf(zone, second, noGo), true, "test")
        val track = TaskTrack(TrackMeta(twoZones, zoneIds = listOf(1L, 2L)), drive(stripes(0.2, 9.8, 0.2, 5.8, 0.35)))
        val c = requireNotNull(CoverageMap.of(track))
        val first = c.zone(1L)!!; val back = c.zone(2L)!!
        assertEquals(56.0, first.areaM2, 0.5)
        assertEquals(16.0, back.areaM2, 0.5)
        assertTrue(first.fraction > 0.97)
        assertEquals(0.0, back.fraction, 1e-9)
        assertEquals(72.0, c.areaM2, 1.0)
        // The no-go zone is neither measured nor a gap; the unmowed zone is one.
        assertFalse(c.coveredAt(Point(5.0, 3.0)))
        assertTrue(c.gaps().none { it.area.contains(factory.createPoint(Coordinate(5.0, 3.0))) })
        assertEquals(2L, c.gaps().single().zoneId)
        // Zones of the task only: the other zone is left out of the measure.
        val single = CoverageMap.of(TaskTrack(TrackMeta(twoZones, zoneIds = listOf(2L)), track.samples))!!
        assertEquals(listOf(2L), single.zones.map { it.id })
        // Unknown zones (old track): all the zones of the map.
        assertEquals(listOf(1L, 2L), CoverageMap.of(TaskTrack(TrackMeta(twoZones), track.samples))!!.zones.map { it.id })
    }

    @Test fun incrementalBuilderMatchesTheWholeTrack() {
        val samples = drive(stripes(0.2, 9.8, 0.2, 5.8, 0.35))
        val whole = CoverageMap.of(TaskTrack(TrackMeta(map), samples))!!
        val builder = CoverageBuilder(map)
        // Resume: the first half arrives after the second one, read back from the saved track.
        val half = samples.size / 2
        samples.drop(half).forEach(builder::add)
        builder.addRun(samples.take(half))
        val live = builder.snapshot()!!
        assertEquals(whole.coveredM2, live.coveredM2, 0.05)
        // A snapshot is immutable: later positions do not change it.
        val frozen = live.coveredM2
        builder.add(TrackSample(Long.MAX_VALUE, 5.0, 3.0, 0.0, TrackPhase.CUTTING))
        assertEquals(frozen, live.coveredM2, 1e-12)
        assertTrue(builder.snapshot()!!.repeatedM2 > live.repeatedM2)
        assertNull(CoverageBuilder(RobotMap()).snapshot())
    }

    @Test fun layerImageMarksCoveredCellsAndGaps() {
        val path = stripes(0.2, 9.8, 0.2, 1.95, 0.35)
        val c = CoverageMap.of(TaskTrack(TrackMeta(map), drive(path)))!!
        val pixels = c.pixels(covered = 1, repeated = 2, gap = 3)
        assertEquals(c.cols * c.rows, pixels.size)
        fun at(p: Point): Int { val col = ((p.x - c.minX) / c.cellM).toInt(); val row = c.rows - 1 - ((p.y - c.minY) / c.cellM).toInt(); return pixels[row * c.cols + col] }
        assertTrue(at(Point(5.0, 1.0)) in setOf(1, 2))
        assertEquals(3, at(Point(5.0, 4.0)))
        assertTrue(c.pixels(1).none { it == 3 })
    }

    @Test fun percentagesAreRoundedDown() {
        assertEquals("99 %", CoverageText.percent(0.996))
        assertEquals("100 %", CoverageText.percent(1.0))
        assertEquals("0 %", CoverageText.percent(-0.1))
    }

    @Test fun trackContextKeepsTheTaskZones() {
        val meta = TrackMeta(map, zoneIds = listOf(1L, -7_000_000_001L))
        assertEquals(listOf(1L, -7_000_000_001L), TrackJson.decode(TrackJson.encode("t", meta)).zoneIds)
        // Context written before the real coverage: no zones, all of them measured.
        val old = TrackJson.encode("t", meta).apply { remove("zones") }
        assertEquals(emptyList<Long>(), TrackJson.decode(JSONObject(old.toString())).zoneIds)
    }

    // --- Planner use of the gaps, on the reference map ---

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

    @Test(timeout = 180_000) fun gapsArePlannedAndTheFinishingPassClosesThem() {
        val playground = reference.shapes.first { it.name.contains("Aire de jeux") }
        val ring = factory.createPolygon((playground.points + playground.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        val env = ring.envelopeInternal
        // Whole zone mowed except a band of about 3 m across its middle.
        val mid = (env.minY + env.maxY) / 2
        fun inZone(p: Point) = ring.contains(factory.createPoint(Coordinate(p.x, p.y)))
        val samples = drive(stripes(env.minX, env.maxX, env.minY + 0.2, env.maxY - 0.2, 0.3)) { p ->
            if (inZone(p) && (p.y < mid - 1.5 || p.y > mid + 1.5)) TrackPhase.CUTTING else TrackPhase.TRAVEL }
        val track = TaskTrack(TrackMeta(reference, zoneIds = listOf(playground.id)), samples)
        val before = CoverageMap.of(track)!!
        val gapArea = before.gapArea()
        val band = factory.toGeometry(org.locationtech.jts.geom.Envelope(env.minX, env.maxX, mid - 1.25, mid + 1.25))
        val expected = ring.intersection(band).area
        assertTrue("Gap ${gapArea.area} m² for $expected", gapArea.area > 0.7 * expected && gapArea.area < 2.0 * expected)
        assertTrue(before.gaps().all { it.zoneId == playground.id })
        // The planner takes the gaps as target, like a painted area.
        val mission = PaintedZone.prepare(reference, gapArea, base.point, base, emptyList(), emptyList(), spacingM = 0.25, borders = true, speedMps = 0.3)
        assertFalse(mission.preview.localOnly)
        assertTrue("Finishing ${mission.areaM2} m²", mission.areaM2 < 0.5 * before.zones.single().areaM2)
        // Finishing driven: its cutting edges are recorded after the first pass.
        val finishing = mutableListOf<TrackSample>()
        var at = samples.last().atMs + 60_000
        mission.engine.edges.filter { it.bladesOn }.forEach { e ->
            drive(listOf(e.a, e.b), from = at).also { finishing += it; at = it.last().atMs + 1_000 }
        }
        val after = CoverageMap.of(TaskTrack(track.meta, samples + finishing))!!
        assertTrue("Coverage ${before.fraction} -> ${after.fraction}", after.fraction > before.fraction + 0.1)
        val left = after.gaps().sumOf { it.areaM2 }
        assertTrue("Gaps left $left m² of ${gapArea.area}", left < 0.2 * gapArea.area)
    }
}
