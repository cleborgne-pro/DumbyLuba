package fr.luba.poc.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Router
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.auth.CloudUi
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.CoveragePlanner
import fr.luba.poc.map.MapThumbnail
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.ui.components.ChargingCard
import fr.luba.poc.ui.components.LubaProgress
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.components.rememberChargeStatus
import fr.luba.poc.ui.components.drawChargingRings
import fr.luba.poc.ui.components.PositionQuality
import fr.luba.poc.ui.components.batteryIcon
import fr.luba.poc.ui.components.positionQuality
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.MammotionColors

/**
 * "Device" home as a showcase: cut-out photo of the LUBA 2 AWD on a halo, drop shadow on the
 * ground, name and status; then the key indicators (battery, positioning, area), the map with
 * quick mowing and the shortcuts. Tapping the robot opens the map, as in the Mammotion app.
 */
@Composable
fun HomeScreen(
    state: RobotUi,
    cloudState: CloudUi,
    map: RobotMap,
    modifier: Modifier = Modifier,
    onOpenMap: () -> Unit = {},
    onStartMowing: () -> Unit = {},
    onFetch: () -> Unit = {},
    onAccount: () -> Unit = {},
    onOpenRobot: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onAlerts: () -> Unit = {},
    alertsBadge: Boolean = false,
    /** Mammotion account telemetry for this robot (null if the account follows another robot). */
    apiTelemetry: fr.luba.poc.protocol.Telemetry? = cloudState.telemetry,
    /** "Before the first mowing" card, below the map (empty once done or hidden). */
    checklist: @Composable () -> Unit = {},
) {
    val colors = MammotionColors
    val t = state.telemetry
    val connected = state.connection != ConnectionState.Disconnected
    val zones = map.shapes.filter { it.kind == ShapeKind.ZONE }
    val area = if (map.complete) CoveragePlanner.selectedArea(map, zones.map { it.id }.toSet()) else 0.0
    val working = state.mowing.active || state.robotRoute.running
    // Robot link: "via the gateway" on the pilot side, "over Bluetooth" on the gateway placed on the robot.
    val context = androidx.compose.ui.platform.LocalContext.current
    val role = remember { fr.luba.poc.gateway.GatewayConfig.read(context).role }
    val gateway by fr.luba.poc.gateway.GatewayService.status.collectAsStateWithLifecycle()
    // Control via the gateway: its reachability is tracked separately, a robot that is off does not make it look cut off.
    val pilot = role == fr.luba.poc.gateway.GatewayRole.CLIENT
    val link = linkStatus(state, role, gateway, fr.luba.poc.gateway.rememberPilotGatewayReach(pilot))
    // Charge and battery: Mammotion account (API), or active BLE if more recent.
    val charge = rememberChargeStatus(apiTelemetry, t.takeIf { state.ready })
    val battery = charge?.percent ?: t.battery ?: apiTelemetry?.battery ?: state.lastKnown?.battery
    val position = positionQuality(t.rtkStatus)
    // Gateway health: shown on the home screen only when there is a problem (details in Service → Wi-Fi gateway).
    val gatewayWarnings = fr.luba.poc.gateway.rememberPilotGatewayHealth(pilot)?.warnings().orEmpty()
    val charging = charge != null
    // Threshold shown next to the battery: return to base when not charging, mowing resume when charging.
    val (taskLow, taskHigh) = fr.luba.poc.ui.components.rememberTaskChargeThresholds()
    val threshold = fr.luba.poc.ui.components.batteryThreshold(charging, state.robotRoute, taskLow, taskHigh)
    // Displayed model (photo and label): Bluetooth or account name, account product key if it is the same robot.
    val robotName = state.selected?.name?.takeIf { it.isNotBlank() } ?: link.robotName?.takeIf { it.isNotBlank() }
        ?: state.lastKnown?.robotName?.takeIf { it.isNotBlank() } ?: cloudState.selected?.name
    val model = RobotModel.detect(robotName, cloudState.selected?.takeIf { robotName == null || it.name.equals(robotName, ignoreCase = true) }?.productKey)

    Box(modifier.fillMaxSize().background(colors.Background), contentAlignment = Alignment.TopCenter) {
      CompositionLocalProvider(LocalContentColor provides colors.TextPrimary) {
        // Showcase background: gradient at the top of the page, under the status bar.
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(colors.HomeTop, colors.Background))))
        Column(
            Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .statusBarsPadding().padding(horizontal = Dimens.Lg),
        ) {
            HomeHeader(state, link, cloudState.connected, connected, alertsBadge, gatewayWarnings.isNotEmpty(), onAlerts, onOpenRobot)
            RobotHero(model, onOpenMap, charging = charge != null && !charge.full)

            // Robot identity and status.
            Column(Modifier.fillMaxWidth().padding(top = Dimens.Xs, bottom = Dimens.Lg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = robotName ?: model.label
                Text(if (!model.named || name.equals(model.label, ignoreCase = true)) "MAMMOTION" else "MAMMOTION · ${model.label}",
                    style = EyebrowStyle, color = colors.TextTertiary)
                Row(Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = tr("Device settings", "Réglages de l'appareil"), onClick = onOpenRobot)
                    .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name,
                        style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
                }
                val (label, tint) = when {
                    state.robotRoute.running -> state.robotRoute.status to colors.Green
                    state.mowing.active -> state.mowing.status to colors.Green
                    charge != null -> charge.label to colors.Green
                    state.ready -> tr("Ready", "Prêt") to colors.Green
                    // Gateway phone: the link is shown in the header, the badge gives the control state.
                    link.connected -> (if (gateway.robot.pilot) tr("Controlled from the other phone", "Piloté depuis l'autre téléphone") else tr("Waiting for the pilot", "En attente du pilote")) to colors.Green
                    connected || link.connecting -> tr("Connecting…", "Connexion en cours…") to colors.Warning
                    link.gatewayReachable == false -> link.short to colors.Warning
                    link.gatewayReachable == true -> link.short to colors.TextSecondary
                    else -> tr("Offline", "Hors ligne") to colors.TextSecondary
                }
                StatusChip(label, tint, pulsing = charge != null && !charge.full && !working)
                // Battery (when not charging: the charging card shows it) and position quality, on a discreet line.
                val showBattery = !charging && battery != null
                val showPosition = position != PositionQuality.UNKNOWN
                if (showBattery || showPosition) Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Lg), verticalAlignment = Alignment.CenterVertically) {
                    if (showBattery) MiniStat(batteryIcon(battery, false), "$battery % · ${threshold.label}",
                        if (threshold.reached(battery)) colors.Danger else colors.Green)
                    if (showPosition) MiniStat(Icons.Rounded.SatelliteAlt, position.label,
                        when (position) { PositionQuality.FIX -> colors.Green; PositionQuality.FLOAT, PositionQuality.SINGLE -> colors.Warning; else -> colors.TextTertiary })
                }
            }

            // Gateway attention point: one line, only when needed.
            if (gatewayWarnings.isNotEmpty()) {
                Surface(onClick = onOpenRobot, modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.Md), shape = MaterialTheme.shapes.medium,
                    color = colors.WarningSoft) {
                    Row(Modifier.padding(horizontal = Dimens.Md, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        Icon(Icons.Rounded.Router, contentDescription = null, tint = colors.Warning, modifier = Modifier.size(18.dp))
                        Text(gatewayWarnings.first().replaceFirstChar { it.uppercase() } + if (gatewayWarnings.size > 1) " (+${gatewayWarnings.size - 1})" else "",
                            style = MaterialTheme.typography.bodySmall, color = colors.TextPrimary, maxLines = 2, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
                    }
                }
            }

            // Charging: animated gauge and percentage, under the robot identity.
            AnimatedVisibility(charge != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (charge != null) ChargingCard(charge, Modifier.padding(bottom = Dimens.Md), threshold = threshold.takeIf { it.charging })
            }

            Spacer(Modifier.height(Dimens.Md))

            // Map and quick mowing.
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.Surface,
                border = BorderStroke(1.dp, colors.Hairline)) {
                Column(Modifier.padding(Dimens.Lg), verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
                    Row(Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = tr("Open the map", "Ouvrir la carte"), onClick = onOpenMap),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                        Surface(Modifier.size(76.dp), shape = MaterialTheme.shapes.medium, color = colors.MapBackground) {
                            MapThumbnail(map, Modifier.fillMaxSize().padding(8.dp), empty = colors.MapBackground)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(tr("MY MAP", "MA CARTE"), style = EyebrowStyle, color = colors.TextTertiary)
                            Text(if (map.complete) Format.plural(zones.size, "zone", "zones") else tr("No map", "Aucune carte"),
                                style = MaterialTheme.typography.titleLarge)
                            Text(when {
                                working && state.robotRoute.count > 0 -> tr("${state.robotRoute.index * 100 / state.robotRoute.count} % mowed · tap to open", "${state.robotRoute.index * 100 / state.robotRoute.count} % tondu · touchez pour ouvrir")
                                map.complete -> tr("${Format.num(area, 0)} m² · tap to open", "${Format.num(area, 0)} m² · touchez pour ouvrir")
                                else -> tr("Read the map from the robot or the account", "Lisez la carte du robot ou du compte")
                            },
                                style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.TextTertiary)
                    }
                    if (working && state.robotRoute.count > 0)
                        LubaProgress(state.robotRoute.index.toFloat() / state.robotRoute.count, Modifier.fillMaxWidth().clip(CircleShape))
                    // Quick mowing: zones are chosen directly on the map.
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryPillButton(if (working) tr("Follow mowing", "Suivre la tonte") else tr("Start mowing", "Démarrer une tonte"), onStartMowing,
                            Modifier.weight(1f).heightIn(min = 56.dp), icon = Icons.Rounded.PlayArrow)
                        // Manual control: enabled only with the robot connected (gateway or direct Bluetooth).
                        FilledTonalIconButton(onClick = onOpenManual, enabled = state.ready, modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.SurfaceMuted, contentColor = colors.TextPrimary,
                                disabledContainerColor = colors.SurfaceMuted, disabledContentColor = colors.TextTertiary)) {
                            Icon(Icons.Rounded.Gamepad, contentDescription = tr("Manual control", "Pilotage manuel"), modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            // Robot linked (directly or through the gateway) without a map on this phone: Bluetooth reading from here.
            if (state.ready && !map.complete && !state.mowing.active) {
                TextAction(when {
                    state.mapLoading -> tr("Reading the robot map…", "Lecture de la carte du robot…")
                    state.viaGateway -> tr("Read the robot map via the gateway", "Lire la carte du robot par la passerelle")
                    else -> tr("Read the robot map over Bluetooth", "Lire la carte du robot en Bluetooth")
                }, onFetch, Modifier.fillMaxWidth().padding(top = Dimens.Sm), enabled = fr.luba.poc.map.mapFetchAllowed(state, false))
            }
            if (!cloudState.connected && cloudState.map.shapes.isEmpty() && !map.complete) {
                TextAction(tr("Read the map via the Mammotion account", "Lire la carte via le compte Mammotion"), onAccount, Modifier.fillMaxWidth().padding(top = Dimens.Sm))
            }
            Spacer(Modifier.height(Dimens.Md))
            checklist()
            Spacer(Modifier.height(Dimens.Lg))
        }
      }
    }
}

