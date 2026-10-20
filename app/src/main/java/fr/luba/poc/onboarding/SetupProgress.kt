package fr.luba.poc.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Steps of the "Before the first mowing" list that are remembered once done (the others are read live). */
enum class SetupStep { ROBOT_CONNECTED, SETUP_CHECKED, MANUAL_TRIED, BLADES_OFF_ROUTE }

/**
 * First-launch assistant and first-mowing list, remembered on the phone (preferences "setup"). An installation that
 * already has a role or a known robot (update from an older version) counts as set up: neither is shown to it.
 */
object SetupProgress {
    private const val PREFS = "setup"
    private const val WIZARD_DONE = "wizardDone"
    private const val CHECKLIST_HIDDEN = "checklistHidden"
    private val saves = MutableStateFlow(0)
    /** Incremented on every change: the screens read the progress again. */
    val changes: StateFlow<Int> = saves

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    /** Already used before the assistant existed: a role chosen or a robot known on this phone. */
    fun existingInstall(context: Context): Boolean =
        context.getSharedPreferences("gateway", 0).contains("role") || context.getSharedPreferences("last_position_robot", 0).contains("id")

    /** The assistant opens after the intro only on a fresh installation that has not finished or skipped it. */
    fun showWizard(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(WIZARD_DONE, false)) return false
        if (existingInstall(context)) { p.edit().putBoolean(WIZARD_DONE, true).putBoolean(CHECKLIST_HIDDEN, true).apply(); return false }
        return true
    }

    fun finishWizard(context: Context) { prefs(context).edit().putBoolean(WIZARD_DONE, true).apply(); saves.value++ }

    fun done(context: Context): Set<SetupStep> = SetupStep.entries.filter { prefs(context).getBoolean(it.name, false) }.toSet()

    fun mark(context: Context, step: SetupStep) {
        if (prefs(context).getBoolean(step.name, false)) return
        prefs(context).edit().putBoolean(step.name, true).apply(); saves.value++
    }

    fun checklistHidden(context: Context) = prefs(context).getBoolean(CHECKLIST_HIDDEN, false)
    fun hideChecklist(context: Context) { prefs(context).edit().putBoolean(CHECKLIST_HIDDEN, true).apply(); saves.value++ }
}

/** Where a list item leads. */
enum class SetupTarget { CONNECTION, MAP, SETUP_CHECK, COMMAND_CUTOFF, MANUAL, NAVIGATION }

data class ChecklistItem(val step: String, val title: String, val subtitle: String, val done: Boolean, val target: SetupTarget)

/**
 * "Before the first mowing": the steps of the quick start that make the first real mowing safe, in order. Pure.
 * [mapComplete] and [cutoffTested] are read live (per robot); the others come from [done].
 */
object FirstMowingChecklist {
    fun items(done: Set<SetupStep>, ready: Boolean, mapComplete: Boolean, cutoffTested: Boolean): List<ChecklistItem> = listOf(
        ChecklistItem("robot", tr("Connect the robot", "Connecter le robot"), tr("Through the gateway or over direct Bluetooth", "Par la passerelle ou en Bluetooth direct"),
            ready || SetupStep.ROBOT_CONNECTED in done, SetupTarget.CONNECTION),
        ChecklistItem("check", tr("Check the setup", "Vérifier l'installation"), tr("Wi-Fi, gateway, placement, link by link", "Wi-Fi, passerelle, placement, maillon par maillon"),
            SetupStep.SETUP_CHECKED in done, SetupTarget.SETUP_CHECK),
        ChecklistItem("map", tr("Read the map", "Lire la carte"), tr("From the robot or the Mammotion account", "Depuis le robot ou le compte Mammotion"),
            mapComplete, SetupTarget.MAP),
        ChecklistItem("cutoff", tr("Test the command cutoff", "Tester la coupure des commandes"), tr("Does the robot stop on its own if the app goes silent?", "Le robot s'arrête-t-il seul si l'application se tait ?"),
            cutoffTested, SetupTarget.COMMAND_CUTOFF),
        ChecklistItem("manual", tr("Drive it by hand", "Le piloter à la main"), tr("Manual control, blades stopped", "Pilotage manuel, lames arrêtées"),
            SetupStep.MANUAL_TRIED in done, SetupTarget.MANUAL),
        ChecklistItem("route", tr("Follow a route, blades stopped", "Suivre un trajet, lames arrêtées"),
            tr("Navigation test, or Simulate → Run this route → Follow, blades stopped", "Essai de navigation, ou Simuler → Lancer ce trajet → Suivre, lames arrêtées"),
            SetupStep.BLADES_OFF_ROUTE in done, SetupTarget.NAVIGATION),
    )
}

/** Remembered steps, read again on every change. */
@Composable
fun rememberSetupDone(context: Context): Set<SetupStep> {
    val version = SetupProgress.changes.collectAsState().value
    return remember(version) { SetupProgress.done(context) }
}
