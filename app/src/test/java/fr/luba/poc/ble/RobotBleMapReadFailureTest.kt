package fr.luba.poc.ble

import fr.luba.poc.diagnostics.LogSanitizer
import fr.luba.poc.map.Point
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failed robot map reading (DumbyLuba#3) with the simulated robot: the reason stays visible until a new reading, a
 * disconnection or the link closing; LUBA 1 reading logged with its receiver and without the account number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleMapReadFailureTest {
    private val benches = mutableListOf<RobotBench>()
    @After fun tearDown() = benches.forEach { it.close() }

    private fun bench(name: String = "Luba-VS-SIM01") = RobotBench(Point(5.0, 2.0), 90.0, name = name).also { benches += it }

    /** Reading of a robot that never answers it: failure after the overall bound. */
    private fun failedReading(bench: RobotBench): String {
        bench.installMap(); bench.connect()
        bench.ble.fetchMap()
        assertTrue(bench.state.mapLoading)
        bench.until(70_000, "read failure") { !bench.state.mapLoading }
        return requireNotNull(bench.state.mapReadFailure)
    }

    @Test fun `failure shown while linked, cleared by the user disconnection and not back at the next connection`() {
        val bench = bench()
        val failure = failedReading(bench)
        bench.advance(120_000)
        assertEquals(failure, fr.luba.poc.map.mapReadFailureShown(bench.state))
        // Account map screen (cloud): the Bluetooth failure is not shown there.
        assertNull(fr.luba.poc.map.mapReadFailureShown(bench.state, cloud = true))
        bench.ble.disconnect()
        bench.until(5000, "disconnected") { bench.state.connection == ConnectionState.Disconnected }
        assertNull(bench.state.mapReadFailure)
        assertNull(fr.luba.poc.map.mapReadFailureShown(bench.state))
        bench.connect()
        assertNull(bench.state.mapReadFailure)
    }

    @Test fun `failure cleared when the link drops, then a successful reading leaves none`() {
        val bench = bench()
        failedReading(bench)
        bench.link.drop()
        bench.until(5000, "link closed") { bench.state.mapReadFailure == null }
        assertFalse(bench.state.ready)
        // Reconnection: the robot now serves its map, the reading ends without a failure.
        bench.until(60_000, "reconnected") { bench.state.ready }
        bench.sim.loadMap(RobotBench.MAP)
        bench.ble.fetchMap()
        bench.until(30_000, "map read") { bench.state.mammotionMap.complete && !bench.state.mapLoading }
        assertNull(bench.state.mapReadFailure)
    }

    @Test fun `LUBA 1 reading logged with its receiver, the account number echoed by the robot never logged`() {
        val account = 87654321
        val bench = bench("Luba-SIM0001")
        bench.sim.navReceiver = MsgDevice.DEV_MAINCTL
        bench.sim.echoSubtype = account
        bench.sim.loadMap(RobotBench.MAP)
        bench.connect()
        bench.ble.fetchMap()
        bench.until(30_000, "LUBA 1 map read") { bench.state.mammotionMap.complete && !bench.state.mapLoading }
        assertNull(bench.state.mapReadFailure)
        bench.advance(2000)
        val dir = java.io.File(bench.app.filesDir, "diagnostics")
        var lines: List<String> = emptyList()
        bench.awaitReal(5000, "log written") {
            lines = dir.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.flatMap { it.readLines().asSequence() }.toList()
            lines.any { it.contains("\"map_complete\"") }
        }
        val events = lines.map { JSONObject(it) }
        val start = events.last { it.getString("event") == "map_start" }.getJSONObject("fields")
        assertEquals("DEV_MAINCTL", start.getString("navReceiver"))
        assertFalse(start.getBoolean("viaGateway"))
        assertTrue(events.any { it.getString("event") == "map_nav_rx" })
        val decoded = events.filter { it.getString("event") == "protobuf_decoded" }
        assertTrue(decoded.isNotEmpty())
        decoded.forEach { e ->
            val fields = e.getJSONObject("fields")
            assertTrue(fields.getBoolean("accountEcho"))
            assertFalse(fields.getString("decoded"), fields.getString("decoded").contains("subtype"))
        }
        // Raw log: only the raw BLE frames (hex) carry it; the anonymized export removes them too.
        lines.filterNot { it.contains("\"ble_rx\"") }.forEach { assertFalse(it, it.contains("$account")) }
        val sanitizer = LogSanitizer()
        lines.map(sanitizer::line).forEach { assertFalse(it, it.contains("$account")) }
        assertNotNull(sanitizer.counts["ble frame"])
    }
}
