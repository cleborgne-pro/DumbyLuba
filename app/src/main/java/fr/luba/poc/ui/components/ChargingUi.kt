package fr.luba.poc.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.ui.theme.NumericStyle

/** Origin of the displayed charge reading. */
enum class ChargeSource(private val labelEn: String, private val labelFr: String) {
    CLOUD("Mammotion account", "compte Mammotion"), BLE("Bluetooth", "Bluetooth");

    val label: String get() = tr(labelEn, labelFr)
}

/** Robot on its station and powered: percentage known or not, charge finished. */
data class ChargeStatus(val percent: Int?, val source: ChargeSource = ChargeSource.CLOUD, val at: Long = 0L) {
    val full: Boolean get() = (percent ?: 0) >= 100
    val label: String get() = if (full) tr("Charge complete", "Charge terminée") else tr("Charging", "En charge")
}

/** An account reading is repeated every 30 s: beyond 3 min, the value is stale. */
const val CLOUD_CHARGE_MAX_AGE_MS = 180_000L
/** BLE publishes the device state continuously: 15 s without a report is enough to declare it silent. */
const val BLE_CHARGE_MAX_AGE_MS = 15_000L
/** A timestamp up to 10 s "in the future" stays valid (screen clock sampled every 5 s); beyond that, the clock is inconsistent. */
const val CLOCK_LEAD_TOLERANCE_MS = 10_000L

/**
 * Charge state to display. Main source: the Mammotion account telemetry (`report.dev` report read
 * through the API/MQTT: battery and charging contact). If an active BLE link publishes a more recent
 * report, it wins. A reading that is too old shows nothing: a frozen charge must not suggest that it
 * is still going on. `chargeState` 1 or 2 = charging contact established, as for docking detection.
 */
fun chargeStatus(cloud: Telemetry?, ble: Telemetry?, now: Long): ChargeStatus? {
    // Timestamp lead tolerance: the screen clock is only re-read every 5 s while a BLE report arrives every second.
    fun fresh(t: Telemetry?, maxAge: Long) = t?.takeIf { it.lastDeviceAt?.let { at -> now - at in -CLOCK_LEAD_TOLERANCE_MS..maxAge } == true }
    val c = fresh(cloud, CLOUD_CHARGE_MAX_AGE_MS)
    val b = fresh(ble, BLE_CHARGE_MAX_AGE_MS)
    val (t, source) = when {
        b != null && (c == null || b.lastDeviceAt!! >= c.lastDeviceAt!!) -> b to ChargeSource.BLE
        c != null -> c to ChargeSource.CLOUD
        else -> return null
    }
    return if (t.chargeState in setOf(1, 2)) ChargeStatus(t.battery?.coerceIn(0, 100), source, t.lastDeviceAt!!) else null
}

/**
 * Battery threshold shown next to the level: lower threshold (return to base) when the robot is not charging, upper
 * threshold (mowing resume) when it is charging.
 */
data class BatteryThreshold(val percent: Int, val charging: Boolean) {
    /** Short form for the status pill: down arrow (return) or up arrow (resume). */
    val short: String get() = if (charging) "↑ $percent %" else "↓ $percent %"
    val label: String get() = if (charging) tr("resume at $percent %", "reprise à $percent %") else tr("return at $percent %", "retour à $percent %")
    val description: String get() = if (charging) tr("Upper threshold: mowing resumes at $percent %", "Seuil haut : reprise de la tonte à $percent %") else tr("Lower threshold: return to base at $percent %", "Seuil bas : retour à la base à $percent %")
    /** Battery at or below the return threshold (not charging): shown in red. */
    fun reached(battery: Int?): Boolean = !charging && battery != null && battery <= percent
}

/** Mowing in progress: its thresholds; otherwise those of the next task (Map → Mow → Task settings). */
fun batteryThreshold(charging: Boolean, route: fr.luba.poc.simulation.RobotRouteUi, taskLow: Int, taskHigh: Int): BatteryThreshold {
    // End of mowing below the lower threshold: return floor; short remainder while charging: early start level.
    val low = if (route.running) route.lowWatch ?: route.returnPercent else taskLow
    val high = if (route.running) route.chargeTarget ?: route.resumePercent else taskHigh
    return BatteryThreshold(if (charging) high else low, charging)
}

