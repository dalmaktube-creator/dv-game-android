package com.dvgame.app.vpn

import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress

internal data class WireGuardCompatConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int,
    val peerPublicKey: String,
    val peerPresharedKey: String?,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int,
)

internal fun parseWireGuardCompatConfig(raw: String): WireGuardCompatConfig {
    require(raw.length <= 512 * 1024) { "کانفیگ بیش از حد بزرگ است" }
    val validationCopy = raw.lineSequence().filterNot {
        val key = it.substringBefore('=').trim()
        key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true)
    }.joinToString("\n")
    Config.parse(ByteArrayInputStream(validationCopy.toByteArray(Charsets.UTF_8)))

    val interfaces = mutableListOf<MutableMap<String, String>>()
    val peers = mutableListOf<MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null
    raw.lineSequence().forEach { source ->
        val line = source.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            current = when (line.lowercase()) {
                "[interface]" -> mutableMapOf<String, String>().also(interfaces::add)
                "[peer]" -> mutableMapOf<String, String>().also(peers::add)
                else -> null
            }
            return@forEach
        }
        val split = line.indexOf('=')
        if (split > 0 && current != null) {
            current!![line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
        }
    }
    require(interfaces.size == 1) { "کانفیگ باید دقیقاً یک Interface داشته باشد" }
    require(peers.size == 1) { "موتور بازی فقط کانفیگ تک Peer پنل را می‌پذیرد" }
    val intf = interfaces.single()
    val peer = peers.single()
    fun csv(value: String?): List<String> = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    val addresses = csv(intf["address"]).filterNot { it.contains(':') }
    val dns = csv(intf["dns"]).filter { isIpv4Literal(it) }
    val allowed = csv(peer["allowedips"]).filterNot { it.contains(':') }
    require(addresses.isNotEmpty()) { "نسخه آزمایشی به Address IPv4 نیاز دارد" }
    require(dns.isNotEmpty()) { "DNS عددی پنل در کانفیگ پیدا نشد" }
    require(allowed.isNotEmpty()) { "مسیر IPv4 در Peer پیدا نشد" }
    val mtu = intf["mtu"]?.toIntOrNull() ?: 1280
    require(mtu in 576..1500) { "MTU کانفیگ نامعتبر است" }
    val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 0
    require(keepalive in 0..65535) { "Keepalive کانفیگ نامعتبر است" }
    return WireGuardCompatConfig(
        privateKey = intf["privatekey"].orEmpty().also { require(it.isNotBlank()) { "PrivateKey پیدا نشد" } },
        addresses = addresses,
        dnsServers = dns,
        mtu = mtu,
        peerPublicKey = peer["publickey"].orEmpty().also { require(it.isNotBlank()) { "PublicKey پیدا نشد" } },
        peerPresharedKey = peer["presharedkey"]?.takeIf(String::isNotBlank),
        endpoint = peer["endpoint"].orEmpty().also { require(it.isNotBlank()) { "Endpoint پیدا نشد" } },
        allowedIps = allowed,
        persistentKeepalive = keepalive,
    )
}

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.substringBefore('/').split('.')
    return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
}

private fun splitEndpoint(value: String): Pair<String, Int> {
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        require(end > 1 && value.getOrNull(end + 1) == ':') { "Endpoint نامعتبر است" }
        return value.substring(1, end) to value.substring(end + 2).toInt()
    }
    val split = value.lastIndexOf(':')
    require(split > 0) { "Endpoint نامعتبر است" }
    return value.substring(0, split) to value.substring(split + 1).toInt()
}

private fun resolveEndpointBeforeTunnel(host: String): String {
    if (isIpv4Literal(host)) return host
    return InetAddress.getAllByName(host)
        .firstOrNull { it is Inet4Address }
        ?.hostAddress
        ?: error("آدرس IPv4 سرور اتصال پیدا نشد: $host")
}

internal fun buildLibboxWireGuardConfig(config: WireGuardCompatConfig, packageName: String): String {
    val (endpointHost, port) = splitEndpoint(config.endpoint)
    require(port in 1..65535) { "پورت Endpoint نامعتبر است" }
    // Resolve the peer hostname before CommandServer/TUN starts. Otherwise the
    // endpoint lookup is routed through wg-game itself and deadlocks because
    // WireGuard is not ready until that lookup succeeds.
    val server = resolveEndpointBeforeTunnel(endpointHost)

    val peer = JSONObject()
        .put("address", server)
        .put("port", port)
        .put("public_key", config.peerPublicKey)
        .put("allowed_ips", JSONArray(config.allowedIps))
        .put("persistent_keepalive_interval", config.persistentKeepalive)
    config.peerPresharedKey?.let { peer.put("pre_shared_key", it) }

    val endpoint = JSONObject()
        .put("type", "wireguard")
        .put("tag", "wg-game")
        .put("system", false)
        .put("mtu", config.mtu)
        .put("address", JSONArray(config.addresses))
        .put("private_key", config.privateKey)
        .put("peers", JSONArray().put(peer))
        .put("udp_timeout", "10m")
        .put("workers", 2)

    val tun = JSONObject()
        .put("type", "tun")
        .put("tag", "game-tun")
        .put("address", JSONArray().put("172.19.0.1/30"))
        .put("mtu", minOf(config.mtu, 1280))
        .put("auto_route", true)
        .put("stack", "mixed")
        .put("endpoint_independent_nat", false)
        .put("udp_timeout", "10m")
        .put("include_package", JSONArray().put(packageName))

    val panelDns = JSONObject()
        .put("type", "udp")
        .put("tag", "panel-dns")
        .put("server", config.dnsServers.first())
        .put("server_port", 53)
        .put("detour", "wg-game")

    return JSONObject()
        .put("log", JSONObject().put("level", "info").put("timestamp", true))
        .put("dns", JSONObject().put("servers", JSONArray().put(panelDns)).put("final", "panel-dns"))
        .put("inbounds", JSONArray().put(tun))
        .put("endpoints", JSONArray().put(endpoint))
        .put("route", JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "wg-game")
            .put("rules", JSONArray().put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))))
        .toString()
}
