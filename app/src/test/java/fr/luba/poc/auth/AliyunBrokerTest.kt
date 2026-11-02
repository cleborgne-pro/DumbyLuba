package fr.luba.poc.auth

import fr.luba.poc.Clock
import fr.luba.poc.auth.AliyunBroker.Received
import fr.luba.poc.auth.CloudViewModel.CloudEvent
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aliyun broker (PyMammotion `AliyunMQTTTransport`): credentials of the app identity and messages read for one robot. */
class AliyunBrokerTest {
    private val now = 1_793_527_200_000L
    private val session = AliyunCloud.Session("eu-central-1", "eu-central-1.api-iot.aliyuncs.com", "ABCD1234",
        AliyunCloud.AppDevice("a1APPPK", "APPDEVICE", "DEVSECRET-DEMO"), "ID-DEMO", "IOT-TOKEN-1", now + 72_000_000, "REFRESH-DEMO", now + 720_000_000)
    private val base = "/sys/a1APPPK/APPDEVICE/app/down"

    private fun event(iotId: String = "iot-DEMO-1", identifier: String = "device_protobuf_msg_event", content: String = Base64.getEncoder().encodeToString(byteArrayOf(8, 1)),
                      time: Long = now - 1_000) = JSONObject().put("params", JSONObject().put("iotId", iotId).put("identifier", identifier).put("time", time)
        .put("value", JSONObject().put("content", content))).toString().toByteArray()
    private fun read(topic: String, payload: ByteArray, properties: Boolean = false) = AliyunBroker.read(topic, payload, "iot-DEMO-1", properties, now)

    @Test fun `credentials of the app identity match PyMammotion`() {
        val c = AliyunBroker.credentials(session, now)
        assertEquals("a1APPPK.iot-as-mqtt.eu-central-1.aliyuncs.com", c.host)
        assertEquals("ABCD1234|securemode=2,signmethod=hmacsha1,ext=1,_ss=1,timestamp=1793527200|", c.clientId)
        assertEquals("APPDEVICE&a1APPPK", c.username)
        assertEquals("5fa0e4ac51c6dbae9bf5d933482ed062935c9ef4", c.password)
        assertEquals(9, c.topics.size); assertTrue(c.topics.all { it.startsWith("$base/") })
        assertTrue(c.topics.containsAll(listOf("$base/account/bind_reply", "$base/thing/events", "$base/thing/properties")))
        assertEquals("/sys/a1APPPK/APPDEVICE/app/up/account/bind", c.bindTopic)
        val bind = JSONObject(c.bindPayload)
        assertEquals("IOT-TOKEN-1", bind.getJSONObject("params").getString("iotToken"))
        assertEquals("APPDEVICE&a1APPPK", bind.getJSONObject("request").getString("clientId"))
    }

    @Test fun `robot protobuf events are kept, other robots, identifiers and stale replays are not`() {
        val kept = read("$base/thing/events", event())
        assertArrayEquals(byteArrayOf(8, 1), ((kept as Received.Event).event as CloudEvent.Proto).bytes)
        assertEquals(Received.Ignored, read("$base/thing/events", event(iotId = "iot-OTHER")))
        assertEquals(Received.Ignored, read("$base/thing/events", event(identifier = "device_warning_event")))
        assertEquals(Received.Ignored, read("$base/thing/events", event(time = now - AliyunBroker.STALE_EVENT_MS - 1)))
        assertEquals(Received.Ignored, read("$base/thing/events", event(content = "%%%")))
        assertEquals(Received.Ignored, read("$base/thing/events", "not json".toByteArray()))
        assertEquals(Received.Ignored, read("$base/thing/status", event()))
    }

    @Test fun `bind replies and properties`() {
        assertEquals(Received.Bind(2152), read("$base/account/bind_reply", """{"code":2152,"id":"msgid1"}""".toByteArray()))
        assertEquals(Received.Bind(200), read("$base/account/bind_reply", """{"id":"msgid1"}""".toByteArray()))
        val property = JSONObject().put("params", JSONObject().put("iotId", "iot-DEMO-1").put("generateTime", now)
            .put("items", JSONObject().put("otaProgress", JSONObject().put("value", "{}")))).toString().toByteArray()
        assertEquals(Received.Ignored, read("$base/thing/properties", property))
        assertTrue((read("$base/thing/properties", property, properties = true) as Received.Event).event is CloudEvent.Property)
    }

    @Test fun `refused binds explain what to do`() {
        assertTrue(AliyunBroker.bindRefusal(2152), AliyunBroker.bindRefusal(2152).startsWith("Aliyun : compte utilisé par une autre session, fermez l'application Mammotion"))
        assertTrue(AliyunBroker.bindRefusal(2043).contains("jeton IoT refusé"))
        assertEquals("Aliyun : liaison du compte refusée (liaison 7)", AliyunBroker.bindRefusal(7))
    }

    @Test fun `TLS trusts the bundled Aliyun IoT and GlobalSign roots`() {
        assertEquals(5, AliyunBroker.rootCount())
        AliyunBroker.socketFactory
    }

    @Test fun `roots found from a class of another package, as after R8 renames the packages`() {
        assertTrue(AliyunBroker.CA_RESOURCE.startsWith("/"))
        assertTrue(Clock::class.java.getResourceAsStream(AliyunBroker.CA_RESOURCE).use { it != null })
    }
}
