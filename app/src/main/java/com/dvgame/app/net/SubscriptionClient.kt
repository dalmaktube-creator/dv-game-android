package com.dvgame.app.net

import com.dvgame.app.model.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

object SubscriptionClient {
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_REDIRECTS = 3
    private const val MAX_GAMES = 500
    private const val MAX_PACKAGES_PER_GAME = 16
    private const val MAX_CONFIGS = 128
    private const val MAX_CONFIG_BYTES = 128 * 1024
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun fetch(input: String): DvSubscription {
        var uri = normalize(input)
        val originalHost = uri.host.lowercase()
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
                    check(next.scheme.equals("https", true)) { "تغییر مسیر ناامن HTTP پذیرفته نمی‌شود" }
                    check(next.host?.lowercase() == originalHost) { "تغییر مسیر لینک به میزبان دیگری مجاز نیست" }
                    check(next.userInfo == null) { "لینک دارای اطلاعات کاربری مجاز نیست" }
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
        require(original.scheme.equals("https", true)) { "برای حفاظت از کلیدها، لینک باید HTTPS باشد" }
        require(!original.host.isNullOrBlank()) { "آدرس لینک معتبر نیست" }
        require(original.userInfo == null) { "لینک دارای اطلاعات کاربری مجاز نیست" }
        val query = original.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() && !it.substringBefore('=').equals("format", true) }
            .toMutableList()
        query += "format=dvgame"
        return URI("https", null, original.host, original.port, original.path, query.joinToString("&"), null)
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
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "پاسخ اشتراک بیش از حد بزرگ است" }
        val root = JSONObject(raw)
        val version = root.getInt("apiVersion")
        require(version == 1) { "نسخه API پنل پشتیبانی نمی‌شود" }

        val accountJson = root.getJSONObject("account")
        val state = accountJson.getString("state").trim().lowercase()
        val usedBytes = accountJson.getLong("usedBytes")
        val totalBytes = accountJson.getLong("totalBytes")
        val expiryMs = accountJson.optLong("expiryMs").takeIf { !accountJson.isNull("expiryMs") }
        require(state.isNotBlank() && state.length <= 32) { "وضعیت اشتراک نامعتبر است" }
        require(usedBytes >= 0 && totalBytes >= 0) { "اطلاعات حجم اشتراک نامعتبر است" }
        require(expiryMs == null || expiryMs > 0) { "تاریخ انقضای اشتراک نامعتبر است" }
        val account = AccountInfo(accountJson.optString("name").take(128), state, usedBytes, totalBytes, expiryMs)

        val catalog = root.getJSONObject("catalog")
        val gamesJson = catalog.getJSONArray("games")
        require(gamesJson.length() <= MAX_GAMES) { "تعداد بازی‌های کاتالوگ بیش از حد است" }
        val seenIds = hashSetOf<String>()
        val seenPackages = hashSetOf<String>()
        val games = buildList {
            for (i in 0 until gamesJson.length()) {
                val item = gamesJson.getJSONObject(i)
                val id = item.getString("id").trim()
                val name = item.getString("name").trim()
                require(id.matches(Regex("^[a-z0-9][a-z0-9_-]{0,63}$")) && seenIds.add(id)) { "شناسه بازی نامعتبر یا تکراری است" }
                require(name.isNotBlank() && name.length <= 128) { "نام بازی نامعتبر است" }
                val packagesJson = item.getJSONArray("packages")
                require(packagesJson.length() in 1..MAX_PACKAGES_PER_GAME) { "تعداد پکیج‌های بازی نامعتبر است" }
                val packages = buildList {
                    for (p in 0 until packagesJson.length()) {
                        val packageName = packagesJson.getString(p).trim()
                        require(packagePattern.matches(packageName)) { "نام پکیج بازی نامعتبر است" }
                        require(seenPackages.add(packageName)) { "پکیج بازی تکراری است" }
                        add(packageName)
                    }
                }
                add(ApprovedGame(id, name, packages))
            }
        }

        val configsJson = root.getJSONArray("configs")
        require(configsJson.length() in 1..MAX_CONFIGS) { "تعداد کانفیگ‌های اشتراک نامعتبر است" }
        val seenProfileIds = hashSetOf<String>()
        val profiles = buildList {
            for (i in 0 until configsJson.length()) {
                val item = configsJson.getJSONObject(i)
                val id = item.getString("id").trim()
                val name = item.getString("name").trim()
                val config = item.getString("config")
                require(id.isNotBlank() && id.length <= 128 && seenProfileIds.add(id)) { "شناسه کانفیگ نامعتبر یا تکراری است" }
                require(name.isNotBlank() && name.length <= 128) { "نام کانفیگ نامعتبر است" }
                require(config.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "کانفیگ بیش از حد بزرگ است" }
                require(config.lineSequence().any { it.trim().equals("[Interface]", true) } &&
                    config.lineSequence().any { it.trim().equals("[Peer]", true) }) { "کانفیگ ناقص است" }
                add(ServerProfile(id, name, item.optString("location").take(128), config))
            }
        }
        return DvSubscription(version, account, catalog.getInt("version"), catalog.getString("digest").take(256), games, profiles)
    }
}
