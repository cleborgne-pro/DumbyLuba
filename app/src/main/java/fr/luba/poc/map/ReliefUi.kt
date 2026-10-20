package fr.luba.poc.map

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.components.ChipChoice
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.SliderRow
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.cos
import kotlin.math.sin

/** "Topographic map" terrain palette: low in blue, then green, yellow, high in brown. */
private val reliefStops = listOf(Color(0xFF2C7BB6), Color(0xFF3FAE9B), Color(0xFF8CC84B), Color(0xFFF2D04A), Color(0xFFC77A34), Color(0xFF7B4A26))

fun reliefColor(level: Float, alpha: Float = 1f): Color {
    val t = level.coerceIn(0f, 1f) * (reliefStops.size - 1)
    val i = t.toInt().coerceAtMost(reliefStops.size - 2)
    return lerp(reliefStops[i], reliefStops[i + 1], t - i).copy(alpha = alpha)
}

/** "Traffic light" palette of the signals and the slope: red (bad), orange, yellow, green (good), bright hues without going through a dark olive. */
private val signalStops = listOf(Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047))

/** Color of a quality 0 (bad, red) .. 1 (good, green). */
fun qualityColor(strength: Float, alpha: Float = 1f): Color {
    val t = strength.coerceIn(0f, 1f) * (signalStops.size - 1)
    val i = t.toInt().coerceAtMost(signalStops.size - 2)
    return lerp(signalStops[i], signalStops[i + 1], t - i).copy(alpha = alpha)
}

/** Color of a heatmap cell: red→orange→yellow→green for the signals and the slope, topographic palette for the terrain. */
fun heatColor(signal: SurveySignal, cell: SignalCell): Color {
    val strength = SignalCoverage.strength(signal, cell)
    return if (signal == SurveySignal.RELIEF) reliefColor(strength, 0.75f) else qualityColor(strength, 0.9f)
}

/** Darkens (< 1) or lightens (> 1) a color according to the terrain shading, without changing its hue. */
fun shaded(color: Color, shade: Float): Color = if (shade <= 1f)
    Color(color.red * shade, color.green * shade, color.blue * shade, color.alpha)
else (shade - 1f).let { k -> Color(color.red + (1 - color.red) * k, color.green + (1 - color.green) * k, color.blue + (1 - color.blue) * k, color.alpha) }

/**
 * Single view of the surveys: the color carries the chosen layer (Wi-Fi by default), the terrain reads at the same time,
 * as shading on the map and as a shape in the 3D view.
 */
data class SignalReliefView(val signal: SurveySignal, val cells: List<SignalCell>, val relief: ReliefResult?, val shading: Boolean) {
    private val byCell = cells.associateBy { kotlin.math.floor(it.point.x).toInt() to kotlin.math.floor(it.point.y).toInt() }
    val shadingActive get() = shading && relief != null && signal != SurveySignal.RELIEF
    fun color(cell: SignalCell): Color {
        val base = heatColor(signal, cell)
        return if (shadingActive) relief?.shadeAt(cell.point)?.let { shaded(base, it) } ?: base else base
    }
    /** Color of a 3D terrain cell: that of the chosen layer, neutral grey if the cell has no such reading. */
    fun color3d(height: SignalCell): Color = if (signal == SurveySignal.RELIEF) reliefColor(height.level ?: 0.5f)
        else byCell[kotlin.math.floor(height.point.x).toInt() to kotlin.math.floor(height.point.y).toInt()]?.let { heatColor(signal, it).copy(alpha = 1f) }
            ?: Color(0xFFB8BEC4)
}

fun signalReliefView(samples: List<SignalSample>, signal: SurveySignal, relief: ReliefResult?, shading: Boolean) = SignalReliefView(signal,
    when (signal) {
        SurveySignal.SLOPE -> relief?.slopeCells.orEmpty()
        SurveySignal.RELIEF -> relief?.heightCells.orEmpty()
        else -> SignalCoverage.cells(samples, signal)
    }, relief, shading)

