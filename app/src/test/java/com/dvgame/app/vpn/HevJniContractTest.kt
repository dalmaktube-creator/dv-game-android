package com.dvgame.app.vpn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevJniContractTest {
    @Test fun bridgeMatchesPinnedVoidJniContract() {
        val source = File("src/main/java/com/v2ray/ang/service/TProxyService.kt").readText()
        assertTrue(source.contains("external fun TProxyStartService(configPath: String, fd: Int)"))
        assertTrue(source.contains("external fun TProxyStopService()"))
        assertFalse(source.contains("TProxyStartService(configPath: String, fd: Int): Boolean"))
        assertFalse(source.contains("check(TProxyStartService"))
    }
}
