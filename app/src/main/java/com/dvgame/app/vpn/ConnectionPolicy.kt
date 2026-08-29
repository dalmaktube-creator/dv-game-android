package com.dvgame.app.vpn

import java.net.UnknownHostException
import kotlin.math.min

internal enum class UnderlyingTransport { CELLULAR, WIFI, ETHERNET, OTHER }

internal data class PacketPathOptions(
    val tunMtuCeiling: Int = 1280,
    val workers: Int = 2,
    val endpointIndependentNat: Boolean = false,
) {
    init {
        require(tunMtuCeiling in 576..1500) { "سقف MTU نامعتبر است" }
        require(workers in 1..8) { "تعداد worker نامعتبر است" }
    }
}

internal fun effectiveKeepaliveSeconds(panelValue: Int, transport: UnderlyingTransport): Int {
    require(panelValue in 0..65535) { "Keepalive نامعتبر است" }
    val safeMaximum = when (transport) {
        UnderlyingTransport.CELLULAR -> 15
        UnderlyingTransport.WIFI, UnderlyingTransport.ETHERNET, UnderlyingTransport.OTHER -> 25
    }
    return if (panelValue == 0) safeMaximum else min(panelValue, safeMaximum)
}

internal fun reconnectDelayMs(attempt: Int): Long {
    require(attempt >= 1)
    val shift = (attempt - 1).coerceAtMost(4)
    return min(1_000L shl shift, 15_000L)
}

internal fun selectEndpointAddress(addresses: List<String>, attempt: Int): String {
    require(addresses.isNotEmpty()) { "هیچ IPv4 برای Endpoint پیدا نشد" }
    require(attempt >= 0)
    return addresses[attempt % addresses.size]
}

internal class ConnectionBlockedException(message: String) : IllegalStateException(message)

internal fun isRetryableTunnelFailure(error: Throwable): Boolean = when (error) {
    is ConnectionBlockedException, is IllegalArgumentException, is SecurityException -> false
    else -> true
}

internal fun humanReadableTunnelError(error: Throwable): String {
    val raw = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
    return when {
        error is UnknownHostException || "unknownhost" in raw || "آدرس ipv4" in raw || "resolve" in raw || "lookup" in raw ->
            "آدرس سرور پیدا نشد؛ اینترنت یا DNS شبکه را بررسی کنید"
        "wireguard is not ready" in raw ->
            "موتور اتصال آماده نشد؛ شبکه را عوض کنید و دوباره تلاش کنید"
        "clash api is not included" in raw ->
            "نسخه موتور ناقص است؛ برنامه باید به‌روزرسانی شود"
        "permission" in raw || "مجوز vpn" in raw ->
            "مجوز VPN صادر نشده است"
        "timeout" in raw || "timed out" in raw ->
            "پاسخ سرور بیش از حد طول کشید"
        "network is unreachable" in raw || "no route to host" in raw || "اتصال شبکه" in raw ->
            "شبکه در دسترس نیست؛ اتصال اینترنت را بررسی کنید"
        "اعتبار" in raw || "اشتراک" in raw || "بازی تأییدشده" in raw ->
            error.message ?: "این اتصال در حال حاضر مجاز نیست"
        else -> "برقراری اتصال ناموفق بود؛ چند لحظه دیگر دوباره تلاش کنید"
    }
}