/** Legend of the chosen color, then what the overlaid terrain says. */
fun signalReliefLegend(view: SignalReliefView, samples: List<SignalSample>): String {
    val color = if (view.signal.terrain) terrainLegend(view.signal, view.relief, samples)
    else if (view.cells.isEmpty()) tr("No readings for this signal.", "Aucune mesure pour ce signal.")
    else when (view.signal) {
        SurveySignal.RTK -> tr("Red: no Fix · green: Fix (mean per cell)", "Rouge : sans Fix · vert : Fix (moyenne par cellule)")
        SurveySignal.GPS -> tr("Red: 0 · green: 30 satellites or more", "Rouge : 0 · vert : 30 satellites ou plus")
        SurveySignal.WIFI -> tr("Red: −80 dBm or less · orange: −70 · yellow: −60 · green: −50 dBm or more", "Rouge : −80 dBm ou moins · orange : −70 · jaune : −60 · vert : −50 dBm ou plus")
        else -> tr("Red: −100 dBm or less · yellow: −67 · green: −50 dBm or more", "Rouge : −100 dBm ou moins · jaune : −67 · vert : −50 dBm ou plus")
    }
    val mount = view.relief?.mount?.takeIf { samples.any { it.hasTilt } }?.let { "\n" + mountDescription(it) }.orEmpty()
    if (view.signal.terrain) return color + mount
    val relief = view.relief ?: return "$color\n" + tr("Terrain: ", "Relief : ") + terrainLegend(SurveySignal.RELIEF, null, samples).replaceFirstChar { it.lowercase() }
    return "$color\n" + tr("Terrain: height difference %.0f cm", "Relief : dénivelé %.0f cm").format(relief.dropM * 100) +
        (if (view.shadingActive) tr(", as shading (dark slopes: facing away from the north-west)", ", en ombrage (versants sombres : pente opposée au nord-ouest)")
            else tr(", shading hidden", ", ombrage masqué")) +
        (if (relief.components > 1) tr(" · ${relief.components} unlinked pieces", " · ${relief.components} morceaux non reliés") else "") + mount
}

/** "top of the phone 35° to the left of the front (deduced from the robot's starts and stops, 24 readings)" */
fun mountDescription(mount: MountEstimate): String {
    val a = PhoneTilt.normalize(mount.angle).let { kotlin.math.round(it).toInt() % 360 }
    val where = when {
        a == 0 -> tr("towards the front", "vers l'avant")
        a == 180 -> tr("towards the back", "vers l'arrière")
        a == 90 -> tr("towards the left", "vers la gauche")
        a == 270 -> tr("towards the right", "vers la droite")
        a < 180 -> tr("$a° to the left of the front", "à $a° à gauche de l'avant")
        else -> tr("${360 - a}° to the right of the front", "à ${360 - a}° à droite de l'avant")
    }
    return mountPrefix() + tr("top $where (${mount.source.label}", "haut $where (${mount.source.label}") +
        (if (mount.events > 0) tr(", ${mount.events} readings)", ", ${mount.events} mesures)") else ")")
}

/** Prefix of [mountDescription], removed where the orientation is shown alone. */
private fun mountPrefix(): String = tr("Phone: ", "Téléphone : ")

