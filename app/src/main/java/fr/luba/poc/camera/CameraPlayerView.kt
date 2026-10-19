package fr.luba.poc.camera

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Host of the camera player (single [CameraController] WebView) in a screen.
 *
 * Each host creates its **own** container; the shared WebView is never returned by a factory. Crashes of 2026-10-14
 * (10:09:31 and 10:30:54, "The specified child already has a parent"): when the activity was recreated, the map (or
 * manual control) view and the [CameraHost] fallback thumbnail were composed in the same insertion; the second factory
 * took the WebView from the first one, which added it back on attach while it already had a parent. Here, the last
 * host that claims it shows it, without ever throwing.
 */
@Composable
fun CameraPlayerView(modifier: Modifier) = AndroidView(
    factory = { context -> FrameLayout(context).also { CameraPlayerHost.claim(it) } },
    modifier = modifier,
    update = { CameraPlayerHost.claim(it) },
    onRelease = { it.removeAllViews() },
)

internal object CameraPlayerHost {
    /** Puts the player in [container], removing it first from its previous container. */
    fun claim(container: FrameLayout) {
        val player = CameraController.webView(container.context)
        if (player.parent === container) return
        (player.parent as? ViewGroup)?.removeView(player)
        container.addView(player, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
