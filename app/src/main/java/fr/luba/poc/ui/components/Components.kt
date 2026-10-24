package fr.luba.poc.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.NumericStyle

/**
 * Rounded white card with a thin border: basic building block of the screens, as in the Mammotion app.
 */
@Composable
fun LubaCard(
    modifier: Modifier = Modifier,
    padding: Dp = Dimens.Lg,
    containerColor: Color = MammotionColors.Surface,
    border: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = if (border) BorderStroke(1.dp, MammotionColors.Hairline) else null,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * Settings section: [LubaCard] with its title, optional subtitle and the standard spacing between
 * the header and the content. Every screen section goes through here.
 */
@Composable
fun LubaSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    padding: Dp = Dimens.Lg,
    containerColor: Color = MammotionColors.Surface,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    LubaCard(modifier = modifier, padding = padding, containerColor = containerColor) {
        SectionHeader(title, subtitle)
        Spacer(Modifier.height(Dimens.Sm))
        content()
    }
}

/** Section title, with an optional subtitle. */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    }
}

/** Small coloured status chip (connected, computing, alert…). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Statistic tile: icon + value + label, aligned in a grid. */
@Composable
fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, tint: Color = MammotionColors.GreenDeep) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MammotionColors.SurfaceMuted) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(value, style = NumericStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary, maxLines = 1)
        }
    }
}

/** Mammotion-style setting row: icon, title, subtitle and trailing content. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(38.dp).background(MammotionColors.GreenSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MammotionColors.GreenDeep, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        trailing?.invoke()
    }
}

/**
 * Label / value pair, for telemetry lists: a short value is right-aligned on the same line; a long
 * value (sentence, several measurements) goes under its label to stay readable.
 */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MammotionColors.TextPrimary) {
    if (value.length > KEY_VALUE_INLINE_MAX) {
        Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        return
    }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.Md), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f), overflow = TextOverflow.Ellipsis)
    }
}

/** Beyond this, the value goes under its label rather than wrapping in a half column. */
private const val KEY_VALUE_INLINE_MAX = 30

/** Discreet divider between two rows. */
@Composable
fun LubaDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MammotionColors.Outline))
}

/**
 * Full-width modal: it takes the whole available width (reduced side margins) and stretches in
 * height up to its content, with inner scrolling if needed. Actions are pinned at the bottom,
 * outside the scrolling area, as in the Mammotion app.
 */
@Composable
fun LubaDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = { if (dismissEnabled) onDismissRequest() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = maxHeight
            Surface(
                modifier = modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxH),
                shape = MaterialTheme.shapes.extraLarge,
                color = MammotionColors.Surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Header: title and close cross, instead of a "Close" button at the bottom of the modal.
                    // [navigation] (back from a sub-view) left of the title, [actions] (settings…) before the cross.
                    if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (navigation != null) Box(Modifier.offset(x = (-8).dp)) { navigation() }
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        actions()
                        IconButton(onClick = onDismissRequest, enabled = dismissEnabled, modifier = Modifier.offset(x = 8.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = tr("Close", "Fermer"), tint = MammotionColors.TextSecondary)
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                    footer()
                }
            }
        }
    }
}

/** Checkbox in the brand colours (green once checked). */
@Composable
fun LubaCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = CheckboxDefaults.colors(
            checkedColor = MammotionColors.Green,
            uncheckedColor = MammotionColors.TextTertiary,
            checkmarkColor = Color.White,
            disabledCheckedColor = MammotionColors.Green.copy(alpha = 0.4f),
            disabledUncheckedColor = MammotionColors.Outline,
        ),
    )
}

/**
 * Checkable setting row: the whole surface is clickable, the background turns green when the
 * option is active. The checkbox is decorative (the click is handled by the row).
 */
@Composable
fun LubaCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        shape = MaterialTheme.shapes.small,
        color = when {
            !enabled -> MammotionColors.SurfaceMuted
            checked -> MammotionColors.GreenSoft
            else -> MammotionColors.SurfaceMuted
        },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LubaCheckbox(checked = checked, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MammotionColors.TextPrimary else MammotionColors.TextTertiary,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                }
            }
        }
    }
}