/** Orientation of the gateway phone on the robot, remembered on the pilot phone. */
object ReliefSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("relief", Context.MODE_PRIVATE)
    fun mount(context: Context): PhoneMount =
        runCatching { PhoneMount.valueOf(prefs(context).getString("mount", null) ?: "") }.getOrDefault(PhoneMount.AUTO)
    fun saveMount(context: Context, mount: PhoneMount) { prefs(context).edit().putString("mount", mount.name).apply() }

    /** Reference taken on flat ground (tilt zero and default orientation), or null. */
    fun calibration(context: Context): TiltCalibration? = prefs(context).let { p ->
        if (!p.contains("cal_gz")) return null
        TiltCalibration(PhoneGravity(p.getFloat("cal_gx", 0f).toDouble(), p.getFloat("cal_gy", 0f).toDouble(), p.getFloat("cal_gz", 9.8f).toDouble()),
            if (p.contains("cal_angle")) p.getFloat("cal_angle", 0f).toDouble() else null, p.getLong("cal_at", 0))
    }
    fun saveCalibration(context: Context, c: TiltCalibration) {
        prefs(context).edit().putFloat("cal_gx", c.reference.x.toFloat()).putFloat("cal_gy", c.reference.y.toFloat())
            .putFloat("cal_gz", c.reference.z.toFloat()).putLong("cal_at", c.at)
            .apply { if (c.angle != null) putFloat("cal_angle", c.angle.toFloat()) else remove("cal_angle") }.apply()
    }
    fun clearCalibration(context: Context) {
        prefs(context).edit().remove("cal_gx").remove("cal_gy").remove("cal_gz").remove("cal_angle").remove("cal_at").apply()
    }
}

/**
 * Saves the current position of the gateway phone as the reference: robot still on flat, level ground.
 * The measured tilt becomes the zero; [orientation] (current orientation: chosen or detected) becomes the default
 * orientation. Readings received from the gateway for 4 s, refused if they move.
 */
