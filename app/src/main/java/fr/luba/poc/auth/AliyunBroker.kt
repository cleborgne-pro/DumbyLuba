package fr.luba.poc.auth

import fr.luba.poc.Clock
import fr.luba.poc.auth.CloudViewModel.CloudEvent
import fr.luba.poc.auth.CloudViewModel.MqttLink
import fr.luba.poc.i18n.tr
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.channels.Channel
import org.json.JSONException
import org.json.JSONObject

/** Account bind refused by the Aliyun broker ([AliyunBroker.BIND_ACCOUNT_IN_USE], [AliyunBroker.BIND_TOKEN_REFUSED]…). */
class AliyunBindException(val code: Int) : IllegalStateException(AliyunBroker.bindRefusal(code))

/**
 * MQTT broker of the Aliyun cloud (PyMammotion `AliyunMQTTTransport`, commit `963209b5`): connection with the app
 * identity of [AliyunCloud.Session], subscription to its `app/down` topics, account bind with the IoT token, then the
 * protobuf events of one robot (`thing/events`, `device_protobuf_msg_event`). TLS trusts only the roots PyMammotion
 * bundles (Aliyun IoT Root CA and GlobalSign, `aliyun_ca.pem`): the Aliyun IoT root is not in the Android trust store.
 */
internal object AliyunBroker {
    const val PORT = 8883
    const val KEEP_ALIVE_SECONDS = 60
    /** Bind accepted; 2152: the account is held by another session (Mammotion app); 2043: IoT token refused. */
    const val BIND_OK = 200
    const val BIND_ACCOUNT_IN_USE = 2152
    const val BIND_TOKEN_REFUSED = 2043
    /** Older events are buffered replays and dropped (PyMammotion `_STALE_EVENT_THRESHOLD_MS`). */
    const val STALE_EVENT_MS = 60_000L
    private const val BIND_TIMEOUT_SECONDS = 10L

    /** Connection parameters of the app identity: host, client id (timestamped), user name, password, topics, bind. */
    data class Credentials(val host: String, val clientId: String, val username: String, val password: String,
                           val topics: List<String>, val bindTopic: String, val bindPayload: String)

    fun credentials(session: AliyunCloud.Session, nowMs: Long = Clock.now()): Credentials {
        val d = session.device
        val timestamp = (nowMs / 1000).toString()
        val base = "/sys/${d.productKey}/${d.deviceName}"
        val username = "${d.deviceName}&${d.productKey}"
        return Credentials(
            host = AliyunCloud.trustedHost("${d.productKey}.iot-as-mqtt.${session.regionId}.aliyuncs.com"),
            clientId = "${session.clientId}|securemode=2,signmethod=hmacsha1,ext=1,_ss=1,timestamp=$timestamp|",
            username = username,
            password = AliyunSignature.mqttPassword(session.clientId, d.deviceName, d.productKey, timestamp, d.deviceSecret),
            topics = listOf("account/bind_reply", "thing/event/property/post_reply", "thing/wifi/status/notify", "thing/wifi/connect/event/notify",
                "_thing/event/notify", "thing/events", "thing/status", "thing/properties", "thing/model/down_raw").map { "$base/app/down/$it" },
            bindTopic = base + MqttSubscriber.ACCOUNT_BIND_SUFFIX,
            bindPayload = JSONObject().put("id", "msgid1").put("version", "1.0").put("request", JSONObject().put("clientId", username))
                .put("params", JSONObject().put("iotToken", session.iotToken)).toString())
    }

    /** Message of the broker, read for one robot. */
    sealed interface Received {
        data class Bind(val code: Int) : Received
        data class Event(val event: CloudEvent) : Received
        /** Another robot, another identifier, a stale replay or an unreadable payload. */
        data object Ignored : Received
    }

