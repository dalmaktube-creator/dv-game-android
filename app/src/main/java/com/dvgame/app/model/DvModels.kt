package com.dvgame.app.model

data class ApprovedGame(val id: String, val name: String, val packages: List<String>)
data class InstalledGame(val id: String, val name: String, val packageName: String)
data class ServerProfile(val id: String, val name: String, val location: String, val config: String)
data class AccountInfo(val name: String, val state: String, val usedBytes: Long, val totalBytes: Long, val expiryMs: Long?) {
    fun connectionBlockReason(nowMs: Long = System.currentTimeMillis()): String? {
        return when {
            state.lowercase() != "active" -> "وضعیت اشتراک فعال نیست"
            expiryMs != null && expiryMs <= nowMs -> "اعتبار اشتراک به پایان رسیده است"
            totalBytes > 0 && usedBytes >= totalBytes -> "حجم اشتراک به پایان رسیده است"
            else -> null
        }
    }

    fun localRestoreValidUntilMs(nowMs: Long = System.currentTimeMillis()): Long {
        val shortLeaseEnd = nowMs + LOCAL_RESTORE_LEASE_MS
        return expiryMs?.let { minOf(it, shortLeaseEnd) } ?: shortLeaseEnd
    }

    private companion object {
        const val LOCAL_RESTORE_LEASE_MS = 15 * 60 * 1000L
    }
}

data class DvSubscription(
    val apiVersion: Int,
    val account: AccountInfo,
    val catalogVersion: Int,
    val catalogDigest: String,
    val games: List<ApprovedGame>,
    val profiles: List<ServerProfile>,
)

sealed interface TunnelStatus {
    data object Idle : TunnelStatus
    data object Preparing : TunnelStatus
    data object Starting : TunnelStatus
    data class Connected(val packageName: String) : TunnelStatus
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : TunnelStatus
    data object Stopping : TunnelStatus
    data class Blocked(val reason: String) : TunnelStatus
    data class Failed(val message: String, val retryable: Boolean = true) : TunnelStatus
}