@Composable
fun TiltCalibrationDialog(orientation: MountEstimate?, calibration: TiltCalibration?, onSaved: (TiltCalibration) -> Unit,
                          onCleared: () -> Unit, onDismiss: () -> Unit) {
    var live by remember { mutableStateOf<PhoneGravity?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, NoteTone>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { live = RobotTilt.current(System.currentTimeMillis()); kotlinx.coroutines.delay(250) } }
    val date = remember { java.text.SimpleDateFormat(tr("yyyy-MM-dd HH:mm", "dd/MM/yyyy à HH:mm"), fr.luba.poc.i18n.I18n.locale) }
    // Closing blocked during the capture (4 s of readings).
    LubaDialog(onDismissRequest = onDismiss, title = tr("Flat-ground reference", "Référence sur sol plat"), dismissEnabled = !capturing, content = {
        InfoNote(tr("Robot stopped on flat, level ground (terrace, path, garage), gateway phone fixed in its " +
            "survey position. Do not touch the robot during the capture.",
            "Robot arrêté sur un sol plat et horizontal (terrasse, allée, garage), téléphone passerelle fixé dans sa " +
            "position de relevé. Ne pas toucher le robot pendant l'enregistrement."))
        SettingsGroup(tr("Current reading", "Mesure actuelle")) {
            val g = live
            if (g != null) KeyValueRow(tr("Tilt", "Inclinaison"), "%.1f°".format(TiltCalibration(g, null, 0).tiltDegrees))
            else StatusNote(tr("No tilt received: control the robot through the gateway (Android phone mounted on the robot).", "Aucune inclinaison reçue : piloter le robot via la passerelle (téléphone Android posé sur le robot)."), NoteTone.WARNING)
            if (orientation != null) KeyValueRow(tr("Current orientation", "Orientation en cours"), mountDescription(orientation).removePrefix(mountPrefix()))
            Text(tr("Saves the current tilt as zero", "Enregistre l'inclinaison actuelle comme zéro") +
                if (orientation != null) tr(" and the current orientation.", " et l'orientation en cours.")
                else tr("; orientation unknown (no survey): automatic detection stays active.", " ; orientation non connue (aucun relevé) : la détection automatique reste active."),
                style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        }
        message?.let { (text, tone) -> StatusNote(text, tone) }
        calibration?.let { c ->
            SettingsGroup(tr("Saved reference", "Référence enregistrée")) {
                KeyValueRow("Date", date.format(java.util.Date(c.at)))
                KeyValueRow(tr("Tilt", "Inclinaison"), "%.1f°".format(c.tiltDegrees))
                c.angle?.let { KeyValueRow("Orientation", mountDescription(MountEstimate(it, MountSource.CALIBRATED)).removePrefix(mountPrefix())) }
                TextAction(tr("Clear the reference", "Effacer la référence"), onCleared, enabled = !capturing, danger = true)
            }
        }
    }, footer = {
        PrimaryPillButton(if (capturing) tr("Capturing… keep the robot still", "Enregistrement… robot immobile") else tr("Save the current position", "Enregistrer la position actuelle"), {
            capturing = true; message = null
            scope.launch {
                val readings = mutableListOf<PhoneGravity>()
                repeat(16) {
                    RobotTilt.current(System.currentTimeMillis())?.let { g -> if (readings.lastOrNull() !== g) readings += g }
                    kotlinx.coroutines.delay(250)
                }
                val (saved, error) = TiltCalibration.capture(readings, orientation?.angle, System.currentTimeMillis())
                capturing = false
                message = if (saved != null) { onSaved(saved); tr("Reference saved (phone tilted by %.1f°).", "Référence enregistrée (téléphone incliné de %.1f°).").format(saved.tiltDegrees) to NoteTone.SUCCESS }
                    else error.orEmpty().takeIf { it.isNotBlank() }?.let { it to NoteTone.WARNING }
            }
        }, Modifier.fillMaxWidth(), enabled = live != null && !capturing)
    })
}

/** Legend of the terrain layers (and why they are empty). */
fun terrainLegend(signal: SurveySignal, relief: ReliefResult?, samples: List<SignalSample>): String {
    if (relief == null) return if (samples.none { it.hasTilt })
        tr("No tilt received: the gateway phone lying flat on the robot sends the slope.", "Aucune inclinaison reçue : le téléphone passerelle posé à plat sur le robot transmet la pente.")
    else tr("Tilts received but unusable: phone not flat (screen facing the sky) or wrong orientation.", "Inclinaisons reçues mais inexploitables : téléphone pas à plat (écran vers le ciel) ou orientation incorrecte.")
    val offset = if (relief.calibrated) tr("phone corrected by the flat-ground reference", "téléphone redressé par la référence sur sol plat") else if (relief.offsetEstimated) tr("phone mounting offset corrected (%.1f %% front, %.1f %% side)", "défaut de pose du téléphone corrigé (%.1f %% avant, %.1f %% côté)").format(relief.offsetForward * 100 + 0.0, relief.offsetRight * 100 + 0.0)
        .replace("-0.0 ", "0.0 ").replace("-0,0 ", "0,0 ")
    else tr("mounting offset not corrected (no round trip)", "défaut de pose non corrigé (aucun aller-retour)")
    val parts = if (relief.components > 1) tr(" · ${relief.components} unlinked pieces, independent heights", " · ${relief.components} morceaux non reliés, hauteurs indépendantes") else ""
    return when (signal) {
        // The mounting offset text contains "%": concatenated after formatting, never formatted again.
        SurveySignal.SLOPE -> tr("Green: flat · red: 30 %% and above · max %.0f %%", "Vert : plat · rouge : 30 %% et plus · max %.0f %%").format(relief.slopeCells.maxOfOrNull { it.value } ?: 0.0) + " · " + offset
        else -> tr("Blue: lowest point · brown: highest point · height difference %.0f cm", "Bleu : point le plus bas · brun : point le plus haut · dénivelé %.0f cm").format(relief.dropM * 100) + parts + " · " + offset
    }
}

/**
 * 3D terrain: each measured cell is placed at its (exaggerated) height, map boundaries at the lowest level.
 * Drag horizontally to rotate, vertically to change the view tilt.
 */
@Composable
fun Relief3DView(cells: List<SignalCell>, map: RobotMap, modifier: Modifier = Modifier, exaggeration: Float = 5f,
                  colorOf: (SignalCell) -> Color = { reliefColor(it.level ?: 0.5f) }) {
    var azimuth by remember { mutableStateOf(-35f) }
    var elevation by remember { mutableStateOf(35f) }
    val edge = MammotionColors.TextSecondary
    Canvas(modifier.pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            azimuth += drag.x * 0.4f
            elevation = (elevation + drag.y * 0.25f).coerceIn(10f, 85f)
        }
    }) {
        if (cells.isEmpty()) return@Canvas
        val outline = map.shapes.filter { it.type == 0 }.map { it.points }
        val all = cells.map { it.point } + outline.flatten()
        val cx = (all.minOf { it.x } + all.maxOf { it.x }) / 2; val cy = (all.minOf { it.y } + all.maxOf { it.y }) / 2
        val a = Math.toRadians(azimuth.toDouble()); val e = Math.toRadians(elevation.toDouble())
        // Orthographic view: rotation around the vertical, then camera tilt.
        fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
            val dx = x - cx; val dy = y - cy
            val rx = dx * cos(a) - dy * sin(a); val ry = dx * sin(a) + dy * cos(a)
            return Triple(rx, -(ry * sin(e) + z * cos(e)), ry * cos(e) - z * sin(e))
        }
        val zOf = { c: SignalCell -> c.value * exaggeration }
        val corners = all.flatMap { listOf(project(it.x, it.y, 0.0)) } + cells.map { project(it.point.x, it.point.y, zOf(it)) }
        val minX = corners.minOf { it.first }; val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }; val maxY = corners.maxOf { it.second }
        val scale = (minOf(size.width / (maxX - minX + 2), size.height / (maxY - minY + 2))).toFloat() * 0.92f
        val ox = size.width / 2 - ((minX + maxX) / 2).toFloat() * scale
        val oy = size.height / 2 - ((minY + maxY) / 2).toFloat() * scale
        fun screen(p: Triple<Double, Double, Double>) = Offset(ox + p.first.toFloat() * scale, oy + p.second.toFloat() * scale)
        outline.forEach { ring ->
            if (ring.size < 2) return@forEach
            val path = Path()
            ring.forEachIndexed { i, p -> val s = screen(project(p.x, p.y, 0.0)); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
            path.close()
            drawPath(path, edge.copy(alpha = 0.6f), style = Stroke(1.2.dp.toPx()))
        }
        // Painter's algorithm: farthest cells first.
        cells.sortedByDescending { project(it.point.x, it.point.y, zOf(it)).third }.forEach { c ->
            val z = zOf(c)
            val top = listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5).map { (u, v) -> screen(project(c.point.x + u, c.point.y + v, z)) }
            val foot = screen(project(c.point.x, c.point.y, 0.0)); val head = screen(project(c.point.x, c.point.y, z))
            drawLine(edge.copy(alpha = 0.25f), foot, head, 1f)
            val path = Path().apply { moveTo(top[0].x, top[0].y); top.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            // Color of the chosen layer, tinted by the shading to keep the slopes readable.
            val color = colorOf(c)
            drawPath(path, color)
            drawPath(path, lerp(color, Color.Black, 0.35f), style = Stroke(0.6f))
        }
    }
}

