package fr.luba.poc.camera

import androidx.compose.ui.Alignment
import org.junit.Assert.*
import org.junit.Test

/** Floating camera window: movement bounded to the screen, resizing from the corner opposite the anchor. */
class CameraWindowTest {
    private val g = CameraWindowGeometry

    @Test fun offsetKeepsTheWindowInsideTheScreen() {
        // Screen 400 × 800, window 168 × 94.5 anchored at the top right (x = 176, y = 72), margin 4.
        val (x, y) = g.clampOffset(dx = 500f, dy = -500f, baseX = 176f, baseY = 72f, width = 168f, height = 94.5f,
            parentWidth = 400f, parentHeight = 800f, margin = 4f)
        assertEquals(400f - 4f - 168f - 176f, x, 0.01f) // stuck to the right edge
        assertEquals(4f - 72f, y, 0.01f) // stuck to the top
        val (lx, by) = g.clampOffset(-1000f, 1000f, 176f, 72f, 168f, 94.5f, 400f, 800f, 4f)
        assertEquals(4f - 176f, lx, 0.01f)
        assertEquals(800f - 4f - 94.5f - 72f, by, 0.01f)
    }

    @Test fun offsetInsideTheScreenIsKept() {
        assertEquals(-50f to 120f, g.clampOffset(-50f, 120f, 176f, 72f, 168f, 94.5f, 400f, 800f, 4f))
    }

    @Test fun windowLargerThanTheScreenSticksToTheTopLeft() {
        val (x, y) = g.clampOffset(30f, 30f, baseX = 10f, baseY = 10f, width = 500f, height = 300f, parentWidth = 400f, parentHeight = 200f, margin = 4f)
        assertEquals(-6f, x, 0.01f)
        assertEquals(-6f, y, 0.01f)
    }

    @Test fun widthStaysBetweenMinimumAndScreen() {
        val max = g.maxWidth(parentWidth = 400f, parentHeight = 800f, margin = 4f)
        assertEquals(392f, max, 0.01f)
        // Landscape screen: the height is the limit (16/9).
        assertEquals((300f - 8f) * 16f / 9f, g.maxWidth(parentWidth = 900f, parentHeight = 300f, margin = 4f), 0.01f)
        assertEquals(392f, g.clampWidth(1000f, max), 0.01f)
        assertEquals(g.MIN_WIDTH.value, g.clampWidth(10f, max), 0.01f)
        // Screen smaller than the minimum width: never below the minimum.
        assertEquals(g.MIN_WIDTH.value, g.clampWidth(200f, g.maxWidth(80f, 60f)), 0.01f)
    }

    @Test fun resizeHandleIsOppositeToTheAnchor() {
        // Map: anchored at the top right, handle at the bottom left, pulling left or down enlarges.
        assertEquals(-1 to 1, g.growDirection(Alignment.TopEnd))
        assertEquals(200f, g.resize(168f, dx = -32f, dy = 0f, growX = -1, growY = 1), 0.01f)
        assertEquals(168f + 9f * 16f / 9f, g.resize(168f, dx = 0f, dy = 9f, growX = -1, growY = 1), 0.01f)
        assertEquals(148f, g.resize(168f, dx = 20f, dy = 2f, growX = -1, growY = 1), 0.01f)
        // Other screens: anchored at the bottom left, handle at the top right, pulling right or up enlarges.
        assertEquals(1 to -1, g.growDirection(Alignment.BottomStart))
        assertEquals(152f, g.resize(132f, dx = 20f, dy = 0f, growX = 1, growY = -1), 0.01f)
        assertEquals(132f + 16f, g.resize(132f, dx = 0f, dy = -9f, growX = 1, growY = -1), 0.01f)
    }

    @Test fun handleZoneIsTheCornerOppositeToTheAnchor() {
        // Window 168 × 94.5 anchored at the top right: 32 dp handle at the bottom left.
        assertTrue(g.onHandle(10f, 90f, 168f, 94.5f, Alignment.TopEnd, 32f))
        assertFalse(g.onHandle(150f, 90f, 168f, 94.5f, Alignment.TopEnd, 32f))
        assertFalse(g.onHandle(10f, 10f, 168f, 94.5f, Alignment.TopEnd, 32f))
        // Anchored at the bottom left: handle at the top right.
        assertTrue(g.onHandle(160f, 5f, 168f, 94.5f, Alignment.BottomStart, 32f))
        assertFalse(g.onHandle(10f, 90f, 168f, 94.5f, Alignment.BottomStart, 32f))
    }
}
