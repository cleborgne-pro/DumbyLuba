package fr.luba.poc.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fr.luba.poc.R

/**
 * Mammotion robot mower model, deduced from the Bluetooth name ("Luba-VSxxxx"…) or from the account product key.
 *
 * Prefixes and keys taken from the Mammotion app table (via PyMammotion, `DeviceType`):
 * Luba-VS / Luba-VP → LUBA 2 AWD (VP = X series), Luba-VA → LUBA 3 AWD (HM442), Luba-MN / Luba-LD → LUBA mini AWD
 * (HM430/431), Luba-LA / Luba-MD / Luba-MB and their SE (LS, MS) → LUBA mini 2 AWD (HM432 to 434).
 * Newer Luba models with no established trade name (HM, ME, TR, SP) stay generic; any other "Luba…" name is a
 * LUBA 1, as in the original app.
 */
enum class RobotModel(val label: String, private val photoName: String? = null) {
    LUBA_1("LUBA AWD", "robot_photo_luba1"),
    LUBA_2("LUBA 2 AWD", "robot_photo_luba2"),
    LUBA_3("LUBA 3 AWD", "robot_photo_luba3"),
    LUBA_MINI("LUBA mini AWD", "robot_photo_luba_mini"),
    LUBA_MINI_2("LUBA mini 2 AWD", "robot_photo_luba_mini2"),
    /** Identified Luba with no trade name or dedicated photo (recent model). */
    LUBA_OTHER("LUBA"),
    /** No known robot: generic illustration. */
    UNKNOWN("LUBA");

    /** Model with an established trade name: its label is shown under the robot name. */
    val named: Boolean get() = photoName != null

    /**
     * Product photo of the model if provided, otherwise the project illustration. Mammotion photos
     * (`robot_photo_*.webp`, Mammotion property, see THIRD_PARTY.md) are looked up by name, so that removing them on
     * request falls back to the illustration instead of breaking the build.
     */
    @SuppressLint("DiscouragedApi")
    @DrawableRes
    fun image(context: Context): Int = photoName
        ?.let { context.resources.getIdentifier(it, "drawable", context.packageName) }
        ?.takeIf { it != 0 } ?: R.drawable.robot_illustration

    companion object {
        private val prefixes = listOf(
            "Luba-VS" to LUBA_2, "Luba-VP" to LUBA_2,
            "Luba-VA" to LUBA_3,
            "Luba-MN" to LUBA_MINI, "Luba-LD" to LUBA_MINI,
            "Luba-LA" to LUBA_MINI_2, "Luba-MD" to LUBA_MINI_2, "Luba-MB" to LUBA_MINI_2,
            "Luba-LS" to LUBA_MINI_2, "Luba-MS" to LUBA_MINI_2,
            "Luba-HM" to LUBA_OTHER, "Luba-MH" to LUBA_OTHER, "Luba-ME" to LUBA_OTHER,
            "Luba-TR" to LUBA_OTHER, "Luba-SP" to LUBA_OTHER,
        )

        private val productKeys: Map<String, RobotModel> = buildMap {
            listOf("a1UBFdq6nNz", "a1x0zHD3Xop", "a1pvCnb3PPu", "a1kweSOPylG", "a1JFpmAV5Ur", "a1BmXWlsdbA", "a1jOhAYOIG8",
                "a1K4Ki2L5rK", "a1ae1QnXZGf", "a1nf9kRBWoH", "a1ZU6bdGjaM", "a1FbaU4Bqk5", "b9tzVWaMxDZ").forEach { put(it, LUBA_1) }
            listOf("a1iMygIwxFC", "a1LLmy1zc0j", "6kWUnPW8UrW", "a1mb8v6tnAa", "a1pHsTqyoPR", "ATyVu9QkAdX").forEach { put(it, LUBA_2) }
            listOf("a1Ce85210Be", "a1BBOJnnjb9", "uY54W5rM8YH").forEach { put(it, LUBA_3) }
            listOf("a1L5ZfJIxGl", "a1dCWYFLROK", "ftDnXns2cdT", "a1jDMfG2Fgj", "a1vtZq9LUFS", "FpstPz2SpwH").forEach { put(it, LUBA_MINI) }
            listOf("CDYuKXTYrSP", "a1YbcqQYFv2", "a1T6VTFTc0C", "a14iRDqMepW", "a1pb9toor70", "3drMFnqGVNe",
                "k8H4M5KZ6Fn", "f4KFveHngnm").forEach { put(it, LUBA_MINI_2) }
            listOf("HK8snDC8Kxh", "tBnCA8u2Aps", "jvEDnj42DRK", "z8Pu2TEeNZU", "SNvVHSxfU73").forEach { put(it, LUBA_OTHER) }
        }

        /** Model from the robot name (Bluetooth or account) and, if known, the Mammotion account product key. */
        fun detect(name: String?, productKey: String? = null): RobotModel {
            val n = name?.trim().orEmpty()
            prefixes.firstOrNull { n.startsWith(it.first, ignoreCase = true) }?.let { return it.second }
            productKey?.trim()?.let { productKeys[it] }?.let { return it }
            return if (n.startsWith("Luba", ignoreCase = true)) LUBA_1 else UNKNOWN
        }
    }
}

/** Model image (photo or illustration) for the current composition. */
@Composable
@DrawableRes
fun RobotModel.imageRes(): Int {
    val context = LocalContext.current
    return remember(this, context) { image(context) }
}
