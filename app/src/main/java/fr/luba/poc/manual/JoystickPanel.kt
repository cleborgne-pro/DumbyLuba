package fr.luba.poc.manual

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.LubaCheckRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.SafetyNotice
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.SliderRow
import fr.luba.poc.ui.components.StatusNote

/** Manual control page content: supervision and speed, large camera view, then the joystick deck. */
@Composable
fun JoystickPanel(robot: RobotBle, state: RobotUi, belowControls: @Composable () -> Unit = {}) {
    val drive = rememberManualDrive(robot, state)
    SafetyNotice(tr("Blades stopped. Supervise the robot, keep the area clear, physical stop within reach.", "Lames arrêtées. Supervisez le robot, zone dégagée, arrêt physique accessible."))
    SettingsGroup(tr("Preparation", "Préparation")) {
        LubaCheckRow(title = tr("I am supervising the robot", "Je supervise le robot"), checked = drive.confirmed, onCheckedChange = { drive.confirmed = it }, enabled = !drive.held)
        SliderRow(tr("Maximum speed", "Vitesse maximale"), drive.speed, { drive.speed = it }, valueRange = 10f..100f,
            valueLabel = "${drive.speedPercent} % · ≈ ${String.format(fr.luba.poc.i18n.I18n.locale, "%.1f", Joystick.estimatedLinearMps(drive.speedPercent))} m/s", enabled = !drive.held)
    }
    // Large camera view above the joysticks (the view can be hidden).
    fr.luba.poc.camera.LiveCameraView(Modifier.fillMaxWidth(), large = true)
    SettingsGroup(tr("Controls", "Commandes")) {
        JoystickDeck(drive, state.manualActive, speedControl = false)
        if (!drive.confirmed) InfoNote(tr("Tick \"I am supervising the robot\" to unlock the joysticks.", "Cochez « Je supervise le robot » pour déverrouiller les joysticks."))
        if (state.manualStatus.isNotBlank()) StatusNote(state.manualStatus, if (state.manualActive) NoteTone.WARNING else NoteTone.INFO)
    }
    belowControls()
    InfoNote(tr("Releasing a joystick resets its axis to zero; releasing both stops the robot. The response is progressive beyond the 12 % dead zone; a tick is felt at each third of the travel and at the end stop. ",
        "Relâcher un joystick remet son axe à zéro ; relâcher les deux arrête le robot. La réponse est progressive au-delà de la zone morte de 12 % ; un cran se fait sentir à chaque tiers de la course et en butée. ") +
        tr("100 % ≈ 1 m/s and 45 °/s, the Mammotion joystick ceiling (estimated m/s, measured up to 0.5 m/s).", "100 % ≈ 1 m/s et 45 °/s, plafond du joystick Mammotion (m/s estimés, mesurés jusqu'à 0,5 m/s)."))
}
