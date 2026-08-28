package com.dvgame.app.model

data class ApprovedGame(val id: String, val name: String, val packages: List<String>)
data class InstalledGame(val id: String, val name: String, val packageName: String)
data class ServerProfile(val id: String, val name: String, val location: String, val config: String)
data class AccountInfo(val name: String, val state: String, val usedBytes: Long, val totalBytes: Long, val expiryMs: Long?)
data class DvSubscription(
    val apiVersion: Int,
    val account: AccountInfo,
    val catalogVersion: Int,
    val catalogDigest: String,
    val games: List<ApprovedGame>,
    val profiles: List<ServerProfile>,
)

sealed interface TunnelStatus {
    data object Down : TunnelStatus
    data object Connecting : TunnelStatus
    data class Up(val packageName: String) : TunnelStatus
    data class Error(val message: String) : TunnelStatus
}
