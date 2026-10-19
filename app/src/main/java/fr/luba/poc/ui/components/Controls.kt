package fr.luba.poc.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/*
 * Shared input controls: sliders, inline switches, text fields, coloured notes and logs. Every
 * screen goes through here to keep the same look (round thumb, thin track, brand green) instead of
 * the default Material variants.
 */

private val TrackHeight = 6.dp
private val ThumbSize = 22.dp

/** Thin rounded track: green active part between [from] and [to] (fractions 0..1). */
@Composable
private fun LubaTrack(from: Float, to: Float, enabled: Boolean) {
    val active = if (enabled) MammotionColors.Green else MammotionColors.TextTertiary
    val inactive = MammotionColors.Outline
    Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(inactive, cornerRadius = r)
        val start = from.coerceIn(0f, 1f) * size.width
        val end = to.coerceIn(0f, 1f) * size.width
        if (end > start) drawRoundRect(active, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = r)
    }
}

/** Round white thumb with a green ring, slightly shadowed. */
@Composable
private fun LubaThumb(enabled: Boolean) {
    val ring = if (enabled) MammotionColors.Green else MammotionColors.TextTertiary
    Box(
        Modifier.size(ThumbSize).shadow(if (enabled) 2.dp else 0.dp, CircleShape)
            .background(Color.White, CircleShape).border(2.dp, ring, CircleShape),
    )
}

private fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else (value - range.start) / span
}

/** App slider (no visible ticks, even with a fixed step). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LubaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(), enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, interactionSource = interaction, steps = steps,
        thumb = { LubaThumb(enabled) },
        track = { state -> LubaTrack(0f, fraction(state.value, state.valueRange), enabled) },
        valueRange = valueRange,
    )
}

/** Two-bound slider (recharge thresholds…), same look as [LubaSlider]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LubaRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    RangeSlider(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(), enabled = enabled,
        valueRange = valueRange, steps = steps,
        startThumb = { LubaThumb(enabled) },
        endThumb = { LubaThumb(enabled) },
        track = { state ->
            LubaTrack(fraction(state.activeRangeStart, state.valueRange), fraction(state.activeRangeEnd, state.valueRange), enabled)
        },
    )
}

/**
 * On/off setting: title, optional explanation and green switch on the right. The whole row is
 * clickable.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Md),
    ) {
        if (icon != null) Box(Modifier.size(38.dp).background(MammotionColors.SurfaceMuted, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MammotionColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MammotionColors.TextPrimary else MammotionColors.TextTertiary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        // The switch reflects the state; the click is handled by the whole row.
        LubaSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Input field in the app colours (ink on focus, medium corners). */
@Composable
fun LubaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MammotionColors
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.Ink, unfocusedBorderColor = colors.Outline,
            focusedLabelColor = colors.TextPrimary, unfocusedLabelColor = colors.TextSecondary,
            cursorColor = colors.Green, focusedContainerColor = colors.Surface, unfocusedContainerColor = colors.Surface,
            disabledContainerColor = colors.SurfaceMuted, disabledBorderColor = colors.Outline,
        ),
    )
}

/** Tone of a note: neutral information, success, warning or error. */
enum class NoteTone { INFO, SUCCESS, WARNING, DANGER }

/**
 * Framed status message (result of an action, warning, error): tinted background, icon and text,
 * instead of coloured texts placed freely in the screens.
 */
@Composable
fun StatusNote(text: String, tone: NoteTone = NoteTone.INFO, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    val (bg, fg, icon) = when (tone) {
        NoteTone.INFO -> Triple(colors.InfoSoft, colors.Info, Icons.Rounded.Info)
        NoteTone.SUCCESS -> Triple(colors.GreenSoft, colors.GreenDeep, Icons.Rounded.TaskAlt)
        NoteTone.WARNING -> Triple(colors.WarningSoft, colors.Warning, Icons.Rounded.WarningAmber)
        NoteTone.DANGER -> Triple(colors.DangerSoft, colors.Danger, Icons.Rounded.ErrorOutline)
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = bg, contentColor = colors.TextPrimary) {
        Row(Modifier.padding(horizontal = Dimens.Md, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm),
            verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Latest events of a log: monospaced text in a grey box, most recent at the top. */
@Composable
fun LogLines(lines: List<String>, modifier: Modifier = Modifier, title: String? = null) {
    if (lines.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MammotionColors.SurfaceMuted) {
            Column(Modifier.padding(Dimens.Md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MammotionColors.TextSecondary)
                }
            }
        }
    }
}

/** Discreet text action (link, secondary action of a row); [danger] for a deletion. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 40.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) MammotionColors.Danger else MammotionColors.TextPrimary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Highlighted value (pairing code, address): large monospaced figures in a box. */
@Composable
fun CodeValue(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MammotionColors.SurfaceMuted,
        border = BorderStroke(1.dp, MammotionColors.Hairline)) {
        Column(Modifier.padding(Dimens.Md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}
