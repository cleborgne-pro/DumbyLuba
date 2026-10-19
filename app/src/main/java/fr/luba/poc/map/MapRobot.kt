package fr.luba.poc.map

import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import fr.luba.poc.obstacles.RobotEnvelope

/**
 * Top view of the LUBA 2 AWD, to scale (690 × 513 mm): four lugged drive wheels, anthracite chassis, white shell with a
 * rounded nose, black front bumper with ultrasonic sensors, central cover with the RTK antenna dome, green status
 * strip, red STOP button and rear handle. Heading 90° draws the nose towards the top of the screen.
 */
fun DrawScope.drawLuba(center: Offset, scale: Double, heading: Double, wheels: WheelTravel = WheelTravel.ZERO) {
    val robot = RobotEnvelope.LUBA2
    val length = (robot.lengthM * scale).toFloat()
    val width = (robot.widthM * scale).toFloat()
    // The nose is drawn upwards; the screen frame inverts the map's y axis.
    rotate((90.0 - heading).toFloat(), center) {
        fun at(x: Float, y: Float) = center + Offset(x * width, y * length)
        fun box(color: Color, x: Float, y: Float, w: Float, h: Float, rounding: Float) =
            drawRoundRect(color, at(x, y), Size(w * width, h * length), CornerRadius(rounding * width))
        fun outline(vararg points: Pair<Float, Float>, curves: Map<Int, Pair<Float, Float>> = emptyMap()) = Path().apply {
            points.forEachIndexed { i, (x, y) ->
                val o = at(x, y)
                when {
                    i == 0 -> moveTo(o.x, o.y)
                    i in curves -> { val (cx, cy) = curves.getValue(i); val c = at(cx, cy); quadraticTo(c.x, c.y, o.x, o.y) }
                    else -> lineTo(o.x, o.y)
                }
            }
            close()
        }
        val stroke = (0.012 * scale).toFloat()
        // Drop shadow: sets the robot apart from light and dark lawns alike.
        drawRoundRect(Color(0x26000000), at(-0.45f, -0.45f) + Offset(0.015f * width, 0.012f * length),
            Size(0.9f * width, 0.92f * length), CornerRadius(0.2f * width))
        // Four drive wheels with chevron lugs (AWD). Lugs scroll with the distance driven by each side: towards the nose
        // when driving forward, in opposite directions on each side during a pivot.
        for (x in listOf(-0.5f, 0.32f)) for (y in listOf(-0.44f, 0.15f)) {
            box(Color(0xFF1A1D1F), x, y, 0.18f, 0.29f, 0.06f)
            val travel = if (x < 0f) wheels.leftM else wheels.rightM
            val shift = -(WheelTravel.phase(travel, WheelOdometer.TREAD_PITCH_M) * TREAD_PITCH).toFloat()
            val top = at(x, y); val bottom = at(x + 0.18f, y + 0.29f)
            clipRect(top.x, top.y, bottom.x, bottom.y) {
                for (k in 0..6) {
                    val ty = y + 0.045f + k * TREAD_PITCH + shift
                    drawLine(Color(0xFF50575A), at(x + 0.025f, ty + 0.018f), at(x + 0.09f, ty), stroke)
                    drawLine(Color(0xFF50575A), at(x + 0.09f, ty), at(x + 0.155f, ty + 0.018f), stroke)
                }
            }
        }
        // Anthracite chassis between the wheels.
        box(Color(0xFF2C3134), -0.34f, -0.47f, 0.68f, 0.94f, 0.16f)
        // White shell: rounded nose, straight sides, wide rear.
        val shell = outline(-0.25f to -0.39f, 0.25f to -0.39f, 0.34f to -0.20f, 0.345f to 0.35f, 0.22f to 0.465f,
            -0.22f to 0.465f, -0.345f to 0.35f, -0.34f to -0.20f,
            curves = mapOf(1 to (0f to -0.47f), 4 to (0.345f to 0.465f), 6 to (-0.345f to 0.465f)))
        drawPath(shell, Color(0xFFF4F5F2))
        drawPath(shell, Color(0xFFB4BBBE), style = Stroke(stroke))
        // Black front bumper and three ultrasonic sensors.
        drawPath(outline(-0.27f to -0.43f, 0.27f to -0.43f, 0.23f to -0.375f, -0.23f to -0.375f,
            curves = mapOf(1 to (0f to -0.53f), 3 to (0f to -0.46f))), Color(0xFF1C2022))
        for (x in listOf(-0.12f, 0f, 0.12f))
            drawCircle(Color(0xFF6A7376), 0.028f * width, at(x, -0.455f + kotlin.math.abs(x) * 0.25f))
        // Central anthracite cover: control panel.
        drawPath(outline(-0.17f to -0.26f, 0.17f to -0.26f, 0.225f to 0.21f, -0.225f to 0.21f,
            curves = mapOf(3 to (0f to 0.28f))), Color(0xFF394045))
        // Built-in RTK antenna dome.
        drawCircle(Color(0xFF22272A), 0.085f * width, at(0f, -0.15f))
        drawCircle(Color(0xFF4F585C), 0.045f * width, at(-0.012f, -0.158f))
        // Green status strip then red STOP button.
        box(Color(0xFF7DD38B), -0.11f, -0.035f, 0.22f, 0.02f, 0.01f)
        box(Color(0xFFE2402F), -0.085f, 0.06f, 0.17f, 0.075f, 0.03f)
        // Rear handle.
        box(Color(0xFF2A2F32), -0.17f, 0.375f, 0.34f, 0.045f, 0.02f)
    }
}

