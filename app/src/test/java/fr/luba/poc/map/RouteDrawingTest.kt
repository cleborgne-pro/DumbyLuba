package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class RouteDrawingTest {
    private val row = listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,1.0),Point(0.0,1.0))
    private val loop = listOf(Point(-1.0,-1.0),Point(11.0,-1.0),Point(11.0,2.0),Point(-1.0,2.0),Point(-1.0,-1.0))
    private val preview = Preview(listOf(row),51.0,0,1,borders=listOf(loop),outerLoops=1,passes=listOf(row.take(2),row.drop(2)))

    @Test fun nothingIsDrawnBeforeTheStartThenTheTipFollowsTheMowingOrder() {
        val drawing = RouteDrawing(preview)
        assertEquals(51.0, drawing.meters, 1e-9)
        val start = drawing.reveal(0.0)
        assertTrue(start.runs.isEmpty() && start.borders.isEmpty())
        assertEquals(Point(0.0,0.0), start.tip)
        val half = drawing.reveal(5.0 / 51.0)
        assertEquals(listOf(Point(0.0,0.0),Point(5.0,0.0)), half.runs.single())
        assertTrue(half.borders.isEmpty())
        assertEquals(Point(5.0,0.0), half.tip)
    }

    @Test fun bordersFollowRowsAndStayBordersOnceRotated() {
        val rotated = listOf(loop[2],loop[3],loop[0],loop[1],loop[2])
        val drawing = RouteDrawing(preview.copy(scheduledPaths=listOf(row,rotated)))
        val reveal = drawing.reveal(34.0 / 51.0)
        assertEquals(row, reveal.runs.single())
        assertEquals(listOf(loop[2],loop[3],Point(-1.0,1.0)), reveal.borders.single())
        val done = drawing.reveal(1.0)
        assertEquals(listOf(row), done.runs); assertEquals(listOf(rotated), done.borders); assertNull(done.tip)
    }

    @Test fun durationStaysShortForLargeLawns() {
        assertEquals(900, RouteDrawing.durationMillis(0.0))
        assertEquals(3200, RouteDrawing.durationMillis(5000.0))
    }
}