/** Header: notifications, link status in the centre, device settings. */
@Composable
private fun HomeHeader(state: RobotUi, link: LinkStatus, cloud: Boolean, connected: Boolean, alertsBadge: Boolean,
                       gatewayWarning: Boolean, onAlerts: () -> Unit, onOpenRobot: () -> Unit) {
    val colors = MammotionColors
    Row(Modifier.fillMaxWidth().padding(top = Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(onAlerts) {
            BadgedBox(badge = { if (alertsBadge) Badge(containerColor = colors.Danger) }) {
                Icon(Icons.Rounded.Notifications, contentDescription = "Notifications", modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val (icon, label, tint) = when {
                link.connected -> Triple(if (state.viaGateway) Icons.Rounded.Router else Icons.Rounded.Bluetooth, link.short, colors.Value)
                link.connecting || connected -> Triple(Icons.AutoMirrored.Rounded.BluetoothSearching, tr("Connecting…", "Connexion…"), colors.Warning)
                // Pilot: gateway reached but robot silent, or gateway unreachable — never confused.
                link.gatewayReachable != null -> Triple(Icons.Rounded.Router, if (link.gatewayReachable) tr("Gateway OK · robot unreachable", "Passerelle OK · robot injoignable") else link.short,
                    if (link.gatewayReachable) colors.Value else colors.Warning)
                cloud -> Triple(Icons.Rounded.Cloud, tr("Mammotion account", "Compte Mammotion"), colors.Value)
                else -> Triple(Icons.Rounded.CloudOff, tr("Offline", "Hors ligne"), colors.TextTertiary)
            }
            Surface(onClick = onOpenRobot, shape = CircleShape, color = colors.Surface.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, colors.Hairline)) {
                Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BadgedBox(badge = { if (gatewayWarning) Badge(containerColor = colors.Warning) }) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.TextPrimary, maxLines = 1)
                }
            }
        }
        HeaderButton(onOpenRobot) { Icon(Icons.Rounded.Settings, contentDescription = tr("Device settings", "Réglages de l'appareil"), modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun HeaderButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = MammotionColors
    Surface(onClick = onClick, shape = CircleShape, color = colors.Surface.copy(alpha = 0.7f), contentColor = colors.TextPrimary,
        border = BorderStroke(1.dp, colors.Hairline), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Robot photo on a radial halo, with an elliptical shadow on the ground. The robot slides and fades in
 * on opening (disabled if system animations are).
 */
@Composable
private fun RobotHero(model: RobotModel, onOpenMap: () -> Unit, charging: Boolean = false) {
    val colors = MammotionColors
    val reveal = remember { Animatable(0f) }
    // Energy rings on the ground, around the robot, while it is charging.
    val rings = if (charging) rememberInfiniteTransition(label = "rings").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase") else null
    val ringTint = colors.Green
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.55f)
            .drawBehind {
                // Halo behind the robot.
                drawCircle(Brush.radialGradient(listOf(colors.HeroGlow, colors.HeroGlow.copy(alpha = 0f)),
                    center = Offset(size.width / 2, size.height * 0.48f), radius = size.width * 0.55f),
                    radius = size.width * 0.55f, center = Offset(size.width / 2, size.height * 0.48f))
                // Drop shadow on the ground, softer at the edges.
                val w = size.width * 0.9f
                val h = size.height * 0.26f
                val c = Offset(size.width / 2, size.height * 0.8f)
                scale(scaleX = 1f, scaleY = h / w, pivot = c) {
                    drawCircle(Brush.radialGradient(0f to colors.HeroShadow, 0.45f to colors.HeroShadow.copy(alpha = colors.HeroShadow.alpha * 0.45f),
                        1f to Color.Transparent, center = c, radius = w / 2), radius = w / 2, center = c)
                    rings?.let { drawChargingRings(c, w / 2, it.value, ringTint) }
                }
            }
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Button, onClickLabel = tr("Open the map", "Ouvrir la carte"), onClick = onOpenMap),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(model.imageRes()),
            contentDescription = tr("${model.label} · open the map", "${model.label} · ouvrir la carte"),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.94f)
                .graphicsLayer { alpha = reveal.value; translationY = (1f - reveal.value) * lift },
        )
    }
}

/** Status badge: coloured dot and label, discreet tinted background. */
@Composable
private fun StatusChip(label: String, tint: Color, pulsing: Boolean = false) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pulsing) {
                val beat by rememberInfiniteTransition(label = "badge").animateFloat(0.35f, 1f,
                    infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "beat")
                Box(Modifier.size(8.dp).graphicsLayer { alpha = beat; scaleX = 0.8f + 0.3f * beat; scaleY = scaleX }.background(tint, CircleShape))
            } else Box(Modifier.size(8.dp).background(tint, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

/** Indicator: overline, large value and unit. */
@Composable
private fun MiniStat(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MammotionColors.TextSecondary, maxLines = 1)
    }
}
