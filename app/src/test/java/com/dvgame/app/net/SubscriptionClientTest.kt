package com.dvgame.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionClientTest {
    @Test fun parsesVersionedPanelPayload() {
        val result = SubscriptionClient.parse("""
            {"apiVersion":1,"account":{"name":"demo","state":"active","usedBytes":0,"totalBytes":100,"expiryMs":null},
             "catalog":{"version":2,"digest":"sha256:test","games":[{"id":"pubg","name":"PUBG","packages":["com.tencent.ig"]}]},
             "configs":[{"id":"1","name":"DE","location":"Germany","config":"[Interface]\\nPrivateKey = x\\n[Peer]\\nPublicKey = y"}]}
        """.trimIndent())
        assertEquals("com.tencent.ig", result.games.single().packages.single())
        assertEquals("Germany", result.profiles.single().location)
    }
}
