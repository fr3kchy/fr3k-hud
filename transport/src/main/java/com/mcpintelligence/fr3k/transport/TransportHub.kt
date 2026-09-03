package com.mcpintelligence.fr3k.transport

import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope
import com.mcpintelligence.fr3k.protocol.Fr3kResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aggregates every registered transport, exposes a unified outbound send API.
 * Inbound channels are exposed per-transport via [inboundFlow].
 *
 * Failure isolation: one broken transport never blocks the others.
 */
class TransportHub {

    private val transports = LinkedHashMap<String, Fr3kTransport>()
    private val _registered = MutableStateFlow<List<String>>(emptyList())
    val registered: StateFlow<List<String>> = _registered.asStateFlow()

    fun register(transport: Fr3kTransport) {
        transports[transport.id] = transport
        _registered.value = transports.keys.toList()
    }

    fun unregister(transportId: String) {
        transports.remove(transportId)
        _registered.value = transports.keys.toList()
    }

    fun get(transportId: String): Fr3kTransport? = transports[transportId]

    suspend fun startAll() {
        transports.values.forEach {
            runCatching { it.start() }
        }
    }

    suspend fun stopAll() {
        transports.values.forEach {
            runCatching { it.stop() }
        }
    }

    fun available(): List<Fr3kTransport> =
        transports.values.filter { it.isAvailable() }

    /**
     * Send via the first available transport whose id appears in [preferred].
     * Falls back to any available transport. Returns a unified [Fr3kResult].
     */
    suspend fun send(envelope: Fr3kEnvelope, preferred: List<String> = listOf("https")): Fr3kResult {
        val candidates = preferred.mapNotNull { transports[it] } + available()
        for (transport in candidates.distinctBy { it.id }) {
            val outcome = runCatching { transport.send(envelope) }
            outcome
                .onSuccess { return com.mcpintelligence.fr3k.protocol.Fr3kResult(code = 0, payload = kotlinx.serialization.json.JsonNull) }
                .onFailure { /* try next */ }
        }
        return com.mcpintelligence.fr3k.protocol.Fr3kResult(
            code = com.mcpintelligence.fr3k.protocol.Fr3kResultCode.OFFLINE.code,
            message = "no transport could deliver envelope ${envelope.id}",
        )
    }
}