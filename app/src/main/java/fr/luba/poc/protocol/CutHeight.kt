package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.RobotModel

/**
 * Cutting height range of the connected mower. The "H" versions of the LUBA (high cut, sold in North America) raise
 * the blade disc higher than the standard versions: LUBA 2 / LUBA 3 / LUBA mini H 55–100 mm, LUBA 1 H 60–100 mm,
 * against 25–70 mm (LUBA 2, LUBA 3), 30–70 mm (LUBA 1) and 20–65 mm (LUBA mini, mini 2) for the standard versions.
 *
 * The H and standard versions share the Bluetooth name prefix and the account product key: only the internal model
 * code published by the robot (`device_product_type_info.sub_product_type`, queried at connection) tells them apart
 * ([forProductType], table of the Mammotion app taken from PyMammotion `device_capabilities.py`). Without that code,
 * the range of the model family deduced from the name applies, raised to the H range when the robot reports a height
 * above the standard maximum.
 *
 * [ANY] covers every known model: it is the sanity bound of the protocol (commands built, telemetry decoded, saved
 * missions), while the screens and the entry points use the range of the connected robot ([fr.luba.poc.ble.RobotUi.cutHeights]).
 */
data class CutHeightRange(val minMm: Int, val maxMm: Int) {
    init { require(minMm in 1 until maxMm) }

    // Functions rather than properties: the relay snapshot exports every getter of the state (RelaySnapshot).
    fun intRange(): IntRange = minMm..maxMm
    fun floatRange(): ClosedFloatingPointRange<Float> = minMm.toFloat()..maxMm.toFloat()
    /** Steps of a 1 mm slider over the range (1 mm step, as in the Mammotion app). */
    val sliderSteps: Int get() = maxMm - minMm - 1
    val label: String get() = "$minMm–$maxMm mm"
    /** High-cut range of an H version. */
    val high: Boolean get() = maxMm >= 100

    operator fun contains(mm: Int): Boolean = mm in minMm..maxMm
    fun clamp(mm: Int): Int = mm.coerceIn(minMm, maxMm)
    fun clamp(mm: Float): Float = if (mm.isFinite()) mm.coerceIn(minMm.toFloat(), maxMm.toFloat()) else defaultMm.toFloat()

    /** Default height: 60 mm, brought into the range. */
    val defaultMm: Int get() = clamp(60)

    /** Shortcut values: both ends and the tens in between. */
    val presets: List<Int> get() = (listOf(minMm) + (minMm / 10 * 10 + 10 until maxMm step 10) + maxMm).distinct()

    /** Refusal text for a height outside the range, null inside. */
    fun refusal(mm: Int): String? = if (mm in this) null else
        tr("Height outside the $label range", "Hauteur hors plage $label")

