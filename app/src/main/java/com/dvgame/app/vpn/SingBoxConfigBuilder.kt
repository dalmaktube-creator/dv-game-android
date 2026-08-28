package com.dvgame.app.vpn

import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {
    data class ParsedConfig(
        val privateKey: String,
        val addresses: List<String>,
        val dnsServers: List<String>,
        val mtu: Int,
        val peerPublicKey: String,
        val peerEndpoint: String,
        val peerEndpointPort: Int,
        val peerAllowedIps: List<String>,
        val persistentKeepalive: Int?,
        val presharedKey: String?,
    )

    fun build(wireGuardConfig: String): String {
        val parsed = parseWireGuardConfig(wireGuardConfig)
        return buildSingBoxJson(parsed)
    }

    private fun parseWireGuardConfig(raw: String): ParsedConfig {
        var privateKey = ""
        var addresses = listOf<String>()
        var dnsServers = listOf<String>()
        var mtu = 1280
        var peerPublicKey = ""
        var peerEndpoint = ""
        var peerEndpointPort = 51820
        var peerAllowedIps = listOf("0.0.0.0/0")
        var persistentKeepalive: Int? = null
        var presharedKey: String? = null

        var section = ""
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.lowercase()
                continue
            }
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val key = trimmed.substring(0, eq).trim().lowercase()
            val value = trimmed.substring(eq + 1).trim()

            when (section) {
                "[interface]" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    "dns" -> dnsServers = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> mtu = value.toIntOrNull() ?: 1280
                }
                "[peer]" -> when (key) {
                    "publickey" -> peerPublicKey = value
                    "endpoint" -> {
                        val lastColon = value.lastIndexOf(':')
                        if (lastColon > 0) {
                            peerEndpoint = value.substring(0, lastColon)
                            peerEndpointPort = value.substring(lastColon + 1).toIntOrNull() ?: 51820
                        } else {
                            peerEndpoint = value
                        }
                    }
                    "allowedips" -> peerAllowedIps = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    "persistentkeepalive" -> persistentKeepalive = value.toIntOrNull()
                    "presharedkey" -> presharedKey = value
                }
            }
        }

        require(privateKey.isNotEmpty()) { "Private Key در کانفیگ پیدا نشد" }
        require(peerPublicKey.isNotEmpty()) { "Public Key در کانفیگ پیدا نشد" }
        require(peerEndpoint.isNotEmpty()) { "Endpoint در کانفیگ پیدا نشد" }
        require(addresses.isNotEmpty()) { "Address در کانفیگ پیدا نشد" }

        return ParsedConfig(
            privateKey, addresses, dnsServers, mtu,
            peerPublicKey, peerEndpoint, peerEndpointPort,
            peerAllowedIps, persistentKeepalive, presharedKey
        )
    }

    private fun buildSingBoxJson(config: ParsedConfig): String {
        val inet4 = config.addresses.find { it.contains(".") && !it.contains(":") }
        val inet6 = config.addresses.find { it.contains(":") }
        val dns = if (config.dnsServers.isNotEmpty()) config.dnsServers else listOf("1.1.1.1")

        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "dv-game")
            if (inet4 != null) put("inet4_address", inet4)
            if (inet6 != null) put("inet6_address", inet6)
            put("mtu", config.mtu)
            put("auto_route", true)
            put("stack", "mixed")
            put("endpoint_independent_nat", true)
        }

        val peer = JSONObject().apply {
            put("server", config.peerEndpoint)
            put("server_port", config.peerEndpointPort)
            put("public_key", config.peerPublicKey)
            put("allowed_ips", JSONArray(config.peerAllowedIps))
            config.persistentKeepalive?.let { put("persistent_keepalive_interval", it) }
            config.presharedKey?.let { put("preshared_key", it) }
        }

        val wgOutbound = JSONObject().apply {
            put("type", "wireguard")
            put("tag", "wg-out")
            put("local_address", JSONArray(config.addresses))
            put("private_key", config.privateKey)
            put("peers", JSONArray().put(peer))
        }

        val root = JSONObject().apply {
            put("log", JSONObject().apply { put("level", "warn") })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    dns.forEachIndexed { i, server ->
                        put(JSONObject().apply {
                            put("type", "udp")
                            put("tag", "dns-$i")
                            put("server", server)
                        })
                    }
                })
                put("final", "dns-0")
            })
            put("inbounds", JSONArray().put(tunInbound))
            put("outbounds", JSONArray().apply {
                put(wgOutbound)
                put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
                put(JSONObject().apply { put("type", "dns"); put("tag", "dns-out") })
            })
            put("route", JSONObject().apply {
                put("rules", JSONArray().put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                }))
                put("final", "wg-out")
                put("auto_detect_interface", true)
            })
        }

        return root.toString(2)
    }
}
