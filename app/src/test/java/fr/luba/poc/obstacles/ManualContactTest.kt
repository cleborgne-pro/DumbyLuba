package fr.luba.poc.obstacles

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class ManualContactTest {
    private val luba = RobotEnvelope.LUBA2

    @Test fun `object touched going forward sits in front of the bumper`() {
        val c = ManualContact.obstacleCenter(Point(1.0, 2.0), 0.0, reversing = false)
        assertEquals(1.0 + luba.frontM + ManualContact.CONTACT_RADIUS_M, c.x, 1e-9)
        assertEquals(2.0, c.y, 1e-9)
        val north = ManualContact.obstacleCenter(Point(0.0, 0.0), 90.0, reversing = false)
        assertEquals(0.0, north.x, 1e-9)
        assertEquals(luba.frontM + ManualContact.CONTACT_RADIUS_M, north.y, 1e-9)
    }

    @Test fun `object touched while reversing sits behind the chassis`() {
        val c = ManualContact.obstacleCenter(Point(0.0, 0.0), 0.0, reversing = true)
        assertEquals(-(luba.rearM + ManualContact.CONTACT_RADIUS_M), c.x, 1e-9)
        assertEquals(0.0, c.y, 1e-9)
    }

    @Test fun `without heading the object is placed on the robot`() {
        assertEquals(Point(3.0, 4.0), ManualContact.obstacleCenter(Point(3.0, 4.0), null, reversing = false))
        assertEquals(Point(3.0, 4.0), ManualContact.obstacleCenter(Point(3.0, 4.0), Double.NaN, reversing = true))
    }

    @Test fun `collision lock or bumper bits are a suspected contact, other protections are not`() {
        assertTrue(ManualContact.suspected(25L, 0))
        assertTrue(ManualContact.suspected(0L, 1))
        assertTrue(ManualContact.suspected(null, 4))
        assertFalse(ManualContact.suspected(0L, 0))
        assertFalse(ManualContact.suspected(11L, 0))
        assertFalse(ManualContact.suspected(null, null))
    }

    @Test fun `pointed obstacle touches the bumper by default and follows the chosen distance`() {
        val pose = TeachContact(0.0, 0.0, 0.0, 0L)
        val p = TeachSafety.pointedProposal(pose, 0.3)
        assertEquals(luba.frontM + 0.3, requireNotNull(p.aheadM), 1e-9)
        assertEquals(luba.frontM + 0.3, p.estimated.x, 1e-9)
        // Same position as an obstacle learnt by contact with the same radius.
        assertEquals(TeachObstacle.estimateCenter(pose, 0.3).x, p.estimated.x, 1e-9)
        assertEquals(2.0, p.center(0.3, 2.0).x, 1e-9)
        assertEquals(0.0, p.center(0.3, 2.0).y, 1e-9)
    }

    @Test fun `contact proposal keeps its centre estimated from the radius`() {
        val pose = TeachContact(0.0, 0.0, 90.0, 0L)
        val p = TeachSafety.proposal(pose, 0.2)
        assertNull(p.aheadM)
        assertEquals(TeachObstacle.estimateCenter(pose, 0.5).y, p.center(0.5).y, 1e-9)
    }
}
