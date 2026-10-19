package fr.luba.poc.history

import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Route tracking quality of a history task: deviation from the prepared stripes, holes, tracking link. */
class RouteDeviationTest {
    private fun s(at: Long, x: Double, y: Double, phase: TrackPhase = TrackPhase.CUTTING) = TrackSample(at, x, y, 0.0, phase)
    private val stripes = listOf(listOf(Point(0.0, 0.0), Point(20.0, 0.0)), listOf(Point(20.0, 0.4), Point(0.0, 0.4)))
    private fun track(samples: List<TrackSample>, cutting: List<List<Point>> = stripes) = TaskTrack(TrackMeta(RobotMap(), cutting), samples)

    @Test fun `deviation measured on mowing positions only, against the nearest stripe`() {
        // 0, 2, 4… 18 cm from the first stripe, then a travel 5 m away that must not count.
        val mowing = (0 until 10).map { s(it * 1000L, it * 2.0, it * 0.02) }
        val travel = listOf(s(10_000, 10.0, 5.0, TrackPhase.TRAVEL))
        val d = requireNotNull(RouteDeviation.of(track(mowing + travel)))
        assertEquals(10, d.samples)
        assertEquals(0.10, d.medianM, 1e-9)
        assertEquals(0.18, d.p95M, 1e-9)
        assertEquals(0.18, d.maxM, 1e-9)
        assertEquals(0, d.over30)
        assertEquals(0, d.offRoute)
        // On the second stripe: 0.4 m away from the first, 2 cm from the second.
        assertEquals(0.02, RouteDeviation.of(track(listOf(s(0, 5.0, 0.38))))!!.maxM, 1e-9)
    }

    @Test fun `positions beyond the limit and far from every stripe are counted`() {
        val d = requireNotNull(RouteDeviation.of(track(listOf(s(0, 1.0, -0.35), s(1000, 2.0, 0.0), s(2000, 3.0, -10.0)))))
        assertEquals(2, d.over30)
        assertEquals(1, d.offRoute)
        assertEquals(RouteDeviation.SEARCH_M, d.maxM, 1e-9)
        assertEquals("35 cm · 300 cm · ≥ 300 cm", TrackingText.deviation(d))
        assertEquals("2 sur 3 (67 %)", TrackingText.beyond(d))
    }

    @Test fun `holes off charge are counted, recharges are not`() {
        val samples = listOf(s(0, 0.0, 0.0), s(5_000, 1.0, 0.0), s(65_000, 2.0, 0.0), // 60 s Wi-Fi hole
            s(66_000, 2.0, 0.0, TrackPhase.CHARGING), s(3_666_000, 2.0, 0.0, TrackPhase.CHARGING), // 1 h on charge
            s(3_667_000, 3.0, 0.0), s(3_687_000, 4.0, 0.0)) // 20 s hole
        val d = requireNotNull(RouteDeviation.of(track(samples)))
        assertEquals(2, d.gaps)
        assertEquals(60_000L, d.longestGapMs)
        assertEquals("2 · le plus long 1 min 00 s", TrackingText.gaps(d))
        assertEquals("Aucun", TrackingText.gaps(RouteDeviation.of(track(samples.take(2)))!!))
    }

    @Test fun `no measure without prepared route or without mowing position`() {
        assertNull(RouteDeviation.of(TaskTrack(null, listOf(s(0, 0.0, 0.0)))))
        assertNull(RouteDeviation.of(track(listOf(s(0, 0.0, 0.0)), cutting = emptyList())))
        assertNull(RouteDeviation.of(track(listOf(s(0, 0.0, 0.0, TrackPhase.TRAVEL)))))
    }

    @Test fun `grid lookup matches a full scan on diagonal stripes`() {
        val random = Random(7)
        val lines = (0 until 30).map { i -> listOf(Point(i * 0.37, -3.0 + i * 0.1), Point(i * 0.37 + 17.0, 12.0 - i * 0.2), Point(i * 0.5, 25.0)) }
        val grid = SegmentGrid(lines)
        fun brute(p: Point): Double = lines.flatMap { it.zipWithNext() }.minOf { (a, b) ->
            val dx = b.x - a.x; val dy = b.y - a.y
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            Point(a.x + t * dx, a.y + t * dy).distance(p)
        }
        repeat(2_000) {
            val p = Point(random.nextDouble(-8.0, 30.0), random.nextDouble(-8.0, 32.0))
            assertEquals("at $p", minOf(brute(p), 3.0), grid.distance(p, 3.0), 1e-9)
        }
    }

    @Test fun `tracking link and gateway runs saved, older records read without them`() {
        val forecast = MowingForecaster.forecast(1080.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        val record = MowingRecord("AA-1", "AA", startedAt = 1L, forecast = forecast,
            links = setOf(TrackingLink.BLUETOOTH, TrackingLink.GATEWAY), gatewayLegs = 3)
        val read = MowingHistoryJson.decode(JSONObject(MowingHistoryJson.encode(record).toString()))
        assertEquals(record.links, read.links)
        assertEquals(3, read.gatewayLegs)
        assertEquals("Téléphone pilote, Bluetooth direct + Passerelle sur le robot, suivi autonome · 3 suivis confiés à la passerelle", TrackingText.links(read))
        val old = MowingHistoryJson.encode(record).apply { remove("links"); remove("gatewayLegs") }
        val legacy = MowingHistoryJson.decode(JSONObject(old.toString()))
        assertEquals(emptySet<TrackingLink>(), legacy.links)
        assertEquals(0, legacy.gatewayLegs)
        assertNull(TrackingText.links(legacy))
        assertEquals("Passerelle sur le robot, suivi autonome · 1 suivi confié à la passerelle",
            TrackingText.links(record.copy(links = setOf(TrackingLink.GATEWAY), gatewayLegs = 1)))
    }
}
