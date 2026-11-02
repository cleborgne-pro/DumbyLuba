package fr.luba.poc.auth

import android.content.Context
import androidx.core.content.edit
import fr.luba.poc.Clock
import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONException
import org.json.JSONObject

/**
 * Mammotion's older Aliyun IoT cloud, where the robots sold before 2025 (LUBA 1 among them) stay bound: the device page
 * of the Mammotion cloud does not list them (DumbyLuba#3). Port of PyMammotion `CloudIOTGateway` (commit `963209b5`):
 * sign-in chain from the Mammotion account ([signIn]), robots of the account ([robots]) and protobuf commands
 * ([invoke], filtered by [CloudReadOnly] like the Mammotion cloud). Pending shares are never accepted, nothing is bound
 * or unbound. The Aliyun session stays in memory: a new process signs in again.
 *
 * [identitySeed]: stable per installation, it derives the app identity registered by `/app/aepauth/handle` (PyMammotion
 * `generate_hardware_string`), so that the same phone keeps the same Aliyun app device. [open]: replaced only by the JVM
 * tests (local server). [onStep]: one call per request of the chain, for the diagnostics (never a token or an identifier).
 */
class AliyunCloud internal constructor(
    private val mammotion: MammotionCloud,
    private val identitySeed: () -> String,
    private val open: (URL) -> HttpURLConnection,
) {
    constructor(mammotion: MammotionCloud, context: Context) : this(mammotion, { installSeed(context) }, { it.openConnection() as HttpsURLConnection })

    /** Request of the chain, as logged: path, domain, HTTP status, API code (null when not read). */
    data class Step(val endpoint: String, val host: String, val httpCode: Int?, val apiCode: Int?) {
        val fields: Map<String, Any?> get() = mapOf("endpoint" to endpoint, "host" to host, "httpCode" to httpCode, "apiCode" to apiCode)
    }

    /** App identity of the account on the Aliyun broker (`/app/aepauth/handle`). */
    data class AppDevice(val productKey: String, val deviceName: String, val deviceSecret: String)

    /** Aliyun session: region endpoints, app identity and IoT token (times in ms). */
    data class Session(val regionId: String, val apiGateway: String, val clientId: String, val device: AppDevice, val identityId: String,
                       val iotToken: String, val iotTokenExpiresAt: Long, val refreshToken: String, val refreshTokenExpiresAt: Long)

    @Volatile var onStep: (Step) -> Unit = {}
    @Volatile private var session: Session? = null

    private val seed by lazy { identitySeed() }
    /** PyMammotion `_client_id` (8 characters), `_device_sn` and `_utdid` (32 characters), salted with the account. */
    private val clientId get() = hardwareString(8)
    private val deviceSn get() = hardwareString(32)
    private val utdid get() = hardwareString(32)

    private fun hardwareString(length: Int): String {
        val hash = MessageDigest.getInstance("SHA-1").digest((seed + mammotion.userAccount()).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return (0 until length).map { hash[it % hash.length] }.joinToString("")
    }

    fun clear() { session = null }

    /** Valid session: the current one, renewed when its IoT token expires within the hour, otherwise a new sign-in. */
    @Synchronized fun session(): Session {
        val current = session ?: return signIn()
        if (current.iotTokenExpiresAt > Clock.now() + TOKEN_MARGIN_MS) return current
        if (current.refreshTokenExpiresAt > Clock.now()) runCatching { return refresh(current) }.onFailure { if (it !is CloudResponseException) throw it }
        return signIn()
    }

    /**
     * PyMammotion `_connect_iot`: region of the account country, open account `connect.json`, `loginbyoauth.json` with the
     * Mammotion authorization code, app identity (`aepauth/handle`) and IoT session (`createSessionByAuthCode`).
     */
    @Synchronized fun signIn(): Session {
        session = null
        val country = mammotion.countryCode()
        // One code for the region and the open account sign-in, as PyMammotion.
        val authorization = mammotion.authorizationCode()
        val refused = authorization.freshFailure
        onStep(when {
            authorization.fresh -> Step(AUTHORIZATION_CODE, MAMMOTION_ID, 200, 0)
            refused == null -> Step(AUTHORIZATION_CODE, MAMMOTION_ID, null, null)
            else -> Step(AUTHORIZATION_CODE, MAMMOTION_ID, if (refused.http) refused.code else 200, if (refused.http) null else refused.code)
        })
        val authCode = authorization.code
        val region = gateway(ALIYUN_DOMAIN, "/living/account/region/get", "1.0.2",
            JSONObject().put("authCode", authCode).put("type", "THIRD_AUTHCODE").put("countryCode", country)).getJSONObject("data")
        val apiGateway = trustedHost(region.getString("apiGatewayEndpoint"))
        val oaGateway = trustedHost(region.getString("oaApiGatewayEndpoint"))
        val connect = connect()
        val vid = connect.getJSONObject("data").getString("vid")
        val deviceId = connect.getJSONObject("data").getJSONObject("data").getJSONObject("device").getJSONObject("data").getString("deviceId")
        val sid = loginByOauth(oaGateway, country, vid, deviceId, authCode)
        val device = aepHandle(apiGateway)
        val data = gateway(apiGateway, "/account/createSessionByAuthCode", "1.0.4", JSONObject().put("request",
            JSONObject().put("authCode", sid).put("accountType", "OA_SESSION").put("appKey", APP_KEY))).getJSONObject("data")
        return sessionFrom(data, region.getString("regionId"), apiGateway, device).also { session = it }
    }

    /** `checkOrRefreshSession` with the refresh token (PyMammotion `check_or_refresh_session`). */
    private fun refresh(current: Session): Session {
        val data = gateway(current.apiGateway, "/account/checkOrRefreshSession", "1.0.4", JSONObject().put("request",
            JSONObject().put("refreshToken", current.refreshToken).put("identityId", current.identityId))).getJSONObject("data")
        return sessionFrom(data, current.regionId, current.apiGateway, current.device).also { session = it }
    }

    private fun sessionFrom(data: JSONObject, regionId: String, apiGateway: String, device: AppDevice): Session {
        val now = Clock.now()
        return Session(regionId, apiGateway, clientId, device, data.getString("identityId"), data.getString("iotToken"),
            now + data.getLong("iotTokenExpire") * 1000, data.getString("refreshToken"), now + data.getLong("refreshTokenExpire") * 1000)
    }

    /** Robots bound to the account (`/uc/listBindingByAccount`, owned and shared), as [CloudRobot] of the Aliyun cloud. */
    fun robots(): List<CloudRobot> {
        val s = session()
        val data = gateway(s.apiGateway, "/uc/listBindingByAccount", "1.0.8", JSONObject().put("pageSize", 100).put("pageNo", 1), s.iotToken)
        val list = data.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("iotId").isNotBlank() && it.optString("deviceName").isNotBlank() }
            .map { CloudRobot(it.getString("deviceName").trim(), it.getString("iotId").trim(), it.optString("productKey").trim(), CloudKind.ALIYUN) }
    }

    /**
     * Protobuf command through `/thing/service/invoke` (`device_protobuf_sync_service`), same [CloudReadOnly] barrier as
     * [MammotionCloud.invoke]. An IoT token refused (460, 29003) renews the session once.
     */
    fun invoke(payload: ByteArray, iotId: String, nativeReturn: Boolean = false,
               navReceiver: LubaMsgProto.MsgDevice = LubaMsgProto.MsgDevice.DEV_NAVIGATION) {
        CloudReadOnly.requireAllowed(payload, nativeReturn, navReceiver)
        val params = JSONObject().put("args", JSONObject().put("content", Base64.getEncoder().encodeToString(payload)))
            .put("identifier", "device_protobuf_sync_service").put("iotId", iotId)
        val s = session()
        try { gateway(s.apiGateway, "/thing/service/invoke", "1.0.5", params, s.iotToken) } catch (e: CloudResponseException) {
            if (e.http || e.code !in TOKEN_REFUSED) throw e
            synchronized(this) { session = null }
            val renewed = session()
            gateway(renewed.apiGateway, "/thing/service/invoke", "1.0.5", params, renewed.iotToken)
        }
    }

    /** `/app/aepauth/handle`: app identity (product key, device name and secret) of this installation on the Aliyun broker. */
    private fun aepHandle(apiGateway: String): AppDevice {
        val timestamp = "%.3f".format(Locale.US, Clock.now() / 1000.0)
        val data = gateway(apiGateway, "/app/aepauth/handle", "1.0.0", JSONObject().put("authInfo", JSONObject()
            .put("clientId", clientId).put("sign", AliyunSignature.aep(APP_KEY, clientId, deviceSn, timestamp, APP_SECRET))
            .put("deviceSn", deviceSn).put("timestamp", timestamp))).getJSONObject("data")
        return AppDevice(data.getString("productKey"), data.getString("deviceName"), data.getString("deviceSecret"))
    }

    /** Open account device registration (`sdk.openaccount.aliyun.com/api/prd/connect.json`, signed query `request`). */
    private fun connect(): JSONObject {
        val request = JSONObject()
            .put("context", JSONObject().put("sdkVersion", SDK_VERSION).put("platformName", "android").put("netType", "wifi").put("appKey", APP_KEY)
                .put("yunOSId", "").put("appVersion", APP_VERSION).put("utDid", utdid).put("appAuthToken", utdid).put("securityToken", utdid))
            .put("config", JSONObject().put("version", 0).put("lastModify", 0))
            .put("device", JSONObject().put("model", "sdk_gphone_x86_arm").put("brand", "goldfish_x86").put("platformVersion", "30"))
            .toString()
        return openAccount(OPEN_ACCOUNT_DOMAIN, "/api/prd/connect.json", "request", request, "application/x-www-form-urlencoded", emptyMap(), inQuery = true)
    }

    /** `loginbyoauth.json` on the region open account gateway: session id (`sid`) of the Mammotion authorization code. */
    private fun loginByOauth(oaGateway: String, country: String, vid: String, deviceId: String, authCode: String): String {
        val request = JSONObject().put("country", country).put("authCode", authCode).put("oauthPlateform", 23).put("oauthAppKey", APP_KEY)
            .put("riskControlInfo", JSONObject().put("appID", "com.agilexrobotics").put("appAuthToken", "").put("signType", "RSA")
                .put("sdkVersion", SDK_VERSION).put("utdid", utdid).put("umidToken", utdid).put("deviceId", deviceId)
                .put("USE_OA_PWD_ENCRYPT", "true").put("USE_H5_NC", "true"))
            .toString()
        val reply = openAccount(oaGateway, "/api/prd/loginbyoauth.json", "loginByOauthRequest", request,
            "application/x-www-form-urlencoded; charset=utf-8", mapOf("vid" to vid), inQuery = false)
        val inner = reply.optJSONObject("data")
        val sid = inner?.optJSONObject("data")?.optJSONObject("loginSuccessResult")?.optString("sid").orEmpty()
        if (sid.isBlank()) throw CloudResponseException(inner?.optInt("code", -1) ?: -1, "/api/prd/loginbyoauth.json", oaGateway, http = false)
        return sid
    }

    /** Open account request: [name]=[value] in the query or in a form body, signed over the raw (not encoded) value. */
    private fun openAccount(host: String, path: String, name: String, value: String, contentType: String, extra: Map<String, String>, inQuery: Boolean): JSONObject {
        val headers = linkedMapOf("date" to httpDate(), "x-ca-nonce" to UUID.randomUUID().toString(), "x-ca-key" to APP_KEY,
            "x-ca-signaturemethod" to "HmacSHA256", "accept" to "application/json", "content-type" to contentType, "user-agent" to USER_AGENT) + extra
        val signed = AliyunSignature.gateway("POST", headers, "$path?$name=$value", APP_SECRET)
        val encoded = "$name=" + URLEncoder.encode(value, "UTF-8")
        val all = headers + mapOf("x-ca-signature-headers" to signed.signedHeaders, "x-ca-signature" to signed.signature)
        val (code, reply) = post(host, if (inQuery) "$path?$encoded" else path, all, if (inQuery) null else encoded)
        return checked(path, host, code, reply, apiCode = null)
    }

    /** API gateway request (PyMammotion `Client.async_do_request`): `IoTApiRequest` body, signed headers and request id. */
    private fun gateway(host: String, path: String, apiVersion: String, params: JSONObject, iotToken: String? = null): JSONObject {
        val request = JSONObject().put("apiVer", apiVersion).put("language", "en-US").apply { if (iotToken != null) put("iotToken", iotToken) }
        val body = JSONObject().put("id", UUID.randomUUID().toString()).put("params", params).put("request", request).put("version", "1.0").toString()
        val requestId = UUID.randomUUID().toString()
        val headers = linkedMapOf("date" to httpDate(), "x-ca-nonce" to UUID.randomUUID().toString(), "x-ca-key" to APP_KEY,
            "x-ca-signaturemethod" to "HmacSHA256", "accept" to "application/json; charset=utf-8", "user-agent" to "ALIYUN-ANDROID-DEMO",
            // Nanoseconds, as PyMammotion sends them (`time.time_ns()`).
            "x-ca-timestamp" to (Clock.now() * 1_000_000).toString(), "content-type" to "application/octet-stream", "content-md5" to AliyunSignature.contentMd5(body))
        val signed = AliyunSignature.gateway("POST", headers, "$path?x-ca-request-id=$requestId", APP_SECRET)
        val all = headers + mapOf("x-ca-signature-headers" to signed.signedHeaders, "x-ca-signature" to signed.signature, "ca_version" to "1")
        val (code, reply) = post(host, "$path?x-ca-request-id=$requestId", all, body)
        val json = checked(path, host, code, reply, apiCode = reply?.optInt("code", -1))
        if (json.optInt("code", -1) != 200) throw CloudResponseException(json.optInt("code", -1), path, host, http = false)
        return json
    }

    private fun checked(path: String, host: String, code: Int, reply: JSONObject?, apiCode: Int?): JSONObject {
        onStep(Step(path, host, code, apiCode))
        if (code != 200) throw CloudResponseException(code, path, host)
        return reply ?: throw CloudResponseException(-1, path, host, http = false)
    }

    private fun post(host: String, pathAndQuery: String, headers: Map<String, String>, body: String?): Pair<Int, JSONObject?> {
        val url = URL("https://${trustedHost(host)}$pathAndQuery")
        val c = open(url)
        try {
            c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.requestMethod = "POST"
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            // Always a body, empty for connect.json (parameters in the query): a POST without one is refused by some stacks.
            c.doOutput = true; c.outputStream.use { it.write(body.orEmpty().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readLimited()) }.orEmpty()
            return code to (try { JSONObject(text) } catch (_: JSONException) { null })
        } finally { c.disconnect() }
    }

    companion object {
        /** Upstream Aliyun app of the Mammotion app (PyMammotion `const.APP_KEY` / `APP_SECRET`), no user account secret. */
        internal const val APP_KEY = "34231230"
        private const val APP_SECRET = "1ba85698bb10e19c6437413b61ba3445"
        private const val APP_VERSION = "2.3.20.30"
        private const val SDK_VERSION = "3.4.2"
        private const val USER_AGENT = "AlibabaCloud (Android) TeaDSL/1"
        const val ALIYUN_DOMAIN = "api.link.aliyun.com"
        /** Step of the Mammotion authorization code: HTTP 200 when fresh, otherwise the refusal (the code of the last sign-in is used). */
        const val AUTHORIZATION_CODE = "/authorization/code"
        private const val MAMMOTION_ID = "id.mammotion.com"
        private const val OPEN_ACCOUNT_DOMAIN = "sdk.openaccount.aliyun.com"
        /** IoT token refused: 460 (expired), 29003 (identityId blank). */
        private val TOKEN_REFUSED = setOf(460, 29003)
        /** The IoT token is renewed when it expires within the hour (PyMammotion). */
        private const val TOKEN_MARGIN_MS = 3_600_000L
        private const val MAX_REPLY_BYTES = 4 * 1024 * 1024

        /** HTTPS Aliyun host: the two fixed domains or an `aliyuncs.com` region endpoint given by `region/get`. */
        fun trustedHost(host: String): String {
            val h = host.trim()
            val lower = h.lowercase(Locale.ROOT)
            require(lower == ALIYUN_DOMAIN || lower == OPEN_ACCOUNT_DOMAIN || (lower.endsWith(".aliyuncs.com") && h.all { it.isLetterOrDigit() || it == '.' || it == '-' })) {
                tr("Aliyun HTTPS domain required", "Domaine Aliyun HTTPS requis") }
            // Kept as given: the broker host carries the product key, case included (PyMammotion).
            return h
        }

        private fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(Clock.now()))

        /** Random seed kept for the installation (identity of the app on the Aliyun broker). */
        private fun installSeed(context: Context): String {
            val prefs = context.getSharedPreferences("aliyun_identity", Context.MODE_PRIVATE)
            return prefs.getString("seed", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("seed", it) } }
        }

        private fun InputStream.readLimited(): ByteArray {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= MAX_REPLY_BYTES) { tr("Response too large", "Réponse trop grande") }; out.write(buffer, 0, n) }
            return out.toByteArray()
        }
    }
}
