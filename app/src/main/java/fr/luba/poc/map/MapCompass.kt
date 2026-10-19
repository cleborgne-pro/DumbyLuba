package fr.luba.poc.map

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Small map compass: the red needle follows the north of the rotated map ([rotation], degrees, clockwise on screen).
 * A tap brings north back to the top by the shortest way, animated; [onRotateBy] receives rotation steps to apply
 * around the screen center.
 */
@Composable
fun MapCompass(rotation: Double, onRotateBy: (Double) -> Unit, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(rotation)
    var job by remember { mutableStateOf<Job?>(null) }
    val measurer: TextMeasurer = rememberTextMeasurer()
    val heading = ((360 - MapCamera.signedDegrees(rotation)) % 360).roundToInt() % 360
    Surface(
        onClick = {
            job?.cancel()
            val turn = -MapCamera.signedDegrees(current)
            job = scope.launch {
                var applied = 0f
                animate(0f, turn.toFloat(), animationSpec = tween(350)) { value, _ ->
                    onRotateBy((value - applied).toDouble()); applied = value
                }
                // Exact north despite the rounding of the successive steps.
                onRotateBy(-MapCamera.signedDegrees(current))
            }
        },
        modifier = modifier.size(40.dp).semantics {
            contentDescription = if (heading == 0) tr("Compass, north up", "Boussole, nord en haut") else tr("Compass, map rotated by $heading°, tap to bring north back up", "Boussole, carte tournée de $heading°, toucher pour remettre le nord en haut")
        },
        shape = CircleShape, color = colors.Overlay, shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(30.dp).rotate(rotation.toFloat())) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2
                val half = r * 0.24f
                drawCircle(colors.Outline, r * 0.98f, c, style = Stroke(1.dp.toPx()))
                val north = Path().apply { moveTo(c.x, c.y - r * 0.62f); lineTo(c.x + half, c.y); lineTo(c.x - half, c.y); close() }
                val south = Path().apply { moveTo(c.x, c.y + r * 0.62f); lineTo(c.x + half, c.y); lineTo(c.x - half, c.y); close() }
                drawPath(north, colors.Danger)
                drawPath(south, colors.TextTertiary)
                val label = measurer.measure("N", TextStyle(fontSize = 7.sp, fontWeight = FontWeight.Bold, color = colors.TextPrimary))
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - r - label.size.height * 0.15f))
            }
        }
    }
}