    /** [topic]/[payload] read for the robot [iotId]; [properties]: posted properties are relayed too. */
    fun read(topic: String, payload: ByteArray, iotId: String, properties: Boolean, nowMs: Long = Clock.now()): Received {
        val json = try { JSONObject(String(payload)) } catch (_: JSONException) { return Received.Ignored }
        if (topic.endsWith("/account/bind_reply")) return Received.Bind(json.optInt("code", BIND_OK))
        val params = json.optJSONObject("params") ?: return Received.Ignored
        if (params.optString("iotId") != iotId) return Received.Ignored
        val sentAt = params.optLong("time").takeIf { it > 0 } ?: params.optLong("generateTime").takeIf { it > 0 } ?: params.optLong("gmtCreate")
        if (sentAt > 0 && nowMs - sentAt > STALE_EVENT_MS) return Received.Ignored
        if (topic.endsWith("/thing/properties")) return if (properties) Received.Event(CloudEvent.Property(String(payload))) else Received.Ignored
        if (!topic.endsWith("/thing/events") || params.optString("identifier") != "device_protobuf_msg_event") return Received.Ignored
        val content = params.optJSONObject("value")?.optString("content").orEmpty()
        val bytes = try { Base64.getMimeDecoder().decode(content) } catch (_: IllegalArgumentException) { return Received.Ignored }
        return if (bytes.isEmpty()) Received.Ignored else Received.Event(CloudEvent.Proto(bytes))
    }

    /**
     * Connects, subscribes, binds the account and waits for the bind reply (at most 10 s): [robot]'s events then flow
     * into the returned link. A refused bind closes the link with the reason; [onBind] logs the bind code (null: no reply).
     */
    fun connect(session: AliyunCloud.Session, robot: CloudRobot, properties: Boolean, onBind: (Int?) -> Unit): MqttLink {
        val creds = credentials(session)
        val events = Channel<CloudEvent>(64)
        val bindCode = AtomicInteger(-1)
        val bound = CountDownLatch(1)
        val client = MqttSubscriber(
            open = { MqttSubscriber.tls(creds.host, PORT, factory = socketFactory) },
            clientId = creds.clientId, username = creds.username, password = creds.password, keepAliveSeconds = KEEP_ALIVE_SECONDS,
            onMessage = { topic, payload ->
                when (val r = read(topic, payload, robot.id, properties)) {
                    is Received.Bind -> { bindCode.compareAndSet(-1, r.code); bound.countDown(); if (r.code != BIND_OK) events.close(AliyunBindException(r.code)) }
                    is Received.Event -> if (!events.trySend(r.event).isSuccess) events.close(IllegalStateException(tr("Cloud queue full", "File cloud saturée")))
                    Received.Ignored -> Unit
                }
            },
            onLost = { events.close(IllegalStateException(tr("MQTT disconnected", "MQTT déconnecté"))) })
        val link = MqttLink(events) { client.close() }
        try {
            client.connect()
            client.subscribe(creds.topics)
            client.publishAccountBind(creds.bindTopic, creds.bindPayload.toByteArray())
            val replied = bound.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            onBind(if (replied) bindCode.get() else null)
            if (replied && bindCode.get() != BIND_OK) throw AliyunBindException(bindCode.get())
        } catch (e: Exception) { link.close(); throw e }
        return link
    }

    fun bindRefusal(code: Int): String = when (code) {
        BIND_ACCOUNT_IN_USE -> tr("Aliyun: account in use by another session, close the Mammotion app and try again (bind $code)",
            "Aliyun : compte utilisé par une autre session, fermez l'application Mammotion et réessayez (liaison $code)")
        BIND_TOKEN_REFUSED -> tr("Aliyun: IoT token refused by the broker, refresh the robots (bind $code)",
            "Aliyun : jeton IoT refusé par le broker, actualisez les robots (liaison $code)")
        else -> tr("Aliyun: account bind refused (bind $code)", "Aliyun : liaison du compte refusée (liaison $code)")
    }

    /**
     * Bundled roots (from PyMammotion `resources/ca.pem`), by absolute path: R8 moves the classes of the published APK to
     * other packages, so a path relative to this class would no longer find the file.
     */
    const val CA_RESOURCE = "/fr/luba/poc/auth/aliyun_ca.pem"

    /** TLS trusting only the bundled roots ([CA_RESOURCE]). */
    val socketFactory: SSLSocketFactory by lazy {
        val stream = checkNotNull(AliyunBroker::class.java.getResourceAsStream(CA_RESOURCE)) { "$CA_RESOURCE missing" }
        val roots = stream.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); roots.forEachIndexed { i, c -> setCertificateEntry("root$i", c) } }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    /** Number of bundled roots, for the tests. */
    internal fun rootCount(): Int = AliyunBroker::class.java.getResourceAsStream(CA_RESOURCE)
        ?.use { CertificateFactory.getInstance("X.509").generateCertificates(it).size } ?: 0
}
