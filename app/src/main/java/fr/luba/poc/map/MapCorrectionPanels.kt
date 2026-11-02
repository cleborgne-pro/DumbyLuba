package fr.luba.poc.map

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.ui.components.LubaSlider
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// Panels of the map correction editor: header, notice bar, browse / create / shape panels and the correction list.

@Composable
internal fun EditorHeader(active: Int, onBack: () -> Unit, onOriginal: (Boolean) -> Unit, onList: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Surface(modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.Md), shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Correct the map", "Corriger la carte"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Local correction · the Mammotion map is not modified", "Correction locale · la carte Mammotion n'est pas modifiée"), style = MaterialTheme.typography.bodySmall,
                    color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Press and hold: the original Mammotion map, to compare.
            Surface(shape = CircleShape, color = colors.SurfaceMuted, modifier = Modifier.size(40.dp).pointerInput(Unit) {
                detectTapGestures(onPress = { onOriginal(true); tryAwaitRelease(); onOriginal(false) })
            }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Visibility, contentDescription = tr("Hold to see the original", "Maintenir pour voir l'original")) } }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onList) {
                Box {
                    Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Corrections")
                    if (active > 0) Surface(shape = CircleShape, color = colors.Green, modifier = Modifier.align(Alignment.TopEnd).size(16.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$active", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MammotionColors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm), content = content)
    }
}

@Composable
internal fun NoticeBar(text: String, onUndo: (() -> Unit)?, onDismiss: () -> Unit) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.medium, color = colors.Ink, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Dimens.Lg, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = colors.OnInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onUndo != null) TextButton(onClick = onUndo) { Text(tr("Undo", "Annuler"), color = colors.Green) }
            TextButton(onClick = onDismiss) { Text("OK", color = colors.OnInk) }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)

@Composable
internal fun BrowsePanel(count: Int, onNoGo: () -> Unit, onShape: () -> Unit, onObstacle: () -> Unit, onList: () -> Unit) {
    Text(tr("Tap a zone or a no-go zone to correct", "Touchez une zone ou une no-go à corriger"), style = MaterialTheme.typography.titleSmall)
    Hint(tr("One finger moves the map, two fingers zoom and rotate. Or add:", "Un doigt déplace la carte, deux doigts zooment et tournent. Ou ajoutez :"))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton(tr("Drawn no-go", "No-go tracée"), onNoGo, Modifier.weight(1f))
        SecondaryPillButton(tr("Shape no-go", "No-go forme"), onShape, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton("Obstacle", onObstacle, Modifier.weight(1f))
        SecondaryPillButton("Corrections ($count)", onList, Modifier.weight(1f))
    }
}

/** Preview summary: area before → after, part outside the Mammotion boundary, or the reason for the refusal. */
@Composable
private fun PreviewLine(preview: EditorPreview?, emptyHint: String) {
    val colors = MammotionColors
    preview?.refusal?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.Danger); return }
    val c = preview?.candidate
    if (c == null) { Hint(emptyHint); return }
    val status = preview.result.status[c.id]
    if (status != null && !status.applied) {
        Text(preview.result.reasons[c.id] ?: status.label, style = MaterialTheme.typography.bodyMedium, color = colors.Danger)
        return
    }
    val after = preview.afterArea
    val before = preview.beforeArea
    val text = when {
        after == null -> c.label
        before == null || c is AddedNoGo && before == after -> tr("Area: ${MapCorrections.squareMeters(after)}", "Surface : ${MapCorrections.squareMeters(after)}")
        else -> tr("Area: ${MapCorrections.squareMeters(before)} → ${MapCorrections.squareMeters(after)}", "Surface : ${MapCorrections.squareMeters(before)} → ${MapCorrections.squareMeters(after)}")
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    if (preview.outsideM2 >= 0.05) Text(tr("Including ${MapCorrections.squareMeters(preview.outsideM2)} outside the Mammotion boundary: mowed under your responsibility", "Dont ${MapCorrections.squareMeters(preview.outsideM2)} hors du contour Mammotion : tondu sous votre responsabilité"),
        style = MaterialTheme.typography.bodySmall, color = colors.Warning)
}

private fun validCandidate(preview: EditorPreview?): MapCorrection? {
    val c = preview?.candidate ?: return null
    return c.takeIf { preview.result.status[it.id]?.applied == true }
}

