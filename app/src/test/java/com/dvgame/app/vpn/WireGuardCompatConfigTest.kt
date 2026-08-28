package com.dvgame.app.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardCompatConfigTest {
    private val raw = """
        [Interface]
        PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
        Address = 192.0.2.2/32
        DNS = 192.0.2.0
        MTU = 1280
        IncludedApplications = com.android.chrome

        [Peer]
        PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0, ::0/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun convertsPanelWireGuardToUdpCompatibilityOutbound() {
        val parsed = parseWireGuardCompatConfig(raw)
        assertEquals(listOf("192.0.2.2/32"), parsed.addresses)
        assertEquals(listOf("192.0.2.0"), parsed.dnsServers)
        assertEquals(listOf("0.0.0.0/0"), parsed.allowedIps)
        assertEquals(1280, parsed.mtu)
        val jsonText = buildXrayWireGuardConfig(parsed)
        val json = JSONObject(jsonText)
        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", outbound.getString("protocol"))
        assertEquals("ForceIPv4", outbound.getJSONObject("settings").getString("domainStrategy"))
        assertTrue(outbound.getJSONObject("settings").getBoolean("noKernelTun"))
        assertFalse(jsonText.contains("com.android.chrome"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultiplePeers() {
        parseWireGuardCompatConfig(raw + "\n[Peer]\nPublicKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\nEndpoint = 192.0.2.3:51820\nAllowedIPs = 198.51.100.0/24")
    }
}
