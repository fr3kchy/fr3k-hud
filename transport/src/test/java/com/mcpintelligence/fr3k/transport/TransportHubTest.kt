package com.mcpintelligence.fr3k.transport

import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope
import com.mcpintelligence.fr3k.protocol.Fr3kResultCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class TransportHubTest {
    private val request = Fr3kEnvelope(id = "request-1", source = "local", type = "status.read",
        timestamp = 1, payload = JsonPrimitive("request"))
    private val response = request.copy(id = "reply-1", replyTo = request.id, payload = JsonPrimitive("reply"))

    private class Adapter(
        override val id: String,
        private val available: Boolean = true,
        private val deliver: () -> Result<Fr3kEnvelope>,
    ) : Fr3kTransport {
        override val displayName = id
        override val requiresNetwork = false
        var sends = 0
        override fun isAvailable() = available
        override suspend fun start() = Result.success(Unit)
        override suspend fun stop() = Result.success(Unit)
        override suspend fun receive() = Result.failure<Fr3kEnvelope>(UnsupportedOperationException())
        override suspend fun send(envelope: Fr3kEnvelope): Result<Fr3kEnvelope> { sends++; return deliver() }
    }

    @Test fun returnedFailureIsNotSuccessAndDoesNotRetry() = runBlocking {
        val hub = TransportHub()
        val first = Adapter("https") { Result.failure(SocketTimeoutException()) }
        val second = Adapter("ble") { Result.success(response) }
        hub.register(first); hub.register(second)
        val result = hub.send(request)
        assertEquals(Fr3kResultCode.TIMEOUT.code, result.code)
        assertEquals(1, first.sends)
        assertEquals(0, second.sends)
        assertNull(result.payload)
    }

    @Test fun thrownAmbiguousFailureAlsoDoesNotRetry() = runBlocking {
        val hub = TransportHub()
        val first = Adapter("https") { error("peer may have received request") }
        val second = Adapter("ble") { Result.success(response) }
        hub.register(first); hub.register(second)
        assertEquals(Fr3kResultCode.INTERNAL.code, hub.send(request).code)
        assertEquals(0, second.sends)
    }

    @Test fun explicitNonDeliveryAllowsFallbackAndPreservesResponse() = runBlocking {
        val hub = TransportHub()
        val first = Adapter("https") { Result.failure(DeliveryNotAttemptedException("disconnected before send")) }
        val second = Adapter("ble") { Result.success(response) }
        hub.register(first); hub.register(second)
        val result = hub.send(request, listOf("https", "https"))
        assertEquals(Fr3kResultCode.OK.code, result.code)
        assertEquals(response.payload, result.payload)
        assertEquals(1, first.sends)
        assertEquals(1, second.sends)
    }

    @Test fun unavailablePreferredTransportIsSkipped() = runBlocking {
        val hub = TransportHub()
        val first = Adapter("https", available = false) { error("must not send") }
        val second = Adapter("ble") { Result.success(response) }
        hub.register(first); hub.register(second)
        assertEquals(Fr3kResultCode.OK.code, hub.send(request).code)
        assertEquals(0, first.sends)
    }

    @Test fun noAvailableRouteReportsOffline() = runBlocking {
        assertEquals(Fr3kResultCode.OFFLINE.code, TransportHub().send(request).code)
    }

    @Test fun cancellationIsPropagatedForThrownAndReturnedFailures() {
        for (returned in listOf(true, false)) {
            val hub = TransportHub()
            val first = Adapter("https") {
                if (returned) Result.failure(CancellationException("cancelled"))
                else throw CancellationException("cancelled")
            }
            val second = Adapter("ble") { Result.success(response) }
            hub.register(first); hub.register(second)
            assertThrows(CancellationException::class.java) { runBlocking { hub.send(request) } }
            assertEquals(0, second.sends)
        }
    }

    @Test fun incompatibleResponseDoesNotTriggerAnotherDelivery() = runBlocking {
        val hub = TransportHub()
        val second = Adapter("ble") { Result.success(response) }
        hub.register(Adapter("https") { Result.success(response.copy(protocol = "fr3k/999")) })
        hub.register(second)
        assertEquals(Fr3kResultCode.UNSUPPORTED.code, hub.send(request).code)
        assertEquals(0, second.sends)
    }
}
