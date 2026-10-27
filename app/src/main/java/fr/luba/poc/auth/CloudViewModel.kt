package fr.luba.poc.auth
import android.app.Application
import android.os.Handler
import android.os.Looper
import fr.luba.poc.EventLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import android.util.Base64
import java.net.URI
import fr.luba.poc.i18n.tr

data class CloudRobot(val name:String,val id:String,val productKey:String)
/** Camera (debug): Agora credentials of the selected robot; [session] changes with each new token. */
enum class CameraCommand(val label:String){VIDEO_ON("vi_switch=1"),VIDEO_OFF("vi_switch=0"),REFRESH("refresh_fpv"),HEARTBEAT("ble_sync(3)")}
data class CameraCloudUi(val busy:Boolean=false,val status:String=tr("Camera stopped", "Caméra arrêtée"),val credentials:fr.luba.poc.camera.CameraCredentials?=null,val robot:CloudRobot?=null,val session:Long=0,val lastCommand:String="",
    /** Current token requested with the right camera (uid 2): without it, the robot only publishes the front camera. */
    val bothFront:Boolean=false)
data class CloudUi(val accountSaved:Boolean=false,val accountEmail:String="",val telemetry:fr.luba.poc.protocol.Telemetry=fr.luba.poc.protocol.Telemetry(),val selected:CloudRobot?=null,val lastKnown:LastKnownPosition?=null,val positionSaveError:Boolean=false,val connected:Boolean=false,val busy:Boolean=false,val status:String=tr("Account not connected", "Compte non connecté"),val robots:List<CloudRobot> = emptyList(),val map:RobotMap=RobotMap(),val events:List<String> = emptyList(),
    /** Details read through the account (read-only): state, maintenance, task, RTK, network, versions. */
    val details:RobotDetails=RobotDetails(),val workRecords:List<WorkRecord> = emptyList(),val firmwareCheck:FirmwareCheck?=null,val accountReadAt:Long?=null,val accountReadErrors:List<String> = emptyList(),
    val camera:CameraCloudUi=CameraCloudUi(),
    /** Native return to base requested through the account (without BLE) and firmware update started from the app. */
    val nativeReturn:CloudReturnUi=CloudReturnUi(),val firmwareUpdate:FirmwareUpdateUi=FirmwareUpdateUi())
/** Failure message meant for the user (displayed as is). */
class CloudActionException(message:String):Exception(message)
/**
 * [api], [accountStore] and [mqttConnector] (account MQTT connection, Paho by default) are replaced only by the JVM
 * tests: cloud simulated on a local port and fabricated MQTT events.
 */
