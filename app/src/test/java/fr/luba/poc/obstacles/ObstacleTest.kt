package fr.luba.poc.obstacles

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ObstacleTest {

    private val robot = RobotEnvelope.LUBA2

    @Test fun `known kinds with suggested radius and fallback to other`() {
        assertEquals(
            listOf("Pied de balançoire", "Arbre", "Poteau", "Trou", "Branche basse", "Autre"),
            ObstacleKind.entries.map { it.label },
        )
        ObstacleKind.entries.forEach { assertTrue(it.defaultRadiusM > 0) }
        assertEquals(ObstacleKind.OTHER, ObstacleKind.from("INTRUS"))
        assertEquals(ObstacleKind.TREE, ObstacleKind.from("TREE"))
    }

    @Test fun `obstacle stores position radius margin kind and history`() {
        val created = LocalObstacle("o1", ObstacleKind.TREE, 3.2, -1.5, radiusM = 0.30, marginM = 0.20, createdAtMs = 10)
        assertEquals(3.2, created.x, 0.0)
        assertEquals(-1.5, created.y, 0.0)
        assertEquals(0.30, created.radiusM, 0.0)
        assertEquals(0.20, created.marginM, 0.0)
        assertEquals(ObstacleKind.TREE, created.kind)
        assertEquals(0.50, created.exclusionRadiusM, 1e-9)
        assertTrue(created.incidents.isEmpty())
        val withIncident = created.withIncident(ObstacleIncident(atMs = 50, kind = "Contact bumper", note = "côté droit"))
        assertEquals(1, withIncident.incidents.size)
        assertEquals("Contact bumper", withIncident.incidents.first().kind)
        assertEquals(50L, withIncident.updatedAtMs)
        assertEquals(10L, withIncident.createdAtMs)
    }

    @Test fun `manual edit of position radius and margin`() {
        val created = LocalObstacle("o1", ObstacleKind.POLE, 1.0, 1.0, radiusM = 0.10, marginM = 0.10, createdAtMs = 10, updatedAtMs = 10)
        val edited = created.edited(x = 2.0, y = 3.0, radiusM = 0.45, marginM = 0.35, kind = ObstacleKind.HOLE, label = "trou nord", atMs = 99)
        assertEquals(2.0, edited.x, 0.0)
        assertEquals(3.0, edited.y, 0.0)
        assertEquals(0.45, edited.radiusM, 0.0)
        assertEquals(0.35, edited.marginM, 0.0)
        assertEquals(ObstacleKind.HOLE, edited.kind)
        assertEquals("trou nord", edited.label)
        assertEquals(99L, edited.updatedAtMs)
        assertEquals("o1", edited.id)
    }

    @Test fun `out of bounds values refused`() {
        val scenarios = listOf<() -> Unit>(
            { LocalObstacle(" ", ObstacleKind.TREE, 0.0, 0.0, radiusM = 0.3, marginM = 0.1) },
            { LocalObstacle("o1", ObstacleKind.TREE, Double.NaN, 0.0, radiusM = 0.3, marginM = 0.1) },
            { LocalObstacle("o1", ObstacleKind.TREE, 0.0, 0.0, radiusM = LocalObstacle.MIN_RADIUS_M - 0.01, marginM = 0.1) },
            { LocalObstacle("o1", ObstacleKind.TREE, 0.0, 0.0, radiusM = LocalObstacle.MAX_RADIUS_M + 0.01, marginM = 0.1) },
            { LocalObstacle("o1", ObstacleKind.TREE, 0.0, 0.0, radiusM = 0.3, marginM = -0.01) },
            { LocalObstacle("o1", ObstacleKind.TREE, 0.0, 0.0, radiusM = 0.3, marginM = LocalObstacle.MAX_MARGIN_M + 0.01) },
            { LocalObstacle("o1", ObstacleKind.TREE, 0.0, 0.0, radiusM = 0.3, marginM = 0.1, label = "x".repeat(121)) },
        )
        scenarios.forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
    }

    @Test fun `luba2 envelope actual volume and turns`() {
        assertEquals(0.69, robot.lengthM, 0.0)
        assertEquals(0.513, robot.widthM, 0.0)
        assertEquals(0.2565, robot.halfWidthM, 1e-9)
        assertEquals(hypot(0.345, 0.2565), robot.turnClearanceM, 1e-9)
        assertTrue(robot.turnClearanceM > robot.halfWidthM)
    }

    @Test fun `reserve disc includes radius margin and turning envelope`() {
        val obstacle = LocalObstacle("o1", ObstacleKind.POLE, 5.0, 6.0, radiusM = 0.10, marginM = 0.20)
        val disc = obstacle.keepOutDisc(robot)
        assertEquals(5.0, disc.x, 0.0)
        assertEquals(6.0, disc.y, 0.0)
        assertEquals(0.10 + 0.20 + robot.turnClearanceM, disc.radiusM, 1e-9)
    }

    @Test fun `teach estimate places the obstacle in front of the bumper`() {
        val contact = TeachContact(x = 10.0, y = 20.0, headingDeg = 0.0, atMs = 5)
        val center = TeachObstacle.estimateCenter(contact, radiusM = 0.25)
        assertEquals(10.0 + robot.frontM + 0.25, center.x, 1e-9)
        assertEquals(20.0, center.y, 1e-9)
        val north = TeachObstacle.estimateCenter(contact.copy(headingDeg = 90.0), radiusM = 0.25)
        assertEquals(10.0, north.x, 1e-9)
        assertEquals(20.0 + robot.frontM + 0.25, north.y, 1e-9)
        val back = TeachObstacle.estimateCenter(contact.copy(headingDeg = 180.0), radiusM = 0.25)
        assertTrue(back.x < 10.0)
    }

    @Test fun `teach estimate refuses invalid measurement or radius`() {
        val contact = TeachContact(x = 0.0, y = 0.0, headingDeg = 45.0, atMs = 1)
        assertThrows(IllegalArgumentException::class.java) { TeachObstacle.estimateCenter(contact, radiusM = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { TeachObstacle.estimateCenter(contact, radiusM = 9.0) }
        assertThrows(IllegalArgumentException::class.java) { TeachContact(Double.NaN, 0.0, 0.0, 1) }
    }

    @Test fun `incident refuses too long note or invalid position`() {
        assertThrows(IllegalArgumentException::class.java) { ObstacleIncident(1, "Contact", note = "x".repeat(501)) }
        assertThrows(IllegalArgumentException::class.java) { ObstacleIncident(1, " ", note = "") }
        assertThrows(IllegalArgumentException::class.java) { ObstacleIncident(1, "Contact", x = Double.POSITIVE_INFINITY) }
    }

    @Test fun `invalid reserve disc refused`() {
        assertThrows(IllegalArgumentException::class.java) { ObstacleDisc(0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ObstacleDisc(Double.NaN, 0.0, 1.0) }
        assertEquals(Point(1.0, 2.0), ObstacleDisc(1.0, 2.0, 0.5).center())
    }
}
