package com.mcpintelligence.fr3k.transport

import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTPS transport to a Hermes / FR3K endpoint. Uses Android's built-in
 * HttpURLConnection so we don't pull in OkHttp unless we need WebSockets.
 *
 * The endpoint URL is supplied by [endpointProvider] so the policy layer can
 * route private vs. research traffic to different hosts without leaking.
 */
class HttpsTransport(
    private val endpointProvider: () -> String,
    private val authTokenProvider: () -> String? = { null },
    private val signer: EnvelopeSigner = NoOpSigner,
) : Fr3kTransport {

    override val id: String = "https"
    override val displayName: String = "HTTPS"
    override val requiresNetwork: Boolean = true

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun start(): Result<Unit> = Result.success(Unit)
    override suspend fun stop(): Result<Unit> = Result.success(Unit)

    override suspend fun send(envelope: Fr3kEnvelope): Result<Fr3kEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = endpointProvider()
            val url = URL("$endpoint/envelope")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Fr3k-Protocol", envelope.protocol)
                setRequestProperty("X-Fr3k-Source", envelope.source)
                authTokenProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json.encodeToString(Fr3kEnvelope.serializer(), envelope))
                    writer.flush()
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val body = connection.errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    } ?: ""
                    throw RuntimeException("HTTP $code: ${connection.responseMessage}${if (body.isNotEmpty()) " — $body" else ""}")
                }
                val raw = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                json.decodeFromString(Fr3kEnvelope.serializer(), raw)
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun receive(): Result<Fr3kEnvelope> =
        Result.failure(UnsupportedOperationException("HTTPS receive is server-driven"))

    override fun isAvailable(): Boolean = runCatching {
        val ep = endpointProvider()
        ep.startsWith("http://") || ep.startsWith("https://")
    }.getOrDefault(false)
}