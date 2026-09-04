package com.mcpintelligence.fr3k.integrations.opencode

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.ProviderHealth
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenCode Zen free-model provider.
 *
 * OpenCode Zen ([opencode.ai/zen](https://opencode.ai/zen)) exposes a set of
 * hosted LLM models through an OpenAI-compatible chat completions endpoint at
 * [endpoint]. Free models are accessible with a public bearer key
 * (`Authorization: Bearer public`); no signup, no API key, no card.
 *
 * We default to **big-pickle** because the Zen docs flag it as the current
 * free headliner (the model is promoted to a new state every few weeks — the
 * default tracks whichever model Zen advertises as the headline free model
 * for the current period).
 *
 * Use [setModel] / [availableFreeModels] to inspect or switch between models.
 * The model list is fetched from [modelsEndpoint] on first use and cached
 * in-memory; pull [refreshFreeModels] to force a re-fetch.
 */
class OpenCodeZenProvider(
    private val endpoint: String = "https://opencode.ai/zen/v1",
    private val bearerToken: String = "public",
    private val defaultModel: String = DEFAULT_MODEL,
    private val modelRegistry: OpenCodeZenModelRegistry = OpenCodeZenModelRegistry(endpoint),
) : AiProvider {

    override val id: String = "opencode-zen"
    override val displayName: String = "OpenCode Zen"
    override val capabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val requiresNetwork: Boolean = true
    override val requiresApiKey: Boolean = false

    @Volatile
    private var currentModel: String = defaultModel

    fun setModel(modelId: String) {
        if (modelId.isNotBlank()) currentModel = modelId
    }

    fun selectedModel(): String = currentModel

    fun availableFreeModels(): List<OpenCodeZenModel> = modelRegistry.cachedFree()

    suspend fun refreshFreeModels(): Result<List<OpenCodeZenModel>> = modelRegistry.refresh()

    override suspend fun ask(request: AgentAskRequest): AgentAskResponse = withContext(Dispatchers.IO) {
        runCatching {
            val model = request.model?.takeIf { it.isNotBlank() } ?: currentModel
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONObject.wrap(
                    org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", request.prompt)
                        })
                    }
                ))
                request.context?.let { put("context", it.toString()) }
                put("max_tokens", 2048)
            }
            val url = URL("$endpoint/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                val raw = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}${if (err.isNotEmpty()) " — $err.take(200)" else ""}")
                }
                val resp = JSONObject(raw)
                val choices = resp.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content").orEmpty()
                val reasoning = msg?.optString("reasoning_content").orEmpty()
                val model = resp.optString("model", model)
                if (text.isBlank() && reasoning.isNotBlank()) {
                    AgentAskResponse(text = reasoning, model = model)
                } else {
                    AgentAskResponse(text = text, model = model)
                }
            } finally {
                conn.disconnect()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { err ->
                AgentAskResponse(
                    text = "opencode-zen error: ${err.message ?: err::class.java.simpleName}",
                    model = currentModel,
                )
            }
        )
    }

    override suspend fun health(): ProviderHealth = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$endpoint/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val free = modelRegistry.cachedFree().size
                    ProviderHealth(online = true, model = currentModel, message = "$free free models cached")
                } else {
                    ProviderHealth(online = false, message = "HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { ProviderHealth(online = false, message = it.message ?: "offline") }
    }

    companion object {
        /**
         * The free-model headline for the current Zen promotion period. Big
         * Pickle is the free model currently advertised at the top of
         * [opencode.ai/zen](https://opencode.ai/zen); if it disappears from
         * the live model list, the registry will surface the next free model
         * as a fallback default.
         */
        const val DEFAULT_MODEL = "big-pickle"
    }
}