/** Recharge thresholds of the next task (low, high), re-read each time the screen is shown. */
@Composable
fun rememberTaskChargeThresholds(): Pair<Int, Int> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { fr.luba.poc.map.MowingTaskStore(context).read().sanitized().let { it.chargeLow.toInt() to it.chargeHigh.toInt() } }
}

/** Wall clock refreshed periodically, to expire a reading that has become too old. */
@Composable
fun rememberWallClock(periodMs: Long = 5_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(periodMs); value = System.currentTimeMillis() }
    }
    return now
}

/** Current charge state (Mammotion account, or more recent BLE), re-evaluated over time. */
@Composable
fun rememberChargeStatus(cloud: Telemetry?, ble: Telemetry?): ChargeStatus? {
    // Reading the sampled clock re-runs the evaluation every 5 s (expiry); the comparison uses the real time.
    rememberWallClock()
    return chargeStatus(cloud, ble, System.currentTimeMillis())
}

/** Gauge colour: red below 20 %, orange below 50 %, green above. */
@Composable
fun chargeColor(percent: Int?): Color = when {
    percent == null -> MammotionColors.Green
    percent < 20 -> MammotionColors.Danger
    percent < 50 -> MammotionColors.Warning
    else -> MammotionColors.Green
}

/**
 * Animated battery gauge: the level slides to the new value, a highlight sweeps the reserve
 * continuously and the bolt pulses until charging is complete.
 */
@Composable
fun ChargingBattery(percent: Int?, modifier: Modifier = Modifier, full: Boolean = (percent ?: 0) >= 100) {
    val colors = MammotionColors
    val tint = chargeColor(percent)
    val level by animateFloatAsState((percent ?: 0) / 100f, tween(900, easing = FastOutSlowInEasing), label = "level")
    val motion = rememberInfiniteTransition(label = "charge")
    val sweep by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "highlight")
    val pulse by motion.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bolt")
    val outline = colors.TextSecondary
    val track = colors.SurfaceMuted
    Box(modifier.aspectRatio(2.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawBattery(level, if (full) 0f else sweep, tint, track, outline) }
        // Bolt in the centre: white on the filled reserve, gauge colour on the empty background.
        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = if (level >= 0.55f) Color.White else tint,
            modifier = Modifier.fillMaxHeight(0.62f).aspectRatio(1f).padding(end = 6.dp)
                .graphicsLayer { alpha = if (full) 1f else pulse; scaleX = 0.9f + 0.1f * pulse; scaleY = scaleX })
    }
}

private fun DrawScope.drawBattery(level: Float, sweep: Float, tint: Color, track: Color, outline: Color) {
    val stroke = size.height * 0.07f
    val cap = Size(size.width * 0.05f, size.height * 0.36f)
    val body = Size(size.width - cap.width - stroke, size.height - stroke)
    val origin = Offset(stroke / 2, stroke / 2)
    val radius = CornerRadius(size.height * 0.2f)
    drawRoundRect(track, origin, body, radius)
    drawRoundRect(outline, origin, body, radius, style = Stroke(stroke))
    drawRoundRect(outline, Offset(origin.x + body.width + stroke * 0.3f, (size.height - cap.height) / 2), cap, CornerRadius(cap.width / 2))
    // Energy reserve: inner fill with a margin, animated highlight inside.
    val inset = stroke * 1.4f
    val inner = Size(body.width - 2 * inset + stroke, body.height - 2 * inset + stroke)
    val start = Offset(origin.x + inset - stroke / 2, origin.y + inset - stroke / 2)
    val filled = inner.width * level.coerceIn(0f, 1f)
    if (filled <= 0f) return
    val innerRadius = CornerRadius(size.height * 0.12f)
    drawRoundRect(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.78f), tint), start.x, start.x + filled), start, Size(filled, inner.height), innerRadius)
    if (sweep > 0f) clipRect(start.x, start.y, start.x + filled, start.y + inner.height) {
        val band = inner.width * 0.35f
        val x = start.x - band + (filled + band) * sweep
        drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0f)), x, x + band),
            Offset(x, start.y), Size(band, inner.height))
    }
}

