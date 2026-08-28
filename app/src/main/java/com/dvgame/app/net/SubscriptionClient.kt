package com.dvgame.app.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SubscriptionResponse(
    val account: AccountInfo,
    val games: List<GameInfo>,
    val configs: List<ConfigInfo>,
)

data class AccountInfo(
    val name: String,
    val state: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val expiryMs: Long?,
) {
    val isActive: Boolean get() = state.equals("active", true) && (expiryMs == null || System.currentTimeMillis() < expiryMs)
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
    val remainingDays: Int? get() = expiryMs?.let { ((it - System.currentTimeMillis()) / 86400000L).toInt() }?.coerceAtLeast(0)
}

data class GameInfo(
    val id: String,
    val name: String,
    val packages: List<String>,
    val enabled: Boolean,
)

data class ConfigInfo(
    val id: String,
    val name: String,
    val location: String,
    val config: String,
)

object SubscriptionClient {
    private const val MAX_RESPONSE = 1048576

    fun fetch(url: String): SubscriptionResponse {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "لینک اشتراک باید با http:// یا https:// شروع شود"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.dvgame.subscription+json, application/json")
            setRequestProperty("User-Agent", "DV-Game/0.2")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("خطای سرور: $code")
            val body = conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                var total = 0
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE) error("پاسخ سرور بیش از حد بزرگ است")
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): SubscriptionResponse {
        val obj = JSONObject(raw)
        val acc = obj.optJSONObject("account")
        val account = if (acc != null) AccountInfo(
            name = acc.optString("name", ""),
            state = acc.optString("state", "active"),
            usedBytes = acc.optLong("usedBytes"),
            totalBytes = acc.optLong("totalBytes"),
            expiryMs = if (acc.has("expiryMs") && !acc.isNull("expiryMs")) acc.optLong("expiryMs") else null,
        ) else AccountInfo("", "active", 0, 0, null)
        val games = mutableListOf<GameInfo>()
        val catalog = obj.optJSONObject("catalog")
        if (catalog != null) {
            val arr = catalog.optJSONArray("games")
            if (arr != null) for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val pkgs = mutableListOf<String>()
                val pkgArr = g.optJSONArray("packages")
                if (pkgArr != null) for (j in 0 until pkgArr.length()) pkgs.add(pkgArr.getString(j))
                games.add(GameInfo(g.optString("id"), g.optString("name"), pkgs, g.optBoolean("enabled", true)))
            }
        }
        val configs = mutableListOf<ConfigInfo>()
        val cfgArr = obj.optJSONArray("configs")
        if (cfgArr != null) for (i in 0 until cfgArr.length()) {
            val c = cfgArr.getJSONObject(i)
            configs.add(ConfigInfo(c.optString("id"), c.optString("name"), c.optString("location"), c.optString("config")))
        }
        require(configs.isNotEmpty()) { "کانفیگی در پاسخ سرور پیدا نشد" }
        return SubscriptionResponse(account, games, configs)
    }
}