/** Test result row: coloured status chip, title, duration and detail. */
@Composable
fun ResultRow(
    outcome: String,
    title: String,
    modifier: Modifier = Modifier,
    detail: String = "",
    meta: String = "",
) {
    val color = when {
        outcome.startsWith("PASS") || outcome == "REPORTED" -> MammotionColors.Green
        outcome.startsWith("FAIL") || outcome.startsWith("ERREUR") || outcome == "CONTACT_BEFORE_PASS_END" -> MammotionColors.Danger
        else -> MammotionColors.Warning
    }
    val label = when (outcome) {
        "PASS LOCAL" -> tr("Passed · local", "Réussi · local")
        "PASS BLE" -> tr("Passed · BLE", "Réussi · BLE")
        "PASS" -> tr("Passed", "Réussi")
        "FAIL", "FAILED" -> tr("Failed", "Échec")
        "ABORTED" -> tr("Interrupted", "Interrompu")
        "UNCONFIRMED" -> tr("Not confirmed", "Non confirmé")
        "REPORTED" -> tr("Reported by the robot", "Rapporté par le robot")
        "REVERTED" -> tr("Height not kept", "Hauteur non conservée")
        "INTERRUPTED" -> tr("Interrupted", "Interrompu")
        "RUNNING" -> tr("In progress", "En cours")
        "CONTACT_DETECTED" -> tr("Contact detected", "Contact détecté")
        "NOT_DETECTED" -> tr("No contact detected", "Aucun contact détecté")
        "BLOCKED_AHEAD" -> tr("Stopped in front of an object", "Arrêté devant un objet")
        "CONTACT" -> "Contact"
        "EDGE" -> tr("Edge found", "Bord constaté")
        "NO_EDGE" -> tr("No edge encountered", "Aucun bord rencontré")
        "CONTACT_BEFORE_PASS_END" -> tr("Contact before the end of the pass", "Contact avant le bout de passe")
        else -> outcome
    }
    Column(modifier.fillMaxWidth().padding(vertical = Dimens.Xs), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.Top) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        StatusPill(label, color)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    }
}

/** Slider setting: label and current value above, full-width slider below. */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    valueLabel: String = "",
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f).padding(end = Dimens.Sm), style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
            // Value in blue, green slider: codes of the Mammotion "Task assignment" page.
            Text(valueLabel, style = MaterialTheme.typography.titleSmall, color = MammotionColors.Value)
        }
        LubaSlider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, enabled = enabled)
    }
}

/** Green switch, like the Mammotion settings. */
@Composable
fun LubaSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = MammotionColors.Green, checkedBorderColor = MammotionColors.Green,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = MammotionColors.Outline, uncheckedBorderColor = MammotionColors.Outline,
        ))
}

/** Indeterminate progress (brand green), continuous rounded track. */
@Composable
fun LubaProgress(modifier: Modifier = Modifier) =
    androidx.compose.material3.LinearProgressIndicator(modifier.height(6.dp), color = MammotionColors.Green,
        trackColor = MammotionColors.Outline, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp)

/** Determinate progress (brand green), continuous track, no stop indicator. */
@Composable
fun LubaProgress(progress: Float, modifier: Modifier = Modifier) =
    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = modifier.height(6.dp),
        color = MammotionColors.Green, trackColor = MammotionColors.Outline,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})

/** Emergency stop button: red background, explicit label, immediate action. */
@Composable
fun EmergencyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = tr("EMERGENCY STOP", "ARRÊT D'URGENCE"),
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.EmergencyButton),
        colors = ButtonDefaults.buttonColors(containerColor = MammotionColors.Danger),
    ) {
        Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(Dimens.Sm))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** Legend entry: colour swatch followed by its label. */
@Composable
fun LegendSwatch(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(4.dp)).border(1.dp, MammotionColors.TextTertiary, RoundedCornerShape(4.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Compact stop, for tracking bars and mission modals. */
@Composable
fun StopAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MammotionColors.Danger)) {
        Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Dimens.Sm))
        Text("STOP", style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Screen exit action (STOP of a test), ignored when the activity is only recreated: Fold folding /
 * unfolding or rotation. The state is kept by the ViewModel and the screen comes back.
 */
@androidx.compose.runtime.Composable
fun OnLeaveScreen(key: Any?, onLeave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(key) {
        // Action captured with the key, like a regular DisposableEffect.
        val leave = onLeave
        onDispose {
            var current: android.content.Context? = context
            while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
            if ((current as? android.app.Activity)?.isChangingConfigurations != true) leave()
        }
    }
}
