package fr.luba.poc.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Mammotion cloud simulated on a local ephemeral port (no Internet call). The HTTPS URLs checked by [MammotionCloud]
 * are redirected to this server keeping path and parameters; [hosts] keeps the requested host.
 */
class FakeMammotionServer : AutoCloseable {
    data class Request(val host: String, val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)
    /** Reply: HTTP code and raw body. */
    data class Reply(val code: Int, val body: ByteArray)

    val requests = CopyOnWriteArrayList<Request>()
    /** Replies per path, consumed in order; the last one is repeated. */
    private val routes = java.util.concurrent.ConcurrentHashMap<String, MutableList<(Request) -> Reply>>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val thread = Thread({ while (!server.isClosed) runCatching { server.accept().use(::serve) } }, "fake-mammotion").apply { isDaemon = true; start() }

    /** Minimal HTTP/1.1 (one request per connection, `Connection: close`): the JDK httpserver is not visible on Android. */
    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
        val (method, target) = line().split(' ').let { it[0] to it[1] }
        val headers = generateSequence(::line).takeWhile { it.isNotEmpty() }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } }
        val request = Request(headers["x-test-host"].orEmpty(), method, target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body.decodeToString())
        requests += request
        val handlers = routes[request.path]
        val reply = synchronized(routes) { when {
            handlers == null -> Reply(404, "{}".toByteArray())
            handlers.size > 1 -> handlers.removeAt(0)(request)
            else -> handlers[0](request)
        } }
        val out = socket.getOutputStream()
        runCatching {
            out.write("HTTP/1.1 ${reply.code} X\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(reply.body); out.flush()
        }
    }
    val port get() = server.localPort

    fun on(path: String, vararg replies: (Request) -> Reply) { routes[path] = replies.toMutableList() }
    fun json(path: String, vararg bodies: String) = on(path, *bodies.map { b -> { _: Request -> Reply(200, b.toByteArray()) } }.toTypedArray())
    fun status(path: String, code: Int) = on(path, { _ -> Reply(code, ByteArray(0)) })

    /** Connection opener injected into [MammotionCloud]: same path, local HTTP server. */
    val open: (URL) -> HttpURLConnection = { url ->
        val local = URL("http://127.0.0.1:$port${url.path}${url.query?.let { "?$it" }.orEmpty()}")
        (local.openConnection() as HttpURLConnection).also { it.setRequestProperty("X-Test-Host", url.host) }
    }
    fun paths() = requests.map { it.path }
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; server.close() } }

    companion object {
        const val TOKEN_PATH = "/oauth2/token"
        const val IOT = "https://api-iot.mammotion.com"
        /** JWT-format access token whose `iot` claim gives the IoT domain. */
        fun jwt(iot: String = IOT, tag: String = "a"): String {
            fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return b64("""{"alg":"none"}""") + "." + b64(JSONObject().put("iot", iot).put("tag", tag).toString()) + ".sig"
        }
        fun session(access: String = jwt(), refresh: String? = "refresh-1", account: String = "1234") =
            JSONObject().put("access_token", access).apply { if (refresh != null) put("refresh_token", refresh) }
                .put("userInformation", JSONObject().put("userAccount", account))
        fun ok(data: Any) = JSONObject().put("code", 0).put("data", data).toString()
        /** Software AES key: the Android Keystore does not exist under Robolectric. */
        fun testKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun store(context: Context, name: String = "test_session", key: SecretKey = testKey()) = SessionStore(context, name) { key }
    }
}
