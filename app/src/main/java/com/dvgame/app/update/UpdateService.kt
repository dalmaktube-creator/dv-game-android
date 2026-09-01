package com.dvgame.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.dvgame.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Secure update flow: GitHub release first, independent mirror as fallback.
 * The panel and the gaming servers are never used as APK sources.
 */
class UpdateService(private val context: Context) {

    suspend fun check(manifestUrls: List<String>): UpdateManifest = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (url in manifestUrls) {
            runCatching { UpdateManifest.parse(readText(url)) }
                .onSuccess { return@withContext it }
                .onFailure { failures += sourceLabel(url) + ": " + (it.message ?: "خطای ناشناخته") }
        }
        throw IllegalStateException(
            if (failures.isEmpty()) "هیچ منبع به‌روزرسانی معتبری تنظیم نشده است"
            else "بررسی به‌روزرسانی ناموفق بود — " + failures.joinToString(" | ")
        )
    }

    private fun sourceLabel(url: String): String =
        if (url.contains("github.com", true)) "گیت‌هاب" else "نشانی جایگزین"

    suspend fun download(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        require(manifest.isNewerThan(currentVersionCode())) { "برنامه به‌روز است" }
        require(manifest.signatureSha256 == installedSignatureSha256()) { "امضای نسخه تازه با نسخه نصب‌شده یکی نیست؛ برای یک‌بار نسخه فعلی را حذف و نسخه تازه را دستی نصب کنید" }
        val target = File(context.cacheDir, "updates/dv-game-${manifest.versionCode}.apk")
        target.parentFile?.mkdirs()
        var failure: Throwable? = null
        for (url in manifest.downloadOrder()) {
            runCatching {
                downloadTo(url, target)
                require(sha256(target) == manifest.sha256) { "فایل دانلودشده سالم نیست" }
                return@withContext target
            }.onFailure { failure = it; target.delete() }
        }
        throw IllegalStateException(failure?.message ?: "دانلود به‌روزرسانی ناموفق بود")
    }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun installedSignatureSha256(): String {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
    }

    private fun readText(url: String): String {
        val connection = openHttps(url)
        return try {
            connection.inputStream.use { it.readBytes() }.decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTo(url: String, target: File) {
        val connection = openHttps(url)
        try {
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(url: String): HttpURLConnection {
        require(UpdateManifest.isHttps(url)) { "منبع به‌روزرسانی باید HTTPS باشد" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        require(code in 200..299) { "پاسخ نامعتبر سرور به‌روزرسانی: $code" }
        return connection
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
