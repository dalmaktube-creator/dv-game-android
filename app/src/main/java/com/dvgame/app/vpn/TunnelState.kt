package com.dvgame.app.vpn

sealed class TunnelState {
    data object Stopped : TunnelState()
    data object Starting : TunnelState()
    data object Started : TunnelState()
    data object Stopping : TunnelState()
    data class Error(val message: String) : TunnelState()
}
