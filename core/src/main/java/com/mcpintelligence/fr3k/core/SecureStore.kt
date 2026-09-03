package com.mcpintelligence.fr3k.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted preferences wrapper for tokens, API credentials, SSH keys references.
 * Production logs MUST scrub these values before write.
 */
class SecureStore(context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "fr3k_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun getOrDefault(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}