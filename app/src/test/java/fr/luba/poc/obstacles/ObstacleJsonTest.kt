package fr.luba.poc.obstacles

import org.junit.Assert.*
import org.junit.Test

class ObstacleJsonTest {

    private val tree = LocalObstacle(
        id = "o1", kind = ObstacleKind.TREE, x = 3.25, y = -1.5, radiusM = 0.30, marginM = 0.20,
        incidents = listOf(
            ObstacleIncident(atMs = 50, kind = "Contact bumper", note = "côté droit", x = 3.1, y = -1.4),
            ObstacleIncident(atMs = 90, kind = "Observation", note = "racine apparente"),
        ),
        createdAtMs = 10, updatedAtMs = 90, label = "vieux chêne",
    )

    @Test fun `round trip keeps position radius margin kind and history`() {
        val decoded = ObstacleJson.decode(ObstacleJson.encode(listOf(tree, tree.copy(id = "o2", kind = ObstacleKind.OTHER))))
        assertEquals(2, decoded.size)
        assertEquals(tree, decoded[0])
        assertEquals(ObstacleKind.OTHER, decoded[1].kind)
        assertEquals(2, decoded[0].incidents.size)
        assertNull(decoded[0].incidents[1].x)
        assertEquals(3.1, decoded[0].incidents[0].x!!, 0.0)
    }

    @Test fun `empty list encoded and read back`() {
        assertEquals(emptyList<LocalObstacle>(), ObstacleJson.decode(ObstacleJson.encode(emptyList())))
    }

    @Test fun `unknown storage version refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleJson.decode("""{"schemaVersion":2,"obstacles":[]}""")
        }
        assertThrows(Exception::class.java) { ObstacleJson.decode("pas du json") }
    }

    @Test fun `invalid values refused on read`() {
        val bad = """{"schemaVersion":1,"obstacles":[{"id":"o1","kind":"TREE","x":0.0,"y":0.0,"radiusM":9.9,"marginM":0.1,"incidents":[]}]}"""
        assertThrows(IllegalArgumentException::class.java) { ObstacleJson.decode(bad) }
        val badIncident = """{"schemaVersion":1,"obstacles":[{"id":"o1","kind":"TREE","x":0.0,"y":0.0,"radiusM":0.3,"marginM":0.1,"incidents":[{"atMs":1,"kind":" "}]}]}"""
        assertThrows(IllegalArgumentException::class.java) { ObstacleJson.decode(badIncident) }
    }

    @Test fun `unknown kind falls back to other`() {
        val unknown = """{"schemaVersion":1,"obstacles":[{"id":"o1","kind":"BANANA","x":1.0,"y":2.0,"radiusM":0.3,"marginM":0.1,"incidents":[]}]}"""
        assertEquals(ObstacleKind.OTHER, ObstacleJson.decode(unknown).single().kind)
    }

    @Test fun `too many obstacles refused`() {
        val many = List(ObstacleJson.MAX_OBSTACLES + 1) { index ->
            LocalObstacle("o$index", ObstacleKind.POLE, 0.0, 0.0, radiusM = 0.1, marginM = 0.1)
        }
        assertThrows(IllegalArgumentException::class.java) { ObstacleJson.encode(many) }
    }
}
