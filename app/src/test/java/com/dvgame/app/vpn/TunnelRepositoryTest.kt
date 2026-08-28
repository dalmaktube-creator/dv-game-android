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
