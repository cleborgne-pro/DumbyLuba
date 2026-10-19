package fr.luba.poc.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Battery0Bar
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.MammotionColors

/** Positioning quality shown as in the Mammotion app (Fix / Float / Single / None). */
enum class PositionQuality(private val labelEn: String, private val labelFr: String) {
    FIX("Fix", "Fix"), FLOAT("Float", "Float"), SINGLE("Single", "Single"), NONE("None", "Aucun"), UNKNOWN("—", "—");

    val label: String get() = tr(labelEn, labelFr)
}

/** Reported RTK state (`rtk.status`, PyMammotion RTKStatus): only 4 is a FIX. `pos_level` is not a solution quality. */
fun positionQuality(rtkStatus: Int?): PositionQuality = when (rtkStatus) {
    null -> PositionQuality.UNKNOWN
    4 -> PositionQuality.FIX
    5 -> PositionQuality.FLOAT
    1, 2 -> PositionQuality.SINGLE
    0 -> PositionQuality.NONE
    else -> PositionQuality.FLOAT
}

/**
 * Status pill at the top of the screen: link (direct Bluetooth or gateway), account, positioning and battery, like
 * the status bar of the Mammotion app. A tap opens the details. [stale]: link open but telemetry pending.
 * [trailing]: segments added on the right (weather), with their own tap action.
 */
@Composable
fun DeviceStatusPill(
    bluetooth: Boolean,
    battery: Int?,
    position: PositionQuality,
    modifier: Modifier = Modifier,
    cloud: Boolean = false,
    charging: Boolean = false,
    /** Show the gateway phone battery (recent HEALTH frame) next to the robot's. */
    gateway: Boolean = false,
    /** Robot link held by the Wi-Fi gateway: router icon instead of Bluetooth. */
    viaGateway: Boolean = false,
    stale: Boolean = false,
    /** Threshold shown after the level: return to base when not charging, mowing resume when charging. */
    threshold: BatteryThreshold? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MammotionColors
    val gatewayBattery = if (gateway) rememberGatewayBattery() else null
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = CircleShape, color = colors.Overlay, contentColor = colors.TextPrimary, shadowElevation = 1.dp,
        border = BorderStroke(1.dp, colors.Hairline),
    ) {
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(when { !bluetooth -> Icons.Rounded.BluetoothDisabled; viaGateway -> Icons.Rounded.Router; else -> Icons.Rounded.Bluetooth },
                contentDescription = when {
                    !bluetooth -> tr("Robot disconnected", "Robot déconnecté")
                    viaGateway -> if (stale) tr("Connected via the gateway, telemetry pending", "Connecté via la passerelle, télémétrie en attente") else tr("Connected via the gateway", "Connecté via la passerelle")
                    else -> if (stale) tr("Bluetooth connected, telemetry pending", "Bluetooth connecté, télémétrie en attente") else tr("Bluetooth connected", "Bluetooth connecté")
                },
                tint = when { !bluetooth -> colors.TextTertiary; stale -> colors.Warning; else -> colors.Value }, modifier = Modifier.size(18.dp))
            if (cloud) Icon(Icons.Rounded.Cloud, contentDescription = tr("Mammotion account", "Compte Mammotion"), tint = colors.Value, modifier = Modifier.size(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Rounded.SatelliteAlt, contentDescription = tr("Positioning", "Positionnement"), modifier = Modifier.size(16.dp),
                    tint = when (position) { PositionQuality.FIX -> colors.Green; PositionQuality.FLOAT, PositionQuality.SINGLE -> colors.Warning; else -> colors.TextTertiary })
                Text(position.label, style = MaterialTheme.typography.labelMedium, color = colors.TextPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (charging) {
                    // Charging: pulsing bolt and percentage counting towards the received value.
                    val beat by rememberInfiniteTransition(label = "pill-charge").animateFloat(0.4f, 1f,
                        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "bolt")
                    val shown by animateIntAsState(battery ?: 0, tween(900), label = "percentage")
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = tr("Charging", "En charge"), modifier = Modifier.size(18.dp)
                        .graphicsLayer { alpha = beat }, tint = chargeColor(battery))
                    Text(battery?.let { "$shown %" } ?: "—", style = MaterialTheme.typography.labelLarge, color = colors.TextPrimary)
                } else {
                    // Red at the return threshold (or below 20 % with no known threshold).
                    val low = threshold?.reached(battery) ?: ((battery ?: 100) <= 20)
                    Icon(batteryIcon(battery, false), contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (low) colors.Danger else colors.Green)
                    Text(battery?.let { "$it %" } ?: "—", style = MaterialTheme.typography.labelLarge, color = colors.TextPrimary)
                }
                threshold?.let { t ->
                    Text(t.short, style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary, maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp).semantics { contentDescription = t.description })
                }
            }
            // Gateway: separated by a hairline, phone icon and secondary figures (the robot stays the main value).
            gatewayBattery?.let { (percent, plugged) ->
                Box(Modifier.width(1.dp).height(18.dp).background(colors.Hairline))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Rounded.Smartphone, contentDescription = tr("Gateway battery", "Batterie de la passerelle"), modifier = Modifier.size(15.dp),
                        tint = if (!plugged && percent <= 20) colors.Danger else colors.TextSecondary)
                    Text("$percent %", style = MaterialTheme.typography.labelMedium,
                        color = if (!plugged && percent <= 20) colors.Danger else colors.TextSecondary)
                    if (plugged) Icon(Icons.Rounded.Bolt, contentDescription = tr("Gateway charging", "Passerelle en charge"), modifier = Modifier.size(12.dp), tint = colors.Green)
                }
            }
            trailing()
        }
    }
}

