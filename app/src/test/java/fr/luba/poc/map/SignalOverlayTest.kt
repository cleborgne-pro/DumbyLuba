package fr.luba.poc.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalOverlayTest {
    private val square = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))
    private val map = RobotMap(listOf(MapShape(1, 0, square, "Jardin")), true, "Bluetooth")
    /** Same garden read again (account map): other fingerprint, same frame. */
    private val cloudMap = RobotMap(listOf(MapShape(7, 0, square.map { Point(it.x + 0.01, it.y) }, "Jardin")), true, "Compte")
    private val elsewhere = RobotMap(listOf(MapShape(1, 0, square.map { Point(it.x + 200, it.y + 200) })), true)
    private fun sample(x: Double, y: Double) = SignalSample(Point(x, y), 1L, -60, null, null, 0, 20)
    private fun live(m: RobotMap, vararg points: Pair<Double, Double>) = SignalSurvey("robot", SignalCoverage.mapKey(m), listOf(1),
        points.map { sample(it.first, it.second) }, sessionId = "live-1", startedAt = 1, live = true)

    @After fun reset() { SignalOverlay.clear(); SignalOverlay.consumeReveal() }

    @Test fun `continuous map of the same map shown`() {
        val survey = live(map, 1.0 to 1.0)
        assertSame(survey, SignalOverlay.resolve(map, survey, null))
    }

    @Test fun `account map in the same frame shows the continuous map`() {
        val survey = live(map, 1.0 to 1.0, 5.0 to 5.0, 9.0 to 9.0)
        assertSame(survey, SignalOverlay.resolve(cloudMap, survey, null))
    }

    @Test fun `survey of another map not overlaid`() {
        assertNull(SignalOverlay.resolve(elsewhere, live(map, 1.0 to 1.0, 5.0 to 5.0), null))
    }

    @Test fun `without readings or map nothing to show`() {
        assertNull(SignalOverlay.resolve(map, live(map), null))
        assertNull(SignalOverlay.resolve(RobotMap(), live(map, 1.0 to 1.0), null))
    }

    @Test fun `survey from the history takes priority over the continuous map`() {
        val pinned = live(elsewhere, 201.0 to 201.0).copy(sessionId = "dedie", live = false)
        assertSame(pinned, SignalOverlay.resolve(map, live(map, 1.0 to 1.0), pinned))
    }

    @Test fun `majority within the extent with a 2 m margin`() {
        assertTrue(SignalOverlay.covers(map, listOf(sample(11.5, 5.0), sample(-1.5, 3.0), sample(50.0, 50.0))))
        assertFalse(SignalOverlay.covers(map, listOf(sample(12.5, 5.0), sample(50.0, 50.0), sample(5.0, 5.0))))
    }

    @Test fun `show on map opens the layers only once`() {
        SignalOverlay.show(null)
        assertTrue(SignalOverlay.consumeReveal())
        assertFalse(SignalOverlay.consumeReveal())
        assertNull(SignalOverlay.pinned.value)
    }

    @Test fun `short label of the surveys`() {
        assertEquals("Relevé", surveyShortLabel(SignalSurvey()))
        assertTrue(surveyShortLabel(live(map).copy(startedAt = 1_700_000_000_000)).startsWith("Carte continue du "))
    }
}
