package com.dvgame.app.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64

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
    require(raw.isNotBlank()) { "کانفیگ خالی است" }
    require(raw.length <= 512 * 1024) { "کانفیگ بیش از حد بزرگ است" }

    val interfaces = mutableListOf<MutableMap<String, String>>()
    val peers = mutableListOf<MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null
    raw.lineSequence().forEachIndexed { index, source ->
        require(source.length <= 8 * 1024) { "خط ${index + 1} کانفیگ بیش از حد بلند است" }
        val line = source.substringBefore('#').trim()
        if (line.isEmpty()) return@forEachIndexed
        if (line.startsWith("[") && line.endsWith("]")) {
            current = when (line.lowercase()) {
                "[interface]" -> mutableMapOf<String, String>().also(interfaces::add)
                "[peer]" -> mutableMapOf<String, String>().also(peers::add)
                else -> throw IllegalArgumentException("بخش ناشناخته در کانفیگ: $line")
            }
            return@forEachIndexed
        }
        val split = line.indexOf('=')
        require(split > 0 && current != null) { "ساختار خط ${index + 1} کانفیگ نامعتبر است" }
        val key = line.substring(0, split).trim().lowercase()
        val value = line.substring(split + 1).trim()
        if (key in setOf("includedapplications", "excludedapplications")) return@forEachIndexed
        require(current!!.put(key, value) == null) { "کلید تکراری در کانفیگ: $key" }
    }

    require(interfaces.size == 1) { "کانفیگ باید دقیقاً یک Interface داشته باشد" }
    require(peers.size == 1) { "موتور بازی فقط کانفیگ تک Peer پنل را می‌پذیرد" }
    val intf = interfaces.single()
    val peer = peers.single()
    fun csv(value: String?): List<String> = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    val privateKey = requireWireGuardKey(intf["privatekey"], "PrivateKey")
    val publicKey = requireWireGuardKey(peer["publickey"], "PublicKey")
    val presharedKey = peer["presharedkey"]?.takeIf(String::isNotBlank)?.let { requireWireGuardKey(it, "PresharedKey") }
    val addresses = csv(intf["address"]).filterNot { it.contains(':') }
    val dns = csv(intf["dns"]).filter { isIpv4Literal(it) }
    val allowed = csv(peer["allowedips"]).filterNot { it.contains(':') }
    require(addresses.isNotEmpty() && addresses.all { isIpv4Cidr(it, hostPrefixRequired = true) }) {
        "نسخه آزمایشی به Address معتبر IPv4 نیاز دارد"
    }
    require(dns.isNotEmpty()) { "DNS عددی پنل در کانفیگ پیدا نشد" }
    require(allowed.isNotEmpty() && allowed.all { isIpv4Cidr(it, hostPrefixRequired = false) }) {
        "مسیر IPv4 معتبر در Peer پیدا نشد"
    }

    val mtu = intf["mtu"]?.toIntOrNull() ?: 1280
    require(mtu in 576..1500) { "MTU کانفیگ نامعتبر است" }
    val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 0
    require(keepalive in 0..65535) { "Keepalive کانفیگ نامعتبر است" }
    val endpoint = peer["endpoint"].orEmpty().also {
        require(it.isNotBlank()) { "Endpoint پیدا نشد" }
        val (host, port) = splitEndpoint(it)
        require(port in 1..65535) { "پورت Endpoint نامعتبر است" }
        requireValidEndpointHost(host)
    }

    return WireGuardCompatConfig(
        privateKey = privateKey,
        addresses = addresses,
        dnsServers = dns,
        mtu = mtu,
        peerPublicKey = publicKey,
        peerPresharedKey = presharedKey,
        endpoint = endpoint,
        allowedIps = allowed,
        persistentKeepalive = keepalive,
    )
}

private fun requireWireGuardKey(value: String?, name: String): String {
    val key = value.orEmpty()
    require(key.isNotBlank()) { "$name پیدا نشد" }
    val decoded = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
    require(decoded?.size == 32) { "$name نامعتبر است" }
    return key
}

