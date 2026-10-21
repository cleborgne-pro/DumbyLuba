package fr.luba.poc.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.PresumedObstacles
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.ObstacleBypass
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.ChipChoice
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.SliderRow
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.roundToInt

/** Map item chosen with a tap: bumper contact (detail sheet) or saved obstacle (exclusion size). */
sealed interface MapPick {
    data class Encounter(val event: DifficultyEvent) : MapPick
    data class Obstacle(val obstacle: LocalObstacle) : MapPick
}

/**
 * Selecting an obstacle on the map and sizing its exclusion zone (object radius + margin around it).
 *
 * Before 2026-10-15, only bumper contacts opened with a tap, and "Save the obstacle" kept the size of an estimated
 * contact (radius 0.2 m, margin 0.3 m): there was no way to ask for more clearance around a post the robot had just hit
 * without going through Service → Obstacles.
 */
object ObstacleSelection {
    /** Step of the size sliders (5 cm). */
    const val STEP_M = 0.05

    /**
     * Item tapped at [p] (meters) at scale [scale] (pixels per meter): the nearest among the contacts (and presumed
     * obstacles, opened on their nearest contact) if [encountersVisible], and the saved obstacles, tapped inside their
     * exclusion zone or within finger reach.
     */
    fun pick(p: Point, scale: Double, encounters: List<DifficultyEvent>, presumed: List<LocalObstacle>,
             obstacles: List<LocalObstacle>, encountersVisible: Boolean): MapPick? {
        val reach = maxOf(0.6, 28.0 / scale.coerceAtLeast(1e-6))
        val candidates = mutableListOf<Pair<Double, MapPick>>()
        if (encountersVisible) {
            encounters.forEach { e -> val d = e.point.distance(p); if (d <= reach) candidates += d to MapPick.Encounter(e) }
            presumed.forEach { o ->
                val center = Point(o.x, o.y)
                val d = center.distance(p)
                if (d <= maxOf(reach, o.exclusionRadiusM)) encounters.minByOrNull { it.point.distance(center) }
                    ?.takeIf { it.point.distance(center) <= PresumedObstacles.CLUSTER_M + o.radiusM }
                    ?.let { candidates += d to MapPick.Encounter(it) }
            }
        }
        obstacles.forEach { o -> val d = Point(o.x, o.y).distance(p); if (d <= maxOf(reach, o.exclusionRadiusM)) candidates += d to MapPick.Obstacle(o) }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Presumed obstacle grouping this contact (same place), if any. */
    fun presumedFor(event: DifficultyEvent, presumed: List<LocalObstacle>): LocalObstacle? =
        presumed.filter { Point(it.x, it.y).distance(event.point) <= PresumedObstacles.CLUSTER_M }
            .minByOrNull { Point(it.x, it.y).distance(event.point) }

    /**
     * Obstacle to save from a contact: center and radius of the presumed obstacle grouping it (several contacts at the
     * same place), otherwise the estimated contact position; type, radius and margin chosen in the sheet.
     */
    fun fromEncounter(event: DifficultyEvent, presumed: List<LocalObstacle>, kind: ObstacleKind, radiusM: Double,
                      marginM: Double, id: String, now: Long, date: String): LocalObstacle {
        val center = presumedFor(event, presumed)?.let { Point(it.x, it.y) } ?: event.point
        return LocalObstacle(id, kind, center.x, center.y, clampRadius(radiusM), clampMargin(marginM),
            createdAtMs = now, updatedAtMs = now, label = tr("Obstacle encountered on $date", "Obstacle rencontré le $date"))
    }

    /** Radius offered for a contact: that of the presumed obstacle, otherwise the radius of an estimated contact. */
    fun defaultRadius(event: DifficultyEvent, presumed: List<LocalObstacle>): Double =
        clampRadius(presumedFor(event, presumed)?.radiusM ?: ObstacleBypass.CONTACT_RADIUS_M)

    fun clampRadius(r: Double): Double = snap(r.coerceIn(LocalObstacle.MIN_RADIUS_M, LocalObstacle.MAX_RADIUS_M))
    fun clampMargin(m: Double): Double = snap(m.coerceIn(0.0, LocalObstacle.MAX_MARGIN_M))
    private fun snap(v: Double): Double = (v / STEP_M).roundToInt() * STEP_M

    /** Radius of the excluded zone (object + margin) and minimum distance between the obstacle center and the robot center. */
    fun exclusionLabel(radiusM: Double, marginM: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): String =
        tr("${Format.num(radiusM + marginM, 2)} m around the center · robot at least ${Format.num(radiusM + marginM + robot.turnClearanceM, 2)} m away",
            "${Format.num(radiusM + marginM, 2)} m autour du centre · robot à ${Format.num(radiusM + marginM + robot.turnClearanceM, 2)} m au moins")
}

/**
 * Type, object radius and margin around it: size of an obstacle's exclusion zone. The type offers its usual radius
 * (post 0.10 m, tree 0.30 m…), adjustable afterwards.
 */
@Composable
fun ColumnScope.ObstacleSizeEditor(kind: ObstacleKind, onKind: (ObstacleKind) -> Unit, radius: Float, onRadius: (Float) -> Unit,
                                   margin: Float, onMargin: (Float) -> Unit) {
    ChipChoice(ObstacleKind.entries.map { it to it.label }, kind, { onKind(it); onRadius(it.defaultRadiusM.toFloat()) })
    SliderRow(tr("Object radius", "Rayon de l'objet"), radius, onRadius, LocalObstacle.MIN_RADIUS_M.toFloat()..LocalObstacle.MAX_RADIUS_M.toFloat(),
        valueLabel = "${Format.num(radius.toDouble(), 2)} m", steps = 38)
    SliderRow(tr("Margin around the object", "Marge autour de l'objet"), margin, onMargin, 0f..LocalObstacle.MAX_MARGIN_M.toFloat(),
        valueLabel = "${Format.num(margin.toDouble(), 2)} m", steps = 39)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.3f, 0.5f, 1.0f).forEach { preset ->
            SecondaryPillButton(tr("Margin ${Format.num(preset.toDouble(), 1)} m", "Marge ${Format.num(preset.toDouble(), 1)} m"), { onMargin(preset) }, Modifier.weight(1f))
        }
    }
    KeyValueRow(tr("Excluded zone", "Zone exclue"), ObstacleSelection.exclusionLabel(radius.toDouble(), margin.toDouble()))
}

