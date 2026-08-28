package com.dvgame.app.vpn

import android.content.Context
import com.dvgame.app.model.TunnelStatus
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class TunnelRepository(private val context: Context, private val scope: CoroutineScope) {
    private val backend = GoBackend(context)
    private val secureStore = SecureTunnelStore(context)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Down)
    val status = mutableStatus.asStateFlow()
    private var activePackage: String? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "dv-game"
        override fun onStateChange(newState: Tunnel.State) {
            mutableStatus.value = if (newState == Tunnel.State.UP) TunnelStatus.Up(activePackage.orEmpty()) else TunnelStatus.Down
        }
    }

    init { GoBackend.setAlwaysOnCallback { scope.launch { restoreForAlwaysOn() } } }

    suspend fun connect(rawConfig: String, approvedPackage: String) = mutex.withLock {
        require(isInstalled(approvedPackage)) { "بازی تأییدشده روی گوشی نصب نیست" }
        mutableStatus.value = TunnelStatus.Connecting
        activePackage = approvedPackage
        try {
            val scoped = withIncludedApplication(rawConfig, approvedPackage)
            val parsed = Config.parse(ByteArrayInputStream(scoped.toByteArray(Charsets.UTF_8)))
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, parsed) }
            secureStore.save(rawConfig, approvedPackage)
            mutableStatus.value = TunnelStatus.Up(approvedPackage)
        } catch (error: Throwable) {
            activePackage = null
            mutableStatus.value = TunnelStatus.Error(messageFor(error))
            throw error
        }
    }

    suspend fun disconnect(forget: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        activePackage = null
        if (forget) secureStore.clear()
        mutableStatus.value = TunnelStatus.Down
    }

    suspend fun restoreForAlwaysOn() {
        val saved = secureStore.load() ?: return
        runCatching { connect(saved.first, saved.second) }
    }

    internal fun withIncludedApplication(raw: String, packageName: String): String {
        val clean = raw.lineSequence().filterNot {
            val value = it.trimStart()
            value.startsWith("IncludedApplications", true) || value.startsWith("ExcludedApplications", true)
        }.toMutableList()
        val index = clean.indexOfFirst { it.trim().equals("[Interface]", true) }
        require(index >= 0) { "بخش Interface در کانفیگ پیدا نشد" }
        clean.add(index + 1, "IncludedApplications = $packageName")
        return clean.joinToString("\n")
    }

    private fun isInstalled(packageName: String) = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    private fun messageFor(error: Throwable): String = when ((error as? BackendException)?.reason) {
        BackendException.Reason.VPN_NOT_AUTHORIZED -> "مجوز VPN صادر نشده است"
        BackendException.Reason.DNS_RESOLUTION_FAILURE -> "نام سرور قابل شناسایی نیست"
        BackendException.Reason.TUN_CREATION_ERROR -> "ساخت رابط VPN ناموفق بود"
        BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "هسته WireGuard فعال نشد"
        else -> error.message ?: "اتصال WireGuard ناموفق بود"
    }
}
