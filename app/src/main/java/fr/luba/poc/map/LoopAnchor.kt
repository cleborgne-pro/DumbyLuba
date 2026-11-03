package fr.luba.poc.map

import fr.luba.poc.i18n.tr

/**
 * Origin of a border loop on a recorded boundary ([CoveragePlanner.plan]): the first vertex where the robot can pivot
 * whole without collision, so that the loop is entered and left there; otherwise a tangent entry in the middle of its
 * longest segment.
 */
internal object LoopAnchor {
    fun anchor(path: List<Point>, envelope: TrajectoryEnvelope): List<Point> {
        val vertices = path.dropLast(1)
        val index = vertices.indexOfFirst { p -> envelope.turnCollision(p,0.0,180.0) == null && envelope.turnCollision(p,180.0,360.0) == null }
        if (index >= 0) {
            val rotated = vertices.drop(index) + vertices.take(index)
            return rotated + rotated.first()
        }
        // A loop may be drivable without allowing a full turn while stopped.
        // Tangent entry in the middle of the longest segment; the engine will check the real link.
        val edge = vertices.indices.maxByOrNull { i -> vertices[i].distance(vertices[(i+1)%vertices.size]) }
            ?: error(tr("Empty border", "Bordure vide"))
        val a = vertices[edge]; val b = vertices[(edge+1)%vertices.size]
        val entry = Point((a.x+b.x)/2, (a.y+b.y)/2)
        return listOf(entry) + vertices.drop(edge+1) + vertices.take(edge+1) + entry
    }
}
