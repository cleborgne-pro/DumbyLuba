package fr.luba.poc.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owned devices (`device/list`) read when the device page is empty: robots on the older Aliyun cloud (DumbyLuba#3). */
class CloudOwnedDevicesTest {
    /** `device/list` reply as PyMammotion parses it (`Response[list[DeviceInfo]]`), fictitious values. */
    private val reply = JSONObject("""{"code":0,"msg":"success","data":[
        {"iotId":"iot-SECRET-1","deviceId":"dev-1","deviceName":"MA5000DEMO000001","deviceType":"LUBA","series":"LUBA",
         "productSeries":"Luba","iconCode":"x","generation":1,"status":1,"productKey":"a1FbaU4Bqk5"},
        {"iotId":"iot-SECRET-2","deviceName":"RTK-DEMO000002","deviceType":"RTK","series":"RTK","generation":1},
        {"iotId":"iot-SECRET-3","deviceName":"Luba-VSDEMO003","deviceType":"LUBA_2","series":"LUBA2"}]}""")

    @Test fun parsesTheDeviceListAndItsVariants() {
        val owned = CloudOwnedDevices.parse(reply)
        assertEquals(listOf("MA5000DEMO000001", "RTK-DEMO000002", "Luba-VSDEMO003"), owned.map { it.name })
        assertEquals("a1FbaU4Bqk5", owned[0].productKey); assertEquals("", owned[1].productKey)
        assertEquals(1, owned[0].generation); assertNull(owned[2].generation)
        // Page shape and unknown shapes.
        val page = JSONObject("""{"code":0,"data":{"records":[{"iotId":"iot-4","deviceName":"Luba-DEMO0004"}],"total":1}}""")
        assertEquals(listOf("iot-4"), CloudOwnedDevices.parse(page).map { it.iotId })
        assertTrue(CloudOwnedDevices.parse(JSONObject("""{"code":0,"data":null}""")).isEmpty())
        assertTrue(CloudOwnedDevices.parse(JSONObject("""{"code":0}""")).isEmpty())
        assertTrue(CloudOwnedDevices.parse(JSONObject("""{"code":0,"data":"x"}""")).isEmpty())
    }

    @Test fun robotsMissingFromAnEmptyPageGiveTheAliyunMessage() {
        val owned = CloudOwnedDevices.parse(reply)
        val empty = CloudRobotFilter.split(emptyList())
        val missing = CloudOwnedDevices.missingRobots(owned, empty.robots)
        // The RTK station is not a robot; a robot without a known prefix or key still counts.
        assertEquals(listOf("MA5000DEMO000001", "Luba-VSDEMO003"), missing.map { it.name })
        assertTrue(CloudOwnedDevices.robot(CloudOwnedDevices.Owned("SERIAL0001", "iot", "", "", "", "", null)))
        assertFalse(CloudOwnedDevices.robot(CloudOwnedDevices.Owned("Base", "iot", "", "", "RTK3A0", "", null)))
        assertFalse(CloudOwnedDevices.robot(CloudOwnedDevices.Owned("Spino-S1DEMO", "iot", "", "", "", "", null)))
        assertEquals("2 robots de votre compte sont sur l'ancien cloud Mammotion (Aliyun), que l'application ne prend pas encore en charge ; le contrôle Bluetooth fonctionne toujours.",
            CloudOwnedDevices.status(empty, missing))
        assertEquals("Votre LUBA est sur l'ancien cloud Mammotion (Aliyun), que l'application ne prend pas encore en charge ; le contrôle Bluetooth fonctionne toujours.",
            CloudOwnedDevices.status(empty, missing.take(1)))
        // Nothing missing (empty list, or only an RTK station): the current status stays.
        assertEquals("Aucun appareil dans le compte", CloudOwnedDevices.status(empty, emptyList()))
        assertEquals("Aucun appareil dans le compte", CloudOwnedDevices.status(empty, CloudOwnedDevices.missingRobots(owned.filter { it.name.startsWith("RTK") }, empty.robots)))
    }

    @Test fun robotsListedByThePageAreNotMissing() {
        val owned = CloudOwnedDevices.parse(reply)
        val page = CloudRobotFilter.split(listOf(CloudRobot("Luba-VSDEMO003", "iot-SECRET-3", "a1iMygIwxFC"), CloudRobot("MA5000DEMO000001", "other-id", "a1FbaU4Bqk5")))
        assertTrue(CloudOwnedDevices.missingRobots(owned, page.robots).isEmpty())
        assertEquals(page.status, CloudOwnedDevices.status(page, CloudOwnedDevices.missingRobots(owned, emptyList())))
    }

    @Test fun logFieldsCarryNoNameSerialNorIotId() {
        val owned = CloudOwnedDevices.parse(reply)
        val fields = CloudOwnedDevices.fields(owned, CloudOwnedDevices.missingRobots(owned, emptyList()))
        assertEquals(3, fields["devices"]); assertEquals(2, fields["missingFromPage"]); assertEquals(1, fields["withProductKey"])
        assertEquals(listOf("a1"), fields["productKeyPrefixes"])
        assertEquals(listOf("LUBA", "LUBA_2", "RTK"), fields["deviceTypes"])
        val text = fields.toString()
        listOf("SECRET", "DEMO000001", "DEMO000002", "VSDEMO003", "a1FbaU4Bqk5", "dev-1").forEach { assertFalse("$it in $text", text.contains(it)) }
    }
}
