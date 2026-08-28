package com.dvgame.app.net

import com.dvgame.app.model.AccountInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionClientTest {
    private fun payload(apiVersion: Int = 1, state: String = "active") = """
        {"apiVersion":$apiVersion,"account":{"name":"demo","state":"$state","usedBytes":0,"totalBytes":100,"expiryMs":null},
         "catalog":{"version":2,"digest":"sha256:test","games":[{"id":"pubg","name":"PUBG","packages":["com.tencent.ig"]}]},
         "configs":[{"id":"1","name":"DE","location":"Germany","config":"[Interface]\\nPrivateKey = x\\n[Peer]\\nPublicKey = y"}]}
    """.trimIndent()

    @Test fun parsesVersionedPanelPayload() {
        val result = SubscriptionClient.parse(payload())
        assertEquals("com.tencent.ig", result.games.single().packages.single())
        assertEquals("Germany", result.profiles.single().location)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedApiVersion() { SubscriptionClient.parse(payload(apiVersion = 2)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPackageName() {
        SubscriptionClient.parse(payload().replace("com.tencent.ig", "com.tencent.ig\\nInjected = yes"))
    }

    @Test fun blocksInactiveExpiredAndDepletedAccounts() {
        assertNotNull(AccountInfo("x", "disabled", 0, 100, null).connectionBlockReason(1000))
        assertNotNull(AccountInfo("x", "active", 0, 100, 999).connectionBlockReason(1000))
        assertNotNull(AccountInfo("x", "active", 100, 100, null).connectionBlockReason(1000))
        assertNull(AccountInfo("x", "active", 50, 100, 2000).connectionBlockReason(1000))
    }
}
