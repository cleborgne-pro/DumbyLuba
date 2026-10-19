package fr.luba.poc.manual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.delay

@Composable
fun JoystickPanel(robot: RobotBle, state: RobotUi) {
    var confirmed by remember { mutableStateOf(false) }
    // Last chosen maximum speed, restored each time manual control is opened (and after a restart).
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { ManualSpeed.preferences(context) }
    var speed by remember { mutableStateOf(ManualSpeed.read(prefs).toFloat()) }
    LaunchedEffect(speed) { delay(300); ManualSpeed.save(prefs, speed.toInt()) }
    var forward by remember { mutableStateOf(0f) }
    var turn by remember { mutableStateOf(0f) }
    var leftHeld by remember { mutableStateOf(false) }
    var rightHeld by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    val held = leftHeld || rightHeld
    val enabled = confirmed && state.ready && !state.mowing.active && !state.navTest.running &&
        !state.teach.running && !state.campaign.running && !state.bladeTestActive && !state.mapLoading
    OnLeaveScreen(robot) { robot.stopManual(tr("Left manual control", "Sortie du pilotage manuel")) }
    fun sendInput(): Boolean {
        val command = Joystick.dualCommand(forward, turn, speed.toInt())
        return robot.manualInput(command.first, command.second)
    }
    fun change(left: Boolean, value: Float, pressed: Boolean) {
        if (pressed && !leftHeld && !rightHeld) {
            rejected = false
            robot.beginManualGesture()
        }
        if (left) { forward = if (pressed) value else 0f; leftHeld = pressed }
        else { turn = if (pressed) value else 0f; rightHeld = pressed }
        // Releasing immediately resets this axis to zero; the other finger stays independent.
        if (!pressed) {
            if (!leftHeld && !rightHeld) robot.stopManual(tr("Both joysticks released", "Deux joysticks relâchés"))
            else if (!rejected && !sendInput()) rejected = true
        }
    }
    LaunchedEffect(held, enabled) {
        if (held && enabled) {
            try {
                while (!rejected) {
                    if (!sendInput()) { rejected = true; break }
                    delay(100)
                }
            } finally { robot.stopManual(tr("Control interrupted / joysticks released", "Pilotage interrompu / joysticks relâchés")) }
        } else robot.stopManual(tr("Joysticks inactive", "Joysticks inactifs"))
    }
    SafetyNotice(tr("Blades stopped. Supervise the robot, keep the area clear, physical stop within reach.", "Lames arrêtées. Supervisez le robot, zone dégagée, arrêt physique accessible."))
    SettingsGroup(tr("Preparation", "Préparation")) {
        LubaCheckRow(title = tr("I am supervising the robot", "Je supervise le robot"), checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !held)
        SliderRow(tr("Maximum speed", "Vitesse maximale"), speed, { speed = it }, valueRange = 10f..100f,
            valueLabel = "${speed.toInt()} % · ≈ ${String.format(fr.luba.poc.i18n.I18n.locale, "%.1f", Joystick.estimatedLinearMps(speed.toInt()))} m/s", enabled = !held)
    }
    // Large camera view above the joysticks: stream requested while the page is open (the view can be hidden).
    DisposableEffect(Unit) { fr.luba.poc.camera.LiveCamera.manualPage = true; onDispose { fr.luba.poc.camera.LiveCamera.manualPage = false } }
    fr.luba.poc.camera.LiveCameraView(Modifier.fillMaxWidth(), large = true)
    SettingsGroup(tr("Controls", "Commandes")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                AxisJoystick(forward, vertical = true, enabled = enabled,
                    onChange = { value, pressed -> change(true, value, pressed) })
                Text(tr("Forward / reverse", "Avancer / reculer"), style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                AxisJoystick(turn, vertical = false, enabled = enabled,
                    onChange = { value, pressed -> change(false, value, pressed) })
                Text(tr("Turn", "Tourner"), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.manualStatus.isNotBlank()) StatusNote(state.manualStatus, if (state.manualActive) NoteTone.WARNING else NoteTone.INFO)
        if (state.manualActive) DangerPillButton(tr("Stop the robot", "Arrêter le robot"), { rejected = true; robot.stopManual(tr("Manual stop", "Arrêt manuel")) }, Modifier.fillMaxWidth())
    }
    InfoNote(tr("Releasing a joystick resets its axis to zero; releasing both stops the robot. ", "Relâcher un joystick remet son axe à zéro ; relâcher les deux arrête le robot. ") +
        tr("100 % ≈ 1 m/s and 45 °/s, the Mammotion joystick ceiling (estimated m/s, measured up to 0.5 m/s).", "100 % ≈ 1 m/s et 45 °/s, plafond du joystick Mammotion (m/s estimés, mesurés jusqu'à 0,5 m/s)."))
}

/** Each area captures its own finger; the handle follows only its assigned axis. */
@Composable
private fun AxisJoystick(value: Float, vertical: Boolean, enabled: Boolean, onChange: (Float, Boolean) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val base = MammotionColors.Ink
    val thumb = MammotionColors.Green
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(enabled, vertical) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * 0.38f
            if ((down.position - center).getDistance() > radius) return@awaitEachGesture
            fun position(p: Offset) = (if (vertical) (p.y - center.y) / radius else (p.x - center.x) / radius).coerceIn(-1f, 1f)
            down.consume(); change(position(down.position), true)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!pointer.pressed) { pointer.consume(); break }
                    pointer.consume(); change(position(pointer.position), true)
                }
            } finally { change(0f, false) }
        }
    }) {
        val radius = minOf(size.width, size.height) * 0.38f
        val axis = if (vertical) Offset(0f, radius) else Offset(radius, 0f)
        drawCircle(base.copy(alpha = if (enabled) 0.10f else 0.04f), radius, center)
        drawLine(base.copy(alpha = 0.5f), center - axis, center + axis, 2f)
        drawCircle(thumb.copy(alpha = if (enabled) 1f else 0.4f), radius * 0.25f, center + axis * value)
    }
}
