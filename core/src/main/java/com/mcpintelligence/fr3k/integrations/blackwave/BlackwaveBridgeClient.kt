package com.mcpintelligence.fr3k.integrations.blackwave

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTP client that talks to the blackwave fleet bridge.
 *
 * Maintains three provider lambdas so the caller can supply endpoint,
 * credential, and client ID reactively (from settings / secure store /
 * identity). Each call to [fetchRole], [fetchFleetStatus], and
 * [fetchDeviceStatus] constructs the request from the current provider values.
 */
class BlackwaveBridgeClient(
    private val endpointProvider: () -> String,
    private val credentialProvider: () -> String?,
    private val clientIdProvider: () -> String,
) {
    /**
     * Fetch the role manifest for this client identity.
     * @return Result with [BlackwaveRoleManifest] on success, failure on error/status <200..
     */
    suspend fun fetchRole(): Result<BlackwaveRoleManifest> {
        val response = get("${endpointProvider()}/mobile/v1/role")
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /**
     * Fetch fleet status: list of all devices in the fleet.
     */
    suspend fun fetchFleetStatus(): Result<FleetStatusResponse> {
        val response = get("${endpointProvider()}/mobile/v1/fleet")
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /**
     * Fetch detailed status for a specific device by model_id.
     */
    suspend fun fetchDeviceStatus(deviceId: String): Result<DeviceStatusResponse> {
        val response = get("${endpointProvider()}/mobile/v1/device/$deviceId")
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /** True if the endpoint resolves and returns a valid response. */
    fun isAvailable(): Boolean {
        return try {
            val response = get("${endpointProvider()}/mobile/v1/ping")
            response.code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Synchronous GET request. Uses [HttpURLConnection] with no
     * third-party dependencies. Sets auth and client-id headers.
     */
    private fun get(urlString: String): HttpResponse {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            credentialProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
            setRequestProperty("X-Client-Id", clientIdProvider())
            // Disable redirect-following so we don't follow an HTTP→HTTPS
            // upgrade we can't verify (trust-on-first-use for LAN certs).
            instanceFollowRedirects = false
        }
        return try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                readStream(conn.inputStream)
            } else {
                readStream(conn.errorStream)
            }
            HttpResponse(code, body)
        } catch (e: Exception) {
            Log.w(TAG, "GET $urlString failed: ${e.message}")
            HttpResponse(0, e.message ?: "unknown error")
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "FR3K.blackwave"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        // Keep for integration tests / DI
        val serializer = { json }
    }
}

/**
 * Response from /mobile/v1/device/{id}.
 */
@Serializable
data class DeviceStatusResponse(
    val identity: Map<String, JsonElement> = emptyMap(),
    val software: Map<String, String> = emptyMap(),
    val connectivity: Map<String, String> = emptyMap(),
    val hardware: List<String> = emptyList(),
    val battery: Map<String, String> = emptyMap(),
    val verification: Map<String, String> = emptyMap(),
)

/**
 * Response from /mobile/v1/fleet.
 */
@Serializable
data class FleetStatusResponse(
    val accounted: Int = 0,
    val online: String = "0/0",
    val devices: List<FleetDeviceCard> = emptyList(),
    val stale: Boolean = false,
)

/**
 * Single device card in the fleet list.
 */
@Serializable
data class FleetDeviceCard(
    val model_id: String = "",
    val display_name: String = "",
    val online: String = "unknown",
    val firmware_version: String = "",
    val enrollment: String = "",
    val device_class: String = "",
    val blackwave_authority: Boolean = false,
)

/**
 * Minimal HTTP response wrapper.
 */
internal data class HttpResponse(
    val code: Int,
    val body: String,
)