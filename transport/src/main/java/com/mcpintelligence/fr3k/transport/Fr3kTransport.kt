package com.mcpintelligence.fr3k.transport

import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope

/**
 * Transport abstraction (§45). Concrete adapters (HttpsTransport, WebSocketTransport,
 * MeshTransport, BleTransport, MqttTransport) implement this. The orchestrator
 * chooses transports based on capability + policy.
 */
interface Fr3kTransport {
    val id: String
    val displayName: String
    val requiresNetwork: Boolean

    suspend fun start(): Result<Unit>
    suspend fun stop(): Result<Unit>

    suspend fun send(envelope: Fr3kEnvelope): Result<Fr3kEnvelope>
    suspend fun receive(): Result<Fr3kEnvelope>

    fun isAvailable(): Boolean
}

/**
 * Common envelope signing/verification. Concrete signature schemes plug into here.
 */
interface EnvelopeSigner {
    fun sign(envelope: Fr3kEnvelope): String
    fun verify(envelope: Fr3kEnvelope, signature: String): Boolean
}

object NoOpSigner : EnvelopeSigner {
    override fun sign(envelope: Fr3kEnvelope): String = ""
    override fun verify(envelope: Fr3kEnvelope, signature: String): Boolean = true
}