@Composable
fun Relief3DDialog(view: SignalReliefView, map: RobotMap, legend: String, onDismiss: () -> Unit) {
    var exaggeration by remember { mutableStateOf(5f) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("${view.signal.label} on the terrain", "${view.signal.label} sur le relief"), content = {
        Relief3DContent(view, map, legend, exaggeration) { exaggeration = it }
    })
}

@Composable
fun Relief3DContent(view: SignalReliefView, map: RobotMap, legend: String, exaggeration: Float, onExaggeration: (Float) -> Unit) {
    val heights = view.relief?.heightCells.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        if (heights.isEmpty()) StatusNote(tr("No height rebuilt for this survey.", "Aucune hauteur reconstruite pour ce relevé."))
        Relief3DView(heights, map, Modifier.fillMaxWidth().height(380.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground),
            exaggeration) { c -> view.relief?.shadeAt(c.point)?.takeIf { view.signal != SurveySignal.RELIEF }?.let { shaded(view.color3d(c), it) } ?: view.color3d(c) }
        InfoNote(tr("Drag to rotate · shape: terrain · color: ${view.signal.label}", "Glisser pour tourner · forme : relief · couleur : ${view.signal.label}"))
        SettingsGroup {
            SliderRow(tr("Height exaggeration", "Exagération des hauteurs"), exaggeration, onExaggeration, 1f..20f, valueLabel = "×${exaggeration.toInt()}", steps = 18)
            if (legend.isNotBlank()) Legend(legend)
        }
    }
}

