package com.mcpintelligence.fr3k.integrations.openrouter

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.ProviderHealth
import com.mcpintelligence.fr3k.core.ProviderIds
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** A model OpenRouter advertises in its catalogue. [free] == `:free` tier. */
data class OpenRouterModel(val id: String, val name: String, val free: Boolean)

/**
 * OpenRouter AI provider (ANDROID-TESTING-PASS §5).
 *
 * Uses the user's OpenRouter API key from the secure store against the
 * OpenAI-compatible chat completions endpoint. The default model is free
 * routed (`:free` suffix) so a fresh install works without paying; the user
 * can switch to any catalogue model via [setModel] / [refreshFreeModels].
 *
 * The key is obtained from [apiKeyProvider] at call time and is never written
 * to logs, terminal history or UI output (spec §5 "never leak the key").
 * [health] uses the diagnostics wording from spec §10: `NOT_UNCONFIGURED`
 * when the key has not been entered, and explicit failures for invalid key
 * / offline so the Settings panel can surface useful errors.
 */
class OpenRouterProvider(
    private val endpoint: String = "https://openrouter.ai/api/v1",
    private val apiKeyProvider: () -> String?,
    private val defaultModel: String = DEFAULT_MODEL,
) : AiProvider, com.mcpintelligence.fr3k.core.tools.AgentSessionBackend {

    override val id: String = ProviderIds.OPENROUTER
    override val displayName: String = "OpenRouter"
    override val capabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val requiresNetwork: Boolean = true
    override val requiresApiKey: Boolean = true

    @Volatile
    private var currentModel: String = defaultModel

    @Volatile
    private var cachedFreeModels: List<OpenRouterModel> = emptyList()

    /** The model the next [ask] would use. */
    fun selectedModel(): String = currentModel

    /** Switch which model the provider asks against. */
    fun setModel(model: String) {
        if (model.isNotBlank()) currentModel = model
    }

    /** Last fetched free-model list (empty until [refreshFreeModels]). */
    fun cachedFreeModels(): List<OpenRouterModel> = cachedFreeModels

    /** Re-fetch the catalogue and keep only `:free`-routed models. Never throws. */
    suspend fun refreshFreeModels(): Result<List<OpenRouterModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$endpoint/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${apiKeyProvider() ?: ""}")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}")
                }
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val data = JSONObject(raw).getJSONArray("data")
                val models = (0 until data.length()).mapNotNull { i -> data.getJSONObject(i).toModel() }
                cachedFreeModels = models.filter { it.free }
                cachedFreeModels
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun ask(request: AgentAskRequest): AgentAskResponse = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return@withContext AgentAskResponse(
                text = "OpenRouter is not configured: no API key stored. Add one in Settings.",
                model = currentModel,
            )
        }
        runCatching {
            val model = request.model?.takeIf { it.isNotBlank() } ?: currentModel
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", request.prompt) })
                    },
                )
                request.context?.let { put("context", it.toString()) }
                put("max_tokens", request.maxTokens ?: 2048)
            }
            val url = URL("$endpoint/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                val raw = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    val err = conn.errorStream
                        ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
                        ?: ""
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}${if (err.isNotEmpty()) " — ${err.take(200)}" else ""}")
                }
                val resp = JSONObject(raw)
                val choices = resp.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content").orEmpty()
                val reasoning = msg?.optString("reasoning").orEmpty()
                val fromApi = resp.optString("model", model)
                if (text.isBlank() && reasoning.isNotBlank()) {
                    AgentAskResponse(text = reasoning, model = fromApi)
                } else {
                    AgentAskResponse(text = text, model = fromApi)
                }
            } finally {
                conn.disconnect()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { err ->
                AgentAskResponse(
                    text = "OpenRouter error: ${(err.message ?: err::class.java.simpleName).replace(key, "[redacted]")}",
                    model = currentModel,
                )
            },
        )
    }

    /** Native tool calls and results remain in one conversation across all surfaces. */
    override suspend fun complete(
        messages: JSONArray,
        tools: List<com.mcpintelligence.fr3k.core.tools.AgentTool>,
    ): JSONObject = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenRouter is not configured. Add an API key in Settings.")
        val functions = JSONArray()
        tools.forEach { tool ->
            val properties = JSONObject()
            val names = when (tool.capability) {
                "browser" -> listOf("action", "url", "selector", "text", "x", "y")
                "termux" -> listOf("command", "cwd")
                else -> listOf("action", "deviceId", "url")
            }
            names.forEach { properties.put(it, JSONObject().put("type", "string")) }
            functions.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", com.mcpintelligence.fr3k.core.tools.SharedAgentSession.functionName(tool.id))
                .put("description", tool.description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("additionalProperties", false))))
        }
        val body = JSONObject().put("model", currentModel).put("messages", messages)
            .put("stream", false).put("max_tokens", 2048)
        if (tools.isNotEmpty()) body.put("tools", functions).put("tool_choice", "auto").put("parallel_tool_calls", false)
        val conn = (URL("$endpoint/chat/completions").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Keep server bodies out of normal UI: they can echo request secrets.
                throw IllegalStateException(when (code) {
                    401, 403 -> "OpenRouter rejected the key or access (HTTP $code). Check Settings."
                    429 -> "OpenRouter rate limit reached. Try later or select another model."
                    else -> "OpenRouter HTTP $code. The selected model may not support tools; check Settings."
                })
            }
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = JSONObject(raw)
            val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("OpenRouter returned no assistant message")
            check(message.optString("role") == "assistant") { "Invalid assistant response" }
            // Retain provider tool-call IDs and any reasoning metadata required by the model.
            JSONObject(message.toString().replace(key, "[redacted]"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException((e.message ?: "OpenRouter request failed").replace(key, "[redacted]")) }
        finally { conn.disconnect() }
    }

    override suspend fun health(): ProviderHealth = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            ProviderHealth(online = false, model = currentModel, message = "NOT CONFIGURED")
        } else {
            runCatching {
                // The public catalogue accepts invalid keys; authenticate against /key.
                val url = URL("$endpoint/key")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    when (val code = conn.responseCode) {
                        in 200..299 -> {
                            val free = cachedFreeModels.size
                            ProviderHealth(online = true, model = currentModel, message = "$free free models cached")
                        }
                        401 -> ProviderHealth(online = false, model = currentModel, message = "invalid key (HTTP 401)")
                        else -> ProviderHealth(online = false, model = currentModel, message = "HTTP $code")
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { ProviderHealth(online = false, model = currentModel, message = (it.message ?: "offline").replace(key, "[redacted]")) }
        }
    }

    private fun JSONObject.toModel(): OpenRouterModel? {
        val id = optString("id")
        if (id.isBlank()) return null
        val pricing = optJSONObject("pricing")
        val free = pricing?.optString("prompt").orEmpty().toDoubleOrNull() == 0.0 &&
            pricing?.optString("completion").orEmpty().toDoubleOrNull() == 0.0
        return OpenRouterModel(id = id, name = optString("name", id), free = free)
    }

    companion object {
        /**
         * Default free-routed model. OpenRouter serves `:free` (prompt/completion
         * priced at $0) without a paid plan, so a fresh install asks immediately.
         */
        const val DEFAULT_MODEL = "openrouter/free"
    }
}