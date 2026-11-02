package fr.luba.poc.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import fr.luba.poc.map.Preview

/**
 * Waits for the route of the current task settings after "Route preview" was tapped; true when it was computed. The
 * route is computed by itself as the settings change, so a preview received earlier may belong to older settings; once
 * "Route preview" is tapped, the route of the current settings is either ready or being computed, so the last preview
 * received when no computation is shown any more is the current one.
 */
internal fun ComposeContentTestRule.awaitCurrentRoute(timeoutMs: Long = 120_000, preview: () -> Preview?): Boolean {
    waitUntil(timeoutMs) { onAllNodesWithText("Calcul du trajet", substring = true).fetchSemanticsNodes().isEmpty() }
    return preview() != null
}
