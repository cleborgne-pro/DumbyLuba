package fr.luba.poc.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObstaclePhotoStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Test fun `saves the photo with the contact context and a mirror copy`() {
        val mirror = temp.newFolder("mirror")
        val store = ObstaclePhotoStore(temp.root, mirror)
        val photo = store.save(jpeg, takenAt = 20_000, contactAt = 5_000, code = 1014, x = 1.234, y = -0.5)
        assertEquals(1014, photo.code); assertEquals(1.234, photo.x!!, 1e-9); assertEquals(-0.5, photo.y!!, 1e-9)
        assertEquals(15L, photo.delaySeconds)
        assertTrue(photo.file.path.contains("diagnostics/obstacle-photos"))
        assertArrayEquals(jpeg, photo.file.readBytes())
        assertTrue(java.io.File(mirror, photo.file.name).exists())
        assertEquals(listOf(photo.file.name), store.list().map { it.file.name })
    }

    @Test fun `unknown position kept as unknown`() {
        val photo = ObstaclePhotoStore(temp.root).save(jpeg, 2, 1, null, null, Double.NaN)
        assertNull(photo.code); assertNull(photo.x); assertNull(photo.y)
    }

    @Test fun `refuses an image that is not a JPEG`() {
        val store = ObstaclePhotoStore(temp.root)
        assertThrows(IllegalArgumentException::class.java) { store.save(byteArrayOf(1, 2, 3), 1, 1, 1014, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { store.save(ByteArray(0), 1, 1, 1014, 0.0, 0.0) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun `keeps the most recent photos`() {
        val store = ObstaclePhotoStore(temp.root)
        repeat(ObstaclePhotoStore.MAX_PHOTOS + 5) { store.save(jpeg, 1_000_000L + it, 1_000L, 1014, 0.0, 0.0) }
        val photos = store.list()
        assertEquals(ObstaclePhotoStore.MAX_PHOTOS, photos.size)
        assertEquals(1_000_000L + ObstaclePhotoStore.MAX_PHOTOS + 4, photos.first().takenAt)
    }

    @Test fun `photos near an obstacle`() {
        val store = ObstaclePhotoStore(temp.root)
        store.save(jpeg, 10, 1, 1014, 2.0, 0.0); store.save(jpeg, 11, 1, 1014, 5.0, 0.0)
        val near = store.list().near(2.4, 0.0, reachM = 0.3)
        assertEquals(listOf(2.0), near.map { it.x })
    }
}
