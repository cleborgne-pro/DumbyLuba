package fr.luba.poc.camera

import org.json.JSONArray
import org.json.JSONObject
import fr.luba.poc.i18n.tr

/**
 * Agora credentials returned by `POST /device-server/v1/stream/token` (PyMammotion `get_stream_subscription`,
 * `StreamSubscriptionResponse` model). [uid]/[token] identify the audience member; the robot publishes camera n
 * (cameraStates) under Agora uid n + 1: 1 = left/front, 2 = right, 3 = rear (Yuka).
 * [key]/[salt] (base64) are used for the AES-256-GCM2 channel encryption when [encrypted].
 */
data class CameraCredentials(
    val appId: String, val channel: String, val token: String, val uid: Long,
    val encrypted: Boolean = false, val key: String? = null, val salt: String? = null,
    val areaCode: String? = null, val license: String? = null,
    /** Seconds of video left over 4G (Mammotion quota), if provided. */
    val availableSeconds: Int? = null,
    val cameraIds: List<Int> = emptyList(),
) {
    /** Parameters passed to the WebView page (never logged: contains the token). */
    fun toPageJson(targetUid: Int): String = JSONObject()
        .put("appid", appId).put("channel", channel).put("token", token).put("uid", uid)
        .put("encrypted", encrypted).put("key", key ?: "").put("salt", salt ?: "")
        .put("target", targetUid).toString()

    /** Loggable summary: no token or key. */
    val summary: String get() = tr("channel ${channel.take(6)}… · viewer $uid · cameras ${cameraIds.joinToString().ifEmpty { "—" }}",
        "canal ${channel.take(6)}… · spectateur $uid · caméras ${cameraIds.joinToString().ifEmpty { "—" }}") +
        (if (encrypted) tr(" · encrypted", " · chiffré") else "") + (availableSeconds?.let { tr(" · 4G left ${it / 60} min", " · 4G restant ${it / 60} min") } ?: "")

    companion object {
        /** Full `{code, msg, data}` response; explicit error if `data` is missing or incomplete. */
        fun parse(response: JSONObject): CameraCredentials {
            val data = response.optJSONObject("data") ?: error(tr("Mammotion: no video stream returned (robot offline?)", "Mammotion : aucun flux vidéo renvoyé (robot hors ligne ?)"))
            val appId = data.optString("appid"); val channel = data.optString("channelName"); val token = data.optString("token")
            check(appId.isNotBlank() && channel.isNotBlank() && token.isNotBlank()) { tr("Mammotion: incomplete video credentials", "Mammotion : identifiants vidéo incomplets") }
            val cameras = data.optJSONArray("cameras") ?: JSONArray()
            return CameraCredentials(appId = appId, channel = channel, token = token, uid = data.optLong("uid"),
                encrypted = data.optInt("openEncrypt") == 1,
                key = data.optString("key").takeIf { it.isNotBlank() && !data.isNull("key") },
                salt = data.optString("salt").takeIf { it.isNotBlank() && !data.isNull("salt") },
                areaCode = data.optString("areaCode").takeIf { it.isNotBlank() && !data.isNull("areaCode") },
                license = data.optString("license").takeIf { it.isNotBlank() && !data.isNull("license") },
                availableSeconds = if (data.has("availableTime") && !data.isNull("availableTime")) data.optInt("availableTime") else null,
                cameraIds = (0 until cameras.length()).mapNotNull { cameras.optJSONObject(it)?.optInt("cameraId") })
        }

        /** Request body: front camera only (like the official app) or front + right. */
        fun requestBody(iotId: String, bothFront: Boolean): JSONObject = JSONObject().put("deviceId", iotId).put("mode", 0)
            .put("cameraStates", JSONArray().apply { listOf(1, if (bothFront) 1 else 0, 0).forEach { put(JSONObject().put("cameraState", it)) } })

        /** Known cloud error messages for the video. */
        fun failure(code: Int): String = when (code) {
            50504 -> tr("the cloud cannot reach the robot (50504): robot Wi-Fi or 4G off?", "le cloud ne joint pas le robot (50504) : Wi-Fi ou 4G du robot coupé ?")
            401, 460, 40101, 40102 -> tr("Mammotion session expired, sign in to the account again", "session Mammotion expirée, reconnectez le compte")
            else -> "code $code"
        }

        /** Readable reason for a token request failure ([cloudCode]: code returned by the cloud). */
        fun failureReason(error: Throwable, cloudCode: Int?): String = when {
            cloudCode != null -> failure(cloudCode)
            error is java.net.UnknownHostException -> tr("no Internet access on the phone (cloud address not found)", "pas d'accès Internet sur le téléphone (adresse du cloud introuvable)")
            error is java.net.SocketTimeoutException -> tr("network timeout (phone Internet?)", "délai réseau dépassé (Internet du téléphone ?)")
            error is java.net.ConnectException || error is javax.net.ssl.SSLException -> tr("cannot connect to the Mammotion cloud (${error.javaClass.simpleName})", "connexion au cloud Mammotion impossible (${error.javaClass.simpleName})")
            error.message?.let { m -> listOf("Mammotion", tr("Sign in", "Connectez"), tr("No LUBA", "Aucun"), "1/2", "2/2").any(m::startsWith) } == true -> error.message!!
            else -> error.javaClass.simpleName
        }
    }
}
