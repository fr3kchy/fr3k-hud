package com.mcpintelligence.fr3k.adapters.morphe

import org.json.JSONArray
import org.json.JSONObject

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
    )

    fun loadFromJson(raw: String): Patch {
        val j = JSONObject(raw)
        return Patch(
            id = j.getString("id"),
            name = j.getString("name"),
            description = j.optString("description", ""),
            targetPackage = j.getString("target_package"),
            supportedVersions = j.getJSONArray("supported_versions").toStringList(),
            fingerprint = j.getString("fingerprint"),
            menuItems = j.optJSONArray("menu_items")?.toStringList() ?: emptyList(),
        )
    }

    fun loadAll(rawList: List<String>): List<Patch> = rawList.map { loadFromJson(it) }

    fun verify(patch: Patch, actualFingerprint: String): Boolean =
        patch.fingerprint == actualFingerprint

    fun matches(patch: Patch, installedVersion: String): Boolean =
        installedVersion in patch.supportedVersions

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}