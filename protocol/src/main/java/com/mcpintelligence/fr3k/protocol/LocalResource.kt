package com.mcpintelligence.fr3k.protocol

import kotlinx.serialization.Serializable

/** A typed resource owned by a node-local supervisor, not a global worker. */
@Serializable
data class LocalResource(
    val resourceId: String,
    val kind: LocalResourceKind,
    val capabilities: List<String> = emptyList(),
    val transport: String,
    val present: Boolean = true,
    val verified: Boolean = false,
    val evidenceRefs: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val schedulerEligible: Boolean
        get() = present && verified && kind.isComputeCapable
}

@Serializable
enum class LocalResourceKind(val isComputeCapable: Boolean) {
    LINUX_WORKER(true),
    ANDROID_WORKER(true),
    EMBEDDED_DEVICE(false),
    INSTRUMENT(false),
}

/** Local subtree projection; global scheduling and leases remain fleet-control concerns. */
@Serializable
data class LocalSupervisorSnapshot(
    val schema: String = "fr3k.local-supervisor.v1",
    val nodeId: String,
    val globalScheduler: Boolean = false,
    val resources: List<LocalResource> = emptyList(),
)
