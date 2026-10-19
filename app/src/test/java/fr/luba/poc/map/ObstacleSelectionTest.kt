package fr.luba.poc.map

import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import org.junit.Assert.*
import org.junit.Test

/** Selecting an obstacle on the map and sizing its exclusion zone (2026-10-15: post hit). */
class ObstacleSelectionTest {
    private val pole = LocalObstacle("pole", ObstacleKind.POLE, 10.0, 5.0, radiusM = 0.1, marginM = 1.0)
    private val contact = DifficultyEvent("c", 1_000_000L, DifficultyKind.CONTACT, 2.5, 2.0, obstacleX = 3.0, obstacleY = 2.0)

    @Test fun registeredObstacleIsPickedInsideItsExclusionZone() {
        // 1.1 m excluded zone: a tap 0.9 m from the center selects the post, even at high zoom.
        val hit = ObstacleSelection.pick(Point(10.9, 5.0), 400.0, emptyList(), emptyList(), listOf(pole), encountersVisible = true)
        assertEquals("pole", (hit as MapPick.Obstacle).obstacle.id)
        assertNull(ObstacleSelection.pick(Point(12.0, 5.0), 400.0, emptyList(), emptyList(), listOf(pole), encountersVisible = true))
    }

    @Test fun registeredObstaclesStaySelectableWithTheEncountersLayerHidden() {
        assertNotNull(ObstacleSelection.pick(Point(10.0, 5.2), 40.0, listOf(contact), emptyList(), listOf(pole), encountersVisible = false))
        assertNull(ObstacleSelection.pick(Point(3.0, 2.0), 40.0, listOf(contact), emptyList(), listOf(pole), encountersVisible = false))
    }

    @Test fun nearestElementWinsBetweenAContactAndAnObstacle() {
        val near = pole.copy(x = 3.6, y = 2.0)
        val onContact = ObstacleSelection.pick(Point(3.05, 2.0), 40.0, listOf(contact), emptyList(), listOf(near), encountersVisible = true)
        assertEquals("c", (onContact as MapPick.Encounter).event.id)
        val onPole = ObstacleSelection.pick(Point(3.55, 2.0), 40.0, listOf(contact), emptyList(), listOf(near), encountersVisible = true)
        assertEquals("pole", (onPole as MapPick.Obstacle).obstacle.id)
    }

    @Test fun presumedCircleOpensItsContact() {
        val presumed = LocalObstacle("presume-c", ObstacleKind.OTHER, 3.1, 2.0, radiusM = 0.5, marginM = 0.3)
        val hit = ObstacleSelection.pick(Point(3.7, 2.3), 200.0, listOf(contact), listOf(presumed), emptyList(), encountersVisible = true)
        assertEquals("c", (hit as MapPick.Encounter).event.id)
    }

    @Test fun contactIsRegisteredWithTheChosenExclusionSize() {
        val o = ObstacleSelection.fromEncounter(contact, emptyList(), ObstacleKind.POLE, 0.1, 1.0, "id", 5L, "15/10/2026 11:00")
        assertEquals(ObstacleKind.POLE, o.kind)
        assertEquals(3.0, o.x, 1e-9)
        assertEquals(0.1, o.radiusM, 1e-9)
        assertEquals(1.0, o.marginM, 1e-9)
        assertEquals(1.1, o.exclusionRadiusM, 1e-9)
        // Several contacts at the same place: center and radius of the presumed obstacle.
        val presumed = LocalObstacle("presume-c", ObstacleKind.OTHER, 3.2, 2.1, radiusM = 0.45, marginM = 0.3)
        val grouped = ObstacleSelection.fromEncounter(contact, listOf(presumed), ObstacleKind.TREE, 0.45, 0.5, "id", 5L, "d")
        assertEquals(3.2, grouped.x, 1e-9)
        assertEquals(2.1, grouped.y, 1e-9)
        assertEquals(0.45, ObstacleSelection.defaultRadius(contact, listOf(presumed)), 1e-9)
        assertEquals(fr.luba.poc.simulation.ObstacleBypass.CONTACT_RADIUS_M, ObstacleSelection.defaultRadius(contact, emptyList()), 1e-9)
    }

    @Test fun sizesAreClampedToTheObstacleLimits() {
        assertEquals(LocalObstacle.MIN_RADIUS_M, ObstacleSelection.clampRadius(0.0), 1e-9)
        assertEquals(LocalObstacle.MAX_MARGIN_M, ObstacleSelection.clampMargin(5.0), 1e-9)
        assertEquals(0.35, ObstacleSelection.clampMargin(0.3499), 1e-9)
        assertTrue(ObstacleSelection.exclusionLabel(0.1, 1.0).startsWith("1,10 m autour du centre"))
    }

    @Test fun tapOnARegisteredObstacleOpensItsSheet() {
        MapExtraLayers.close()
        MapExtraLayers.latest = DifficultyUi()
        MapExtraLayers.latestObstacles = listOf(pole)
        try {
            assertTrue(MapExtraLayers.tap(Point(10.3, 5.0), scale = 40.0))
            assertEquals("pole", MapExtraLayers.openedObstacle.value)
            assertNull(MapExtraLayers.openedEncounter.value)
            MapExtraLayers.close()
            assertNull(MapExtraLayers.openedObstacle.value)
        } finally { MapExtraLayers.latestObstacles = emptyList() }
    }
}
