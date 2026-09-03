package com.mcpintelligence.fr3k.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Common envelope for every FR3K message — local IPC, HTTPS, WebSocket, MQTT, BLE, mesh.
 * versioned via the protocol field. Unversioned messages MUST be rejected.
 *
 * Example:
 *   {
 *     "protocol": "fr3k/1",
 *     "id": "01JABC...",
 *     "source": "fr3k-phone-01",
 *     "destination": "fr3k-dell-01",
 *     "type": "agent.ask",
 *     "timestamp": 1735862345123,
 *     "payload": {...}
 *   }
 */
@Serializable
data class Fr3kEnvelope(
    val protocol: String = PROTOCOL_VERSION,
    val id: String,
    val source: String,
    val destination: String? = null,
    val type: String,
    val timestamp: Long,
    val ttlMs: Long? = null,
    val replyTo: String? = null,
    val correlationId: String? = null,
    val auth: AuthHeader? = null,
    val payload: JsonElement,
) {
    companion object {
        const val PROTOCOL_VERSION: String = "fr3k/1"
    }
}

/**
 * Lightweight authentication header carried inside the envelope.
 * Carries a device fingerprint, an algorithm, and a signature over canonicalized body.
 * Concrete signature schemes are pluggable.
 */
@Serializable
data class AuthHeader(
    val deviceId: String,
    val scheme: String = "ed25519-blake3",
    val signature: String,
    val publicKey: String? = null,
    val nonce: String,
    val issuedAt: Long,
)

/**
 * Standard envelope-level result codes. The same codes are used across the
 * transport layer (HTTP status, WebSocket close codes, MQTT reason codes).
 */
@Serializable
enum class Fr3kResultCode(val code: Int) {
    OK(0),
    UNAUTHORIZED(1),
    FORBIDDEN(2),
    NOT_FOUND(3),
    CAPABILITY_MISSING(4),
    POLICY_DENIED(5),
    TIMEOUT(6),
    CANCELLED(7),
    BAD_REQUEST(8),
    RATE_LIMITED(9),
    OFFLINE(10),
    INTERNAL(11),
    UNSUPPORTED(12);
}

/**
 * Standard envelope-level result envelope returned by transport clients.
 */
@Serializable
data class Fr3kResult(
    val code: Int,
    val message: String? = null,
    val payload: JsonElement? = null,
)

/** Convenience builders and helpers. */
object Fr3kProtocol {
    fun newId(): String = java.util.UUID.randomUUID().toString()
    fun now(): Long = java.lang.System.currentTimeMillis()

    fun agentAsk(
        source: String,
        destination: String?,
        prompt: String,
        context: JsonObject? = null,
    ): Fr3kEnvelope = Fr3kEnvelope(
        id = newId(),
        source = source,
        destination = destination,
        type = "agent.ask",
        timestamp = now(),
        payload = kotlinx.serialization.json.buildJsonObject {
            put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
            if (context != null) put("context", context)
        },
    )
}