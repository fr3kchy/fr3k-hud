package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.serialization.Serializable

/**
 * Role manifest served by the blackwave fleet bridge at /mobile/v1/role.
 * Defines what scopes and capabilities this identity has been granted.
 */
@Serializable
data class BlackwaveRoleManifest(
    val schema_version: Int = 1,
    val role_id: String = "",
    val identity: String = "",
    val trust_tier: String = "untrusted",
    val allowed_scopes: List<String> = emptyList(),
    val device_access: String = "none",
    val approval_required: List<String> = emptyList(),
    val delegation_depth: Int = 0,
    val max_session_ttl_s: Int = 300,
    val issued_at: String = "",
    val expires_at: String? = null,
    val issuer: String = "",
    val revocation_epoch: Int = 0,
    val capabilities_map: Map<String, String> = emptyMap(),
) {
    val isExpired: Boolean
        get() = isExpiredAt(System.currentTimeMillis())

    /** Gateway roles permit null expiry; malformed non-null dates fail closed. */
    fun isExpiredAt(nowMillis: Long): Boolean {
        val expiry = expires_at ?: return false
        return try {
            nowMillis >= java.time.Instant.parse(expiry).toEpochMilli()
        } catch (_: Exception) {
            true
        }
    }

    fun capabilityIdForScope(scope: String): String? = capabilities_map[scope]

}
