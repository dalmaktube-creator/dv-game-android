package com.dvgame.app.vpn

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun usesAggressiveNatSafeKeepaliveOnCellular() {
        assertEquals(15, effectiveKeepaliveSeconds(0, UnderlyingTransport.CELLULAR))
        assertEquals(15, effectiveKeepaliveSeconds(25, UnderlyingTransport.CELLULAR))
        assertEquals(10, effectiveKeepaliveSeconds(10, UnderlyingTransport.CELLULAR))
    }

    @Test fun usesStandardKeepaliveOnWifi() {
        assertEquals(25, effectiveKeepaliveSeconds(0, UnderlyingTransport.WIFI))
        assertEquals(25, effectiveKeepaliveSeconds(60, UnderlyingTransport.WIFI))
    }

    @Test fun reconnectBackoffIsBounded() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
            (1..6).map(::reconnectDelayMs))
    }

    @Test fun rotatesAllResolvedARecords() {
        val values = listOf("192.0.2.1", "192.0.2.2", "192.0.2.3")
        assertEquals("192.0.2.1", selectEndpointAddress(values, 0))
        assertEquals("192.0.2.2", selectEndpointAddress(values, 1))
        assertEquals("192.0.2.3", selectEndpointAddress(values, 2))
        assertEquals("192.0.2.1", selectEndpointAddress(values, 3))
    }

    @Test fun classifiesConfigurationFailuresAsNonRetryable() {
        assertFalse(isRetryableTunnelFailure(IllegalArgumentException("bad config")))
        assertFalse(isRetryableTunnelFailure(ConnectionBlockedException("expired")))
        assertTrue(isRetryableTunnelFailure(UnknownHostException("server")))
    }

    @Test fun hidesRawEngineErrorsFromUser() {
        val text = humanReadableTunnelError(IllegalStateException("lookup failed: WireGuard is not ready yet"))
        assertFalse(text.contains("lookup", ignoreCase = true))
        assertTrue(text.contains("آدرس سرور") || text.contains("موتور اتصال"))
    }
}
