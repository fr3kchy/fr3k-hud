package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tier-0 capability registry: source of truth for what FR3K can currently do.
 *
 * Plugins register capabilities at start(); they are removed at stop().
 * UI must read capabilities from [snapshot] and never display actions that
 * require missing capabilities unless explicitly labelled "unavailable".
 *
 * Failure isolation: plugins register under an [ownerId]; teardown removes
 * only that owner's capabilities, so a misbehaving Meshtastic plugin can never
 * disable Hermes.
 */
class CapabilityRegistry {

    private data class Entry(val capability: Capability, val ownerId: String)

    private val entriesMap = LinkedHashMap<String, Entry>()
    private val registrations = LinkedHashMap<String, LinkedHashMap<String, Capability>>()
    private val ownerIndex = HashMap<String, MutableSet<String>>()

    private val _snapshot = MutableStateFlow<Map<String, Capability>>(emptyMap())
    val snapshot: StateFlow<Map<String, Capability>> = _snapshot

    @Synchronized
    fun register(ownerId: String, capability: Capability) {
        put(ownerId, capability)
        ownerIndex.getOrPut(ownerId) { LinkedHashSet() }.add(capability.id)
        publish()
    }

    @Synchronized
    fun registerAll(ownerId: String, capabilities: Iterable<Capability>) {
        val owned = ownerIndex.getOrPut(ownerId) { LinkedHashSet() }
        capabilities.forEach { cap ->
            put(ownerId, cap)
            owned.add(cap.id)
        }
        publish()
    }

    @Synchronized
    fun unregisterAllByOwner(ownerId: String) {
        val owned = ownerIndex.remove(ownerId) ?: return
        owned.forEach { id ->
            val providers = registrations[id] ?: return@forEach
            providers.remove(ownerId)
            val remaining = providers.entries.lastOrNull()
            if (remaining == null) {
                registrations.remove(id)
                entriesMap.remove(id)
            } else {
                entriesMap[id] = Entry(remaining.value, remaining.key)
            }
        }
        publish()
    }

    @Synchronized
    fun unregister(capabilityId: String) {
        entriesMap.remove(capabilityId)
        registrations.remove(capabilityId)
        ownerIndex.values.forEach { it.remove(capabilityId) }
        publish()
    }

    @Synchronized
    fun has(capabilityId: String): Boolean = entriesMap.containsKey(capabilityId)

    @Synchronized
    fun hasAll(capabilityIds: Collection<String>): Boolean =
        capabilityIds.all { has(it) }

    @Synchronized
    fun hasAny(capabilityIds: Collection<String>): Boolean =
        capabilityIds.any { has(it) }

    @Synchronized
    fun capabilitiesAtTier(tier: CapabilityTier): List<Capability> =
        entriesMap.values.map { it.capability }.filter { it.tier == tier }

    @Synchronized
    fun missingFor(capabilityIds: Collection<String>): List<String> =
        capabilityIds.filterNot { has(it) }

    @Synchronized
    fun ownerOf(capabilityId: String): String? = entriesMap[capabilityId]?.ownerId

    fun changes(): Flow<Map<String, Capability>> = snapshot

    @Synchronized
    fun clear() {
        entriesMap.clear()
        registrations.clear()
        ownerIndex.clear()
        publish()
    }

    private fun publish() {
        _snapshot.value = entriesMap.mapValues { (_, e) -> e.capability }
    }

    /** Latest registration supplies presentation metadata; every owner retains its claim. */
    private fun put(ownerId: String, capability: Capability) {
        val providers = registrations.getOrPut(capability.id) { LinkedHashMap() }
        providers.remove(ownerId)
        providers[ownerId] = capability
        entriesMap[capability.id] = Entry(capability, ownerId)
    }
}
