package fr.luba.poc.ui.brand

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.luba.poc.R
import kotlinx.coroutines.delay
import fr.luba.poc.i18n.tr
import kotlin.math.PI
import kotlin.math.sin

object DumbyBrand {
    val Forest = Color(0xFF10291F)
    val Mint = Color(0xFFA7F3B9)
    val Cream = Color(0xFFF0FFF3)
}

@Composable
fun DumbyBrandHeader(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = DumbyBrand.Forest, modifier = Modifier.size(36.dp)) {
            Image(painterResource(R.drawable.dumby_mark), contentDescription = null)
        }
        Text("DumbyLuba", style = MaterialTheme.typography.titleMedium)
    }
}

/** Short intro, interruptible, with no network dependency and no robot command. */
@Composable
fun DumbyIntro(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (motion == 0f) {
            finish()
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(1050, easing = LinearEasing))
        delay(150)
        exit.animateTo(0f, tween(180))
        finish()
    }
    val p = progress.value
    Box(
        Modifier.fillMaxSize().background(DumbyBrand.Forest)
            .clickable(role = Role.Button, onClickLabel = tr("Skip the startup animation", "Passer l'animation de démarrage")) { finish() },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }) {
            val center = Offset(size.width / 2, size.height * 0.43f)
            val radius = size.minDimension * 0.54f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF275D3E).copy(alpha = 0.55f), Color.Transparent), center, radius), radius, center)
            // Garden lines: drawn like a coverage route.
            val w = size.width * 0.64f
            val h = size.height * 0.14f
            val route = Path().apply {
                moveTo(center.x - w / 2, center.y - h)
                lineTo(center.x + w / 2 - 36, center.y - h)
                quadraticTo(center.x + w / 2, center.y - h, center.x + w / 2, center.y - h / 2)
                quadraticTo(center.x + w / 2, center.y, center.x + w / 2 - 36, center.y)
                lineTo(center.x - w / 2 + 36, center.y)
                quadraticTo(center.x - w / 2, center.y, center.x - w / 2, center.y + h / 2)
                quadraticTo(center.x - w / 2, center.y + h, center.x - w / 2 + 36, center.y + h)
                lineTo(center.x + w / 2, center.y + h)
            }
            val measure = PathMeasure().apply { setPath(route, false) }
            val drawn = Path()
            measure.getSegment(0f, measure.length * p, drawn)
            drawPath(drawn, DumbyBrand.Mint.copy(alpha = 0.12f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(DumbyBrand.Mint.copy(alpha = 0.04f), radius * (0.55f + p * 0.08f), center, style = Stroke(1.dp.toPx()))
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp).graphicsLayer { alpha = exit.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(R.drawable.dumby_mark), contentDescription = null,
                modifier = Modifier.size(240.dp).graphicsLayer {
                    val arrival = (p * 3f).coerceIn(0f, 1f)
                    scaleX = 0.86f + arrival * 0.14f
                    scaleY = scaleX
                    translationY = -sin(p * PI).toFloat() * 10.dp.toPx()
                    rotationZ = sin(p * PI * 2).toFloat() * 3f
                },
            )
            Text("DumbyLuba", color = DumbyBrand.Cream, fontSize = 34.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
                modifier = Modifier.graphicsLayer { alpha = (p * 3f).coerceIn(0f, 1f) })
            Spacer(Modifier.height(12.dp))
            Text(tr("Your garden, simply.", "Votre jardin, simplement."), color = DumbyBrand.Mint.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.graphicsLayer { alpha = ((p - 0.15f) * 3f).coerceIn(0f, 1f) })
        }
        Text(tr("Tap to continue", "Touchez pour continuer"), color = DumbyBrand.Mint.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 28.dp)
                .graphicsLayer { alpha = exit.value })
    }
}
