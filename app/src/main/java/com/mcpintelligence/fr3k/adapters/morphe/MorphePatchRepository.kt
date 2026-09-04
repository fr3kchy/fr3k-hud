package com.mcpintelligence.fr3k.adapters.morphe

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Morphe-style companion patch repository (§35).
 *
 * A patch is a small JSON document that:
 *  - targets a specific supported version of an APK (fingerprint-based, per the brief)
 *  - declares the menu items it injects ("Send to FR3K", "Ask FR3K", "Clean URL")
 *  - carries stable integration points (well-known resource IDs / activities)
 *
 * Patches are shipped as a directory of `*.json` files (see `examples/patches/`).
 * Each patch is verified against a fingerprint before being offered as an option.
 * FR3K never silently mutates installed apps — the user always confirms.
 *
 * NOTE: this is the *architecture* and *verifier*. Generating actual APK
 * patches requires the apkzlib toolchain and is out of scope for V1 —
 * FR3K HUD reads + lists Morphe-style patches; the build pipeline produces them.
 */
class MorphePatchRepository {

    data class Patch(
        val id: String,
        val name: String,
        val description: String,
        val targetPackage: String,
        val supportedVersions: List<String>,
        val fingerprint: String,
        val menuItems: List<String>,
        val sourcePath: String? = null,
    )

    /** JSON schema (relaxed — every field is optional except id+name+target_package):
     * {
     *   "id": "browser-url-clean",
     *   "name": "Browser URL Cleaner",
     *   "description": "Adds 'Send to FR3K → Clean URL' to Chrome share sheet",
     *   "target_package": "com.android.chrome",
     *   "supported_versions": ["120.0.6099.144", "121.0.6167.143"],
     *   "fingerprint": "sha256:ab12...",
     *   "menu_items": ["Send to FR3K", "Clean URL"]
     * }
     */
    fun loadFromJson(raw: String, sourcePath: String? = null): Patch {
        val j = JSONObject(raw)
        return Patch(
            id = j.getString("id"),
            name = j.getString("name"),
            description = j.optString("description", ""),
            targetPackage = j.getString("target_package"),
            supportedVersions = j.optJSONArray("supported_versions")?.toStringList() ?: emptyList(),
            fingerprint = j.optString("fingerprint", ""),
            menuItems = j.optJSONArray("menu_items")?.toStringList() ?: emptyList(),
            sourcePath = sourcePath,
        )
    }

    fun loadAll(rawList: List<String>): List<Patch> = rawList.mapIndexed { i, raw -> loadFromJson(raw, "raw:$i") }

    /**
     * Load every `*.json` patch file from the given directory. Each patch
     * is parsed and returned. Bad files are logged and skipped — never
     * abort the whole load because one patch is malformed.
     */
    fun loadAllFromDirectory(dir: File): List<Patch> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { loadFromJson(f.readText(), f.absolutePath) }
                    .onFailure { Log.w(TAG, "bad patch ${f.name}: ${it.message}") }
                    .getOrNull()
            } ?: emptyList()
    }

    /**
     * Convenience: read the FR3K-shipped patches from the app's `patches/`
     * directory (shipped as raw assets) and from any user-imported patches
     * in the app-private files dir.
     */
    fun loadAllAvailable(context: Context): List<Patch> {
        val shipped = try {
            context.assets.list("patches")
                ?.filter { it.endsWith(".json") }
                ?.mapNotNull { name ->
                    runCatching { context.assets.open("patches/$name").bufferedReader().readText() }
                        .map { loadFromJson(it, "asset:patches/$name") }
                        .getOrNull()
                }
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        val userDir = File(context.filesDir, "patches")
        val userLoaded = loadAllFromDirectory(userDir)
        return shipped + userLoaded
    }

    fun verify(patch: Patch, actualFingerprint: String): Boolean =
        patch.fingerprint.isEmpty() || patch.fingerprint == actualFingerprint

    fun matches(patch: Patch, installedVersion: String): Boolean =
        patch.supportedVersions.isEmpty() || installedVersion in patch.supportedVersions

    /**
     * Compute the SHA-256 fingerprint of an APK file on disk. Returns
     * `sha256:<hex>`. Used to verify a patch is targeting the exact APK
     * version the user has installed.
     */
    fun fingerprint(apk: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }.onFailure { Log.w(TAG, "fingerprint failed: ${it.message}") }.getOrNull()

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    companion object { private const val TAG = "FR3K.morphe" }
}
