package fr.luba.poc.manual

import fr.luba.poc.map.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivenViewTest {
    @Test fun headingUpBringsTheNoseToTheTop() {
        assertEquals(0f, DrivenView.rotation(false, 30.0), 0f)
        assertEquals(0f, DrivenView.rotation(true, null), 0f)
        assertEquals(0f, DrivenView.rotation(true, Double.NaN), 0f)
        // Heading 90° (north) is already up; heading 0° (east) turns the map by -90°.
        assertEquals(0f, DrivenView.rotation(true, 90.0), 0f)
        assertEquals(-90f, DrivenView.rotation(true, 0.0), 0f)
    }

    @Test fun focusIsDrawnAtTheAnchor() {
        val focus = Point(3.0, 4.0)
        assertEquals(200f to 300f, DrivenView.screen(focus, focus, 200f, 300f, 50f))
        // One metre north = 50 px up on the screen.
        assertEquals(200f to 250f, DrivenView.screen(Point(3.0, 5.0), focus, 200f, 300f, 50f))
    }

    @Test fun panningMovesTheMapUnderTheFinger() {
        val focus = Point(0.0, 0.0)
        // North up: dragging 100 px to the right shows what is 2 m further west.
        val right = DrivenView.panned(focus, 100f, 0f, 50f, 0f)
        assertEquals(-2.0, right.x, 1e-9); assertEquals(0.0, right.y, 1e-9)
        // Dragging down shows what is further north.
        assertEquals(2.0, DrivenView.panned(focus, 0f, 100f, 50f, 0f).y, 1e-9)
        // Map turned by 90° clockwise: a drag to the right moves the content along the map's north axis.
        val turned = DrivenView.panned(focus, 100f, 0f, 50f, 90f)
        assertEquals(0.0, turned.x, 1e-9); assertEquals(-2.0, turned.y, 1e-9)
        // The point under the finger after the drag is the one that was there before.
        val anchor = 200f to 300f
        val before = DrivenView.screen(Point(1.0, 1.0), focus, anchor.first, anchor.second, 50f)
        val after = DrivenView.screen(Point(1.0, 1.0), DrivenView.panned(focus, 30f, -20f, 50f, 0f), anchor.first, anchor.second, 50f)
        assertEquals(before.first + 30f, after.first, 1e-3f); assertEquals(before.second - 20f, after.second, 1e-3f)
    }

    @Test fun zoomScaleAndFit() {
        assertEquals(DrivenView.MAX_DP_PER_M, DrivenView.zoomed(200f, 2f), 0f)
        assertEquals(DrivenView.MIN_DP_PER_M, DrivenView.zoomed(8f, 0.1f), 0f)
        assertEquals(36f, DrivenView.zoomed(36f, Float.NaN), 0f)
        assertEquals(2.0, DrivenView.scaleBarMeters(36f, 96f), 0.0)
        assertEquals(0.5, DrivenView.scaleBarMeters(240f, 96f), 0.0)
        assertEquals(10.0, DrivenView.scaleBarMeters(6f, 96f), 0.0)
        assertNull(DrivenView.fit(emptyList(), 400f, 300f))
        val (center, scale) = DrivenView.fit(listOf(Point(0.0, 0.0), Point(20.0, 4.0)), 400f, 300f)!!
        assertEquals(Point(10.0, 2.0), center)
        assertEquals(16f, scale, 1e-3f) // 20 m × 1.25 across 400 px
    }
}
