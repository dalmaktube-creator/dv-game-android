package com.dvgame.app.vpn

import android.content.Context
import com.dvgame.app.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

internal val ESSENTIAL_GAME_SERVICES = listOf(
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.play.games",
)

data class TunnelTelemetry(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val latestHandshakeEpochMillis: Long = 0,
    val routedPackages: Int = 0,
    val engineName: String = "",
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
    require(clean.count { it.trim().equals("[Peer]", true) } == 1) { "ساختار Peer کانفیگ نامعتبر است" }
    clean.add(interfaceIndexes.single() + 1, "IncludedApplications = ${packageNames.sorted().joinToString(", ")}")
    return clean.joinToString("\n")
}

internal fun removeUnconfiguredIpv6Routes(raw: String): String {
    val lines = raw.lines()
    var section = ""
    var hasIpv6InterfaceAddress = false
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) { section = trimmed.lowercase(); continue }
        if (section == "[interface]" && trimmed.substringBefore('=').trim().equals("Address", true)) {
            if (trimmed.substringAfter('=', "").split(',').any { it.trim().contains(':') }) hasIpv6InterfaceAddress = true
        }
    }
    if (hasIpv6InterfaceAddress) return raw
    section = ""
    return lines.map { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) { section = trimmed.lowercase(); line }
        else if (section == "[peer]" && trimmed.substringBefore('=').trim().equals("AllowedIPs", true)) {
            val ipv4Only = trimmed.substringAfter('=', "").split(',').map(String::trim).filter { it.isNotEmpty() && !it.contains(':') }
            require(ipv4Only.isNotEmpty()) { "کانفیگ بدون مسیر IPv4 قابل استفاده نیست" }
            "${line.takeWhile(Char::isWhitespace)}AllowedIPs = ${ipv4Only.joinToString(", ")}"
        } else line
    }.joinToString("\n")
}

internal fun scopeConfigToPackage(raw: String, packageName: String): String = scopeConfigToPackages(raw, setOf(packageName))

class TunnelRepository(private val context: Context, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
    val status = CompatibilityTunnelState.status.asStateFlow()
    val telemetry = CompatibilityTunnelState.telemetry.asStateFlow()

    private val gate = Mutex()

    suspend fun connect(rawConfig: String, approvedPackage: String, restoreValidUntilMs: Long, serverName: String = "") = gate.withLock {
        require(restoreValidUntilMs > System.currentTimeMillis()) { "اعتبار محلی اتصال پایان یافته است" }
        require(PACKAGE_NAME_PATTERN.matches(approvedPackage) && isInstalled(approvedPackage)) { "بازی تأییدشده روی گوشی نصب نیست" }
        parseWireGuardCompatConfig(rawConfig)
        ensureFullyStopped()
        CompatibilityTunnelState.status.value = TunnelStatus.Preparing
        CompatibilityVpnService.connect(context, rawConfig, approvedPackage, restoreValidUntilMs, serverName)
        val result = try {
            withTimeout(30_000) {
                status.filter {
                    it is TunnelStatus.Connected || it is TunnelStatus.Blocked || it is TunnelStatus.Failed
                }.first()
            }
        } catch (_: TimeoutCancellationException) {
            CompatibilityVpnService.disconnect(context)
            throw IllegalStateException("زمان برقراری اتصال بیش از حد طول کشید")
        }
        when (result) {
            is TunnelStatus.Blocked -> error(result.reason)
            is TunnelStatus.Failed -> error(result.message)
            else -> Unit
        }
    }

    suspend fun disconnect(forget: Boolean = false) {
        gate.withLock {
            if (forget) SecureTunnelStore(context).clear()
            CompatibilityVpnService.disconnect(context)
            withTimeoutOrNull(6_000) { status.filter { it is TunnelStatus.Idle }.first() }
        }
    }

    private suspend fun ensureFullyStopped() {
        if (status.value is TunnelStatus.Idle) return
        CompatibilityVpnService.disconnect(context)
        if (withTimeoutOrNull(6_000) { status.filter { it is TunnelStatus.Idle }.first() } == null) {
            CompatibilityTunnelState.status.value = TunnelStatus.Idle
        }
    }

    private fun isInstalled(packageName: String) = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess
}
