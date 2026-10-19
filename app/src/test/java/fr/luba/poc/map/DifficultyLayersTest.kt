package fr.luba.poc.map

import fr.luba.poc.camera.ObstaclePhoto
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Encountered obstacles layer: photo linked to the contact, sheet opened on tap. */
class DifficultyLayersTest {
    private val contact = DifficultyEvent("c", 1_000_000L, DifficultyKind.CONTACT, 4.5, 2.0, obstacleX = 5.0, obstacleY = 2.0,
        contactAtMs = 999_000L)

    private fun photo(contactAt: Long) = ObstaclePhoto(File("p_$contactAt.jpg"), contactAt + 20_000, contactAt, 1014, 4.5, 2.0)

    @Test fun photoIsLinkedByContactTimeThenByNearestTime() {
        assertEquals(999_000L, MapExtraLayers.photoFor(contact, listOf(photo(1_050_000L), photo(999_000L)))!!.contactAt)
        assertEquals(1_050_000L, MapExtraLayers.photoFor(contact, listOf(photo(1_050_000L), photo(500_000L)))!!.contactAt)
        assertNull(MapExtraLayers.photoFor(contact, listOf(photo(500_000L))))
    }

    @Test fun tapOpensTheNearestEncounterWithinReach() {
        MapExtraLayers.close()
        MapExtraLayers.latest = DifficultyUi(events = listOf(contact, contact.copy(id = "d", dismissed = true, obstacleX = 9.0)))
        assertFalse("too far", MapExtraLayers.tap(Point(7.0, 2.0), scale = 40.0))
        assertNull(MapExtraLayers.openedEncounter.value)
        assertFalse("dismissed contact ignored", MapExtraLayers.tap(Point(9.0, 2.0), scale = 40.0))
        assertTrue(MapExtraLayers.tap(Point(5.3, 2.1), scale = 40.0))
        assertEquals("c", MapExtraLayers.openedEncounter.value?.id)
        MapExtraLayers.close()
        assertNull(MapExtraLayers.openedEncounter.value)
    }
}