/** Gateway phone battery (percentage, charging) while its last HEALTH frame is recent; null otherwise. */
@Composable
fun rememberGatewayBattery(): Pair<Int, Boolean>? {
    val latest by fr.luba.poc.gateway.GatewayHealthStore.latest.collectAsState()
    // Stale frame with no new reception: re-evaluated every 10 s (compared with the current time, never with the last tick's,
    // older than the frame that just arrived).
    val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(10_000); value = value + 1 } }
    return latest?.takeIf { tick >= 0 && it.fresh() && it.health.batteryPercent in 0..100 }?.let { it.health.batteryPercent to it.health.charging }
}

fun batteryIcon(battery: Int?, charging: Boolean = false): ImageVector = when {
    charging -> Icons.Rounded.BatteryChargingFull
    battery == null -> Icons.Rounded.Battery0Bar
    battery >= 90 -> Icons.Rounded.BatteryFull
    battery >= 50 -> Icons.Rounded.Battery5Bar
    battery >= 20 -> Icons.Rounded.Battery3Bar
    else -> Icons.Rounded.Battery0Bar
}

/** Mammotion primary button: "ink" pill (black in light theme). */
@Composable
fun PrimaryPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                      icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.Button), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MammotionColors.Ink, contentColor = MammotionColors.OnInk)) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.Sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Mammotion secondary button ("Preview"): grey pill. */
@Composable
fun SecondaryPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                        icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.Button), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MammotionColors.SurfaceMuted, contentColor = MammotionColors.TextPrimary),
        border = BorderStroke(1.dp, MammotionColors.Outline)) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.Sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Map action bar item: coloured icon in a disc, label below (compact format). */
@Composable
fun ActionBarItem(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier,
                  enabled: Boolean = true, highlighted: Boolean = false) {
    val colors = MammotionColors
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(38.dp).background(if (highlighted) tint else tint.copy(alpha = if (enabled) 0.14f else 0.06f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (highlighted) Color.White else if (enabled) tint else colors.TextTertiary)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (enabled) colors.TextPrimary else colors.TextTertiary, textAlign = TextAlign.Center)
    }
}

/** White action bar with rounded corners, at the bottom of the map. */
@Composable
fun ActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MammotionColors.Surface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Round floating button of the map's right vertical bar (recharge, recentre…). */
@Composable
fun MapSideButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  enabled: Boolean = true, tint: Color = MammotionColors.TextPrimary) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.size(44.dp), shape = CircleShape,
        color = MammotionColors.Overlay, contentColor = MammotionColors.TextPrimary, shadowElevation = 3.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = if (enabled) tint else MammotionColors.TextTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

