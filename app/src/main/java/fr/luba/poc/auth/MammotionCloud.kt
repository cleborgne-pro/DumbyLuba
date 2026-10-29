package fr.luba.poc.auth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import android.util.Base64
import java.util.UUID
import fr.luba.poc.i18n.tr
/** Exact PyMammotion HTTP endpoints; writes limited to the native return to base and the firmware update, on explicit request. */
/** [open]: opens the connection of an already checked URL; replaced only by JVM tests (local server). */
class MammotionCloud internal constructor(private val store:SessionStore, private val open:(URL)->HttpURLConnection) {
    constructor(store:SessionStore):this(store,{ it.openConnection() as HttpsURLConnection })
    private var token=store.read()?.let{JSONObject(it).optString("access_token")}.orEmpty()
    private val clientId="${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(7)}_1"
    private fun trusted(url:String):URL {
        val u=URL(url);require(u.protocol=="https" && (u.host=="mammotion.com" || u.host.endsWith(".mammotion.com")) && u.userInfo==null) { tr("HTTPS Mammotion domain required", "Domaine Mammotion HTTPS requis") };return u
    }
    fun request(url:String,method:String="GET",body:JSONObject?=null,headers:Map<String,String> = emptyMap()):JSONObject {
        return AuthorizedRequest.run(
            request = { requestOnce(url,method,body,headers) },
            refresh = { refreshSession() },
            canRefresh = !url.startsWith("https://id.mammotion.com/oauth2/token")
        )
    }
    private fun requestOnce(url:String,method:String,body:JSONObject?,headers:Map<String,String>):JSONObject {
        val u=trusted(url);val c=open(u)
        try {
            c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=20000;c.requestMethod=method
            c.setRequestProperty("User-Agent","okhttp/4.9.3");c.setRequestProperty("App-Version","NOT HA,2.3.20.30")
            c.setRequestProperty("Client-Id",clientId);c.setRequestProperty("Client-Type","1")
            if(token.isNotEmpty() && u.host != "id.mammotion.com") c.setRequestProperty("Authorization","Bearer $token")
            headers.forEach { (k,v)->c.setRequestProperty(k,v) }
            if(body!=null) { c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())} }
            // Path and domain only in the error: the query of the sign-in carries the credentials.
            val code=c.responseCode;if(code!=200) throw CloudResponseException(code,u.path,u.host)
            val bytes=c.inputStream.use{it.readBytesLimited(4*1024*1024)}
            val json=JSONObject(String(bytes));val result=json.optInt("code",-1);if(result !in listOf(0,200)) throw CloudResponseException(result,u.path,u.host,http=false)
            return json
        } finally {c.disconnect()}
    }
    fun login(email:String,password:String) {
        require(email.isNotBlank() && password.isNotEmpty())
        val params=linkedMapOf("username" to email,"password" to Base64.encodeToString(password.toByteArray(),Base64.NO_WRAP),"client_id" to OAUTH_CLIENT,"grant_type" to "password","authType" to "0")
        val timestamp=System.currentTimeMillis()
        val compact=params.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key)+":"+JSONObject.quote(it.value) }
        val signature=OAuthSignature.sign(OAUTH_CLIENT,OAUTH_SECRET,timestamp,compact)
        val query=params.entries.joinToString("&") { it.key+"="+URLEncoder.encode(it.value,"UTF-8") }
        val json=try {
            request("https://id.mammotion.com/oauth2/token?$query","POST",headers=mapOf("Ma-App-Key" to OAUTH_CLIENT,"Ma-Signature" to signature,"Ma-Timestamp" to timestamp.toString()))
        } catch(e:CloudResponseException) {
            if(e.code in listOf(400,401,403,40101,40102)) throw IllegalStateException(tr("Mammotion: email or password rejected (code ${e.code})", "Mammotion : e-mail ou mot de passe refusé (code ${e.code})"),e)
            throw e
        }
        val data=json.getJSONObject("data");check(data.optString("access_token").isNotBlank()) { tr("No session", "Session absente") }
        store.save(data.toString());token=data.getString("access_token")
    }
    /**
     * Saved account (Me tab, encrypted) used to sign in again when the session can no longer be renewed: signing in
     * with the same account elsewhere (other build of the app, Mammotion app) revokes this session and its refresh token.
     */
    @Volatile var savedAccount:()->SavedAccount? = { null }
    @Synchronized private fun refreshSession() {
        val old=store.read()?.let(::JSONObject)
        // Explicit sign-out: no silent sign-in.
        if(old==null) { logout();error(tr("Mammotion: session expired, sign in to your account again", "Mammotion : session expirée, reconnectez votre compte")) }
        val refresh=old.optString("refresh_token")
        if(refresh.isNotBlank() && renew(old,refresh)) return
        signInAgain()
    }
    /** Refresh-token grant; false if the cloud refuses the refresh token. */
    private fun renew(old:JSONObject,refresh:String):Boolean {
        val params=linkedMapOf("client_id" to OAUTH_CLIENT,"grant_type" to "refresh_token","refresh_token" to refresh)
        val timestamp=System.currentTimeMillis()
        val compact=params.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key)+":"+JSONObject.quote(it.value) }
        val query=params.entries.joinToString("&") { it.key+"="+URLEncoder.encode(it.value,"UTF-8") }
        val response=try {
            requestOnce("https://id.mammotion.com/oauth2/token?$query","POST",null,
                mapOf("Ma-App-Key" to OAUTH_CLIENT,"Ma-Signature" to OAuthSignature.sign(OAUTH_CLIENT,OAUTH_SECRET,timestamp,compact),
                    "Ma-Timestamp" to timestamp.toString()))
        } catch(e:CloudResponseException) {
            if(e.code in listOf(401,403,40101,40102)) return false
            throw e
        }
        val data=response.getJSONObject("data")
        check(data.optString("access_token").isNotBlank()) { tr("No session", "Session absente") }
        data.keys().forEach { key ->
            if(!data.isNull(key) && !(key=="refresh_token" && data.optString(key).isBlank())) old.put(key,data.get(key))
        }
        store.save(old.toString());token=old.getString("access_token")
        return true
    }
    /** Session revoked: new sign-in with the saved account, otherwise sign-out. A network failure keeps the session for a later attempt. */
    private fun signInAgain() {
        val account=savedAccount()
        if(account==null) { logout();error(tr("Mammotion: session expired, sign in to your account again", "Mammotion : session expirée, reconnectez votre compte")) }
        try { login(account.email,account.password) }
        catch(e:java.io.IOException) { throw e }
        catch(e:Exception) {
            logout();throw IllegalStateException(tr("Mammotion: session expired and saved account refused, sign in again", "Mammotion : session expirée et compte enregistré refusé, reconnectez-vous"),e)
        }
    }
    fun userAccount():Int = store.read()?.let { JSONObject(it).optJSONObject("userInformation")?.optString("userAccount")?.toIntOrNull() } ?: 0
    fun connected()=token.isNotBlank()
    fun logout(){token="";store.clear()}
    fun iot():String {
        check(token.isNotBlank()) { tr("Sign in to your account", "Connectez votre compte") }
        val claims=JSONObject(String(Base64.decode(token.split('.')[1],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        val url=claims.optString("iot");trusted(url);return url.trimEnd('/')
    }
    /**
     * Account devices of the Mammotion IoT cloud (`/v1/user/device/page`, PyMammotion `get_user_device_page`), with what
     * the diagnostics log: IoT domain ([DevicePage.host], from the JWT), API code and `total`. This page does not list
     * the robots still bound to the older Aliyun cloud ([ownedDevices]).
     */
    fun devicePage():DevicePage {
        val url=iot()+DEVICE_PAGE
        val json=request(url,"POST",JSONObject().put("iotId","").put("pageNumber",1).put("pageSize",100))
        val data=json.getJSONObject("data")
        return DevicePage(URL(url).host,json.optInt("code",-1),if(data.has("total")) data.optInt("total") else null,data.getJSONArray("records"))
    }
    fun devices()=devicePage().records
    /**
     * Devices owned by the account, read-only (PyMammotion `get_user_device_list`: GET
     * `{MAMMOTION_API_DOMAIN}/device-server/v1/device/list`, same Bearer token, `Client-Id` and `Client-Type: 1`,
     * reply `data` = list of `DeviceInfo`). Unlike [devicePage], it also lists the robots bound to the older Aliyun
     * cloud. Shared devices are not returned.
     */
    fun ownedDevices():JSONObject=request("$API_DOMAIN$DEVICE_LIST","GET",headers=mapOf("Content-Type" to "application/json"))
    fun mqtt()=request(iot()+"/v1/mqtt/auth/jwt","POST").getJSONObject("data")
    /** Account mowing history (read-only, `work-report/page`). */
    fun workReports(deviceName:String,pageSize:Int=10)=request("$API_DOMAIN/device-server/v1/device/work-report/page","POST",
        JSONObject().put("deviceName",deviceName).put("pageNumber",1).put("pageSize",pageSize))
    /** Installed version and available update (read-only, never starts an update). */
    fun firmwareCheck(iotId:String)=request("$API_DOMAIN/device-server/v1/devices/version/check","POST",
        JSONObject().put("deviceIds",org.json.JSONArray().put(iotId)))
    /**
     * Agora token of the camera stream (PyMammotion `get_stream_subscription`): requesting it also triggers
     * publishing on recent firmware (`fpv_info`). No movement command.
     */
    fun streamToken(iotId:String,bothFront:Boolean=false)=request("$API_DOMAIN/device-server/v1/stream/token","POST",
        fr.luba.poc.camera.CameraCredentials.requestBody(iotId,bothFront))
    /**
     * Starts the firmware update offered by `devices/version/check` (PyMammotion `start_ota_upgrade`): on
     * explicit user request only, exact target version.
     */
    fun startFirmwareUpdate(iotId:String,version:String):JSONObject {
        require(iotId.isNotBlank() && version.isNotBlank()) { tr("Unknown target version", "Version cible inconnue") }
        return request("$API_DOMAIN/device-server/v1/ota/device/upgrade","POST",JSONObject().put("deviceId",iotId).put("version",version))
    }
    /** Wake-up from standby (state 48): the only way once the robot has left the broker. `true` = request accepted. */
    fun wakeUp(deviceName:String):Boolean =
        request("$API_DOMAIN/device-server/v1/device/wakeup","POST",JSONObject().put("deviceName",deviceName)).optBoolean("data",false)
    /**
     * [nativeReturn]: explicit "native return to base" choice of the user, see [CloudReadOnly]. [navReceiver]: navigation
     * receiver of the robot model, the one of the [fr.luba.poc.protocol.Commands] that built [payload].
     */
    fun invoke(payload:ByteArray,name:String,id:String,nativeReturn:Boolean=false,
               navReceiver:fr.luba.poc.proto.LubaMsgProto.MsgDevice=fr.luba.poc.proto.LubaMsgProto.MsgDevice.DEV_NAVIGATION):JSONObject {
        CloudReadOnly.requireAllowed(payload,nativeReturn,navReceiver)
        return request(iot()+"/v1/mqtt/rpc/thing/service/invoke","POST",JSONObject().put("args",JSONObject().put("content",Base64.encodeToString(payload,Base64.NO_WRAP))).put("deviceName",name).put("identifier","device_protobuf_sync_service").put("iotId",id).put("productKey",""))
    }
    /** Reply of [devicePage]: IoT domain (host name only), API `code`, `total` (null if absent) and the records. */
    data class DevicePage(val host:String,val apiCode:Int,val total:Int?,val records:org.json.JSONArray) {
        val fields:Map<String,Any?> get() = mapOf("host" to host,"endpoint" to DEVICE_PAGE,"httpCode" to 200,"apiCode" to apiCode,"total" to total,"records" to records.length())
    }
    companion object {
        const val DEVICE_PAGE="/v1/user/device/page"
        const val DEVICE_LIST="/device-server/v1/device/list"
        /** Mammotion application API (PyMammotion const.MAMMOTION_API_DOMAIN), same Bearer token. */
        private const val API_DOMAIN="https://domestic.mammotion.com"
        // Upstream client distributed with PyMammotion 0.10.8; no user account secret.
        private const val OAUTH_CLIENT="GxebgSt8si6pKqR"
        private const val OAUTH_SECRET="JP0508SRJFa0A90ADpzLINDBxMa4Vj"
    }
}
private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=read(buffer);if(n<0)break;check(out.size()+n<=limit){tr("Response too large", "Réponse trop grande")};out.write(buffer,0,n)};return out.toByteArray()}
