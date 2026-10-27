package fr.luba.poc.auth

import fr.luba.poc.i18n.I18n
import fr.luba.poc.protocol.ModelSupport
import fr.luba.poc.ui.RobotModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance of DumbyLuba#3 on the account device filter: every LUBA 1 product key listed whatever the name, anonymous
 * trace of the devices left out (short and empty names included), account status, read-only for a robot recognized by
 * its product key only, and no RTK station or pool cleaner key accepted.
 */
class CloudRobotFilterAcceptanceTest {
    /** The 13 LUBA 1 product keys of the Mammotion table (PyMammotion `LubaProductKey`). */
    private val luba1Keys = listOf("a1UBFdq6nNz", "a1x0zHD3Xop", "a1pvCnb3PPu", "a1kweSOPylG", "a1JFpmAV5Ur", "a1BmXWlsdbA",
        "a1jOhAYOIG8", "a1K4Ki2L5rK", "a1ae1QnXZGf", "a1nf9kRBWoH", "a1ZU6bdGjaM", "a1FbaU4Bqk5", "b9tzVWaMxDZ")

    /** Account names that carry no mower prefix, the empty name included. */
    private val namesWithoutPrefix = listOf("", "   ", "A", "Garage", "MA5000H23A0012345", "Mower Luba-VS1", "VS-Luba", "Tondeuse")

    /** RTK stations, pool cleaners and charging piles (PyMammotion `utility/device_type.py`). */
    private val nonMowerKeys = listOf("a1qXkZ5P39W", "a1Nc68bGZzX", "mUzerUySdcC", "a1wIIUUdAMX", "sePx2YW6xDT", "a1mGLcddn4u",
        "a1vIxwrmpMn", "HwHcWDDVkT9", "a1NfZqdSREf", "a1ZuQVL7UiN", "6DPytKe4pKz", "rbk9dxFBe6m", "xEBF8JsgVeR", "a15Cq8FbCh1",
        "a1UvLZ3mWcW", "FCtXbVnmd2C", "YBRDhT2YTvY", "GJzsmaVk5za", "fEaKVY28tNz", "zkRuTK9KsXG", "6DbgVh2Qs5m", "a1tyIkI4q0G", "1SCa3mAX6G")

    private fun spaced(key: String) = listOf(key, " $key", "$key ", "  $key  ", "\t$key\n")

    @Test fun everyLuba1KeyIsListedWhateverTheNameAndSpacesAroundTheKey() {
        assertEquals(13, luba1Keys.toSet().size)
        luba1Keys.forEach { key ->
            assertEquals(key, RobotModel.LUBA_1, RobotModel.fromProductKey(key))
            for (name in namesWithoutPrefix) for (variant in spaced(key)) {
                val robot = CloudRobot(name, "iot-$key", variant)
                val found = CloudRobotFilter.split(listOf(robot))
                assertEquals("'$name' / '$variant'", listOf(robot), found.robots)
                assertTrue("'$name' / '$variant'", found.skipped.isEmpty())
                assertEquals("1 LUBA dans le compte", found.status)
            }
        }
    }

    @Test fun luba1KeyIsListedWhateverTheCaseOfAMowerName() {
        // A name with a mower prefix in any case, beside a LUBA 1 key: listed (the key alone would be enough).
        listOf("luba-1234", "LUBA-1234", "LuBa 1", " lUbA ").forEach { name ->
            assertEquals(name, 1, CloudRobotFilter.split(listOf(CloudRobot(name, "iot", "a1FbaU4Bqk5"))).robots.size)
        }
    }

    @Test fun robotRecognizedByItsProductKeyOnlyStaysReadOnly() {
        val mowerKeys = luba1Keys + listOf("a1iMygIwxFC", "a1Ce85210Be", "a1L5ZfJIxGl", "CDYuKXTYrSP", "a1kT0TlYEza", "HK8snDC8Kxh")
        mowerKeys.forEach { key ->
            for (name in namesWithoutPrefix + listOf("Luba VS", "Lub-VS1", "LubaVS1")) {
                if (ModelSupport.isMower(name)) continue // recognized by its name, not by its key alone
                assertTrue("$name / $key", ModelSupport.isMower(name, key))
                assertFalse("$name / $key", ModelSupport.motionValidated(name))
                assertNotNull("$name / $key", ModelSupport.refusal(name))
            }
        }
        // Whatever name passes motionValidated also passes the name filter: the key never widens motion.
        listOf("Luba-VS1", " luba-vp2 ", "LUBA-VSX").forEach {
            assertTrue(it, ModelSupport.motionValidated(it)); assertTrue(it, ModelSupport.isMower(it))
        }
    }

