package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/** Thin, auditable JNI bridge for the pinned hev-socks5-tunnel binary. */
class TProxyService(private val context: Context, private val tun: ParcelFileDescriptor) {
    companion object {
        @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic private external fun TProxyStopService(): Boolean
        init { System.loadLibrary("hev-socks5-tunnel") }
    }

    fun start() {
        val file = File(context.filesDir, "dvgame-hev.yaml")
        file.writeText(
            """
            tunnel:
              mtu: 1280
              ipv4: 10.10.0.2
            socks5:
              port: 10808
              address: 127.0.0.1
              udp: 'udp'
            misc:
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
            """.trimIndent()
        )
        check(TProxyStartService(file.absolutePath, tun.fd)) { "راه‌اندازی UDP compatibility ناموفق بود" }
    }

    fun stop() { runCatching { TProxyStopService() } }
}
