package com.dvgame.app.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardCompatConfigTest {
    private val raw = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.77.0.2/32
        DNS = 10.77.0.1
        MTU = 1280
        IncludedApplications = com.android.chrome

        [Peer]
        PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
        PresharedKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun convertsPanelWireGuardToUdpCompatibilityOutbound() {
        val parsed = parseWireGuardCompatConfig(raw)
        assertEquals(listOf("10.77.0.2/32"), parsed.addresses)
        assertEquals(listOf("10.77.0.1"), parsed.dnsServers)
        assertEquals(listOf("0.0.0.0/0"), parsed.allowedIps)
        assertEquals(1280, parsed.mtu)
        val json = JSONObject(buildXrayWireGuardConfig(parsed))
        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", outbound.getString("protocol"))
        assertEquals("ForceIPv4", outbound.getJSONObject("settings").getString("domainStrategy"))
        assertTrue(outbound.getJSONObject("settings").getBoolean("noKernelTun"))
        assertFalse(buildXrayWireGuardConfig(parsed).contains("com.android.chrome"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultiplePeers() {
        parseWireGuardCompatConfig(raw + "\n[Peer]\nPublicKey = DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD=\nEndpoint=x:1\nAllowedIPs=0.0.0.0/0")
    }
}