internal fun isIpv4Literal(value: String): Boolean {
    val parts = value.substringBefore('/').split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && (part.length == 1 || !part.startsWith('0')) && part.toIntOrNull() in 0..255
    }
}

private fun isIpv4Cidr(value: String, hostPrefixRequired: Boolean): Boolean {
    val pieces = value.split('/')
    if (pieces.size !in 1..2 || !isIpv4Literal(pieces[0])) return false
    if (hostPrefixRequired && pieces.size != 2) return false
    return pieces.size == 1 || pieces[1].toIntOrNull() in 0..32
}

internal fun splitEndpoint(value: String): Pair<String, Int> {
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        require(end > 1 && value.getOrNull(end + 1) == ':') { "Endpoint نامعتبر است" }
        return value.substring(1, end) to value.substring(end + 2).toIntOrNull().let { it ?: error("پورت Endpoint نامعتبر است") }
    }
    val split = value.lastIndexOf(':')
    require(split > 0) { "Endpoint نامعتبر است" }
    return value.substring(0, split) to (value.substring(split + 1).toIntOrNull() ?: error("پورت Endpoint نامعتبر است"))
}

private fun requireValidEndpointHost(host: String) {
    require(!host.contains(':')) { "Endpoint نسخه آزمایشی باید IPv4 یا دامنه باشد" }
    if (isIpv4Literal(host)) return
    val ascii = runCatching { IDN.toASCII(host) }.getOrNull().orEmpty()
    require(ascii.length in 1..253 && ascii.split('.').all { label ->
        label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }) { "دامنه Endpoint نامعتبر است" }
}

internal fun resolveEndpointIpv4Candidates(
    config: WireGuardCompatConfig,
    lookup: (String) -> Array<InetAddress>,
): List<String> {
    val (host, _) = splitEndpoint(config.endpoint)
    if (isIpv4Literal(host)) return listOf(host)
    val values = lookup(host).asSequence()
        .filterIsInstance<Inet4Address>()
        .mapNotNull(InetAddress::getHostAddress)
        .filter(::isIpv4Literal)
        .distinct()
        .toList()
    require(values.isNotEmpty()) { "آدرس IPv4 سرور اتصال پیدا نشد: $host" }
    return values
}

internal fun buildLibboxWireGuardConfig(
    config: WireGuardCompatConfig,
    packageName: String,
    resolvedEndpointAddress: String = splitEndpoint(config.endpoint).first,
    keepaliveSeconds: Int = config.persistentKeepalive,
    packetPath: PacketPathOptions = PacketPathOptions(),
): String {
    val (_, port) = splitEndpoint(config.endpoint)
    require(port in 1..65535) { "پورت Endpoint نامعتبر است" }
    require(isIpv4Literal(resolvedEndpointAddress)) { "آدرس Peer باید IPv4 عددی باشد" }
    require(keepaliveSeconds in 1..65535) { "Keepalive مؤثر نامعتبر است" }
    val tunMtu = minOf(config.mtu, packetPath.tunMtuCeiling)
    require(tunMtu <= config.mtu) { "MTU رابط TUN نباید از MTU Endpoint بیشتر باشد" }

    val peer = JSONObject()
        .put("address", resolvedEndpointAddress)
        .put("port", port)
        .put("public_key", config.peerPublicKey)
        .put("allowed_ips", JSONArray(config.allowedIps))
        .put("persistent_keepalive_interval", keepaliveSeconds)
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
        .put("workers", packetPath.workers)

    val tun = JSONObject()
        .put("type", "tun")
        .put("tag", "game-tun")
        .put("address", JSONArray().put("172.19.0.1/30"))
        .put("mtu", tunMtu)
        .put("auto_route", true)
        .put("stack", "mixed")
        .put("endpoint_independent_nat", packetPath.endpointIndependentNat)
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
