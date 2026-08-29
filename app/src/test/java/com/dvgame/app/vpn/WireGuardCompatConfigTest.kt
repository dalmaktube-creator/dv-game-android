package com.dvgame.app.vpn

import java.net.InetAddress
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
        DNS = 192.0.2.53
        MTU = 1420
        IncludedApplications = com.android.chrome

        [Peer]
        PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun convertsPanelWireGuardToStableLibboxPacketPath() {
        val parsed = parseWireGuardCompatConfig(raw)
        val text = buildLibboxWireGuardConfig(parsed, "com.mobile.legends", keepaliveSeconds = 15)
        val json = JSONObject(text)
        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("mixed", tun.getString("stack"))
        assertEquals(1280, tun.getInt("mtu"))
        assertFalse(tun.getBoolean("endpoint_independent_nat"))
        assertEquals("com.mobile.legends", tun.getJSONArray("include_package").getString(0))
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals(1420, endpoint.getInt("mtu"))
        assertEquals(2, endpoint.getInt("workers"))
        assertEquals(15, endpoint.getJSONArray("peers").getJSONObject(0).getInt("persistent_keepalive_interval"))
        assertEquals("wg-game", json.getJSONObject("route").getString("final"))
        assertFalse(text.contains("com.android.chrome"))
    }

    @Test fun peerAddressIsAlwaysAnIpv4Literal() {
        val hostnameConfig = parseWireGuardCompatConfig(raw.replace("192.0.2.1:51820", "game.example.com:51820"))
        val text = buildLibboxWireGuardConfig(
            hostnameConfig,
            "com.mobile.legends",
            resolvedEndpointAddress = "198.51.100.10",
            keepaliveSeconds = 15,
        )
        val address = JSONObject(text).getJSONArray("endpoints").getJSONObject(0)
            .getJSONArray("peers").getJSONObject(0).getString("address")
        assertTrue(isIpv4Literal(address))
        assertFalse(text.contains("game.example.com"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesHostnameInsideFinalEngineConfig() {
        val hostnameConfig = parseWireGuardCompatConfig(raw.replace("192.0.2.1:51820", "game.example.com:51820"))
        buildLibboxWireGuardConfig(hostnameConfig, "com.mobile.legends", keepaliveSeconds = 15)
    }

    @Test fun resolverKeepsAllDistinctIpv4Candidates() {
        val hostnameConfig = parseWireGuardCompatConfig(raw.replace("192.0.2.1:51820", "game.example.com:51820"))
        val candidates = resolveEndpointIpv4Candidates(hostnameConfig) {
            arrayOf(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("192.0.2.11"), InetAddress.getByName("192.0.2.10"))
        }
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), candidates)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultiplePeers() {
        parseWireGuardCompatConfig(raw + "\n[Peer]\nPublicKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\nEndpoint = 192.0.2.3:51820\nAllowedIPs = 198.51.100.0/24")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSecurityKey() {
        parseWireGuardCompatConfig(raw.replace("PrivateKey =", "PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\nPrivateKey ="))
    }
}