class CloudViewModel internal constructor(app:Application,private val api:MammotionCloud,private val accountStore:AccountStore,
    /** Local mode ([AccountMode]): no request reaches the Mammotion cloud. */
    private val localOnly:()->Boolean={ AccountMode.localOnly },
    private val mqttConnector:((CloudRobot,Boolean)->MqttLink)?=null):AndroidViewModel(app) {
    constructor(app:Application):this(app,MammotionCloud(SessionStore(app)),AccountStore(app))
    private val restoredAccount=accountStore.read()
    init { api.savedAccount=accountStore::read }
    private val positionStore=LastKnownPositionStore(app.filesDir)
    private val cache=MapCache(app)
    private val preferences=app.getSharedPreferences("mammotion_map_selection",android.content.Context.MODE_PRIVATE)
    private val restoredSession=!localOnly() && api.connected()
    private val mutable=MutableStateFlow(CloudUi(accountSaved=restoredAccount!=null,accountEmail=restoredAccount?.email.orEmpty(),lastKnown=if(localOnly()) null else preferences.getString("robot",null)?.let(positionStore::read),connected=restoredSession,map=if(restoredSession) preferences.getString("robot",null)?.let{cache.read(it)} ?: RobotMap() else RobotMap(),status=when { localOnly() -> AccountMode.message; restoredSession -> tr("Secure session kept · refresh the robots", "Session sécurisée conservée · actualiser les robots"); else -> tr("Account not connected", "Compte non connecté") }))
    val state=mutable.asStateFlow()
    private val handler=Handler(Looper.getMainLooper())
    private val log=EventLog(app) { message -> handler.post { mutable.value=mutable.value.copy(status=message) } }
    private var job:Job?=null
    private fun status(s:String){handler.post { if(log.healthy) log.append("cloud_status",s) };mutable.value=mutable.value.copy(status=s,events=(mutable.value.events+s).takeLast(100))}
    private fun work(action:suspend ()->Unit) {
        if(localOnly()) { status(AccountMode.message);return }
        if(mutable.value.busy)return
        // A tracking (return, update) already holds the account MQTT connection and relays the state.
        if(following())return
        job=viewModelScope.launch {mutable.value=mutable.value.copy(busy=true)
            try {withContext(Dispatchers.IO){action()}} catch(e:CancellationException){throw e} catch(e:Exception){status(tr("Cloud failure: ", "Échec cloud : ")+when(e){is MqttException -> "MQTT code ${e.reasonCode}";is java.net.UnknownHostException,is java.net.ConnectException,is java.net.SocketTimeoutException,is java.net.NoRouteToHostException -> tr("network unavailable (${e.javaClass.simpleName})", "réseau indisponible (${e.javaClass.simpleName})");else -> e.message?.takeIf { it.startsWith("Mammotion") || it in listOf(tr("No session", "Session absente"),tr("Sign in to your account", "Connectez votre compte"),tr("HTTPS Mammotion domain required", "Domaine Mammotion HTTPS requis"),tr("Mammotion TLS broker required", "Broker TLS Mammotion requis"),tr("MQTT disconnected", "MQTT déconnecté"),tr("Cloud queue full", "File cloud saturée"),tr("Sign in to your Mammotion account (Me tab)", "Connectez votre compte Mammotion (onglet Moi)")) } ?: e.javaClass.simpleName})}
            finally {mutable.value=mutable.value.copy(busy=false,connected=api.connected())}
        }
    }
    private var cameraJob:Job?=null
    private fun camera(update:(CameraCloudUi)->CameraCloudUi){mutable.value=mutable.value.copy(camera=update(mutable.value.camera))}
    private fun cameraLog(text:String,fields:Map<String,Any?> = emptyMap()){handler.post { if(log.healthy) log.append("camera",text,fields) }}
    private var cameraAttempt=0L
    /** Blocking camera network calls: outside [viewModelScope] so the screen is released at the timeout even if the socket lingers. */
    private val cameraIo=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private class CameraTimeout(message:String):Exception(message)
    /** Step of the video request, displayed and logged, limited to [timeoutMs]. */
    private suspend fun <T> cameraStep(attempt:Long,label:String,timeoutMs:Long,block:()->T):T {
        if(attempt==cameraAttempt) camera { it.copy(status=label) }
        cameraLog(tr("Camera: $label", "Caméra : $label"))
        val call=cameraIo.async { block() }
        return try { withTimeout(timeoutMs) { call.await() } }
        catch(e:TimeoutCancellationException) { call.cancel(); throw CameraTimeout(tr("$label: no response within ${timeoutMs/1000} s", "$label : pas de réponse en ${timeoutMs/1000} s")) }
    }
    /**
     * Camera stream token for the selected account robot. On recent firmware, the token request triggers
     * publishing; an empty response is requested once more (like PyMammotion). Each step is displayed and
     * time-limited: the request never stays stuck without a message.
     */
    fun requestCamera(bothFront:Boolean=false) {
        if(localOnly()) { camera { it.copy(busy=false,credentials=null,status=tr("Video stream unavailable: ${AccountMode.message}", "Flux vidéo indisponible : ${AccountMode.message}")) };return }
        if(mutable.value.camera.busy) return
        val attempt=++cameraAttempt
        cameraJob=viewModelScope.launch {
            camera { it.copy(busy=true,credentials=null,status=tr("Requesting the video stream from the cloud…", "Demande du flux vidéo au cloud…")) }
            try {
                check(api.connected()) { tr("Sign in to your Mammotion account (Me tab)", "Connectez votre compte Mammotion (onglet Moi)") }
                if(mutable.value.robots.isEmpty()) cameraStep(attempt,tr("1/2 · account robot list", "1/2 · liste des robots du compte"),CAMERA_LIST_TIMEOUT_MS) { load() }
                val robot=mutable.value.selected ?: error(tr("No LUBA in the account", "Aucun LUBA dans le compte"))
                camera { it.copy(robot=robot) }
                val label=tr("2/2 · video token (the cloud contacts ${robot.name})", "2/2 · jeton vidéo (le cloud contacte ${robot.name})")
                val credentials=try {
                    fr.luba.poc.camera.CameraCredentials.parse(cameraStep(attempt,label,CAMERA_TOKEN_TIMEOUT_MS) { api.streamToken(robot.id,bothFront) })
                } catch(e:IllegalStateException) {
                    // Response without credentials only: no retry after a timeout, a network error or a cloud code.
                    if(e is CloudResponseException) throw e
                    delay(1000)
                    fr.luba.poc.camera.CameraCredentials.parse(cameraStep(attempt,tr("$label · 2nd attempt", "$label · 2e essai"),CAMERA_TOKEN_TIMEOUT_MS) { api.streamToken(robot.id,bothFront) })
                }
                camera { it.copy(credentials=credentials,session=it.session+1,bothFront=bothFront,status=tr("Token received · loading the video player…", "Jeton reçu · chargement du lecteur vidéo…")) }
                cameraLog(tr("Camera: token received", "Caméra : jeton reçu"),mapOf("summary" to credentials.summary,"bothFront" to bothFront))
            } catch(e:CancellationException){throw e} catch(e:Exception) {
                val reason=fr.luba.poc.camera.CameraCredentials.failureReason(e,(e as? CloudResponseException)?.code)
                camera { it.copy(credentials=null,status=tr("Video stream failed: $reason", "Échec du flux vidéo : $reason")) }
                cameraLog(tr("Camera: token failed", "Caméra : échec du jeton"),mapOf("reason" to reason,"error" to e.javaClass.simpleName))
            } finally { if(attempt==cameraAttempt) camera { it.copy(busy=false) } }
        }
    }
    fun cameraStatus(text:String){camera { it.copy(status=text) }}
    fun stopCamera(){cameraAttempt++;cameraJob?.cancel();camera { it.copy(busy=false,credentials=null,status=tr("Camera stopped", "Caméra arrêtée")) }}
    /**
     * Video command (vi_switch, refresh_fpv) or `ble_sync(3)` heartbeat through the cloud, when no robot link is
     * ready: the [CloudReadOnly] barrier only accepts these messages.
     */
    fun cloudCameraCommand(kind:CameraCommand) {
        if(localOnly()) return
        val robot=mutable.value.camera.robot ?: mutable.value.selected ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result=runCatching {
                val commands=Commands(api.userAccount())
                api.invoke(when(kind){CameraCommand.VIDEO_ON -> commands.video(true);CameraCommand.VIDEO_OFF -> commands.video(false);CameraCommand.REFRESH -> commands.refreshFpv();CameraCommand.HEARTBEAT -> commands.sync(syncType=3)},robot.name,robot.id)
            }
            camera { it.copy(lastCommand=tr("${kind.label} via the cloud: ", "${kind.label} par le cloud : ")+if(result.isSuccess) tr("sent", "envoyé") else failure(result.exceptionOrNull()!!)) }
            if(result.isFailure && kind!=CameraCommand.REFRESH) cameraLog(tr("Camera: ${kind.label} refused by the cloud", "Caméra : ${kind.label} refusé par le cloud"),mapOf("reason" to failure(result.exceptionOrNull()!!)))
        }
    }
    fun cameraCommandSent(text:String){camera { it.copy(lastCommand=text) }}
    fun savedAccount():SavedAccount? = accountStore.read()
    fun login(email:String,password:String,rememberAccount:Boolean=true)=work {
        status(tr("Signing in to the account…", "Connexion au compte…"))
        api.login(email,password)
        if(rememberAccount) accountStore.save(SavedAccount(email,password)) else accountStore.clear()
        mutable.value=mutable.value.copy(accountSaved=rememberAccount,accountEmail=email.trim())
        status(if(rememberAccount) tr("Account saved encrypted; fetching the robots…", "Compte sauvegardé de façon chiffrée ; récupération des robots…") else tr("Account connected; fetching the robots…", "Compte connecté ; récupération des robots…"))
        load()
    }
    fun refresh()=work {load()}
    private fun load(){val a=api.devices();val found=CloudRobotFilter.split((0 until a.length()).map{a.getJSONObject(it)}.map { CloudRobot(it.getString("deviceName"),it.getString("iotId"),it.getString("productKey")) });val robots=found.robots
        // Devices left out, anonymous (product key and short name prefix) for the diagnostics export: DumbyLuba#3.
        found.skipped.forEach { event("cloud_device_skipped",tr("Account device not recognized as a mower", "Appareil du compte non reconnu comme tondeuse"),it.fields) }
        val selected=robots.firstOrNull { it.id == preferences.getString("robot",null) } ?: robots.firstOrNull()
        mutable.value=mutable.value.copy(robots=robots,selected=selected,telemetry=fr.luba.poc.protocol.Telemetry(),details=if(selected?.id==mutable.value.selected?.id) mutable.value.details else RobotDetails(),connected=true,map=selected?.let{cache.read(it.id)} ?: RobotMap(),lastKnown=selected?.let{positionStore.read(it.id)});status(found.status)}
    fun logout(){job?.cancel();api.logout();preferences.edit().clear().apply();val saved=accountStore.read();mutable.value=CloudUi(accountSaved=saved!=null,accountEmail=saved?.email.orEmpty());status(tr("Session deleted", "Session supprimée"))}
    /**
     * Entering local mode: camera stopped and session deleted (the saved credentials are kept for a later return to
     * the account). Refused while a native return or a firmware update is followed: false.
     */
    fun enterLocalMode():Boolean {
        if(following()) return false
        stopCamera();logout();status(AccountMode.message)
        return true
    }
    /** Leaving local mode: the account can be signed in again (Me → Mammotion account). */
    fun leaveLocalMode(){status(tr("Account not connected", "Compte non connecté"))}
    fun forgetAccount(){accountStore.clear();logout();status(tr("Saved account and session deleted", "Compte et session sauvegardés supprimés"))}
    fun refreshPosition()=work {
        if (mutable.value.robots.isEmpty()) load()
        val robot=mutable.value.selected ?: return@work
        read(robot,false)
    }
    fun fetch(robot:CloudRobot)=work { read(robot,true) }
    /**
     * Full read-only account reading: mowing history and firmware (HTTP), then robot state, maintenance and
     * RTK base (MQTT protobuf reports).
     */
    fun refreshAccount()=work {
        if (mutable.value.robots.isEmpty()) load()
        val robot=mutable.value.selected ?: return@work
        val errors=mutableListOf<String>()
        status(tr("Reading the account · ${robot.name}", "Lecture du compte · ${robot.name}"))
        runCatching { AccountJson.workRecords(api.workReports(robot.name)) }
            .onSuccess { mutable.value=mutable.value.copy(workRecords=it) }
            .onFailure { if(it is CancellationException) throw it; errors+=tr("History: ${failure(it)}", "Historique : ${failure(it)}") }
        runCatching { AccountJson.firmware(api.firmwareCheck(robot.id),robot.id) }
            .onSuccess { mutable.value=mutable.value.copy(firmwareCheck=it) }
            .onFailure { if(it is CancellationException) throw it; errors+=tr("Firmware: ${failure(it)}", "Firmware : ${failure(it)}") }
        mutable.value=mutable.value.copy(accountReadErrors=errors,accountReadAt=System.currentTimeMillis())
        read(robot,false)
    }
    private fun failure(e:Throwable)=when(e){is CloudResponseException -> "code ${e.code}";is MqttException -> "MQTT code ${e.reasonCode}";is CloudActionException -> e.message.orEmpty();else -> e.message?.takeIf { it.startsWith("Mammotion") || it.startsWith("MQTT") || it.startsWith("Broker") } ?: e.javaClass.simpleName}
    private suspend fun read(robot:CloudRobot,readMap:Boolean) {
        preferences.edit().putString("robot",robot.id).apply()
        val sameRobot=mutable.value.selected?.id==robot.id
        mutable.value=mutable.value.copy(selected=robot,telemetry=if(sameRobot) mutable.value.telemetry else fr.luba.poc.protocol.Telemetry(),details=if(sameRobot) mutable.value.details else RobotDetails(),workRecords=if(sameRobot) mutable.value.workRecords else emptyList(),firmwareCheck=if(sameRobot) mutable.value.firmwareCheck else null,map=if(readMap) cache.read(robot.id) ?: RobotMap() else mutable.value.map,lastKnown=positionStore.read(robot.id),positionSaveError=false)
        val absorber=Absorber(robot)
        status(tr("Secure MQTT connection · ${robot.name}", "Connexion MQTT sécurisée · ${robot.name}"))
        var requestedAt=Long.MAX_VALUE
        val link=connectMqtt(robot)
        try {
            val commands=Commands(api.userAccount());val transfer=MapTransfer(3, cache.read(robot.id)?.station, requestTimeoutMs=10_000, frameTimeoutMs=8_000, maxAttempts=3)
            var completedAt: Long? = null
            if(readMap) mutable.value=mutable.value.copy(map=RobotMap(source=tr("Cloud read of ${robot.name}…", "Lecture cloud de ${robot.name}…")))
            fun send(bytes:ByteArray){api.invoke(bytes,robot.name,robot.id)}
            send(commands.sync(syncType=3));send(commands.reports(false,account=true));if(readMap)send(commands.mapHashes())
            requestedAt=System.currentTimeMillis()
            fun notices() { transfer.notices().forEach { n -> handler.post { if(log.healthy) log.append(n.kind,n.detail,n.fields) } } }
            withTimeout(if(readMap) CLOUD_MAP_TIMEOUT_MS else 15000L) {
                var nextKeep=System.currentTimeMillis()+5000
                while(true) {
                    // Map reading: 1 s steps so that an element that stops answering is asked again (MapTransfer.tick).
                    val event=withTimeoutOrNull(if(readMap) 1000L else 5000L){link.events.receive()}
                    if(readMap && completedAt==null) {
                        transfer.tick(commands).forEach(::send); notices()
                        if(transfer.finished && !transfer.snapshot().complete) {
                            val map=transfer.snapshot().copy(source="Cloud ${robot.name} · ${transfer.snapshot().source}")
                            mutable.value=mutable.value.copy(map=map)
                            status(transfer.failure?.let { tr("Cloud map not read: $it", "Carte cloud non lue : $it") }
                                ?: tr("Cloud map incomplete: ${transfer.missing} element(s) not read (${map.source}), planning disabled", "Carte cloud incomplète : ${transfer.missing} élément(s) non lu(s) (${map.source}), calcul désactivé"))
                            break
                        }
                    }
                    if(event==null){
                        if(completedAt != null && System.currentTimeMillis()-completedAt!! >= 10000) break
                        if(System.currentTimeMillis()>=nextKeep){send(commands.sync(syncType=3));send(commands.reports(true,account=true));nextKeep=System.currentTimeMillis()+5000}
                        continue
                    }
                    val bytes=(event as? CloudEvent.Proto)?.bytes ?: continue
                    val message=LubaMsg.parseFrom(bytes)
                    absorber.accept(message)
                    handler.post { log.append("cloud_map_rx", tr("Cloud protobuf received", "Protobuf cloud reçu"), mapOf("bytes" to bytes.size,"msgType" to message.msgtypeValue,"navFields" to (if(message.hasNav()) message.nav.allFields.keys.map{it.name} else emptyList<String>()),"mapNav" to (if(message.hasNav() && (message.nav.hasToappGethashAck() || message.nav.hasToappGetCommondataAck())) message.nav.toString() else null),
                        // Drawing frames: identifiers only, never the SVG content.
                        "svg" to (if(message.hasNav() && message.nav.hasToappSvgMsg()) message.nav.toappSvgMsg.let { mapOf("dataHash" to it.dataHash,"paternalHashA" to it.paternalHashA,"frame" to it.currentFrame,"totalFrames" to it.totalFrame,"subCmd" to it.subCmd,"type" to it.type,"result" to it.result) } else null))) }
                    if(!readMap) {
                        // Reading: recent position and device report (battery, charging contact).
                        if(positionSince(absorber.telemetry.value,requestedAt) && deviceSince(absorber.telemetry.value,requestedAt)) {
                            status(tr("API position and battery received · ${robot.name}", "Position et batterie API reçues · ${robot.name}"))
                            break
                        }
                        continue
                    }
                    val replies=transfer.accept(message,commands); replies.forEach(::send); notices()
                    if(replies.isEmpty() && completedAt==null) continue
                    val map=transfer.snapshot().copy(source="Cloud ${robot.name} · ${transfer.snapshot().source}")
                    mutable.value=mutable.value.copy(map=map)
                    status(tr("Map: ${transfer.read}/${transfer.announced ?: "?"} elements read", "Carte : ${transfer.read}/${transfer.announced ?: "?"} éléments lus"))
                    if(map.complete){cache.save(robot.id,map);preferences.edit().putString("robot",robot.id).apply();status(if(map.station?.entryHeadingDegrees != null) tr("Cloud map and base received", "Carte et base cloud reçues") else tr("Map received · base axis waiting for the robot to charge", "Carte reçue · axe de base en attente du robot en recharge"));if(completedAt==null) completedAt=System.currentTimeMillis();if(map.station?.entryHeadingDegrees != null || System.currentTimeMillis()-completedAt!! >= 10000) break}
                }
            }
        } catch(e:TimeoutCancellationException) {status(if(readMap) tr("Cloud map incomplete: ${CLOUD_MAP_TIMEOUT_MS / 1000} s timeout, planning disabled", "Carte cloud incomplète : timeout ${CLOUD_MAP_TIMEOUT_MS / 1000} s, calcul désactivé") else when {
            deviceSince(absorber.telemetry.value,requestedAt) -> tr("API battery received · position not received within 15 s, last position kept", "Batterie API reçue · position non reçue en 15 s, dernière position conservée")
            positionSince(absorber.telemetry.value,requestedAt) -> tr("API position received · battery not received within 15 s", "Position API reçue · batterie non reçue en 15 s")
            else -> tr("API position not received within 15 s · last position kept", "Position API non reçue en 15 s · dernière position conservée")
        })}
        finally {link.close()}
    }
    private fun positionSince(t:fr.luba.poc.protocol.Telemetry,at:Long)=(t.lastPositionAt ?: 0)>=at && t.x?.isFinite()==true && t.y?.isFinite()==true
    private fun deviceSince(t:fr.luba.poc.protocol.Telemetry,at:Long)=(t.lastDeviceAt ?: 0)>=at
    private companion object {
        const val CAMERA_LIST_TIMEOUT_MS=25_000L
        /** Bound of a cloud map reading (each element has its own retries, MapTransfer). */
        const val CLOUD_MAP_TIMEOUT_MS=240_000L
        /** The cloud waits for the robot's answer (50504 if it does not answer): a bit more than the HTTP read timeout. */
        const val CAMERA_TOKEN_TIMEOUT_MS=30_000L
    }
    /** Account MQTT event: robot protobuf or posted property (JSON, e.g. `otaProgress`). */
    internal sealed interface CloudEvent { class Proto(val bytes:ByteArray):CloudEvent; class Property(val json:String):CloudEvent }
    internal class MqttLink(val events:Channel<CloudEvent>,private val disconnect:()->Unit={}):AutoCloseable {
        override fun close(){events.close();disconnect()}
    }
    private fun connectMqtt(robot:CloudRobot,properties:Boolean=false):MqttLink = mqttConnector?.invoke(robot,properties) ?: connectPaho(robot,properties)
    /** TLS MQTT connection to the Mammotion broker, subscribed to the robot events; [properties] also relays posted properties. */
    private fun connectPaho(robot:CloudRobot,properties:Boolean):MqttLink {
        val creds=api.mqtt();val host=creds.getString("host");val uri=URI(if(host.contains("://"))host else "mqtts://$host")
        require(uri.scheme in listOf("mqtts","ssl") && uri.host.endsWith(".mammotion.com")) { tr("Mammotion TLS broker required", "Broker TLS Mammotion requis") }
        val client=MqttClient("ssl://${uri.host}:${if(uri.port>0)uri.port else 8883}",creds.getString("clientId"),MemoryPersistence())
        val events=Channel<CloudEvent>(64)
        val link=MqttLink(events) { try{if(client.isConnected)client.disconnect(1000)}catch(_:Exception){};try{client.close()}catch(_:Exception){} }
        client.setCallback(object:MqttCallback {
            override fun connectionLost(cause:Throwable?){events.close(IllegalStateException(tr("MQTT disconnected", "MQTT déconnecté")))}
            override fun deliveryComplete(token:IMqttDeliveryToken?){}
            override fun messageArrived(topic:String?,msg:MqttMessage?) {
                if(msg==null || msg.payload.size>1048576 || topic==null)return
                if(properties && topic.endsWith("/thing/event/property/post")) { events.trySend(CloudEvent.Property(String(msg.payload)));return }
                if(!topic.contains("device_protobuf_msg_event"))return
                try {val p=JSONObject(String(msg.payload)).getJSONObject("params");val content=p.optJSONObject("value")?.optString("content").orEmpty().ifEmpty{p.optString("content")};if(content.isEmpty())return
                    val bytes=Base64.decode(content,Base64.DEFAULT);if(!events.trySend(CloudEvent.Proto(bytes)).isSuccess)events.close(IllegalStateException(tr("Cloud queue full", "File cloud saturée")))
                }catch(_:Exception){}
            }
        })
        try {
            val options=MqttConnectOptions().apply{userName=creds.getString("username");password=creds.getString("jwt").toCharArray();isAutomaticReconnect=false;isCleanSession=true;connectionTimeout=15;keepAliveInterval=30;isHttpsHostnameVerificationEnabled=true}
            client.connect(options)
            val pk=robot.productKey;val name=robot.name
            client.subscribe(arrayOf("/sys/$pk/$name/thing/event/+/post","/sys/proto/$pk/$name/thing/event/+/post"),intArrayOf(0,0))
        } catch(e:Exception) { link.close();throw e }
        return link
    }
    /** Protobuf reports received through the account: robot errors, details, position and battery (same rules as the reading). */
    private inner class Absorber(private val robot:CloudRobot) {
        val telemetry=fr.luba.poc.protocol.TelemetryReducer()
        val details=RobotDetailsReducer(mutable.value.details)
        private val robotErrors=fr.luba.poc.protocol.RobotErrorMonitor()
        fun accept(message:LubaMsg) {
            // Cloud reading without movement command: each code is decoded and logged, faults are displayed.
            robotErrors.accept(message).forEach { e ->
                val info=fr.luba.poc.protocol.RobotErrorCatalog.describe(e.code)
                handler.post { log.append("robot_error", tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"), mapOf("code" to e.code,"source" to e.source,"robotErrorTime" to e.timestamp,"category" to info.category.name,"response" to info.response.name)) }
                if(info.response!=fr.luba.poc.protocol.RobotErrorResponse.LOG) status(tr("Robot: ${info.summary} — ${info.advice}", "Robot : ${info.summary} — ${info.advice}"))
            }
            if(details.accept(message,System.currentTimeMillis())) mutable.value=mutable.value.copy(details=details.value)
            if(telemetry.accept(message,System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime())) {
                val known=(mutable.value.lastKnown ?: LastKnownPosition(robot.id,robot.name)).accept(telemetry.value)
                val saved=runCatching {positionStore.save(known)}.isSuccess
                mutable.value=mutable.value.copy(telemetry=telemetry.value,lastKnown=known,positionSaveError=!saved)
            }
        }
        fun accept(event:CloudEvent?):LubaMsg? = (event as? CloudEvent.Proto)?.let { runCatching { LubaMsg.parseFrom(it.bytes) }.getOrNull() }?.also(::accept)
    }

    private var followJob:Job?=null
    @Volatile private var returnTracker:CloudReturnTracker?=null
    private fun following()=mutable.value.nativeReturn.active || mutable.value.firmwareUpdate.active
    private fun setReturn(ui:CloudReturnUi){mutable.value=mutable.value.copy(nativeReturn=ui)}
    private fun setFirmware(ui:FirmwareUpdateUi){mutable.value=mutable.value.copy(firmwareUpdate=ui)}
    private fun event(kind:String,text:String,fields:Map<String,Any?> = emptyMap()){handler.post { if(log.healthy) log.append(kind,text,fields) }}
    private fun now()=System.currentTimeMillis()

    /**
     * Native return to base through the Mammotion account, without a BLE link, on the user's explicit choice in
     * "Charging station". The route and docking are the firmware's; tracking reads the robot state (14 = return)
     * until the charging contact. Robot in standby: wake-up requested from the cloud first.
     */
    fun nativeReturnToBase() {
        if(following()) return
        if(localOnly()) { setReturn(CloudReturnUi(CloudReturnPhase.FAILED,AccountMode.message,now()));return }
        val robot=mutable.value.selected ?: run { setReturn(CloudReturnUi(CloudReturnPhase.FAILED,tr("No LUBA selected in the account", "Aucun LUBA sélectionné dans le compte"),now()));return }
        // Read-only mode: a model that is not validated never receives a motion order, native return included.
        fr.luba.poc.protocol.ModelSupport.refusal(robot.name)?.let { setReturn(CloudReturnUi(CloudReturnPhase.FAILED,it,now()));return }
        if(!api.connected()) { setReturn(CloudReturnUi(CloudReturnPhase.FAILED,tr("Sign in to your Mammotion account (Me tab)", "Connectez votre compte Mammotion (onglet Moi)"),now()));return }
        setReturn(CloudReturnUi(CloudReturnPhase.CONNECTING,tr("Connecting to the robot through the account…", "Connexion au robot par le compte…"),now()))
        followJob=viewModelScope.launch(Dispatchers.IO) {
            try { job?.cancelAndJoin();followReturn(robot) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                val tracker=returnTracker
                setReturn(if(tracker==null) CloudReturnUi(CloudReturnPhase.FAILED,tr("Native return refused: ${failure(e)}", "Retour natif refusé : ${failure(e)}"),now())
                    else CloudReturnUi(CloudReturnPhase.FAILED,tr("Return tracking lost (${failure(e)}): the robot may continue, check it", "Suivi du retour perdu (${failure(e)}) : le robot peut poursuivre, vérifier"),now()))
                event("native_return_error",tr("Native return through the account: ${failure(e)}", "Retour natif par le compte : ${failure(e)}"),mapOf("requested" to (tracker!=null),"via" to "cloud"))
            } finally { returnTracker=null;mutable.value=mutable.value.copy(connected=api.connected()) }
        }
    }
    /** Cancels the native return (action 12): a stop command, always sent. */
    fun cancelNativeReturn() {
        val robot=mutable.value.selected ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result=runCatching { api.invoke(Commands(api.userAccount()).cancelReturnToDock(),robot.name,robot.id) }
            result.onSuccess {
                event("native_return_cancel",tr("Native return cancellation sent through the account", "Annulation du retour natif envoyée par le compte"),mapOf("via" to "cloud"))
                returnTracker?.let { it.cancelRequested(now());setReturn(it.value) }
                    ?: setReturn(CloudReturnUi(CloudReturnPhase.CANCELLED,tr("Return cancellation sent through the account", "Annulation du retour envoyée par le compte"),now()))
            }.onFailure { e -> if(e is CancellationException) throw e
                setReturn(mutable.value.nativeReturn.copy(status=tr("Cancellation refused by the cloud (${failure(e)}): use the Mammotion app or the robot STOP button", "Annulation refusée par le cloud (${failure(e)}) : utiliser l’app Mammotion ou le STOP du robot"))) }
        }
    }
    private suspend fun followReturn(robot:CloudRobot) {
        val commands=Commands(api.userAccount())
        fun send(bytes:ByteArray,native:Boolean=false){api.invoke(bytes,robot.name,robot.id,native)}
        val absorber=Absorber(robot)
        var reconnects=0
        while(true) {
            val link=try { connectMqtt(robot) } catch(e:Exception) {
                if(returnTracker==null || ++reconnects>5) throw e
                delay(3000);continue
            }
            try {
                send(commands.sync(syncType=3));send(commands.reports(false,account=true))
                var tracker=returnTracker
                if(tracker==null) {
                    // 1. Recent robot state; silent for 15 s → wake-up (standby), then 60 s at most.
                    val since=now()
                    var woke=false;var waitFrom=since
                    while((absorber.details.value.statusAt ?: 0)<since) {
                        val e=withTimeoutOrNull(5000){link.events.receive()}
                        if(e!=null){absorber.accept(e);continue}
                        val t=now()
                        if(!woke && t-waitFrom>15000) {
                            setReturn(CloudReturnUi(CloudReturnPhase.WAKING,tr("Robot silent: wake-up requested through the account…", "Robot muet : réveil demandé par le compte…"),t))
                            event("cloud_wakeup",tr("Robot wake-up requested through the account before the native return", "Réveil du robot demandé par le compte avant le retour natif"))
                            if(!api.wakeUp(robot.name)) throw CloudActionException(tr("robot unreachable, wake-up refused by the cloud", "robot injoignable, réveil refusé par le cloud"))
                            woke=true;waitFrom=t
                        } else if(woke && t-waitFrom>60000) throw CloudActionException(tr("robot unreachable through the account (offline or in standby)", "robot injoignable par le compte (hors ligne ou en veille)"))
                        send(commands.sync(syncType=3));send(commands.reports(true,account=true))
                    }
                    val d=absorber.details.value
                    if(d.chargeState in CloudReturnTracker.CHARGING) { setReturn(CloudReturnUi(CloudReturnPhase.DOCKED,tr("Already on the base: charging observed", "Déjà sur la base : charge observée"),now()));return }
                    if(d.sysStatus==FirmwareUpdateTracker.UPGRADING) throw CloudActionException(tr("robot update in progress", "mise à jour du robot en cours"))
                    val already=d.sysStatus==CloudReturnTracker.RETURNING_STATE
                    if(!already) send(commands.returnToDock(),native=true)
                    tracker=CloudReturnTracker(now(),already)
                    returnTracker=tracker
                    setReturn(tracker.value)
                    event("native_return_request",tr("Return to base by the Mammotion firmware, requested through the account (user choice)", "Retour à la base par le firmware Mammotion, demandé par le compte (choix utilisateur)"),
                        mapOf("robotState" to d.sysStatus,"battery" to d.battery,"alreadyReturning" to already,"via" to "cloud"))
                }
                // 2. Tracking until the charging contact; report subscription kept alive (expires after 10 s).
                var lastStatusAt=absorber.details.value.statusAt ?: 0
                var lastKeep=now()
                while(!tracker.done) {
                    val e=withTimeoutOrNull(5000){link.events.receive()}
                    absorber.accept(e)
                    val t=now()
                    val st=absorber.details.value
                    if((st.statusAt ?: 0)>lastStatusAt) { lastStatusAt=st.statusAt!!;tracker.accept(st.sysStatus,st.chargeState,lastStatusAt,t) }
                    tracker.tick(t)
                    setReturn(tracker.value)
                    if(e==null || t-lastKeep>=8000) { lastKeep=t;send(commands.sync(syncType=3));send(commands.reports(true,account=true)) }
                }
                event("native_return_end",tracker.value.status,mapOf("phase" to tracker.value.phase.name,"via" to "cloud"))
                return
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) {
                // Connection lost during tracking: reconnect without resending the command.
                if(returnTracker==null || e is CloudActionException || e is CloudResponseException || ++reconnects>5) throw e
                setReturn(returnTracker!!.value.copy(status=tr("Account connection lost · reconnecting…", "Connexion au compte perdue · reconnexion…")))
                delay(3000)
            } finally { link.close() }
        }
    }

    /**
     * Starts the firmware update offered by the account, on explicit request, then follows its progress:
     * `otaProgress` MQTT property, `toapp_upgrade_report` protobuf report, state 16/22/23 and HTTP re-read of
     * `devices/version/check` every 15 s.
     */
    fun startFirmwareUpdate() {
        if(following()) return
        if(localOnly()) { setFirmware(FirmwareUpdateUi(status=tr("Update not started: ${AccountMode.message}", "Mise à jour non lancée : ${AccountMode.message}")));return }
        val robot=mutable.value.selected ?: return
        val check=mutable.value.firmwareCheck
        // Read-only mode: the firmware of a model that is not validated is updated from the Mammotion app only.
        fr.luba.poc.protocol.ModelSupport.refusal(robot.name)?.let { setFirmware(FirmwareUpdateUi(status=tr("Update not started: $it", "Mise à jour non lancée : $it")));return }
        FirmwareUpdatePolicy.refusal(check,mutable.value.details)?.let { setFirmware(FirmwareUpdateUi(status=tr("Update not started: $it", "Mise à jour non lancée : $it")));return }
        val target=check!!.latest!!
        val started=now()
        setFirmware(FirmwareUpdateUi(FirmwarePhase.REQUESTED,target,null,"",tr("Sending the update request…", "Envoi de la demande de mise à jour…"),started,started))
        followJob=viewModelScope.launch(Dispatchers.IO) {
            var sent=false
            try {
                job?.cancelAndJoin()
                api.startFirmwareUpdate(robot.id,target)
                sent=true
                event("firmware_update_request",tr("Firmware update started from the app", "Mise à jour firmware lancée depuis l'app"),mapOf("from" to check.current,"to" to target))
                followFirmware(robot,FirmwareUpdateTracker(target,started))
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) {
                setFirmware(mutable.value.firmwareUpdate.copy(phase=if(sent) FirmwarePhase.LOST else FirmwarePhase.FAILED,
                    status=if(sent) tr("Tracking lost (${failure(e)}): the update continues on the robot", "Suivi perdu (${failure(e)}) : la mise à jour continue sur le robot") else tr("Update refused by the cloud: ${failure(e)}", "Mise à jour refusée par le cloud : ${failure(e)}"),updatedAt=now()))
                event("firmware_update_error",tr("Firmware update: ${failure(e)}", "Mise à jour firmware : ${failure(e)}"),mapOf("sent" to sent))
            } finally { mutable.value=mutable.value.copy(connected=api.connected()) }
        }
    }
    /** Follows an update already in progress (started from the Mammotion app or before an app restart). */
    fun followFirmwareUpdate() {
        if(localOnly()) return
        val check=mutable.value.firmwareCheck
        if(following() || check?.upgrading!=true) return
        val robot=mutable.value.selected ?: return
        val tracker=FirmwareUpdateTracker(check.latest,now(),alreadyRunning=true)
        setFirmware(tracker.value)
        followJob=viewModelScope.launch(Dispatchers.IO) {
            try { job?.cancelAndJoin();followFirmware(robot,tracker) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { setFirmware(mutable.value.firmwareUpdate.copy(phase=FirmwarePhase.LOST,status=tr("Tracking lost (${failure(e)}): the update continues on the robot", "Suivi perdu (${failure(e)}) : la mise à jour continue sur le robot"),updatedAt=now())) }
        }
    }
    /** Only stops the tracking: the update continues on the robot. */
    fun stopFirmwareFollow() {
        if(!mutable.value.firmwareUpdate.active) return
        followJob?.cancel()
        setFirmware(mutable.value.firmwareUpdate.copy(phase=FirmwarePhase.IDLE,status=tr("Tracking stopped; the update continues on the robot", "Suivi arrêté ; la mise à jour continue sur le robot"),updatedAt=now()))
    }
    private fun pollFirmware(robot:CloudRobot,tracker:FirmwareUpdateTracker,t:Long) {
        runCatching { AccountJson.firmware(api.firmwareCheck(robot.id),robot.id) }.getOrNull()?.let {
            mutable.value=mutable.value.copy(firmwareCheck=it);tracker.acceptCheck(it,t)
        }
    }
    private suspend fun followFirmware(robot:CloudRobot,tracker:FirmwareUpdateTracker) {
        val commands=Commands(api.userAccount())
        val absorber=Absorber(robot)
        var lastCheck=0L
        var lastStatusAt=absorber.details.value.statusAt ?: 0
        var lastProgress:Int?=null
        fun publish() {
            setFirmware(tracker.value)
            if(tracker.value.progress!=lastProgress && (tracker.value.progress ?: 0)%10==0) {
                lastProgress=tracker.value.progress
                event("firmware_update_progress",tr("Firmware update ${tracker.value.progress ?: 0} %", "Mise à jour firmware ${tracker.value.progress ?: 0} %"),mapOf("stage" to tracker.value.stage))
            }
        }
        setFirmware(tracker.value)
        while(!tracker.done) {
            try {
                connectMqtt(robot,properties=true).use { link ->
                    // A single report subscription: robot state (16/22/23) and versions, without periodic keep-alive.
                    runCatching { api.invoke(commands.sync(syncType=3),robot.name,robot.id);api.invoke(commands.reports(false,account=true),robot.name,robot.id) }
                    while(!tracker.done) {
                        val e=withTimeoutOrNull(5000){link.events.receive()}
                        val t=now()
                        when(e) {
                            is CloudEvent.Property -> {
                                OtaProgressJson.parse(e.json)?.let { tracker.accept(it,t) }
                                OtaProgressJson.deviceState(e.json)?.let { tracker.acceptState(it,t) }
                            }
                            is CloudEvent.Proto -> absorber.accept(e)?.let { m ->
                                if(m.hasNet() && m.net.hasToappUpgradeReport()) tracker.accept(OtaProgressJson.fromReport(m.net.toappUpgradeReport),t)
                                val st=absorber.details.value
                                if((st.statusAt ?: 0)>lastStatusAt) { lastStatusAt=st.statusAt!!;tracker.acceptState(st.sysStatus,t) }
                            }
                            null -> {}
                        }
                        if(t-lastCheck>=15000) { lastCheck=t;pollFirmware(robot,tracker,t) }
                        tracker.tick(t)
                        publish()
                    }
                }
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) {
                // MQTT connection lost (the robot often restarts to install): HTTP re-read then reconnection.
                setFirmware(tracker.value.copy(stage=tr("Account connection lost · retrying", "Connexion au compte perdue · nouvel essai")))
                repeat(3) { delay(5000);val t=now();pollFirmware(robot,tracker,t);tracker.tick(t) }
                publish()
            }
        }
        pollFirmware(robot,tracker,now())
        setFirmware(tracker.value)
        event("firmware_update_end",tracker.value.status,mapOf("phase" to tracker.value.phase.name,"progress" to tracker.value.progress,"target" to tracker.value.target))
    }
    override fun onCleared(){job?.cancel();followJob?.cancel();cameraIo.cancel();handler.post{log.close()};super.onCleared()}
}
