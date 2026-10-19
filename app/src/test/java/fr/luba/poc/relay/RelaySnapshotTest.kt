package fr.luba.poc.relay

import fr.luba.poc.ble.RobotUi
import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RelaySnapshotTest {
    @Test fun exportsAllTelemetryAndDomainStateWithNoCredential() {
        val value = RelaySnapshot.capture(RobotUi(telemetry=Telemetry(battery=76,rtkStatus=4,cutterRpm=2200)),null,"phone",7,10000)
        val telemetry=value.getJSONObject("state").getJSONObject("telemetry")
        assertEquals(2200,telemetry.getInt("cutterRpm"))
        assertEquals(4,telemetry.getInt("rtkStatus"))
        assertTrue(value.getJSONObject("state").getJSONObject("details").has("lastKnown"))
        assertFalse(value.has("code"))
        assertEquals(7L,value.getLong("sequence"))
    }
    @Test fun durableOutboxSurvivesRestartAndKeepsOnlyLatest() {
        val dir=Files.createTempDirectory("relay-outbox").toFile()
        try {
            val file=java.io.File(dir,"latest.json")
            RelayOutbox(file).write("first")
            val restarted=RelayOutbox(file)
            assertEquals("first",restarted.read())
            restarted.write("latest")
            assertEquals("latest",RelayOutbox(file).read())
            restarted.acknowledge()
            assertNull(restarted.read())
        } finally { dir.deleteRecursively() }
    }
    @Test fun codeRequiresExactlySixAsciiDigitsAndUrlCannotLeakItThroughRedirects() {
        RelayConfig("http://192.168.1.2:8765","001234","id").validate()
        for(config in listOf(RelayConfig("http://host/redirect","001234","id"),RelayConfig("http://user@host","001234","id"),RelayConfig("http://host","12345","id"))) {
            try { config.validate(); fail("Configuration accepted") } catch(_:IllegalArgumentException) {}
        }
    }
}
