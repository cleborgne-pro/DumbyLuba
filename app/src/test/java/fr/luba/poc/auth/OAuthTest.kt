package fr.luba.poc.auth
import org.junit.Test
import org.junit.Assert.*
class OAuthTest {
 @Test fun `cloud refuses all motor commands and accepts map reading`() {
  val c=fr.luba.poc.protocol.Commands()
  for(bytes in listOf(c.stop(),c.bladeHeight(60),c.manualBlades(true,60),c.manualBlades(false,60),c.sync())) {
   try {CloudReadOnly.requireAllowed(bytes);fail("Motor command or BLE sync accepted")} catch(_:IllegalArgumentException){}
  }
  CloudReadOnly.requireAllowed(c.sync(syncType=3))
  CloudReadOnly.requireAllowed(c.reports(false))
  CloudReadOnly.requireAllowed(c.reports(true))
  CloudReadOnly.requireAllowed(c.mapHashes())
  CloudReadOnly.requireAllowed(c.mapData(42))
 }

 @Test fun `signature matches the upstream Python vector`() {
  val json="""{"username":"test@example.com","password":"YWJj","client_id":"test-client","grant_type":"password","authType":"0"}"""
  assertEquals("39bd5765757c12d3fc16b120db2594dfcb3074829d6a544581929ca2b6589039",OAuthSignature.sign("test-client","test-secret",123456789,json))
 }
}
