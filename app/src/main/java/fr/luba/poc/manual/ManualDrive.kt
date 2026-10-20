package fr.luba.poc.manual

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.OnLeaveScreen
import kotlinx.coroutines.delay

/**
 * Joystick control shared by the manual control page and the "record by driving" page: supervision, maximum speed,
 * the two axes and the 10 Hz input loop. The robot guards ([RobotBle.manualInput]) are unchanged: releasing both axes,
 * a refusal, leaving the screen or [stop] sends STOP.
 */
@Stable
class ManualDrive internal constructor(private val robot: RobotBle, initialSpeed: Int) {
    var confirmed by mutableStateOf(false)
    var speed by mutableFloatStateOf(initialSpeed.toFloat())
    var forward by mutableFloatStateOf(0f)
        private set
    var turn by mutableFloatStateOf(0f)
        private set
    var leftHeld by mutableStateOf(false)
        private set
    var rightHeld by mutableStateOf(false)
        private set
    internal var rejected by mutableStateOf(false)
    /** Robot idle and control allowed (supervision ticked, no mission or test), published after each composition. */
    var enabled by mutableStateOf(false)
        internal set
    val held: Boolean get() = leftHeld || rightHeld
    val speedPercent: Int get() = ManualSpeed.sanitize(speed.toInt())

    internal fun sendInput(): Boolean {
        val command = Joystick.dualCommand(forward, turn, speedPercent)
        return robot.manualInput(command.first, command.second)
    }

    /** New position of an axis ([left] = forward / reverse); releasing it resets this axis to zero. */
    fun change(left: Boolean, value: Float, pressed: Boolean) {
        if (pressed && !leftHeld && !rightHeld) {
            rejected = false
            robot.beginManualGesture()
        }
        if (left) { forward = if (pressed) value else 0f; leftHeld = pressed }
        else { turn = if (pressed) value else 0f; rightHeld = pressed }
        // The other finger stays independent.
        if (!pressed) {
            if (!leftHeld && !rightHeld) robot.stopManual(tr("Both joysticks released", "Deux joysticks relâchés"))
            else if (!rejected && !sendInput()) rejected = true
        }
    }

    /** Stop button: the robot stops and only restarts with a new press. */
    fun stop() {
        rejected = true
        robot.stopManual(tr("Manual stop", "Arrêt manuel"))
    }

    /** Speed step of the compact control (± 10 %), only while no joystick is held. */
    fun step(delta: Int) {
        if (held) return
        speed = ManualSpeed.sanitize(speedPercent + delta).toFloat()
    }
}

/**
 * Remembers the joystick control of a screen: speed restored and saved ([ManualSpeed]), input loop while a joystick is
 * held, STOP when leaving the screen. Also requests the camera stream while the screen is shown.
 */
@Composable
fun rememberManualDrive(robot: RobotBle, state: RobotUi): ManualDrive {
    val context = LocalContext.current
    val prefs = remember(context) { ManualSpeed.preferences(context) }
    val drive = remember(robot) { ManualDrive(robot, ManualSpeed.read(prefs)) }
    val enabled = drive.confirmed && state.ready && !state.mowing.active && !state.navTest.running &&
        !state.teach.running && !state.campaign.running && !state.bladeTestActive && !state.mapLoading
    // Published after the composition (no state write while composing); the joysticks recompose with it.
    SideEffect { drive.enabled = enabled }
    val speed = drive.speedPercent
    LaunchedEffect(speed) { delay(300); ManualSpeed.save(prefs, speed) }
    OnLeaveScreen(robot) { robot.stopManual(tr("Left manual control", "Sortie du pilotage manuel")) }
    val held = drive.held
    LaunchedEffect(held, enabled) {
        if (held && enabled) {
            try {
                while (!drive.rejected) {
                    if (!drive.sendInput()) { drive.rejected = true; break }
                    delay(100)
                }
            } finally { robot.stopManual(tr("Control interrupted / joysticks released", "Pilotage interrompu / joysticks relâchés")) }
        } else robot.stopManual(tr("Joysticks inactive", "Joysticks inactifs"))
    }
    // Camera stream requested while a control screen is open (the view can be hidden).
    androidx.compose.runtime.DisposableEffect(Unit) {
        fr.luba.poc.camera.LiveCamera.manualPage = true
        onDispose { fr.luba.poc.camera.LiveCamera.manualPage = false }
    }
    return drive
}
