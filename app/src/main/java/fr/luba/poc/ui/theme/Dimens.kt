package fr.luba.poc.ui.theme

import androidx.compose.ui.unit.dp

/** App spacing grid: 4 / 8 / 12 / 16 dp. */
object Dimens {
    val Xs = 4.dp   // fine adjustments (between two lines of text)
    val Sm = 8.dp   // between a title and its content
    val Md = 12.dp  // between items of the same group
    val Lg = 16.dp  // between two sections, screen margins

    /** Full-width action buttons; the emergency stop is slightly taller. */
    val Button = 52.dp
    val EmergencyButton = 56.dp
}
