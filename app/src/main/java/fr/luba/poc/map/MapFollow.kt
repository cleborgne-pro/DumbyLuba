package fr.luba.poc.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/**
 * "Follow the robot" map mode: the view stays centered on the robot (position glided between two readings), with the
 * zoom and rotation chosen by the user. The forced offset is only read by the drawing layers and the gestures:
 * following the robot frame by frame does not recompose the map screen.
 */
@Stable
class RobotFollow(enabled: Boolean = false) {
    var enabled by mutableStateOf(enabled)
        private set
    /** View offset that centers the robot; null: no position, user view. */
    var offset by mutableStateOf<Point?>(null)
        internal set

    /** Displayed view: the user's, recentered on the robot while following. */
    fun view(camera: MapCamera): MapCamera = offset?.takeIf { enabled }?.let { camera.copy(offset = it) } ?: camera
    fun start() { enabled = true }
    fun stop() { enabled = false; offset = null }

    companion object {
        val saver: Saver<RobotFollow, Boolean> = Saver(save = { it.enabled }, restore = { RobotFollow(it) })
    }
}

@Composable
fun rememberRobotFollow(): RobotFollow = rememberSaveable(saver = RobotFollow.saver) { RobotFollow() }

/**
 * Follow driver: recomputes the offset on each frame of the robot glide, at the current zoom and rotation.
 * Target: center of the visible part of the map, between the header ([top]) and the bottom panels ([bottom]).
 */
@Composable
fun RobotFollowDriver(follow: RobotFollow, camera: MapCamera, robot: DisplayPose?, size: IntSize, top: Float, bottom: Float,
                      projection: (Size) -> MapProjection) {
    if (!follow.enabled) return
    val pose = rememberSmoothedPose(robot)
    SideEffect {
        if (pose == null || size.width <= 0 || size.height <= 0) { follow.offset = null; return@SideEffect }
        val w = size.width.toDouble(); val h = size.height.toDouble()
        val at = projection(Size(size.width.toFloat(), size.height.toFloat())).screen(pose.point)
        val visibleTop = top.toDouble().coerceIn(0.0, h); val visibleBottom = (h - bottom).coerceIn(visibleTop, h)
        follow.offset = camera.following(Point(at.x.toDouble(), at.y.toDouble()), Point(w / 2, h / 2),
            Point(w / 2, (visibleTop + visibleBottom) / 2)).offset
    }
}
