package com.dvgame.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRepositoryTest {
    @Test fun replacesAnyServerProvidedApplicationRouting() {
        val result = scopeConfigToPackage(
            "[Interface]\nIncludedApplications = com.android.chrome\nExcludedApplications = com.bank.app\nPrivateKey = x\n[Peer]\nPublicKey = y",
            "com.tencent.ig",
        )
        assertTrue(result.contains("IncludedApplications = com.tencent.ig"))
        assertFalse(result.contains("com.android.chrome"))
        assertFalse(result.contains("ExcludedApplications"))
        assertEquals(1, result.lineSequence().count { it.startsWith("IncludedApplications") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPackageInjection() {
        scopeConfigToPackage("[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y", "com.game.ok\nIncludedApplications = com.bad")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleInterfaceSections() {
        scopeConfigToPackage("[Interface]\n[Interface]\n[Peer]", "com.game.ok")
    }
}
