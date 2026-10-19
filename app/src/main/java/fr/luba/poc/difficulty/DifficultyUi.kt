package fr.luba.poc.difficulty

import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle

/**
 * Published state of the difficulty log: events (oldest first), presumed obstacles (repeated contacts), difficult
 * areas avoided by transits, and the settings controlling how they are taken into account.
 */
data class DifficultyUi(
    val events: List<DifficultyEvent> = emptyList(),
    val presumed: List<LocalObstacle> = emptyList(),
    val hotAreas: List<List<Point>> = emptyList(),
    /** Heatmap cells (1 m), computed on publication rather than on every map frame. */
    val cells: List<DifficultyCell> = emptyList(),
    /** Presumed obstacles count as obstacles when routes are prepared. */
    val avoidPresumed: Boolean = true,
    /** Transits with blades stopped bypass difficult areas when the detour stays reasonable. */
    val avoidHot: Boolean = true,
    val saveError: Boolean = false,
) {
    /** Bumper contacts (obstacles encountered), most recent first. */
    val encounters: List<DifficultyEvent> get() = events.filter { it.kind == DifficultyKind.CONTACT && !it.dismissed }.asReversed()
    val transitAvoidance: List<List<Point>> get() = if (avoidHot) hotAreas else emptyList()

    companion object {
        /** Recomputes presumed obstacles and difficult areas for [events] and the saved obstacles [known]. */
        fun derive(base: DifficultyUi, events: List<DifficultyEvent>, known: List<LocalObstacle>, now: Long,
                   planning: Boolean = true): DifficultyUi {
            val cells = DifficultyField(events, now).cells()
            if (!planning) return base.copy(events = events, cells = cells)
            return base.copy(events = events, presumed = PresumedObstacles.obstacles(events, known, now), cells = cells,
                hotAreas = DifficultyField.hotAreas(cells))
        }
    }
}
