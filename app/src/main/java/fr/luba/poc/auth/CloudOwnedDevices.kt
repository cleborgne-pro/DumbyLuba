package fr.luba.poc.auth

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.ModelSupport
import org.json.JSONObject

/**
 * Devices owned by the Mammotion account (`/device-server/v1/device/list`, [MammotionCloud.ownedDevices]), read when the
 * device page (`/v1/user/device/page`) is empty: that page does not list the robots still bound to the older Aliyun
 * cloud (PyMammotion, `test_a_reload_leaves_aliyun_devices_to_the_aliyun_listing`), the likely case of the LUBA AWD 5000
 * of DumbyLuba#3, whose account showed "No device in the account". Diagnostics only: nothing here is sent to a robot.
 */
object CloudOwnedDevices {
    /**
     * One owned device (PyMammotion `DeviceInfo`: `iotId`, `deviceName`, `deviceType`, `series`, `productSeries`,
     * `generation`). `productKey` is not part of that model; it is read when the reply carries it.
     */
    data class Owned(val name: String, val iotId: String, val productKey: String, val deviceType: String,
                     val series: String, val productSeries: String, val generation: Int?)

    /** Name prefixes and model words of devices that are not robot mowers (RTK stations, pool cleaners). */
    private val NON_MOWER_PREFIXES = listOf("RTK", "RBS", "Spino")
    private val NON_MOWER_WORDS = listOf("rtk", "spino", "pool")

    /** Devices of a `device/list` reply: `data` as a list (PyMammotion) or as a page with `records`; unknown shapes give none. */
    fun parse(reply: JSONObject): List<Owned> {
        val list = reply.optJSONArray("data") ?: reply.optJSONObject("data")?.optJSONArray("records") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { d ->
            Owned(d.optString("deviceName").trim(), d.optString("iotId").trim(), d.optString("productKey").trim(),
                d.optString("deviceType").trim(), d.optString("series").trim(), d.optString("productSeries").trim(),
                if (d.has("generation") && !d.isNull("generation")) d.optInt("generation") else null)
        }
    }

    /** Robot mower or possibly one: recognized as a mower, or not recognized as an RTK station or a pool cleaner. */
    fun robot(device: Owned): Boolean {
        if (ModelSupport.isMower(device.name, device.productKey)) return true
        if (NON_MOWER_PREFIXES.any { device.name.startsWith(it, ignoreCase = true) }) return false
        val model = listOf(device.deviceType, device.series, device.productSeries).joinToString(" ").lowercase()
        return NON_MOWER_WORDS.none { it in model }
    }

    /** Owned robots that the device page did not list (matched by iotId, otherwise by name). */
    fun missingRobots(owned: List<Owned>, page: List<CloudRobot>): List<Owned> {
        val ids = page.map { it.id.trim() }.filter { it.isNotEmpty() }.toSet()
        val names = page.map { it.name.trim() }.filter { it.isNotEmpty() }.toSet()
        return owned.filter { robot(it) && it.iotId !in ids && (it.name.isEmpty() || it.name !in names) }
    }

    /**
     * Fields of the log: device count, product key prefixes (2 characters, `a1` for the Aliyun keys), model words
     * (`deviceType`, `series`, `productSeries`, `generation`) and short name prefixes ([CloudRobotFilter.namePrefix]);
     * never a full name, a serial number or an iotId.
     */
    fun fields(owned: List<Owned>, missing: List<Owned>): Map<String, Any?> = mapOf(
        "devices" to owned.size,
        "missingFromPage" to missing.size,
        "withProductKey" to owned.count { it.productKey.isNotEmpty() },
        "productKeyPrefixes" to owned.mapNotNull { it.productKey.take(2).takeIf(String::isNotEmpty) }.distinct().sorted(),
        "deviceTypes" to owned.map { it.deviceType }.filter(String::isNotEmpty).distinct().sorted(),
        "series" to owned.map { it.series }.filter(String::isNotEmpty).distinct().sorted(),
        "productSeries" to owned.map { it.productSeries }.filter(String::isNotEmpty).distinct().sorted(),
        "generations" to owned.mapNotNull { it.generation }.distinct().sorted(),
        "namePrefixes" to owned.map { CloudRobotFilter.namePrefix(it.name) }.filter(String::isNotEmpty).distinct().sorted(),
    )

    /**
     * Account status once the owned devices are known: robots missing from the device page are on the older Aliyun cloud,
     * which the app cannot reach; otherwise [found]'s status ("No device in the account"…). [missing]: [missingRobots].
     */
    fun status(found: CloudRobotFilter.Result, missing: List<Owned>): String = when {
        found.robots.isNotEmpty() || missing.isEmpty() -> found.status
        missing.size == 1 -> tr("Your LUBA is on Mammotion's older cloud (Aliyun), which this app does not support yet; Bluetooth control still works.",
            "Votre LUBA est sur l'ancien cloud Mammotion (Aliyun), que l'application ne prend pas encore en charge ; le contrôle Bluetooth fonctionne toujours.")
        else -> tr("${missing.size} robots of your account are on Mammotion's older cloud (Aliyun), which this app does not support yet; Bluetooth control still works.",
            "${missing.size} robots de votre compte sont sur l'ancien cloud Mammotion (Aliyun), que l'application ne prend pas encore en charge ; le contrôle Bluetooth fonctionne toujours.")
    }
}
