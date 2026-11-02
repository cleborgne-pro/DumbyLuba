package fr.luba.poc.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway protocol is plain TCP: only local addresses may receive the pairing code. A public address (or a name
 * that is not mDNS) is refused before the socket is opened.
 */
class LocalNetworkTest {
    @Test fun loopbackAndMdnsNamesAreLocal() {
        assertTrue(LocalNetwork.allows("localhost"))
        assertTrue(LocalNetwork.allows("127.0.0.1"))
        assertTrue(LocalNetwork.allows("127.1.2.3"))
        assertTrue(LocalNetwork.allows("::1"))
        assertTrue(LocalNetwork.allows("[::1]"))
        assertTrue(LocalNetwork.allows("dumbyluba-esp32.local"))
        assertTrue(LocalNetwork.allows("Gateway.Local"))
    }

    @Test fun privateRangesAreLocal() {
        assertTrue(LocalNetwork.allows("10.0.0.1"))
        assertTrue(LocalNetwork.allows("10.255.255.254"))
        assertTrue(LocalNetwork.allows("172.16.0.1"))
        assertTrue(LocalNetwork.allows("172.31.255.255"))
        assertTrue(LocalNetwork.allows("192.168.0.189"))
        assertTrue(LocalNetwork.allows("192.168.1.5"))
        assertTrue(LocalNetwork.allows("169.254.10.20"))
        assertTrue(LocalNetwork.allows("fd12:3456:789a::1"))
        assertTrue(LocalNetwork.allows("fe80::1"))
        assertTrue(LocalNetwork.allows("::ffff:192.168.0.10"))
    }

    @Test fun publicAddressesAndNamesAreRefused() {
        assertFalse(LocalNetwork.allows("8.8.8.8"))
        assertFalse(LocalNetwork.allows("172.32.0.1"))
        assertFalse(LocalNetwork.allows("192.169.0.1"))
        assertFalse(LocalNetwork.allows("2001:db8::1"))
        assertFalse(LocalNetwork.allows("example.com"))
        assertFalse(LocalNetwork.allows("gateway.lan"))
        assertFalse(LocalNetwork.allows(""))
        assertFalse(LocalNetwork.allows("999.1.1.1"))
        assertFalse(LocalNetwork.allows("192.168.01.5"))
    }
}