/** Sheet of a saved obstacle tapped on the map: exclusion zone size, or deletion. */
@Composable
fun ObstacleEditSheet(obstacles: List<LocalObstacle>) {
    val openedId by MapExtraLayers.openedObstacle.collectAsState()
    val obstacle = obstacles.firstOrNull { it.id == openedId } ?: return
    var kind by remember(obstacle.id) { mutableStateOf(obstacle.kind) }
    var radius by remember(obstacle.id) { mutableFloatStateOf(obstacle.radiusM.toFloat()) }
    var margin by remember(obstacle.id) { mutableFloatStateOf(obstacle.marginM.toFloat()) }
    var confirmDelete by remember(obstacle.id) { mutableStateOf(false) }
    LubaDialog(onDismissRequest = { MapExtraLayers.close() }, title = obstacle.label.ifBlank { obstacle.kind.label }, content = {
        KeyValueRow("Position", "${Format.num(obstacle.x, 2)} / ${Format.num(obstacle.y, 2)} m")
        if (obstacle.incidents.isNotEmpty()) KeyValueRow("Incidents", "${obstacle.incidents.size}")
        if (confirmDelete) {
            val contacts = PresumedObstacles.hiddenContacts(MapExtraLayers.latest.events, obstacle, obstacles.filter { it.id != obstacle.id },
                System.currentTimeMillis()).size
            if (contacts > 0) Text(tr("The $contacts bumper contacts recorded at this place will be dismissed too, so that it does not come back as a presumed obstacle.",
                "Les $contacts contacts pare-chocs consignés à cet endroit seront écartés aussi, pour qu'il ne revienne pas en obstacle présumé."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        Text(tr("Size of the zone that routes avoid around this obstacle. Mowing and moves are recomputed.", "Taille de la zone que les trajets évitent autour de cet obstacle. La tonte et les déplacements sont recalculés."),
            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        ObstacleSizeEditor(kind, { kind = it }, radius, { radius = it }, margin, { margin = it })
    }, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPillButton(if (confirmDelete) tr("Confirm deletion", "Confirmer la suppression") else tr("Delete", "Supprimer"), {
                if (!confirmDelete) confirmDelete = true
                else { MapExtraLayers.actions?.invoke(DifficultyAction.Delete(obstacle.id)); MapExtraLayers.close() }
            }, Modifier.weight(1f))
            PrimaryPillButton(tr("Save", "Enregistrer"), {
                MapExtraLayers.actions?.invoke(DifficultyAction.Register(obstacle.edited(
                    radiusM = ObstacleSelection.clampRadius(radius.toDouble()), marginM = ObstacleSelection.clampMargin(margin.toDouble()),
                    kind = kind, atMs = System.currentTimeMillis())))
                MapExtraLayers.close()
            }, Modifier.weight(1f))
        }
    })
}
