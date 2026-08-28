package com.dvgame.app.net

import com.dvgame.app.model.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

object SubscriptionClient {
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_REDIRECTS = 3

    fun fetch(input: String): DvSubscription {
        var uri = normalize(input)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "DV-Game/0.2 Android")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    check(redirect < MAX_REDIRECTS) { "تعداد تغییر مسیرهای لینک بیش از حد است" }
                    val location = connection.getHeaderField("Location") ?: error("تغییر مسیر نامعتبر است")
                    val next = uri.resolve(location)
                    check(next.scheme == "https") { "تغییر مسیر ناامن HTTP پذیرفته نمی‌شود" }
                    uri = next
                    return@repeat
                }
                check(status == 200) { "پاسخ سرور نامعتبر است: $status" }
                val declared = connection.contentLengthLong
                check(declared < 0 || declared <= MAX_BYTES) { "پاسخ اشتراک بیش از حد بزرگ است" }
                return parse(readLimited(connection, MAX_BYTES))
            } finally {
                connection.disconnect()
            }
        }
        error("دریافت اشتراک ناموفق بود")
    }

    private fun normalize(input: String): URI {
        val original = URI(input.trim())
        require(original.scheme == "https") { "برای حفاظت از کلیدها، لینک باید HTTPS باشد" }
        require(!original.host.isNullOrBlank()) { "آدرس لینک معتبر نیست" }
        val query = original.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && !it.startsWith("format=") }.toMutableList()
        query += "format=dvgame"
        return URI(original.scheme, original.userInfo, original.host, original.port, original.path, query.joinToString("&"), null)
    }

    private fun readLimited(connection: HttpURLConnection, limit: Int): String {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= limit) { "پاسخ اشتراک بیش از حد بزرگ است" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    internal fun parse(raw: String): DvSubscription {
        val root = JSONObject(raw)
        val version = root.getInt("apiVersion")
        require(version == 1) { "نسخه API پنل پشتیبانی نمی‌شود" }
        val account = root.getJSONObject("account")
        val catalog = root.getJSONObject("catalog")
        val gamesJson = catalog.getJSONArray("games")
        val games = buildList {
            for (i in 0 until gamesJson.length()) {
                val item = gamesJson.getJSONObject(i)
                val packagesJson = item.getJSONArray("packages")
                val packages = buildList { for (p in 0 until packagesJson.length()) add(packagesJson.getString(p)) }
                add(ApprovedGame(item.getString("id"), item.getString("name"), packages))
            }
        }
        val configsJson = root.getJSONArray("configs")
        val profiles = buildList {
            for (i in 0 until configsJson.length()) {
                val item = configsJson.getJSONObject(i)
                val config = item.getString("config")
                require(config.contains("[Interface]") && config.contains("[Peer]")) { "کانفیگ ناقص است" }
                add(ServerProfile(item.getString("id"), item.getString("name"), item.optString("location"), config))
            }
        }
        require(profiles.isNotEmpty()) { "هیچ کانفیگی برای این اشتراک وجود ندارد" }
        return DvSubscription(
            version,
            AccountInfo(account.optString("name"), account.optString("state"), account.optLong("usedBytes"), account.optLong("totalBytes"), account.optLong("expiryMs").takeIf { !account.isNull("expiryMs") }),
            catalog.getInt("version"), catalog.getString("digest"), games, profiles,
        )
    }
}