@Composable
private fun ValidateRow(preview: EditorPreview?, onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit, undo: (() -> Unit)? = null, undoEnabled: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
        if (undo != null) IconButton(onClick = undo, enabled = undoEnabled) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = tr("Undo the last gesture", "Annuler le dernier geste")) }
        SecondaryPillButton(tr("Discard", "Abandonner"), onCancel, Modifier.weight(1f))
        val c = validCandidate(preview)
        PrimaryPillButton(tr("Validate", "Valider"), { c?.let(onValidate) }, Modifier.weight(1f), enabled = c != null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kinds: List<PrimitiveKind>, selected: PrimitiveKind?, onKind: (PrimitiveKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEach { k -> FilterChip(selected = k == selected, onClick = { onKind(k) }, label = { Text(k.label) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatePanel(
    creating: Creating, reopened: Boolean, draft: List<Point>?, primitive: PrimitiveShape?, obstacleKind: ObstacleKind, preview: EditorPreview?,
    onKind: (PrimitiveKind) -> Unit, onObstacle: (ObstacleKind) -> Unit, onRadius: (Double) -> Unit,
    onUndo: () -> Unit, onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit,
) {
    when (creating) {
        Creating.TRACE -> {
            Text(if (reopened) tr("Edit the no-go zone", "Modifier la no-go") else tr("New drawn no-go zone", "Nouvelle no-go tracée"), style = MaterialTheme.typography.titleSmall)
            Hint(tr("Tap the map to place each point; drag a point to move it, \"+\" adds one between two.", "Touchez la carte pour poser chaque point ; glissez un point pour le déplacer, « + » en ajoute un entre deux."))
            PreviewLine(preview, tr("${draft?.size ?: 0} point(s): at least 3", "${draft?.size ?: 0} point(s) : 3 au moins"))
            ValidateRow(preview, onCancel, onValidate, onUndo, (draft?.size ?: 0) > 0)
        }
        Creating.SHAPE -> {
            Text(if (reopened) tr("Edit the no-go zone", "Modifier la no-go") else tr("New no-go zone", "Nouvelle no-go"), style = MaterialTheme.typography.titleSmall)
            KindChips(PrimitiveKind.entries, primitive?.kind, onKind)
            Hint(tr("Drag the shape to move it, the white square to resize it, the top circle to rotate it.", "Glissez la forme pour la déplacer, le carré blanc pour la redimensionner, le rond du haut pour la tourner.") +
                (primitive?.let { " ${it.sizeText()}" } ?: ""))
            PreviewLine(preview, tr("Place the shape", "Placez la forme"))
            ValidateRow(preview, onCancel, onValidate)
        }
        Creating.OBSTACLE -> {
            Text(if (reopened) tr("Edit the obstacle", "Modifier l'obstacle") else tr("New obstacle", "Nouvel obstacle"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ObstacleKind.entries.forEach { k -> FilterChip(selected = k == obstacleKind, onClick = { onObstacle(k) }, label = { Text(k.label) }) }
            }
            val radius = (primitive?.widthM ?: 0.5) / 2
            Text(tr("Radius: ${MapCorrections.meters(radius)} m · tap the map to place it", "Rayon : ${MapCorrections.meters(radius)} m · touchez la carte pour le placer"), style = MaterialTheme.typography.bodyMedium)
            LubaSlider(radius.toFloat(), { onRadius(((it * 20).toInt() / 20.0).coerceAtLeast(0.1)) }, valueRange = 0.1f..2f)
            PreviewLine(preview, tr("Place the obstacle", "Placez l'obstacle"))
            ValidateRow(preview, onCancel, onValidate)
        }
    }
}

@Composable
internal fun ShapePanel(
    target: MapShape, tool: EditTool, reopened: Boolean, draft: List<Point>?, draftBase: List<Point>?, edgeLine: List<Point>, otherSide: Boolean,
    selectedVertex: Int?, primitive: PrimitiveShape?, removing: Boolean, offset: Float, preview: EditorPreview?, canUndo: Boolean,
    onTool: (EditTool) -> Unit, onEdgeApply: () -> Unit, onEdgeUndo: () -> Unit, onOtherSide: () -> Unit, onDeleteVertex: (Int) -> Unit,
    onUndo: () -> Unit, onKind: (PrimitiveKind) -> Unit, onRemoving: (Boolean) -> Unit, onOffset: (Float) -> Unit,
    onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit,
) {
    val name = MapCorrections.shapeName(target)
    Text(if (target.kind == ShapeKind.NO_GO) "No-go · $name".takeIf { name != "No-go" } ?: "No-go" else name, style = MaterialTheme.typography.titleSmall)
    if (!reopened) Segmented(EditTool.entries.map { it to it.label }, tool, onTool)
    when (tool) {
        EditTool.POINTS -> {
            Hint(tr("Drag a point to move it; \"+\" adds a point; zoom in to see all the points.", "Glissez un point pour le déplacer ; « + » ajoute un point ; zoomez pour voir tous les points."))
            if (selectedVertex != null) SecondaryPillButton(tr("Delete this point", "Supprimer ce point"), { onDeleteVertex(selectedVertex) }, Modifier.fillMaxWidth(),
                enabled = (draft?.size ?: 0) > 3, icon = Icons.Rounded.Delete)
            PreviewLine(preview, tr("No change", "Aucun changement"))
            ValidateRow(preview, onCancel, onValidate, onUndo, canUndo)
        }
        EditTool.EDGE -> {
            Hint(tr("Tap the boundary at the start of the edge, the points of the new edge, then the boundary at the end: the part in between is replaced.", "Touchez le contour au début du bord, les points du nouveau bord, puis le contour à la fin : la partie entre les deux est remplacée."))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("Previous point", "Point précédent"), onEdgeUndo, Modifier.weight(1f), enabled = edgeLine.isNotEmpty())
                SecondaryPillButton(if (otherSide) tr("Side: other", "Côté : autre") else tr("Side: short", "Côté : court"), onOtherSide, Modifier.weight(1f), enabled = edgeLine.size >= 2)
            }
            PrimaryPillButton(tr("Apply this edge", "Appliquer ce bord"), onEdgeApply, Modifier.fillMaxWidth(), enabled = edgeLine.size >= 2)
            PreviewLine(preview, if (draft == draftBase) tr("No edge applied", "Aucun bord appliqué") else tr("Edge applied", "Bord appliqué"))
            ValidateRow(preview, onCancel, onValidate, onUndo, canUndo)
        }
        EditTool.SHAPE -> {
            Segmented(listOf(false to tr("Add", "Ajouter"), true to tr("Remove", "Retirer")), removing, onRemoving)
            KindChips(PrimitiveKind.entries, primitive?.kind, onKind)
            Hint(tr("Drag the shape, the white square for the size, the top circle for the rotation.", "Glissez la forme, le carré blanc pour la taille, le rond du haut pour la rotation.") + (primitive?.let { " ${it.sizeText()}" } ?: ""))
            PreviewLine(preview, tr("Place the shape", "Placez la forme"))
            ValidateRow(preview, onCancel, onValidate)
        }
        EditTool.OFFSET -> {
            Text(tr("Offset: ${MapCorrections.centimeters(offset.toDouble())}", "Décalage : ${MapCorrections.centimeters(offset.toDouble())}") +
                " (${if (offset >= 0) tr("outwards", "vers l'extérieur") else tr("inwards", "vers l'intérieur")})",
                style = MaterialTheme.typography.bodyMedium)
            LubaSlider(offset, { onOffset((Math.round(it * 20) / 20f)) }, valueRange = -1f..1f)
            PreviewLine(preview, tr("Zero offset", "Décalage nul"))
            ValidateRow(preview, onCancel, onValidate)
        }
    }
}

@Composable
internal fun CorrectionList(
    corrections: List<MapCorrection>, corrected: CorrectedMap,
    onToggle: (MapCorrection, Boolean) -> Unit, onShow: (MapCorrection) -> Unit, onEdit: (MapCorrection) -> Unit,
    onDelete: (MapCorrection) -> Unit, onResetAll: () -> Unit,
) {
    val colors = MammotionColors
    val format = remember { SimpleDateFormat("d MMM HH:mm", if (I18n.french) Locale.FRANCE else Locale.ENGLISH) }
    Text("Corrections (${corrections.size})", style = MaterialTheme.typography.titleSmall)
    if (corrections.isEmpty()) { Hint(tr("No correction: the map is the Mammotion one.", "Aucune correction : la carte est celle de Mammotion.")); return }
    Hint(tr("Applied in order; each one can be disabled or deleted on its own.", "Appliquées dans l'ordre ; chacune peut être désactivée ou supprimée seule."))
    corrections.asReversed().forEach { c ->
        val status = corrected.status[c.id]
        val reason = corrected.reasons[c.id]
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(c.createdAtMs.takeIf { it > 0 }?.let { format.format(Date(it)) }, status?.label, reason).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == null || status.applied && status != CorrectionStatus.CHANGED) colors.TextSecondary else colors.Warning)
                }
                Switch(checked = c.enabled, onCheckedChange = { onToggle(c, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.Green))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onShow(c) }, enabled = status?.applied == true) { Text(tr("Show", "Voir")) }
                val editable = c is ShapeOffset || c is AddedNoGo || (c is ShapePatch && c.primitive != null)
                if (editable) TextButton(onClick = { onEdit(c) }) { Text(tr("Edit", "Modifier")) }
                TextButton(onClick = { onDelete(c) }) { Text(tr("Delete", "Supprimer"), color = colors.Danger) }
            }
        }
    }
    SecondaryPillButton(tr("Restore everything", "Tout rétablir"), onResetAll, Modifier.fillMaxWidth())
}
