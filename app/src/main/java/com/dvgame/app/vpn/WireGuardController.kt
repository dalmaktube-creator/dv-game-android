package com.dvgame.app.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class WireGuardController(context: Context) {
    private val backend = GoBackend(context.applicationContext)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "dv-game"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    fun connect(rawConfig: String, allowedPackages: Set<String>) {
        require(allowedPackages.isNotEmpty()) { "حداقل یک بازی را انتخاب کنید" }
        val scoped = withIncludedApplications(rawConfig, allowedPackages)
        val config = Config.parse(ByteArrayInputStream(scoped.toByteArray(Charsets.UTF_8)))
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    fun disconnect() {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }

    fun isConnected(): Boolean = backend.getState(tunnel) == Tunnel.State.UP

    internal fun withIncludedApplications(raw: String, packages: Set<String>): String {
        val cleaned = raw.lineSequence()
            .filterNot {
                val value = it.trimStart()
                value.startsWith("IncludedApplications", ignoreCase = true) ||
                    value.startsWith("ExcludedApplications", ignoreCase = true)
            }
            .toMutableList()
        val interfaceIndex = cleaned.indexOfFirst { it.trim().equals("[Interface]", true) }
        require(interfaceIndex >= 0) { "بخش [Interface] در کانفیگ پیدا نشد" }
        cleaned.add(interfaceIndex + 1, "IncludedApplications = ${packages.sorted().joinToString(", ")}")
        return cleaned.joinToString("\n")
    }
}
