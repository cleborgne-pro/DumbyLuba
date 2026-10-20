package fr.luba.poc.manual

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry of a single-axis joystick, in pixels: capsule track along the axis and knob travel. */
internal data class AxisPad(val width: Float, val height: Float, val vertical: Boolean) {
    val length: Float get() = if (vertical) height else width
    /** Same thickness for both pads (from the smaller pad side), so that the knob leaves a long travel. */
    val thickness: Float get() = min(min(width, height) * 0.40f, length * 0.42f)
    val knobRadius: Float get() = thickness * 0.44f
    /** Distance between the centre and the knob centre at the end stop. */
    val travel: Float get() = (length / 2f - thickness / 2f).coerceAtLeast(1f)
    val center: Offset get() = Offset(width / 2f, height / 2f)

    /**
     * Axis value of a finger position: −1 at the top (or left) end stop, +1 at the bottom (or right). The whole pad
     * area captures the finger, beyond the track too; the value is clamped at the end stops.
     */
    fun value(position: Offset): Float =
        ((if (vertical) position.y - center.y else position.x - center.x) / travel).coerceIn(-1f, 1f)

    fun knob(value: Float): Offset = center + (if (vertical) Offset(0f, travel * value) else Offset(travel * value, 0f))
}

/**
 * Single-axis joystick: capsule track along its axis (vertical: forward / reverse; horizontal: turn), knob that follows
 * the finger and springs back to the centre on release, green fill from the centre to the knob, notches marked on the
 * track and felt by a haptic tick (see [Joystick.notch]). The finger is captured anywhere on the pad, not only on the
 * knob; each pad captures its own finger, so both can be held at the same time.
 * [onChange] receives the axis value (−1..1, screen orientation) and whether the finger is still down.
 */
@Composable
fun AxisJoystick(value: Float, vertical: Boolean, enabled: Boolean, onChange: (Float, Boolean) -> Unit,
                 modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    // Knob drawn under the finger while pressed, then springing back to the centre (display only: the command is
    // reset to zero as soon as the finger is lifted).
    val shown = remember { Animatable(0f) }
    LaunchedEffect(value, pressed) {
        if (pressed) shown.snapTo(value)
        else shown.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    val colors = MammotionColors
    val description = if (vertical) tr("Forward / reverse joystick", "Joystick avancer / reculer") else tr("Turn joystick", "Joystick tourner")
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(enabled, vertical) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pad = AxisPad(size.width.toFloat(), size.height.toFloat(), vertical)
                var notch = 0
                fun report(position: Offset) {
                    val v = pad.value(position)
                    val next = Joystick.notch(v)
                    if (next != notch) {
                        haptics.performHapticFeedback(if (next == 4) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                        notch = next
                    }
                    change(v, true)
                }
                down.consume(); pressed = true; report(down.position)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) { pointer.consume(); break }
                        pointer.consume(); report(pointer.position)
                    }
                } finally { pressed = false; change(0f, false) }
            }
        }) {
            val pad = AxisPad(size.width, size.height, vertical)
            val t = pad.thickness
            val alpha = if (enabled) 1f else 0.45f
            val trackSize = if (vertical) Size(t, pad.length) else Size(pad.length, t)
            val trackTopLeft = pad.center - Offset(trackSize.width / 2f, trackSize.height / 2f)
            val radius = CornerRadius(t / 2f)
            val axis = if (vertical) Offset(0f, 1f) else Offset(1f, 0f)
            val across = if (vertical) Offset(1f, 0f) else Offset(0f, 1f)
            // Track: soft gradient capsule with a thin outline, green outline while held.
            drawRoundRect(Brush.linearGradient(listOf(colors.SurfaceMuted, colors.Surface),
                start = trackTopLeft, end = trackTopLeft + Offset(trackSize.width, trackSize.height)), trackTopLeft, trackSize, radius, alpha = alpha)
            drawRoundRect(if (pressed) colors.Green.copy(alpha = 0.7f) else colors.Outline, trackTopLeft, trackSize, radius,
                style = Stroke(if (pressed) 2.dp.toPx() else 1.dp.toPx()), alpha = alpha)
            // Groove along the axis, the haptic notches as dots (thirds of the travel, end stop) and the dead zone.
            drawLine(colors.Ink.copy(alpha = 0.08f * alpha), pad.center - axis * pad.travel, pad.center + axis * pad.travel,
                2.dp.toPx(), StrokeCap.Round)
            drawLine(colors.Ink.copy(alpha = 0.16f * alpha), pad.center - axis * (pad.travel * Joystick.DEAD_ZONE),
                pad.center + axis * (pad.travel * Joystick.DEAD_ZONE), 4.dp.toPx(), StrokeCap.Round)
            for (k in 1..3) for (side in listOf(-1f, 1f)) {
                val n = Joystick.DEAD_ZONE + (1f - Joystick.DEAD_ZONE) * k / 3f
                drawCircle(colors.Ink.copy(alpha = 0.22f * alpha), (if (k == 3) 2.5f else 2f) * 1.dp.toPx(), pad.center + axis * (pad.travel * n * side))
            }
            // Level: from the centre to the knob, more opaque towards the knob.
            val knobAt = pad.knob(shown.value)
            if (abs(shown.value) > 0.01f) drawLine(Brush.linearGradient(listOf(colors.Green.copy(alpha = 0.10f), colors.Green.copy(alpha = 0.65f)),
                start = pad.center, end = knobAt), pad.center, knobAt, t * 0.5f, StrokeCap.Round)
            // Knob: drop shadow, halo while held, gradient body, white ring and grip lines across the axis.
            val r = pad.knobRadius
            if (pressed) drawCircle(colors.Green.copy(alpha = 0.18f), r * 1.5f, knobAt)
            drawCircle(Color.Black.copy(alpha = 0.18f * alpha), r * 1.04f, knobAt + Offset(0f, 2.dp.toPx()))
            val body = if (enabled) listOf(colors.Green, colors.GreenDark) else listOf(colors.Outline, colors.TextTertiary)
            drawCircle(Brush.radialGradient(body, center = knobAt - Offset(r * 0.35f, r * 0.35f), radius = r * 1.6f), r, knobAt)
            drawCircle(Color.White.copy(alpha = 0.55f), r - 1.dp.toPx(), knobAt, style = Stroke(1.5.dp.toPx()))
            if (enabled) for (k in -1..1) {
                val o = knobAt + axis * (k * r * 0.28f)
                drawLine(Color.White.copy(alpha = 0.8f), o - across * (r * 0.38f), o + across * (r * 0.38f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        // Direction marks at both ends; the side being driven turns green.
        val tint = { active: Boolean -> if (!enabled) colors.TextTertiary.copy(alpha = 0.5f) else if (active) colors.Green else colors.TextTertiary }
        val endPadding = 2.dp
        if (vertical) {
            Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(top = endPadding).size(22.dp), tint = tint(value < -Joystick.DEAD_ZONE))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(bottom = endPadding).size(22.dp), tint = tint(value > Joystick.DEAD_ZONE))
        } else {
            Icon(Icons.AutoMirrored.Rounded.RotateLeft, null, Modifier.align(Alignment.CenterStart).padding(start = endPadding + 4.dp).size(20.dp), tint = tint(value < -Joystick.DEAD_ZONE))
            Icon(Icons.AutoMirrored.Rounded.RotateRight, null, Modifier.align(Alignment.CenterEnd).padding(end = endPadding + 4.dp).size(20.dp), tint = tint(value > Joystick.DEAD_ZONE))
        }
        if (!enabled) Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.9f))
    }
}

