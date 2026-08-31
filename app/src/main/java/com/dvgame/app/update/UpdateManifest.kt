package com.dvgame.app.update

import org.json.JSONObject

/**
 * Update manifest published beside the APK.
 * Gaming servers never host the APK; only GitHub or an independent mirror do.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val packageName: String,
    val sha256: String,
    val signatureSha256: String,
    val primaryUrl: String,
    val mirrorUrl: String?,
    val notes: String,
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    fun downloadOrder(): List<String> = listOfNotNull(primaryUrl, mirrorUrl)

    companion object {
        const val EXPECTED_PACKAGE = "com.dvgame.app"

        fun parse(raw: String): UpdateManifest {
            val json = JSONObject(raw)
            val manifest = UpdateManifest(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName").trim(),
                packageName = json.optString("packageName").trim(),
                sha256 = json.optString("sha256").trim().lowercase(),
                signatureSha256 = json.optString("signatureSha256").trim().lowercase(),
                primaryUrl = json.optString("primaryUrl").trim(),
                mirrorUrl = json.optString("mirrorUrl").trim().ifBlank { null },
                notes = json.optString("notes").trim(),
            )
            require(manifest.versionCode > 0) { "شماره نسخه نامعتبر است" }
            require(manifest.versionName.isNotBlank()) { "نام نسخه نامعتبر است" }
            require(manifest.packageName == EXPECTED_PACKAGE) { "فایل نصبی متعلق به این برنامه نیست" }
            require(isHex64(manifest.sha256)) { "هش فایل نصبی نامعتبر است" }
            require(isHex64(manifest.signatureSha256)) { "اثر انگشت امضا نامعتبر است" }
            require(isHttps(manifest.primaryUrl)) { "نشانی دانلود باید HTTPS باشد" }
            manifest.mirrorUrl?.let { require(isHttps(it)) { "نشانی جایگزین باید HTTPS باشد" } }
            return manifest
        }

        fun isHex64(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        fun isHttps(value: String): Boolean = value.startsWith("https://", ignoreCase = true)
    }
}
