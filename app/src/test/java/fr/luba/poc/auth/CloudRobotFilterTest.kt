package fr.luba.poc.auth

import fr.luba.poc.diagnostics.LogSanitizer
import fr.luba.poc.protocol.ModelSupport
import fr.luba.poc.ui.RobotModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account devices kept as robots, by name or product key, and anonymous trace of the others (DumbyLuba#3). */
class CloudRobotFilterTest {
    /** LUBA AWD 5000 product key (PyMammotion `LubaProductKey`, "LubaAWD5000743"). */
    private val luba1Key = "a1FbaU4Bqk5"

    @Test fun luba1ProductKeyKeepsARobotWhateverItsName() {
        val robot = CloudRobot("MA5000H23A0012345", "iot-secret-1", luba1Key)
        assertTrue(ModelSupport.isMower(robot.name, robot.productKey))
        assertFalse("name alone is not enough", ModelSupport.isMower(robot.name))
        val found = CloudRobotFilter.split(listOf(robot))
        assertEquals(listOf(robot), found.robots)
        assertTrue(found.skipped.isEmpty())
        assertEquals("1 LUBA dans le compte", found.status)
        assertEquals(RobotModel.LUBA_1, RobotModel.detect(robot.name, robot.productKey))
        // Every LUBA 1 key of the table, with and without spaces around.
        listOf("a1UBFdq6nNz", " b9tzVWaMxDZ ", "a1ZU6bdGjaM").forEach { assertTrue(it, ModelSupport.isMower("Garage", it)) }
    }

    @Test fun listedByProductKeyStaysReadOnly() {
        // A LUBA 2 key under a name without the Luba-VS prefix: listed, but motion stays bound to the name.
        assertTrue(ModelSupport.isMower("Mower 2", "a1iMygIwxFC"))
        assertFalse(ModelSupport.motionValidated("Mower 2"))
        assertTrue(ModelSupport.refusal("Mower 2") != null)
        assertFalse(ModelSupport.motionValidated("MA5000H23A0012345"))
    }

    @Test fun unknownKeyAndNonMowerNameIsSkipped() {
        assertFalse(ModelSupport.isMower("Garage", "zzUnknown01"))
        assertFalse(ModelSupport.isMower("Garage", null))
        val found = CloudRobotFilter.split(listOf(CloudRobot("Garage door 1", "iot-2", "zzUnknown01")))
        assertTrue(found.robots.isEmpty())
        assertEquals(listOf(CloudRobotFilter.Skipped("zzUnknown01", "Gar", 13)), found.skipped)
    }

    @Test fun lubaNameWithoutKnownKeyIsStillKept() {
        assertTrue(ModelSupport.isMower("Luba-VS1A2B3C", "pk1"))
        assertTrue(ModelSupport.isMower("LUBA-2", ""))
        assertTrue(ModelSupport.isMower("Yuka-MN01", null))
        assertEquals(2, CloudRobotFilter.split(listOf(CloudRobot("Luba-VS1A2B3C", "iot1", "pk1"), CloudRobot("Yuka-MN01", "iot2", ""))).robots.size)
    }

    @Test fun rtkStationsAndPoolCleanersAreNotInTheTable() {
        // PyMammotion utility/device_type.py: RTK, RTK NB, SpinoH1, pool cleaners, PC210 pile, Cm900.
        listOf("a1qXkZ5P39W", "a1Nc68bGZzX", "mUzerUySdcC", "a1wIIUUdAMX", "sePx2YW6xDT", "a1mGLcddn4u", "a1vIxwrmpMn", "HwHcWDDVkT9",
            "a1NfZqdSREf", "a1ZuQVL7UiN", "6DPytKe4pKz", "rbk9dxFBe6m", "xEBF8JsgVeR", "a15Cq8FbCh1", "a1UvLZ3mWcW",
            "FCtXbVnmd2C", "YBRDhT2YTvY", "GJzsmaVk5za", "fEaKVY28tNz", "zkRuTK9KsXG", "6DbgVh2Qs5m", "a1tyIkI4q0G", "1SCa3mAX6G")
            .forEach { assertNull(it, RobotModel.fromProductKey(it)); assertFalse(it, ModelSupport.isMower("RTK-AB1234", it)) }
        assertTrue(CloudRobotFilter.split(listOf(CloudRobot("Spino-S1-04", "iot4", "a1UvLZ3mWcW"))).robots.isEmpty())
    }

    @Test fun statusTellsEmptyAccountFromUnrecognizedDevices() {
        assertEquals("Aucun appareil dans le compte", CloudRobotFilter.split(emptyList()).status)
        val two = CloudRobotFilter.split(listOf(CloudRobot("RTK-AB1234", "i1", "a1qXkZ5P39W"), CloudRobot("XYZ", "i2", "zz")))
        assertEquals("2 appareils dans le compte, aucun reconnu comme tondeuse Mammotion : exportez les diagnostics " +
            "(Service → Journaux → Tout exporter) et signalez le modèle", two.status)
        assertTrue(CloudRobotFilter.split(listOf(CloudRobot("XYZ", "i2", "zz"))).status.startsWith("1 appareil dans le compte, aucun reconnu"))
        // Robots found: skipped devices beside them do not change the status.
        assertEquals("1 LUBA dans le compte", CloudRobotFilter.split(listOf(CloudRobot("RTK-AB1234", "i1", "a1qXkZ5P39W"), CloudRobot("Luba-VS1", "i3", "pk"))).status)
    }

    @Test fun namePrefixKeepsTheModelPartPlusTwoCharactersOrThreeWithoutSeparatorAndHalfTheName() {
        assertEquals("Luba-VS", CloudRobotFilter.namePrefix("Luba-VS1A2B3C4D"))
        assertEquals("Luba-", CloudRobotFilter.namePrefix("Luba-VSXXXX"))
        assertEquals("Spino_S1", CloudRobotFilter.namePrefix("Spino_S1ABCDEFGH"))
        assertEquals("RTK-AB", CloudRobotFilter.namePrefix("RTK-AB12345678"))
        assertEquals("RTK-A", CloudRobotFilter.namePrefix("RTK-AB1234"))
        assertEquals("MA5", CloudRobotFilter.namePrefix(" MA5000H23A0012345 "))
        assertEquals("AB1", CloudRobotFilter.namePrefix("AB12345678"))
        assertEquals("Gar", CloudRobotFilter.namePrefix("Garage door 1"))
        assertEquals("Joh", CloudRobotFilter.namePrefix("Johnsmithgarden-12345678"))
        assertEquals("X", CloudRobotFilter.namePrefix("XYZ"))
        assertEquals("", CloudRobotFilter.namePrefix("Q"))
        assertEquals("", CloudRobotFilter.namePrefix(""))
    }

    @Test fun skippedEventCarriesNeitherNameNorSerialNorIotIdAndSurvivesTheExport() {
        val device = CloudRobot("RTK-AB12345678", "iot-SECRET-42", "a1qXkZ5P39W")
        val skipped = CloudRobotFilter.split(listOf(device)).skipped.single()
        val line = JSONObject().put("event", "cloud_device_skipped").put("detail", "Appareil du compte non reconnu comme tondeuse")
            .put("fields", JSONObject(skipped.fields)).toString()
        assertFalse(line.contains(device.name)); assertFalse(line.contains("AB12345678")); assertFalse(line.contains(device.id))
        val exported = JSONObject(LogSanitizer().line(line)).getJSONObject("fields")
        assertEquals("a1qXkZ5P39W", exported.getString("productKey"))
        assertEquals("RTK-AB", exported.getString("namePrefix"))
        assertEquals(14, exported.getInt("nameLength"))
    }
}
