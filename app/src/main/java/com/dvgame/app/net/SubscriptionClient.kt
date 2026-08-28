package com.dvgame.app.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SubscriptionClient {
    fun fetch(url: String): String {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "لینک اشتراک باید با http:// یا https:// شروع شود"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/plain, application/json")
        connection.setRequestProperty("User-Agent", "DV-Game/0.1")
        return connection.inputStream.bufferedReader().use { reader ->
            decodePayload(reader.readText().trim())
        }.also { connection.disconnect() }
    }

    internal fun decodePayload(raw: String): String {
        if (raw.contains("[Interface]")) return raw

        runCatching {
            val obj = JSONObject(raw)
            listOf("config", "wireguard", "wgConfig").forEach { key ->
                obj.optString(key).takeIf { it.contains("[Interface]") }?.let { return it }
            }
            val configs = obj.optJSONArray("configs")
            if (configs != null) findConfig(configs)?.let { return it }
        }

        listOf(Base64.DEFAULT, Base64.URL_SAFE).forEach { flags ->
            runCatching {
                val decoded = String(Base64.decode(raw, flags), Charsets.UTF_8)
                if (decoded.contains("[Interface]")) return decoded
            }
        }
        error("پاسخ اشتراک شامل کانفیگ WireGuard قابل استفاده نیست")
    }

    private fun findConfig(array: JSONArray): String? {
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.contains("[Interface]")) return value
        }
        return null
    }
}
