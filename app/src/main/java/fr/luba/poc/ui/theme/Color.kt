package fr.luba.poc.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Semantic palette of the app, in two instances (light / dark): screens read the colours through
 * [MammotionColors] and so follow the current theme automatically. Inspired by the Mammotion (LUBA)
 * app: signature green, light backgrounds, white cards, status accents (orange/red) reserved for
 * alerts.
 */
@Immutable
data class LubaColors(
    // Brand green: primary actions, selections, battery gauges.
    val Green: Color,
    val GreenDark: Color,   // pressed / gradients
    val GreenDeep: Color,   // strong text, headers, "dark" buttons
    val GreenSoft: Color,   // background of green chips and tiles

    // Neutrals: backgrounds, cards, dividers, texts.
    val Background: Color,
    val Surface: Color,
    val SurfaceMuted: Color,
    val Outline: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,

    // States: information, warning, danger (STOP / blades).
    val Info: Color,
    val InfoSoft: Color,
    val Warning: Color,
    val WarningSoft: Color,
    val Danger: Color,
    val DangerSoft: Color,
    val DangerDeep: Color,

    // Map: background, outlines (mowed zones, no-go, obstacles), cutting stripes and robot marker.
    val MapBackground: Color,
    val ZoneFill: Color,
    val ZoneFillSelected: Color,
    val ZoneStroke: Color,
    val NoGoFill: Color,
    val NoGoStroke: Color,
    val ObstacleFill: Color,
    val ObstacleStroke: Color,
    // Local point obstacles: physical circle, safety margin and turning envelope.
    val ObstacleLocalFill: Color,
    val ObstacleLocalStroke: Color,
    val ObstacleKeepOut: Color,
    val BandPass: Color,    // cutting grid: semi-transparent stripe
    val BandBorder: Color,  // outline and no-go loop, same width, lighter: margin visible along the boundaries
    val LinePass: Color,    // theoretical centre path: 1 px opaque
    val LineBorder: Color,  // theoretical border path
    val BaseSafety: Color,  // base safety zone (no route outside the docking corridor)
    val RobotHalo: Color,
    val RobotMarker: Color,


    // Floating surfaces above the map (overlays, legend, progress).
    val Overlay: Color,

    // Mammotion v1.x codes: "ink" pill primary button, setting values in blue,
    // bluish home background and coloured icons of the map action bar.
    val Ink: Color,
    val OnInk: Color,
    val Value: Color,
    val HomeTop: Color,
    val HomeBottom: Color,
    // "Showcase" home: halo behind the robot photo and drop shadow on the ground.
    val HeroGlow: Color,
    val HeroShadow: Color,
    // Thin card hairline (subtler than [Outline]).
    val Hairline: Color,
    val ActionMow: Color,
    val ActionManual: Color,
    val ActionTasks: Color,
    val ActionMap: Color,
    val Mowed: Color,
    // Moves with the blades stopped (returns, links, access, move to a point): blue dashes.
    val Transit: Color,
    // Lines of the metric grid of the map background.
    val MapGrid: Color,
)

/** Light palette. Map taken from the current Mammotion app (screenshot of 2026-10-17): light grey background, pale
 * green zones with a green outline, solid ochre no-go zones, white routes and border loops, grey base zone. The rest
 * follows the Mammotion v1.x app (LUBA 2 AWD V6.0 manual): black pill, values in blue. */
val LightLubaColors = LubaColors(
    Green = Color(0xFF0FBB5E),
    GreenDark = Color(0xFF0B9A4C),
    GreenDeep = Color(0xFF0A3D28),
    GreenSoft = Color(0xFFE3F7EC),
    Background = Color(0xFFF4F5F7),
    Surface = Color(0xFFFFFFFF),
    SurfaceMuted = Color(0xFFF0F2F5),
    Outline = Color(0xFFE3E6EA),
    TextPrimary = Color(0xFF15171A),
    TextSecondary = Color(0xFF646A73),
    TextTertiary = Color(0xFF9AA0A8),
    Info = Color(0xFF3A6CC9),
    InfoSoft = Color(0xFFEAF1FC),
    Warning = Color(0xFFF59E0B),
    WarningSoft = Color(0xFFFFF6E0),
    Danger = Color(0xFFE5484D),
    DangerSoft = Color(0xFFFDECEC),
    DangerDeep = Color(0xFFC1272D),
    MapBackground = Color(0xFFEEF0F1),
    ZoneFill = Color(0xFFD6ECD7),
    ZoneFillSelected = Color(0xFFC2E5C5),
    ZoneStroke = Color(0xFF5CB862),
    NoGoFill = Color(0xFFD8B66A),
    NoGoStroke = Color(0xFFC09A48),
    ObstacleFill = Color(0xFF9AA9AD),
    ObstacleStroke = Color(0xFF9AA9AD),
    ObstacleLocalFill = Color(0x55B45309),
    ObstacleLocalStroke = Color(0xFFB45309),
    ObstacleKeepOut = Color(0x99B45309),
    BandPass = Color(0x40FFFFFF),
    BandBorder = Color(0x80FFFFFF),
    LinePass = Color(0xFFFFFFFF),
    LineBorder = Color(0xFFFFFFFF),
    BaseSafety = Color(0x45848C94),
    RobotHalo = Color(0xFFFFFFFF),
    RobotMarker = Color(0xFF15171A),
    Overlay = Color(0xF7FFFFFF),
    Ink = Color(0xFF15171A),
    OnInk = Color(0xFFFFFFFF),
    Value = Color(0xFF3A6CC9),
    HomeTop = Color(0xFFE4E9ED),
    HomeBottom = Color(0xFFF4F5F7),
    HeroGlow = Color(0xFFFFFFFF),
    HeroShadow = Color(0x40000000),
    Hairline = Color(0xFFE9EBEE),
    ActionMow = Color(0xFF0FBB5E),
    ActionManual = Color(0xFF3A6CC9),
    ActionTasks = Color(0xFFF59E0B),
    ActionMap = Color(0xFF7A5AF8),
    Mowed = Color(0xFF0A8F5B),
    Transit = Color(0xFF2F6FE0),
    MapGrid = Color(0xFF8E99A4),
)

