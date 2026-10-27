package fr.luba.poc.gateway

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** ESP32 pairing by its PWR button: answer of GET /pair read, closed window and other gateways ignored. */
class GatewayButtonPairingTest {
    private val link = "dumbyluba://pair?v=2&host=192.168.0.187&port=8766&code=123456&name=ESP32"
    private fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"

    @Test fun `open window gives the pairing link`() {
        val pairing = GatewayButtonPairing.parseResponse(ok("{\"open\":true,\"link\":\"$link\",\"remaining_s\":170}"))
        assertEquals(GatewayPairing("192.168.0.187", 8766, "123456", "ESP32"), pairing)
        // Escaped slashes accepted (other JSON writers).
        assertEquals(pairing, GatewayButtonPairing.parseResponse(ok("{\"link\":\"${link.replace("/", "\\/")}\"}")))
    }

    @Test fun `closed window, other servers and malformed answers give nothing`() {
        for (bad in listOf(null, "", "HTTP/1.1 403 Forbidden\r\n\r\n{\"open\":false}", "HTTP/1.1 404 Not Found\r\n\r\nnot found",
                ok("{\"open\":true}"), ok("{\"link\":\"https://example.com\"}"),
                ok("{\"link\":\"dumbyluba://pair?v=2&host=192.168.0.187&code=12345\"}"),
                "HTTP/1.1 403 Forbidden\r\n\r\n{\"link\":\"$link\"}"))
            assertNull(bad, GatewayButtonPairing.parseResponse(bad))
    }

    @Test fun `first open gateway among the candidates, each asked once`() {
        val asked = mutableListOf<String>()
        val found = GatewayButtonPairing.first(listOf("192.168.0.189", " ", "192.168.0.187", "192.168.0.189 ", "")) { host ->
            asked += host
            if (host == "192.168.0.187") GatewayPairing(host, 8766, "123456") else null
        }
        assertEquals("192.168.0.187", found?.host)
        assertEquals(listOf("192.168.0.189", "192.168.0.187"), asked)
        assertNull(GatewayButtonPairing.first(emptyList()) { error("not called") })
    }

    @Test fun `fetch reads the window over a plain socket`() {
        ServerSocket(0).use { server ->
            val request = StringBuilder()
            val worker = thread {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; request.appendLine(line) }
                    client.getOutputStream().write(ok("{\"open\":true,\"link\":\"$link\"}").toByteArray())
                }
            }
            val pairing = GatewayButtonPairing.fetch("127.0.0.1", server.localPort)
            worker.join(2000)
            assertEquals("123456", pairing?.code)
            assertTrue(request.toString(), request.startsWith("GET /pair HTTP/1.0"))
        }
        // Nobody listening: null, no exception.
        val freePort = ServerSocket(0).use { it.localPort }
        assertNull(GatewayButtonPairing.fetch("127.0.0.1", freePort, timeoutMs = 500))
    }
}