/** Pitch of the drawn lugs, as a fraction of the robot length. */
private const val TREAD_PITCH = 0.05f

/** Cumulative distance driven by the left and right wheels (m), signed: reverse is negative. */
data class WheelTravel(val leftM: Double, val rightM: Double) {
    companion object {
        val ZERO = WheelTravel(0.0, 0.0)
        /** Lug progress within a pitch [pitchM]: 0 ≤ phase < 1. */
        fun phase(travelM: Double, pitchM: Double): Double = ((travelM / pitchM) % 1.0 + 1.0) % 1.0
    }
}

/**
 * Display odometry of the wheels: from the successive drawn poses (glided between two positions), forward motion and
 * rotation converted into the distance driven by each side of a robot with two wheel sets.
 * Large jump (relocalization) or unknown heading: no scrolling.
 *
 * Each side moves at most [maxSideStepM] per frame: in fast playback (simulation × 10 to × 40), the robot drives
 * several lug pitches between two frames and the lugs seemed to turn randomly or backwards (stroboscopic effect), a
 * defect only visible once the map was zoomed in. Bounded below half a pitch, scrolling stays in the direction of
 * motion.
 */
class WheelOdometer(private val trackM: Double = RobotEnvelope.LUBA2.widthM * 0.8,
                    private val maxSideStepM: Double = MAX_SIDE_STEP_M) {
    private var last: DisplayPose? = null
    var travel: WheelTravel = WheelTravel.ZERO
        private set

    fun advance(pose: DisplayPose?): WheelTravel {
        val previous = last
        last = pose
        if (pose == null || previous == null || pose.heading == null || previous.heading == null) return travel
        val dx = pose.point.x - previous.point.x
        val dy = pose.point.y - previous.point.y
        if (kotlin.math.hypot(dx, dy) > MAX_STEP_M) return travel
        val h = Math.toRadians(previous.heading)
        val forward = dx * kotlin.math.cos(h) + dy * kotlin.math.sin(h)
        val turn = Math.toRadians(((pose.heading - previous.heading) % 360 + 540) % 360 - 180)
        fun bounded(step: Double) = step.coerceIn(-maxSideStepM, maxSideStepM)
        travel = WheelTravel(travel.leftM + bounded(forward - turn * trackM / 2), travel.rightM + bounded(forward + turn * trackM / 2))
        return travel
    }

    companion object {
        const val MAX_STEP_M = 1.0
        /** Pitch of the lugs drawn on the LUBA 2 (m). */
        val TREAD_PITCH_M = RobotEnvelope.LUBA2.lengthM * TREAD_PITCH
        /** Maximum scrolling of one side per frame: 40 % of the pitch, below the threshold where the direction becomes ambiguous. */
        val MAX_SIDE_STEP_M = 0.4 * TREAD_PITCH_M
    }
}

/** LUBA display size: to the map scale, but never below [minimumPx] on screen. */
fun lubaDrawScale(mapScale: Double, minimumPx: Float, zoom: Float): Double =
    kotlin.math.max(mapScale, minimumPx / zoom.coerceAtLeast(0.01f) / RobotEnvelope.LUBA2.lengthM)

