package com.dvgame.app.vpn

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BoxController private constructor(private val app: Application) {
    val state: MutableStateFlow<TunnelState> = MutableStateFlow(TunnelState.Stopped)
    val stateFlow: StateFlow<TunnelState> = state.asStateFlow()

    fun connect(singBoxConfig: String, packages: Set<String>) {
        val intent = Intent(app, DvVpnService::class.java).apply {
            action = DvVpnService.ACTION_CONNECT
            putExtra(DvVpnService.EXTRA_CONFIG, singBoxConfig)
            putExtra(DvVpnService.EXTRA_PACKAGES, packages.toTypedArray())
        }
        app.startForegroundService(intent)
    }

    fun disconnect() {
        val intent = Intent(app, DvVpnService::class.java).apply {
            action = DvVpnService.ACTION_DISCONNECT
        }
        app.startService(intent)
    }

    fun setError(message: String) {
        state.value = TunnelState.Error(message)
    }

    companion object {
        @Volatile private var instance: BoxController? = null
        fun get(app: Application): BoxController =
            instance ?: synchronized(this) {
                instance ?: BoxController(app).also { instance = it }
            }
    }
}
