package com.mcpintelligence.fr3k.integrations.opencode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Snapshot of one model from the OpenCode Zen catalog.
 */
data class OpenCodeZenModel(
    val id: String,
    val ownedBy: String = "opencode",
    val created: Long = 0L,
    val isFree: Boolean = false,
    val description: String? = null,
)

/**
 * Registry of OpenCode Zen free models. Fetches [https://opencode.ai/zen/v1/models]
 * on first request, caches the result, and exposes a filter for the free
 * subset (model id ends in "-free" or matches a known promo name).
 */
class OpenCodeZenModelRegistry(
    private val endpoint: String = "https://opencode.ai/zen/v1",
    private val bearerToken: String = "public",
) {
    private val mutex = Mutex()

    @Volatile
    private var all: List<OpenCodeZenModel> = emptyList()

    @Volatile
    private var free: List<OpenCodeZenModel> = emptyList()

    fun cachedFree(): List<OpenCodeZenModel> = free
    fun cachedAll(): List<OpenCodeZenModel> = all

    suspend fun refresh(): Result<List<OpenCodeZenModel>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("$endpoint/models")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
                val raw = try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val arr = JSONObject(raw).optJSONArray("data") ?: org.json.JSONArray()
                val allList = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    OpenCodeZenModel(
                        id = o.optString("id"),
                        ownedBy = o.optString("owned_by", "opencode"),
                        created = o.optLong("created", 0L),
                        isFree = isFreeId(o.optString("id")),
                    )
                }.filter { it.id.isNotBlank() }
                val freeList = allList.filter { it.isFree }.sortedBy { it.id }
                all = allList
                free = freeList
                freeList
            }
        }
    }

    companion object {
        /**
         * Heuristic for "is this a free model". OpenCode Zen's model ids
         * follow a few patterns:
         * - `*-free` (e.g. `mimo-v2.5-free`)
         * - `big-pickle` (current free headliner, name is not "-free")
         * - `*-contributor-free` (e.g. `muse-spark-1.3-contributor-free`)
         * - `laguna-s-2.1-free` (not always -free suffix in older snapshots)
         */
        fun isFreeId(id: String): Boolean {
            if (id.isBlank()) return false
            val lower = id.lowercase()
            return lower.endsWith("-free")
                || lower.contains("contributor-free")
                || lower == "big-pickle"
        }
    }
}