    companion object {
        /** LUBA 2 AWD, LUBA 3 AWD, LUBA 4 AWD standard version. */
        val STANDARD = CutHeightRange(25, 70)
        /** H versions of the LUBA 2, LUBA 3 and LUBA mini. */
        val HIGH = CutHeightRange(55, 100)
        /** LUBA AWD (LUBA 1). */
        val LUBA_1 = CutHeightRange(30, 70)
        /** LUBA AWD 5000H (LUBA 1 H). */
        val LUBA_1_HIGH = CutHeightRange(60, 100)
        /** LUBA mini AWD, LUBA mini 2 AWD. */
        val MINI = CutHeightRange(20, 65)
        /** Union of the known ranges: protocol sanity bound, independent of the connected model. */
        val ANY = CutHeightRange(20, 100)

        private val LUBA_4_PRO = CutHeightRange(35, 95)
        private val MINI_3 = CutHeightRange(25, 95)

        /** Internal model codes of the Luba mowers (HM code or numeric identifier) and their range. */
        private val productTypes: Map<String, CutHeightRange> = buildMap {
            fun add(range: CutHeightRange, vararg codes: String) = codes.forEach { put(it, range) }
            fun ids(range: CutHeightRange, vararg ids: Int) = ids.forEach { put(it.toString(), range) }
            add(LUBA_1, "HM010060LBAWD10", "HM030080LBAWD30", "HM050080LBAWD50", "HM030060LBAWD50OMNI")
            ids(LUBA_1, 0, 1, 2, 3)
            add(LUBA_1_HIGH, "HM060100LBAWD50OMNIH")
            ids(LUBA_1_HIGH, 4)
            listOf("10", "30", "50", "100").forEach { add(STANDARD, "HM030070LBVAWD${it}OMNI", "HM025070LB2PAWDL${it}OMNI") }
            listOf("30", "50", "100").forEach { add(STANDARD, "HM030070LB2PAWD${it}OMNI") }
            add(STANDARD, "HM030080LB2PAWD30OMNI")
            ids(STANDARD, 5, 7, 9, 11, 23, 25, 27, 61, 63, 65, 67, 104, 107, 109, 111, 115, 116, 119, 121, 122, 123, 124,
                137, 138, 139, 146, 147, 148)
            listOf("10", "30", "50", "100").forEach { add(HIGH, "HM060100LBVAWD${it}OMNIH", "HM055100LB2PAWDL${it}OMNIH") }
            listOf("30", "50", "100").forEach { add(HIGH, "HM060100LB2PAWD${it}OMNIH") }
            add(HIGH, "HM055100LB2MINIAWD08OMNIH", "HM055100LB2MINIAWD15OMNIH", "HM055100LB2MINIAWD15OMNIHLD")
            ids(HIGH, 6, 8, 10, 12, 24, 26, 28, 34, 35, 62, 64, 66, 68, 108, 110, 112, 114, 128, 133)
            add(MINI, "HM020065LB2MINIAWD08OMNI", "HM020065LB2MINIAWD12OMNILD", "HM020065LB2MINIAWD15OMNI",
                "HM020065LB2MINIAWD15OMNILD", "HM020065LB2MINIAWDL10OMNI", "HM020065LB2MINIAWDLA20OMNI", "HM020065LB2MINIAWDL20OMNI")
            ids(MINI, 31, 32, 33, 56, 70, 71, 72, 101, 105, 106, 125, 126, 127, 129, 130, 131, 132, 149, 156, 158, 159)
            ids(LUBA_4_PRO, 143, 144)
            ids(MINI_3, 150, 151, 152)
        }

        /** Range of the model family deduced from the name (standard version). */
        fun forFamily(model: RobotModel): CutHeightRange = when (model) {
            RobotModel.LUBA_1 -> LUBA_1
            RobotModel.LUBA_MINI, RobotModel.LUBA_MINI_2 -> MINI
            else -> STANDARD
        }

        /** H range of the same family. */
        private fun highOf(family: CutHeightRange): CutHeightRange = if (family == LUBA_1) LUBA_1_HIGH else HIGH

        /**
         * Range given by the internal model code, null if unknown. A Luba HM code not listed but ending with "H"
         * (`…OMNIH`, `…OMNIHLD`) is an H version of the [family].
         */
        fun forProductType(code: String?, family: CutHeightRange = STANDARD): CutHeightRange? {
            val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            productTypes[c]?.let { return it }
            return if (c.startsWith("HM") && c.contains("LB") && c.removeSuffix("LD").endsWith("H")) highOf(family) else null
        }

        /**
         * Range of the robot named [name] (Bluetooth or account name): its internal model code [productType] first,
         * then the family of the name, raised to the H range when the robot reports a height ([reportedMm]) above
         * the standard maximum.
         */
        fun forRobot(name: String?, productType: String? = null, reportedMm: Int? = null): CutHeightRange {
            val family = forFamily(RobotModel.detect(name))
            forProductType(productType, family)?.let { return it }
            return if (reportedMm != null && reportedMm in ANY && reportedMm > family.maxMm) highOf(family) else family
        }
    }
}