/** Label of a heatmap setting (above its chips), with an optional hint. */
@Composable
private fun ControlLabel(title: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    }
}

/** Legend of the colors and the terrain: one line per item, discreet text. */
@Composable
private fun Legend(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
}

/**
 * Settings shared by the map and the history: displayed color, terrain shading, gateway phone orientation, 3D view.
 * A non-null [onHide] adds "Hide". Placed in the caller's [SettingsGroup].
 */
@Composable
fun SignalReliefControls(view: SignalReliefView, samples: List<SignalSample>, onSignal: (SurveySignal) -> Unit, onShading: (Boolean) -> Unit,
                         mount: PhoneMount?, onMount: (PhoneMount) -> Unit, on3d: (() -> Unit)?, hidden: Boolean = false, onHide: (() -> Unit)? = null,
                         calibration: TiltCalibration? = null, onCalibrate: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        ControlLabel(tr("Color", "Couleur"))
        ChipChoice(SurveySignal.entries.map<SurveySignal, Pair<SurveySignal?, String>> { it to it.label } + listOfNotNull(onHide?.let { null to tr("Hide", "Masquer") }),
            if (hidden) null else view.signal, { if (it == null) onHide?.invoke() else onSignal(it) })
        Legend(signalReliefLegend(view, samples))
    }
    if (view.relief != null) ToggleRow(tr("Terrain shading", "Relief en ombrage"), view.shading, onShading, enabled = view.signal != SurveySignal.RELIEF,
        subtitle = if (view.signal == SurveySignal.RELIEF) tr("Not applicable: the color already shows the terrain", "Sans objet : la couleur montre déjà le relief") else null)
    if (mount != null && samples.any { it.hasTilt }) PhoneMountChoice(mount, calibration?.angle != null, onMount)
    if (onCalibrate != null) NavRow(tr("Flat-ground reference", "Référence sur sol plat"),
        subtitle = if (calibration != null) tr("Saved: tilt zero", "Enregistrée : zéro d'inclinaison") + (if (calibration.angle != null) tr(" and orientation", " et orientation") else "")
            else tr("Save the current tilt and orientation", "Enregistrer l'inclinaison et l'orientation actuelles"), onClick = onCalibrate)
    if (on3d != null && view.relief != null) NavRow(tr("3D view: ${view.signal.label} on the terrain", "Vue 3D : ${view.signal.label} sur le relief"),
        icon = androidx.compose.material.icons.Icons.Rounded.Landscape, onClick = on3d)
}

/** Choice of the gateway phone orientation (Slope / Terrain). */
@Composable
fun PhoneMountChoice(mount: PhoneMount, saved: Boolean, onMount: (PhoneMount) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        ControlLabel(tr("Gateway phone orientation", "Orientation du téléphone passerelle"), tr("Lying flat, screen facing the sky", "Posé à plat, écran vers le ciel"))
        // "Saved" only appears with a flat-ground reference that contains an orientation.
        ChipChoice(PhoneMount.entries.filter { saved || it != PhoneMount.CALIBRATED }.map { it to it.label }, mount, onMount)
    }
}
