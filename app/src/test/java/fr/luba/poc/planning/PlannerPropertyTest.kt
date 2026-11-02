package fr.luba.poc.planning

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property test on generated maps: whatever the shape, a plan published as validated keeps every blade path inside
 * the zone and outside the no-go outlines. The reference-map sweeps pin exact values on one map; this one explores the
 * geometry the planner was not tuned on (irregular outlines, rotated, with and without holes) and states the invariant
 * that must hold everywhere.
 *
 * A map whose plan is refused is not a failure: the planner is allowed to refuse instead of planning something unsafe.
 * The test only checks the plans it publishes, and requires that most generated maps remain plannable so that the
 * invariant is not checked on an empty set.
 */
class PlannerPropertyTest {
    private val random = Random(20261030)

    @Test(timeout = 300_000) fun everyPublishedRouteStaysInTheFreeArea() {
        var published = 0
        repeat(8) { index ->
            val zone = randomOutline(index)
            val holes = holesInside(zone, count = index % 3)
            val map = RobotMap(listOf(MapShape(1, 0, zone, "Zone $index")) +
                holes.mapIndexed { i, points -> MapShape(i + 2L, 1, points, "Massif $i") }, true, "generated-$index")
            val request = PlanningRequest(map, listOf(1), inside(zone), config = PlanningConfig(spacingM = 0.45, minimumCoverage = 0.6))
            // A refusal is allowed (no safe entry, geometry too tight): only a published plan must keep the invariant.
            val plan = runCatching { RoutePlanning.coverage(request) }.getOrNull() ?: return@repeat
            if (!plan.preview.localOnly) {
                published++
                val paths: List<List<Point>> = plan.preview.cuttingPaths + plan.preview.borders
                val offenders = paths.flatten().filter { point ->
                    !insideWithTolerance(zone, point) || holes.any { insideWithTolerance(it, point) }
                }
                assertTrue("map $index: ${offenders.size} blade points outside the free area, first ${offenders.take(3)}",
                    offenders.isEmpty())
                plan.report?.selected?.metrics?.let { metrics ->
                    assertTrue("map $index: coverage ${metrics.coverage}", metrics.coverage >= request.config.minimumCoverage - 1e-9)
                }
            }
        }
        // Most generated maps must be plannable, otherwise the property would silently check nothing.
        assertTrue("only $published of 8 generated maps were planned", published >= 5)
    }

    /** Outline drawn on a circle (points sorted by angle, radius varying mildly), rotated by a random angle. */
    private fun randomOutline(index: Int): List<Point> {
        val centerX = 100.0 * index
        val centerY = 50.0 * index
        val radius = 4.0 + random.nextDouble() * 4.0
        val vertices = 5 + random.nextInt(4)
        val rotation = random.nextDouble() * 2 * Math.PI
        return (0 until vertices).map { i ->
            val angle = rotation + 2 * Math.PI * i / vertices
            // Radius varies a little without breaking convexity: a star-shaped polygon with a wide margin.
            val r = radius * (0.85 + 0.15 * (i % 2))
            Point(centerX + r * kotlin.math.cos(angle), centerY + r * kotlin.math.sin(angle))
        }
    }

    /** Axis-aligned rectangular holes, fully inside [zone] (a metre of clearance), no overlap. */
    private fun holesInside(zone: List<Point>, count: Int): List<List<Point>> {
        val maxX = zone.maxOf { it.x }; val minX = zone.minOf { it.x }
        val maxY = zone.maxOf { it.y }; val minY = zone.minOf { it.y }
        val width = (maxX - minX) / (count + 1.0)
        val holes = mutableListOf<List<Point>>()
        repeat(count) { i ->
            val w = 0.8 + random.nextDouble() * 0.7
            val h = 0.8 + random.nextDouble() * 0.7
            val x = minX + width * (i + 0.5) - w / 2
            val y = minY + (maxY - minY) * (0.3 + 0.4 * random.nextDouble()) - h / 2
            val rectangle = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
            if (rectangle.all { insideWithTolerance(zone, it, margin = 1.0) } && holes.none { overlaps(it, rectangle) }) {
                holes += rectangle
            }
        }
        return holes
    }

    private fun overlaps(a: List<Point>, b: List<Point>): Boolean =
        a.maxOf { it.x } > b.minOf { it.x } && b.maxOf { it.x } > a.minOf { it.x } &&
            a.maxOf { it.y } > b.minOf { it.y } && b.maxOf { it.y } > a.minOf { it.y }

    /** A start point well inside the outline, away from the edges. */
    private fun inside(zone: List<Point>): Point {
        val cx = zone.sumOf { it.x } / zone.size
        val cy = zone.sumOf { it.y } / zone.size
        return Point(cx, cy)
    }

    /** Ray casting; [margin] widens the polygon (negative shrinks it), so that a point on an edge is still "inside". */
    private fun insideWithTolerance(polygon: List<Point>, point: Point, margin: Double = 1e-6): Boolean {
        if (contains(polygon, point)) return true
        return distanceToEdges(polygon, point) <= margin
    }

    private fun contains(polygon: List<Point>, point: Point): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private fun distanceToEdges(polygon: List<Point>, point: Point): Double =
        polygon.indices.minOf { i ->
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            distanceToSegment(point, a, b)
        }

    private fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
