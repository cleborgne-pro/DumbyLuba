package fr.luba.poc.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import fr.luba.poc.diagnostics.PerceptionMapFrame
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.theme.MammotionColors
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Local perception map pushed by the robot (Service → Sensors and perception), decoded by Android (WebP, PNG) and
 * enlarged without smoothing so that each cell stays visible.
 */
@Composable
internal fun PerceptionMapGroup(frame: PerceptionMapFrame?) {
    SettingsGroup(tr("Robot perception map", "Carte de perception du robot")) {
        if (frame == null) {
            InfoNote(tr("None received since the app started. The robot sends it when it navigates by itself (seen at the start of the official return to base), even without a capture.",
                "Aucune reçue depuis le lancement. Le robot l'envoie quand il navigue seul (vue au début du retour officiel à la base), même sans capture."))
            return@SettingsGroup
        }
        val decoded = remember(frame) {
            frame.image.takeIf { it.isNotEmpty() }?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }?.let { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                bitmap to pixels.distinct().size
            }
        }
        KeyValueRow(tr("Received", "Reçues"), tr("${frame.count} · last at ", "${frame.count} · dernière à ") +
            SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(Date(frame.receivedAt)))
        KeyValueRow(tr("Image", "Image"), frame.info.label)
        KeyValueRow(tr("Robot state", "État du robot"), frame.robotState?.toString() ?: "—")
        if (decoded == null) InfoNote(tr("Image not decodable on this phone: raw content in the log (perception_map event).",
            "Image non décodable sur ce téléphone : contenu brut dans le journal (événement perception_map)."))
        decoded?.let { (bitmap, colors) ->
            KeyValueRow(tr("Colours", "Couleurs"), if (colors <= 1) tr("1 (uniform: nothing perceived)", "1 (uniforme : rien de perçu)") else colors.toString())
            Image(bitmap.asImageBitmap(), tr("Perception map", "Carte de perception"),
                Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).border(1.dp, MammotionColors.Outline),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        }
        InfoNote(tr("Meaning of the cells (orientation, scale, colours) not documented: compare with what surrounds the robot when the map arrives.",
            "Signification des cases (orientation, échelle, couleurs) non documentée : comparer avec ce qui entoure le robot à l'arrivée de la carte."))
    }
}
