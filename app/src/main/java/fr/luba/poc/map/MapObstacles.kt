package fr.luba.poc.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.ui.theme.LubaColors

/**
 * Local point obstacles on the maps: solid object circled in white, safety margin as a light fill, then the LUBA
 * turning envelope dashed (perimeter to keep out of the robot's reach). [unit] = 1 dp on screen in the current layer:
 * strokes and dashes stay thin when zooming.
 */
fun DrawScope.drawLocalObstacles(
    obstacles: List<LocalObstacle>,
    screen: (Point) -> Offset,
    scale: Double,
    colors: LubaColors,
    robot: RobotEnvelope = RobotEnvelope.LUBA2,
    unit: Float = 1f,
) {
    obstacles.forEach { obstacle ->
        val center = screen(Point(obstacle.x, obstacle.y))
        if (obstacle.incidents.isNotEmpty()) drawCircle(colors.ObstacleLocalStroke.copy(alpha = (0.08f + obstacle.incidents.size * 0.04f).coerceAtMost(0.35f)),
            ((obstacle.exclusionRadiusM + robot.turnClearanceM + 0.3) * scale).toFloat(), center)
        val physical = (obstacle.radiusM * scale).toFloat().coerceAtLeast(4f * unit)
        val excluded = (obstacle.exclusionRadiusM * scale).toFloat().coerceAtLeast(physical)
        val keepOut = ((obstacle.exclusionRadiusM + robot.turnClearanceM) * scale).toFloat().coerceAtLeast(excluded)
        drawCircle(colors.ObstacleLocalStroke.copy(alpha = 0.10f), keepOut, center)
        drawCircle(colors.ObstacleKeepOut, keepOut, center,
            style = Stroke(1.5f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit))))
        drawCircle(colors.ObstacleLocalStroke.copy(alpha = 0.18f), excluded, center)
        drawCircle(colors.ObstacleLocalStroke, excluded, center, style = Stroke(1.25f * unit))
        drawCircle(colors.ObstacleLocalStroke, physical, center)
        drawCircle(Color.White, physical, center, style = Stroke(1.5f * unit))
    }
}

/** Extent taken by the obstacles (center ± reserve radius), to frame the map. */
fun List<LocalObstacle>.extentPoints(robot: RobotEnvelope = RobotEnvelope.LUBA2): List<Point> = flatMap { obstacle ->
    val radius = obstacle.exclusionRadiusM + robot.turnClearanceM
    listOf(Point(obstacle.x - radius, obstacle.y - radius), Point(obstacle.x + radius, obstacle.y + radius))
}
