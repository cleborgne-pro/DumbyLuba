package fr.luba.poc.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.ModelTrial
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * Model trial (docs/features/supported-models.md#model-trial): the owner of a connected model in read-only mode arms,
 * for 30 minutes, the three tests that can validate it: command cutoff, joystick at low speed, blade test.
 */
@Composable
fun ModelTrialGroup(robot: RobotBle, state: RobotUi, modelLabel: String, open: (String) -> Unit) {
    val name = state.selected?.name
    val armed by ModelTrial.state.collectAsStateWithLifecycle()
    if (!state.ready || !ModelTrial.eligible(name)) return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(armed) { while (armed != null) { now = SystemClock.elapsedRealtime(); delay(15_000) } }
    var asking by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val active = armed?.takeIf { ModelTrial.active(name, now) }
    SettingsGroup(tr("Model trial", "Essai du modèle")) {
        if (active == null) {
            InfoNote(tr("Owner of this $modelLabel? Three supervised tests (command cutoff, joystick at low speed, blade test) are what this model needs to leave read-only mode. Arm the trial to run them, then report the result.",
                "Propriétaire de ce $modelLabel ? Trois essais supervisés (coupure des commandes, joystick lent, essai des lames) suffisent pour sortir ce modèle de la lecture seule. Armez l'essai pour les faire, puis transmettez le résultat."))
            SecondaryPillButton(tr("Arm the model trial", "Armer l'essai du modèle"), { refusal = null; asking = true }, Modifier.fillMaxWidth())
            refusal?.let { StatusNote(it, NoteTone.WARNING) }
        } else {
            StatusNote(tr("Trial armed · ${(active.remainingMs(now) / 60_000) + 1} min left · these three tests only, joystick limited to low speed",
                "Essai armé · encore ${(active.remainingMs(now) / 60_000) + 1} min · ces trois essais seulement, joystick limité à basse vitesse"), NoteTone.WARNING)
            if (active.bumperRest != 0) InfoNote(tr("The bumper read \"alert\" at rest when arming: kept as this robot's resting value for the trial. A bumper error/obstacle value, or a collision protection, still stops the tests.",
                "Le pare-chocs indiquait « alerte » au repos à l'armement : valeur retenue comme repos de ce robot pour l'essai. Une valeur erreur/obstacle du pare-chocs, ou une protection collision, arrête toujours les essais."))
            NavRow(tr("1. Command cutoff", "1. Coupure des commandes"), icon = Icons.Rounded.TimerOff,
                subtitle = tr("Does the robot stop on its own when the app goes silent?", "Le robot s'arrête-t-il seul quand l'application se tait ?")) { open(ServicePage.WATCHDOG.key) }
            NavRow(tr("2. Joystick at low speed", "2. Joystick à basse vitesse"), icon = Icons.Rounded.Gamepad,
                subtitle = tr("Forward, reverse, turns; blades stopped", "Avant, arrière, virages ; lames arrêtées")) { open(ServicePage.MANUAL.key) }
            NavRow(tr("3. Blade test", "3. Essai des lames"), icon = Icons.Rounded.ContentCut,
                subtitle = tr("Discs while stationary only (10 s max)", "Disques à l'arrêt seulement (10 s max)")) { open(ServicePage.BLADES.key) }
            InfoNote(tr("Then Service → Logs → Export all (ZIP, anonymized) and attach it to a Model compatibility report on GitHub.",
                "Ensuite Service → Journaux → Tout exporter (ZIP anonymisé) et joignez-le à un rapport de compatibilité de modèle sur GitHub."))
            SecondaryPillButton(tr("End the trial", "Terminer l'essai"), { robot.endModelTrial(tr("Ended by the user", "Terminé par l'utilisateur")) }, Modifier.fillMaxWidth())
        }
    }
    if (asking) ModelTrialDialog(modelLabel, state.telemetry.sensorStatus?.let { it and 7 } == ModelTrial.BUMPER_REST_ALERT, onDismiss = { asking = false }) {
        asking = false
        refusal = robot.armModelTrial()
    }
}

@Composable
private fun ModelTrialDialog(modelLabel: String, bumperAlertAtRest: Boolean, onDismiss: () -> Unit, onArm: () -> Unit) {
    var sight by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("Arm the model trial", "Armer l'essai du modèle"), content = {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            StatusNote(tr("$modelLabel has never been driven by DumbyLuba: its reaction to these commands is unknown. The robot will really move and its blades will really spin.",
                "$modelLabel n'a jamais été piloté par DumbyLuba : sa réaction à ces commandes est inconnue. Le robot va réellement rouler et ses lames vraiment tourner."), NoteTone.DANGER)
            Text(tr("For 30 minutes, this robot only accepts the command cutoff test, the joystick at low speed (≈ 0.3 m/s) and the blade test while stationary. Mowing, routes, return to base and map writing stay disabled. The trial ends at disconnection.",
                "Pendant 30 minutes, ce robot n'accepte que l'essai de coupure des commandes, le joystick à basse vitesse (≈ 0,3 m/s) et l'essai des lames à l'arrêt. Tonte, trajets, retour à la base et écriture de carte restent désactivés. L'essai s'arrête à la déconnexion."),
                style = MaterialTheme.typography.bodyMedium)
            if (bumperAlertAtRest) StatusNote(tr("The bumper reads \"alert\" right now. Some models report it at rest (LUBA 3 AWD): check that the bumper is free and moves normally. This value will be kept as the resting value of the trial; an error/obstacle value still stops the tests.",
                "Le pare-chocs indique « alerte » en ce moment. Certains modèles l'indiquent au repos (LUBA 3 AWD) : vérifiez que le pare-chocs est libre et bouge normalement. Cette valeur sera retenue comme repos de l'essai ; une valeur erreur/obstacle arrête toujours les essais."), NoteTone.WARNING)
            LubaCheckRow(tr("I am the owner, next to the robot, which I can see", "Je suis le propriétaire, à côté du robot, que je vois"), sight, { sight = it })
            LubaCheckRow(tr("Flat open ground, 3 m clear around it, no people or animals", "Terrain plat et dégagé, 3 m libres autour, ni personne ni animal"), clear, { clear = it })
            LubaCheckRow(tr("I can reach the robot's physical STOP button", "Je peux atteindre le bouton STOP physique du robot"), stop, { stop = it })
        }
    }, footer = {
        PrimaryPillButton(tr("Arm for 30 minutes", "Armer pour 30 minutes"), onArm, Modifier.fillMaxWidth(), enabled = sight && clear && stop)
    })
}
