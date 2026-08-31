package com.dvgame.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val hash = "a".repeat(64)
    private val signature = "b".repeat(64)

    private fun payload(
        versionCode: Int = 15,
        packageName: String = "com.dvgame.app",
        sha256: String = hash,
        primaryUrl: String = "https://github.com/dalmaktube-creator/dv-game-android/releases/download/v0.2.0-alpha.12/app.apk",
        mirrorUrl: String = "https://mirror.example.org/dv-game/app.apk",
    ): String = """
        {"versionCode":$versionCode,"versionName":"0.2.0-alpha12","packageName":"$packageName",
         "sha256":"$sha256","signatureSha256":"$signature",
         "primaryUrl":"$primaryUrl","mirrorUrl":"$mirrorUrl","notes":"test"}
    """.trimIndent()

    @Test fun parsesManifestAndPrefersGithubBeforeMirror() {
        val manifest = UpdateManifest.parse(payload())
        assertEquals(15, manifest.versionCode)
        assertEquals(listOf(manifest.primaryUrl, manifest.mirrorUrl), manifest.downloadOrder())
        assertTrue(manifest.primaryUrl.startsWith("https://github.com/"))
    }

    @Test fun onlyAcceptsHigherVersionCode() {
        val manifest = UpdateManifest.parse(payload(versionCode = 15))
        assertTrue(manifest.isNewerThan(14))
        assertFalse(manifest.isNewerThan(15))
        assertFalse(manifest.isNewerThan(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignPackage() { UpdateManifest.parse(payload(packageName = "com.other.app")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHash() { UpdateManifest.parse(payload(sha256 = "zz")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecurePrimaryUrl() { UpdateManifest.parse(payload(primaryUrl = "http://example.org/app.apk")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureMirrorUrl() { UpdateManifest.parse(payload(mirrorUrl = "http://mirror.example.org/app.apk")) }
}
