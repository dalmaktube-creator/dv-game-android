package com.dvgame.app.vpn

import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
    Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    var section = ""
    val interfaces = mutableListOf<MutableMap<String, String>>()
    val peers = mutableListOf<MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null
    raw.lineSequence().forEach { source ->
        val line = source.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.lowercase()
            current = when (section) {
                "[interface]" -> mutableMapOf<String, String>().also(interfaces::add)
                "[peer]" -> mutableMapOf<String, String>().also(peers::add)
                else -> null
            }
            return@forEach
        }
        val split = line.indexOf('=')
        if (split <= 0 || current == null) return@forEach
        current!![line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
    }
    require(interfaces.size == 1) { "کانفیگ باید دقیقاً یک Interface داشته باشد" }
    require(peers.size == 1) { "موتور سازگار فقط کانفیگ تک Peer پنل را می‌پذیرد" }
    val intf = interfaces.single()
    val peer = peers.single()
    fun csv(value: String?): List<String> = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    val addresses = csv(intf["address"])
    val dns = csv(intf["dns"]).filter { isIpLiteral(it) }
    val allowed = csv(peer["allowedips"])
    require(addresses.isNotEmpty()) { "Address کانفیگ پیدا نشد" }
    require(addresses.any { !it.contains(':') }) { "نسخه آزمایشی به Address IPv4 نیاز دارد" }
    require(dns.isNotEmpty()) { "DNS عددی پنل در کانفیگ پیدا نشد" }
    require(allowed.any { !it.contains(':') }) { "مسیر IPv4 در Peer پیدا نشد" }
    val mtu = intf["mtu"]?.toIntOrNull() ?: 1280
    require(mtu in 576..1500) { "MTU کانفیگ نامعتبر است" }
    val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 0
    require(keepalive in 0..65535) { "Keepalive کانفیگ نامعتبر است" }
    return WireGuardCompatConfig(
        privateKey = intf["privatekey"].orEmpty().also { require(it.isNotBlank()) { "PrivateKey پیدا نشد" } },
        addresses = addresses.filterNot { it.contains(':') },
        dnsServers = dns.filterNot { it.contains(':') },
        mtu = mtu,
        peerPublicKey = peer["publickey"].orEmpty().also { require(it.isNotBlank()) { "PublicKey پیدا نشد" } },
        peerPresharedKey = peer["presharedkey"]?.takeIf(String::isNotBlank),
        endpoint = peer["endpoint"].orEmpty().also { require(it.isNotBlank()) { "Endpoint پیدا نشد" } },
        allowedIps = allowed.filterNot { it.contains(':') },
        persistentKeepalive = keepalive,
    )
}

private fun isIpLiteral(value: String): Boolean {
    val host = value.substringBefore('%').substringBefore('/')
    return host.contains(':') || (host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 })
}

internal fun buildXrayWireGuardConfig(config: WireGuardCompatConfig): String {
    val peer = JSONObject()
        .put("publicKey", config.peerPublicKey)
        .put("endpoint", config.endpoint)
        .put("keepAlive", config.persistentKeepalive)
        .put("allowedIPs", JSONArray(config.allowedIps))
    config.peerPresharedKey?.let { peer.put("preSharedKey", it) }
    val wgSettings = JSONObject()
        .put("secretKey", config.privateKey)
        .put("address", JSONArray(config.addresses))
        .put("peers", JSONArray().put(peer))
        .put("mtu", config.mtu)
        .put("domainStrategy", "ForceIPv4")
        .put("remoteDNS", JSONArray(config.dnsServers))
        .put("noKernelTun", true)
    val socksInbound = JSONObject()
        .put("tag", "game-tun")
        .put("listen", "127.0.0.1")
        .put("port", 10808)
        .put("protocol", "socks")
        .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
        .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
    val outbound = JSONObject()
        .put("tag", "wireguard-game")
        .put("protocol", "wireguard")
        .put("settings", wgSettings)
    return JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("policy", JSONObject()
            .put("levels", JSONObject().put("8", JSONObject()
                .put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1)))
            .put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
        .put("inbounds", JSONArray().put(socksInbound))
        .put("outbounds", JSONArray().put(outbound))
        .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
        .toString()
}
