package com.dvgame.app.vpn

import android.content.Context
import com.dvgame.app.model.TunnelStatus
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/* Google identity and game-save services remain available for games that use
 * Play Games sign-in. Play Store, Download Manager, browsers and social apps
 * are deliberately excluded. */
private val ESSENTIAL_GAME_SERVICES = listOf(
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.play.games",
)

data class TunnelTelemetry(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val latestHandshakeEpochMillis: Long = 0,
    val routedPackages: Int = 0,
)

internal fun scopeConfigToPackages(raw: String, packageNames: Set<String>): String {
    require(packageNames.isNotEmpty() && packageNames.size <= 8) { "فهرست سرویس‌های بازی نامعتبر است" }
    require(packageNames.all(PACKAGE_NAME_PATTERN::matches)) { "نام پکیج بازی نامعتبر است" }
    val clean = raw.lineSequence().filterNot {
        val value = it.trimStart()
        value.startsWith("IncludedApplications", true) || value.startsWith("ExcludedApplications", true)
    }.toMutableList()
    val interfaceIndexes = clean.indices.filter { clean[it].trim().equals("[Interface]", true) }
    require(interfaceIndexes.size == 1) { "ساختار Interface کانفیگ نامعتبر است" }
    require(clean.count { it.trim().equals("[Peer]", true) } >= 1) { "بخش Peer در کانفیگ پیدا نشد" }
    clean.add(interfaceIndexes.single() + 1,
        "IncludedApplications = ${packageNames.sorted().joinToString(", ")}")
    return clean.joinToString("\n")
}

/* The panel currently emits an IPv4 client address with dual-stack
 * AllowedIPs. On Android this can install ::/0 without any IPv6 interface
 * address, leaving games waiting on an unusable IPv6 path before falling back.
 * Fail closed instead: when the interface has no IPv6 address, remove only
 * IPv6 AllowedIPs. IPv6 is then blocked by VpnService rather than leaked to
 * the underlying network. DNS and every IPv4 route remain untouched. */
internal fun removeUnconfiguredIpv6Routes(raw: String): String {
    val lines = raw.lines()
    var section = ""
    var hasIpv6InterfaceAddress = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            section = trimmed.lowercase()
            continue
        }
        if (section == "[interface]" && trimmed.substringBefore('=').trim().equals("Address", true)) {
            val values = trimmed.substringAfter('=', "").split(',')
            if (values.any { it.trim().contains(':') }) hasIpv6InterfaceAddress = true
        }
    }
    if (hasIpv6InterfaceAddress) return raw

    section = ""
    return lines.map { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            section = trimmed.lowercase()
            line
        } else if (section == "[peer]" && trimmed.substringBefore('=').trim().equals("AllowedIPs", true)) {
            val ipv4Only = trimmed.substringAfter('=', "").split(',')
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.contains(':') }
            require(ipv4Only.isNotEmpty()) { "کانفیگ بدون مسیر IPv4 قابل استفاده نیست" }
            val indent = line.takeWhile(Char::isWhitespace)
            "${indent}AllowedIPs = ${ipv4Only.joinToString(", ")}"
        } else line
    }.joinToString("\n")
}

internal fun scopeConfigToPackage(raw: String, packageName: String): String =
    scopeConfigToPackages(raw, setOf(packageName))

class TunnelRepository(private val context: Context, private val scope: CoroutineScope) {
    private val backend = GoBackend(context)
    private val secureStore = SecureTunnelStore(context)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Down)
    val status = mutableStatus.asStateFlow()
    private val mutableTelemetry = MutableStateFlow(TunnelTelemetry())
    val telemetry = mutableTelemetry.asStateFlow()
    private var telemetryJob: Job? = null
    private var activePackage: String? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "dv-game"
        override fun onStateChange(newState: Tunnel.State) {
            mutableStatus.value = if (newState == Tunnel.State.UP) TunnelStatus.Up(activePackage.orEmpty()) else TunnelStatus.Down
        }
    }

    init { GoBackend.setAlwaysOnCallback { scope.launch { restoreForAlwaysOn() } } }

    suspend fun connect(rawConfig: String, approvedPackage: String, restoreValidUntilMs: Long) = mutex.withLock {
        require(restoreValidUntilMs > System.currentTimeMillis()) { "اعتبار محلی اتصال پایان یافته است" }
        require(isInstalled(approvedPackage)) { "بازی تأییدشده روی گوشی نصب نیست" }
        mutableStatus.value = TunnelStatus.Connecting
        activePackage = approvedPackage
        try {
            val routedPackages = buildSet {
                add(approvedPackage)
                ESSENTIAL_GAME_SERVICES.filterTo(this) { isInstalled(it) }
            }
            val networkSafe = removeUnconfiguredIpv6Routes(rawConfig)
            /* Only application routing and the invalid IPv6-only route case
             * are adjusted. DNS, MTU, endpoint, IPv4 AllowedIPs and keepalive
             * remain from the panel so the server-side DNS lock stays in
             * complete control. */
            val scoped = scopeConfigToPackages(networkSafe, routedPackages)
            val parsed = Config.parse(ByteArrayInputStream(scoped.toByteArray(Charsets.UTF_8)))
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, parsed) }
            secureStore.save(rawConfig, approvedPackage, restoreValidUntilMs)
            mutableTelemetry.value = TunnelTelemetry(routedPackages = routedPackages.size)
            mutableStatus.value = TunnelStatus.Up(approvedPackage)
            startTelemetryMonitor(routedPackages.size)
        } catch (error: Throwable) {
            telemetryJob?.cancel()
            activePackage = null
            mutableTelemetry.value = TunnelTelemetry()
            mutableStatus.value = TunnelStatus.Error(messageFor(error))
            throw error
        }
    }

    suspend fun disconnect(forget: Boolean = false) = mutex.withLock {
        telemetryJob?.cancel()
        telemetryJob = null
        withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        activePackage = null
        mutableTelemetry.value = TunnelTelemetry()
        if (forget) secureStore.clear()
        mutableStatus.value = TunnelStatus.Down
    }

    suspend fun restoreForAlwaysOn() {
        val saved = secureStore.load() ?: return
        runCatching { connect(saved.config, saved.packageName, saved.validUntilMs) }
            .onFailure { secureStore.clear() }
    }

    private fun startTelemetryMonitor(routedPackageCount: Int) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val stats = backend.getStatistics(tunnel)
                    val latestHandshake = stats.peers().mapNotNull { key ->
                        stats.peer(key)?.latestHandshakeEpochMillis()
                    }.maxOrNull() ?: 0L
                    TunnelTelemetry(
                        rxBytes = stats.totalRx(),
                        txBytes = stats.totalTx(),
                        latestHandshakeEpochMillis = latestHandshake,
                        routedPackages = routedPackageCount,
                    )
                }.onSuccess { mutableTelemetry.value = it }
                delay(1_500)
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) return false
        return runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    }

    private fun messageFor(error: Throwable): String = when ((error as? BackendException)?.reason) {
        BackendException.Reason.VPN_NOT_AUTHORIZED -> "مجوز VPN صادر نشده است"
        BackendException.Reason.DNS_RESOLUTION_FAILURE -> "نام سرور قابل شناسایی نیست"
        BackendException.Reason.TUN_CREATION_ERROR -> "ساخت رابط VPN ناموفق بود"
        BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "هسته WireGuard فعال نشد"
        else -> error.message ?: "اتصال WireGuard ناموفق بود"
    }
}
