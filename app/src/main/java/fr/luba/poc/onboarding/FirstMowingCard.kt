package fr.luba.poc.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.LubaProgress
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.MammotionColors

/**
 * Home card "Before the first mowing": the steps still to do, each opening its page; hidden once all are done or on
 * request (**Hide this list**).
 */
@Composable
fun FirstMowingCard(items: List<ChecklistItem>, onOpen: (SetupTarget) -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    if (items.all { it.done }) return
    val colors = MammotionColors
    val doneCount = items.count { it.done }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.Surface, border = BorderStroke(1.dp, colors.Hairline)) {
        Column(Modifier.padding(Dimens.Lg), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            Text(tr("BEFORE THE FIRST MOWING", "AVANT LA PREMIÈRE TONTE"), style = EyebrowStyle, color = colors.TextTertiary)
            Text(tr("$doneCount of ${items.size} steps done", "$doneCount étapes sur ${items.size} faites"), style = MaterialTheme.typography.titleMedium)
            LubaProgress(doneCount.toFloat() / items.size, Modifier.fillMaxWidth().clip(CircleShape))
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = !item.done, role = Role.Button) { onOpen(item.target) }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                    Icon(if (item.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
                        tint = if (item.done) colors.Green else colors.TextTertiary, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = if (item.done) colors.TextTertiary else colors.TextPrimary)
                        if (!item.done) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                    }
                    if (!item.done) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
                }
            }
            TextAction(tr("Hide this list", "Masquer cette liste"), onHide, Modifier.fillMaxWidth())
        }
    }
}
