package fr.luba.poc.auth

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.ModelSupport

/**
 * Devices of the Mammotion account kept as robots ([ModelSupport.isMower]: mower name prefix or mower product key) and
 * anonymous trace of the others (RTK stations, pool cleaners, unknown models), DumbyLuba#3: a LUBA AWD 5000 whose account
 * name carried no model prefix was dropped without a trace ("0 LUBA in the account").
 */
object CloudRobotFilter {
    /** Longest model part before the first `-` or `_` (`Spino`, `Kumar`), as in [fr.luba.poc.diagnostics.LogSanitizer]. */
    const val MODEL_PART = 5
    /** Characters kept after the separator (`VS` of `Luba-VS…`). */
    const val AFTER_SEPARATOR = 2
    /** Characters kept from a name without a model part (`Gar` of `Garage door 1`). */
    const val NO_SEPARATOR = 3

    /**
     * Device left out, as written to the diagnostics: product key (shared by every robot of a model, not an identifier
     * of the robot) and [namePrefix]; neither the full name, nor the serial number, nor the iotId.
     */
    data class Skipped(val productKey: String, val namePrefix: String, val nameLength: Int) {
        val fields: Map<String, Any?> get() = mapOf("productKey" to productKey, "namePrefix" to namePrefix, "nameLength" to nameLength)
    }

    data class Result(val robots: List<CloudRobot>, val skipped: List<Skipped>, val total: Int) {
        /** Account status: robots found, devices none of which is recognized, or empty account. */
        val status: String get() = when {
            robots.isNotEmpty() -> tr("${robots.size} LUBA in the account", "${robots.size} LUBA dans le compte")
            total == 0 -> tr("No device in the account", "Aucun appareil dans le compte")
            else -> tr("$total device${if (total > 1) "s" else ""} in the account, none recognized as a Mammotion mower: export the diagnostics (Service → Logs → Export all) and report the model",
                "$total appareil${if (total > 1) "s" else ""} dans le compte, aucun reconnu comme tondeuse Mammotion : exportez les diagnostics (Service → Journaux → Tout exporter) et signalez le modèle")
        }
    }

    fun split(devices: List<CloudRobot>): Result {
        val (robots, others) = devices.partition { ModelSupport.isMower(it.name, it.productKey) }
        return Result(robots, others.map { Skipped(it.productKey.trim(), namePrefix(it.name), it.name.trim().length) }, devices.size)
    }

    /**
     * Start of a device name safe for a public export, never more than half of the name: the model part up to the first
     * `-` or `_` included plus at most [AFTER_SEPARATOR] characters (`Luba-VS` of `Luba-VSXXXX`, as kept by
     * [fr.luba.poc.diagnostics.LogSanitizer]); without such a separator within the first [MODEL_PART] + 1 characters,
     * at most [NO_SEPARATOR] characters (`MA5` of a serial number, `Gar` of a free name).
     */
    fun namePrefix(name: String): String = name.trim().let {
        val separator = it.indexOfFirst { c -> c == '-' || c == '_' }
        val kept = if (separator in 0..MODEL_PART) separator + 1 + AFTER_SEPARATOR else NO_SEPARATOR
        it.take(minOf(kept, it.length / 2))
    }
}
