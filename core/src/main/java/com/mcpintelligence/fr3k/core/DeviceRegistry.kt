package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.DeviceManifest
import com.mcpintelligence.fr3k.protocol.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local cache of device manifests discovered in the FR3K fleet.
 *
 * Implementations (LAN mDNS, QR pairing, MeshCore, Meshtastic, MQTT) push
 * manifests here. UI reads [snapshot] and reacts. The application is the
 * single source of truth — never trust LAN presence alone (§44).
 */
class DeviceRegistry {

    private val mutex = Mutex()
    private val devices = LinkedHashMap<String, DeviceManifest>()

    private val _snapshot = MutableStateFlow<List<DeviceManifest>>(emptyList())
    val snapshot: StateFlow<List<DeviceManifest>> = _snapshot.asStateFlow()

    suspend fun upsert(manifest: DeviceManifest) = mutex.withLock {
        devices[manifest.deviceId] = manifest.copy(lastSeen = System.currentTimeMillis())
        publish()
    }

    suspend fun remove(deviceId: String) = mutex.withLock {
        devices.remove(deviceId)
        publish()
    }

    suspend fun setStatus(deviceId: String, status: DeviceStatus) = mutex.withLock {
        val existing = devices[deviceId] ?: return@withLock
        devices[deviceId] = existing.copy(status = status, lastSeen = System.currentTimeMillis())
        publish()
    }

    suspend fun get(deviceId: String): DeviceManifest? = mutex.withLock { devices[deviceId] }

    suspend fun all(): List<DeviceManifest> = mutex.withLock { devices.values.toList() }

    suspend fun online(): List<DeviceManifest> =
        all().filter { it.status == DeviceStatus.ONLINE }

    fun changes(): Flow<List<DeviceManifest>> = snapshot

    private fun publish() {
        _snapshot.value = devices.values.sortedBy { it.deviceId }
    }
}