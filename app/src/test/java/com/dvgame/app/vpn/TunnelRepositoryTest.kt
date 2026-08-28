package com.dvgame.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRepositoryTest {
    @Test fun replacesAnyServerProvidedApplicationRouting() {
        val result = scopeConfigToPackages(
            "[Interface]\nIncludedApplications = com.android.chrome\nExcludedApplications = com.bank.app\nPrivateKey = x\n[Peer]\nPublicKey = y",
            setOf("com.mobile.legends", "com.google.android.gms", "com.google.android.play.games"),
        )
        assertTrue(result.contains("com.mobile.legends"))
        assertTrue(result.contains("com.google.android.gms"))
        assertTrue(result.contains("com.google.android.play.games"))
        assertFalse(result.contains("com.android.chrome"))
        assertFalse(result.contains("ExcludedApplications"))
        assertEquals(1, result.lineSequence().count { it.startsWith("IncludedApplications") })
    }

    @Test fun preservesPanelDnsMtuRoutesAndKeepaliveExactly() {
        val raw = "[Interface]\nPrivateKey = x\nAddress = 10.10.0.2/32\nDNS = 10.20.0.2\nMTU = 1280\n\n[Peer]\nPublicKey = y\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25"
        val result = scopeConfigToPackages(raw, setOf("com.mobile.legends", "com.google.android.gms"))
        assertTrue(result.contains("DNS = 10.20.0.2"))
        assertTrue(result.contains("MTU = 1280"))
        assertTrue(result.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(result.contains("PersistentKeepalive = 25"))
        assertFalse(result.contains("DNS = 1.1.1.1"))
        assertEquals(1, result.lineSequence().count { it.trimStart().startsWith("DNS =") })
    }

    @Test fun keepsSinglePackageCompatibility() {
        val result = scopeConfigToPackage(
            "[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y", "com.tencent.ig")
        assertTrue(result.contains("IncludedApplications = com.tencent.ig"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPackageInjection() {
        scopeConfigToPackages("[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y",
            setOf("com.game.ok", "com.bad\nIncludedApplications=com.android.chrome"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleInterfaceSections() {
        scopeConfigToPackage("[Interface]\n[Interface]\n[Peer]", "com.game.ok")
    }
}