/**
 * Dark palette: same semantics, deep green-tinted backgrounds, light texts. Brand colours stay
 * readable on a dark background; the "Deep/Dark" variants get lighter to keep the contrast
 * (links, icons on soft green backgrounds).
 */
val DarkLubaColors = LubaColors(
    Green = Color(0xFF22C76B),
    GreenDark = Color(0xFF45CE92),
    GreenDeep = Color(0xFF7FE3AC),
    GreenSoft = Color(0xFF15301F),
    Background = Color(0xFF0D0F11),
    Surface = Color(0xFF171A1D),
    SurfaceMuted = Color(0xFF202428),
    Outline = Color(0xFF2B3035),
    TextPrimary = Color(0xFFF2F4F6),
    TextSecondary = Color(0xFFA3A9B2),
    TextTertiary = Color(0xFF727882),
    Info = Color(0xFF7FA6EE),
    InfoSoft = Color(0xFF18243A),
    Warning = Color(0xFFF5A524),
    WarningSoft = Color(0xFF33260E),
    Danger = Color(0xFFFF6369),
    DangerSoft = Color(0xFF3B1517),
    DangerDeep = Color(0xFFFF9EA1),
    MapBackground = Color(0xFF1A1E22),
    ZoneFill = Color(0xFF22402F),
    ZoneFillSelected = Color(0xFF2B5A40),
    ZoneStroke = Color(0xFF55B062),
    NoGoFill = Color(0xFFA88648),
    NoGoStroke = Color(0xFF866A36),
    ObstacleFill = Color(0xFF3B4147),
    ObstacleStroke = Color(0xFF59616A),
    ObstacleLocalFill = Color(0x55F59E0B),
    ObstacleLocalStroke = Color(0xFFF59E0B),
    ObstacleKeepOut = Color(0x99F59E0B),
    BandPass = Color(0x33FFFFFF),
    BandBorder = Color(0x5CFFFFFF),
    LinePass = Color(0xFFFFFFFF),
    LineBorder = Color(0xFFFFFFFF),
    BaseSafety = Color(0x40B4BCC4),
    RobotHalo = Color(0xFFFFFFFF),
    RobotMarker = Color(0xFF0B0C0E),
    Overlay = Color(0xF2171A1D),
    Ink = Color(0xFFF1F3F6),
    OnInk = Color(0xFF16181B),
    Value = Color(0xFF7FA6EE),
    HomeTop = Color(0xFF1C2227),
    HomeBottom = Color(0xFF0D0F11),
    HeroGlow = Color(0xFF39424A),
    HeroShadow = Color(0xB3000000),
    Hairline = Color(0xFF23272B),
    ActionMow = Color(0xFF22C76B),
    ActionManual = Color(0xFF7FA6EE),
    ActionTasks = Color(0xFFF5A524),
    ActionMap = Color(0xFF9B8AFB),
    Mowed = Color(0xFF34D399),
    Transit = Color(0xFF8DB4FF),
    MapGrid = Color(0xFF6B7682),
)

/**
 * Mammotion drawings (SVG patterns) on the map: purple, apart from the zone green, the no-go ochre, the danger red and
 * the No-Stop blue; lighter on the dark palette. Kept out of the [LubaColors] constructor: the static initializer of
 * the two palettes is at the Dalvik register limit (`tools/check-dex-registers.py`: 218 registers, 222 with one more colour, threshold 224).
 */
val LubaColors.Drawing: Color get() = if (Background.luminance() < 0.5f) Color(0xFFCE93D8) else Color(0xFF9C27B0)

val LocalLubaColors = staticCompositionLocalOf { LightLubaColors }

/** Current palette, readable from any composable context (follows the light or dark theme). */
val MammotionColors: LubaColors
    @Composable get() = LocalLubaColors.current