/** Commanded share of the maximum speed for an axis value, in percent (after the response curve). */
internal fun axisPercent(value: Float, speed: Int): Int = (abs(Joystick.response(value)) * speed).roundToInt()

/**
 * Joystick deck: forward / reverse on the left, turn on the right, and between them the speed (± 10 %, if
 * [speedControl]) and a round STOP. Under each pad, its live output ("Forward 18 %"), or its role at rest.
 */
@Composable
fun JoystickDeck(drive: ManualDrive, manualActive: Boolean, modifier: Modifier = Modifier, padHeight: Dp = 168.dp,
                 speedControl: Boolean = true) {
    val colors = MammotionColors
    val speed = drive.speedPercent
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AxisJoystick(drive.forward, vertical = true, enabled = drive.enabled, onChange = { v, pressed -> drive.change(true, v, pressed) },
                modifier = Modifier.fillMaxWidth().height(padHeight))
            val f = drive.forward
            AxisCaption(when {
                !drive.leftHeld || abs(f) <= Joystick.DEAD_ZONE -> tr("Forward / reverse", "Avancer / reculer")
                f < 0 -> tr("Forward ${axisPercent(f, speed)} %", "Avance ${axisPercent(f, speed)} %")
                else -> tr("Reverse ${axisPercent(f, speed)} %", "Recul ${axisPercent(f, speed)} %")
            }, drive.leftHeld && abs(f) > Joystick.DEAD_ZONE)
        }
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (speedControl) SpeedStepper(drive)
            Surface(onClick = { drive.stop() }, shape = CircleShape, color = colors.Danger, contentColor = Color.White,
                shadowElevation = if (manualActive) 6.dp else 2.dp, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("STOP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AxisJoystick(drive.turn, vertical = false, enabled = drive.enabled, onChange = { v, pressed -> drive.change(false, v, pressed) },
                modifier = Modifier.fillMaxWidth().height(padHeight))
            val t = drive.turn
            AxisCaption(when {
                !drive.rightHeld || abs(t) <= Joystick.DEAD_ZONE -> tr("Turn", "Tourner")
                t < 0 -> tr("Left ${axisPercent(t, speed)} %", "Gauche ${axisPercent(t, speed)} %")
                else -> tr("Right ${axisPercent(t, speed)} %", "Droite ${axisPercent(t, speed)} %")
            }, drive.rightHeld && abs(t) > Joystick.DEAD_ZONE)
        }
    }
}

@Composable
private fun AxisCaption(text: String, active: Boolean) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = TextAlign.Center,
        color = if (active) MammotionColors.GreenDeep else MammotionColors.TextSecondary)
}

/** Maximum speed by steps of 10 %, locked while a joystick is held. */
@Composable
private fun SpeedStepper(drive: ManualDrive) {
    val colors = MammotionColors
    val speed = drive.speedPercent
    Surface(shape = RoundedCornerShape(14.dp), color = colors.SurfaceMuted, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speed %", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.TextPrimary)
            Text("≈ ${String.format(fr.luba.poc.i18n.I18n.locale, "%.1f", Joystick.estimatedLinearMps(speed))} m/s",
                style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StepButton(Icons.Rounded.Remove, tr("Slower", "Moins vite"), !drive.held && speed > ManualSpeed.MIN_PERCENT) { drive.step(-10) }
                StepButton(Icons.Rounded.Add, tr("Faster", "Plus vite"), !drive.held && speed < ManualSpeed.MAX_PERCENT) { drive.step(10) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MammotionColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MammotionColors.Outline), modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(18.dp), tint = if (enabled) MammotionColors.TextPrimary else MammotionColors.TextTertiary)
        }
    }
}
