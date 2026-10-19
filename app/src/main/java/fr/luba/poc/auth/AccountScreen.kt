package fr.luba.poc.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/** Mammotion account screen, styled like the sign-in screens of the original app. */
@Composable
fun AccountScreen(model: CloudViewModel, state: CloudUi, onOpenMap: () -> Unit = {}) {
    var email by remember { mutableStateOf(state.accountEmail) }
    var password by remember { mutableStateOf("") }
    var rememberAccount by remember { mutableStateOf(true) }
    LaunchedEffect(state.connected, state.accountSaved) {
        if (!state.connected) {
            val saved=model.savedAccount()
            email=saved?.email ?: state.accountEmail
            password=saved?.password.orEmpty()
        } else password=""
    }

    // Content of a pushed page (title and scrolling provided by PushedPage).
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Lg), verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                Icon(if (state.connected) Icons.Rounded.CloudQueue else Icons.Rounded.CloudOff, contentDescription = null,
                    tint = if (state.connected) MammotionColors.Green else MammotionColors.TextTertiary, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (state.connected) state.accountEmail.ifBlank { tr("Account connected", "Compte connecté") } else tr("Not connected", "Non connecté"), style = MaterialTheme.typography.titleMedium)
                    if (!state.status.equals(tr("Account not connected", "Compte non connecté"), ignoreCase = true))
                        Text(state.status, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                }
            }
            if (state.busy) LubaProgress(Modifier.fillMaxWidth())
        }
        if (!state.connected) {
            SettingsGroup(tr("Sign in", "Connexion")) {
                LubaTextField(email, { email = it }, tr("Email", "E-mail"), enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                LubaTextField(password, { password = it }, tr("Password", "Mot de passe"), enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                LubaCheckRow(tr("Remember my credentials", "Mémoriser mes identifiants"), rememberAccount, { rememberAccount = it }, enabled = !state.busy,
                    subtitle = tr("Encrypted on this phone (Android Keystore)", "Chiffrés sur ce téléphone (Android Keystore)"))
                PrimaryPillButton(tr("Sign in", "Se connecter"), { model.login(email.trim(), password, rememberAccount); password = "" }, Modifier.fillMaxWidth(),
                    enabled = email.isNotBlank() && password.isNotEmpty() && !state.busy)
                if (state.accountSaved) ForgetAction(state.busy) { model.forgetAccount(); email = ""; password = "" }
            }
        } else {
            SettingsGroup(tr("Account robots", "Robots du compte")) {
                if (state.robots.isEmpty()) InfoNote(tr("No robot: refresh the list.", "Aucun robot : actualisez la liste."))
                state.robots.forEach { robot ->
                    NavRow(robot.name, icon = Icons.Rounded.CloudQueue, value = tr("Read the map", "Lire la carte"), onClick = { if (!state.busy) model.fetch(robot) })
                }
                if (state.map.complete) PrimaryPillButton(tr("Open the map", "Ouvrir la carte"), onOpenMap, Modifier.fillMaxWidth())
                SecondaryPillButton(tr("Refresh the list", "Actualiser la liste"), model::refresh, Modifier.fillMaxWidth(), enabled = !state.busy, icon = Icons.Rounded.Refresh)
            }
            // Full account read on opening (state, maintenance, history, firmware).
            LaunchedEffect(Unit) { model.refreshAccount() }
            if (state.selected != null) AccountRobotPanel(state, model::refreshAccount, onStartFirmwareUpdate = model::startFirmwareUpdate,
                onFollowFirmwareUpdate = model::followFirmwareUpdate, onStopFirmwareFollow = model::stopFirmwareFollow)
            SettingsGroup("Session") {
                SecondaryPillButton(tr("Sign out", "Déconnexion"), model::logout, Modifier.fillMaxWidth(), enabled = !state.busy, icon = Icons.AutoMirrored.Rounded.Logout)
                if (state.accountSaved) ForgetAction(state.busy) { model.forgetAccount(); email = ""; password = "" }
            }
        }
        InfoNote(tr("The account is used to read the map, state, maintenance, positioning, versions and mowing history. The only possible actions, on request: the native return to base (Charging station, without Bluetooth) and the firmware update. No mowing, blade activation or setting goes through the cloud.", "Le compte sert à lire la carte, l’état, l’entretien, le positionnement, les versions et l’historique des tontes. Seules actions possibles, sur demande : le retour natif à la base (Station de charge, sans Bluetooth) et la mise à jour du firmware. Aucune tonte, activation des lames ni aucun réglage ne passe par le cloud."))
        if (state.events.isNotEmpty()) SettingsGroup(tr("Account log", "Journal du compte")) {
            LogLines(state.events.takeLast(15).reversed())
        }
    }
}

/** Deletes the remembered credentials (and the session), centred under the main action. */
@Composable
private fun ForgetAction(busy: Boolean, onForget: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextAction(tr("Forget saved credentials", "Oublier les identifiants enregistrés"), onForget, enabled = !busy, danger = true)
    }
}
