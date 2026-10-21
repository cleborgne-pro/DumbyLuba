package fr.luba.poc.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.MowingLiveStats
import fr.luba.poc.ui.components.LubaProgress
import fr.luba.poc.ui.components.StopAction
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/**
 * Bottom bar during a real mowing: cutting progress, blades (measured rotation), reported height, battery, duration
 * and current recharge over the planned total ("Recharge 1/2"), then the current step. The travel speed is set in the
 * details ("⋯"). Composable kept apart from [MapScreen] (function size checked by ART).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MowingLiveBar(state: RobotUi, onStop: () -> Unit, onDetails: () -> Unit) {
    val colors = MammotionColors
    val route = state.robotRoute
    val record = state.mowingRecord?.takeIf { route.cutProgress != null }
    val stats = MowingLiveStats.of(state.telemetry, route, record?.totals?.totalSeconds, record?.forecast?.totalSeconds,
        recharges = record?.recharges, expectedRecharges = record?.forecast?.recharges)
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onDetails).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(listOfNotNull(tr("Local mowing", "Tonte locale"), stats.progress).joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                        color = if (route.cutProgress != null) colors.GreenDeep else colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                        Text(stats.blades, style = MaterialTheme.typography.labelMedium,
                            color = when (stats.bladesOn) { true -> colors.Green; false -> colors.TextSecondary; null -> colors.Warning })
                        stats.height?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary) }
                        stats.battery?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary) }
                        stats.duration?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary) }
                        stats.recharges?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary) }
                        LiveCoverageLabel(state.mowingRecord?.id)
                    }
                    Text(route.status, style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (route.running) StopAction(onStop)
                IconButton(onClick = onDetails) { Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Route and local mowing details", "Détails du trajet et de la tonte locale")) }
            }
            stats.fraction?.let { LubaProgress(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp)) }
        }
    }
}

/** Share of the task zones actually mowed so far ([fr.luba.poc.history.RealCoverage]), once something is mowed. */
@Composable
private fun LiveCoverageLabel(taskId: String?) {
    val view by fr.luba.poc.history.RealCoverage.flow.collectAsState()
    val coverage = view?.takeIf { it.running && it.taskId == taskId && it.coverage.coveredM2 > 0 }?.coverage ?: return
    Text(fr.luba.poc.history.CoverageText.live(coverage), style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
}