/** Station and back-off corridor to scale, from the approach point to the docked robot. */
fun DrawScope.drawStation(center: Offset, scale: Double, heading: Double?, radius: Double?, color: Color, approachDistanceM: Double=1.5, circleStrokePx: Float=1.5f) {
    radius?.let {
        drawCircle(Color.White.copy(alpha=0.9f), (it*scale).toFloat(), center,
            style=androidx.compose.ui.graphics.drawscope.Stroke(circleStrokePx))
    }
    rotate((90.0-(heading ?: 90.0)).toFloat(), center) {
        if(heading!=null) {
            val width=(RobotEnvelope.LUBA2.widthM*scale).toFloat()
            drawRect(Color.Black.copy(alpha=0.22f),center+Offset(-width/2f,0f),
                Size(width,(approachDistanceM*scale).toFloat()))
        }
        // Published footprint of the LUBA 2 station: depth 0.49 m, width 0.52 m.
        // The center is the position of the docked robot; the frame sits behind its rear.
        fun at(x: Double, y: Double) = center+Offset((x*scale).toFloat(),(y*scale).toFloat())
        fun plate(points: List<Pair<Double,Double>>, fill: Color) {
            val outline=Path().apply {
                points.forEachIndexed { i,p -> val o=at(p.first,p.second); if(i==0) moveTo(o.x,o.y) else lineTo(o.x,o.y) }
                close()
            }
            drawPath(outline,fill)
        }
        plate(listOf(-0.26 to -0.40,0.26 to -0.40,0.26 to 0.06,0.23 to 0.09,
            0.075 to 0.09,0.055 to 0.035,-0.055 to 0.035,-0.075 to 0.09,-0.23 to 0.09,-0.26 to 0.06),Color(0xFF444A4C))
        for(x in listOf(-0.245,0.085)) {
            drawRoundRect(Color(0xFF292E30),at(x,-0.30),Size((0.16*scale).toFloat(),(0.365*scale).toFloat()),
                CornerRadius((0.014*scale).toFloat()))
            for(i in 0..12) drawLine(Color(0xFF596063),at(x+0.012,-0.275+i*0.023),at(x+0.148,-0.275+i*0.023),
                (0.005*scale).toFloat())
        }
        // Rear support with cover, side guides and green light.
        drawRoundRect(Color(0xFF1C2124),at(-0.19,-0.40),Size((0.38*scale).toFloat(),(0.145*scale).toFloat()),
            CornerRadius((0.025*scale).toFloat()))
        plate(listOf(-0.20 to -0.405,0.20 to -0.405,0.20 to -0.345,0.115 to -0.325,
            -0.115 to -0.325,-0.20 to -0.345),Color(0xFF757D80))
        drawRoundRect(Color(0xFF8ECF87),at(-0.020,-0.383),Size((0.04*scale).toFloat(),(0.015*scale).toFloat()),
            CornerRadius((0.003*scale).toFloat()))
        for(x in listOf(-0.205,0.19)) drawRoundRect(Color(0xFF121719),at(x,-0.31),
            Size((0.015*scale).toFloat(),(0.085*scale).toFloat()),CornerRadius((0.005*scale).toFloat()))
        drawRect(Color(0xFF0F1519),at(-0.065,-0.26),Size((0.13*scale).toFloat(),(0.017*scale).toFloat()))
        for(x in listOf(-0.075,0.06)) drawRect(Color(0xFFE2C376),at(x,-0.245),
            Size((0.015*scale).toFloat(),(0.025*scale).toFloat()))
        // RTK mast mounting hole and plate screws.
        drawCircle(Color(0xFF202729),(0.032*scale).toFloat(),at(-0.227,-0.335))
        drawCircle(Color(0xFF0C1012),(0.019*scale).toFloat(),at(-0.227,-0.335))
        for(x in listOf(-0.225,0.225)) for(y in listOf(-0.20,0.065))
            drawCircle(Color(0xFF12181A),(0.009*scale).toFloat(),at(x,y))

    }
}

/**
 * Base safety zone, like the grey perimeter of the Mammotion app: disc actually excluded from routes (same radius as
 * the planner, [fr.luba.poc.simulation.TrajectoryPlanner.baseSafetyRadius]); the robot only enters and leaves it through
 * the front corridor. Translucent grey fill and white edging. [unit] = 1 dp on screen.
 */
fun DrawScope.drawBaseSafetyZone(center: Offset, radiusPx: Float, unit: Float, fill: Color) {
    if (radiusPx <= 0f) return
    drawCircle(fill, radiusPx, center)
    drawCircle(Color.White.copy(alpha = 0.85f), radiusPx, center, style = Stroke(1.25f * unit))
}

/**
 * Station badge (dark circle and lightning bolt, as in the Mammotion app), at a fixed on-screen size: readable marker
 * when the to-scale station becomes too small. [unit] = 1 dp on screen.
 */
fun DrawScope.drawStationBadge(center: Offset, unit: Float, ink: Color, onInk: Color) {
    val r = 10f * unit
    drawCircle(Color.White, r + 1.5f * unit, center)
    drawCircle(ink, r, center)
    fun at(x: Float, y: Float) = center + Offset(x * r, y * r)
    val bolt = Path().apply {
        moveTo(at(0.18f, -0.62f).x, at(0.18f, -0.62f).y)
        lineTo(at(-0.38f, 0.10f).x, at(-0.38f, 0.10f).y)
        lineTo(at(-0.02f, 0.10f).x, at(-0.02f, 0.10f).y)
        lineTo(at(-0.18f, 0.62f).x, at(-0.18f, 0.62f).y)
        lineTo(at(0.38f, -0.12f).x, at(0.38f, -0.12f).y)
        lineTo(at(0.02f, -0.12f).x, at(0.02f, -0.12f).y)
        close()
    }
    drawPath(bolt, onInk)
}

/** Station drawn to scale too small on screen (width < 20 dp): the badge marks it. */
fun stationNeedsBadge(scale: Double, unit: Float): Boolean = STATION_WIDTH_M * scale < 20f * unit

/** Published width of the LUBA 2 station (see [drawStation]). */
const val STATION_WIDTH_M = 0.52