/** Square choice tile (route mode), selection outlined in green. */
@Composable
fun ChoiceTile(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true) {
    val colors = MammotionColors
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.GreenSoft else colors.SurfaceMuted,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.Green else colors.Outline)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.Green else colors.TextSecondary, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
                color = if (selected) colors.TextPrimary else colors.TextSecondary)
        }
    }
}

/** Segmented control: the chosen option is an "ink" pill, like Mammotion's "Frequency". */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
                  enabled: Boolean = true) {
    val colors = MammotionColors
    Surface(modifier.fillMaxWidth(), shape = CircleShape, color = colors.SurfaceMuted) {
        Row(Modifier.padding(3.dp)) {
            options.forEach { (value, label) ->
                val active = value == selected
                Surface(onClick = { onSelect(value) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                    shape = CircleShape, color = if (active) colors.Ink else Color.Transparent) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (active) colors.OnInk else colors.TextSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
    }
}

/** Tab title (Service, Me): large, tight, with space above. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = Dimens.Sm, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
    }
}

/** Full-screen pushed page: back arrow, title, scrolling content and fixed bottom bar. */
@Composable
fun PushedPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MammotionColors.Background,
    contentPadding: Dp = Dimens.Lg,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = contentPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                content()
                Spacer(Modifier.height(Dimens.Lg))
            }
            if (bottomBar != null) Surface(color = MammotionColors.Surface, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Lg, vertical = Dimens.Md),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Sm), content = bottomBar)
            }
        }
    }
}

/** Settings group: overline in spaced capitals, white card with a thin hairline. */
@Composable
fun SettingsGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title.uppercase(), style = EyebrowStyle, color = MammotionColors.TextSecondary,
            modifier = Modifier.padding(start = 6.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MammotionColors.Surface,
            border = BorderStroke(1.dp, MammotionColors.Hairline)) {
            Column(Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Md), content = content)
        }
    }
}

/** Navigable setting row: title, value on the right, chevron. */
@Composable
fun NavRow(title: String, modifier: Modifier = Modifier, value: String? = null, icon: ImageVector? = null,
           subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MammotionColors
    Row(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).clip(MaterialTheme.shapes.small).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Box(Modifier.size(38.dp).background(colors.SurfaceMuted, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, maxLines = 1)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
    }
}


/** Safety warning at the top of pages that move the robot or the blades. */
@Composable
fun SafetyNotice(text: String, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.WarningSoft, contentColor = colors.TextPrimary) {
        Row(Modifier.padding(Dimens.Md), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.Top) {
            Icon(androidx.compose.material.icons.Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.Warning, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Discreet information note (limits, explanations), under the settings. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.Top) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Info, contentDescription = null, tint = colors.TextTertiary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
    }
}

/** Press-and-hold action: starts on press, stops on release (or on cancellation). */
@Composable
fun HoldToRunButton(label: String, activeLabel: String, active: Boolean, enabled: Boolean,
                    onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    val colors = MammotionColors
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val stop by androidx.compose.runtime.rememberUpdatedState(onStop)
    val cancel by androidx.compose.runtime.rememberUpdatedState(onCancel)
    val allowed by androidx.compose.runtime.rememberUpdatedState(enabled)
    Surface(
        // Fixed key: a state change during the press must not restart the detector and stop the action.
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (allowed) {
                    start()
                    var released = false
                    try { released = tryAwaitRelease() } finally { if (released) stop() else (cancel ?: stop)() }
                }
            })
        },
        shape = CircleShape, color = if (!enabled) colors.SurfaceMuted else if (active) colors.Danger else colors.Ink,
        contentColor = if (!enabled) colors.TextTertiary else if (active) Color.White else colors.OnInk,
    ) {
        Box(Modifier.padding(horizontal = Dimens.Lg, vertical = Dimens.Md), contentAlignment = Alignment.Center) {
            Text(if (active) activeLabel else label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

/** Horizontal choice chips (durations, types), selection in "ink". */
@Composable
fun <T> ChipChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MammotionColors.Ink, selectedLabelColor = MammotionColors.OnInk))
        }
    }
}

/** Explicit stop (blades, test): full-width red pill. */
@Composable
fun DangerPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Dimens.Button), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MammotionColors.Danger, contentColor = Color.White)) {
        Icon(androidx.compose.material.icons.Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Dimens.Sm))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
