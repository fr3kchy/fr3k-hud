package com.mcpintelligence.fr3k.core

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

/**
 * Per-device identity. Created once on first launch and persisted to encrypted storage.
 *
 * The device id is a stable UUID-derived string ("fr3k-xxxxxxxx-xxxx"). It is the
 * envelope `source` field on every outbound message and is used for fleet inventory.
 *
 * The signing keypair is held in memory only on V1 (Ed25519 backing in V3).
 * Public key fingerprint travels in metadata.
 */
class DeviceIdentity(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "fr3k_identity",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val deviceId: String by lazy { loadOrCreateDeviceId() }

    val androidId: String by lazy {
        @Suppress("HardwareIds")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    val platform: String = "android"

    val appVersion: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }

    private fun loadOrCreateDeviceId(): String {
        val existing = encryptedPrefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val generated = "fr3k-${UUID.randomUUID().toString().take(18)}"
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(deviceId.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}