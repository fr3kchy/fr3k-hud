package com.mcpintelligence.fr3k.transport

import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope
import com.mcpintelligence.fr3k.protocol.Fr3kResult
import com.mcpintelligence.fr3k.protocol.Fr3kResultCode
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aggregates every registered transport, exposes a unified outbound send API.
 * Receive operations remain owned by each transport.
 *
 * Failure isolation: one broken transport never blocks the others.
 */
class TransportHub {

    private val transports = LinkedHashMap<String, Fr3kTransport>()
    private val _registered = MutableStateFlow<List<String>>(emptyList())
    val registered: StateFlow<List<String>> = _registered.asStateFlow()

    @Synchronized
    fun register(transport: Fr3kTransport) {
        transports[transport.id] = transport
        _registered.value = transports.keys.toList()
    }

    @Synchronized
    fun unregister(transportId: String) {
        transports.remove(transportId)
        _registered.value = transports.keys.toList()
    }

    @Synchronized
    fun get(transportId: String): Fr3kTransport? = transports[transportId]

    @Synchronized
    private fun snapshot(): List<Fr3kTransport> = transports.values.toList()

    suspend fun startAll() {
        snapshot().forEach {
            try { it.start().exceptionOrNull()?.let { cause -> if (cause is CancellationException) throw cause } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* one unavailable adapter must not block the others */ }
        }
    }

    suspend fun stopAll() {
        snapshot().forEach {
            try { it.stop().exceptionOrNull()?.let { cause -> if (cause is CancellationException) throw cause } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* continue releasing the remaining adapters */ }
        }
    }

    fun available(): List<Fr3kTransport> =
        snapshot().filter { isAvailable(it) }

    private fun isAvailable(transport: Fr3kTransport): Boolean = try {
        transport.isAvailable()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    /**
     * Send via the first available transport whose id appears in [preferred].
     * Falls back only before delivery is attempted. An ambiguous failure must not
     * resend a command via another route: the first peer may already have executed it.
     */
    suspend fun send(envelope: Fr3kEnvelope, preferred: List<String> = listOf("https")): Fr3kResult {
        val registered = snapshot()
        val candidates = preferred.mapNotNull { id -> registered.find { it.id == id } } + registered
        for (transport in candidates.distinctBy { it.id }.filter { isAvailable(it) }) {
            val outcome = try { transport.send(envelope) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: Exception) { Result.failure(cause) }
            val failure = outcome.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure is DeliveryNotAttemptedException) continue
            if (failure != null) {
                return Fr3kResult(
                    code = if (failure is SocketTimeoutException) Fr3kResultCode.TIMEOUT.code else Fr3kResultCode.INTERNAL.code,
                    message = "delivery unconfirmed via ${transport.id}; envelope ${envelope.id} was not retried",
                )
            }
            val response = outcome.getOrThrow()
            if (response.protocol != Fr3kEnvelope.PROTOCOL_VERSION) {
                return Fr3kResult(Fr3kResultCode.UNSUPPORTED.code, "unsupported response protocol")
            }
            return Fr3kResult(code = Fr3kResultCode.OK.code, payload = response.payload)
        }
        return Fr3kResult(
            code = Fr3kResultCode.OFFLINE.code,
            message = "no transport could deliver envelope ${envelope.id}",
        )
    }
}
