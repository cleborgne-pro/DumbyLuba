package fr.luba.poc.simulation

import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.MammotionColors

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens

@Composable
fun SimulationDialog(ui:SimulationUi, preview:Preview?, base:MapBase?, map:RobotMap, manualBase:Boolean, selected:Set<Long>, onSelection:(Set<Long>)->Unit,zoneSelectionAllowed:Boolean,
    initialProgress:Float,onProgress:(Float)->Unit,battery:Float,onBattery:(Float)->Unit,autonomy:Float,onAutonomy:(Float)->Unit,
    chargeTime:Float,onChargeTime:(Float)->Unit,building:Boolean,onSpeed:(Int)->Unit,onToggle:()->Unit,onClose:()->Unit,
    onClear:()->Unit,onPreview:()->Unit,onStart:()->Unit,chargeLow:Float=20f,chargeHigh:Float=90f) {
    LubaDialog(onDismissRequest=onClose,dismissEnabled=!building,title=if (ui.engine?.preview?.mode?.isSurvey == true || preview?.mode?.isSurvey == true) tr("Survey simulation", "Simulation du relevé") else tr("Mowing simulation", "Simulation de tonte"),content={
        InfoNote(tr("Accelerated local simulation: no command is sent to the robot.", "Simulation locale accélérée : aucun ordre n'est envoyé au robot."))
        if(ui.state==null) {
            SettingsGroup(tr("Route", "Trajet")) {
                val order=(preview?.zoneIds?.takeIf { it.isNotEmpty() } ?: selected.toList()).mapIndexedNotNull { index,id ->
                    map.shapes.firstOrNull { it.id==id }?.let { "${index+1}. ${it.name.trim().ifBlank { "Zone ${it.id}" }}" } }
                KeyValueRow("Zones",if(order.isEmpty()) tr("No zone chosen", "Aucune zone choisie") else order.joinToString(" → "))
                KeyValueRow("Station",base?.let { baseOriginLabel(it.origin) } ?: tr("pending: put the robot on charge", "en attente : placez le robot en charge"))
                SecondaryPillButton(if(preview==null) tr("Choose the zones and compute", "Choisir les zones et calculer") else tr("Edit the task", "Modifier la tâche"),onPreview,Modifier.fillMaxWidth(),enabled=!building && map.complete)
            }
            SettingsGroup(tr("Scenario", "Scénario")) {
                SliderRow(tr("Mowing already done", "Tonte déjà réalisée"),initialProgress,onProgress,0f..100f,valueLabel="${initialProgress.toInt()} %",enabled=!building)
                SliderRow(tr("Battery at start", "Batterie au départ"),battery,onBattery,0f..100f,valueLabel="${battery.toInt()} %",enabled=!building)
                SliderRow(tr("Autonomy (100 % → 0 %)", "Autonomie (100 % → 0 %)"),autonomy,onAutonomy,5f..180f,valueLabel="${autonomy.toInt()} min",enabled=!building)
                SliderRow(tr("Full recharge", "Recharge complète"),chargeTime,onChargeTime,5f..120f,valueLabel="${chargeTime.toInt()} min",enabled=!building)
                InfoNote(tr("Return to the station at ${chargeLow.toInt()} %, resume at ${chargeHigh.toInt()} %. Constant provisional consumption, to be calibrated with the robot.",
                    "Retour à la station à ${chargeLow.toInt()} %, reprise à ${chargeHigh.toInt()} %. Consommation constante provisoire, à calibrer avec le robot."))
            }
        } else {
            val s=ui.state
            SettingsGroup {
                Text(s.phase.label,style=MaterialTheme.typography.titleMedium)
                Text("${(s.covered / ui.engine!!.total * 100).toInt()} % ${if (ui.engine?.preview?.mode?.isSurvey == true) tr("measured", "mesuré") else tr("mowed", "tondu")}",style=MaterialTheme.typography.headlineSmall,color=MammotionColors.GreenDeep)
                KeyValueRow(tr("Battery", "Batterie"),"${s.battery.toInt()} %")
                KeyValueRow(tr("Simulated time", "Temps simulé"),"${(s.elapsed/60).toInt()} min")
                KeyValueRow("Recharges","${s.cycles}")
                if(s.phase !in setOf(SimPhase.COMPLETE,SimPhase.BLOCKED)) SecondaryPillButton(if(ui.playing) "Pause" else tr("Resume", "Reprendre"),onToggle,Modifier.fillMaxWidth())
            }
            LogLines(s.log.takeLast(12).reversed(),title=tr("Log", "Journal"))
            TextAction(tr("Finish and prepare another scenario", "Terminer et préparer un autre scénario"),onClear,Modifier.fillMaxWidth())
        }
        SettingsGroup(tr("Playback speed", "Vitesse de lecture")) {
            ChipChoice(listOf(1,5,20,60,120,300).map { it to "×$it" },ui.speed,onSpeed)
        }
        InfoNote(tr("Theoretical model (docking 0.1 m/s, linear battery, pivot 45°/s, no inertia or slope): it helps examine the route, without validating the physical behavior of the robot.",
            "Modèle théorique (accostage 0,1 m/s, batterie linéaire, pivot 45°/s, sans inertie ni pente) : il aide à examiner le trajet, sans valider le comportement physique du robot."))
    },footer={
        if(ui.state==null) PrimaryPillButton(if(building) tr("Checking accesses…", "Vérification des accès…") else tr("Start the simulation", "Lancer la simulation"),onStart,Modifier.fillMaxWidth(),
            enabled=!building && preview!=null && base!=null && (manualBase || base.entryHeadingDegrees!=null))
        else SecondaryPillButton(tr("Show on map", "Voir sur la carte"),onClose,Modifier.fillMaxWidth())
    })
}
