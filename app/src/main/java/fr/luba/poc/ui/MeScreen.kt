package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.BuildConfig
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.i18n.AppLanguage
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.ScreenTitle
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.ThemeMode
import fr.luba.poc.update.ReleaseInfo
import fr.luba.poc.update.UpdateCheck
import fr.luba.poc.update.UpdateState

/** Mammotion-style "Me" tab: account, app preferences and information. */
@Composable
fun MeScreen(
    cloud: CloudUi,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAccount: () -> Unit,
    onAlerts: () -> Unit,
    onOpenMap: () -> Unit,
    alertsSummary: String,
    modifier: Modifier = Modifier,
    update: UpdateState = UpdateState(),
    onCheckUpdate: () -> Unit = {},
    onOpenRelease: (ReleaseInfo) -> Unit = {},
    onAutomaticUpdates: (Boolean) -> Unit = {},
    /** Local mode ([fr.luba.poc.auth.AccountMode]): no Mammotion account, its entries are hidden. */
    localOnly: Boolean = false,
    /** Native return or firmware update followed through the account: local mode cannot be entered. */
    localOnlyLocked: Boolean = false,
    onLocalOnly: (Boolean) -> Unit = {},
) {
    val colors = MammotionColors
    Column(modifier.fillMaxSize().background(colors.Background).verticalScroll(rememberScrollState()).padding(Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        ScreenTitle(tr("Me", "Moi"), subtitle = tr("Account, preferences and information", "Compte, préférences et informations"))
        // Profile: the whole card opens the Mammotion account (a single entry); hidden in local mode.
        SettingsGroup {
            if (!localOnly) {
                ProfileRow(cloud, onAccount)
                if (cloud.map.complete) NavRow(tr("Account map", "Carte du compte"), icon = Icons.Rounded.Map, onClick = onOpenMap)
            }
            ToggleRow(tr("Local mode (without Mammotion account)", "Mode local (sans compte Mammotion)"), localOnly, onLocalOnly,
                subtitle = when {
                    localOnly -> tr("No request to the Mammotion cloud. Bluetooth and the gateway are enough.", "Aucune requête au cloud Mammotion. Le Bluetooth et la passerelle suffisent.")
                    localOnlyLocked -> tr("Unavailable while a native return or a firmware update is followed.", "Indisponible pendant le suivi d'un retour natif ou d'une mise à jour du firmware.")
                    cloud.connected -> tr("Signs out of the account (saved credentials are kept) and hides its entries.", "Déconnecte le compte (les identifiants enregistrés sont gardés) et masque ses entrées.")
                    else -> tr("Hides the account entries: account map, camera, obstacle photo, firmware.", "Masque les entrées du compte : carte du compte, caméra, photo d'obstacle, firmware.")
                }, enabled = localOnly || !localOnlyLocked)
        }
        SettingsGroup(tr("App", "Application")) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                Text(tr("Appearance", "Apparence"), style = MaterialTheme.typography.bodyLarge)
                Segmented(ThemeMode.entries.map { it to it.label }, themeMode, onThemeModeChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                Text(tr("Language", "Langue"), style = MaterialTheme.typography.bodyLarge)
                Segmented(AppLanguage.entries.map { it to it.label }, I18n.chosen, onSelect = { if (it != I18n.chosen) onLanguageChange(it) })
            }
            NavRow("Notifications", icon = Icons.Rounded.Notifications, subtitle = alertsSummary, onClick = onAlerts)
        }
        // Connection, tests and logs: Service tab of the bottom bar (no second entry here).
        SettingsGroup(tr("About", "À propos")) {
            ValueRow(Icons.Rounded.Info, "Version", BuildConfig.VERSION_NAME)
            // New version on GitHub: opens the release page when one is available, otherwise checks now.
            val available = update.available
            NavRow(tr("Updates", "Mises à jour"), icon = if (available != null) Icons.Rounded.NewReleases else Icons.Rounded.SystemUpdate,
                subtitle = UpdateCheck.summary(update), enabled = !update.checking,
                onClick = { if (available != null) onOpenRelease(available) else onCheckUpdate() })
            if (update.publishedBuild) ToggleRow(tr("Check automatically", "Vérifier automatiquement"), update.automatic, onAutomaticUpdates,
                subtitle = tr("Once a day, reads the latest release on GitHub. Nothing is installed without you.",
                    "Une fois par jour, lit la dernière publication sur GitHub. Rien n'est installé sans vous."))
        }
        if (localOnly) InfoNote(tr("Local mode: map, planning, mowing, return to base, maintenance and alerts work over Bluetooth or the gateway. Unavailable: camera and obstacle photo, firmware updates, Mammotion history, remote reading and native return without Bluetooth.",
            "Mode local : carte, planification, tonte, retour à la base, entretien et alertes fonctionnent en Bluetooth ou par la passerelle. Indisponibles : caméra et photo d'obstacle, mises à jour du firmware, historique Mammotion, lecture à distance et retour natif sans Bluetooth."))
        else InfoNote(tr("DumbyLuba controls the robot locally (Bluetooth or the Wi-Fi gateway). The Mammotion account adds remote reading (map, status, history), the native return to base, firmware updates and the camera.", "DumbyLuba pilote le robot en local (Bluetooth ou passerelle Wi-Fi). Le compte Mammotion ajoute la lecture à distance (carte, état, historique), le retour natif à la base, les mises à jour du firmware et la caméra."))
    }
}

/** Profile header: avatar, account and status; opens the account page. */
@Composable
private fun ProfileRow(cloud: CloudUi, onClick: () -> Unit) {
    val colors = MammotionColors
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = tr("Mammotion account", "Compte Mammotion"), onClick = onClick)
        .padding(vertical = Dimens.Xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        Box(Modifier.size(52.dp).background(colors.SurfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = colors.TextSecondary, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cloud.accountEmail.ifBlank { tr("Mammotion account", "Compte Mammotion") },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (cloud.connected) tr("Signed in · remote map reading", "Connecté · lecture de carte à distance") else tr("Not signed in · sign in", "Non connecté · se connecter"),
                style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
    }
}

/** Non-navigable information row, aligned with [NavRow] (icon, title, value on the right). */
@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String) {
    val colors = MammotionColors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).background(colors.SurfaceMuted, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
