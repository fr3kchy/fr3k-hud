package com.mcpintelligence.fr3k.integrations

/**
 * Truth model for a single integration adapter's runtime health.
 *
 * The plan §9: each adapter reports installed / serviceLive /
 * authorised / operational / lastProbeAt / latencyMs / version /
 * failureCode / evidence. UI renders a state green ONLY when
 * [operational] is true. This replaces the pre-broker habit of each
 * screen probing packages/binders/files itself and guessing a label.
 */
sealed class IntegrationState {
    abstract val installed: Boolean
    abstract val serviceLive: Boolean
    abstract val authorised: Boolean
    abstract val latencyMs: Long
    abstract val version: String?
    abstract val failureCode: String?
    abstract val lastProbeAt: Long

    /** True only when the adapter is fully operational → the only green label. */
    open val operational: Boolean get() = false

    object Unknown : IntegrationState() {
        override val installed = false
        override val serviceLive = false
        override val authorised = false
        override val latencyMs = 0L
        override val version: String? = null
        override val failureCode: String? = null
        override val lastProbeAt = 0L
    }

    object Missing : IntegrationState() {
        override val installed = false
        override val serviceLive = false
        override val authorised = false
        override val latencyMs = 0L
        override val version: String? = null
        override val failureCode: String? = null
        override val lastProbeAt = 0L
    }

    data class ServerStarting(
        override val version: String? = null,
        override val lastProbeAt: Long = 0L,
    ) : IntegrationState() {
        override val installed = true
        override val serviceLive = false
        override val authorised = false
        override val latencyMs = 0L
        override val failureCode: String? = "starting"
    }

    data class Partial(
        override val failureCode: String,
        override val version: String? = null,
        override val installed: Boolean = true,
        override val serviceLive: Boolean = true,
        override val latencyMs: Long = 0L,
        override val lastProbeAt: Long = 0L,
    ) : IntegrationState() {
        override val authorised = false
    }

    data class Healthy(
        override val installed: Boolean = true,
        override val serviceLive: Boolean = true,
        override val authorised: Boolean = true,
        override val latencyMs: Long = 0L,
        override val version: String? = null,
        override val failureCode: String? = null,
        override val lastProbeAt: Long = 0L,
    ) : IntegrationState() {
        override val operational = true
    }

    data class Stale(
        override val version: String? = null,
        override val failureCode: String? = "binder_dead",
        override val lastProbeAt: Long = 0L,
    ) : IntegrationState() {
        override val installed = true
        override val serviceLive = false
        override val authorised = false
        override val latencyMs = 0L
    }

    data class Stalled(
        override val version: String? = null,
        override val failureCode: String? = "timeout",
        override val lastProbeAt: Long = 0L,
    ) : IntegrationState() {
        override val installed = true
        override val serviceLive = false
        override val authorised = false
        override val latencyMs = 0L
    }
}

/** Discrete observation a broker raises for one adapter. */
sealed class IntegrationEvent {
    data class Probe(
        val installed: Boolean,
        val serviceLive: Boolean,
        val authorised: Boolean,
        val latencyMs: Long,
        val version: String?,
        val failureCode: String?,
    ) : IntegrationEvent()
    object BinderDied : IntegrationEvent()
    object ProbeTimeout : IntegrationEvent()
    object AppUninstalled : IntegrationEvent()
}

/** Pure reducer: (current, event) -> next. JVM-testable, no side effects. */
object IntegrationStateReducer {
    fun reduce(current: IntegrationState, event: IntegrationEvent): IntegrationState =
        when (event) {
            is IntegrationEvent.Probe ->
                when {
                    !event.installed -> IntegrationState.Missing
                    event.authorised && event.serviceLive -> IntegrationState.Healthy(
                        latencyMs = event.latencyMs,
                        version = event.version,
                        failureCode = event.failureCode,
                        lastProbeAt = System.nanoTime(),
                    )
                    else -> IntegrationState.Partial(
                        failureCode = event.failureCode ?: "permission_required",
                        version = event.version,
                        lastProbeAt = System.nanoTime(),
                    )
                }
            IntegrationEvent.BinderDied -> IntegrationState.Stale(lastProbeAt = System.nanoTime())
            IntegrationEvent.ProbeTimeout -> IntegrationState.Stalled(lastProbeAt = System.nanoTime())
            IntegrationEvent.AppUninstalled -> IntegrationState.Missing
        }
}