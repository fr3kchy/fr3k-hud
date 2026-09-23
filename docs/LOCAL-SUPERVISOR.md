# HUD local-supervisor projection

`protocol/LocalResource.kt` is the Android wire contract for a node-local resource projection. It preserves resource kind, capability IDs, presence, verification state, evidence references, transport and metadata.

`LocalResource.schedulerEligible` is true only for present, verified Linux/Android workers. Embedded devices and instruments are always resource-only. `LocalSupervisorSnapshot.globalScheduler` is fixed false: HUD renders capability state but cannot grant global ownership or select a global job.

Global task eligibility and leases remain the responsibility of the single fleet-control authority. HUD/CORTEX may request local discovery or display resource state; they must not infer global authority from a capability manifest.