/**
 * "Charging" card: animated gauge, percentage counting up to the received value and progress
 * bar. Shown under the robot on the home screen.
 */
@Composable
fun ChargingCard(status: ChargeStatus, modifier: Modifier = Modifier, threshold: BatteryThreshold? = null) {
    val colors = MammotionColors
    val tint = chargeColor(status.percent)
    val shown by animateIntAsState(status.percent ?: 0, tween(900, easing = FastOutSlowInEasing), label = "percentage")
    val percentText = status.percent?.let { "$shown" } ?: "—"
    Surface(modifier.fillMaxWidth().semantics { contentDescription = tr("${status.label}, battery ${status.percent?.let { "$it %" } ?: "unknown"}", "${status.label}, batterie ${status.percent?.let { "$it %" } ?: "inconnue"}") +
            (threshold?.let { ", ${it.description}" } ?: "") },
        shape = MaterialTheme.shapes.large, color = colors.Surface, border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))) {
        Row(Modifier.padding(Dimens.Lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
            ChargingBattery(status.percent, Modifier.width(112.dp), status.full)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // "Charging" is already said by the home status badge: the overline names the gauge.
                Text(tr("BATTERY", "BATTERIE"), style = EyebrowStyle, color = tint, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(percentText, style = NumericStyle, color = colors.TextPrimary, maxLines = 1)
                    if (status.percent != null) Text(" %", style = MaterialTheme.typography.titleMedium, color = colors.TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp))
                }
                ChargingBar(status.percent, status.full, Modifier.fillMaxWidth().height(6.dp), mark = threshold?.percent)
                // Upper threshold: level at which mowing resumes (marker on the bar).
                val reprise = threshold?.let { " · ${it.label}" } ?: ""
                Text(if (status.full) tr("Battery full$reprise · ${status.source.label}", "Batterie pleine$reprise · ${status.source.label}") else tr("On the station$reprise · ${status.source.label}", "Sur la station$reprise · ${status.source.label}"),
                    style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary, maxLines = 1)
            }
        }
    }
}

/** Thin charge bar: animated level and a light wave running along the filled part. */
@Composable
fun ChargingBar(percent: Int?, full: Boolean, modifier: Modifier = Modifier, mark: Int? = null) {
    val tint = chargeColor(percent)
    val track = MammotionColors.Outline
    val level by animateFloatAsState((percent ?: 0) / 100f, tween(900, easing = FastOutSlowInEasing), label = "bar")
    val sweep by rememberInfiniteTransition(label = "bar-wave")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave")
    val markColor = MammotionColors.TextPrimary
    Canvas(modifier.clip(CircleShape)) {
        drawRect(track)
        val filled = size.width * level.coerceIn(0f, 1f)
        // Threshold marker (mowing resume), drawn last to stay visible over the filled part.
        fun drawMark() { mark?.takeIf { it in 1..99 }?.let { m ->
            val x = size.width * m / 100f
            drawRect(markColor.copy(alpha = 0.7f), Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        } }
        if (filled <= 0f) { drawMark(); return@Canvas }
        drawRect(tint, size = Size(filled, size.height))
        if (!full) clipRect(right = filled) {
            val band = size.width * 0.25f
            val x = -band + (filled + band) * sweep
            drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), x, x + band),
                Offset(x, 0f), Size(band, size.height))
        }
        drawMark()
    }
}

/** Charging halo: rings that widen and fade around a centre (under the robot). */
fun DrawScope.drawChargingRings(center: Offset, radius: Float, phase: Float, tint: Color, rings: Int = 3) {
    repeat(rings) { i ->
        val p = (phase + i.toFloat() / rings) % 1f
        drawCircle(tint.copy(alpha = 0.28f * (1f - p)), radius * (0.55f + 0.45f * p), center, style = Stroke(radius * 0.025f))
    }
}
