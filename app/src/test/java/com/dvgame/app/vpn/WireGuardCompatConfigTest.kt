package com.dvgame.app.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WireGuardCompatConfigTest {
    private val raw = """
        [Interface]
        PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
        Address = 192.0.2.2/32
        DNS = 192.0.2.53
        MTU = 1280
        IncludedApplications = com.android.chrome

        [Peer]
        PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun convertsPanelWireGuardToLibboxMixedStack() {
        val parsed = parseWireGuardCompatConfig(raw)
        val text = buildLibboxWireGuardConfig(parsed, "com.mobile.legends")
        val json = JSONObject(text)
        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("mixed", tun.getString("stack"))
        assertEquals("com.mobile.legends", tun.getJSONArray("include_package").getString(0))
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("wg-game", json.getJSONObject("route").getString("final"))
        assertFalse(text.contains("com.android.chrome"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultiplePeers() {
        parseWireGuardCompatConfig(raw + "\n[Peer]\nPublicKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\nEndpoint = 192.0.2.3:51820\nAllowedIPs = 198.51.100.0/24")
    }
}
