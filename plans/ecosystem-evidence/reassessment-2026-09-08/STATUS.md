# Reassessment checkpoint — 2026-09-08

**Later checkpoint correction:** the PluginManager defect described below is now
resolved in current source and existing regression results. See `hud-current.md`
for current lifecycle, settings, transport and capability-ownership reconciliation.
The older bullets are retained as history, not the current defect list. Privacy
enforcement, explicit BLACKWAVE mode, dynamic capability refresh, real handoff and
complete pairing remain outstanding. New plans 008/009 expand authority, migration
and all seven failure configurations. Fresh Hermes review and vetted dispositions
are recorded in `architecture-critical*` and `architecture-review-vetting.md`.

K230 read-only image inspection: `k230-existing-image.json` records the existing
608,174,080-byte native image hash, its RISC-V ELF and nine overlay files. All nine
match current source byte-for-byte. This does not establish vendor app reproducibility,
boot behavior, IPC/media behavior or M5 Launcher compatibility. The K230 build wrapper
now propagates make failures through its logging pipeline; a synthetic failing-build
regression passed with system Python. Repository `.venv` lacks pytest; no installation
was performed. Delegated BLACKWAVE host review hit a usage limit; preliminary new
findings remain unverified leads until independently checked.

This is an incomplete reassessment of the live dirty trees, not a comprehensive
completion claim. The persistent implementation goal was reaffirmed during this
review. Product fixes and binary packaging continue separately from this ledger.

`baseline.json` and the three inventory JSON files record branches, full commits,
working-tree status and nonignored-file hashes. Inventoried does not mean reviewed.
Classification is preliminary: vendor-candidate paths require provenance review.

## Independently checked changes since the September 7 assessment

- HUD `BlackwaveBridgeClient.kt`: canonical health/device routes and
  X-Blackwave-Client header now match gateway `mobile.py`. Original F-01 is resolved
  for these fields; complete pairing/TLS/DTO compatibility remains unproven.
- HUD `BlackwavePlugin.kt`: stop now cancels its own scope and role fetch failure
  clears cachedRole. Earlier F-19 is locally resolved, but PluginManager.unregister
  never calls stop, so shutdown behavior is still defective.
- `core/.../core/Fr3kPlugin.kt:64`: parent coroutine context appears last and replaces
  the newly created supervisor Job; unregister cancels that shared job at line 84.
  Capabilities register before plugin.start at lines 68–70 and are not refreshed
  after role polling. HIGH confidence source findings; runtime regression tests
  still required. Fix effort M, regression risk HIGH (all plugin lifecycle paths).
- `app/.../Fr3kApplication.kt:94,141`: unconditional BLACKWAVE registration persists.
  Optional user-enabled mode is not implemented. HIGH confidence, effort L,
  regression risk HIGH; enabling the UI must never confer fleet authority.
- Cardputer file edit fixes passed 40 host tests and six native targets; final
  safe/lab/diagnostic builds pass. New packages are under BLACKWAVE
  `releases/ecosystem-2026-09-08/file-edit/`. These are binary evidence, not hardware
  or Launcher installation evidence. USB/SD ownership remains unresolved.
- BLACKWAVE companion isolated build finished: nine tests in each variant, debug
  lint zero errors and 62 warnings, debug/release assembly pass. Debug APK v2
  signature verified; release unsigned. No runtime claim follows from this.

## AutoExpert invocation and vetting

Hermes CLI completed specialist critique; exact prompt/output/stderr are retained.
Safe mode disables normal customization injection, so the prompt explicitly
requested AutoExpert's SCAN/SYNTHESIZE/REFLECT and specialist critical review.
The command also requested `--skills autoexpert`; this is not proof that safe mode
loaded the skill automatically.

Reject the output's implication that pairing completion needs an existing bearer:
`pairing.py:158–190` validates the one-time request, nonce, expiry, consumed state
and gateway identity before minting test/client credentials. That endpoint's lack
of a preexisting bearer is intentional, not independently a vulnerability.
Reject a general bearer replay-vulnerability finding without threat-model evidence:
`pairing.py:193–201` checks stored credential digest and active expiry/revocation.
Reject trusting only the pin received in the pairing HTTP response: the pin must
be authenticated from the operator-issued QR before submitting its nonce.
`withContext(IO)` alone also does not make blocking network I/O cancellable.
Confirmed concerns are unbounded HUD response reads, absent configured SPKI trust,
static capability registration and the plugin lifecycle issue described above.

BLACKWAVE delegated reviewer hit a usage limit before delivering a complete ledger.
Its preliminary findings are leads only. Do not promote them to published defects
without independent source and call-path verification. Remaining subsystem coverage,
seven-configuration harnesses, full contracts and final critical review are pending.