    @Test fun skippedDevicesNeverCarryTheFullNameNorTheIotId() {
        val names = listOf("", " ", "A", "AB", "ABC", " AB ", "XYZ", "Q1", "RTK", "Spino", "RTK-AB12345678", "Garage door 1",
            "AB12345678", "Johnsmithgarden-12345678", "Spino_S1ABCDEFGH")
        val devices = names.mapIndexed { i, name -> CloudRobot(name, "iot-SECRET-$i", "zzUnknown0$i") }
        val found = CloudRobotFilter.split(devices)
        assertTrue(found.robots.isEmpty())
        assertEquals(names.size, found.skipped.size)
        found.skipped.zip(devices).forEach { (skipped, device) ->
            val full = device.name.trim()
            assertEquals(full.length, skipped.nameLength)
            assertTrue("'$full' -> '${skipped.namePrefix}'", skipped.namePrefix.length <= full.length / 2)
            assertTrue(full.startsWith(skipped.namePrefix))
            if (full.isNotEmpty()) assertNotEquals("full name '$full' traced", full, skipped.namePrefix)
            assertEquals(setOf("productKey", "namePrefix", "nameLength"), skipped.fields.keys)
            assertFalse(skipped.fields.values.joinToString("|").contains(device.id))
            assertFalse(skipped.toString().contains(device.id))
            if (full.length >= 2) assertFalse(skipped.fields.values.any { it == full })
            // Model part and 2 characters after the first separator, otherwise 3 characters at most.
            val separator = skipped.namePrefix.indexOfFirst { it == '-' || it == '_' }
            if (separator < 0) assertTrue("'$full' -> '${skipped.namePrefix}'", skipped.namePrefix.length <= 3)
            else assertTrue("'$full' -> '${skipped.namePrefix}'", separator <= 5 && skipped.namePrefix.length - separator - 1 <= 2)
        }
        // Very short and empty names: at most one character traced.
        assertEquals(listOf("" to 0, "" to 0, "" to 1, "A" to 2, "A" to 3, "A" to 2, "X" to 3),
            found.skipped.take(7).map { it.namePrefix to it.nameLength })
        // Longer names: model part and 2 characters, or 3 characters (serial number, free name).
        assertEquals(listOf("RTK-AB", "Gar", "AB1", "Joh", "Spino_S1"), found.skipped.drop(10).map { it.namePrefix })
    }

    @Test fun statusTellsEmptyAccountFromUnrecognizedDevicesAndKeepsTheNominalText() {
        assertEquals("Aucun appareil dans le compte", CloudRobotFilter.split(emptyList()).status)
        val unknown = (1..3).map { CloudRobot("Dev$it", "i$it", "zz$it") }
        assertTrue(CloudRobotFilter.split(unknown).status.startsWith("3 appareils dans le compte, aucun reconnu comme tondeuse Mammotion"))
        val mixed = unknown + CloudRobot("", "i9", "a1FbaU4Bqk5") + CloudRobot("Luba-VS1", "i8", "pk")
        assertEquals("2 LUBA dans le compte", CloudRobotFilter.split(mixed).status)
        I18n.override = "en"
        try {
            assertEquals("2 LUBA in the account", CloudRobotFilter.split(mixed).status)
            assertEquals("No device in the account", CloudRobotFilter.split(emptyList()).status)
            assertTrue(CloudRobotFilter.split(unknown.take(1)).status.startsWith("1 device in the account, none recognized as a Mammotion mower"))
        } finally { I18n.override = null }
    }

    @Test fun noRtkStationNorPoolCleanerKeyIsAccepted() {
        nonMowerKeys.forEach { key ->
            for (variant in spaced(key) + listOf(key.lowercase(), key.uppercase())) {
                assertNull(variant, RobotModel.fromProductKey(variant))
                for (name in listOf("", "Garage", "RTK-AB1234", "Spino-S1-04")) {
                    assertFalse("$name / $variant", ModelSupport.isMower(name, variant))
                }
            }
        }
        val found = CloudRobotFilter.split(nonMowerKeys.mapIndexed { i, key -> CloudRobot("RTK-$i", "iot$i", " $key ") })
        assertTrue(found.robots.isEmpty())
        assertEquals(nonMowerKeys, found.skipped.map { it.productKey })
        assertTrue(found.status.startsWith("${nonMowerKeys.size} appareils dans le compte, aucun reconnu"))
    }
}
