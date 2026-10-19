package fr.luba.poc.simulation

import fr.luba.poc.map.DisplayPose
import fr.luba.poc.map.Point
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-place pivot shown on the map: the published point (8 cm behind the rotation centre) describes a circle, so the
 * silhouette drawn on it turned around a point in front of it, the rear sweeping as if it were skidding. Centred on the
 * rotation centre, it stays still during the whole pivot.
 */
class RotationCenterTest {
    @Test fun pivotKeepsTheDisplayedCenterStill() {
        val center = Point(-38.5, -4.6)
        for (heading in 0 until 360 step 15) {
            val rad = Math.toRadians(heading.toDouble())
            val published = Point(center.x - DeadReckoning.PUBLISHED_POINT_BEHIND_M * kotlin.math.cos(rad),
                center.y - DeadReckoning.PUBLISHED_POINT_BEHIND_M * kotlin.math.sin(rad))
            val shown = DisplayPose.published(published, heading.toDouble())
            assertEquals("heading $heading°", center.x, shown.point.x, 1e-9)
            assertEquals("heading $heading°", center.y, shown.point.y, 1e-9)
            assertEquals(heading.toDouble(), shown.heading!!, 0.0)
        }
    }

    @Test fun unknownHeadingKeepsThePublishedPoint() {
        val p = Point(1.0, 2.0)
        assertEquals(p, DeadReckoning.rotationCenter(p, null))
        assertEquals(p, DeadReckoning.rotationCenter(p, Double.NaN))
    }

    @Test fun sameCenterAsTheFollower() {
        val reckoning = DeadReckoning(publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M)
        val p = Point(3.0, -1.0)
        assertEquals(reckoning.center(p, 37.0), DeadReckoning.rotationCenter(p, 37.0))
    }
}
