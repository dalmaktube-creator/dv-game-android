package com.dvgame.app.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredTunnel(val config: String, val packageName: String, val validUntilMs: Long)

internal class SecureTunnelStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_tunnel_v2", Context.MODE_PRIVATE)
    private val alias = "dv_game_tunnel_v2"
    private val aad = "DV-Game:StoredTunnel:v2".toByteArray(Charsets.UTF_8)

    fun save(config: String, packageName: String, validUntilMs: Long) {
        require(validUntilMs > System.currentTimeMillis()) { "مهلت بازیابی اتصال معتبر نیست" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val plain = JSONObject()
            .put("version", 2)
            .put("config", config)
            .put("package", packageName)
            .put("validUntilMs", validUntilMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val saved = preferences.edit()
            .putString("payload", Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(saved) { "ذخیره امن وضعیت اتصال ناموفق بود" }
    }

    fun load(nowMs: Long = System.currentTimeMillis()): StoredTunnel? {
        val stored = runCatching {
            val payloadText = preferences.getString("payload", null) ?: return@runCatching null
            val ivText = preferences.getString("iv", null) ?: return@runCatching null
            val payload = Base64.decode(payloadText, Base64.NO_WRAP)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            require(iv.size == 12) { "بردار رمزگذاری نامعتبر است" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val json = JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
            require(json.getInt("version") == 2) { "نسخه ذخیره اتصال پشتیبانی نمی‌شود" }
            StoredTunnel(json.getString("config"), json.getString("package"), json.getLong("validUntilMs"))
        }.getOrNull()
        if (stored == null || stored.validUntilMs <= nowMs) {
            clear()
            return null
        }
        return stored
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
